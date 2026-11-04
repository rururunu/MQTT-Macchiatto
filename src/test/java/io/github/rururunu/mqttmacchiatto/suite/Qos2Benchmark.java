package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * QoS 2 publishes against the in-process broker. Both clients use the same inflight window,
 * because a packet id is held across PUBREC and PUBCOMP. Every completion in the batch is
 * joined. The batch is smaller than the window: Paho rejects a publish when its inflight
 * counter has not yet dropped, instead of waiting. Neither client subscribes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class Qos2Benchmark {
    static final int BATCH = 32;
    static final int INFLIGHT = 128;

    private TestBroker broker;
    private MqttSession session;
    private MqttAsyncClient paho;
    private byte[] topic;
    private byte[] payload;
    private String topicText;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        broker = new TestBroker();
        topicText = "bench/qos2";
        topic = topicText.getBytes(StandardCharsets.UTF_8);
        payload = new byte[64];
        session = BenchClients.mac(broker.endpoint(), "mac-qos2", INFLIGHT, BATCH + 16);
        paho = BenchClients.paho(broker.endpoint(), "paho-qos2", INFLIGHT, new MemoryPersistence());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        BenchClients.close(session);
        BenchClients.close(paho);
        BenchClients.close(broker);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void macchiatto() {
        CompletableFuture<?>[] done = new CompletableFuture<?>[BATCH];
        for (int i = 0; i < BATCH; i++) {
            done[i] = session.publish(topic, payload, MqttQos.EXACTLY_ONCE, false);
        }
        CompletableFuture.allOf(done).join();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void paho() throws Exception {
        IMqttDeliveryToken[] tokens = new IMqttDeliveryToken[BATCH];
        for (int i = 0; i < BATCH; i++) {
            tokens[i] = paho.publish(topicText, payload, 2, false);
        }
        for (IMqttDeliveryToken token : tokens) {
            token.waitForCompletion();
        }
    }
}
