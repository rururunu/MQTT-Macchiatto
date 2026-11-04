package io.github.rururunu.mqttmacchiatto;

/**
 * One application message. {@code payload} belongs to the listener.
 * {@code properties} is {@code null} for an MQTT 3.1.1 session.
 */
public record MqttDelivery(String topic,
                           byte[] payload,
                           MqttQos qos,
                           boolean retained,
                           boolean duplicate,
                           MqttProperties properties,
                           int[] subscriptionIds) {
    public MqttDelivery {
        subscriptionIds = subscriptionIds == null ? new int[0] : subscriptionIds;
    }
}
