package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.internal.Wire;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttPublish;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Encodes one QoS 0 PUBLISH whose payload is UTF-8 JSON text.
 * 32768 bytes is the first size that no longer fits the 32 KiB stage.
 * 1048000 bytes sits just under the default 1 MiB packet cap.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class LargeEncodeBenchmark {
    @Param({"32768", "262144", "1048000"})
    int payloadBytes;

    private byte[] topic;
    private byte[] payload;
    private ByteBuffer buffer;
    private MqttPublish paho;

    @Setup
    public void setup() {
        topic = "sensors/json".getBytes(StandardCharsets.UTF_8);
        payload = json(payloadBytes);
        buffer = ByteBuffer.allocate(payloadBytes + 64);
        MqttMessage message = new MqttMessage(payload);
        message.setQos(0);
        paho = new MqttPublish("sensors/json", message);
    }

    @Benchmark
    public void macchiatto(Blackhole blackhole) {
        buffer.clear();
        Wire.encodePublish(buffer, topic, payload, 0, false, false, 0);
        blackhole.consume(buffer.position());
    }

    @Benchmark
    public void paho(Blackhole blackhole) throws Exception {
        blackhole.consume(paho.getHeader());
        blackhole.consume(paho.getPayload());
    }

    static byte[] json(int bytes) {
        byte[] prefix = "{\"pad\":\"".getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = "\"}".getBytes(StandardCharsets.US_ASCII);
        if (bytes < prefix.length + suffix.length) {
            throw new IllegalArgumentException("payload shorter than the JSON wrapper");
        }
        byte[] payload = new byte[bytes];
        System.arraycopy(prefix, 0, payload, 0, prefix.length);
        int fillEnd = bytes - suffix.length;
        for (int i = prefix.length; i < fillEnd; i++) {
            payload[i] = 'x';
        }
        System.arraycopy(suffix, 0, payload, fillEnd, suffix.length);
        return payload;
    }
}
