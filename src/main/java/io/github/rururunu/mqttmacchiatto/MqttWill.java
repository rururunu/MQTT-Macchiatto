package io.github.rururunu.mqttmacchiatto;

/**
 * Last-will message published by the broker if this session dies without a DISCONNECT.
 * {@code payload} is binary and may contain zeros. The array is not copied.
 * {@code delaySeconds} {@code -1} omits the MQTT 5 will-delay property.
 * {@code properties} are MQTT 5 will properties and are ignored on MQTT 3.
 */
public record MqttWill(String topic, byte[] payload, MqttQos qos, boolean retained, int delaySeconds,
                       MqttProperties properties) {
    public MqttWill(String topic, byte[] payload, MqttQos qos, boolean retained) {
        this(topic, payload, qos, retained, -1, null);
    }

    public MqttWill {
        if (topic == null || topic.isEmpty()) {
            throw new IllegalArgumentException("will topic is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("will payload is required");
        }
        if (qos == null) {
            throw new IllegalArgumentException("will qos is required");
        }
        if (delaySeconds < -1) {
            throw new IllegalArgumentException("will delay is negative");
        }
    }
}
