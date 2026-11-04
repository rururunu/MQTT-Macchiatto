package io.github.rururunu.mqttmacchiatto;

/** Where the session is in its one connection cycle. */
public enum MqttLinkState {
    /** A socket attempt is in progress. */
    CONNECTING,
    /** CONNACK has been accepted and the socket is up. */
    OPEN,
    /** The socket is down and the next attempt is waiting out its delay. */
    WAITING,
    /** Retries are exhausted, or the broker refused the session permanently. */
    FAILED,
    /** {@link MqttSession#close()} has finished. */
    CLOSED
}
