package io.github.rururunu.mqttmacchiatto;

/**
 * One filter inside a single SUBSCRIBE packet. Filters in the same packet share one
 * MQTT 5 subscription identifier, because that is how the protocol defines the property.
 */
public record MqttSubscriptionRequest(String filter, MqttQos qos, MqttListener listener) {
    public MqttSubscriptionRequest {
        if (filter == null || filter.isEmpty()) {
            throw new IllegalArgumentException("topic filter is required");
        }
        if (qos == null || listener == null) {
            throw new IllegalArgumentException("qos and listener are required");
        }
    }
}
