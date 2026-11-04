package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Receive path against EMQX. One Macchiatto publisher feeds either subscriber, so the publisher
 * is not the difference. Run the class main. Not a supported API.
 */
public final class EmqxReceiveProbe {
    static final String ENDPOINT = "tcp://127.0.0.1:1883";
    static final int WINDOW = 32;
    static final int INFLIGHT = 128;

    public static void main(String[] args) throws Exception {
        byte[] payload = EmqxBenchmark.json(128);
        System.out.println("broker tcp://127.0.0.1:1883 publisher macchiatto payload 128");
        MqttSession publisher = BenchClients.mac(ENDPOINT, "mac-recv-src", INFLIGHT, WINDOW + 16);
        try {
            receive(publisher, payload, 0, 30_000);
            receive(publisher, payload, 1, 15_000);
            receive(publisher, payload, 2, 3_000);
            wildcard(publisher, payload, 2_000);
        } finally {
            BenchClients.close(publisher);
        }
    }

    private static void receive(MqttSession publisher, byte[] payload, int qos, int count) throws Exception {
        String topic = "bench/emqx/isolate/" + qos;
        runMac(publisher, topic, payload, qos, count);
        runPaho(publisher, topic, payload, qos, count);
    }

    private static void runMac(MqttSession publisher, String topic, byte[] payload, int qos, int count) throws Exception {
        MqttSession subscriber = BenchClients.mac(ENDPOINT, "mac-isolate-sub-" + qos, INFLIGHT, WINDOW + 16);
        Span span = new Span();
        try {
            subscriber.subscribe(topic, MqttQos.fromWire(qos), (name, body, messageQos, retained, duplicate) -> span.hit());
            Thread.sleep(100);
            long started = System.nanoTime();
            int errors = publish(publisher, topic, payload, qos, count);
            long published = System.nanoTime();
            boolean full = span.await(count, 30);
            long drained = System.nanoTime();
            print("mac", qos, count, errors, span, started, published, drained, full);
        } finally {
            BenchClients.close(subscriber);
        }
    }

    private static void runPaho(MqttSession publisher, String topic, byte[] payload, int qos, int count) throws Exception {
        MqttAsyncClient subscriber = BenchClients.paho(ENDPOINT, "paho-isolate-sub-" + qos, INFLIGHT, new MemoryPersistence());
        Span span = new Span();
        try {
            subscriber.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String name, MqttMessage message) {
                    span.hit();
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                }
            });
            subscriber.subscribe(topic, qos).waitForCompletion(5_000);
            Thread.sleep(100);
            long started = System.nanoTime();
            int errors = publish(publisher, topic, payload, qos, count);
            long published = System.nanoTime();
            boolean full = span.await(count, 30);
            long drained = System.nanoTime();
            print("paho", qos, count, errors, span, started, published, drained, full);
        } finally {
            BenchClients.close(subscriber);
        }
    }

    private static void wildcard(MqttSession publisher, byte[] payload, int count) throws Exception {
        String filter = "bench/emqx/wild/#";
        String topic = "bench/emqx/wild/room";
        MqttSession mac = BenchClients.mac(ENDPOINT, "mac-wild", INFLIGHT, WINDOW + 16);
        MqttAsyncClient paho = BenchClients.paho(ENDPOINT, "paho-wild", INFLIGHT, new MemoryPersistence());
        Span macSpan = new Span();
        Span pahoSpan = new Span();
        try {
            mac.subscribe(filter, MqttQos.AT_MOST_ONCE, (name, body, messageQos, retained, duplicate) -> macSpan.hit());
            paho.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String name, MqttMessage message) {
                    pahoSpan.hit();
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                }
            });
            paho.subscribe(filter, 0).waitForCompletion(5_000);
            Thread.sleep(100);
            int errors = publish(publisher, topic, payload, 0, count);
            boolean macFull = macSpan.await(count, 15);
            boolean pahoFull = pahoSpan.await(count, 15);
            System.out.println("wildcard qos 0 sent " + count
                    + " errors " + errors
                    + " mac " + macSpan.count.sum()
                    + " macFull " + macFull
                    + " paho " + pahoSpan.count.sum()
                    + " pahoFull " + pahoFull);
        } finally {
            BenchClients.close(mac);
            BenchClients.close(paho);
        }
    }

    private static int publish(MqttSession publisher, String topic, byte[] payload, int qos, int count) {
        MqttQos level = MqttQos.fromWire(qos);
        byte[] topicBytes = topic.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CompletableFuture<?>[] window = new CompletableFuture<?>[WINDOW];
        int errors = 0;
        for (int i = 0; i < count; i++) {
            int slot = i % WINDOW;
            if (i >= WINDOW) {
                errors += join(window[slot]);
            }
            window[slot] = publisher.publish(topicBytes, payload, level, false);
        }
        for (CompletableFuture<?> pending : window) {
            if (pending != null) {
                errors += join(pending);
            }
        }
        return errors;
    }

    private static int join(CompletableFuture<?> pending) {
        try {
            pending.join();
            return 0;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static void print(String client, int qos, int count, int errors, Span span, long started, long published, long drained, boolean full) {
        long received = span.count.sum();
        long callbackSpan = span.last.get() - span.first.get();
        System.out.println("recv " + client
                + " qos " + qos
                + " sent " + count
                + " errors " + errors
                + " received " + received
                + " full " + full
                + " callbackRate " + rate(received, callbackSpan)
                + " callbackMs " + ms(callbackSpan)
                + " publishMs " + ms(published - started)
                + " drainMs " + ms(drained - published));
    }

    private static long ms(long nanos) {
        return Math.max(0, nanos) / 1_000_000;
    }

    private static long rate(long messages, long nanos) {
        if (messages <= 0 || nanos <= 0) {
            return 0;
        }
        return (long) (messages * 1_000_000_000.0 / nanos);
    }

    private static final class Span {
        final LongAdder count = new LongAdder();
        final AtomicLong first = new AtomicLong();
        final AtomicLong last = new AtomicLong();

        void hit() {
            long now = System.nanoTime();
            first.compareAndSet(0, now);
            last.set(now);
            count.increment();
        }

        boolean await(int expected, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (count.sum() < expected && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            return count.sum() >= expected;
        }
    }
}
