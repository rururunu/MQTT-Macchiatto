package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttConfig;
import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import io.github.rururunu.mqttmacchiatto.ReconnectPolicy;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pipelined publishes against the in-process broker. The last message is joined, so earlier
 * messages in the same batch have already been written. QoS 0 uses {@link MqttSession#emit}
 * for every message except the last. Neither client subscribes, so the broker only reads.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class PublishBenchmark {
    static final int QOS0_BATCH = 1000;
    static final int QOS1_BATCH = 200;

    private TestBroker broker;
    private MqttSession session;
    private MqttAsyncClient paho;
    private byte[] topic;
    private byte[] payload;
    private String topicText;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        broker = new TestBroker();
        topicText = "bench/t";
        topic = topicText.getBytes(StandardCharsets.UTF_8);
        payload = new byte[64];
        session = MqttSession.connect(MqttConfig.builder(broker.endpoint())
                .clientId("mac-bench")
                .keepAliveSeconds(0)
                .connectTimeoutMillis(5_000)
                .maxInflight(64)
                .maxQueued(QOS0_BATCH + 16)
                .reconnect(ReconnectPolicy.none())
                .build());
        paho = new MqttAsyncClient(broker.endpoint(), "paho-bench", new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        options.setKeepAliveInterval(0);
        options.setMaxInflight(QOS0_BATCH);
        paho.connect(options).waitForCompletion(5_000);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        try {
            session.close();
        } catch (RuntimeException ignored) {
        }
        try {
            paho.disconnect().waitForCompletion(2_000);
            paho.close();
        } catch (Exception ignored) {
        }
        try {
            broker.close();
        } catch (Exception ignored) {
        }
    }

    @Benchmark
    @OperationsPerInvocation(QOS0_BATCH)
    public void macchiattoQos0() {
        int last = QOS0_BATCH - 1;
        for (int i = 0; i < last; i++) {
            session.emit(topic, payload);
        }
        session.publish(topic, payload, MqttQos.AT_MOST_ONCE, false).join();
    }

    @Benchmark
    @OperationsPerInvocation(QOS0_BATCH)
    public void pahoQos0() throws Exception {
        var last = paho.publish(topicText, payload, 0, false);
        for (int i = 1; i < QOS0_BATCH; i++) {
            last = paho.publish(topicText, payload, 0, false);
        }
        last.waitForCompletion();
    }

    @Benchmark
    @OperationsPerInvocation(QOS1_BATCH)
    public void macchiattoQos1() {
        CompletableFuture<Void> last = null;
        for (int i = 0; i < QOS1_BATCH; i++) {
            last = session.publish(topic, payload, MqttQos.AT_LEAST_ONCE, false);
        }
        last.join();
    }

    @Benchmark
    @OperationsPerInvocation(QOS1_BATCH)
    public void pahoQos1() throws Exception {
        var last = paho.publish(topicText, payload, 1, false);
        for (int i = 1; i < QOS1_BATCH; i++) {
            last = paho.publish(topicText, payload, 1, false);
        }
        last.waitForCompletion();
    }
}
