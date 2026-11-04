package io.github.rururunu.mqttmacchiatto;

import io.github.rururunu.mqttmacchiatto.internal.Wire;

/**
 * MQTT 5 properties carried on a PUBLISH. Absent numeric fields are {@code -1}.
 * Topic alias {@code 0} means the alias is not set. The array returned by
 * {@link #userProperties()} is not copied and must not be mutated.
 */
public final class MqttProperties {
    public static final MqttUserProperty[] NO_USERS = new MqttUserProperty[0];

    private final int messageExpirySeconds;
    private final int payloadFormat;
    private final int topicAlias;
    private final String contentType;
    private final String responseTopic;
    private final byte[] correlationData;
    private final MqttUserProperty[] userProperties;

    private MqttProperties(int messageExpirySeconds,
                           int payloadFormat,
                           int topicAlias,
                           String contentType,
                           String responseTopic,
                           byte[] correlationData,
                           MqttUserProperty[] userProperties) {
        this.messageExpirySeconds = messageExpirySeconds;
        this.payloadFormat = payloadFormat;
        this.topicAlias = topicAlias;
        this.contentType = contentType;
        this.responseTopic = responseTopic;
        this.correlationData = correlationData;
        this.userProperties = userProperties;
    }

    public int messageExpirySeconds() {
        return messageExpirySeconds;
    }

    /** {@code 0} unspecified, {@code 1} UTF-8. {@code -1} if the property is absent. */
    public int payloadFormat() {
        return payloadFormat;
    }

    public int topicAlias() {
        return topicAlias;
    }

    public String contentType() {
        return contentType;
    }

    public String responseTopic() {
        return responseTopic;
    }

    public byte[] correlationData() {
        return correlationData;
    }

    public MqttUserProperty[] userProperties() {
        return userProperties;
    }

    public boolean isEmpty() {
        return messageExpirySeconds < 0
                && payloadFormat < 0
                && topicAlias <= 0
                && contentType == null
                && responseTopic == null
                && correlationData == null
                && userProperties.length == 0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int messageExpirySeconds = -1;
        private int payloadFormat = -1;
        private String contentType;
        private String responseTopic;
        private byte[] correlationData;
        private MqttUserProperty[] userProperties = NO_USERS;

        private Builder() {
        }

        public Builder messageExpirySeconds(int seconds) {
            this.messageExpirySeconds = seconds;
            return this;
        }

        public Builder payloadFormat(int format) {
            this.payloadFormat = format;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder responseTopic(String responseTopic) {
            this.responseTopic = responseTopic;
            return this;
        }

        /** Not copied. May contain zeros. */
        public Builder correlationData(byte[] correlationData) {
            this.correlationData = correlationData;
            return this;
        }

        public Builder userProperty(String name, String value) {
            MqttUserProperty[] next = new MqttUserProperty[userProperties.length + 1];
            System.arraycopy(userProperties, 0, next, 0, userProperties.length);
            next[userProperties.length] = new MqttUserProperty(name, value);
            this.userProperties = next;
            return this;
        }

        public MqttProperties build() {
            if (messageExpirySeconds < -1) {
                throw new IllegalArgumentException("messageExpirySeconds out of range");
            }
            if (payloadFormat < -1 || payloadFormat > 1) {
                throw new IllegalArgumentException("payloadFormat must be 0, 1, or absent");
            }
            if (contentType != null) {
                Wire.utf8(contentType);
            }
            if (responseTopic != null) {
                Wire.topicName(responseTopic);
            }
            for (MqttUserProperty property : userProperties) {
                Wire.utf8(property.name());
                Wire.utf8(property.value());
            }
            return new MqttProperties(messageExpirySeconds, payloadFormat, 0, contentType, responseTopic,
                    correlationData, userProperties);
        }
    }

    /** Inbound view, including a topic alias assigned by the peer. */
    public static MqttProperties inbound(int messageExpirySeconds,
                                        int payloadFormat,
                                        int topicAlias,
                                        String contentType,
                                        String responseTopic,
                                        byte[] correlationData,
                                        MqttUserProperty[] userProperties) {
        return new MqttProperties(messageExpirySeconds, payloadFormat, topicAlias, contentType, responseTopic,
                correlationData, userProperties == null ? NO_USERS : userProperties);
    }
}
