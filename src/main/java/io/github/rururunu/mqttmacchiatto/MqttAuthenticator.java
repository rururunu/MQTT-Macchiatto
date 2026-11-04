package io.github.rururunu.mqttmacchiatto;

/**
 * MQTT 5 enhanced authentication. The initial method and data are sent on CONNECT.
 * Each later call answers one AUTH challenge from the server. The method stays the same
 * for the whole exchange. Return the response data, which may be empty. Throw to abort.
 */
@FunctionalInterface
public interface MqttAuthenticator {
    byte[] authenticate(String method, byte[] challenge);
}
