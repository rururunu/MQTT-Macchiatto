package io.github.rururunu.mqttmacchiatto;

import java.util.List;

/**
 * Durable session image. {@code packetId} {@code 0} is a publish accepted while the socket
 * was down and not yet written. Implementations must not retain and mutate the arrays.
 */
public record MqttSessionImage(List<Outbound> outbound, List<Inbound> inbound, List<Subscription> subscriptions) {
    public static final MqttSessionImage EMPTY = new MqttSessionImage(List.of(), List.of(), List.of());

    public MqttSessionImage {
        outbound = outbound == null ? List.of() : List.copyOf(outbound);
        inbound = inbound == null ? List.of() : List.copyOf(inbound);
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }

    public record Outbound(int packetId, int state, int qos, boolean retain, byte[] topic, byte[] payload,
                           MqttProperties properties) {
    }

    public record Inbound(int packetId, boolean retain, boolean dup, byte[] topic, byte[] payload,
                          MqttProperties properties) {
    }

    public record Subscription(int qos, byte[] filter) {
    }
}
