package io.github.rururunu.mqttmacchiatto;

/**
 * One MQTT 5 user property pair. Both strings follow MQTT UTF-8 rules.
 */
public record MqttUserProperty(String name, String value) {
    public MqttUserProperty {
        if (name == null || value == null) {
            throw new IllegalArgumentException("user property name and value are required");
        }
    }
}
