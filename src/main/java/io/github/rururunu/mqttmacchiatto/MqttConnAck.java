package io.github.rururunu.mqttmacchiatto;

/**
 * The accepted CONNACK. Absent integer fields are {@code -1}. {@code assignedClientId} is null
 * when the client supplied its own id.
 */
public record MqttConnAck(boolean sessionPresent,
                          int reasonCode,
                          String reasonString,
                          String assignedClientId,
                          int serverKeepAliveSeconds,
                          int receiveMaximum,
                          int maximumQos,
                          int topicAliasMaximum) {
}
