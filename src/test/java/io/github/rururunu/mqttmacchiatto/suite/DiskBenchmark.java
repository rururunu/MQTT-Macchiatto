package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttProtocol;
import io.github.rururunu.mqttmacchiatto.MqttQos;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * QoS 1 publishes of a 64-byte payload. The disk columns force every unacknowledged
 * batch to disk before it is written to the socket. Memory columns are the same
 * publish with no store. Both inflight windows are 64. Nobody subscribes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class DiskBenchmark {
    static final int BATCH = 32;
    static final int INFLIGHT = 64;

    private TestBroker broker;
    private MqttSession memory;
    private MqttSession disk;
    private MqttAsyncClient pahoMemory;
    private MqttAsyncClient pahoDisk;
    private byte[] topic;
    private byte[] payload;
    private String topicText;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        broker = new TestBroker();
        topicText = "bench/disk";
        topic = topicText.getBytes(StandardCharsets.UTF_8);
        payload = new byte[64];
        Path root = Path.of("target", "jmh-store");
        Path macDir = root.resolve("mac");
        Path pahoDir = root.resolve("paho");
        Files.createDirectories(macDir);
        Files.createDirectories(pahoDir);
        String endpoint = broker.endpoint();
        memory = BenchClients.mac(endpoint, "mac-mem", INFLIGHT, BATCH + 16);
        disk = BenchClients.mac(endpoint, "mac-disk", INFLIGHT, BATCH + 16, MqttProtocol.MQTT_3_1_1, macDir);
        pahoMemory = BenchClients.paho(endpoint, "paho-mem", INFLIGHT, new MemoryPersistence());
        pahoDisk = BenchClients.paho(endpoint, "paho-disk", INFLIGHT, new MqttDefaultFilePersistence(pahoDir.toString()));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        BenchClients.close(memory);
        BenchClients.close(disk);
        BenchClients.close(pahoMemory);
        BenchClients.close(pahoDisk);
        BenchClients.close(broker);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void macchiattoMemory() {
        publish(memory);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void macchiattoDisk() {
        publish(disk);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void pahoMemory() throws Exception {
        publish(pahoMemory);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void pahoDisk() throws Exception {
        publish(pahoDisk);
    }

    private void publish(MqttSession session) {
        CompletableFuture<Void> last = null;
        for (int i = 0; i < BATCH; i++) {
            last = session.publish(topic, payload, MqttQos.AT_LEAST_ONCE, false);
        }
        last.join();
    }

    private void publish(MqttAsyncClient client) throws Exception {
        var last = client.publish(topicText, payload, 1, false);
        for (int i = 1; i < BATCH; i++) {
            last = client.publish(topicText, payload, 1, false);
        }
        last.waitForCompletion();
    }
}
