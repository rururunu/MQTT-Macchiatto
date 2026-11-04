package io.github.rururunu.mqttmacchiatto;

/**
 * A point-in-time view of the connection. Safe to log or keep: it does not hold the socket.
 * {@code attempt} counts completed outages since the last CONNACK and is {@code 0} while open.
 * {@code retryInMillis} is the delay of the current wait, or {@code 0} when no wait is in progress.
 * {@code cause} is the last failure message, or {@code null} after a successful CONNACK.
 */
public record MqttLink(MqttLinkState state, int attempt, String endpoint, long retryInMillis, String cause) {
}
