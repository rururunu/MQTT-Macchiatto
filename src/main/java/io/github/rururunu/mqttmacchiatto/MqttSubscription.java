package io.github.rururunu.mqttmacchiatto;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An active subscription. {@link #close()} sends UNSUBSCRIBE when this was the last
 * local listener for the filter.
 */
public final class MqttSubscription implements AutoCloseable {
    private final MqttSession session;
    private final int id;
    private final String filter;
    private final int subscriptionId;
    private final AtomicBoolean closed = new AtomicBoolean();

    public MqttSubscription(MqttSession session, int id, String filter) {
        this(session, id, filter, 0);
    }

    public MqttSubscription(MqttSession session, int id, String filter, int subscriptionId) {
        this.session = session;
        this.id = id;
        this.filter = filter;
        this.subscriptionId = subscriptionId;
    }

    public String filter() {
        return filter;
    }

    public int id() {
        return id;
    }

    /** MQTT 5 subscription identifier, or {@code 0} when the session is not MQTT 5. */
    public int subscriptionId() {
        return subscriptionId;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            session.dropSubscription(id);
        }
    }
}
