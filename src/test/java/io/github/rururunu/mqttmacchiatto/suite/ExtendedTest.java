package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttAuthenticator;
import io.github.rururunu.mqttmacchiatto.MqttConfig;
import io.github.rururunu.mqttmacchiatto.MqttDelivery;
import io.github.rururunu.mqttmacchiatto.MqttLinkState;
import io.github.rururunu.mqttmacchiatto.MqttListener;
import io.github.rururunu.mqttmacchiatto.MqttOverflow;
import io.github.rururunu.mqttmacchiatto.MqttProperties;
import io.github.rururunu.mqttmacchiatto.MqttProtocol;
import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import io.github.rururunu.mqttmacchiatto.MqttSessionImage;
import io.github.rururunu.mqttmacchiatto.MqttStore;
import io.github.rururunu.mqttmacchiatto.MqttSubscriptionRequest;
import io.github.rururunu.mqttmacchiatto.ReconnectPolicy;
import io.github.rururunu.mqttmacchiatto.internal.SessionStore;
import io.github.rururunu.mqttmacchiatto.internal.V5;
import io.github.rururunu.mqttmacchiatto.internal.WebSocketCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class ExtendedTest {
    @Test
    void qos2PublishResumesWithPubRel() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint())
                    .cleanSession(false)
                    .reconnect(new ReconnectPolicy(20, 20, 1.0, 8))
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                session.publish("stall", new byte[]{1}, MqttQos.EXACTLY_ONCE, false).join();
            }
        }
    }

    @Test
    void qos2InboundSurvivesReconnect() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint())
                    .clientId("qos2-in")
                    .cleanSession(false)
                    .reconnect(new ReconnectPolicy(20, 20, 1.0, 8))
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                CountDownLatch latch = new CountDownLatch(1);
                session.subscribe("injected", MqttQos.EXACTLY_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    assertEquals("injected", topic);
                    assertEquals(1, payload.length);
                    assertEquals(7, payload[0]);
                    latch.countDown();
                });
                session.publish("inject", new byte[]{1}, MqttQos.AT_MOST_ONCE, false).join();
                assertTrue(latch.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void mqtt5PropertiesAndTopicAlias() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint())
                    .clientId("v5")
                    .protocol(MqttProtocol.MQTT_5)
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                List<String> topics = new ArrayList<>();
                List<String> types = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(2);
                session.subscribe("sensors/#", MqttQos.AT_LEAST_ONCE, new MqttListener() {
                    @Override
                    public void onMessage(String topic, byte[] payload, MqttQos qos, boolean retained, boolean duplicate) {
                    }

                    @Override
                    public void onMessage(MqttDelivery delivery) {
                        topics.add(delivery.topic());
                        types.add(delivery.properties() == null ? null : delivery.properties().contentType());
                        latch.countDown();
                    }
                });
                MqttProperties properties = MqttProperties.builder().contentType("text/plain").messageExpirySeconds(30).build();
                byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
                session.publish("sensors/a", payload, MqttQos.AT_LEAST_ONCE, false, properties).join();
                session.publish("sensors/a", payload, MqttQos.AT_LEAST_ONCE, false, properties).join();
                assertTrue(latch.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("sensors/a", "sensors/a"), topics);
                assertEquals(List.of("text/plain", "text/plain"), types);
            }
        }
    }

    @Test
    void webSocketCarriesAPublish() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            try (MqttSession session = MqttSession.connect(base(broker.webSocketEndpoint()).clientId("ws").build())) {
                CountDownLatch latch = new CountDownLatch(1);
                session.subscribe("ws/t", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    assertEquals("ws/t", topic);
                    latch.countDown();
                });
                session.publish("ws/t", new byte[]{3}, MqttQos.AT_MOST_ONCE, false).join();
                assertTrue(latch.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void diskRestoresAnUnacknowledgedPublish() throws Exception {
        Path directory = Files.createTempDirectory("macchiatto-session");
        byte[] topic = "held".getBytes(StandardCharsets.UTF_8);
        SessionStore seeding = SessionStore.open(directory, "disk-client");
        seeding.rememberOutbound(3, 1, 1, false, topic, new byte[]{4});
        seeding.flush();
        try (TestBroker broker = new TestBroker();
             MqttSession listener = MqttSession.connect(base(broker.endpoint()).clientId("listener").build())) {
            CountDownLatch latch = new CountDownLatch(1);
            listener.subscribe("held", MqttQos.AT_MOST_ONCE, (name, payload, qos, retained, duplicate) -> {
                if (payload.length == 1 && payload[0] == 4) {
                    latch.countDown();
                }
            });
            MqttConfig restored = base(broker.endpoint())
                    .clientId("disk-client")
                    .cleanSession(false)
                    .persistence(directory)
                    .build();
            try (MqttSession session = MqttSession.connect(restored)) {
                assertTrue(latch.await(5, TimeUnit.SECONDS));
                assertEquals("disk-client", session.clientId());
            }
        }
    }

    @Test
    void mqtt5ConnectMatchesThePropertyLayout() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        V5.encodeConnect(buffer, new byte[]{'a'}, true, 10, null, null, null, null, 0, false,
                0, 32, 8, 1024);
        buffer.flip();
        byte[] actual = new byte[buffer.remaining()];
        buffer.get(actual);
        assertEquals(0x10, actual[0]);
        assertEquals(5, actual[8]);
        assertEquals(0x02, actual[9]);
    }

    @Test
    void webSocketFrameRoundTrips() {
        byte[] payload = new byte[]{0x30, 0x00};
        ByteBuffer mqtt = ByteBuffer.wrap(payload);
        ByteBuffer framed = ByteBuffer.allocate(32);
        WebSocketCodec client = new WebSocketCodec(true);
        client.encodeBinary(mqtt, framed);
        ByteBuffer raw = ByteBuffer.allocate(32);
        raw.put(framed);
        WebSocketCodec server = new WebSocketCodec(false);
        ByteBuffer out = ByteBuffer.allocate(8);
        assertTrue(server.pull(raw, out));
        out.flip();
        assertEquals(2, out.remaining());
        assertEquals(0x30, out.get());
        assertEquals(0x00, out.get());
    }

    @Test
    void secondEndpointIsUsedWhenTheFirstRefuses() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = MqttConfig.builder("tcp://127.0.0.1:1", broker.endpoint())
                    .clientId("failover")
                    .keepAliveSeconds(30)
                    .connectTimeoutMillis(400)
                    .reconnect(ReconnectPolicy.none())
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                assertTrue(session.connected());
            }
        }
    }

    @Test
    void protocolFallsBackWithoutChangingTheRequestedProtocol() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            broker.refuseProtocolLevel = 4;
            MqttConfig config = base(broker.endpoint())
                    .clientId("fallback")
                    .protocol(MqttProtocol.MQTT_3_1_1)
                    .protocolFallback(MqttProtocol.MQTT_3_1)
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                assertEquals(0, session.connection().reasonCode());
                assertEquals(MqttProtocol.MQTT_3_1_1, config.protocol());
            }
        }
    }

    @Test
    void enhancedAuthAnswersTheChallenge() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            byte[] response = new byte[]{9, 9};
            MqttAuthenticator authenticator = (method, challenge) -> {
                assertEquals("token", method);
                assertArrayEquals(new byte[]{1, 2, 3}, challenge);
                return response;
            };
            MqttConfig config = base(broker.endpoint())
                    .clientId("auth")
                    .protocol(MqttProtocol.MQTT_5)
                    .authMethod("token")
                    .authenticator(authenticator)
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                assertArrayEquals(response, broker.lastAuthData);
                assertEquals(128, session.connection().receiveMaximum());
                assertEquals(8, session.connection().topicAliasMaximum());
            }
        }
    }

    @Test
    void oneSubscribeCarriesManyFiltersAndASubscriptionId() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint())
                    .clientId("subs")
                    .protocol(MqttProtocol.MQTT_5)
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                CountDownLatch latch = new CountDownLatch(1);
                int[] seen = new int[1];
                session.subscribe(
                        new MqttSubscriptionRequest("plant/a", MqttQos.AT_MOST_ONCE,
                                (topic, payload, qos, retained, duplicate) -> {
                                }),
                        new MqttSubscriptionRequest("plant/b", MqttQos.AT_MOST_ONCE, new MqttListener() {
                            @Override
                            public void onMessage(String topic, byte[] payload, MqttQos qos, boolean retained, boolean duplicate) {
                            }

                            @Override
                            public void onMessage(MqttDelivery delivery) {
                                seen[0] = delivery.subscriptionIds().length == 0 ? 0 : delivery.subscriptionIds()[0];
                                latch.countDown();
                            }
                        }));
                assertTrue(broker.lastSubscriptionId > 0);
                session.publish("plant/b", new byte[]{1}, MqttQos.AT_MOST_ONCE, false).join();
                assertTrue(latch.await(5, TimeUnit.SECONDS));
                assertEquals(broker.lastSubscriptionId, seen[0]);
            }
        }
    }

    @Test
    void willDelayIsAConnectProperty() {
        V5.Connect extra = new V5.Connect();
        extra.willDelay = 15;
        ByteBuffer buffer = ByteBuffer.allocate(128);
        V5.encodeConnect(buffer, new byte[]{'a'}, true, 10, null, null, "w".getBytes(StandardCharsets.US_ASCII),
                new byte[]{1}, 0, false, 0, 32, 8, 1024, extra);
        buffer.flip();
        byte[] actual = new byte[buffer.remaining()];
        buffer.get(actual);
        assertTrue(indexOf(actual, new byte[]{24, 0, 0, 0, 15}) >= 0);
    }

    @Test
    void webSocketHeaderIsSent() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.webSocketEndpoint())
                    .clientId("ws-header")
                    .webSocketHeader("X-Test", "mac")
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                assertTrue(session.connected());
            }
            assertNotNull(broker.lastWebSocketRequest);
            assertTrue(broker.lastWebSocketRequest.contains("X-Test: mac"));
        }
    }

    @Test
    void customStoreKeepsPublishProperties() throws Exception {
        MemoryStore store = new MemoryStore();
        try (TestBroker broker = new TestBroker()) {
            broker.holdAcks = true;
            MqttProperties properties = MqttProperties.builder().contentType("application/json").build();
            MqttConfig config = base(broker.endpoint())
                    .clientId("stored")
                    .protocol(MqttProtocol.MQTT_5)
                    .cleanSession(false)
                    .store(store)
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                session.publish("kept", new byte[]{9}, MqttQos.AT_LEAST_ONCE, false, properties);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (store.image.outbound().isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
            }
            assertFalse(store.image.outbound().isEmpty());
            assertEquals("application/json", store.image.outbound().get(0).properties().contentType());
            broker.holdAcks = false;
            try (MqttSession listener = MqttSession.connect(base(broker.endpoint()).clientId("store-listener")
                    .protocol(MqttProtocol.MQTT_5).build())) {
                CountDownLatch latch = new CountDownLatch(1);
                listener.subscribe("kept", MqttQos.AT_MOST_ONCE, new MqttListener() {
                    @Override
                    public void onMessage(String topic, byte[] payload, MqttQos qos, boolean retained, boolean duplicate) {
                    }

                    @Override
                    public void onMessage(MqttDelivery delivery) {
                        if (delivery.properties() != null
                                && "application/json".equals(delivery.properties().contentType())
                                && delivery.payload().length == 1
                                && delivery.payload()[0] == 9) {
                            latch.countDown();
                        }
                    }
                });
                try (MqttSession session = MqttSession.connect(base(broker.endpoint())
                        .clientId("stored")
                        .protocol(MqttProtocol.MQTT_5)
                        .cleanSession(false)
                        .store(store)
                        .build())) {
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                    assertEquals("stored", session.clientId());
                }
            }
        }
    }

    @Test
    void dropOldestMakesRoomForANewerPublish() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            broker.holdAcks = true;
            MqttConfig config = base(broker.endpoint())
                    .clientId("overflow")
                    .maxInflight(1)
                    .maxQueued(1)
                    .overflow(MqttOverflow.DROP_OLDEST)
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                List<CompletableFuture<Void>> published = new ArrayList<>();
                published.add(session.publish("held", new byte[]{1}, MqttQos.AT_LEAST_ONCE, false));
                Thread.sleep(50);
                for (int i = 2; i <= 6; i++) {
                    published.add(session.publish("held", new byte[]{(byte) i}, MqttQos.AT_LEAST_ONCE, false));
                }
                long dropped = published.stream().filter(CompletableFuture::isCompletedExceptionally).count();
                assertTrue(dropped >= 1);
                assertFalse(published.get(published.size() - 1).isCompletedExceptionally());
            }
        }
    }

    @Test
    void qos1PublishCompletesAfterTheBrokerDropsTheSocket() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            broker.holdAcks = true;
            MqttConfig config = base(broker.endpoint())
                    .clientId("qos1-drop")
                    .cleanSession(false)
                    .reconnect(new ReconnectPolicy(30, 30, 1.0, 6))
                    .build();
            try (MqttSession publisher = MqttSession.connect(config);
                 MqttSession listener = MqttSession.connect(base(broker.endpoint()).clientId("qos1-hear").build())) {
                CountDownLatch heard = new CountDownLatch(1);
                listener.subscribe("room", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    if (payload.length == 1 && payload[0] == 9) {
                        heard.countDown();
                    }
                });
                CompletableFuture<Void> sent = publisher.publish("room", new byte[]{9}, MqttQos.AT_LEAST_ONCE, false);
                publisher.publish("drop", new byte[]{0}, MqttQos.AT_MOST_ONCE, false);
                long down = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (publisher.connected() && System.nanoTime() < down) {
                    Thread.sleep(10);
                }
                assertFalse(publisher.connected());
                broker.holdAcks = false;
                sent.get(4, TimeUnit.SECONDS);
                assertTrue(heard.await(3, TimeUnit.SECONDS));
                assertEquals(MqttLinkState.OPEN, publisher.link().state());
                assertEquals(0, publisher.link().attempt());
            }
        }
    }

    @Test
    void publishWhileWaitingIsDeliveredAfterReconnect() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint())
                    .clientId("queued-down")
                    .cleanSession(false)
                    .reconnect(new ReconnectPolicy(400, 400, 1.0, 4))
                    .build();
            try (MqttSession publisher = MqttSession.connect(config);
                 MqttSession listener = MqttSession.connect(base(broker.endpoint()).clientId("queued-hear").build())) {
                CountDownLatch heard = new CountDownLatch(1);
                listener.subscribe("room", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    if (payload.length == 1 && payload[0] == 4) {
                        heard.countDown();
                    }
                });
                publisher.publish("drop", new byte[]{0}, MqttQos.AT_MOST_ONCE, false);
                long down = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (publisher.link().state() != MqttLinkState.WAITING && System.nanoTime() < down) {
                    Thread.sleep(10);
                }
                assertEquals(MqttLinkState.WAITING, publisher.link().state());
                CompletableFuture<Void> sent = publisher.publish("room", new byte[]{4}, MqttQos.AT_LEAST_ONCE, false);
                Thread.sleep(100);
                assertEquals(MqttLinkState.WAITING, publisher.link().state());
                sent.get(3, TimeUnit.SECONDS);
                assertTrue(heard.await(3, TimeUnit.SECONDS));
                assertEquals(MqttLinkState.OPEN, publisher.link().state());
                assertEquals(0, publisher.link().attempt());
            }
        }
    }

    @Test
    void administrativeDisconnectIsRetried() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint())
                    .clientId("admin-boot")
                    .protocol(MqttProtocol.MQTT_5)
                    .cleanSession(false)
                    .reconnect(new ReconnectPolicy(40, 40, 1.0, 4))
                    .build();
            try (MqttSession session = MqttSession.connect(config);
                 MqttSession peer = MqttSession.connect(base(broker.endpoint())
                         .clientId("admin-peer")
                         .protocol(MqttProtocol.MQTT_5)
                         .build())) {
                CountDownLatch heard = new CountDownLatch(1);
                session.subscribe("room", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    if (payload.length == 1 && payload[0] == 3) {
                        heard.countDown();
                    }
                });
                session.publish("boot", new byte[]{0}, MqttQos.AT_MOST_ONCE, false);
                long down = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (session.connected() && System.nanoTime() < down) {
                    Thread.sleep(10);
                }
                assertFalse(session.connected());
                long back = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!session.connected() && System.nanoTime() < back) {
                    Thread.sleep(10);
                }
                assertEquals(MqttLinkState.OPEN, session.link().state());
                assertEquals(0, session.link().attempt());
                peer.publish("room", new byte[]{3}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
                assertTrue(heard.await(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void reconnectDelayIsBoundedAndJittered() {
        ReconnectPolicy policy = ReconnectPolicy.standard();
        assertEquals(200, policy.delayMillis(1));
        assertEquals(400, policy.delayMillis(2));
        assertEquals(10_000, policy.delayMillis(40));
        assertEquals(-1, ReconnectPolicy.none().delayMillis(1));
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < 20; i++) {
            long jittered = policy.jitteredDelayMillis(3, random::nextDouble);
            assertTrue(jittered >= 400 && jittered <= 800);
        }
    }

    @Test
    void publishesDoNotShortenTheReconnectWait() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint())
                    .clientId("steady")
                    .reconnect(new ReconnectPolicy(1_500, 1_500, 1.0, 4))
                    .build();
            try (MqttSession session = MqttSession.connect(config)) {
                assertEquals(MqttLinkState.OPEN, session.link().state());
                session.publish("drop", new byte[]{0}, MqttQos.AT_MOST_ONCE, false);
                long down = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (session.link().state() != MqttLinkState.WAITING && System.nanoTime() < down) {
                    Thread.sleep(10);
                }
                assertEquals(MqttLinkState.WAITING, session.link().state());
                assertTrue(session.link().retryInMillis() >= 750);
                for (int i = 0; i < 30; i++) {
                    session.publish("room", new byte[]{1}, MqttQos.AT_MOST_ONCE, false);
                }
                Thread.sleep(200);
                assertEquals(MqttLinkState.WAITING, session.link().state());
                session.reconnect();
                long back = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!session.connected() && System.nanoTime() < back) {
                    Thread.sleep(10);
                }
                assertTrue(session.connected());
                assertEquals(MqttLinkState.OPEN, session.link().state());
                assertEquals(0, session.link().attempt());
            }
        }
    }

    private static int indexOf(byte[] data, byte[] needle) {
        for (int i = 0; i + needle.length <= data.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static final class MemoryStore implements MqttStore {
        volatile MqttSessionImage image = MqttSessionImage.EMPTY;

        @Override
        public MqttSessionImage load() {
            return image;
        }

        @Override
        public void save(MqttSessionImage image) {
            this.image = image;
        }

        @Override
        public void discard() {
            image = MqttSessionImage.EMPTY;
        }
    }

    private static MqttConfig.Builder base(String endpoint) {
        return MqttConfig.builder(endpoint)
                .clientId("extended")
                .keepAliveSeconds(30)
                .connectTimeoutMillis(2000)
                .reconnect(ReconnectPolicy.none());
    }
}
