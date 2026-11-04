package io.github.rururunu.mqttmacchiatto.internal;

import io.github.rururunu.mqttmacchiatto.MqttProperties;
import io.github.rururunu.mqttmacchiatto.MqttSessionException;
import io.github.rururunu.mqttmacchiatto.MqttSessionImage;
import io.github.rururunu.mqttmacchiatto.MqttStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Durable image of unacknowledged QoS 1 and 2 messages, publishes accepted while disconnected,
 * inbound QoS 2 handshakes, and subscriptions. The image is rewritten as a whole and forced
 * to disk, or handed to a {@link MqttStore}. A crash can lose only the updates that have not
 * reached {@link #flush()}.
 */
public final class SessionStore {
    private static final int MAGIC = 0x4D4D5131;
    private static final int VERSION = 2;
    private static final byte[] EMPTY = new byte[0];

    private final Path file;
    private final MqttStore delegate;
    private final boolean enabled;
    private final List<MqttSessionImage.Outbound> outbound = new ArrayList<>();
    private final List<MqttSessionImage.Inbound> inbound = new ArrayList<>();
    private final List<MqttSessionImage.Subscription> subscriptions = new ArrayList<>();
    private ByteBuffer imageBuf;
    private boolean dirty;

    private SessionStore(Path file, MqttStore delegate) {
        this.file = file;
        this.delegate = delegate;
        this.enabled = file != null || delegate != null;
    }

    public static SessionStore disabled() {
        return new SessionStore(null, null);
    }

    public static SessionStore open(Path directory, String clientId) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new MqttSessionException("cannot create persistence directory", e);
        }
        return new SessionStore(directory.resolve(sanitize(clientId) + ".session"), null);
    }

    public static SessionStore custom(MqttStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store is required");
        }
        return new SessionStore(null, store);
    }

    public boolean enabled() {
        return enabled;
    }

    public synchronized MqttSessionImage load() {
        MqttSessionImage image;
        if (delegate != null) {
            image = delegate.load();
            if (image == null) {
                image = MqttSessionImage.EMPTY;
            }
        } else if (file == null || Files.notExists(file)) {
            image = MqttSessionImage.EMPTY;
        } else {
            try {
                image = decode(Files.readAllBytes(file));
            } catch (IOException e) {
                throw new MqttSessionException("cannot read session store", e);
            }
        }
        outbound.addAll(image.outbound());
        inbound.addAll(image.inbound());
        subscriptions.addAll(image.subscriptions());
        dirty = false;
        return image;
    }

    public void discard() {
        if (!enabled) {
            return;
        }
        discardLocked();
    }

    private synchronized void discardLocked() {
        outbound.clear();
        inbound.clear();
        subscriptions.clear();
        dirty = false;
        if (delegate != null) {
            delegate.discard();
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new MqttSessionException("cannot delete session store", e);
        }
    }

    public void rememberOutbound(int packetId, int state, int qos, boolean retain, byte[] topic, byte[] payload) {
        rememberOutbound(packetId, state, qos, retain, topic, payload, null);
    }

    public void rememberOutbound(int packetId, int state, int qos, boolean retain, byte[] topic,
                                 byte[] payload, MqttProperties properties) {
        if (!enabled) {
            return;
        }
        rememberOutboundLocked(packetId, state, qos, retain, topic, payload, properties);
    }

    private synchronized void rememberOutboundLocked(int packetId, int state, int qos, boolean retain, byte[] topic,
                                                     byte[] payload, MqttProperties properties) {
        forgetQueuedTwin(qos, topic, payload);
        byte[] topicCopy = topic.clone();
        byte[] payloadCopy = payload.clone();
        for (int i = 0; i < outbound.size(); i++) {
            if (outbound.get(i).packetId() == packetId) {
                outbound.set(i, new MqttSessionImage.Outbound(packetId, state, qos, retain, topicCopy, payloadCopy, properties));
                dirty = true;
                return;
            }
        }
        outbound.add(new MqttSessionImage.Outbound(packetId, state, qos, retain, topicCopy, payloadCopy, properties));
        dirty = true;
    }

    public void forgetQueued(int qos, byte[] topic, byte[] payload) {
        if (!enabled) {
            return;
        }
        forgetQueuedLocked(qos, topic, payload);
    }

    private synchronized void forgetQueuedLocked(int qos, byte[] topic, byte[] payload) {
        forgetQueuedTwin(qos, topic, payload);
        dirty = true;
    }

    public void rememberQueued(int qos, boolean retain, byte[] topic, byte[] payload, MqttProperties properties) {
        if (!enabled) {
            return;
        }
        rememberQueuedLocked(qos, retain, topic, payload, properties);
    }

    private synchronized void rememberQueuedLocked(int qos, boolean retain, byte[] topic, byte[] payload,
                                                   MqttProperties properties) {
        outbound.add(new MqttSessionImage.Outbound(0, 0, qos, retain, topic.clone(), payload.clone(), properties));
        dirty = true;
    }

    public void forgetOutbound(int packetId) {
        if (!enabled) {
            return;
        }
        forgetOutboundLocked(packetId);
    }

    private synchronized void forgetOutboundLocked(int packetId) {
        for (int i = 0; i < outbound.size(); i++) {
            if (outbound.get(i).packetId() == packetId) {
                outbound.remove(i);
                dirty = true;
                return;
            }
        }
    }

    public void rememberInbound(int packetId, boolean retain, boolean dup, byte[] topic, byte[] payload,
                                MqttProperties properties) {
        if (!enabled) {
            return;
        }
        rememberInboundLocked(packetId, retain, dup, topic, payload, properties);
    }

    private synchronized void rememberInboundLocked(int packetId, boolean retain, boolean dup, byte[] topic,
                                                    byte[] payload, MqttProperties properties) {
        byte[] topicCopy = topic.clone();
        byte[] payloadCopy = payload.clone();
        for (int i = 0; i < inbound.size(); i++) {
            if (inbound.get(i).packetId() == packetId) {
                inbound.set(i, new MqttSessionImage.Inbound(packetId, retain, dup, topicCopy, payloadCopy, properties));
                dirty = true;
                return;
            }
        }
        inbound.add(new MqttSessionImage.Inbound(packetId, retain, dup, topicCopy, payloadCopy, properties));
        dirty = true;
    }

    public void forgetInbound(int packetId) {
        if (!enabled) {
            return;
        }
        forgetInboundLocked(packetId);
    }

    private synchronized void forgetInboundLocked(int packetId) {
        for (int i = 0; i < inbound.size(); i++) {
            if (inbound.get(i).packetId() == packetId) {
                inbound.remove(i);
                dirty = true;
                return;
            }
        }
    }

    public void rememberSubscription(int qos, byte[] filter) {
        if (!enabled) {
            return;
        }
        rememberSubscriptionLocked(qos, filter);
    }

    private synchronized void rememberSubscriptionLocked(int qos, byte[] filter) {
        for (int i = 0; i < subscriptions.size(); i++) {
            if (Arrays.equals(subscriptions.get(i).filter(), filter)) {
                subscriptions.set(i, new MqttSessionImage.Subscription(qos, filter.clone()));
                dirty = true;
                return;
            }
        }
        subscriptions.add(new MqttSessionImage.Subscription(qos, filter.clone()));
        dirty = true;
    }

    public void forgetSubscription(byte[] filter) {
        if (!enabled) {
            return;
        }
        forgetSubscriptionLocked(filter);
    }

    private synchronized void forgetSubscriptionLocked(byte[] filter) {
        for (int i = 0; i < subscriptions.size(); i++) {
            if (Arrays.equals(subscriptions.get(i).filter(), filter)) {
                subscriptions.remove(i);
                dirty = true;
                return;
            }
        }
    }

    public void clearInbound() {
        if (!enabled) {
            return;
        }
        clearInboundLocked();
    }

    private synchronized void clearInboundLocked() {
        if (inbound.isEmpty()) {
            return;
        }
        inbound.clear();
        dirty = true;
    }

    public void flush() {
        if (!enabled) {
            return;
        }
        flushLocked();
    }

    private synchronized void flushLocked() {
        if (!dirty) {
            return;
        }
        if (delegate != null) {
            delegate.save(new MqttSessionImage(outbound, inbound, subscriptions));
            dirty = false;
            return;
        }
        ByteBuffer image = encode();
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (image.hasRemaining()) {
                    channel.write(image);
                }
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            throw new MqttSessionException("cannot write session store", e);
        }
    }

    private void forgetQueuedTwin(int qos, byte[] topic, byte[] payload) {
        for (int i = 0; i < outbound.size(); i++) {
            MqttSessionImage.Outbound existing = outbound.get(i);
            if (existing.packetId() == 0 && existing.qos() == qos
                    && Arrays.equals(existing.topic(), topic) && Arrays.equals(existing.payload(), payload)) {
                outbound.remove(i);
                return;
            }
        }
    }

    private ByteBuffer encode() {
        int size = 20;
        byte[][] outboundProps = new byte[outbound.size()][];
        byte[][] inboundProps = new byte[inbound.size()][];
        for (int i = 0; i < outbound.size(); i++) {
            MqttSessionImage.Outbound out = outbound.get(i);
            outboundProps[i] = propertyBytes(out.properties());
            size += 15 + out.topic().length + out.payload().length + outboundProps[i].length;
        }
        for (int i = 0; i < inbound.size(); i++) {
            MqttSessionImage.Inbound in = inbound.get(i);
            inboundProps[i] = propertyBytes(in.properties());
            size += 14 + in.topic().length + in.payload().length + inboundProps[i].length;
        }
        for (MqttSessionImage.Subscription subscription : subscriptions) {
            size += 3 + subscription.filter().length;
        }
        ByteBuffer buf = imageBuf;
        if (buf == null || buf.capacity() < size) {
            buf = ByteBuffer.allocate(size);
            imageBuf = buf;
        } else {
            buf.clear();
        }
        buf.putInt(MAGIC);
        buf.putInt(VERSION);
        buf.putInt(outbound.size());
        for (int i = 0; i < outbound.size(); i++) {
            MqttSessionImage.Outbound out = outbound.get(i);
            buf.putShort((short) out.packetId());
            buf.put((byte) out.state());
            buf.put((byte) out.qos());
            buf.put((byte) (out.retain() ? 1 : 0));
            buf.putShort((short) out.topic().length);
            buf.put(out.topic());
            buf.putInt(out.payload().length);
            buf.put(out.payload());
            buf.putInt(outboundProps[i].length);
            buf.put(outboundProps[i]);
        }
        buf.putInt(inbound.size());
        for (int i = 0; i < inbound.size(); i++) {
            MqttSessionImage.Inbound in = inbound.get(i);
            buf.putShort((short) in.packetId());
            buf.put((byte) (in.retain() ? 1 : 0));
            buf.put((byte) (in.dup() ? 1 : 0));
            buf.putShort((short) in.topic().length);
            buf.put(in.topic());
            buf.putInt(in.payload().length);
            buf.put(in.payload());
            buf.putInt(inboundProps[i].length);
            buf.put(inboundProps[i]);
        }
        buf.putInt(subscriptions.size());
        for (MqttSessionImage.Subscription subscription : subscriptions) {
            buf.put((byte) subscription.qos());
            buf.putShort((short) subscription.filter().length);
            buf.put(subscription.filter());
        }
        buf.flip();
        return buf;
    }

    private static MqttSessionImage decode(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (buf.remaining() < 16 || buf.getInt() != MAGIC) {
            throw new MqttSessionException("session store is not a Macchiatto file");
        }
        int version = buf.getInt();
        if (version != 1 && version != VERSION) {
            throw new MqttSessionException("unsupported session store version " + version);
        }
        int outCount = buf.getInt();
        List<MqttSessionImage.Outbound> outbound = new ArrayList<>(Math.max(0, outCount));
        for (int i = 0; i < outCount; i++) {
            int packetId = Short.toUnsignedInt(buf.getShort());
            int state = buf.get() & 0xFF;
            int qos = buf.get() & 0xFF;
            boolean retain = buf.get() != 0;
            int topicLen = Short.toUnsignedInt(buf.getShort());
            byte[] topic = new byte[topicLen];
            buf.get(topic);
            int payloadLen = buf.getInt();
            byte[] payload = new byte[payloadLen];
            buf.get(payload);
            MqttProperties properties = version == 1 ? null : readProperties(buf);
            outbound.add(new MqttSessionImage.Outbound(packetId, state, qos, retain, topic, payload, properties));
        }
        int inCount = buf.getInt();
        List<MqttSessionImage.Inbound> inbound = new ArrayList<>(Math.max(0, inCount));
        for (int i = 0; i < inCount; i++) {
            int packetId = Short.toUnsignedInt(buf.getShort());
            boolean retain = buf.get() != 0;
            boolean dup = buf.get() != 0;
            int topicLen = Short.toUnsignedInt(buf.getShort());
            byte[] topic = new byte[topicLen];
            buf.get(topic);
            int payloadLen = buf.getInt();
            byte[] payload = new byte[payloadLen];
            buf.get(payload);
            MqttProperties properties = version == 1 ? null : readProperties(buf);
            inbound.add(new MqttSessionImage.Inbound(packetId, retain, dup, topic, payload, properties));
        }
        int subCount = buf.getInt();
        List<MqttSessionImage.Subscription> subscriptions = new ArrayList<>(Math.max(0, subCount));
        for (int i = 0; i < subCount; i++) {
            int qos = buf.get() & 0xFF;
            int filterLen = Short.toUnsignedInt(buf.getShort());
            byte[] filter = new byte[filterLen];
            buf.get(filter);
            subscriptions.add(new MqttSessionImage.Subscription(qos, filter));
        }
        return new MqttSessionImage(outbound, inbound, subscriptions);
    }

    private static byte[] propertyBytes(MqttProperties properties) {
        if (properties == null || properties.isEmpty()) {
            return EMPTY;
        }
        ByteBuffer buf = ByteBuffer.allocate(V5.publishPropertiesSize(0, properties));
        V5.writePublishProperties(buf, 0, properties);
        byte[] bytes = new byte[buf.position()];
        buf.flip();
        buf.get(bytes);
        return bytes;
    }

    private static MqttProperties readProperties(ByteBuffer buf) {
        int length = buf.getInt();
        if (length == 0) {
            return null;
        }
        byte[] raw = new byte[length];
        buf.get(raw);
        V5.View view = new V5.View();
        V5.readProperties(ByteBuffer.wrap(raw), view);
        return view.toProperties();
    }

    private static String sanitize(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            return "_empty";
        }
        StringBuilder builder = new StringBuilder(clientId.length());
        for (int i = 0; i < clientId.length(); i++) {
            char c = clientId.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
