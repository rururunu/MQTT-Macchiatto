package io.github.rururunu.mqttmacchiatto;

/**
 * Receives one application message. Invoked on the session callback thread, in arrival order.
 * Do not block this thread waiting for the same session: a full delivery queue will stall the socket.
 * {@code payload} belongs to the listener and is not reused by the client.
 */
@FunctionalInterface
public interface MqttListener {
    void onMessage(String topic, byte[] payload, MqttQos qos, boolean retained, boolean duplicate);

    /**
     * MQTT 5 delivery, including properties. The default delegates to
     * {@link #onMessage(String, byte[], MqttQos, boolean, boolean)}.
     */
    default void onMessage(MqttDelivery delivery) {
        onMessage(delivery.topic(), delivery.payload(), delivery.qos(), delivery.retained(), delivery.duplicate());
    }
}
