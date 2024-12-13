package io.github.rururunu.mqttmacchiatto.content;

public enum MQTTQos {
    AT_LEAST_ONCE(1),
    EXACTLY_ONCE(2),
    AT_MOST_ONCE(0);

    private final int value;

    MQTTQos(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
