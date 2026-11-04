package io.github.rururunu.mqttmacchiatto.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * MQTT 3.1.1 codec that writes into a caller-owned buffer. No per-packet objects.
 */
public final class Wire {
    public static final int MAX_REMAINING = 268_435_455;
    public static final byte CONNECT = 1;
    public static final byte CONNACK = 2;
    public static final byte PUBLISH = 3;
    public static final byte PUBACK = 4;
    public static final byte PUBREC = 5;
    public static final byte PUBREL = 6;
    public static final byte PUBCOMP = 7;
    public static final byte SUBSCRIBE = 8;
    public static final byte SUBACK = 9;
    public static final byte UNSUBSCRIBE = 10;
    public static final byte UNSUBACK = 11;
    public static final byte PINGREQ = 12;
    public static final byte PINGRESP = 13;
    public static final byte DISCONNECT = 14;

    private Wire() {
    }

    public static int remainingLengthSize(int length) {
        if (length < 128) {
            return 1;
        }
        if (length < 16_384) {
            return 2;
        }
        if (length < 2_097_152) {
            return 3;
        }
        return 4;
    }

    public static int publishSize(int topicUtf8Length, int payloadLength, int qos) {
        int remaining = 2 + topicUtf8Length + payloadLength + (qos > 0 ? 2 : 0);
        return 1 + remainingLengthSize(remaining) + remaining;
    }

    /**
     * Opens one frame if the buffer holds it. On success the position is the first body byte
     * and the return value is the body length. On an incomplete frame the position is unchanged
     * and the return value is -1.
     */
    public static int openFrame(ByteBuffer buf, Frame frame, int maxPacketBytes) {
        int available = buf.remaining();
        if (available < 2) {
            return -1;
        }
        int start = buf.position();
        int header = buf.get(start) & 0xFF;
        int value = 0;
        int multiplier = 1;
        int lengthBytes = 0;
        for (int i = 0; i < 4; i++) {
            if (available < 2 + i) {
                return -1;
            }
            int digit = buf.get(start + 1 + i) & 0xFF;
            lengthBytes++;
            value += (digit & 0x7F) * multiplier;
            if ((digit & 0x80) == 0) {
                break;
            }
            if (i == 3) {
                throw new IllegalArgumentException("remaining length is longer than 4 bytes");
            }
            multiplier *= 128;
        }
        if (value < 0 || value > MAX_REMAINING) {
            throw new IllegalArgumentException("remaining length out of range");
        }
        int packet = 1 + lengthBytes + value;
        if (packet > maxPacketBytes) {
            throw new IllegalArgumentException("packet exceeds maxPacketBytes: " + packet);
        }
        if (available < packet) {
            return -1;
        }
        frame.type = header >>> 4;
        frame.flags = header & 0x0F;
        frame.bodyLength = value;
        buf.position(start + 1 + lengthBytes);
        return value;
    }

    public static void encodeConnect(ByteBuffer buf,
                                     byte[] clientId,
                                     boolean cleanSession,
                                     int keepAliveSeconds,
                                     byte[] username,
                                     byte[] password,
                                     byte[] willTopic,
                                     byte[] willPayload,
                                     int willQos,
                                     boolean willRetain) {
        int remaining = 10 + mqttSize(clientId);
        if (willTopic != null) {
            remaining += mqttSize(willTopic) + 2 + willPayload.length;
        }
        if (username != null) {
            remaining += mqttSize(username);
            if (password != null) {
                remaining += mqttSize(password);
            }
        }
        buf.put((byte) (CONNECT << 4));
        putRemaining(buf, remaining);
        putMqtt(buf, "MQTT".getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 4);
        int flags = cleanSession ? 0x02 : 0;
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
        putMqtt(buf, clientId);
        if (willTopic != null) {
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

    public static void encodePublish(ByteBuffer buf,
                                     byte[] topic,
                                     byte[] payload,
                                     int qos,
                                     boolean retain,
                                     boolean dup,
                                     int packetId) {
        int flags = (qos & 0x03) << 1;
        if (retain) {
            flags |= 0x01;
        }
        if (dup) {
            flags |= 0x08;
        }
        int remaining = 2 + topic.length + payload.length + (qos > 0 ? 2 : 0);
        buf.put((byte) ((PUBLISH << 4) | flags));
        putRemaining(buf, remaining);
        putMqtt(buf, topic);
        if (qos > 0) {
            buf.putShort((short) packetId);
        }
        buf.put(payload);
    }

    public static void encodeAck(ByteBuffer buf, int type, int packetId) {
        int flags = type == PUBREL ? 0x02 : 0;
        buf.put((byte) ((type << 4) | flags));
        buf.put((byte) 2);
        buf.putShort((short) packetId);
    }

    public static void encodeSubscribe(ByteBuffer buf, int packetId, byte[] filter, int qos) {
        encodeSubscribe(buf, packetId, new byte[][]{filter}, new int[]{qos});
    }

    public static void encodeSubscribe(ByteBuffer buf, int packetId, byte[][] filters, int[] qos) {
        int remaining = 2;
        for (byte[] filter : filters) {
            remaining += mqttSize(filter) + 1;
        }
        buf.put((byte) ((SUBSCRIBE << 4) | 0x02));
        putRemaining(buf, remaining);
        buf.putShort((short) packetId);
        for (int i = 0; i < filters.length; i++) {
            putMqtt(buf, filters[i]);
            buf.put((byte) qos[i]);
        }
    }

    public static void encodeUnsubscribe(ByteBuffer buf, int packetId, byte[] filter) {
        int remaining = 2 + mqttSize(filter);
        buf.put((byte) ((UNSUBSCRIBE << 4) | 0x02));
        putRemaining(buf, remaining);
        buf.putShort((short) packetId);
        putMqtt(buf, filter);
    }

    public static void encodePingReq(ByteBuffer buf) {
        buf.put((byte) (PINGREQ << 4));
        buf.put((byte) 0);
    }

    public static void encodePingResp(ByteBuffer buf) {
        buf.put((byte) (PINGRESP << 4));
        buf.put((byte) 0);
    }

    public static void encodeDisconnect(ByteBuffer buf) {
        buf.put((byte) (DISCONNECT << 4));
        buf.put((byte) 0);
    }

    public static void encodeConnAck(ByteBuffer buf, boolean sessionPresent, int returnCode) {
        buf.put((byte) (CONNACK << 4));
        buf.put((byte) 2);
        buf.put((byte) (sessionPresent ? 1 : 0));
        buf.put((byte) returnCode);
    }

    public static void encodeSubAck(ByteBuffer buf, int packetId, int grantedQos) {
        encodeSubAck(buf, packetId, new int[]{grantedQos});
    }

    public static void encodeSubAck(ByteBuffer buf, int packetId, int[] reasons) {
        int remaining = 2 + reasons.length;
        buf.put((byte) (SUBACK << 4));
        putRemaining(buf, remaining);
        buf.putShort((short) packetId);
        for (int reason : reasons) {
            buf.put((byte) reason);
        }
    }

    /** MQTT 3.1 CONNECT. The protocol name is {@code MQIsdp} and the level is 3. */
    public static void encodeConnect31(ByteBuffer buf,
                                       byte[] clientId,
                                       boolean cleanSession,
                                       int keepAliveSeconds,
                                       byte[] username,
                                       byte[] password,
                                       byte[] willTopic,
                                       byte[] willPayload,
                                       int willQos,
                                       boolean willRetain) {
        byte[] name = "MQIsdp".getBytes(StandardCharsets.US_ASCII);
        int remaining = 2 + name.length + 4 + mqttSize(clientId);
        if (willTopic != null) {
            remaining += mqttSize(willTopic) + 2 + willPayload.length;
        }
        if (username != null) {
            remaining += mqttSize(username);
            if (password != null) {
                remaining += mqttSize(password);
            }
        }
        buf.put((byte) (CONNECT << 4));
        putRemaining(buf, remaining);
        putMqtt(buf, name);
        buf.put((byte) 3);
        int flags = cleanSession ? 0x02 : 0;
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
        putMqtt(buf, clientId);
        if (willTopic != null) {
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

    public static void encodeUnsubAck(ByteBuffer buf, int packetId) {
        buf.put((byte) (UNSUBACK << 4));
        buf.put((byte) 2);
        buf.putShort((short) packetId);
    }

    public static int readUnsignedShort(ByteBuffer buf) {
        return Short.toUnsignedInt(buf.getShort());
    }

    public static String readString(ByteBuffer buf) {
        return readString(buf, null);
    }

    /**
     * @param scratch a buffer of at least the string's byte length, or null to allocate one.
     *                Only the first {@code length} bytes are read. The returned string copies them.
     */
    public static String readString(ByteBuffer buf, byte[] scratch) {
        int length = readUnsignedShort(buf);
        if (length > buf.remaining()) {
            throw new IllegalArgumentException("truncated MQTT string");
        }
        byte[] raw = scratch != null && scratch.length >= length ? scratch : new byte[length];
        buf.get(raw, 0, length);
        rejectIllegalUtf8(raw, length);
        return new String(raw, 0, length, StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(ByteBuffer buf, int length) {
        if (length < 0 || length > buf.remaining()) {
            throw new IllegalArgumentException("truncated binary field");
        }
        byte[] raw = new byte[length];
        buf.get(raw);
        return raw;
    }

    public static byte[] utf8(String value) {
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("MQTT string contains NUL");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 65_535) {
            throw new IllegalArgumentException("MQTT string longer than 65535 bytes");
        }
        if (encoded.length >= 3
                && (encoded[0] & 0xFF) == 0xEF
                && (encoded[1] & 0xFF) == 0xBB
                && (encoded[2] & 0xFF) == 0xBF) {
            throw new IllegalArgumentException("MQTT string starts with a UTF-8 BOM");
        }
        return encoded;
    }

    public static byte[] topicName(String topic) {
        if (topic == null || topic.isEmpty()) {
            throw new IllegalArgumentException("topic is required");
        }
        if (topic.indexOf('+') >= 0 || topic.indexOf('#') >= 0) {
            throw new IllegalArgumentException("topic name must not contain a wildcard");
        }
        return utf8(topic);
    }

    public static void rejectIllegalUtf8(byte[] raw) {
        rejectIllegalUtf8(raw, raw.length);
    }

    public static void rejectIllegalUtf8(byte[] raw, int length) {
        for (int i = 0; i < length; i++) {
            int b = raw[i] & 0xFF;
            if (b == 0) {
                throw new IllegalArgumentException("MQTT string contains NUL");
            }
            if (b < 0x80) {
                continue;
            }
            int need;
            if ((b & 0xE0) == 0xC0) {
                need = 1;
                if (b < 0xC2) {
                    throw new IllegalArgumentException("overlong UTF-8");
                }
            } else if ((b & 0xF0) == 0xE0) {
                need = 2;
            } else if ((b & 0xF8) == 0xF0) {
                need = 3;
                if (b > 0xF4) {
                    throw new IllegalArgumentException("UTF-8 code point out of range");
                }
            } else {
                throw new IllegalArgumentException("illegal UTF-8");
            }
            if (i + need >= length) {
                throw new IllegalArgumentException("truncated UTF-8");
            }
            for (int j = 1; j <= need; j++) {
                if ((raw[i + j] & 0xC0) != 0x80) {
                    throw new IllegalArgumentException("illegal UTF-8 continuation");
                }
            }
            if (need == 2 && b == 0xED && (raw[i + 1] & 0xFF) >= 0xA0) {
                throw new IllegalArgumentException("UTF-8 encodes a surrogate");
            }
            if (need == 3 && b == 0xF0 && (raw[i + 1] & 0xFF) < 0x90) {
                throw new IllegalArgumentException("overlong UTF-8");
            }
            i += need;
        }
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
