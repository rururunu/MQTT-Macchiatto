package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivered throughput on EMQX. Each client publishes only to its own subscriber, and the
 * iteration ends when that subscriber has seen the whole batch. QoS 0 joins the last write.
 * QoS 1 and 2 wait for every publisher handshake as well.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class EmqxReceiveBenchmark {
    static final String ENDPOINT = "tcp://127.0.0.1:1883";
    static final int QOS0_BATCH = 200;
    static final int RELIABLE_BATCH = 32;
    static final int INFLIGHT = 128;

    @Param({"128", "1024"})
    public int bytes;

    private MqttSession macPublisher;
    private MqttSession macSubscriber;
    private MqttAsyncClient pahoPublisher;
    private MqttAsyncClient pahoSubscriber;
    private byte[] macTopic;
    private byte[] pahoTopic;
    private byte[] payload;
    private String macTopicText;
    private String pahoTopicText;
    private final AtomicLong macReceived = new AtomicLong();
    private final AtomicLong pahoReceived = new AtomicLong();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        macTopicText = "bench/emqx/recv/mac/" + bytes;
        pahoTopicText = "bench/emqx/recv/paho/" + bytes;
        macTopic = macTopicText.getBytes(StandardCharsets.UTF_8);
        pahoTopic = pahoTopicText.getBytes(StandardCharsets.UTF_8);
        payload = EmqxBenchmark.json(bytes);
        macSubscriber = BenchClients.mac(ENDPOINT, "mac-recv-sub-" + bytes, INFLIGHT, QOS0_BATCH + 16);
        macSubscriber.subscribe(macTopicText, MqttQos.EXACTLY_ONCE, (topic, body, qos, retained, duplicate) -> macReceived.incrementAndGet());
        macPublisher = BenchClients.mac(ENDPOINT, "mac-recv-pub-" + bytes, INFLIGHT, QOS0_BATCH + 16);
        pahoSubscriber = BenchClients.paho(ENDPOINT, "paho-recv-sub-" + bytes, INFLIGHT, new MemoryPersistence());
        pahoSubscriber.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                pahoReceived.incrementAndGet();
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
        pahoSubscriber.subscribe(pahoTopicText, 2).waitForCompletion(5_000);
        pahoPublisher = BenchClients.paho(ENDPOINT, "paho-recv-pub-" + bytes, INFLIGHT, new MemoryPersistence());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        BenchClients.close(macPublisher);
        BenchClients.close(macSubscriber);
        BenchClients.close(pahoPublisher);
        BenchClients.close(pahoSubscriber);
    }

    @Benchmark
    @OperationsPerInvocation(QOS0_BATCH)
    public void macchiattoQos0() {
        long target = macReceived.get() + QOS0_BATCH;
        int last = QOS0_BATCH - 1;
        for (int i = 0; i < last; i++) {
            macPublisher.emit(macTopic, payload);
        }
        macPublisher.publish(macTopic, payload, MqttQos.AT_MOST_ONCE, false).join();
        await(macReceived, target);
    }

    @Benchmark
    @OperationsPerInvocation(QOS0_BATCH)
    public void pahoQos0() throws Exception {
        long target = pahoReceived.get() + QOS0_BATCH;
        IMqttDeliveryToken last = null;
        for (int i = 0; i < QOS0_BATCH; i++) {
            last = pahoPublisher.publish(pahoTopicText, payload, 0, false);
        }
        last.waitForCompletion();
        await(pahoReceived, target);
    }

    @Benchmark
    @OperationsPerInvocation(RELIABLE_BATCH)
    public void macchiattoQos1() {
        long target = macReceived.get() + RELIABLE_BATCH;
        CompletableFuture<?>[] done = new CompletableFuture<?>[RELIABLE_BATCH];
        for (int i = 0; i < RELIABLE_BATCH; i++) {
            done[i] = macPublisher.publish(macTopic, payload, MqttQos.AT_LEAST_ONCE, false);
        }
        CompletableFuture.allOf(done).join();
        await(macReceived, target);
    }

    @Benchmark
    @OperationsPerInvocation(RELIABLE_BATCH)
    public void pahoQos1() throws Exception {
        long target = pahoReceived.get() + RELIABLE_BATCH;
        IMqttDeliveryToken[] tokens = new IMqttDeliveryToken[RELIABLE_BATCH];
        for (int i = 0; i < RELIABLE_BATCH; i++) {
            tokens[i] = pahoPublisher.publish(pahoTopicText, payload, 1, false);
        }
        for (IMqttDeliveryToken token : tokens) {
            token.waitForCompletion();
        }
        await(pahoReceived, target);
    }

    @Benchmark
    @OperationsPerInvocation(RELIABLE_BATCH)
    public void macchiattoQos2() {
        long target = macReceived.get() + RELIABLE_BATCH;
        CompletableFuture<?>[] done = new CompletableFuture<?>[RELIABLE_BATCH];
        for (int i = 0; i < RELIABLE_BATCH; i++) {
            done[i] = macPublisher.publish(macTopic, payload, MqttQos.EXACTLY_ONCE, false);
        }
        CompletableFuture.allOf(done).join();
        await(macReceived, target);
    }

    @Benchmark
    @OperationsPerInvocation(RELIABLE_BATCH)
    public void pahoQos2() throws Exception {
        long target = pahoReceived.get() + RELIABLE_BATCH;
        IMqttDeliveryToken[] tokens = new IMqttDeliveryToken[RELIABLE_BATCH];
        for (int i = 0; i < RELIABLE_BATCH; i++) {
            tokens[i] = pahoPublisher.publish(pahoTopicText, payload, 2, false);
        }
        for (IMqttDeliveryToken token : tokens) {
            token.waitForCompletion();
        }
        await(pahoReceived, target);
    }

    private static void await(AtomicLong received, long target) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (received.get() < target) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("subscriber saw " + received.get() + " of " + target);
            }
            Thread.onSpinWait();
        }
    }
}
