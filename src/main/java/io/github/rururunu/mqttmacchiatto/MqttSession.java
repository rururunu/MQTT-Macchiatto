package io.github.rururunu.mqttmacchiatto;

import io.github.rururunu.mqttmacchiatto.internal.MqttEngine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * One MQTT 3.1.1 or MQTT 5 connection. A session owns the socket, the in-flight window, and the
 * subscriptions. Publish and subscribe may be called from any thread.
 *
 * <p>Payload and topic arrays passed to {@link #publish} and {@link #emit} are not copied.
 * Do not mutate them until the returned stage completes. Arrays passed to {@link #emit}
 * are borrowed with no completion signal: treat them as immutable.
 */
public final class MqttSession implements AutoCloseable {
    final MqttEngine engine;

    private MqttSession(MqttConfig config) {
        this.engine = new MqttEngine(config, this);
    }

    /**
     * Connects with a clean session and {@link ReconnectPolicy#standard()}.
     * Blocks until the first CONNACK.
     */
    public static MqttSession connect(String endpoint, String clientId) {
        return connect(endpoint, clientId, true);
    }

    /**
     * Connects with {@link ReconnectPolicy#standard()}.
     * {@code cleanSession} false asks the broker to keep the session.
     * Blocks until the first CONNACK.
     */
    public static MqttSession connect(String endpoint, String clientId, boolean cleanSession) {
        return connect(MqttConfig.builder(endpoint).clientId(clientId).cleanSession(cleanSession).build());
    }

    /** Blocks until the first CONNACK, retrying as configured. */
    public static MqttSession connect(MqttConfig config) {
        MqttSession session = new MqttSession(config);
        session.engine.start();
        try {
            session.engine.awaitConnected();
            return session;
        } catch (Throwable failure) {
            session.close();
            if (failure instanceof MqttSessionException mqtt) {
                throw mqtt;
            }
            throw new MqttSessionException("connect failed", failure);
        }
    }

    public String clientId() {
        return engine.clientId();
    }

    public boolean connected() {
        return engine.connected();
    }

    /** The last CONNACK, or {@code null} before the broker accepts the connection. */
    public MqttConnAck connection() {
        return engine.connection();
    }

    /** The current connection cycle. This does not touch the socket. */
    public MqttLink link() {
        return engine.link();
    }

    /**
     * Dial again on this session. An open socket is dropped. A retry that is waiting starts now.
     * In-flight QoS 1 and 2 messages and the subscriptions stay with the session.
     */
    public void reconnect() {
        engine.reconnect();
    }

    /** QoS 0, not retained. */
    public CompletableFuture<Void> publish(String topic, byte[] payload) {
        return publish(topic, payload, MqttQos.AT_MOST_ONCE, false);
    }

    /** Not retained. */
    public CompletableFuture<Void> publish(String topic, byte[] payload, MqttQos qos) {
        return publish(topic, payload, qos, false);
    }

    /** UTF-8 text, not retained. The encoded bytes are not shared with the caller. */
    public CompletableFuture<Void> publish(String topic, String text, MqttQos qos) {
        return publish(topic, Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), qos, false);
    }

    public CompletableFuture<Void> publish(String topic, byte[] payload, MqttQos qos, boolean retained) {
        return engine.publish(topic, payload, qos, retained);
    }

    public CompletableFuture<Void> publish(byte[] topicUtf8, byte[] payload, MqttQos qos, boolean retained) {
        return engine.publish(topicUtf8, payload, qos, retained, null);
    }

    /** MQTT 5 publish. {@code properties} is ignored on an MQTT 3.1.1 session. */
    public CompletableFuture<Void> publish(byte[] topicUtf8, byte[] payload, MqttQos qos, boolean retained,
                                           MqttProperties properties) {
        return engine.publish(topicUtf8, payload, qos, retained, properties);
    }

    public CompletableFuture<Void> publish(String topic, byte[] payload, MqttQos qos, boolean retained,
                                           MqttProperties properties) {
        return engine.publish(topic, payload, qos, retained, properties);
    }

    /**
     * QoS 0, not retained. Does not allocate once the outbound pool is warm.
     * The arrays are borrowed: do not mutate them until the write has gone out.
     */
    public void emit(byte[] topicUtf8, byte[] payload) {
        engine.emit(topicUtf8, payload);
    }

    /**
     * QoS 0, not retained. Encodes {@code topic} as UTF-8 on each call.
     * {@code payload} is borrowed: do not mutate it until the write has gone out.
     */
    public void emit(String topic, byte[] payload) {
        engine.emit(Objects.requireNonNull(topic, "topic").getBytes(StandardCharsets.UTF_8), payload);
    }

    /**
     * Subscribes at {@link MqttQos#AT_LEAST_ONCE}. The listener receives the topic and the payload.
     * Blocks until SUBACK.
     */
    public MqttSubscription subscribe(String filter, BiConsumer<String, byte[]> listener) {
        return subscribe(filter, MqttQos.AT_LEAST_ONCE, listener);
    }

    /**
     * The listener receives the topic and the payload. Use {@link MqttListener} when the callback
     * also needs QoS, the retained flag, or the duplicate flag. Blocks until SUBACK.
     */
    public MqttSubscription subscribe(String filter, MqttQos qos, BiConsumer<String, byte[]> listener) {
        Objects.requireNonNull(listener, "listener");
        return subscribe(filter, qos, (topic, body, ignoredQos, retained, duplicate) -> listener.accept(topic, body));
    }

    /** Blocks until SUBACK. The listener runs on the session callback thread. */
    public MqttSubscription subscribe(String filter, MqttQos qos, MqttListener listener) {
        return engine.subscribe(filter, qos, listener);
    }

    /**
     * One SUBSCRIBE packet for every filter. On MQTT 5 the filters share one subscription identifier.
     * Blocks until every reason code arrives and throws if any filter is refused.
     */
    public List<MqttSubscription> subscribe(MqttSubscriptionRequest... requests) {
        return engine.subscribe(requests);
    }

    void dropSubscription(int id) {
        engine.unsubscribe(id);
    }

    @Override
    public void close() {
        engine.close();
    }
}
