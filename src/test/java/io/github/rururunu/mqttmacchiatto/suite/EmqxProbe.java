package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Latency, delivery, and a fixed-size publish load against EMQX at {@code tcp://127.0.0.1:1883}.
 * Run the class main. Not a supported API.
 */
public final class EmqxProbe {
    static final String ENDPOINT = "tcp://127.0.0.1:1883";
    static final int CONNECT_CYCLES = 30;
    static final int LATENCY_WARMUP = 50;
    static final int LATENCY_SAMPLES = 500;
    static final int WINDOW = 32;
    static final int INFLIGHT = 128;

    public static void main(String[] args) throws Exception {
        byte[] payload = EmqxBenchmark.json(128);
        System.out.println("broker tcp://127.0.0.1:1883 payload 128 json-shaped");
        connectCycles(payload);
        latency(payload, 0);
        latency(payload, 1);
        stress(payload, 0, 30_000);
        stress(payload, 1, 15_000);
        stress(payload, 2, 3_000);
    }

    private static void connectCycles(byte[] payload) throws Exception {
        long[] mac = new long[CONNECT_CYCLES];
        long[] paho = new long[CONNECT_CYCLES];
        int macFail = 0;
        int pahoFail = 0;
        for (int i = 0; i < CONNECT_CYCLES; i++) {
            long start = System.nanoTime();
            try (MqttSession session = BenchClients.mac(ENDPOINT, "mac-cycle", INFLIGHT, 8)) {
                session.publish("bench/emqx/cycle", payload, MqttQos.AT_LEAST_ONCE, false).join();
                mac[i] = System.nanoTime() - start;
            } catch (RuntimeException e) {
                macFail++;
                mac[i] = -1;
            }
            start = System.nanoTime();
            MqttAsyncClient client = null;
            try {
                client = BenchClients.paho(ENDPOINT, "paho-cycle", INFLIGHT, new MemoryPersistence());
                client.publish("bench/emqx/cycle", payload, 1, false).waitForCompletion(5_000);
                paho[i] = System.nanoTime() - start;
            } catch (Exception e) {
                pahoFail++;
                paho[i] = -1;
            } finally {
                BenchClients.close(client);
            }
        }
        report("connect+qos1 mac", mac, macFail);
        report("connect+qos1 paho", paho, pahoFail);
    }

    private static void latency(byte[] payload, int qos) throws Exception {
        String topic = "bench/emqx/lat/" + qos;
        long[] mac = sampleMac(topic, payload, qos);
        long[] paho = samplePaho(topic, payload, qos);
        report("e2e qos " + qos + " mac", mac, misses(mac));
        report("e2e qos " + qos + " paho", paho, misses(paho));
    }

    private static long[] sampleMac(String topic, byte[] payload, int qos) throws Exception {
        MqttQos level = MqttQos.fromWire(qos);
        MqttSession publisher = BenchClients.mac(ENDPOINT, "mac-lat-pub-" + qos, INFLIGHT, 8);
        MqttSession subscriber = BenchClients.mac(ENDPOINT, "mac-lat-sub-" + qos, INFLIGHT, 8);
        long[] samples = new long[LATENCY_SAMPLES];
        Arrays.fill(samples, -1);
        try {
            CountDownLatch ready = new CountDownLatch(1);
            Holder hold = new Holder();
            subscriber.subscribe(topic, level, (name, body, messageQos, retained, duplicate) -> {
                if (!hold.expect) {
                    return;
                }
                hold.expect = false;
                hold.elapsed = System.nanoTime() - hold.start;
                hold.done.countDown();
            });
            ready.countDown();
            for (int i = 0; i < LATENCY_WARMUP; i++) {
                oneMac(publisher, topic, payload, level, hold);
            }
            for (int i = 0; i < LATENCY_SAMPLES; i++) {
                samples[i] = oneMac(publisher, topic, payload, level, hold);
            }
        } finally {
            BenchClients.close(publisher);
            BenchClients.close(subscriber);
        }
        return samples;
    }

    private static long oneMac(MqttSession publisher, String topic, byte[] payload, MqttQos level, Holder hold)
            throws InterruptedException {
        hold.done = new CountDownLatch(1);
        hold.start = System.nanoTime();
        hold.expect = true;
        CompletableFuture<Void> ack = publisher.publish(topic, payload, level, false);
        boolean arrived = hold.done.await(2, TimeUnit.SECONDS);
        ack.join();
        return arrived ? hold.elapsed : -1;
    }

    private static long[] samplePaho(String topic, byte[] payload, int qos) throws Exception {
        MqttAsyncClient publisher = BenchClients.paho(ENDPOINT, "paho-lat-pub-" + qos, INFLIGHT, new MemoryPersistence());
        MqttAsyncClient subscriber = BenchClients.paho(ENDPOINT, "paho-lat-sub-" + qos, INFLIGHT, new MemoryPersistence());
        long[] samples = new long[LATENCY_SAMPLES];
        Arrays.fill(samples, -1);
        Holder hold = new Holder();
        try {
            subscriber.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String name, MqttMessage message) {
                    if (!hold.expect) {
                        return;
                    }
                    hold.expect = false;
                    hold.elapsed = System.nanoTime() - hold.start;
                    hold.done.countDown();
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                }
            });
            subscriber.subscribe(topic, qos).waitForCompletion(5_000);
            for (int i = 0; i < LATENCY_WARMUP; i++) {
                onePaho(publisher, topic, payload, qos, hold);
            }
            for (int i = 0; i < LATENCY_SAMPLES; i++) {
                samples[i] = onePaho(publisher, topic, payload, qos, hold);
            }
        } finally {
            BenchClients.close(publisher);
            BenchClients.close(subscriber);
        }
        return samples;
    }

    private static long onePaho(MqttAsyncClient publisher, String topic, byte[] payload, int qos, Holder hold)
            throws Exception {
        hold.done = new CountDownLatch(1);
        hold.start = System.nanoTime();
        hold.expect = true;
        var token = publisher.publish(topic, payload, qos, false);
        boolean arrived = hold.done.await(2, TimeUnit.SECONDS);
        token.waitForCompletion(5_000);
        return arrived ? hold.elapsed : -1;
    }

    private static void stress(byte[] payload, int qos, int count) throws Exception {
        String topic = "bench/emqx/stress/" + qos;
        runMacStress(topic, payload, qos, count);
        runPahoStress(topic, payload, qos, count);
    }

    private static void runMacStress(String topic, byte[] payload, int qos, int count) throws Exception {
        MqttQos level = MqttQos.fromWire(qos);
        MqttSession publisher = BenchClients.mac(ENDPOINT, "mac-stress-pub-" + qos, INFLIGHT, WINDOW + 16);
        MqttSession subscriber = BenchClients.mac(ENDPOINT, "mac-stress-sub-" + qos, INFLIGHT, WINDOW + 16);
        LongAdder received = new LongAdder();
        try {
            subscriber.subscribe(topic, level, (name, body, messageQos, retained, duplicate) -> received.increment());
            Thread.sleep(100);
            long start = System.nanoTime();
            int errors = publishMac(publisher, topic, payload, level, count);
            long ackNanos = System.nanoTime() - start;
            long receivedCount = awaitCount(received, count, 30);
            long totalNanos = System.nanoTime() - start;
            System.out.println("stress mac qos " + qos
                    + " sent " + count
                    + " errors " + errors
                    + " ackMs " + ms(ackNanos)
                    + " ackRate " + rate(count - errors, ackNanos)
                    + " received " + receivedCount
                    + " drainMs " + ms(totalNanos));
        } finally {
            BenchClients.close(publisher);
            BenchClients.close(subscriber);
        }
    }

    private static int publishMac(MqttSession publisher, String topic, byte[] payload, MqttQos level, int count) {
        CompletableFuture<?>[] window = new CompletableFuture<?>[WINDOW];
        int errors = 0;
        for (int i = 0; i < count; i++) {
            int slot = i % WINDOW;
            if (i >= WINDOW) {
                errors += join(window[slot]);
            }
            window[slot] = publisher.publish(topic, payload, level, false);
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

    private static void runPahoStress(String topic, byte[] payload, int qos, int count) throws Exception {
        MqttAsyncClient publisher = BenchClients.paho(ENDPOINT, "paho-stress-pub-" + qos, INFLIGHT, new MemoryPersistence());
        MqttAsyncClient subscriber = BenchClients.paho(ENDPOINT, "paho-stress-sub-" + qos, INFLIGHT, new MemoryPersistence());
        AtomicInteger received = new AtomicInteger();
        try {
            subscriber.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String name, MqttMessage message) {
                    received.incrementAndGet();
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                }
            });
            subscriber.subscribe(topic, qos).waitForCompletion(5_000);
            Thread.sleep(100);
            long start = System.nanoTime();
            int errors = publishPaho(publisher, topic, payload, qos, count);
            long ackNanos = System.nanoTime() - start;
            long receivedCount = awaitCount(received, count, 30);
            long totalNanos = System.nanoTime() - start;
            System.out.println("stress paho qos " + qos
                    + " sent " + count
                    + " errors " + errors
                    + " ackMs " + ms(ackNanos)
                    + " ackRate " + rate(count - errors, ackNanos)
                    + " received " + receivedCount
                    + " drainMs " + ms(totalNanos));
        } finally {
            BenchClients.close(publisher);
            BenchClients.close(subscriber);
        }
    }

    private static int publishPaho(MqttAsyncClient publisher, String topic, byte[] payload, int qos, int count)
            throws Exception {
        org.eclipse.paho.client.mqttv3.IMqttDeliveryToken[] window =
                new org.eclipse.paho.client.mqttv3.IMqttDeliveryToken[WINDOW];
        int errors = 0;
        for (int i = 0; i < count; i++) {
            int slot = i % WINDOW;
            if (i >= WINDOW) {
                errors += waitToken(window[slot]);
            }
            try {
                window[slot] = publisher.publish(topic, payload, qos, false);
            } catch (Exception e) {
                errors++;
                window[slot] = null;
            }
        }
        for (var token : window) {
            errors += waitToken(token);
        }
        return errors;
    }

    private static int waitToken(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
        if (token == null) {
            return 0;
        }
        try {
            token.waitForCompletion(10_000);
            return 0;
        } catch (Exception e) {
            return 1;
        }
    }

    private static long awaitCount(LongAdder received, int expected, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (received.sum() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return received.sum();
    }

    private static long awaitCount(AtomicInteger received, int expected, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (received.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return received.get();
    }

    private static void report(String label, long[] samples, int failures) {
        long[] ok = Arrays.stream(samples).filter(value -> value >= 0).sorted().toArray();
        if (ok.length == 0) {
            System.out.println(label + " failures " + failures + " no samples");
            return;
        }
        System.out.println(label
                + " n " + ok.length
                + " fail " + failures
                + " p50us " + us(percentile(ok, 0.50))
                + " p95us " + us(percentile(ok, 0.95))
                + " p99us " + us(percentile(ok, 0.99))
                + " maxus " + us(ok[ok.length - 1]));
    }

    private static int misses(long[] samples) {
        int misses = 0;
        for (long sample : samples) {
            if (sample < 0) {
                misses++;
            }
        }
        return misses;
    }

    private static long percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index];
    }

    private static long us(long nanos) {
        return nanos / 1_000;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }

    private static long rate(int messages, long nanos) {
        if (nanos <= 0) {
            return 0;
        }
        return (long) (messages * 1_000_000_000.0 / nanos);
    }

    private static final class Holder {
        volatile boolean expect;
        volatile long start;
        volatile long elapsed;
        volatile CountDownLatch done = new CountDownLatch(1);
    }
}
