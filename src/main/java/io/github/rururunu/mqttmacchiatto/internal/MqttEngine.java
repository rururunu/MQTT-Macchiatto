package io.github.rururunu.mqttmacchiatto.internal;

import io.github.rururunu.mqttmacchiatto.MqttAuthenticator;
import io.github.rururunu.mqttmacchiatto.MqttConfig;
import io.github.rururunu.mqttmacchiatto.MqttConnAck;
import io.github.rururunu.mqttmacchiatto.MqttDelivery;
import io.github.rururunu.mqttmacchiatto.MqttEvents;
import io.github.rururunu.mqttmacchiatto.MqttLink;
import io.github.rururunu.mqttmacchiatto.MqttLinkState;
import io.github.rururunu.mqttmacchiatto.MqttListener;
import io.github.rururunu.mqttmacchiatto.MqttOverflow;
import io.github.rururunu.mqttmacchiatto.MqttProperties;
import io.github.rururunu.mqttmacchiatto.MqttProtocol;
import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import io.github.rururunu.mqttmacchiatto.MqttSessionException;
import io.github.rururunu.mqttmacchiatto.MqttSessionImage;
import io.github.rururunu.mqttmacchiatto.MqttSubscription;
import io.github.rururunu.mqttmacchiatto.MqttSubscriptionRequest;
import io.github.rururunu.mqttmacchiatto.MqttWill;
import io.github.rururunu.mqttmacchiatto.ReconnectPolicy;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Single I/O thread, one reused write buffer, packet ids packed into a free stack.
 * Not a supported API.
 */
public final class MqttEngine {
    private static final int STAGE_BYTES = 32 * 1024;
    private static final byte WAIT_PUBACK = 1;
    private static final byte WAIT_PUBREC = 2;
    private static final byte WAIT_PUBCOMP = 3;
    private static final byte WAIT_SUBACK = 4;
    private static final byte WAIT_UNSUBACK = 5;
    private static final int PHASE_CONNECTING = 1;
    private static final int PHASE_OPEN = 2;
    private static final byte[] EMPTY = new byte[0];

    private final MqttSession session;
    private final MqttConfig config;
    private final MqttEvents events;
    private final ReconnectPolicy reconnect;
    private final String[] endpoints;
    private final SessionStore store;
    private final MqttAuthenticator authenticator;
    private final V5.Connect connectExtras;
    private Remote remote;
    private MqttProtocol activeProtocol;
    private boolean mqtt5;
    private int endpointCursor;
    private boolean protocolRetried;
    private final V5.View view = new V5.View();
    private final long sessionExpiry;
    private final int advertisedAliasMaximum;
    private String clientId;
    private byte[] clientIdBytes;
    private final byte[] usernameBytes;
    private final byte[] passwordBytes;
    private final byte[] willTopicBytes;
    private final byte[] willPayload;
    private final int willQos;
    private final boolean willRetain;
    private final int willDelay;
    private final MqttProperties willProperties;
    private final int maxPacketBytes;
    private final long connectTimeoutNanos;
    private long keepAliveNanos;
    private final long retryNanos;
    private final Slot[] slots;
    private final int[] freeIds;
    private int freeTop;
    private final WorkPool pool;
    private final ArrayBlockingQueue<Work> outbox;
    private final ArrayBlockingQueue<Delivery> deliveries;
    private final DeliveryPool deliveryPool;
    private final ArrayList<Sub> subs = new ArrayList<>();
    private final ArrayList<InboundQos2> inboundQos2 = new ArrayList<>();
    private final ArrayDeque<Delivery> overflow = new ArrayDeque<>();
    private final AtomicInteger nextSubId = new AtomicInteger(1);
    private int nextMqttSubId = 1;
    private volatile MqttConnAck connAck;
    private byte[] pendingAuth;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> firstConnect = new CompletableFuture<>();
    private final Frame frame = new Frame();
    private final Selector selector;
    private final ByteBuffer stage;
    /** One direct buffer for a packet that does not fit in {@link #stage}. Reused, never cleared mid-write. */
    private ByteBuffer largeOut;
    private byte[] textScratch = new byte[128];
    private final int[] controls;
    private int controlRead;
    private int controlWrite;
    private final int[] stagedSlots;
    private final CompletableFuture<Void>[] batchFutures;

    private SocketChannel channel;
    private SelectionKey key;
    private TlsLink tls;
    private WebSocketLink web;
    private byte[][] outboundAliases = new byte[1][];
    private int outboundAliasCount;
    private final String[] inboundAliasTopic;
    private int serverTopicAliasMaximum;
    private int serverReceiveMaximum;
    private int sendQuota;
    private volatile int peerMaxPacket;
    private volatile int peerMaxQos = 2;
    private int resendPending;
    private ByteBuffer plainIn;
    private ByteBuffer active;
    private Thread ioThread;
    private Thread callbackThread;
    private volatile boolean open;
    private volatile boolean ioWaiting;
    private volatile boolean reconnectNow;
    private volatile MqttLink link;
    private int phase;
    private boolean writing;
    private boolean closeAfterWrite;
    private boolean pingDue;
    private boolean pingWaiting;
    private boolean everOpen;
    private int failureCount;
    private int resubCursor = -1;
    private int resendCursor;
    private int stagedSlotCount;
    private int batchFutureCount;
    private long lastInbound;
    private long lastOutbound;
    private long connackDeadline;
    private Delivery parked;

    public MqttEngine(MqttConfig config, MqttSession session) {
        this.session = session;
        this.config = config;
        this.events = config.events();
        this.reconnect = config.reconnect();
        this.endpoints = config.endpoints().toArray(String[]::new);
        this.remote = Remote.parse(endpoints[0]);
        this.link = new MqttLink(MqttLinkState.CONNECTING, 0, endpoints[0], 0, null);
        this.activeProtocol = config.protocol();
        this.mqtt5 = activeProtocol == MqttProtocol.MQTT_5;
        this.authenticator = config.authenticator();
        String id = config.clientId();
        if (id == null) {
            id = "m" + Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong(), 36);
        }
        this.clientId = id;
        this.clientIdBytes = id.isEmpty() ? EMPTY : Wire.utf8(id);
        if (config.store() != null) {
            this.store = SessionStore.custom(config.store());
        } else if (config.persistence() != null) {
            this.store = SessionStore.open(config.persistence(), id);
        } else {
            this.store = SessionStore.disabled();
        }
        long expiry = config.sessionExpirySeconds();
        if (expiry < 0) {
            expiry = config.cleanSession() ? 0L : V5.SESSION_EXPIRY_MAX;
        }
        this.sessionExpiry = expiry;
        this.advertisedAliasMaximum = config.topicAliasMaximum();
        this.inboundAliasTopic = advertisedAliasMaximum == 0 ? new String[0] : new String[advertisedAliasMaximum + 1];
        this.usernameBytes = config.username() == null || config.username().isEmpty() ? null : Wire.utf8(config.username());
        this.passwordBytes = config.password() == null ? null : Wire.utf8(config.password());
        MqttWill will = config.will();
        if (will == null) {
            this.willTopicBytes = null;
            this.willPayload = null;
            this.willQos = 0;
            this.willRetain = false;
            this.willDelay = -1;
            this.willProperties = null;
        } else {
            this.willTopicBytes = Wire.topicName(will.topic());
            this.willPayload = will.payload();
            this.willQos = will.qos().wire();
            this.willRetain = will.retained();
            this.willDelay = will.delaySeconds();
            this.willProperties = will.properties();
        }
        V5.Connect extras = new V5.Connect();
        extras.willDelay = willDelay;
        extras.willProperties = willProperties;
        extras.users = config.connectUserProperties();
        extras.requestResponseInfo = config.requestResponseInfo();
        extras.suppressProblemInfo = !config.requestProblemInfo();
        extras.authMethod = config.authMethod();
        extras.authData = config.authData();
        this.connectExtras = extras;
        this.maxPacketBytes = config.maxPacketBytes();
        this.peerMaxPacket = config.maxPacketBytes();
        this.connectTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.connectTimeoutMillis());
        this.keepAliveNanos = TimeUnit.SECONDS.toNanos(config.keepAliveSeconds());
        this.retryNanos = TimeUnit.MILLISECONDS.toNanos(config.retryMillis());
        int inflight = config.maxInflight();
        this.slots = new Slot[inflight];
        this.freeIds = new int[inflight];
        for (int i = 0; i < inflight; i++) {
            slots[i] = new Slot();
            freeIds[i] = inflight - i;
        }
        this.freeTop = inflight;
        this.serverReceiveMaximum = inflight;
        this.sendQuota = inflight;
        int queued = config.maxQueued();
        this.pool = new WorkPool();
        this.outbox = new ArrayBlockingQueue<>(queued + 1);
        for (int i = 0; i < queued; i++) {
            pool.recycle(new Work());
        }
        this.deliveries = new ArrayBlockingQueue<>(Math.max(256, Math.min(queued, 4096)));
        this.deliveryPool = new DeliveryPool();
        for (int i = 0; i < 256; i++) {
            deliveryPool.recycle(new Delivery());
        }
        try {
            this.selector = Selector.open();
        } catch (IOException e) {
            throw new MqttSessionException("selector", e);
        }
        int connectBytes = mqtt5
                ? V5.connectPacketSize(clientIdBytes, usernameBytes, passwordBytes, willTopicBytes, willPayload,
                sessionExpiry, inflight, advertisedAliasMaximum, maxPacketBytes, connectExtras)
                : connectSize();
        this.stage = ByteBuffer.allocateDirect(Math.max(STAGE_BYTES, connectBytes));
        this.plainIn = ByteBuffer.allocate(64 * 1024);
        this.plainIn.limit(0);
        this.controls = new int[1024];
        this.stagedSlots = new int[inflight];
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[128];
        this.batchFutures = futures;
    }

    public String clientId() {
        return clientId;
    }

    public boolean connected() {
        return open;
    }

    public MqttConnAck connection() {
        return connAck;
    }

    public MqttLink link() {
        return link;
    }

    /** Drop the socket, or end the current wait, and dial again on this same session. */
    public void reconnect() {
        if (closing.get()) {
            throw new MqttSessionException("session is closed");
        }
        reconnectNow = true;
        LockSupport.unpark(ioThread);
        selector.wakeup();
    }

    public void start() {
        callbackThread = Thread.ofPlatform().name("mqtt-macchiatto-cb").unstarted(this::callbackLoop);
        ioThread = Thread.ofPlatform().name("mqtt-macchiatto-io").unstarted(this::run);
        callbackThread.start();
        ioThread.start();
    }

    public void awaitConnected() {
        try {
            firstConnect.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MqttSessionException("connect interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof MqttSessionException mqtt) {
                throw mqtt;
            }
            throw new MqttSessionException("connect failed", cause);
        }
    }

    public CompletableFuture<Void> publish(String topic, byte[] payload, MqttQos qos, boolean retained) {
        return publish(Wire.topicName(topic), payload, qos, retained, null);
    }

    public CompletableFuture<Void> publish(String topic, byte[] payload, MqttQos qos, boolean retained, MqttProperties properties) {
        return publish(Wire.topicName(topic), payload, qos, retained, properties);
    }

    public CompletableFuture<Void> publish(byte[] topicUtf8, byte[] payload, MqttQos qos, boolean retained) {
        return publish(topicUtf8, payload, qos, retained, null);
    }

    public CompletableFuture<Void> publish(byte[] topicUtf8, byte[] payload, MqttQos qos, boolean retained, MqttProperties properties) {
        checkTopicBytes(topicUtf8);
        checkPayload(payload, qos, properties);
        if (closing.get()) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            closed.completeExceptionally(new MqttSessionException("session is closed"));
            return closed;
        }
        if (qos == null) {
            throw new IllegalArgumentException("qos is required");
        }
        if (qos.wire() > peerMaxQos) {
            throw new IllegalArgumentException("broker maximum qos is " + peerMaxQos);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        Work work = pool.take();
        work.kind = Work.PUBLISH;
        work.topic = topicUtf8;
        work.payload = payload;
        work.qos = qos.wire();
        work.retain = retained;
        work.future = future;
        work.properties = properties;
        if (!offer(work)) {
            future.completeExceptionally(new MqttSessionException(queueFullMessage()));
        }
        return future;
    }

    public void emit(byte[] topicUtf8, byte[] payload) {
        checkTopicBytes(topicUtf8);
        checkPayload(payload, MqttQos.AT_MOST_ONCE);
        if (closing.get()) {
            throw new MqttSessionException("session is closed");
        }
        Work work = pool.take();
        work.kind = Work.PUBLISH;
        work.topic = topicUtf8;
        work.payload = payload;
        work.qos = 0;
        work.retain = false;
        if (!offer(work)) {
            throw new MqttSessionException(queueFullMessage());
        }
    }

    public MqttSubscription subscribe(String filter, MqttQos qos, MqttListener listener) {
        return subscribe(new MqttSubscriptionRequest(filter, qos, listener)).get(0);
    }

    public java.util.List<MqttSubscription> subscribe(MqttSubscriptionRequest... requests) {
        if (requests == null || requests.length == 0) {
            throw new IllegalArgumentException("at least one subscription is required");
        }
        if (closing.get()) {
            throw new MqttSessionException("session is closed");
        }
        Sub[] group = new Sub[requests.length];
        int mqttId = 0;
        if (mqtt5) {
            mqttId = nextMqttSubId++;
            if (nextMqttSubId > 268_435_455) {
                nextMqttSubId = 1;
            }
        }
        for (int i = 0; i < requests.length; i++) {
            MqttSubscriptionRequest request = requests[i];
            if (request == null) {
                throw new IllegalArgumentException("subscription is required");
            }
            TopicFilter compiled = TopicFilter.compile(request.filter());
            Sub sub = new Sub(nextSubId.getAndIncrement(), compiled, Wire.utf8(request.filter()), request.listener(),
                    new CompletableFuture<>());
            sub.qos = (byte) request.qos().wire();
            sub.mqttId = mqttId;
            group[i] = sub;
        }
        Work work = pool.take();
        work.kind = Work.SUBSCRIBE;
        work.sub = group[0];
        work.group = group;
        work.qos = group[0].qos;
        if (!offer(work)) {
            throw new MqttSessionException(queueFullMessage());
        }
        try {
            CompletableFuture<?>[] done = new CompletableFuture<?>[group.length];
            for (int i = 0; i < group.length; i++) {
                done[i] = group[i].future;
            }
            CompletableFuture.allOf(done).get();
            java.util.List<MqttSubscription> handles = new ArrayList<>(group.length);
            for (Sub sub : group) {
                handles.add(sub.future.get());
            }
            return handles;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MqttSessionException("subscribe interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof MqttSessionException mqtt) {
                throw mqtt;
            }
            throw new MqttSessionException("subscribe failed", cause);
        }
    }

    public void unsubscribe(int id) {
        Work work = pool.take();
        work.kind = Work.UNSUBSCRIBE;
        work.subId = id;
        offer(work);
    }

    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        LockSupport.unpark(ioThread);
        selector.wakeup();
        if (Thread.currentThread() != ioThread && Thread.currentThread() != callbackThread) {
            joinThreads();
        }
    }

    private void joinThreads() {
        try {
            if (ioThread != null && Thread.currentThread() != ioThread) {
                ioThread.join(5_000);
            }
            if (callbackThread != null && Thread.currentThread() != callbackThread) {
                callbackThread.interrupt();
                callbackThread.join(2_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean offer(Work work) {
        if (enqueue(work)) {
            return true;
        }
        if (config.overflow() == MqttOverflow.DROP_OLDEST && dropOldestPublish() && enqueue(work)) {
            return true;
        }
        pool.recycle(work);
        return false;
    }

    private boolean enqueue(Work work) {
        if (!outbox.offer(work)) {
            return false;
        }
        if (!open && work.kind == Work.PUBLISH && work.qos > 0 && store.enabled()) {
            store.rememberQueued(work.qos, work.retain, work.topic, work.payload, work.properties);
        }
        wakeIo();
        return true;
    }

    private boolean dropOldestPublish() {
        Iterator<Work> queued = outbox.iterator();
        while (queued.hasNext()) {
            Work candidate = queued.next();
            if (candidate.kind != Work.PUBLISH) {
                continue;
            }
            queued.remove();
            if (candidate.future != null) {
                candidate.future.completeExceptionally(new MqttSessionException("dropped to make room"));
            }
            store.forgetQueued(candidate.qos, candidate.topic, candidate.payload);
            pool.recycle(candidate);
            return true;
        }
        return false;
    }

    private void wakeIo() {
        if (ioWaiting) {
            selector.wakeup();
        }
    }

    private String queueFullMessage() {
        return config.overflow() == MqttOverflow.DROP_NEWEST ? "dropped because the outbound queue is full" : "outbound queue is full";
    }

    private void run() {
        if (config.cleanSession()) {
            store.discard();
        } else {
            restore(store.load());
        }
        Throwable fatal = null;
        while (!closing.get()) {
            try {
                connectAndRun();
                break;
            } catch (Throwable failure) {
                fatal = failure;
                open = false;
                tearDown(failure);
                if (closing.get()) {
                    break;
                }
                if (failure instanceof ForcedReconnect) {
                    if (everOpen) {
                        safeEvent(() -> events.disconnected(failure));
                    }
                    continue;
                }
                int reason = failure instanceof MqttSessionException mqtt ? mqtt.reasonCode() : MqttSessionException.NONE;
                if (reason == MqttSessionException.IDENTIFIER_REJECTED
                        || reason == MqttSessionException.BAD_CREDENTIALS
                        || reason == MqttSessionException.NOT_AUTHORIZED) {
                    giveUp(failure);
                    break;
                }
                if (reason == MqttSessionException.UNACCEPTABLE_PROTOCOL
                        && config.protocolFallback() != null
                        && !protocolRetried) {
                    applyProtocol(config.protocolFallback());
                    protocolRetried = true;
                    noteRetry(MqttLinkState.CONNECTING, endpoints[endpointCursor], 0, failure);
                    continue;
                }
                protocolRetried = false;
                applyProtocol(config.protocol());
                if (endpoints.length > 1 && endpointCursor + 1 < endpoints.length) {
                    endpointCursor++;
                    remote = Remote.parse(endpoints[endpointCursor]);
                    noteRetry(MqttLinkState.CONNECTING, endpoints[endpointCursor], 0, failure);
                    continue;
                }
                endpointCursor = 0;
                remote = Remote.parse(endpoints[0]);
                boolean retry = !(failure instanceof MqttSessionException mqtt) || mqtt.retryable();
                failureCount++;
                long delay = retry
                        ? reconnect.jitteredDelayMillis(failureCount, java.util.concurrent.ThreadLocalRandom.current()::nextDouble)
                        : -1L;
                if (delay < 0) {
                    giveUp(failure);
                    break;
                }
                if (everOpen) {
                    safeEvent(() -> events.disconnected(failure));
                }
                noteRetry(MqttLinkState.WAITING, endpoints[0], delay, failure);
                store.flush();
                waitForRetry(delay);
            }
        }
        open = false;
        MqttSessionException reported = fatal instanceof MqttSessionException mqtt
                ? mqtt
                : new MqttSessionException(fatal == null ? "closed" : fatal.getMessage(), fatal);
        if (!firstConnect.isDone()) {
            if (closing.get() && fatal == null) {
                firstConnect.completeExceptionally(new MqttSessionException("closed"));
            } else {
                firstConnect.completeExceptionally(reported);
            }
        }
        if (closing.get() || link.state() != MqttLinkState.FAILED) {
            if (closing.get()) {
                publishLink(MqttLinkState.CLOSED, link.attempt(), link.endpoint(), 0, (String) null);
            } else if (link.state() != MqttLinkState.FAILED) {
                publishLink(MqttLinkState.FAILED, link.attempt(), link.endpoint(), 0, fatal);
            }
        }
        failQueued(reported);
        failSlots(reported);
        failSubs(reported);
        try {
            selector.close();
        } catch (IOException ignored) {
        }
        closing.set(true);
    }

    private void connectAndRun() throws IOException {
        publishLink(MqttLinkState.CONNECTING, failureCount, endpoints[endpointCursor], 0, link.cause());
        try {
            openChannel();
            long deadline = System.nanoTime() + connectTimeoutNanos;
            if (remote.tls) {
                tls = TlsLink.handshake(channel, key, selector, remote.host, remote.port, config.sslContext(),
                        deadline, closing, config.verifyHostname(), config.serverName(), config.hostnameVerifier());
            }
            if (remote.websocket) {
                web = WebSocketLink.handshake(channel, key, selector, tls, remote, deadline, closing, config.webSocketHeaders());
            }
            resetAliases();
            writeConnect();
            phase = PHASE_CONNECTING;
            connackDeadline = System.nanoTime() + connectTimeoutNanos;
            plainIn.clear();
            plainIn.limit(0);
            ioLoop();
        } finally {
            closeChannel();
        }
    }

    private void openChannel() throws IOException {
        channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
        InetSocketAddress address = resolve(remote.host, remote.port);
        boolean pending = !channel.connect(address);
        key = channel.register(selector, pending ? SelectionKey.OP_CONNECT : SelectionKey.OP_READ);
        long deadline = System.nanoTime() + connectTimeoutNanos;
        while (!channel.finishConnect()) {
            long wait = deadline - System.nanoTime();
            if (wait <= 0 || closing.get()) {
                throw new MqttSessionException(closing.get() ? "closed" : "connect timed out");
            }
            selector.select(TimeUnit.NANOSECONDS.toMillis(wait) + 1);
            clearSelected();
        }
        key.interestOps(SelectionKey.OP_READ);
    }

    private void writeConnect() throws IOException {
        int size = mqtt5
                ? V5.connectPacketSize(clientIdBytes, usernameBytes, passwordBytes, willTopicBytes, willPayload,
                sessionExpiry, slots.length, advertisedAliasMaximum, maxPacketBytes, connectExtras)
                : connectSize();
        ByteBuffer buffer = directBuffer(size);
        buffer.clear();
        if (mqtt5) {
            V5.encodeConnect(buffer, clientIdBytes, config.cleanSession(), config.keepAliveSeconds(),
                    usernameBytes, passwordBytes, willTopicBytes, willPayload, willQos, willRetain,
                    sessionExpiry, slots.length, advertisedAliasMaximum, maxPacketBytes, connectExtras);
        } else if (activeProtocol == MqttProtocol.MQTT_3_1) {
            Wire.encodeConnect31(buffer, clientIdBytes, config.cleanSession(), config.keepAliveSeconds(),
                    usernameBytes, passwordBytes, willTopicBytes, willPayload, willQos, willRetain);
        } else {
            Wire.encodeConnect(buffer, clientIdBytes, config.cleanSession(), config.keepAliveSeconds(),
                    usernameBytes, passwordBytes, willTopicBytes, willPayload, willQos, willRetain);
        }
        buffer.flip();
        active = buffer;
        writing = true;
        long deadline = System.nanoTime() + connectTimeoutNanos;
        while (writing) {
            writeSome();
            if (!writing) {
                return;
            }
            long wait = deadline - System.nanoTime();
            if (wait <= 0) {
                throw new MqttSessionException("connect timed out");
            }
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            selector.select(TimeUnit.NANOSECONDS.toMillis(wait) + 1);
            clearSelected();
        }
    }

    private void applyProtocol(MqttProtocol protocol) {
        activeProtocol = protocol;
        mqtt5 = protocol == MqttProtocol.MQTT_5;
    }

    private void noteRetry(MqttLinkState state, String endpoint, long retryInMillis, Throwable failure) {
        publishLink(state, failureCount, endpoint, retryInMillis, failure);
        MqttLink snapshot = link;
        safeEvent(() -> events.reconnecting(snapshot));
    }

    private void giveUp(Throwable failure) {
        publishLink(MqttLinkState.FAILED, failureCount, endpoints[Math.min(endpointCursor, endpoints.length - 1)], 0, failure);
    }

    private void publishLink(MqttLinkState state, int attempt, String endpoint, long retryInMillis, Throwable failure) {
        publishLink(state, attempt, endpoint, retryInMillis, causeText(failure));
    }

    private void publishLink(MqttLinkState state, int attempt, String endpoint, long retryInMillis, String cause) {
        link = new MqttLink(state, attempt, endpoint == null ? "" : endpoint, retryInMillis, cause);
    }

    private static String causeText(Throwable failure) {
        if (failure == null) {
            return null;
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        if (failure instanceof MqttSessionException mqtt && mqtt.reasonCode() >= 0) {
            return message + " (" + mqtt.reasonCode() + ")";
        }
        return message;
    }

    private void waitForRetry(long delayMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        while (!closing.get()) {
            if (reconnectNow) {
                reconnectNow = false;
                return;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            LockSupport.parkNanos(left);
        }
    }

    private InetSocketAddress resolve(String host, int port) {
        long deadline = System.nanoTime() + connectTimeoutNanos;
        CompletableFuture<InetAddress> lookedUp = new CompletableFuture<>();
        Thread.ofVirtual().name("mqtt-macchiatto-dns").start(() -> {
            try {
                lookedUp.complete(InetAddress.getByName(host));
            } catch (Throwable failure) {
                lookedUp.completeExceptionally(failure);
            }
        });
        while (true) {
            if (closing.get()) {
                lookedUp.cancel(true);
                throw new MqttSessionException("closed");
            }
            long leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMillis <= 0) {
                lookedUp.cancel(true);
                throw new MqttSessionException("connect timed out");
            }
            try {
                InetAddress address = lookedUp.get(Math.min(50L, leftMillis), TimeUnit.MILLISECONDS);
                return new InetSocketAddress(address, port);
            } catch (TimeoutException ignored) {
                // The resolver is still working. Keep the connect deadline.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new MqttSessionException("connect interrupted", interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                String text = cause.getMessage() == null ? "unresolved host " + host : cause.getMessage();
                throw new MqttSessionException(text, cause);
            }
        }
    }

    private int connectSize() {
        int name = activeProtocol == MqttProtocol.MQTT_3_1 ? 8 : 6;
        int remaining = name + 4 + 2 + clientIdBytes.length;
        if (willTopicBytes != null) {
            remaining += 2 + willTopicBytes.length + 2 + willPayload.length;
        }
        if (usernameBytes != null) {
            remaining += 2 + usernameBytes.length;
            if (passwordBytes != null) {
                remaining += 2 + passwordBytes.length;
            }
        }
        return 1 + Wire.remainingLengthSize(remaining) + remaining;
    }

    /**
     * Packets that fit use the stage. A larger one uses a single direct buffer kept for the
     * next large packet. Callers run this only while nothing is mid-write, then {@code clear}
     * before encoding.
     */
    private ByteBuffer directBuffer(int size) {
        if (size <= stage.capacity()) {
            return stage;
        }
        if (largeOut == null || largeOut.capacity() < size) {
            largeOut = ByteBuffer.allocateDirect(size);
        }
        return largeOut;
    }

    private String readTopic() {
        if (plainIn.remaining() < 2) {
            throw new IllegalArgumentException("truncated MQTT string");
        }
        int length = Short.toUnsignedInt(plainIn.getShort(plainIn.position()));
        if (length > plainIn.remaining() - 2) {
            throw new IllegalArgumentException("truncated MQTT string");
        }
        if (textScratch.length < length) {
            textScratch = new byte[length];
        }
        return Wire.readString(plainIn, textScratch);
    }

    private void ioLoop() throws IOException {
        while (channel != null && channel.isOpen() && !closeAfterWrite) {
            if (reconnectNow) {
                reconnectNow = false;
                throw new ForcedReconnect();
            }
            if (closing.get() && !writing) {
                stage.clear();
                Wire.encodeDisconnect(stage);
                stage.flip();
                active = stage;
                writing = true;
                closeAfterWrite = true;
            }
            if (phase == PHASE_CONNECTING && System.nanoTime() > connackDeadline) {
                throw new MqttSessionException("CONNACK timed out");
            }
            flushOverflow();
            boolean encoded = false;
            if (!writing && overflow.isEmpty()) {
                fill();
                encoded = writing;
            }
            if (writing) {
                writeSome();
            }
            if (closeAfterWrite && !writing) {
                return;
            }
            if (phase == PHASE_OPEN) {
                timers();
            }
            // A full write with more queued work polls for inbound data and continues.
            // A stalled write, or a queue waiting on acks, blocks instead of spinning.
            key.interestOps(writing ? SelectionKey.OP_READ | SelectionKey.OP_WRITE : SelectionKey.OP_READ);
            boolean moreReady = encoded && !writing && phase == PHASE_OPEN && overflow.isEmpty() && canEncodeMore();
            if (moreReady) {
                selector.selectNow();
            } else {
                ioWaiting = true;
                try {
                    if (!writing && phase == PHASE_OPEN && overflow.isEmpty() && canEncodeMore()) {
                        selector.selectNow();
                    } else {
                        selector.select(nextTimeoutMillis());
                    }
                } finally {
                    ioWaiting = false;
                }
            }
            boolean readable = false;
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey selected = keys.next();
                keys.remove();
                if (selected.isValid() && selected.isReadable()) {
                    readable = true;
                }
            }
            if (readable && overflow.isEmpty()) {
                if (!readMore()) {
                    throw new MqttSessionException("connection closed");
                }
                parse();
            }
        }
    }

    private long nextTimeoutMillis() {
        if (!writing && phase == PHASE_OPEN && canEncodeMore()) {
            return 1;
        }
        if (phase != PHASE_OPEN) {
            long wait = connackDeadline - System.nanoTime();
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(wait) + 1);
        }
        long now = System.nanoTime();
        long wait = TimeUnit.SECONDS.toNanos(30);
        if (keepAliveNanos > 0) {
            wait = Math.min(wait, lastOutbound + keepAliveNanos - now);
            wait = Math.min(wait, lastInbound + keepAliveNanos + keepAliveNanos / 2 - now);
        }
        long retry = nextRetry();
        if (retry != 0) {
            wait = Math.min(wait, retry - now);
        }
        if (wait < 1_000_000L) {
            return 1;
        }
        return TimeUnit.NANOSECONDS.toMillis(wait);
    }

    private boolean canEncodeMore() {
        if (pingDue || controlRead != controlWrite || resendPending > 0) {
            return true;
        }
        if (resubCursor >= 0) {
            for (int i = Math.max(resubCursor, 0); i < subs.size(); i++) {
                if (subs.get(i).live) {
                    return freeTop > 0;
                }
            }
        }
        Work work = outbox.peek();
        if (work == null) {
            return false;
        }
        if (work.kind == Work.PUBLISH && work.qos == 0) {
            return true;
        }
        if (work.kind == Work.PUBLISH && work.qos > 0) {
            return freeTop > 0 && sendQuota > 0;
        }
        if (work.kind == Work.SUBSCRIBE || work.kind == Work.UNSUBSCRIBE) {
            return freeTop > 0;
        }
        return true;
    }

    private boolean hasOutbound() {
        if (!outbox.isEmpty() || controlRead != controlWrite || pingDue || resubCursor >= 0) {
            return true;
        }
        for (Slot slot : slots) {
            if (slot.packetId != 0 && (slot.needResend || (slot.onWire && System.nanoTime() >= slot.retryAt))) {
                return true;
            }
        }
        return false;
    }

    private long nextRetry() {
        long soonest = 0;
        for (Slot slot : slots) {
            if (slot.packetId != 0 && slot.onWire) {
                if (soonest == 0 || slot.retryAt < soonest) {
                    soonest = slot.retryAt;
                }
            }
        }
        return soonest;
    }

    private boolean readMore() throws IOException {
        plainIn.compact();
        if (plainIn.remaining() < 4096 && plainIn.capacity() < maxPacketBytes + 5) {
            plainIn = grow(plainIn, Math.min(maxPacketBytes + 5, plainIn.capacity() * 2));
        }
        boolean eof;
        if (web != null) {
            if (plainIn.capacity() < maxPacketBytes + 5) {
                plainIn = grow(plainIn, maxPacketBytes + 5);
            }
            eof = !web.read(plainIn);
        } else if (tls == null) {
            int read = channel.read(plainIn);
            eof = read < 0;
        } else {
            eof = !tls.unwrap(channel, plainIn);
        }
        plainIn.flip();
        return !eof || plainIn.hasRemaining();
    }

    private void parse() {
        while (overflow.isEmpty() && plainIn.hasRemaining()) {
            int mark = plainIn.position();
            int body;
            try {
                body = Wire.openFrame(plainIn, frame, maxPacketBytes);
            } catch (IllegalArgumentException e) {
                throw new MqttSessionException(e.getMessage(), e, MqttSessionException.NONE, false);
            }
            if (body < 0) {
                plainIn.position(mark);
                return;
            }
            int end = plainIn.position() + body;
            int limit = plainIn.limit();
            plainIn.limit(end);
            try {
                handle(end);
            } catch (IllegalArgumentException e) {
                throw new MqttSessionException(e.getMessage(), e, MqttSessionException.NONE, false);
            } finally {
                plainIn.limit(limit);
                plainIn.position(end);
            }
        }
        if (store.enabled()) {
            store.flush();
        }
    }

    private void handle(int end) {
        lastInbound = System.nanoTime();
        pingWaiting = false;
        int type = frame.type;
        int flags = frame.flags;
        if (type == Wire.CONNACK) {
            int ackFlags = plainIn.get() & 0xFF;
            int code = plainIn.get() & 0xFF;
            boolean sessionPresent = (ackFlags & 0x01) != 0;
            if (mqtt5) {
                view.reset();
                V5.readProperties(plainIn, view);
                if (!V5.successReason(code)) {
                    String text = view.reasonString == null ? connackText(code) : view.reasonString;
                    boolean retry = code == 0x88 || code == 0x89 || code == 0x8B;
                    throw new MqttSessionException(text, null, code, retry);
                }
                applyServerLimits();
            } else if (code != 0) {
                throw new MqttSessionException(connackText(code), code);
            }
            onConnack(sessionPresent, code);
            return;
        }
        if (type == 15) {
            onAuth(end);
            return;
        }
        if (phase != PHASE_OPEN) {
            throw new MqttSessionException("packet before CONNACK", null, MqttSessionException.NONE, false);
        }
        switch (type) {
            case Wire.PUBLISH -> onPublish(flags, end);
            case Wire.PUBACK -> onPubAck(end);
            case Wire.PUBREC -> onPubRec(end);
            case Wire.PUBREL -> onPubRel(flags, end);
            case Wire.PUBCOMP -> onPubComp(end);
            case Wire.SUBACK -> onSubAck(end);
            case Wire.UNSUBACK -> onUnsubAck(Wire.readUnsignedShort(plainIn));
            case Wire.PINGRESP -> {
            }
            case Wire.PINGREQ -> pingDue = true;
            case Wire.DISCONNECT -> onDisconnect(end);
            case 15 -> onAuth(end);
            default -> throw new MqttSessionException("unexpected packet type " + type, null, MqttSessionException.NONE, false);
        }
    }

    private void applyServerLimits() {
        if (view.receiveMaximum > 0) {
            serverReceiveMaximum = view.receiveMaximum;
        }
        int aliasMax = Math.max(view.topicAliasMaximum, 0);
        if (aliasMax > 4096) {
            aliasMax = 4096;
        }
        serverTopicAliasMaximum = aliasMax;
        outboundAliases = aliasMax == 0 ? new byte[1][] : new byte[aliasMax + 1][];
        outboundAliasCount = 0;
        if (view.maximumPacketSize > 0) {
            peerMaxPacket = Math.min(maxPacketBytes, view.maximumPacketSize);
        }
        if (view.maximumQos >= 0) {
            peerMaxQos = Math.min(2, view.maximumQos);
        }
        if (view.serverKeepAlive >= 0) {
            keepAliveNanos = TimeUnit.SECONDS.toNanos(view.serverKeepAlive);
        }
        if (view.assignedClientId != null && view.assignedClientId.length > 0) {
            clientId = new String(view.assignedClientId, java.nio.charset.StandardCharsets.UTF_8);
            clientIdBytes = view.assignedClientId;
        }
    }

    private void onAuth(int end) {
        if (!mqtt5) {
            throw new MqttSessionException("unexpected packet type 15", null, MqttSessionException.NONE, false);
        }
        int reason = plainIn.position() < end ? plainIn.get() & 0xFF : 0;
        view.reset();
        if (plainIn.position() < end) {
            V5.readProperties(plainIn, view);
        }
        if (authenticator == null) {
            throw new MqttSessionException("enhanced authentication is not supported", null, reason, false);
        }
        String method = view.authMethod == null ? config.authMethod() : view.authMethod;
        byte[] challenge = view.authData == null ? EMPTY : view.authData;
        byte[] response;
        try {
            response = authenticator.authenticate(method, challenge);
        } catch (RuntimeException e) {
            throw new MqttSessionException("enhanced authentication failed", e, reason, false);
        }
        if (response == null) {
            response = EMPTY;
        }
        int size = 8 + (method == null ? 0 : method.length() + 5) + response.length + 5;
        ByteBuffer buf = ByteBuffer.allocate(size);
        V5.encodeAuth(buf, 0x18, method, response);
        buf.flip();
        pendingAuth = new byte[buf.remaining()];
        buf.get(pendingAuth);
    }

    private void onConnack(boolean sessionPresent, int reasonCode) {
        phase = PHASE_OPEN;
        open = true;
        everOpen = true;
        failureCount = 0;
        publishLink(MqttLinkState.OPEN, 0, endpoints[endpointCursor], 0, (String) null);
        long now = System.nanoTime();
        lastInbound = now;
        lastOutbound = now;
        resubCursor = 0;
        resendCursor = 0;
        if (!sessionPresent && !inboundQos2.isEmpty()) {
            inboundQos2.clear();
            store.clearInbound();
        }
        int used = 0;
        for (Slot slot : slots) {
            if (slot.packetId == 0) {
                continue;
            }
            if (!slot.needResend) {
                resendPending++;
            }
            slot.needResend = true;
            slot.onWire = false;
            if (slot.state == WAIT_PUBACK || slot.state == WAIT_PUBREC) {
                used++;
            }
        }
        int cap = Math.min(serverReceiveMaximum, slots.length);
        sendQuota = Math.max(0, cap - used);
        String assigned = null;
        int keepAlive = -1;
        int receiveMaximum = -1;
        int maximumQos = -1;
        int aliasMaximum = -1;
        String reasonString = null;
        if (mqtt5) {
            if (view.assignedClientId != null && view.assignedClientId.length > 0) {
                assigned = new String(view.assignedClientId, StandardCharsets.UTF_8);
            }
            keepAlive = view.serverKeepAlive;
            receiveMaximum = view.receiveMaximum;
            maximumQos = view.maximumQos;
            aliasMaximum = view.topicAliasMaximum;
            reasonString = view.reasonString;
        }
        connAck = new MqttConnAck(sessionPresent, reasonCode, reasonString, assigned, keepAlive, receiveMaximum,
                maximumQos, aliasMaximum);
        if (!firstConnect.isDone()) {
            firstConnect.complete(null);
        }
        safeEvent(events::connected);
        MqttConnAck ack = connAck;
        safeEvent(() -> events.connAck(ack));
    }

    private void onPublish(int flags, int end) {
        int qos = (flags >>> 1) & 0x03;
        boolean retain = (flags & 0x01) != 0;
        boolean dup = (flags & 0x08) != 0;
        if (qos == 3 || (qos == 0 && dup)) {
            throw new MqttSessionException("malformed PUBLISH flags", null, MqttSessionException.NONE, false);
        }
        String topic = readTopic();
        int packetId = qos == 0 ? 0 : Wire.readUnsignedShort(plainIn);
        MqttProperties properties = null;
        if (mqtt5) {
            view.reset();
            V5.readProperties(plainIn, view);
            topic = resolveAlias(topic, view.topicAlias);
            properties = view.toProperties();
        }
        if (topic == null || topic.isEmpty()) {
            throw new MqttSessionException("publish is missing a topic", null, MqttSessionException.NONE, false);
        }
        int length = end - plainIn.position();
        byte[] payload = Wire.readBytes(plainIn, length);
        if (qos == 1) {
            pushControl(Wire.PUBACK, packetId);
            deliver(topic, payload, qos, retain, dup, properties);
        } else if (qos == 2) {
            if (findInbound(packetId) == null) {
                inboundQos2.add(new InboundQos2(packetId, topic, payload, retain, dup, properties));
                store.rememberInbound(packetId, retain, dup, topic.getBytes(StandardCharsets.UTF_8), payload, properties);
            }
            pushControl(Wire.PUBREC, packetId);
        } else {
            deliver(topic, payload, qos, retain, dup, properties);
        }
    }

    private String resolveAlias(String topic, int alias) {
        if (alias <= 0) {
            return topic;
        }
        if (alias >= inboundAliasTopic.length) {
            throw new MqttSessionException("topic alias exceeds the advertised maximum", null, MqttSessionException.NONE, false);
        }
        if (topic != null && !topic.isEmpty()) {
            inboundAliasTopic[alias] = topic;
            return topic;
        }
        String cached = inboundAliasTopic[alias];
        if (cached == null) {
            throw new MqttSessionException("unknown topic alias " + alias, null, MqttSessionException.NONE, false);
        }
        return cached;
    }

    private void onPubRel(int flags, int end) {
        if (flags != 0x02) {
            throw new MqttSessionException("malformed PUBREL", null, MqttSessionException.NONE, false);
        }
        int packetId = Wire.readUnsignedShort(plainIn);
        InboundQos2 held = findInbound(packetId);
        if (held != null) {
            inboundQos2.remove(held);
            store.forgetInbound(packetId);
            deliver(held.topic, held.payload, 2, held.retain, held.dup, held.properties);
        }
        pushControl(Wire.PUBCOMP, packetId);
    }

    private void onPubAck(int end) {
        int packetId = Wire.readUnsignedShort(plainIn);
        int reason = readReason(end);
        if (mqtt5 && !V5.successReason(reason)) {
            failSlot(packetId, WAIT_PUBACK, reason);
            return;
        }
        onOutboundAck(packetId, WAIT_PUBACK);
    }

    private void onPubRec(int end) {
        int packetId = Wire.readUnsignedShort(plainIn);
        int reason = readReason(end);
        Slot slot = slotIf(packetId, WAIT_PUBREC);
        if (slot == null) {
            return;
        }
        if (mqtt5 && !V5.successReason(reason)) {
            failSlot(packetId, WAIT_PUBREC, reason);
            return;
        }
        slot.state = WAIT_PUBCOMP;
        store.rememberOutbound(slot.packetId, slot.state, slot.qos, slot.retain, slot.topic, slot.payload, slot.properties);
        releaseQuota();
        markResend(slot);
    }

    private void onPubComp(int end) {
        int packetId = Wire.readUnsignedShort(plainIn);
        int reason = readReason(end);
        if (mqtt5 && !V5.successReason(reason)) {
            failSlot(packetId, WAIT_PUBCOMP, reason);
            return;
        }
        onOutboundAck(packetId, WAIT_PUBCOMP);
    }

    private int readReason(int end) {
        if (!mqtt5 || plainIn.position() >= end) {
            return 0;
        }
        return plainIn.get() & 0xFF;
    }

    /**
     * Broker DISCONNECT reasons the session dials again for. A taken-over client id, a ban,
     * and a protocol or credential refusal stay down.
     */
    static boolean retryBrokerDisconnect(int reason) {
        return switch (reason) {
            case 0x00, 0x80, 0x88, 0x89, 0x8B, 0x8D, 0x96, 0x97, 0x98, 0x9C, 0x9D, 0x9F, 0xA0 -> true;
            default -> false;
        };
    }

    private void onDisconnect(int end) {
        int reason = plainIn.position() < end ? plainIn.get() & 0xFF : 0;
        throw new MqttSessionException("broker disconnected (" + reason + ")", null, reason, retryBrokerDisconnect(reason));
    }

    private void onOutboundAck(int packetId, byte expected) {
        Slot slot = slotIf(packetId, expected);
        if (slot == null) {
            return;
        }
        CompletableFuture<Void> future = slot.future;
        if (expected == WAIT_PUBACK) {
            releaseQuota();
        }
        store.forgetOutbound(packetId);
        release(packetId);
        if (future != null) {
            future.complete(null);
        }
    }

    private void failSlot(int packetId, byte expected, int reason) {
        Slot slot = slotIf(packetId, expected);
        if (slot == null) {
            return;
        }
        CompletableFuture<Void> future = slot.future;
        if (expected == WAIT_PUBACK || expected == WAIT_PUBREC) {
            releaseQuota();
        }
        store.forgetOutbound(packetId);
        release(packetId);
        if (future != null) {
            future.completeExceptionally(new MqttSessionException("publish refused (" + reason + ")", null, reason, false));
        }
    }

    private void releaseQuota() {
        if (sendQuota < serverReceiveMaximum) {
            sendQuota++;
        }
    }

    private void onSubAck(int end) {
        int packetId = Wire.readUnsignedShort(plainIn);
        if (mqtt5) {
            view.reset();
            V5.readProperties(plainIn, view);
        }
        Slot slot = slotIf(packetId, WAIT_SUBACK);
        if (slot == null) {
            return;
        }
        Sub[] group = slot.group == null ? new Sub[]{slot.sub} : slot.group;
        int[] granted = new int[group.length];
        for (int i = 0; i < granted.length && plainIn.hasRemaining(); i++) {
            granted[i] = plainIn.get() & 0xFF;
        }
        release(packetId);
        for (int i = 0; i < group.length; i++) {
            finishSubscribe(group[i], i < granted.length ? granted[i] : 0x80);
        }
    }

    private void finishSubscribe(Sub sub, int granted) {
        if (sub == null || !sub.live) {
            return;
        }
        if (granted == 0x80 || (mqtt5 && !V5.successReason(granted))) {
            sub.live = false;
            MqttSessionException refusal = new MqttSessionException("subscription refused");
            sub.future.completeExceptionally(refusal);
            if (sub.extra != null) {
                sub.extra.completeExceptionally(refusal);
            }
            return;
        }
        sub.acked = true;
        sub.qos = (byte) (granted & 0x03);
        store.rememberSubscription(sub.qos, sub.filterUtf8);
        MqttSubscription handle = new MqttSubscription(session, sub.id, sub.filter.text(), sub.mqttId);
        if (!sub.future.isDone()) {
            sub.future.complete(handle);
        }
        if (sub.extra != null && !sub.extra.isDone()) {
            sub.extra.complete(new MqttSubscription(session, sub.id, sub.filter.text(), sub.mqttId));
        }
    }

    private void onUnsubAck(int packetId) {
        Slot slot = slotIf(packetId, WAIT_UNSUBACK);
        if (slot != null) {
            release(packetId);
        }
    }

    private void deliver(String topic, byte[] payload, int qos, boolean retain, boolean dup, MqttProperties properties) {
        MqttQos level = MqttQos.fromWire(qos);
        for (int i = 0; i < subs.size(); i++) {
            Sub sub = subs.get(i);
            if (!sub.live || sub.listener == null || !sub.filter.matches(topic)) {
                continue;
            }
            Delivery delivery = deliveryPool.take();
            delivery.topic = topic;
            delivery.payload = payload;
            delivery.qos = level;
            delivery.retained = retain;
            delivery.duplicate = dup;
            delivery.listener = sub.listener;
            delivery.rich = mqtt5
                    ? new MqttDelivery(topic, payload, level, retain, dup, properties, view.subscriptionIds.clone())
                    : null;
            if (!overflow.isEmpty() || !deliveries.offer(delivery)) {
                overflow.addLast(delivery);
            }
        }
    }

    private void flushOverflow() {
        while (!overflow.isEmpty() && deliveries.offer(overflow.peek())) {
            overflow.poll();
        }
    }

    private void closeChannel() {
        try {
            if (key != null) {
                key.cancel();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
        channel = null;
        key = null;
        tls = null;
        web = null;
    }

    private void fill() throws IOException {
        stage.clear();
        active = stage;
        stagedSlotCount = 0;
        batchFutureCount = 0;
        while (stage.remaining() >= 4) {
            if (!encodePriority()) {
                break;
            }
        }
        if (phase == PHASE_OPEN) {
            while (stage.remaining() >= 4 && batchFutureCount < batchFutures.length) {
                Work work = outbox.peek();
                if (work == null) {
                    break;
                }
                if (work.kind == Work.PUBLISH && work.qos > 0 && (freeTop == 0 || sendQuota == 0)) {
                    break;
                }
                if ((work.kind == Work.SUBSCRIBE || work.kind == Work.UNSUBSCRIBE) && freeTop == 0) {
                    break;
                }
                int size = workSize(work);
                if (size > stage.capacity() && stage.position() == 0) {
                    outbox.poll();
                    ByteBuffer large = directBuffer(size);
                    large.clear();
                    encodeWork(large, work);
                    large.flip();
                    if (stage.position() > 0) {
                        throw new IllegalStateException("large packet mixed into a batch");
                    }
                    active = large;
                    writing = true;
                    pool.recycle(work);
                    if (store.enabled()) {
                        store.flush();
                    }
                    return;
                }
                if (stage.remaining() < size) {
                    break;
                }
                outbox.poll();
                if (work.kind == Work.DISCONNECT) {
                    Wire.encodeDisconnect(stage);
                    closeAfterWrite = true;
                    pool.recycle(work);
                    break;
                }
                encodeWork(stage, work);
                pool.recycle(work);
            }
        }
        if (stage.position() == 0) {
            return;
        }
        stage.flip();
        active = stage;
        writing = true;
        if (store.enabled()) {
            store.flush();
        }
        lastOutbound = System.nanoTime();
    }

    /** @return false when this priority source needs the current batch to be flushed, or there is nothing to add */
    private boolean encodePriority() {
        if (pendingAuth != null) {
            if (stage.remaining() < pendingAuth.length) {
                return false;
            }
            stage.put(pendingAuth);
            pendingAuth = null;
            return true;
        }
        if (pingDue && stage.remaining() >= 2) {
            Wire.encodePingReq(stage);
            pingDue = false;
            pingWaiting = true;
            return true;
        }
        if (controlRead != controlWrite && stage.remaining() >= 4) {
            int packed = controls[controlRead & (controls.length - 1)];
            controlRead++;
            Wire.encodeAck(stage, packed >>> 16, packed & 0xFFFF);
            return true;
        }
        if (resubCursor >= 0) {
            while (resubCursor < subs.size()) {
                Sub sub = subs.get(resubCursor);
                if (!sub.live) {
                    resubCursor++;
                    continue;
                }
                int size = mqtt5 ? V5.subscribePacketSize(sub.filterUtf8) : 5 + 2 + sub.filterUtf8.length;
                if (freeTop == 0 || stage.remaining() < size) {
                    return false;
                }
                resubCursor++;
                encodeSubscribe(currentOut(), new Sub[]{sub});
                return true;
            }
            resubCursor = -1;
        }
        if (resendCursor < slots.length) {
            while (resendCursor < slots.length) {
                Slot slot = slots[resendCursor];
                resendCursor++;
                if (slot.packetId == 0 || !slot.needResend) {
                    continue;
                }
                int size = resendSize(slot);
                if (stage.remaining() < size) {
                    resendCursor--;
                    return false;
                }
                encodeResend(slot);
                return true;
            }
        }
        return false;
    }

    private void encodeWork(ByteBuffer buf, Work work) {
        switch (work.kind) {
            case Work.PUBLISH -> {
                if (work.qos == 0) {
                    encodeOutboundPublish(buf, work.topic, work.payload, 0, work.retain, false, 0, work.properties);
                    if (work.future != null) {
                        batchFutures[batchFutureCount++] = work.future;
                    }
                } else {
                    int id = alloc();
                    sendQuota--;
                    Slot slot = slots[id - 1];
                    slot.packetId = id;
                    slot.state = work.qos == 1 ? WAIT_PUBACK : WAIT_PUBREC;
                    slot.qos = (byte) work.qos;
                    slot.retain = work.retain;
                    slot.topic = work.topic;
                    slot.payload = work.payload;
                    slot.future = work.future;
                    slot.properties = work.properties;
                    stagedSlots[stagedSlotCount++] = id - 1;
                    store.rememberOutbound(id, slot.state, slot.qos, slot.retain, slot.topic, slot.payload, slot.properties);
                    encodeOutboundPublish(buf, work.topic, work.payload, work.qos, work.retain, false, id, work.properties);
                }
            }
            case Work.SUBSCRIBE -> {
                Sub[] group = work.group == null ? new Sub[]{work.sub} : work.group;
                if (group.length == 1 && attachRestored(group[0])) {
                    break;
                }
                for (Sub sub : group) {
                    subs.add(sub);
                    if (sub.qos == 0 && work.qos != 0) {
                        sub.qos = (byte) work.qos;
                    }
                }
                encodeSubscribe(buf, group);
            }
            case Work.UNSUBSCRIBE -> encodeUnsubscribe(work.subId);
            default -> {
            }
        }
    }

    private boolean attachRestored(Sub incoming) {
        for (int i = 0; i < subs.size(); i++) {
            Sub existing = subs.get(i);
            if (!existing.live || existing.listener != null) {
                continue;
            }
            if (!Arrays.equals(existing.filterUtf8, incoming.filterUtf8)) {
                continue;
            }
            existing.listener = incoming.listener;
            existing.extra = incoming.future;
            if (existing.acked) {
                existing.extra.complete(new MqttSubscription(session, existing.id, existing.filter.text(), existing.mqttId));
            }
            return true;
        }
        return false;
    }

    private void encodeSubscribe(ByteBuffer buf, Sub[] group) {
        int id = alloc();
        Slot slot = slots[id - 1];
        slot.packetId = id;
        slot.state = WAIT_SUBACK;
        slot.sub = group[0];
        slot.group = group;
        slot.filter = group[0].filterUtf8;
        slot.qos = group[0].qos;
        stagedSlots[stagedSlotCount++] = id - 1;
        byte[][] filters = new byte[group.length][];
        int[] qos = new int[group.length];
        for (int i = 0; i < group.length; i++) {
            filters[i] = group[i].filterUtf8;
            qos[i] = group[i].qos;
        }
        if (mqtt5) {
            V5.encodeSubscribe(buf, id, filters, qos, group[0].mqttId);
        } else {
            Wire.encodeSubscribe(buf, id, filters, qos);
        }
    }

    private void encodeUnsubscribe(int subId) {
        Sub found = null;
        for (int i = 0; i < subs.size(); i++) {
            if (subs.get(i).id == subId) {
                found = subs.get(i);
                break;
            }
        }
        if (found == null || !found.live) {
            return;
        }
        found.live = false;
        boolean still = false;
        for (int i = 0; i < subs.size(); i++) {
            Sub other = subs.get(i);
            if (other.live && other.filter.text().equals(found.filter.text())) {
                still = true;
                break;
            }
        }
        if (still || freeTop == 0) {
            return;
        }
        store.forgetSubscription(found.filterUtf8);
        int id = alloc();
        Slot slot = slots[id - 1];
        slot.packetId = id;
        slot.state = WAIT_UNSUBACK;
        slot.filter = found.filterUtf8;
        stagedSlots[stagedSlotCount++] = id - 1;
        if (mqtt5) {
            V5.encodeUnsubscribe(currentOut(), id, found.filterUtf8);
        } else {
            Wire.encodeUnsubscribe(currentOut(), id, found.filterUtf8);
        }
    }

    private void encodeResend(Slot slot) {
        if (slot.needResend) {
            resendPending--;
        }
        slot.needResend = false;
        stagedSlots[stagedSlotCount++] = slot.packetId - 1;
        ByteBuffer buf = currentOut();
        if (slot.state == WAIT_PUBCOMP) {
            Wire.encodeAck(buf, Wire.PUBREL, slot.packetId);
        } else if (slot.state == WAIT_SUBACK) {
            Sub[] group = slot.group == null || slot.group.length == 0
                    ? new Sub[]{slot.sub}
                    : slot.group;
            if (slot.sub == null && group[0] == null) {
                return;
            }
            byte[][] filters = new byte[group.length][];
            int[] qos = new int[group.length];
            for (int i = 0; i < group.length; i++) {
                filters[i] = group[i] == null ? slot.filter : group[i].filterUtf8;
                qos[i] = group[i] == null ? slot.qos : group[i].qos;
            }
            if (mqtt5) {
                int mqttId = group[0] == null ? 0 : group[0].mqttId;
                V5.encodeSubscribe(buf, slot.packetId, filters, qos, mqttId);
            } else {
                Wire.encodeSubscribe(buf, slot.packetId, filters, qos);
            }
        } else if (slot.state == WAIT_UNSUBACK) {
            if (mqtt5) {
                V5.encodeUnsubscribe(buf, slot.packetId, slot.filter);
            } else {
                Wire.encodeUnsubscribe(buf, slot.packetId, slot.filter);
            }
        } else {
            encodeOutboundPublish(buf, slot.topic, slot.payload, slot.qos, slot.retain, true, slot.packetId, slot.properties);
        }
    }

    private void encodeOutboundPublish(ByteBuffer buf, byte[] topic, byte[] payload, int qos, boolean retain, boolean dup,
                                      int packetId, MqttProperties properties) {
        if (!mqtt5) {
            Wire.encodePublish(buf, topic, payload, qos, retain, dup, packetId);
            return;
        }
        int alias = selectAlias(topic, false);
        byte[] wireTopic = topic;
        int wireAlias = 0;
        if (alias > 0) {
            wireTopic = EMPTY;
            wireAlias = alias;
        } else if (alias < 0) {
            wireAlias = selectAlias(topic, true);
        }
        V5.encodePublish(buf, wireTopic, payload, qos, retain, dup, packetId, wireAlias, properties);
    }

    /** Positive is an existing alias, negative means the next encode will assign one, zero means no alias. */
    private int selectAlias(byte[] topic, boolean assign) {
        if (!mqtt5 || serverTopicAliasMaximum <= 0) {
            return 0;
        }
        for (int i = 1; i <= outboundAliasCount; i++) {
            if (Arrays.equals(outboundAliases[i], topic)) {
                return i;
            }
        }
        if (outboundAliasCount >= serverTopicAliasMaximum || outboundAliases.length <= outboundAliasCount + 1) {
            return 0;
        }
        if (!assign) {
            return -1;
        }
        outboundAliases[++outboundAliasCount] = topic.clone();
        return outboundAliasCount;
    }

    private ByteBuffer currentOut() {
        return active == null ? stage : active;
    }

    private int workSize(Work work) {
        return switch (work.kind) {
            case Work.PUBLISH -> outboundPublishSize(work.topic, work.payload, work.qos, work.properties);
            case Work.SUBSCRIBE -> subscribeSize(work.group == null ? new Sub[]{work.sub} : work.group);
            case Work.UNSUBSCRIBE -> unsubscribeSize(work.subId);
            case Work.DISCONNECT -> 2;
            default -> 2;
        };
    }

    private int unsubscribeSize(int subId) {
        for (int i = 0; i < subs.size(); i++) {
            Sub sub = subs.get(i);
            if (sub.id == subId) {
                return mqtt5 ? V5.unsubscribePacketSize(sub.filterUtf8) : 4 + 2 + sub.filterUtf8.length;
            }
        }
        return 2;
    }

    private int outboundPublishSize(byte[] topic, byte[] payload, int qos, MqttProperties properties) {
        if (!mqtt5) {
            return Wire.publishSize(topic.length, payload.length, qos);
        }
        int alias = selectAlias(topic, false);
        int topicLen = alias > 0 ? 0 : topic.length;
        int wireAlias = alias == 0 ? 0 : 1;
        return V5.publishPacketSize(topicLen, payload.length, qos, wireAlias, properties);
    }

    private int resendSize(Slot slot) {
        if (slot.state == WAIT_PUBCOMP) {
            return 4;
        }
        if (slot.state == WAIT_SUBACK) {
            Sub[] group = slot.group == null ? new Sub[]{slot.sub} : slot.group;
            return subscribeSize(group);
        }
        if (slot.state == WAIT_UNSUBACK) {
            return mqtt5 ? V5.unsubscribePacketSize(slot.filter) : 4 + 2 + (slot.filter == null ? 0 : slot.filter.length);
        }
        return outboundPublishSize(slot.topic, slot.payload, slot.qos, slot.properties);
    }

    private void writeSome() throws IOException {
        if (web != null) {
            web.write(active);
            if (active.hasRemaining() || web.pending()) {
                return;
            }
        } else if (tls == null) {
            channel.write(active);
        } else {
            tls.wrap(channel, active);
        }
        if (active.hasRemaining()) {
            return;
        }
        if (web == null && tls != null && tls.pendingCipher()) {
            return;
        }
        long now = System.nanoTime();
        for (int i = 0; i < stagedSlotCount; i++) {
            Slot slot = slots[stagedSlots[i]];
            slot.onWire = true;
            slot.retryAt = now + retryNanos;
        }
        for (int i = 0; i < batchFutureCount; i++) {
            batchFutures[i].complete(null);
            batchFutures[i] = null;
        }
        stagedSlotCount = 0;
        batchFutureCount = 0;
        writing = false;
        if (active != stage) {
            active = stage;
        }
        lastOutbound = now;
    }

    private void timers() {
        long now = System.nanoTime();
        if (keepAliveNanos > 0 && !pingWaiting && now - lastOutbound >= keepAliveNanos) {
            pingDue = true;
        }
        if (keepAliveNanos > 0 && now - lastInbound >= keepAliveNanos + keepAliveNanos / 2) {
            throw new MqttSessionException("keepalive timed out");
        }
        if (resendCursor >= slots.length) {
            boolean again = false;
            for (Slot slot : slots) {
                if (slot.packetId != 0 && slot.onWire && now >= slot.retryAt) {
                    markResend(slot);
                    again = true;
                }
            }
            if (again) {
                resendCursor = 0;
            }
        }
    }

    private void markResend(Slot slot) {
        if (!slot.needResend) {
            resendPending++;
        }
        slot.needResend = true;
        slot.onWire = false;
        resendCursor = 0;
    }

    private void pushControl(int type, int packetId) {
        if (controlWrite - controlRead == controls.length) {
            throw new MqttSessionException("control queue overflow");
        }
        controls[controlWrite & (controls.length - 1)] = (type << 16) | packetId;
        controlWrite++;
    }

    private int alloc() {
        if (freeTop == 0) {
            throw new MqttSessionException("in-flight window is full");
        }
        return freeIds[--freeTop];
    }

    private void release(int packetId) {
        Slot slot = slots[packetId - 1];
        if (slot.packetId == 0) {
            return;
        }
        if (slot.needResend && resendPending > 0) {
            resendPending--;
        }
        slot.clear();
        freeIds[freeTop++] = packetId;
    }

    private void takeId(int packetId) {
        for (int i = 0; i < freeTop; i++) {
            if (freeIds[i] == packetId) {
                freeIds[i] = freeIds[--freeTop];
                return;
            }
        }
        throw new MqttSessionException("persisted packet id is outside the in-flight window");
    }

    private int subscribeSize(Sub[] group) {
        if (group == null || group.length == 0 || group[0] == null) {
            return 2;
        }
        if (mqtt5) {
            byte[][] filters = new byte[group.length][];
            for (int i = 0; i < group.length; i++) {
                filters[i] = group[i].filterUtf8;
            }
            return V5.subscribePacketSize(filters, group[0].mqttId);
        }
        int remaining = 2;
        for (Sub sub : group) {
            remaining += 2 + sub.filterUtf8.length + 1;
        }
        return 2 + remaining;
    }

    private void restore(MqttSessionImage image) {
        for (MqttSessionImage.Outbound out : image.outbound()) {
            if (out.packetId() == 0) {
                Work work = pool.take();
                work.kind = Work.PUBLISH;
                work.topic = out.topic();
                work.payload = out.payload();
                work.qos = out.qos();
                work.retain = out.retain();
                work.properties = out.properties();
                if (!outbox.offer(work)) {
                    pool.recycle(work);
                }
                continue;
            }
            if (out.packetId() < 0 || out.packetId() > slots.length) {
                continue;
            }
            takeId(out.packetId());
            Slot slot = slots[out.packetId() - 1];
            slot.packetId = out.packetId();
            slot.state = (byte) out.state();
            slot.qos = (byte) out.qos();
            slot.retain = out.retain();
            slot.topic = out.topic();
            slot.payload = out.payload();
            slot.properties = out.properties();
            slot.needResend = true;
            resendPending++;
        }
        for (MqttSessionImage.Inbound in : image.inbound()) {
            String topic = new String(in.topic(), StandardCharsets.UTF_8);
            inboundQos2.add(new InboundQos2(in.packetId(), topic, in.payload(), in.retain(), in.dup(), in.properties()));
        }
        for (MqttSessionImage.Subscription saved : image.subscriptions()) {
            String text = new String(saved.filter(), StandardCharsets.UTF_8);
            Sub sub = new Sub(nextSubId.getAndIncrement(), TopicFilter.compile(text), saved.filter(), null, new CompletableFuture<>());
            sub.qos = (byte) saved.qos();
            subs.add(sub);
        }
    }

    private void resetAliases() {
        outboundAliasCount = 0;
        if (outboundAliases != null) {
            Arrays.fill(outboundAliases, null);
        }
        if (inboundAliasTopic != null) {
            Arrays.fill(inboundAliasTopic, null);
        }
    }

    private Slot slotIf(int packetId, byte state) {
        if (packetId <= 0 || packetId > slots.length) {
            return null;
        }
        Slot slot = slots[packetId - 1];
        if (slot.packetId != packetId || slot.state != state) {
            return null;
        }
        return slot;
    }

    private InboundQos2 findInbound(int packetId) {
        for (int i = 0; i < inboundQos2.size(); i++) {
            if (inboundQos2.get(i).packetId == packetId) {
                return inboundQos2.get(i);
            }
        }
        return null;
    }

    private void tearDown(Throwable failure) {
        open = false;
        writing = false;
        phase = 0;
        closeAfterWrite = false;
        pingDue = false;
        pingWaiting = false;
        resubCursor = -1;
        MqttSessionException reported = failure instanceof MqttSessionException mqtt
                ? mqtt
                : new MqttSessionException(failure.getMessage(), failure);
        for (int i = 0; i < batchFutureCount; i++) {
            batchFutures[i].completeExceptionally(reported);
            batchFutures[i] = null;
        }
        batchFutureCount = 0;
        stagedSlotCount = 0;
        if (config.cleanSession()) {
            inboundQos2.clear();
            store.clearInbound();
        }
        controlRead = 0;
        controlWrite = 0;
        while (!overflow.isEmpty()) {
            deliveryPool.recycle(overflow.poll());
        }
        for (Slot slot : slots) {
            if (slot.packetId != 0 && (slot.state == WAIT_SUBACK || slot.state == WAIT_UNSUBACK)) {
                release(slot.packetId);
            }
        }
        try {
            if (key != null) {
                key.cancel();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
        channel = null;
        key = null;
        tls = null;
        web = null;
        clearSelected();
    }

    private void failQueued(MqttSessionException failure) {
        Work work;
        while ((work = outbox.poll()) != null) {
            if (work.future != null) {
                work.future.completeExceptionally(failure);
            }
            if (work.sub != null && !work.sub.future.isDone()) {
                work.sub.future.completeExceptionally(failure);
            }
            pool.recycle(work);
        }
    }

    private void failSlots(MqttSessionException failure) {
        for (Slot slot : slots) {
            if (slot.packetId == 0) {
                continue;
            }
            if (slot.future != null) {
                slot.future.completeExceptionally(failure);
            }
            release(slot.packetId);
        }
    }

    private void failSubs(MqttSessionException failure) {
        for (int i = 0; i < subs.size(); i++) {
            Sub sub = subs.get(i);
            if (!sub.future.isDone()) {
                sub.future.completeExceptionally(failure);
            }
        }
    }

    private void clearSelected() {
        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
        while (keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private static ByteBuffer grow(ByteBuffer buffer, int capacity) {
        ByteBuffer larger = ByteBuffer.allocate(capacity);
        buffer.flip();
        larger.put(buffer);
        return larger;
    }

    private static void checkTopicBytes(byte[] topic) {
        if (topic == null || topic.length == 0 || topic.length > 65_535) {
            throw new IllegalArgumentException("topic is required and must be at most 65535 bytes");
        }
        for (byte value : topic) {
            if (value == 0 || value == '+' || value == '#') {
                throw new IllegalArgumentException("topic name must not contain NUL or a wildcard");
            }
        }
    }

    private void checkPayload(byte[] payload, MqttQos qos) {
        checkPayload(payload, qos, null);
    }

    private void checkPayload(byte[] payload, MqttQos qos, MqttProperties properties) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        int qosWire = qos == null ? 0 : qos.wire();
        int size = mqtt5
                ? V5.publishPacketSize(1, payload.length, qosWire, 0, properties)
                : Wire.publishSize(1, payload.length, qosWire);
        if (size > peerMaxPacket) {
            throw new IllegalArgumentException("packet exceeds maxPacketBytes");
        }
    }

    private static String connackText(int code) {
        return switch (code) {
            case 1 -> "unacceptable protocol version";
            case 2 -> "identifier rejected";
            case 3 -> "server unavailable";
            case 4 -> "bad user name or password";
            case 5 -> "not authorized";
            default -> "CONNACK refusal " + code;
        };
    }

    private void safeEvent(Runnable hook) {
        try {
            hook.run();
        } catch (Throwable ignored) {
        }
    }

    private void callbackLoop() {
        while (!closing.get()) {
            Delivery delivery;
            try {
                delivery = deliveries.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (closing.get()) {
                    return;
                }
                continue;
            }
            if (delivery == null || delivery == Delivery.POISON) {
                continue;
            }
            try {
                if (delivery.rich != null) {
                    delivery.listener.onMessage(delivery.rich);
                } else {
                    delivery.listener.onMessage(delivery.topic, delivery.payload, delivery.qos, delivery.retained, delivery.duplicate);
                }
            } catch (Throwable failure) {
                safeEvent(() -> events.listenerThrew(failure));
            } finally {
                deliveryPool.recycle(delivery);
            }
        }
    }

    private static final class ForcedReconnect extends MqttSessionException {
        ForcedReconnect() {
            super("reconnect requested");
        }
    }

    private record Remote(String host, int port, boolean tls, boolean websocket, String path) {
        static Remote parse(String endpoint) {
            String rest = endpoint;
            boolean tls;
            boolean websocket = false;
            if (rest.startsWith("tcp://")) {
                rest = rest.substring(6);
                tls = false;
            } else if (rest.startsWith("mqtt://")) {
                rest = rest.substring(7);
                tls = false;
            } else if (rest.startsWith("ssl://")) {
                rest = rest.substring(6);
                tls = true;
            } else if (rest.startsWith("tls://")) {
                rest = rest.substring(6);
                tls = true;
            } else if (rest.startsWith("mqtts://")) {
                rest = rest.substring(8);
                tls = true;
            } else if (rest.startsWith("ws://")) {
                rest = rest.substring(5);
                tls = false;
                websocket = true;
            } else if (rest.startsWith("wss://")) {
                rest = rest.substring(6);
                tls = true;
                websocket = true;
            } else {
                throw new IllegalArgumentException("endpoint must start with tcp://, mqtt://, ssl://, tls://, mqtts://, ws:// or wss://");
            }
            String host;
            String portText;
            if (rest.startsWith("[")) {
                int end = rest.indexOf(']');
                if (end < 0 || end + 1 >= rest.length() || rest.charAt(end + 1) != ':') {
                    throw new IllegalArgumentException("IPv6 endpoint must look like tcp://[::1]:1883");
                }
                host = rest.substring(1, end);
                portText = rest.substring(end + 2);
            } else {
                int colon = rest.lastIndexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("endpoint is missing a port");
                }
                host = rest.substring(0, colon);
                portText = rest.substring(colon + 1);
            }
            String path = "/mqtt";
            int slash = portText.indexOf('/');
            if (slash >= 0) {
                if (!websocket) {
                    throw new IllegalArgumentException("only a websocket endpoint accepts a path");
                }
                path = portText.substring(slash);
                if (path.isEmpty()) {
                    path = "/mqtt";
                }
                portText = portText.substring(0, slash);
            }
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad port in endpoint");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port out of range");
            }
            return new Remote(host, port, tls, websocket, path);
        }
    }

    private static final class Slot {
        int packetId;
        byte state;
        byte qos;
        boolean retain;
        boolean onWire;
        boolean needResend;
        long retryAt;
        byte[] topic;
        byte[] payload;
        byte[] filter;
        Sub sub;
        Sub[] group;
        CompletableFuture<Void> future;
        MqttProperties properties;

        void clear() {
            packetId = 0;
            state = 0;
            qos = 0;
            retain = false;
            onWire = false;
            needResend = false;
            topic = null;
            payload = null;
            filter = null;
            sub = null;
            group = null;
            future = null;
            properties = null;
        }
    }

    private static final class Sub {
        final int id;
        final TopicFilter filter;
        final byte[] filterUtf8;
        MqttListener listener;
        final CompletableFuture<MqttSubscription> future;
        CompletableFuture<MqttSubscription> extra;
        byte qos;
        int mqttId;
        boolean live = true;
        boolean acked;

        Sub(int id, TopicFilter filter, byte[] filterUtf8, MqttListener listener, CompletableFuture<MqttSubscription> future) {
            this.id = id;
            this.filter = filter;
            this.filterUtf8 = filterUtf8;
            this.listener = listener;
            this.future = future;
        }
    }

    private record InboundQos2(int packetId, String topic, byte[] payload, boolean retain, boolean dup, MqttProperties properties) {
    }

    private static final class Work {
        static final int PUBLISH = 1;
        static final int SUBSCRIBE = 2;
        static final int UNSUBSCRIBE = 3;
        static final int DISCONNECT = 4;

        Work next;
        int kind;
        byte[] topic;
        byte[] payload;
        int qos;
        boolean retain;
        CompletableFuture<Void> future;
        MqttProperties properties;
        Sub sub;
        Sub[] group;
        int subId;

        void clear() {
            kind = 0;
            topic = null;
            payload = null;
            qos = 0;
            retain = false;
            future = null;
            properties = null;
            sub = null;
            group = null;
            subId = 0;
        }
    }

    private static final class WorkPool {
        private final AtomicReference<Work> head = new AtomicReference<>();

        Work take() {
            Work current;
            do {
                current = head.get();
                if (current == null) {
                    return new Work();
                }
            } while (!head.compareAndSet(current, current.next));
            current.next = null;
            return current;
        }

        void recycle(Work work) {
            work.clear();
            Work current;
            do {
                current = head.get();
                work.next = current;
            } while (!head.compareAndSet(current, work));
        }
    }

    private static final class Delivery {
        static final Delivery POISON = new Delivery();

        Delivery next;
        String topic;
        byte[] payload;
        MqttQos qos;
        boolean retained;
        boolean duplicate;
        MqttListener listener;
        MqttDelivery rich;

        void clear() {
            topic = null;
            payload = null;
            qos = null;
            retained = false;
            duplicate = false;
            listener = null;
            rich = null;
        }
    }

    private static final class DeliveryPool {
        private final AtomicReference<Delivery> head = new AtomicReference<>();

        Delivery take() {
            Delivery current;
            do {
                current = head.get();
                if (current == null) {
                    return new Delivery();
                }
            } while (!head.compareAndSet(current, current.next));
            current.next = null;
            return current;
        }

        void recycle(Delivery delivery) {
            if (delivery == Delivery.POISON) {
                return;
            }
            delivery.clear();
            Delivery current;
            do {
                current = head.get();
                delivery.next = current;
            } while (!head.compareAndSet(current, delivery));
        }
    }

    private static final class TlsLink {
        final SSLEngine engine;
        ByteBuffer netIn;
        ByteBuffer netOut;

        private TlsLink(SSLEngine engine) {
            this.engine = engine;
            int packet = engine.getSession().getPacketBufferSize();
            this.netIn = ByteBuffer.allocateDirect(packet);
            this.netOut = ByteBuffer.allocate(0);
        }

        boolean pendingCipher() {
            return netOut.hasRemaining();
        }

        static TlsLink handshake(SocketChannel channel, SelectionKey key, Selector selector, String host, int port,
                                 SSLContext context, long deadline, AtomicBoolean closing,
                                 boolean verifyHostname, String serverName, HostnameVerifier verifier) throws IOException {
            SSLContext ssl = context;
            if (ssl == null) {
                try {
                    ssl = SSLContext.getDefault();
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new MqttSessionException("no default TLS context", e);
                }
            }
            String sni = serverName == null || serverName.isBlank() ? host : serverName;
            SSLEngine engine = ssl.createSSLEngine(sni, port);
            engine.setUseClientMode(true);
            SSLParameters parameters = engine.getSSLParameters();
            if (verifyHostname) {
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
            }
            engine.setSSLParameters(parameters);
            TlsLink link = new TlsLink(engine);
            ByteBuffer scratch = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
            engine.beginHandshake();
            while (true) {
                if (System.nanoTime() > deadline || closing.get()) {
                    throw new MqttSessionException(closing.get() ? "closed" : "TLS handshake timed out");
                }
                switch (engine.getHandshakeStatus()) {
                    case NOT_HANDSHAKING, FINISHED -> {
                        if (verifier != null && !verifier.verify(host, engine.getSession())) {
                            throw new MqttSessionException("hostname verification failed");
                        }
                        return link;
                    }
                    case NEED_TASK -> link.tasks();
                    case NEED_WRAP -> {
                        link.netOut.clear();
                        SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), link.netOut);
                        link.tasks();
                        link.netOut.flip();
                        while (link.netOut.hasRemaining()) {
                            if (channel.write(link.netOut) == 0) {
                                key.interestOps(SelectionKey.OP_WRITE);
                                selector.select(50);
                                clear(selector);
                            }
                        }
                        if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                            throw new MqttSessionException("TLS closed during handshake");
                        }
                    }
                    case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> {
                        if (link.netIn.position() == 0) {
                            key.interestOps(SelectionKey.OP_READ);
                            selector.select(50);
                            clear(selector);
                            int read = channel.read(link.netIn);
                            if (read < 0) {
                                throw new MqttSessionException("TLS closed during handshake");
                            }
                        }
                        link.netIn.flip();
                        SSLEngineResult result = engine.unwrap(link.netIn, scratch);
                        link.netIn.compact();
                        link.tasks();
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            scratch = ByteBuffer.allocate(scratch.capacity() * 2);
                        } else if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                            throw new MqttSessionException("TLS closed during handshake");
                        }
                    }
                    default -> throw new MqttSessionException("unexpected TLS handshake state");
                }
            }
        }

        boolean unwrap(SocketChannel channel, ByteBuffer plain) throws IOException {
            int read = channel.read(netIn);
            boolean eof = read < 0;
            if (netIn.position() == 0) {
                return !eof;
            }
            netIn.flip();
            while (netIn.hasRemaining()) {
                if (plain.remaining() < engine.getSession().getApplicationBufferSize()) {
                    netIn.compact();
                    return !eof;
                }
                SSLEngineResult result = engine.unwrap(netIn, plain);
                tasks();
                if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW
                        || result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    break;
                }
                if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                    netIn.compact();
                    return false;
                }
            }
            netIn.compact();
            return !eof;
        }

        void wrap(SocketChannel channel, ByteBuffer plain) throws IOException {
            if (!netOut.hasRemaining() && plain.hasRemaining()) {
                netOut.clear();
                SSLEngineResult result;
                try {
                    result = engine.wrap(plain, netOut);
                } catch (SSLException e) {
                    throw new IOException(e);
                }
                tasks();
                if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    netOut = ByteBuffer.allocateDirect(netOut.capacity() * 2);
                    return;
                }
                netOut.flip();
            }
            if (netOut.hasRemaining()) {
                channel.write(netOut);
            }
        }

        private void tasks() {
            Runnable task;
            while ((task = engine.getDelegatedTask()) != null) {
                task.run();
            }
        }

        private static void clear(Selector selector) {
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                keys.next();
                keys.remove();
            }
        }
    }

    private static final class WebSocketLink {
        private final SocketChannel channel;
        private final TlsLink tls;
        private final WebSocketCodec codec = new WebSocketCodec(true);
        private final ByteBuffer raw = ByteBuffer.allocate(64 * 1024);
        private ByteBuffer framed = ByteBuffer.allocateDirect(4096);

        private WebSocketLink(SocketChannel channel, TlsLink tls) {
            this.channel = channel;
            this.tls = tls;
            this.framed.limit(0);
        }

        static WebSocketLink handshake(SocketChannel channel, SelectionKey key, Selector selector, TlsLink tls,
                                       Remote remote, long deadline, AtomicBoolean closing,
                                       java.util.Map<String, String> headers) throws IOException {
            byte[] nonce = new byte[16];
            java.util.concurrent.ThreadLocalRandom.current().nextBytes(nonce);
            ByteBuffer request = WebSocketCodec.clientRequest(remote.host, remote.port, remote.path, nonce, headers);
            writeFully(channel, key, selector, tls, request, deadline, closing);
            ByteBuffer response = ByteBuffer.allocate(2048);
            int headerEnd = -1;
            while (headerEnd < 0) {
                if (System.nanoTime() > deadline || closing.get()) {
                    throw new MqttSessionException(closing.get() ? "closed" : "websocket handshake timed out");
                }
                int before = response.position();
                int read = readApplication(channel, key, selector, tls, response, deadline, closing);
                if (read < 0 && response.position() == before) {
                    throw new MqttSessionException("websocket closed during handshake");
                }
                headerEnd = indexOfHeaderEnd(response);
            }
            byte[] ascii = new byte[headerEnd];
            response.flip();
            response.get(ascii);
            try {
                WebSocketCodec.verifyResponse(new String(ascii, java.nio.charset.StandardCharsets.US_ASCII), nonce);
            } catch (IllegalArgumentException e) {
                throw new MqttSessionException(e.getMessage(), e);
            }
            WebSocketLink link = new WebSocketLink(channel, tls);
            if (response.hasRemaining()) {
                link.raw.put(response);
            }
            return link;
        }

        void write(ByteBuffer mqtt) throws IOException {
            if (!framed.hasRemaining()) {
                flushControl();
            }
            if (!framed.hasRemaining() && mqtt.hasRemaining()) {
                int need = mqtt.remaining() + 14;
                if (framed.capacity() < need) {
                    framed = ByteBuffer.allocateDirect(need);
                }
                codec.encodeBinary(mqtt, framed);
            }
            if (framed.hasRemaining()) {
                writeRaw(framed);
            }
        }

        boolean pending() {
            return framed.hasRemaining() || codec.hasControl();
        }

        boolean read(ByteBuffer plain) throws IOException {
            int read = readRaw(raw);
            if (read < 0 && raw.position() == 0) {
                return false;
            }
            boolean open;
            try {
                open = codec.pull(raw, plain);
            } catch (IllegalArgumentException e) {
                throw new MqttSessionException(e.getMessage(), e, MqttSessionException.NONE, false);
            }
            flushControl();
            return open;
        }

        private void flushControl() throws IOException {
            if (!codec.hasControl() || framed.hasRemaining()) {
                return;
            }
            writeRaw(codec.control());
            if (!codec.control().hasRemaining()) {
                codec.control().clear();
                codec.control().limit(0);
            }
        }

        private void writeRaw(ByteBuffer src) throws IOException {
            if (tls == null) {
                channel.write(src);
            } else {
                tls.wrap(channel, src);
            }
        }

        private int readRaw(ByteBuffer dst) throws IOException {
            if (tls == null) {
                return channel.read(dst);
            }
            int before = dst.position();
            boolean ok = tls.unwrap(channel, dst);
            if (!ok && dst.position() == before) {
                return -1;
            }
            return dst.position() - before;
        }

        private static void writeFully(SocketChannel channel, SelectionKey key, Selector selector, TlsLink tls,
                                       ByteBuffer data, long deadline, AtomicBoolean closing) throws IOException {
            while (data.hasRemaining() || (tls != null && tls.pendingCipher())) {
                if (System.nanoTime() > deadline || closing.get()) {
                    throw new MqttSessionException(closing.get() ? "closed" : "websocket handshake timed out");
                }
                int wrote;
                if (tls == null) {
                    wrote = channel.write(data);
                } else {
                    int before = data.position();
                    tls.wrap(channel, data);
                    wrote = data.position() - before;
                    if (tls.pendingCipher()) {
                        tls.wrap(channel, ByteBuffer.allocate(0));
                    }
                }
                if (wrote == 0 && data.hasRemaining()) {
                    key.interestOps(SelectionKey.OP_WRITE);
                    selector.select(50);
                    TlsLink.clear(selector);
                }
            }
        }

        private static int readApplication(SocketChannel channel, SelectionKey key, Selector selector, TlsLink tls,
                                           ByteBuffer dst, long deadline, AtomicBoolean closing) throws IOException {
            key.interestOps(SelectionKey.OP_READ);
            long wait = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            selector.select(Math.min(wait, 200));
            TlsLink.clear(selector);
            if (tls == null) {
                return channel.read(dst);
            }
            int before = dst.position();
            boolean ok = tls.unwrap(channel, dst);
            if (!ok && dst.position() == before) {
                return -1;
            }
            return dst.position() - before;
        }

        private static int indexOfHeaderEnd(ByteBuffer response) {
            int limit = response.position();
            for (int i = 0; i + 3 < limit; i++) {
                if (response.get(i) == '\r' && response.get(i + 1) == '\n'
                        && response.get(i + 2) == '\r' && response.get(i + 3) == '\n') {
                    return i + 4;
                }
            }
            if (limit > 8192) {
                throw new MqttSessionException("websocket handshake is too large");
            }
            return -1;
        }
    }
}
