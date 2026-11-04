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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * QoS 0 publishes over WebSocket ({@code ws://host/mqtt}) to the in-process broker.
 * Payload is 64 bytes. Nobody subscribes. The last publish is joined.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class WebSocketBenchmark {
    static final int BATCH = 200;

    private TestBroker broker;
    private MqttSession session;
    private MqttAsyncClient paho;
    private byte[] topic;
    private byte[] payload;
    private String topicText;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        broker = new TestBroker();
        topicText = "bench/ws";
        topic = topicText.getBytes(StandardCharsets.UTF_8);
        payload = new byte[64];
        String endpoint = broker.webSocketEndpoint();
        session = BenchClients.mac(endpoint, "mac-ws", 64, BATCH + 16);
        paho = BenchClients.paho(endpoint, "paho-ws", 64, new MemoryPersistence());
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
        int last = BATCH - 1;
        for (int i = 0; i < last; i++) {
            session.emit(topic, payload);
        }
        session.publish(topic, payload, MqttQos.AT_MOST_ONCE, false).join();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void paho() throws Exception {
        var last = paho.publish(topicText, payload, 0, false);
        for (int i = 1; i < BATCH; i++) {
            last = paho.publish(topicText, payload, 0, false);
        }
        last.waitForCompletion();
    }
}
