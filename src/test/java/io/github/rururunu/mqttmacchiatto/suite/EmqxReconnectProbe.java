package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttConfig;
import io.github.rururunu.mqttmacchiatto.MqttEvents;
import io.github.rururunu.mqttmacchiatto.MqttLinkState;
import io.github.rururunu.mqttmacchiatto.MqttProtocol;
import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import io.github.rururunu.mqttmacchiatto.ReconnectPolicy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drops a live EMQX session with {@code emqx ctl clients kick} and checks the client comes back.
 * Run the class main. Not a supported API.
 */
public final class EmqxReconnectProbe {
    static final String ENDPOINT = "tcp://127.0.0.1:1883";

    public static void main(String[] args) throws Exception {
        List<String> lines = new ArrayList<>();
        long stamp = System.currentTimeMillis();
        check(lines, "mqtt311-kicks", () -> mqtt311Kicks(stamp));
        check(lines, "mqtt311-queued", () -> queuedWhileDown(stamp));
        check(lines, "mqtt311-inflight", () -> inflightSurvivesKick(stamp));
        check(lines, "mqtt311-forced", () -> forcedReconnect(stamp));
        check(lines, "mqtt5-kick", () -> mqtt5Kick(stamp));
        int failed = 0;
        for (String line : lines) {
            System.out.println(line);
            if (line.startsWith("FAIL")) {
                failed++;
            }
        }
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String mqtt311Kicks(long stamp) throws Exception {
        String id = "mac-re-" + stamp;
        String peerId = "mac-re-peer-" + stamp;
        String inbound = "bench/re/" + stamp + "/in";
        String outbound = "bench/re/" + stamp + "/out";
        AtomicInteger acks = new AtomicInteger();
        try (MqttSession session = connect(id, MqttProtocol.MQTT_3_1_1, new ReconnectPolicy(80, 80, 1.0, 20), acks);
             MqttSession peer = connect(peerId, MqttProtocol.MQTT_3_1_1, ReconnectPolicy.none(), null)) {
            AtomicInteger got = new AtomicInteger();
            session.subscribe(inbound, MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> got.incrementAndGet());
            AtomicInteger echoed = new AtomicInteger();
            peer.subscribe(outbound, MqttQos.AT_LEAST_ONCE, (topic, payload, qos, retained, duplicate) -> echoed.incrementAndGet());
            Thread.sleep(150);
            StringBuilder detail = new StringBuilder();
            for (int cycle = 1; cycle <= 5; cycle++) {
                int before = got.get();
                peer.publish(inbound, new byte[]{(byte) cycle}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
                if (!waitCount(got, before + 1, 3)) {
                    return fail("peer publish " + cycle + " was not delivered before the kick");
                }
                long started = System.nanoTime();
                int seen = acks.get();
                String kick = kick(id);
                if (!waitCount(acks, seen + 1, 5)) {
                    return fail("cycle " + cycle + " did not CONNACK again (" + kick + ") link " + session.link());
                }
                Thread.sleep(100);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                boolean present = session.connection() != null && session.connection().sessionPresent();
                int heard = echoed.get();
                session.publish(outbound, new byte[]{(byte) cycle}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
                if (!waitCount(echoed, heard + 1, 3)) {
                    return fail("cycle " + cycle + " subscription publish was lost after reconnect");
                }
                if (session.link().state() != MqttLinkState.OPEN || session.link().attempt() != 0) {
                    return fail("cycle " + cycle + " link " + session.link());
                }
                detail.append(" c").append(cycle).append("=").append(millis).append("ms present=").append(present);
            }
            return "5/5 back to OPEN" + detail;
        }
    }

    private static String queuedWhileDown(long stamp) throws Exception {
        String id = "mac-re-q-" + stamp;
        String peerId = "mac-re-qpeer-" + stamp;
        String topic = "bench/re/" + stamp + "/queued";
        try (MqttSession session = connect(id, MqttProtocol.MQTT_3_1_1, new ReconnectPolicy(800, 800, 1.0, 4), null);
             MqttSession peer = connect(peerId, MqttProtocol.MQTT_3_1_1, ReconnectPolicy.none(), null)) {
            CountDownLatch heard = new CountDownLatch(1);
            peer.subscribe(topic, MqttQos.AT_LEAST_ONCE, (name, payload, qos, retained, duplicate) -> {
                if (payload.length == 1 && payload[0] == 4) {
                    heard.countDown();
                }
            });
            Thread.sleep(100);
            String kick = kick(id);
            if (!waitLink(session, MqttLinkState.WAITING, 3)) {
                return fail("did not enter WAITING (" + kick + ") link " + session.link());
            }
            CompletableFuture<Void> sent = session.publish(topic, new byte[]{4}, MqttQos.AT_LEAST_ONCE, false);
            Thread.sleep(150);
            if (session.link().state() != MqttLinkState.WAITING) {
                return fail("publish woke the wait early: " + session.link());
            }
            sent.get(4, TimeUnit.SECONDS);
            if (!heard.await(3, TimeUnit.SECONDS)) {
                return fail("queued QoS 1 was not delivered");
            }
            if (session.link().state() != MqttLinkState.OPEN || session.link().attempt() != 0) {
                return fail("link after delivery " + session.link());
            }
            return "QoS 1 queued in WAITING delivered, attempt 0";
        }
    }

    private static String inflightSurvivesKick(long stamp) throws Exception {
        String id = "mac-re-inf-" + stamp;
        String peerId = "mac-re-infpeer-" + stamp;
        String topic = "bench/re/" + stamp + "/inflight";
        int count = 20;
        try (MqttSession session = connect(id, MqttProtocol.MQTT_3_1_1, new ReconnectPolicy(80, 80, 1.0, 8), null);
             MqttSession peer = connect(peerId, MqttProtocol.MQTT_3_1_1, ReconnectPolicy.none(), null)) {
            AtomicInteger heard = new AtomicInteger();
            peer.subscribe(topic, MqttQos.AT_LEAST_ONCE, (name, payload, qos, retained, duplicate) -> heard.incrementAndGet());
            Thread.sleep(100);
            CompletableFuture<?>[] sent = new CompletableFuture<?>[count];
            byte[] payload = new byte[]{7};
            for (int i = 0; i < count; i++) {
                sent[i] = session.publish(topic, payload, MqttQos.AT_LEAST_ONCE, false);
            }
            String kick = kick(id);
            CompletableFuture.allOf(sent).get(6, TimeUnit.SECONDS);
            if (!waitCount(heard, count, 3)) {
                return fail("peer saw " + heard.get() + " of " + count + " after kick (" + kick + ") link " + session.link());
            }
            long back = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while ((session.link().state() != MqttLinkState.OPEN || session.link().attempt() != 0)
                    && System.nanoTime() < back) {
                if (session.link().state() == MqttLinkState.FAILED) {
                    return fail("link " + session.link());
                }
                Thread.sleep(20);
            }
            if (session.link().state() != MqttLinkState.OPEN || session.link().attempt() != 0) {
                return fail("link " + session.link());
            }
            return "20/20 QoS 1 futures completed, peer saw " + heard.get();
        }
    }

    private static String forcedReconnect(long stamp) throws Exception {
        String id = "mac-re-force-" + stamp;
        String peerId = "mac-re-forcepeer-" + stamp;
        String topic = "bench/re/" + stamp + "/force";
        AtomicInteger acks = new AtomicInteger();
        try (MqttSession session = connect(id, MqttProtocol.MQTT_3_1_1, new ReconnectPolicy(80, 80, 1.0, 6), acks);
             MqttSession peer = connect(peerId, MqttProtocol.MQTT_3_1_1, ReconnectPolicy.none(), null)) {
            AtomicInteger heard = new AtomicInteger();
            session.subscribe(topic, MqttQos.AT_LEAST_ONCE, (name, payload, qos, retained, duplicate) -> heard.incrementAndGet());
            Thread.sleep(100);
            int seen = acks.get();
            session.reconnect();
            if (!waitCount(acks, seen + 1, 4) || !session.connected()) {
                return fail("forced reconnect did not return: " + session.link());
            }
            peer.publish(topic, new byte[]{1}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
            if (!waitCount(heard, 1, 3)) {
                return fail("subscription silent after forced reconnect, link " + session.link());
            }
            if (session.link().state() != MqttLinkState.OPEN || session.link().attempt() != 0) {
                return fail("link " + session.link());
            }
            return "forced reconnect delivered the next publish";
        }
    }

    private static String mqtt5Kick(long stamp) throws Exception {
        String id = "mac-re-v5-" + stamp;
        String peerId = "mac-re-v5peer-" + stamp;
        String topic = "bench/re/" + stamp + "/v5";
        AtomicInteger acks = new AtomicInteger();
        try (MqttSession session = connect(id, MqttProtocol.MQTT_5, new ReconnectPolicy(80, 80, 1.0, 6), acks);
             MqttSession peer = connect(peerId, MqttProtocol.MQTT_5, ReconnectPolicy.none(), null)) {
            AtomicInteger heard = new AtomicInteger();
            session.subscribe(topic, MqttQos.AT_LEAST_ONCE, (name, payload, qos, retained, duplicate) -> heard.incrementAndGet());
            Thread.sleep(100);
            int seen = acks.get();
            String kick = kick(id);
            if (!waitCount(acks, seen + 1, 5) || !session.connected()) {
                return fail("did not return (" + kick + ") link " + session.link());
            }
            peer.publish(topic, new byte[]{5}, MqttQos.AT_LEAST_ONCE, false).get(3, TimeUnit.SECONDS);
            if (!waitCount(heard, 1, 3)) {
                return fail("MQTT 5 subscription silent after kick, link " + session.link());
            }
            boolean present = session.connection() != null && session.connection().sessionPresent();
            return "back to OPEN present=" + present + " attempt=" + session.link().attempt();
        }
    }

    private static MqttSession connect(String clientId, MqttProtocol protocol, ReconnectPolicy policy, AtomicInteger acks) {
        MqttConfig.Builder builder = MqttConfig.builder(ENDPOINT)
                .clientId(clientId)
                .protocol(protocol)
                .cleanSession(false)
                .keepAliveSeconds(20)
                .connectTimeoutMillis(3_000)
                .reconnect(policy);
        if (acks != null) {
            builder.events(new MqttEvents() {
                @Override
                public void connAck(io.github.rururunu.mqttmacchiatto.MqttConnAck ack) {
                    acks.incrementAndGet();
                }
            });
        }
        return MqttSession.connect(builder.build());
    }

    private static String kick(String clientId) throws Exception {
        Process process = new ProcessBuilder("docker", "exec", "macchiatto-emqx", "emqx", "ctl", "clients", "kick", clientId)
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        String text = new String(output, StandardCharsets.UTF_8).trim();
        if (code != 0) {
            return "exit " + code + " " + text;
        }
        return text.isEmpty() ? "ok" : text;
    }

    private static boolean waitLink(MqttSession session, MqttLinkState state, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (session.link().state() != state && System.nanoTime() < deadline) {
            if (session.link().state() == MqttLinkState.FAILED) {
                return false;
            }
            Thread.sleep(20);
        }
        return session.link().state() == state;
    }

    private static boolean waitCount(AtomicInteger count, int target, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (count.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return count.get() >= target;
    }

    private static String fail(String detail) {
        return "FAIL " + detail;
    }

    private static void check(List<String> lines, String name, Callable<String> body) {
        try {
            String detail = body.call();
            if (detail.startsWith("FAIL ")) {
                lines.add("FAIL " + name + " " + detail.substring(5));
            } else {
                lines.add("PASS " + name + " " + detail);
            }
        } catch (Throwable failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            lines.add("FAIL " + name + " " + failure.getClass().getSimpleName() + ": " + message);
        }
    }
}
