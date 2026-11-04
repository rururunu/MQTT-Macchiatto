package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttProperties;
import io.github.rururunu.mqttmacchiatto.internal.Frame;
import io.github.rururunu.mqttmacchiatto.internal.TopicFilter;
import io.github.rururunu.mqttmacchiatto.internal.V5;
import io.github.rururunu.mqttmacchiatto.internal.WebSocketCodec;
import io.github.rururunu.mqttmacchiatto.internal.Wire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process MQTT 3.1.1 and MQTT 5 broker for tests and benchmarks.
 * One connection is served by one thread. Not a supported API.
 */
public final class TestBroker implements AutoCloseable {
    private static final byte[] DROP = new byte[]{'d', 'r', 'o', 'p'};
    private static final byte[] BOOT = new byte[]{'b', 'o', 'o', 't'};
    private static final byte[] STALL = new byte[]{'s', 't', 'a', 'l', 'l'};
    private static final byte[] INJECT = new byte[]{'i', 'n', 'j', 'e', 'c', 't'};
    /** TCP reads must hold one frame at the client default of 1 MiB. WebSocket benches stay small. */
    private static final int INBOUND_BYTES = 2 * 1024 * 1024;

    private final ServerSocketChannel server;
    private final CopyOnWriteArrayList<Conn> connections = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, SessionMem> sessions = new ConcurrentHashMap<>();
    private final Thread acceptor;
    private volatile boolean open = true;
    /** When the CONNECT protocol level matches, the broker answers reason 1 and closes. */
    public volatile int refuseProtocolLevel = -1;
    /** Non-negative CONNACK code refuses every connect. {@code -1} accepts. */
    public volatile int refuseConnAck = -1;
    /** QoS 1 PUBACK is not written, so the publish stays in flight. */
    public volatile boolean holdAcks;
    public volatile String lastWebSocketRequest;
    public volatile int lastSubscriptionId;
    public volatile byte[] lastAuthData;

    public TestBroker() throws IOException {
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        acceptor = Thread.ofPlatform().name("macchiatto-test-broker").start(this::acceptLoop);
    }

    public String endpoint() {
        return "tcp://127.0.0.1:" + server.socket().getLocalPort();
    }

    public String webSocketEndpoint() {
        return "ws://127.0.0.1:" + server.socket().getLocalPort() + "/mqtt";
    }

    private void acceptLoop() {
        while (open) {
            try {
                SocketChannel channel = server.accept();
                if (channel == null) {
                    return;
                }
                channel.configureBlocking(true);
                channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                Conn conn = new Conn(channel);
                connections.add(conn);
                Thread.ofVirtual().start(conn);
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public void close() {
        open = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        for (Conn conn : connections) {
            conn.close();
        }
        acceptor.interrupt();
    }

    private final class Conn implements Runnable {
        private final SocketChannel channel;
        private final List<TopicFilter> filters = new ArrayList<>();
        private final List<Held> qos2 = new ArrayList<>();
        private final ByteBuffer inbound = ByteBuffer.allocate(INBOUND_BYTES);
        private final ByteBuffer raw = ByteBuffer.allocate(64 * 1024);
        private final ByteBuffer gathered = ByteBuffer.allocate(64 * 1024);
        private final ByteBuffer ack4 = ByteBuffer.allocate(4);
        private final Frame frame = new Frame();
        private final V5.View view = new V5.View();
        private final WebSocketCodec webSocket = new WebSocketCodec(false);
        private final String[] aliases = new String[17];
        private final Object writeLock = new Object();
        private boolean websocket;
        private boolean mqtt5;
        private boolean durable;
        private boolean awaitingAuth;
        private int subscriptionId;
        private SessionMem mem = new SessionMem();
        private String willTopic;
        private byte[] willPayload = new byte[0];
        private boolean willRetain;
        private boolean suppressWill;

        Conn(SocketChannel channel) {
            this.channel = channel;
            inbound.limit(0);
        }

        @Override
        public void run() {
            try {
                if (!preface()) {
                    return;
                }
                while (open && channel.isOpen()) {
                    if (!fillMqtt()) {
                        break;
                    }
                    while (inbound.hasRemaining()) {
                        int mark = inbound.position();
                        int body = Wire.openFrame(inbound, frame, Integer.MAX_VALUE);
                        if (body < 0) {
                            inbound.position(mark);
                            break;
                        }
                        int end = inbound.position() + body;
                        int limit = inbound.limit();
                        inbound.limit(end);
                        try {
                            handle(end);
                        } finally {
                            inbound.limit(limit);
                            inbound.position(end);
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                connections.remove(this);
                if (!suppressWill && willTopic != null) {
                    fanout(willTopic, willPayload, willRetain, null);
                }
                close();
            }
        }

        private boolean preface() throws IOException {
            ByteBuffer one = ByteBuffer.allocate(1);
            if (channel.read(one) < 0) {
                return false;
            }
            one.flip();
            byte first = one.get();
            if (first != 'G') {
                inbound.clear();
                inbound.put(first);
                inbound.flip();
                return true;
            }
            websocket = true;
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            headers.write('G');
            ByteBuffer chunk = ByteBuffer.allocate(1024);
            while (true) {
                int read = channel.read(chunk);
                if (read < 0) {
                    return false;
                }
                chunk.flip();
                byte[] bytes = new byte[chunk.remaining()];
                chunk.get(bytes);
                chunk.clear();
                headers.write(bytes);
                byte[] all = headers.toByteArray();
                int end = indexOf(all, new byte[]{'\r', '\n', '\r', '\n'});
                if (end < 0) {
                    continue;
                }
                String text = new String(all, 0, end, StandardCharsets.US_ASCII);
                lastWebSocketRequest = text;
                String key = WebSocketCodec.header(text, "Sec-WebSocket-Key");
                ByteBuffer response = ByteBuffer.wrap(WebSocketCodec.serverResponse(key).getBytes(StandardCharsets.US_ASCII));
                while (response.hasRemaining()) {
                    channel.write(response);
                }
                if (all.length > end) {
                    raw.put(all, end, all.length - end);
                }
                inbound.clear();
                inbound.limit(0);
                return true;
            }
        }

        private boolean fillMqtt() throws IOException {
            if (!websocket) {
                inbound.compact();
                int read = channel.read(inbound);
                if (read < 0 && inbound.position() == 0) {
                    return false;
                }
                inbound.flip();
                return true;
            }
            int read = channel.read(raw);
            if (read < 0 && raw.position() == 0) {
                return false;
            }
            if (!webSocket.pull(raw, gathered)) {
                return false;
            }
            if (webSocket.hasControl()) {
                ByteBuffer control = webSocket.control();
                while (control.hasRemaining()) {
                    channel.write(control);
                }
                control.clear();
                control.limit(0);
            }
            inbound.compact();
            gathered.flip();
            if (inbound.remaining() < gathered.remaining()) {
                return false;
            }
            inbound.put(gathered);
            gathered.clear();
            inbound.flip();
            return true;
        }

        private void handle(int end) throws IOException {
            switch (frame.type) {
                case Wire.CONNECT -> onConnect();
                case Wire.SUBSCRIBE -> onSubscribe();
                case Wire.UNSUBSCRIBE -> onUnsubscribe();
                case Wire.PUBLISH -> onPublish(end);
                case Wire.PUBREL -> {
                    int packetId = Wire.readUnsignedShort(inbound);
                    Held held = takeQos2(packetId);
                    writeAck(Wire.PUBCOMP, packetId);
                    if (held != null) {
                        fanout(held.topic, held.payload, held.retain, held.properties);
                    }
                }
                case Wire.PUBREC -> onPubRec();
                case Wire.PUBCOMP -> onPubComp();
                case Wire.PINGREQ -> writeOwn(bytes(buf -> Wire.encodePingResp(buf)));
                case Wire.PUBACK, Wire.SUBACK, Wire.UNSUBACK, Wire.PINGRESP -> {
                }
                case Wire.DISCONNECT -> {
                    suppressWill = true;
                    channel.close();
                }
                case 15 -> onAuth();
                default -> channel.close();
            }
        }

        private void onConnect() throws IOException {
            Wire.readString(inbound);
            int level = inbound.get() & 0xFF;
            int flags = inbound.get() & 0xFF;
            Wire.readUnsignedShort(inbound);
            mqtt5 = level == 5;
            if (level == refuseProtocolLevel) {
                writeOwn(bytes(buf -> Wire.encodeConnAck(buf, false, 1)));
                channel.close();
                return;
            }
            if (refuseConnAck >= 0) {
                int code = refuseConnAck;
                if (mqtt5) {
                    writeOwn(bytes(buf -> V5.encodeConnAck(buf, false, code, 128, 8)));
                } else {
                    writeOwn(bytes(buf -> Wire.encodeConnAck(buf, false, code)));
                }
                channel.close();
                return;
            }
            if (mqtt5) {
                view.reset();
                V5.readProperties(inbound, view);
            }
            String clientId = Wire.readString(inbound);
            if ((flags & 0x04) != 0) {
                if (mqtt5) {
                    view.reset();
                    V5.readProperties(inbound, view);
                }
                willTopic = Wire.readString(inbound);
                willPayload = Wire.readBytes(inbound, Wire.readUnsignedShort(inbound));
                willRetain = (flags & 0x20) != 0;
            }
            boolean clean = (flags & 0x02) != 0;
            boolean present = false;
            if (clean) {
                sessions.remove(clientId);
                mem = new SessionMem();
                durable = false;
            } else {
                SessionMem existing = sessions.get(clientId);
                durable = true;
                if (existing == null) {
                    mem = new SessionMem();
                    sessions.put(clientId, mem);
                } else {
                    mem = existing;
                    present = true;
                    filters.addAll(mem.filters);
                }
            }
            boolean resume = present;
            if (mqtt5 && view.authMethod != null) {
                String method = view.authMethod;
                writeOwn(bytes(buf -> V5.encodeAuth(buf, 0x18, method, new byte[]{1, 2, 3})));
                awaitingAuth = true;
                return;
            }
            sendConnAck(resume);
            if (resume && mem.pendingRel != 0) {
                int packetId = mem.pendingRel;
                writeAck(Wire.PUBREL, packetId);
            }
        }

        private void sendConnAck(boolean resume) {
            if (mqtt5) {
                writeOwn(bytes(buf -> V5.encodeConnAck(buf, resume, 0, 128, 8)));
            } else {
                writeOwn(bytes(buf -> Wire.encodeConnAck(buf, resume, 0)));
            }
        }

        private void onAuth() {
            if (inbound.hasRemaining()) {
                inbound.get();
            }
            view.reset();
            if (inbound.hasRemaining()) {
                V5.readProperties(inbound, view);
            }
            lastAuthData = view.authData == null ? new byte[0] : view.authData.clone();
            awaitingAuth = false;
            sendConnAck(false);
        }

        private void onSubscribe() throws IOException {
            int packetId = Wire.readUnsignedShort(inbound);
            if (mqtt5) {
                view.reset();
                V5.readProperties(inbound, view);
                if (view.subscriptionIds.length > 0) {
                    subscriptionId = view.subscriptionIds[0];
                    lastSubscriptionId = subscriptionId;
                }
            }
            List<Integer> granted = new ArrayList<>();
            while (inbound.hasRemaining()) {
                String filter = Wire.readString(inbound);
                int qos = inbound.get() & 0x03;
                TopicFilter compiled = TopicFilter.compile(filter);
                filters.add(compiled);
                if (durable) {
                    mem.filters.add(compiled);
                }
                granted.add(qos);
            }
            int[] reasons = new int[granted.size()];
            for (int i = 0; i < reasons.length; i++) {
                reasons[i] = granted.get(i);
            }
            if (mqtt5) {
                writeOwn(bytes(buf -> V5.encodeSubAck(buf, packetId, reasons)));
            } else {
                writeOwn(bytes(buf -> Wire.encodeSubAck(buf, packetId, reasons)));
            }
        }

        private void onUnsubscribe() throws IOException {
            int packetId = Wire.readUnsignedShort(inbound);
            if (mqtt5) {
                view.reset();
                V5.readProperties(inbound, view);
            }
            String filter = Wire.readString(inbound);
            filters.removeIf(candidate -> candidate.text().equals(filter));
            if (durable) {
                mem.filters.removeIf(candidate -> candidate.text().equals(filter));
            }
            if (mqtt5) {
                writeOwn(bytes(buf -> V5.encodeUnsubAck(buf, packetId)));
            } else {
                writeOwn(bytes(buf -> Wire.encodeUnsubAck(buf, packetId)));
            }
        }

        private void onPublish(int end) throws IOException {
            int qos = (frame.flags >>> 1) & 0x03;
            boolean retain = (frame.flags & 0x01) != 0;
            int topicLength = Wire.readUnsignedShort(inbound);
            int topicStart = inbound.position();
            boolean drop = topicEquals(inbound, topicStart, topicLength, DROP);
            boolean boot = topicEquals(inbound, topicStart, topicLength, BOOT);
            boolean stall = topicEquals(inbound, topicStart, topicLength, STALL);
            boolean inject = topicEquals(inbound, topicStart, topicLength, INJECT);
            inbound.position(topicStart + topicLength);
            int packetId = qos == 0 ? 0 : Wire.readUnsignedShort(inbound);
            MqttProperties properties = null;
            int alias = 0;
            if (mqtt5) {
                view.reset();
                V5.readProperties(inbound, view);
                properties = view.toProperties();
                alias = view.topicAlias;
            }
            if (drop || boot) {
                if (boot && mqtt5) {
                    writeOwn(new byte[]{(byte) 0xE0, 0x02, (byte) 0x98, 0x00});
                }
                channel.close();
                return;
            }
            if (!stall && !inject && !anyFilter()) {
                if (qos == 1) {
                    writeAck(Wire.PUBACK, packetId);
                } else if (qos == 2) {
                    writeAck(Wire.PUBREC, packetId);
                }
                inbound.position(end);
                return;
            }
            byte[] topicBytes = new byte[topicLength];
            for (int i = 0; i < topicLength; i++) {
                topicBytes[i] = inbound.get(topicStart + i);
            }
            String topic = topicLength == 0 ? null : new String(topicBytes, StandardCharsets.UTF_8);
            if (alias > 0) {
                if (topic != null) {
                    if (alias < aliases.length) {
                        aliases[alias] = topic;
                    }
                } else if (alias < aliases.length) {
                    topic = aliases[alias];
                }
            }
            if (topic == null) {
                channel.close();
                return;
            }
            byte[] payload = Wire.readBytes(inbound, end - inbound.position());
            if (stall && qos == 2) {
                writeAck(Wire.PUBREC, packetId);
                channel.close();
                return;
            }
            if (inject) {
                armInject();
            }
            if (qos == 1) {
                writeAck(Wire.PUBACK, packetId);
                fanout(topic, payload, retain, properties);
            } else if (qos == 2) {
                qos2.add(new Held(packetId, topic, payload, retain, properties));
                writeAck(Wire.PUBREC, packetId);
            } else {
                fanout(topic, payload, retain, properties);
            }
        }

        private void armInject() throws IOException {
            byte[] payload = new byte[]{7};
            mem.pendingRel = 9;
            if (mqtt5) {
                writeOwn(bytes(buf -> V5.encodePublish(buf, Wire.utf8("injected"), payload, 2, false, false, 9, 0, null)));
            } else {
                writeOwn(bytes(buf -> Wire.encodePublish(buf, Wire.utf8("injected"), payload, 2, false, false, 9)));
            }
            mem.waitingForPubRec = true;
        }

        private void onPubRec() throws IOException {
            int packetId = Wire.readUnsignedShort(inbound);
            if (mem.waitingForPubRec && packetId == 9) {
                mem.waitingForPubRec = false;
                channel.close();
            }
        }

        private void onPubComp() {
            int packetId = Wire.readUnsignedShort(inbound);
            if (mem.pendingRel == packetId) {
                mem.pendingRel = 0;
            }
        }

        private Held takeQos2(int packetId) {
            for (int i = 0; i < qos2.size(); i++) {
                if (qos2.get(i).packetId == packetId) {
                    return qos2.remove(i);
                }
            }
            return null;
        }

        private void fanout(String topic, byte[] payload, boolean retain, MqttProperties properties) {
            byte[] v3 = null;
            for (Conn conn : connections) {
                if (!conn.matches(topic)) {
                    continue;
                }
                if (conn.mqtt5) {
                    MqttProperties forwarded = properties;
                    int id = conn.subscriptionId;
                    conn.writeOwn(bytes(buf -> V5.encodePublish(buf, Wire.utf8(topic), payload, 0, retain, false, 0, 0,
                            forwarded, id)));
                } else {
                    if (v3 == null) {
                        v3 = bytes(buf -> Wire.encodePublish(buf, Wire.utf8(topic), payload, 0, retain, false, 0));
                    }
                    conn.writeOwn(v3);
                }
            }
        }

        private boolean anyFilter() {
            for (Conn conn : connections) {
                if (!conn.filters.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        private boolean matches(String topic) {
            for (int i = 0; i < filters.size(); i++) {
                if (filters.get(i).matches(topic)) {
                    return true;
                }
            }
            return false;
        }

        private void writeAck(int type, int packetId) {
            if (holdAcks && type == Wire.PUBACK) {
                return;
            }
            ack4.clear();
            Wire.encodeAck(ack4, type, packetId);
            ack4.flip();
            writeMqtt(ack4);
        }

        private void writeOwn(byte[] packet) {
            writeMqtt(ByteBuffer.wrap(packet));
        }

        private void writeMqtt(ByteBuffer mqtt) {
            synchronized (writeLock) {
                try {
                    if (!websocket) {
                        while (mqtt.hasRemaining() && channel.isOpen()) {
                            channel.write(mqtt);
                        }
                        return;
                    }
                    ByteBuffer framed = ByteBuffer.allocate(mqtt.remaining() + 14);
                    webSocket.encodeBinary(mqtt, framed);
                    while (framed.hasRemaining() && channel.isOpen()) {
                        channel.write(framed);
                    }
                } catch (IOException ignored) {
                }
            }
        }

        private void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class SessionMem {
        private final List<TopicFilter> filters = new ArrayList<>();
        private int pendingRel;
        private boolean waitingForPubRec;
    }

    private record Held(int packetId, String topic, byte[] payload, boolean retain, MqttProperties properties) {
    }

    private static boolean topicEquals(ByteBuffer inbound, int start, int length, byte[] expected) {
        if (length != expected.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (inbound.get(start + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i + pattern.length;
        }
        return -1;
    }

    private static byte[] bytes(Writer writer) {
        ByteBuffer buffer = ByteBuffer.allocate(128);
        while (true) {
            buffer.clear();
            try {
                writer.write(buffer);
            } catch (java.nio.BufferOverflowException overflow) {
                buffer = ByteBuffer.allocate(buffer.capacity() * 2);
                continue;
            }
            byte[] out = new byte[buffer.position()];
            buffer.flip();
            buffer.get(out);
            return out;
        }
    }

    private interface Writer {
        void write(ByteBuffer buffer);
    }
}
