package io.github.rururunu.mqttmacchiatto;

/**
 * Optional lifecycle hooks. Invoked on the session I/O thread except {@link #listenerThrew},
 * which runs on the callback thread. Implementations must return quickly.
 */
public interface MqttEvents {
    default void connected() {
    }

    /** The accepted CONNACK, including the server limits that are actually in force. */
    default void connAck(MqttConnAck ack) {
    }

    default void disconnected(Throwable cause) {
    }

    /**
     * A retry has been scheduled, or the session is moving to another endpoint without waiting.
     * {@code link} is the state at the decision. Return quickly: this runs on the I/O thread.
     * Poll {@link MqttSession#link()} when the work can wait.
     */
    default void reconnecting(MqttLink link) {
    }

    default void listenerThrew(Throwable cause) {
    }
}
