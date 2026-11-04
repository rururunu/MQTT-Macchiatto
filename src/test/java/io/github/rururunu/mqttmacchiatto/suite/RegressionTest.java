package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttConfig;
import io.github.rururunu.mqttmacchiatto.MqttEvents;
import io.github.rururunu.mqttmacchiatto.MqttOverflow;
import io.github.rururunu.mqttmacchiatto.MqttProtocol;
import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import io.github.rururunu.mqttmacchiatto.MqttSessionException;
import io.github.rururunu.mqttmacchiatto.MqttWill;
import io.github.rururunu.mqttmacchiatto.ReconnectPolicy;
import io.github.rururunu.mqttmacchiatto.internal.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User-facing paths that the reconnect and codec tests do not cover: subscribe and unsubscribe,
 * will, credentials, queue limits, keepalive, a clean disk, and a closed session.
 */
@Timeout(20)
class RegressionTest {
    @Test
    void builderRejectsInconsistentOptions() {
        assertThrows(IllegalArgumentException.class, () -> MqttConfig.builder("tcp://127.0.0.1:1")
                .password("secret")
                .build());
        assertThrows(IllegalArgumentException.class, () -> MqttConfig.builder("tcp://127.0.0.1:1")
                .clientId("")
                .cleanSession(false)
                .build());
        assertThrows(IllegalArgumentException.class, () -> MqttConfig.builder("tcp://127.0.0.1:1")
                .protocol(MqttProtocol.MQTT_5)
                .protocolFallback(MqttProtocol.MQTT_5)
                .build());
        assertThrows(IllegalArgumentException.class, () -> MqttConfig.builder("tcp://127.0.0.1:1")
                .persistence(Path.of("sessions"))
                .store(new io.github.rururunu.mqttmacchiatto.MqttStore() {
                    @Override
                    public io.github.rururunu.mqttmacchiatto.MqttSessionImage load() {
                        return io.github.rururunu.mqttmacchiatto.MqttSessionImage.EMPTY;
                    }

                    @Override
                    public void save(io.github.rururunu.mqttmacchiatto.MqttSessionImage image) {
                    }

                    @Override
                    public void discard() {
                    }
                })
                .build());
    }

    @Test
    void publishRejectsAWildcardTopicAndAnEmptySubscribe() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession session = MqttSession.connect(base(broker.endpoint()).build())) {
            assertThrows(IllegalArgumentException.class,
                    () -> session.publish("a/#", new byte[]{1}, MqttQos.AT_MOST_ONCE, false));
            assertThrows(IllegalArgumentException.class, () -> session.subscribe(new io.github.rururunu.mqttmacchiatto.MqttSubscriptionRequest[0]));
            assertEquals(0, session.subscribe("room", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> {
            }).subscriptionId());
        }
    }

    @Test
    void connAckIsVisibleAndMqtt31RoundTrips() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession session = MqttSession.connect(base(broker.endpoint())
                     .protocol(MqttProtocol.MQTT_3_1)
                     .clientId("old")
                     .build())) {
            assertTrue(session.connected());
            assertNotNull(session.connection());
            assertEquals(0, session.connection().reasonCode());
            assertFalse(session.connection().sessionPresent());
            CountDownLatch latch = new CountDownLatch(1);
            session.subscribe("old/t", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> latch.countDown());
            session.publish("old/t", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void unsubscribeStopsOneFilterAndLeavesTheOther() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession session = MqttSession.connect(base(broker.endpoint()).build())) {
            AtomicInteger gone = new AtomicInteger();
            AtomicInteger stay = new AtomicInteger();
            var closed = session.subscribe("gone", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> gone.incrementAndGet());
            session.subscribe("stay", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> stay.incrementAndGet());
            closed.close();
            session.publish("sync", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
            session.publish("gone", new byte[]{2}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
            session.publish("stay", new byte[]{3}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
            Thread.sleep(200);
            assertEquals(0, gone.get());
            assertEquals(1, stay.get());
        }
    }

    @Test
    void closingOneOfTwoListenersKeepsTheFilter() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession session = MqttSession.connect(base(broker.endpoint()).build())) {
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            var left = session.subscribe("room", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> first.incrementAndGet());
            session.subscribe("room", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> second.incrementAndGet());
            left.close();
            session.publish("sync", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
            session.publish("room", new byte[]{2}, MqttQos.AT_MOST_ONCE, false).get(3, TimeUnit.SECONDS);
            Thread.sleep(200);
            assertEquals(0, first.get());
            assertEquals(1, second.get());
        }
    }

    @Test
    void retainedFlagReachesTheSubscriber() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession session = MqttSession.connect(base(broker.endpoint()).build())) {
            AtomicReference<Boolean> flag = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            session.subscribe("keep", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                flag.set(retained);
                latch.countDown();
            });
            session.publish("keep", new byte[]{1}, MqttQos.AT_LEAST_ONCE, true).get(3, TimeUnit.SECONDS);
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, flag.get());
        }
    }

    @Test
    void willIsPublishedOnADropAndNotOnDisconnect() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession listener = MqttSession.connect(base(broker.endpoint()).clientId("will-hear").build())) {
            AtomicInteger heard = new AtomicInteger();
            listener.subscribe("last", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                if (payload.length == 1 && payload[0] == 9) {
                    heard.incrementAndGet();
                }
            });
            MqttConfig.Builder dying = base(broker.endpoint())
                    .clientId("will-die")
                    .will(new MqttWill("last", new byte[]{9}, MqttQos.AT_LEAST_ONCE, false));
            try (MqttSession quiet = MqttSession.connect(dying.clientId("will-quiet").build())) {
                quiet.publish("other", new byte[]{1}, MqttQos.AT_MOST_ONCE, false).get(3, TimeUnit.SECONDS);
            }
            Thread.sleep(200);
            assertEquals(0, heard.get());
            try (MqttSession dropped = MqttSession.connect(dying.clientId("will-drop").build())) {
                dropped.publish("drop", new byte[]{0}, MqttQos.AT_MOST_ONCE, false);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (heard.get() == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
            }
            assertEquals(1, heard.get());
        }
    }

    @Test
    void badCredentialsStopInsteadOfRetrying() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            broker.refuseConnAck = MqttSessionException.BAD_CREDENTIALS;
            MqttSessionException failure = assertThrows(MqttSessionException.class, () -> MqttSession.connect(base(broker.endpoint())
                    .clientId("denied")
                    .username("user")
                    .password("nope")
                    .reconnect(ReconnectPolicy.standard())
                    .build()));
            assertEquals(MqttSessionException.BAD_CREDENTIALS, failure.reasonCode());
            assertFalse(failure.retryable());
        }
    }

    @Test
    void mqtt5BadCredentialsStopInsteadOfRetrying() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            broker.refuseConnAck = 0x86;
            MqttSessionException failure = assertThrows(MqttSessionException.class, () -> MqttSession.connect(base(broker.endpoint())
                    .clientId("denied-v5")
                    .protocol(MqttProtocol.MQTT_5)
                    .username("user")
                    .password("nope")
                    .reconnect(ReconnectPolicy.standard())
                    .build()));
            assertEquals(0x86, failure.reasonCode());
            assertFalse(failure.retryable());
        }
    }

    @Test
    void listenerFailureIsIsolated() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicInteger second = new AtomicInteger();
            MqttConfig config = base(broker.endpoint()).events(new MqttEvents() {
                @Override
                public void listenerThrew(Throwable cause) {
                    thrown.set(cause);
                }
            }).build();
            try (MqttSession session = MqttSession.connect(config)) {
                session.subscribe("boom", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    if (payload[0] == 1) {
                        throw new IllegalStateException("listener failed");
                    }
                    second.incrementAndGet();
                });
                session.publish("boom", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
                session.publish("boom", new byte[]{2}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((thrown.get() == null || second.get() == 0) && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertNotNull(thrown.get());
                assertEquals("listener failed", thrown.get().getMessage());
                assertEquals(1, second.get());
                assertTrue(session.connected());
            }
        }
    }

    @Test
    void closedSessionRejectsPublishAndEmit() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttSession session = MqttSession.connect(base(broker.endpoint()).build());
            session.close();
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> session.publish("t", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false).get(2, TimeUnit.SECONDS));
            assertTrue(failed.getCause() instanceof MqttSessionException);
            assertEquals("session is closed", failed.getCause().getMessage());
            byte[] topic = "t".getBytes(StandardCharsets.UTF_8);
            MqttSessionException emit = assertThrows(MqttSessionException.class, () -> session.emit(topic, new byte[]{1}));
            assertEquals("session is closed", emit.getMessage());
            assertThrows(MqttSessionException.class, () -> session.subscribe("t", MqttQos.AT_MOST_ONCE, (name, payload, qos, retained, duplicate) -> {
            }));
        }
    }

    @Test
    void dropNewestRejectsTheArrivingPublish() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            broker.holdAcks = true;
            try (MqttSession session = MqttSession.connect(base(broker.endpoint())
                    .maxInflight(1)
                    .maxQueued(1)
                    .overflow(MqttOverflow.DROP_NEWEST)
                    .build())) {
                CompletableFuture<Void> first = session.publish("t", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false);
                Thread.sleep(50);
                session.publish("t", new byte[]{2}, MqttQos.AT_LEAST_ONCE, false);
                session.publish("t", new byte[]{3}, MqttQos.AT_LEAST_ONCE, false);
                CompletableFuture<Void> newest = session.publish("t", new byte[]{4}, MqttQos.AT_LEAST_ONCE, false);
                ExecutionException failed = assertThrows(ExecutionException.class, () -> newest.get(2, TimeUnit.SECONDS));
                assertTrue(failed.getCause().getMessage().contains("dropped because the outbound queue is full"));
                assertFalse(first.isCompletedExceptionally());
            }
        }
    }

    @Test
    void rejectLeavesTheQueuedPublish() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            broker.holdAcks = true;
            try (MqttSession session = MqttSession.connect(base(broker.endpoint())
                    .maxInflight(1)
                    .maxQueued(1)
                    .overflow(MqttOverflow.REJECT)
                    .build())) {
                CompletableFuture<Void> first = session.publish("t", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false);
                Thread.sleep(50);
                CompletableFuture<Void> queued = session.publish("t", new byte[]{2}, MqttQos.AT_LEAST_ONCE, false);
                session.publish("t", new byte[]{3}, MqttQos.AT_LEAST_ONCE, false);
                CompletableFuture<Void> refused = session.publish("t", new byte[]{4}, MqttQos.AT_LEAST_ONCE, false);
                ExecutionException failed = assertThrows(ExecutionException.class, () -> refused.get(2, TimeUnit.SECONDS));
                assertEquals("outbound queue is full", failed.getCause().getMessage());
                assertFalse(first.isCompletedExceptionally());
                assertFalse(queued.isCompletedExceptionally());
            }
        }
    }

    @Test
    void qos2ReachesAnotherClientOnce() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession publisher = MqttSession.connect(base(broker.endpoint()).clientId("q2-pub").build());
             MqttSession subscriber = MqttSession.connect(base(broker.endpoint()).clientId("q2-sub").build())) {
            AtomicInteger heard = new AtomicInteger();
            subscriber.subscribe("once", MqttQos.EXACTLY_ONCE, (topic, payload, qos, retained, duplicate) -> heard.incrementAndGet());
            publisher.publish("once", new byte[]{6}, MqttQos.EXACTLY_ONCE, false).get(3, TimeUnit.SECONDS);
            Thread.sleep(200);
            assertEquals(1, heard.get());
        }
    }

    @Test
    void concurrentPublishesAllArrive() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession session = MqttSession.connect(base(broker.endpoint()).maxQueued(4096).build())) {
            int threads = 4;
            int each = 40;
            CountDownLatch heard = new CountDownLatch(threads * each);
            session.subscribe("fan", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> heard.countDown());
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            List<CompletableFuture<Void>> sent = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < threads; i++) {
                Thread.ofPlatform().start(() -> {
                    try {
                        ready.await();
                        for (int n = 0; n < each; n++) {
                            sent.add(session.publish("fan", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            ready.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            for (CompletableFuture<Void> future : sent) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertTrue(heard.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanSessionDiscardsTheDiskImage() throws Exception {
        Path directory = Files.createTempDirectory("macchiatto-clean");
        byte[] topic = "held".getBytes(StandardCharsets.UTF_8);
        SessionStore seeding = SessionStore.open(directory, "clean-disk");
        seeding.rememberOutbound(3, 1, 1, false, topic, new byte[]{4});
        seeding.flush();
        try (TestBroker broker = new TestBroker();
             MqttSession listener = MqttSession.connect(base(broker.endpoint()).clientId("clean-hear").build())) {
            AtomicInteger stale = new AtomicInteger();
            CountDownLatch live = new CountDownLatch(1);
            listener.subscribe("held", MqttQos.AT_MOST_ONCE, (name, payload, qos, retained, duplicate) -> {
                if (payload.length == 1 && payload[0] == 4) {
                    stale.incrementAndGet();
                }
                if (payload.length == 1 && payload[0] == 5) {
                    live.countDown();
                }
            });
            try (MqttSession session = MqttSession.connect(base(broker.endpoint())
                    .clientId("clean-disk")
                    .cleanSession(true)
                    .persistence(directory)
                    .build())) {
                Thread.sleep(300);
                session.publish("held", new byte[]{5}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
                assertTrue(live.await(3, TimeUnit.SECONDS));
                assertEquals(0, stale.get());
            }
        }
    }

    @Test
    void keepaliveKeepsTheSessionOpen() throws Exception {
        try (TestBroker broker = new TestBroker();
             MqttSession session = MqttSession.connect(base(broker.endpoint()).keepAliveSeconds(1).build())) {
            Thread.sleep(2_500);
            assertTrue(session.connected());
            CountDownLatch latch = new CountDownLatch(1);
            session.subscribe("pinged", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> latch.countDown());
            session.publish("pinged", new byte[]{1}, MqttQos.AT_MOST_ONCE, false).get(3, TimeUnit.SECONDS);
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        }
    }

    private static MqttConfig.Builder base(String endpoint) {
        return MqttConfig.builder(endpoint)
                .clientId("regression")
                .keepAliveSeconds(30)
                .connectTimeoutMillis(2000)
                .reconnect(ReconnectPolicy.none());
    }
}
