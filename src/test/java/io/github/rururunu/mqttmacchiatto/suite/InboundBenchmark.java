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
import java.util.concurrent.atomic.AtomicLong;

/**
 * QoS 0 publish plus delivery to one subscriber on the same in-process broker.
 * The iteration ends when the subscriber has observed the whole batch, so the score
 * includes the broker copy and the client callback. Payload is 64 bytes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class InboundBenchmark {
    static final int BATCH = 200;
    static final String TOPIC = "bench/in";

    private TestBroker broker;
    private MqttSession publisher;
    private MqttSession subscriber;
    private MqttAsyncClient pahoPublisher;
    private MqttAsyncClient pahoSubscriber;
    private byte[] topic;
    private byte[] payload;
    private final AtomicLong macReceived = new AtomicLong();
    private final AtomicLong pahoReceived = new AtomicLong();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        broker = new TestBroker();
        topic = TOPIC.getBytes(StandardCharsets.UTF_8);
        payload = new byte[64];
        subscriber = BenchClients.mac(broker.endpoint(), "mac-sub", 64, BATCH + 16);
        subscriber.subscribe(TOPIC, MqttQos.AT_MOST_ONCE, (t, body, qos, retain, dup) -> macReceived.incrementAndGet());
        publisher = BenchClients.mac(broker.endpoint(), "mac-pub", 64, BATCH + 16);
        pahoSubscriber = BenchClients.paho(broker.endpoint(), "paho-sub", 64, new MemoryPersistence());
        pahoSubscriber.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                pahoReceived.incrementAndGet();
            }

            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
            }
        });
        pahoSubscriber.subscribe(TOPIC, 0).waitForCompletion(5_000);
        pahoPublisher = BenchClients.paho(broker.endpoint(), "paho-pub", 64, new MemoryPersistence());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        BenchClients.close(publisher);
        BenchClients.close(subscriber);
        BenchClients.close(pahoPublisher);
        BenchClients.close(pahoSubscriber);
        BenchClients.close(broker);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void macchiatto() {
        long target = macReceived.get() + BATCH;
        int last = BATCH - 1;
        for (int i = 0; i < last; i++) {
            publisher.emit(topic, payload);
        }
        publisher.publish(topic, payload, MqttQos.AT_MOST_ONCE, false).join();
        while (macReceived.get() < target) {
            Thread.onSpinWait();
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void paho() throws Exception {
        long target = pahoReceived.get() + BATCH;
        var last = pahoPublisher.publish(TOPIC, payload, 0, false);
        for (int i = 1; i < BATCH; i++) {
            last = pahoPublisher.publish(TOPIC, payload, 0, false);
        }
        last.waitForCompletion();
        while (pahoReceived.get() < target) {
            Thread.onSpinWait();
        }
    }
}
