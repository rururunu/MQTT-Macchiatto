package io.github.rururunu.mqttmacchiatto;

/**
 * MQTT 3.1.1 delivery guarantee. Wire values are the protocol codes.
 */
public enum MqttQos {
    AT_MOST_ONCE(0),
    AT_LEAST_ONCE(1),
    EXACTLY_ONCE(2);

    private final int wire;

    MqttQos(int wire) {
        this.wire = wire;
    }

    public int wire() {
        return wire;
    }

    public static MqttQos fromWire(int wire) {
        return switch (wire) {
            case 0 -> AT_MOST_ONCE;
            case 1 -> AT_LEAST_ONCE;
            case 2 -> EXACTLY_ONCE;
            default -> throw new IllegalArgumentException("QoS out of range: " + wire);
        };
    }
}
