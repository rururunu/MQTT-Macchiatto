package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.internal.Frame;
import io.github.rururunu.mqttmacchiatto.internal.Wire;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireTest {
    @Test
    void connectMatchesTheSpecLayout() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        Wire.encodeConnect(buffer, new byte[]{'a'}, true, 10, null, null, null, null, 0, false);
        buffer.flip();
        byte[] actual = new byte[buffer.remaining()];
        buffer.get(actual);
        assertArrayEquals(new byte[]{
                0x10, 0x0D,
                0x00, 0x04, 'M', 'Q', 'T', 'T',
                0x04, 0x02, 0x00, 0x0A,
                0x00, 0x01, 'a'
        }, actual);
    }

    @Test
    void publishQos0AndQos1() {
        byte[] topic = new byte[]{'a'};
        byte[] payload = new byte[]{'h', 'i'};
        ByteBuffer qos0 = ByteBuffer.allocate(16);
        Wire.encodePublish(qos0, topic, payload, 0, false, false, 0);
        qos0.flip();
        assertArrayEquals(new byte[]{0x30, 0x05, 0x00, 0x01, 'a', 'h', 'i'}, bytes(qos0));

        ByteBuffer qos1 = ByteBuffer.allocate(16);
        Wire.encodePublish(qos1, topic, payload, 1, false, false, 7);
        qos1.flip();
        assertArrayEquals(new byte[]{0x32, 0x07, 0x00, 0x01, 'a', 0x00, 0x07, 'h', 'i'}, bytes(qos1));
    }

    @Test
    void roundTripSurvivesASplitRemainingLength() {
        byte[] topic = "sensors/temp".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[200];
        ByteBuffer encoded = ByteBuffer.allocate(256);
        Wire.encodePublish(encoded, topic, payload, 0, true, false, 0);
        encoded.flip();
        ByteBuffer split = ByteBuffer.allocate(256);
        split.put(encoded.get());
        split.flip();
        Frame frame = new Frame();
        assertEquals(-1, Wire.openFrame(split, frame, 10_000));
        encoded.position(0);
        split.clear();
        split.put(encoded);
        split.flip();
        int body = Wire.openFrame(split, frame, 10_000);
        assertEquals(Wire.PUBLISH, frame.type);
        assertEquals(1, frame.flags & 0x01);
        assertEquals(body, frame.bodyLength);
        String decoded = Wire.readString(split);
        assertEquals("sensors/temp", decoded);
        assertEquals(200, frame.bodyLength - (2 + topic.length));
    }

    @Test
    void rejectsNulInATopicName() {
        assertThrows(IllegalArgumentException.class, () -> Wire.topicName("a\u0000b"));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }
}
