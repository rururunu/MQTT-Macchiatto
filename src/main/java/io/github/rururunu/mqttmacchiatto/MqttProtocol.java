package io.github.rururunu.mqttmacchiatto;

/**
 * Protocol level written into CONNECT. {@link #MQTT_3_1_1} is the default.
 * {@link #MQTT_3_1} uses the historical protocol name {@code MQIsdp}.
 */
public enum MqttProtocol {
    MQTT_3_1(3),
    MQTT_3_1_1(4),
    MQTT_5(5);

    private final int level;

    MqttProtocol(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }
}
