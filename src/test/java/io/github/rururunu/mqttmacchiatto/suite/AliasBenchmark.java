package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttProtocol;
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

/**
 * QoS 0 publishes of a 64-byte payload on a long topic. MQTT 5 reuses a topic alias
 * after the first publish on that connection. Paho 1.2.5 has no MQTT 5 client, so its
 * column always sends the topic. Nobody subscribes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class AliasBenchmark {
    static final int BATCH = 200;
    static final String TOPIC = "plant/area/" + "segment/".repeat(24);

    private TestBroker broker;
    private MqttSession mqtt3;
    private MqttSession mqtt5;
    private MqttAsyncClient paho;
    private byte[] topic;
    private byte[] payload;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        broker = new TestBroker();
        topic = TOPIC.getBytes(StandardCharsets.UTF_8);
        payload = new byte[64];
        String endpoint = broker.endpoint();
        mqtt3 = BenchClients.mac(endpoint, "mac-alias3", 64, BATCH + 16);
        mqtt5 = BenchClients.mac(endpoint, "mac-alias5", 64, BATCH + 16, MqttProtocol.MQTT_5, null);
        paho = BenchClients.paho(endpoint, "paho-alias", 64, new MemoryPersistence());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        BenchClients.close(mqtt3);
        BenchClients.close(mqtt5);
        BenchClients.close(paho);
        BenchClients.close(broker);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void macchiattoMqtt3() {
        publish(mqtt3);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void macchiattoMqtt5() {
        publish(mqtt5);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void paho() throws Exception {
        var last = paho.publish(TOPIC, payload, 0, false);
        for (int i = 1; i < BATCH; i++) {
            last = paho.publish(TOPIC, payload, 0, false);
        }
        last.waitForCompletion();
    }

    private void publish(MqttSession session) {
        int last = BATCH - 1;
        for (int i = 0; i < last; i++) {
            session.emit(topic, payload);
        }
        session.publish(topic, payload, MqttQos.AT_MOST_ONCE, false).join();
    }
}
