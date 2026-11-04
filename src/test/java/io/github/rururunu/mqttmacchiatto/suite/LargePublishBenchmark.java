package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes one reused JSON payload to the in-process broker. Neither client subscribes.
 * A payload of 32768 bytes makes the PUBLISH larger than the 32 KiB stage, so each
 * message is encoded into one direct buffer kept for the next large packet. QoS 0 joins the last write.
 * QoS 1 joins the last acknowledgement. Both inflight windows are 64.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class LargePublishBenchmark {
    static final int BATCH = 8;

    @Param({"32768", "262144", "1048000"})
    int payloadBytes;

    private TestBroker broker;
    private MqttSession session;
    private MqttAsyncClient paho;
    private byte[] topic;
    private byte[] payload;
    private String topicText;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        broker = new TestBroker();
        topicText = "bench/json";
        topic = topicText.getBytes(StandardCharsets.UTF_8);
        payload = LargeEncodeBenchmark.json(payloadBytes);
        session = BenchClients.mac(broker.endpoint(), "mac-json", 64, BATCH + 16);
        paho = BenchClients.paho(broker.endpoint(), "paho-json", 64, new MemoryPersistence());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        BenchClients.close(session);
        BenchClients.close(paho);
        BenchClients.close(broker);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void macchiattoQos0() {
        int last = BATCH - 1;
        for (int i = 0; i < last; i++) {
            session.emit(topic, payload);
        }
        session.publish(topic, payload, MqttQos.AT_MOST_ONCE, false).join();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void pahoQos0() throws Exception {
        var last = paho.publish(topicText, payload, 0, false);
        for (int i = 1; i < BATCH; i++) {
            last = paho.publish(topicText, payload, 0, false);
        }
        last.waitForCompletion();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void macchiattoQos1() {
        CompletableFuture<Void> last = null;
        for (int i = 0; i < BATCH; i++) {
            last = session.publish(topic, payload, MqttQos.AT_LEAST_ONCE, false);
        }
        last.join();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void pahoQos1() throws Exception {
        var last = paho.publish(topicText, payload, 1, false);
        for (int i = 1; i < BATCH; i++) {
            last = paho.publish(topicText, payload, 1, false);
        }
        last.waitForCompletion();
    }
}
