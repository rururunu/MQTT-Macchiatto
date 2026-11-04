package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttConfig;
import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import io.github.rururunu.mqttmacchiatto.ReconnectPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class SessionTest {
    @Test
    void publishesAndReceivesQos0Qos1AndQos2() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint()).build();
            try (MqttSession session = MqttSession.connect(config)) {
                AtomicInteger qosSeen = new AtomicInteger();
                CountDownLatch latch = new CountDownLatch(3);
                session.subscribe("sensors/#", MqttQos.EXACTLY_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    qosSeen.addAndGet(qos.wire());
                    latch.countDown();
                });
                byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
                session.publish("sensors/a", payload, MqttQos.AT_MOST_ONCE, false).join();
                session.publish("sensors/b", payload, MqttQos.AT_LEAST_ONCE, false).join();
                session.publish("sensors/c", payload, MqttQos.EXACTLY_ONCE, false).join();
                assertTrue(latch.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pipelinedEmitsAreAllDelivered() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            try (MqttSession session = MqttSession.connect(base(broker.endpoint()).maxQueued(4096).build())) {
                int count = 1000;
                CountDownLatch latch = new CountDownLatch(count);
                session.subscribe("t", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> latch.countDown());
                byte[] topic = "t".getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[]{1, 2, 3, 4};
                for (int i = 0; i < count; i++) {
                    session.emit(topic, payload);
                }
                assertTrue(latch.await(5, TimeUnit.SECONDS), "delivered " + (count - latch.getCount()));
            }
        }
    }

    @Test
    void reconnectRestoresTheSubscription() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            MqttConfig config = base(broker.endpoint())
                    .reconnect(new ReconnectPolicy(40, 40, 1.0, 5))
                    .build();
            try (MqttSession session = MqttSession.connect(config);
                 MqttSession other = MqttSession.connect(base(broker.endpoint()).clientId("other").build())) {
                CountDownLatch first = new CountDownLatch(1);
                CountDownLatch second = new CountDownLatch(1);
                session.subscribe("room", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    if (payload.length > 0 && payload[0] == 1) {
                        first.countDown();
                    } else if (payload.length > 0 && payload[0] == 2) {
                        second.countDown();
                    }
                });
                other.publish("room", new byte[]{1}, MqttQos.AT_MOST_ONCE, false).join();
                assertTrue(first.await(3, TimeUnit.SECONDS));
                session.publish("drop", new byte[]{0}, MqttQos.AT_MOST_ONCE, false);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (session.connected() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                while (!session.connected() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(session.connected());
                Thread.sleep(100);
                other.publish("room", new byte[]{2}, MqttQos.AT_MOST_ONCE, false).join();
                assertTrue(second.await(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void twoSubscribersBothReceiveALiteralAndAWildcard() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            try (MqttSession left = MqttSession.connect(base(broker.endpoint()).clientId("left").build());
                 MqttSession right = MqttSession.connect(base(broker.endpoint()).clientId("right").build())) {
                CountDownLatch latch = new CountDownLatch(2);
                left.subscribe("a/+", MqttQos.AT_MOST_ONCE, (topic, payload, qos, retained, duplicate) -> {
                    assertEquals("a/b", topic);
                    assertArrayEquals(new byte[]{9}, payload);
                    latch.countDown();
                });
                right.subscribe("a/#", MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> latch.countDown());
                left.publish("a/b", new byte[]{9}, MqttQos.AT_LEAST_ONCE, false).join();
                assertTrue(latch.await(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void shortSubscribeAndPublishUseTheTopicAndTheText() throws Exception {
        try (TestBroker broker = new TestBroker()) {
            try (MqttSession session = MqttSession.connect(broker.endpoint(), "short", true)) {
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<byte[]> body = new AtomicReference<>();
                session.subscribe("sensors/#", (topic, payload) -> {
                    assertEquals("sensors/temp", topic);
                    body.set(payload);
                    latch.countDown();
                });
                session.publish("sensors/temp", "21.5", MqttQos.AT_LEAST_ONCE).join();
                assertTrue(latch.await(3, TimeUnit.SECONDS));
                assertArrayEquals("21.5".getBytes(StandardCharsets.UTF_8), body.get());
            }
        }
    }

    private static MqttConfig.Builder base(String endpoint) {
        return MqttConfig.builder(endpoint)
                .clientId("session-test")
                .keepAliveSeconds(30)
                .connectTimeoutMillis(2000)
                .reconnect(ReconnectPolicy.none());
    }
}
