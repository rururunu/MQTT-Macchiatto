package io.github.rururunu.mqttmacchiatto;

/**
 * What happens when {@link MqttConfig#maxQueued()} messages are already waiting to be written.
 * In-flight messages that already have a packet id are never discarded.
 */
public enum MqttOverflow {
    /** The new publish fails. */
    REJECT,
    /** Drop the oldest queued publish and accept the new one. */
    DROP_OLDEST,
    /** Leave the queue unchanged and fail the new publish. */
    DROP_NEWEST
}
