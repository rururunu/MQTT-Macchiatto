package io.github.rururunu.mqttmacchiatto.internal;

import io.github.rururunu.mqttmacchiatto.MqttProperties;
import io.github.rururunu.mqttmacchiatto.MqttUserProperty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * MQTT 5 property and packet codec. Property identifiers below 128 use one byte.
 */
public final class V5 {
    public static final int PAYLOAD_FORMAT = 1;
    public static final int MESSAGE_EXPIRY = 2;
    public static final int CONTENT_TYPE = 3;
    public static final int RESPONSE_TOPIC = 8;
    public static final int CORRELATION_DATA = 9;
    public static final int SESSION_EXPIRY = 17;
    public static final int ASSIGNED_CLIENT_ID = 18;
    public static final int SERVER_KEEP_ALIVE = 19;
    public static final int REQUEST_PROBLEM_INFO = 23;
    public static final int REQUEST_RESPONSE_INFO = 25;
    public static final int REASON_STRING = 31;
    public static final int RECEIVE_MAXIMUM = 33;
    public static final int TOPIC_ALIAS_MAXIMUM = 34;
    public static final int TOPIC_ALIAS = 35;
    public static final int MAXIMUM_QOS = 36;
    public static final int MAXIMUM_PACKET_SIZE = 39;
    public static final int USER_PROPERTY = 38;
    public static final int SUBSCRIPTION_IDENTIFIER = 11;
    public static final int AUTHENTICATION_METHOD = 21;
    public static final int AUTHENTICATION_DATA = 22;
    public static final int WILL_DELAY = 24;

    public static final long SESSION_EXPIRY_MAX = 0xFFFF_FFFFL;

    private V5() {
    }

    public static final class View {
        public int messageExpiry = -1;
        public int payloadFormat = -1;
        public int topicAlias;
        public int receiveMaximum = -1;
        public int topicAliasMaximum = -1;
        public int maximumPacketSize = -1;
        public int serverKeepAlive = -1;
        public int maximumQos = -1;
        public long sessionExpiry = -1;
        public byte[] assignedClientId;
        public String contentType;
        public String responseTopic;
        public byte[] correlationData;
        public String reasonString;
        public String authMethod;
        public byte[] authData;
        public int[] subscriptionIds = new int[0];
        public MqttUserProperty[] users = MqttProperties.NO_USERS;

        public void reset() {
            messageExpiry = -1;
            payloadFormat = -1;
            topicAlias = 0;
            receiveMaximum = -1;
            topicAliasMaximum = -1;
            maximumPacketSize = -1;
            serverKeepAlive = -1;
            maximumQos = -1;
            sessionExpiry = -1;
            assignedClientId = null;
            contentType = null;
            responseTopic = null;
            correlationData = null;
            reasonString = null;
            authMethod = null;
            authData = null;
            subscriptionIds = new int[0];
            users = MqttProperties.NO_USERS;
        }

        public MqttProperties toProperties() {
            if (messageExpiry < 0 && payloadFormat < 0 && topicAlias <= 0 && contentType == null
                    && responseTopic == null && correlationData == null && users.length == 0) {
                return null;
            }
            return MqttProperties.inbound(messageExpiry, payloadFormat, topicAlias, contentType, responseTopic,
                    correlationData, users);
        }
    }

    public static int varIntSize(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("variable byte integer is negative");
        }
        if (value < 128) {
            return 1;
        }
        if (value < 16_384) {
            return 2;
        }
        if (value < 2_097_152) {
            return 3;
        }
        return 4;
    }

    public static void putVarInt(ByteBuffer buf, int value) {
        do {
            int digit = value % 128;
            value /= 128;
            if (value > 0) {
                digit |= 0x80;
            }
            buf.put((byte) digit);
        } while (value > 0);
    }

    public static int readVarInt(ByteBuffer buf) {
        int value = 0;
        int multiplier = 1;
        for (int i = 0; i < 4; i++) {
            if (!buf.hasRemaining()) {
                throw new IllegalArgumentException("truncated variable byte integer");
            }
            int digit = buf.get() & 0xFF;
            value += (digit & 0x7F) * multiplier;
            if ((digit & 0x80) == 0) {
                return value;
            }
            multiplier *= 128;
            if (i == 3) {
                throw new IllegalArgumentException("variable byte integer is longer than 4 bytes");
            }
        }
        return value;
    }

    public static int userPropertyWire(MqttProperties properties) {
        if (properties == null) {
            return 0;
        }
        int size = 0;
        if (properties.messageExpirySeconds() >= 0) {
            size += 5;
        }
        if (properties.payloadFormat() >= 0) {
            size += 2;
        }
        if (properties.contentType() != null) {
            size += 1 + mqttSize(Wire.utf8(properties.contentType()));
        }
        if (properties.responseTopic() != null) {
            size += 1 + mqttSize(Wire.utf8(properties.responseTopic()));
        }
        if (properties.correlationData() != null) {
            size += 3 + properties.correlationData().length;
        }
        for (MqttUserProperty property : properties.userProperties()) {
            size += 1 + mqttSize(Wire.utf8(property.name())) + mqttSize(Wire.utf8(property.value()));
        }
        return size;
    }

    /** Byte length of the properties section, including its length prefix. Alias 0 omits that property. */
    public static int publishPropertiesSize(int topicAlias, MqttProperties properties) {
        int body = userPropertyWire(properties);
        if (topicAlias > 0) {
            body += 3;
        }
        return varIntSize(body) + body;
    }

    public static void writePublishProperties(ByteBuffer buf, int topicAlias, MqttProperties properties) {
        writePublishProperties(buf, topicAlias, properties, 0);
    }

    public static void writePublishProperties(ByteBuffer buf, int topicAlias, MqttProperties properties, int subscriptionId) {
        int body = userPropertyWire(properties) + (topicAlias > 0 ? 3 : 0);
        if (subscriptionId > 0) {
            body += 1 + varIntSize(subscriptionId);
        }
        putVarInt(buf, body);
        if (properties != null && properties.messageExpirySeconds() >= 0) {
            buf.put((byte) MESSAGE_EXPIRY);
            buf.putInt(properties.messageExpirySeconds());
        }
        if (properties != null && properties.payloadFormat() >= 0) {
            buf.put((byte) PAYLOAD_FORMAT);
            buf.put((byte) properties.payloadFormat());
        }
        if (properties != null && properties.contentType() != null) {
            buf.put((byte) CONTENT_TYPE);
            putMqtt(buf, Wire.utf8(properties.contentType()));
        }
        if (properties != null && properties.responseTopic() != null) {
            buf.put((byte) RESPONSE_TOPIC);
            putMqtt(buf, Wire.utf8(properties.responseTopic()));
        }
        if (properties != null && properties.correlationData() != null) {
            buf.put((byte) CORRELATION_DATA);
            buf.putShort((short) properties.correlationData().length);
            buf.put(properties.correlationData());
        }
        if (topicAlias > 0) {
            buf.put((byte) TOPIC_ALIAS);
            buf.putShort((short) topicAlias);
        }
        if (subscriptionId > 0) {
            buf.put((byte) SUBSCRIPTION_IDENTIFIER);
            putVarInt(buf, subscriptionId);
        }
        if (properties != null) {
            for (MqttUserProperty property : properties.userProperties()) {
                buf.put((byte) USER_PROPERTY);
                putMqtt(buf, Wire.utf8(property.name()));
                putMqtt(buf, Wire.utf8(property.value()));
            }
        }
    }

    public static void readProperties(ByteBuffer buf, View view) {
        int length = readVarInt(buf);
        int end = buf.position() + length;
        if (length < 0 || end > buf.limit()) {
            throw new IllegalArgumentException("properties exceed the packet");
        }
        MqttUserProperty[] users = MqttProperties.NO_USERS;
        while (buf.position() < end) {
            int id = readVarInt(buf);
            switch (id) {
                case PAYLOAD_FORMAT -> view.payloadFormat = buf.get() & 0xFF;
                case MESSAGE_EXPIRY -> view.messageExpiry = buf.getInt();
                case CONTENT_TYPE -> view.contentType = Wire.readString(buf);
                case RESPONSE_TOPIC -> view.responseTopic = Wire.readString(buf);
                case CORRELATION_DATA -> {
                    int n = Wire.readUnsignedShort(buf);
                    view.correlationData = Wire.readBytes(buf, n);
                }
                case SESSION_EXPIRY -> view.sessionExpiry = Integer.toUnsignedLong(buf.getInt());
                case ASSIGNED_CLIENT_ID -> {
                    int n = Wire.readUnsignedShort(buf);
                    view.assignedClientId = Wire.readBytes(buf, n);
                    Wire.rejectIllegalUtf8(view.assignedClientId);
                }
                case SERVER_KEEP_ALIVE -> view.serverKeepAlive = Wire.readUnsignedShort(buf);
                case REQUEST_PROBLEM_INFO, REQUEST_RESPONSE_INFO -> buf.get();
                case REASON_STRING -> view.reasonString = Wire.readString(buf);
                case RECEIVE_MAXIMUM -> view.receiveMaximum = Wire.readUnsignedShort(buf);
                case TOPIC_ALIAS_MAXIMUM -> view.topicAliasMaximum = Wire.readUnsignedShort(buf);
                case TOPIC_ALIAS -> view.topicAlias = Wire.readUnsignedShort(buf);
                case MAXIMUM_QOS -> view.maximumQos = buf.get() & 0xFF;
                case MAXIMUM_PACKET_SIZE -> view.maximumPacketSize = buf.getInt();
                case SUBSCRIPTION_IDENTIFIER -> {
                    int subscriptionId = readVarInt(buf);
                    int[] next = Arrays.copyOf(view.subscriptionIds, view.subscriptionIds.length + 1);
                    next[view.subscriptionIds.length] = subscriptionId;
                    view.subscriptionIds = next;
                }
                case WILL_DELAY -> buf.getInt();
                case 26, 28 -> Wire.readString(buf);
                case AUTHENTICATION_METHOD -> view.authMethod = Wire.readString(buf);
                case AUTHENTICATION_DATA -> view.authData = Wire.readBytes(buf, Wire.readUnsignedShort(buf));
                case 37, 40, 41, 42 -> buf.get();
                case USER_PROPERTY -> {
                    MqttUserProperty[] next = new MqttUserProperty[users.length + 1];
                    System.arraycopy(users, 0, next, 0, users.length);
                    next[users.length] = new MqttUserProperty(Wire.readString(buf), Wire.readString(buf));
                    users = next;
                }
                default -> throw new IllegalArgumentException("unsupported property " + id);
            }
        }
        if (buf.position() != end) {
            throw new IllegalArgumentException("property length does not match");
        }
        view.users = users;
    }

    /** Optional CONNECT additions. Null fields keep the compact default property set. */
    public static final class Connect {
        public int willDelay = -1;
        public MqttProperties willProperties;
        public MqttUserProperty[] users = MqttProperties.NO_USERS;
        public boolean requestResponseInfo;
        public boolean suppressProblemInfo;
        public String authMethod;
        public byte[] authData;
    }

    public static int connectPropertiesSize(long sessionExpiry, int receiveMaximum, int topicAliasMaximum, int maxPacketSize) {
        return connectPropertiesSize(sessionExpiry, receiveMaximum, topicAliasMaximum, maxPacketSize, null);
    }

    public static int connectPropertiesSize(long sessionExpiry, int receiveMaximum, int topicAliasMaximum,
                                           int maxPacketSize, Connect extra) {
        int body = connectPropertyBody(extra);
        return varIntSize(body) + body;
    }

    public static void encodeConnect(ByteBuffer buf,
                                     byte[] clientId,
                                     boolean cleanStart,
                                     int keepAliveSeconds,
                                     byte[] username,
                                     byte[] password,
                                     byte[] willTopic,
                                     byte[] willPayload,
                                     int willQos,
                                     boolean willRetain,
                                     long sessionExpiry,
                                     int receiveMaximum,
                                     int topicAliasMaximum,
                                     int maxPacketSize) {
        encodeConnect(buf, clientId, cleanStart, keepAliveSeconds, username, password, willTopic, willPayload,
                willQos, willRetain, sessionExpiry, receiveMaximum, topicAliasMaximum, maxPacketSize, null);
    }

    public static void encodeConnect(ByteBuffer buf,
                                     byte[] clientId,
                                     boolean cleanStart,
                                     int keepAliveSeconds,
                                     byte[] username,
                                     byte[] password,
                                     byte[] willTopic,
                                     byte[] willPayload,
                                     int willQos,
                                     boolean willRetain,
                                     long sessionExpiry,
                                     int receiveMaximum,
                                     int topicAliasMaximum,
                                     int maxPacketSize,
                                     Connect extra) {
        int properties = connectPropertiesSize(sessionExpiry, receiveMaximum, topicAliasMaximum, maxPacketSize, extra);
        int willSection = willTopic == null ? 0 : willPropertiesSection(extra);
        int remaining = 10 + properties + mqttSize(clientId);
        if (willTopic != null) {
            remaining += willSection + mqttSize(willTopic) + 2 + willPayload.length;
        }
        if (username != null) {
            remaining += mqttSize(username);
            if (password != null) {
                remaining += mqttSize(password);
            }
        }
        buf.put((byte) (Wire.CONNECT << 4));
        putRemaining(buf, remaining);
        putMqtt(buf, "MQTT".getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 5);
        int flags = cleanStart ? 0x02 : 0;
        if (willTopic != null) {
            flags |= 0x04;
            flags |= (willQos & 0x03) << 3;
            if (willRetain) {
                flags |= 0x20;
            }
        }
        if (username != null) {
            flags |= 0x80;
            if (password != null) {
                flags |= 0x40;
            }
        }
        buf.put((byte) flags);
        buf.putShort((short) keepAliveSeconds);
        writeConnectProperties(buf, sessionExpiry, receiveMaximum, topicAliasMaximum, maxPacketSize, extra);
        putMqtt(buf, clientId);
        if (willTopic != null) {
            writeWillProperties(buf, extra);
            putMqtt(buf, willTopic);
            buf.putShort((short) willPayload.length);
            buf.put(willPayload);
        }
        if (username != null) {
            putMqtt(buf, username);
            if (password != null) {
                putMqtt(buf, password);
            }
        }
    }

    public static int connectPacketSize(byte[] clientId,
                                        byte[] username,
                                        byte[] password,
                                        byte[] willTopic,
                                        byte[] willPayload,
                                        long sessionExpiry,
                                        int receiveMaximum,
                                        int topicAliasMaximum,
                                        int maxPacketSize) {
        return connectPacketSize(clientId, username, password, willTopic, willPayload, sessionExpiry,
                receiveMaximum, topicAliasMaximum, maxPacketSize, null);
    }

    public static int connectPacketSize(byte[] clientId,
                                        byte[] username,
                                        byte[] password,
                                        byte[] willTopic,
                                        byte[] willPayload,
                                        long sessionExpiry,
                                        int receiveMaximum,
                                        int topicAliasMaximum,
                                        int maxPacketSize,
                                        Connect extra) {
        int properties = connectPropertiesSize(sessionExpiry, receiveMaximum, topicAliasMaximum, maxPacketSize, extra);
        int remaining = 10 + properties + mqttSize(clientId);
        if (willTopic != null) {
            remaining += willPropertiesSection(extra) + mqttSize(willTopic) + 2 + willPayload.length;
        }
        if (username != null) {
            remaining += mqttSize(username);
            if (password != null) {
                remaining += mqttSize(password);
            }
        }
        return 1 + Wire.remainingLengthSize(remaining) + remaining;
    }

    public static void encodePublish(ByteBuffer buf,
                                     byte[] topic,
                                     byte[] payload,
                                     int qos,
                                     boolean retain,
                                     boolean dup,
                                     int packetId,
                                     int topicAlias,
                                     MqttProperties properties) {
        int flags = (qos & 0x03) << 1;
        if (retain) {
            flags |= 0x01;
        }
        if (dup) {
            flags |= 0x08;
        }
        int props = publishPropertiesSize(topicAlias, properties);
        int remaining = 2 + topic.length + props + payload.length + (qos > 0 ? 2 : 0);
        buf.put((byte) ((Wire.PUBLISH << 4) | flags));
        putRemaining(buf, remaining);
        putMqtt(buf, topic);
        if (qos > 0) {
            buf.putShort((short) packetId);
        }
        writePublishProperties(buf, topicAlias, properties);
        buf.put(payload);
    }

    public static void encodePublish(ByteBuffer buf,
                                     byte[] topic,
                                     byte[] payload,
                                     int qos,
                                     boolean retain,
                                     boolean dup,
                                     int packetId,
                                     int topicAlias,
                                     MqttProperties properties,
                                     int subscriptionId) {
        int flags = (qos & 0x03) << 1;
        if (retain) {
            flags |= 0x01;
        }
        if (dup) {
            flags |= 0x08;
        }
        int props = publishPropertiesSize(topicAlias, properties, subscriptionId);
        int remaining = 2 + topic.length + props + payload.length + (qos > 0 ? 2 : 0);
        buf.put((byte) ((Wire.PUBLISH << 4) | flags));
        putRemaining(buf, remaining);
        putMqtt(buf, topic);
        if (qos > 0) {
            buf.putShort((short) packetId);
        }
        writePublishProperties(buf, topicAlias, properties, subscriptionId);
        buf.put(payload);
    }

    public static int publishPropertiesSize(int topicAlias, MqttProperties properties, int subscriptionId) {
        int body = userPropertyWire(properties) + (topicAlias > 0 ? 3 : 0);
        if (subscriptionId > 0) {
            body += 1 + varIntSize(subscriptionId);
        }
        return varIntSize(body) + body;
    }

    /**
     * @param topicOnWire topic bytes actually written. Empty when a previously assigned alias is reused.
     */
    public static int publishPacketSize(int topicOnWire, int payloadLength, int qos, int topicAlias, MqttProperties properties) {
        int remaining = 2 + topicOnWire + publishPropertiesSize(topicAlias, properties) + payloadLength + (qos > 0 ? 2 : 0);
        return 1 + Wire.remainingLengthSize(remaining) + remaining;
    }

    public static void encodeSubscribe(ByteBuffer buf, int packetId, byte[] filter, int qos) {
        encodeSubscribe(buf, packetId, new byte[][]{filter}, new int[]{qos}, 0);
    }

    public static void encodeSubscribe(ByteBuffer buf, int packetId, byte[][] filters, int[] qos, int subscriptionId) {
        int remaining = 2 + subscribePropertiesSection(subscriptionId);
        for (byte[] filter : filters) {
            remaining += mqttSize(filter) + 1;
        }
        buf.put((byte) ((Wire.SUBSCRIBE << 4) | 0x02));
        putRemaining(buf, remaining);
        buf.putShort((short) packetId);
        writeSubscriptionProperties(buf, subscriptionId);
        for (int i = 0; i < filters.length; i++) {
            putMqtt(buf, filters[i]);
            buf.put((byte) qos[i]);
        }
    }

    public static int subscribePacketSize(byte[] filter) {
        return subscribePacketSize(new byte[][]{filter}, 0);
    }

    public static int subscribePacketSize(byte[][] filters, int subscriptionId) {
        int remaining = 2 + subscribePropertiesSection(subscriptionId);
        for (byte[] filter : filters) {
            remaining += mqttSize(filter) + 1;
        }
        return 1 + Wire.remainingLengthSize(remaining) + remaining;
    }

    public static void encodeUnsubscribe(ByteBuffer buf, int packetId, byte[] filter) {
        int remaining = 2 + 1 + mqttSize(filter);
        buf.put((byte) ((Wire.UNSUBSCRIBE << 4) | 0x02));
        putRemaining(buf, remaining);
        buf.putShort((short) packetId);
        buf.put((byte) 0);
        putMqtt(buf, filter);
    }

    public static int unsubscribePacketSize(byte[] filter) {
        int remaining = 2 + 1 + mqttSize(filter);
        return 1 + Wire.remainingLengthSize(remaining) + remaining;
    }

    public static void encodeConnAck(ByteBuffer buf, boolean sessionPresent, int reason, int receiveMaximum, int topicAliasMaximum) {
        int body = 3 + 3;
        int remaining = 2 + varIntSize(body) + body;
        buf.put((byte) (Wire.CONNACK << 4));
        putRemaining(buf, remaining);
        buf.put((byte) (sessionPresent ? 1 : 0));
        buf.put((byte) reason);
        putVarInt(buf, body);
        buf.put((byte) RECEIVE_MAXIMUM);
        buf.putShort((short) receiveMaximum);
        buf.put((byte) TOPIC_ALIAS_MAXIMUM);
        buf.putShort((short) topicAliasMaximum);
    }

    public static void encodeSubAck(ByteBuffer buf, int packetId, int reason) {
        encodeSubAck(buf, packetId, new int[]{reason});
    }

    public static void encodeSubAck(ByteBuffer buf, int packetId, int[] reasons) {
        int remaining = 3 + reasons.length;
        buf.put((byte) (Wire.SUBACK << 4));
        putRemaining(buf, remaining);
        buf.putShort((short) packetId);
        buf.put((byte) 0);
        for (int reason : reasons) {
            buf.put((byte) reason);
        }
    }

    public static void encodeAuth(ByteBuffer buf, int reason, String method, byte[] data) {
        int body = 0;
        byte[] methodBytes = method == null ? null : Wire.utf8(method);
        if (methodBytes != null) {
            body += 1 + mqttSize(methodBytes);
        }
        if (data != null && data.length > 0) {
            body += 3 + data.length;
        }
        int remaining = 1 + varIntSize(body) + body;
        buf.put((byte) (15 << 4));
        putRemaining(buf, remaining);
        buf.put((byte) reason);
        putVarInt(buf, body);
        if (methodBytes != null) {
            buf.put((byte) AUTHENTICATION_METHOD);
            putMqtt(buf, methodBytes);
        }
        if (data != null && data.length > 0) {
            buf.put((byte) AUTHENTICATION_DATA);
            buf.putShort((short) data.length);
            buf.put(data);
        }
    }

    public static void encodeUnsubAck(ByteBuffer buf, int packetId) {
        buf.put((byte) (Wire.UNSUBACK << 4));
        buf.put((byte) 4);
        buf.putShort((short) packetId);
        buf.put((byte) 0);
        buf.put((byte) 0);
    }

    public static boolean successReason(int code) {
        return code >= 0 && code < 0x80;
    }

    private static int connectPropertyBody(Connect extra) {
        int body = 5 + 3 + 3 + 5;
        if (extra == null) {
            return body;
        }
        if (extra.requestResponseInfo) {
            body += 2;
        }
        if (extra.suppressProblemInfo) {
            body += 2;
        }
        if (extra.authMethod != null) {
            body += 1 + mqttSize(Wire.utf8(extra.authMethod));
        }
        if (extra.authData != null && extra.authData.length > 0) {
            body += 3 + extra.authData.length;
        }
        if (extra.users != null) {
            for (MqttUserProperty property : extra.users) {
                body += 1 + mqttSize(Wire.utf8(property.name())) + mqttSize(Wire.utf8(property.value()));
            }
        }
        return body;
    }

    private static void writeConnectProperties(ByteBuffer buf,
                                               long sessionExpiry,
                                               int receiveMaximum,
                                               int topicAliasMaximum,
                                               int maxPacketSize,
                                               Connect extra) {
        int body = connectPropertyBody(extra);
        putVarInt(buf, body);
        buf.put((byte) SESSION_EXPIRY);
        buf.putInt((int) sessionExpiry);
        buf.put((byte) RECEIVE_MAXIMUM);
        buf.putShort((short) receiveMaximum);
        buf.put((byte) TOPIC_ALIAS_MAXIMUM);
        buf.putShort((short) topicAliasMaximum);
        buf.put((byte) MAXIMUM_PACKET_SIZE);
        buf.putInt(maxPacketSize);
        if (extra == null) {
            return;
        }
        if (extra.requestResponseInfo) {
            buf.put((byte) REQUEST_RESPONSE_INFO);
            buf.put((byte) 1);
        }
        if (extra.suppressProblemInfo) {
            buf.put((byte) REQUEST_PROBLEM_INFO);
            buf.put((byte) 0);
        }
        if (extra.authMethod != null) {
            buf.put((byte) AUTHENTICATION_METHOD);
            putMqtt(buf, Wire.utf8(extra.authMethod));
        }
        if (extra.authData != null && extra.authData.length > 0) {
            buf.put((byte) AUTHENTICATION_DATA);
            buf.putShort((short) extra.authData.length);
            buf.put(extra.authData);
        }
        if (extra.users != null) {
            for (MqttUserProperty property : extra.users) {
                buf.put((byte) USER_PROPERTY);
                putMqtt(buf, Wire.utf8(property.name()));
                putMqtt(buf, Wire.utf8(property.value()));
            }
        }
    }

    private static int willPropertiesSection(Connect extra) {
        int body = willPropertyBody(extra);
        if (body == 0) {
            return 1;
        }
        return varIntSize(body) + body;
    }

    private static int willPropertyBody(Connect extra) {
        if (extra == null) {
            return 0;
        }
        int body = extra.willDelay >= 0 ? 5 : 0;
        body += userPropertyWire(extra.willProperties);
        return body;
    }

    private static void writeWillProperties(ByteBuffer buf, Connect extra) {
        int body = willPropertyBody(extra);
        if (body == 0) {
            buf.put((byte) 0);
            return;
        }
        putVarInt(buf, body);
        if (extra.willDelay >= 0) {
            buf.put((byte) WILL_DELAY);
            buf.putInt(extra.willDelay);
        }
        if (extra.willProperties != null && !extra.willProperties.isEmpty()) {
            int section = publishPropertiesSize(0, extra.willProperties);
            ByteBuffer properties = ByteBuffer.allocate(section);
            writePublishProperties(properties, 0, extra.willProperties);
            properties.flip();
            properties.position(varIntSize(userPropertyWire(extra.willProperties)));
            buf.put(properties);
        }
    }

    private static int subscribePropertiesSection(int subscriptionId) {
        if (subscriptionId <= 0) {
            return 1;
        }
        int body = 1 + varIntSize(subscriptionId);
        return varIntSize(body) + body;
    }

    private static void writeSubscriptionProperties(ByteBuffer buf, int subscriptionId) {
        if (subscriptionId <= 0) {
            buf.put((byte) 0);
            return;
        }
        int body = 1 + varIntSize(subscriptionId);
        putVarInt(buf, body);
        buf.put((byte) SUBSCRIPTION_IDENTIFIER);
        putVarInt(buf, subscriptionId);
    }

    private static int mqttSize(byte[] encoded) {
        return 2 + encoded.length;
    }

    private static void putMqtt(ByteBuffer buf, byte[] encoded) {
        buf.putShort((short) encoded.length);
        buf.put(encoded);
    }

    private static void putRemaining(ByteBuffer buf, int length) {
        do {
            int digit = length % 128;
            length /= 128;
            if (length > 0) {
                digit |= 0x80;
            }
            buf.put((byte) digit);
        } while (length > 0);
    }
}
