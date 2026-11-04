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
 * Pipelined publishes to EMQX at {@code tcp://127.0.0.1:1883}. Nobody subscribes, so the score is
 * how fast the broker accepts the stream. QoS 0 joins the last write. QoS 1 and 2 wait for every
 * handshake in the batch. The batch is smaller than the inflight window.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class EmqxBenchmark {
    static final String ENDPOINT = "tcp://127.0.0.1:1883";
    static final int QOS0_BATCH = 200;
    static final int RELIABLE_BATCH = 32;
    static final int INFLIGHT = 128;

    @Param({"128", "1024"})
    public int bytes;

    private MqttSession session;
    private MqttAsyncClient paho;
    private byte[] topic;
    private byte[] payload;
    private String topicText;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        topicText = "bench/emqx/" + bytes;
        topic = topicText.getBytes(StandardCharsets.UTF_8);
        payload = json(bytes);
        session = BenchClients.mac(ENDPOINT, "mac-emqx-" + bytes, INFLIGHT, QOS0_BATCH + 16);
        paho = BenchClients.paho(ENDPOINT, "paho-emqx-" + bytes, INFLIGHT, new MemoryPersistence());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        BenchClients.close(session);
        BenchClients.close(paho);
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
        IMqttDeliveryToken last = null;
        for (int i = 0; i < QOS0_BATCH; i++) {
            last = paho.publish(topicText, payload, 0, false);
        }
        last.waitForCompletion();
    }

    @Benchmark
    @OperationsPerInvocation(RELIABLE_BATCH)
    public void macchiattoQos1() {
        CompletableFuture<?>[] done = new CompletableFuture<?>[RELIABLE_BATCH];
        for (int i = 0; i < RELIABLE_BATCH; i++) {
            done[i] = session.publish(topic, payload, MqttQos.AT_LEAST_ONCE, false);
        }
        CompletableFuture.allOf(done).join();
    }

    @Benchmark
    @OperationsPerInvocation(RELIABLE_BATCH)
    public void pahoQos1() throws Exception {
        IMqttDeliveryToken[] tokens = new IMqttDeliveryToken[RELIABLE_BATCH];
        for (int i = 0; i < RELIABLE_BATCH; i++) {
            tokens[i] = paho.publish(topicText, payload, 1, false);
        }
        for (IMqttDeliveryToken token : tokens) {
            token.waitForCompletion();
        }
    }

    @Benchmark
    @OperationsPerInvocation(RELIABLE_BATCH)
    public void macchiattoQos2() {
        CompletableFuture<?>[] done = new CompletableFuture<?>[RELIABLE_BATCH];
        for (int i = 0; i < RELIABLE_BATCH; i++) {
            done[i] = session.publish(topic, payload, MqttQos.EXACTLY_ONCE, false);
        }
        CompletableFuture.allOf(done).join();
    }

    @Benchmark
    @OperationsPerInvocation(RELIABLE_BATCH)
    public void pahoQos2() throws Exception {
        IMqttDeliveryToken[] tokens = new IMqttDeliveryToken[RELIABLE_BATCH];
        for (int i = 0; i < RELIABLE_BATCH; i++) {
            tokens[i] = paho.publish(topicText, payload, 2, false);
        }
        for (IMqttDeliveryToken token : tokens) {
            token.waitForCompletion();
        }
    }

    static byte[] json(int size) {
        byte[] body = new byte[size];
        byte[] head = "{\"n\":0,\"pad\":\"".getBytes(StandardCharsets.US_ASCII);
        int copy = Math.min(head.length, size);
        System.arraycopy(head, 0, body, 0, copy);
        for (int i = copy; i < size; i++) {
            body[i] = 'x';
        }
        if (size >= 2) {
            body[size - 2] = '"';
            body[size - 1] = '}';
        }
        return body;
    }
}
