package io.github.rururunu.mqttmacchiatto.internal;

/** Header of one MQTT frame. The body sits at the buffer position after {@link Wire#openFrame}. */
public final class Frame {
    public int type;
    public int flags;
    public int bodyLength;
}
