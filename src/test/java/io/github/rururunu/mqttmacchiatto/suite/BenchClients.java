package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.MqttConfig;
import io.github.rururunu.mqttmacchiatto.MqttProtocol;
import io.github.rururunu.mqttmacchiatto.MqttSession;
import io.github.rururunu.mqttmacchiatto.ReconnectPolicy;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

import java.nio.file.Path;

/** Shared connect options for the JMH suites. Not a supported API. */
final class BenchClients {
    private BenchClients() {
    }

    static MqttSession mac(String endpoint, String clientId, int inflight, int queued) {
        return mac(endpoint, clientId, inflight, queued, MqttProtocol.MQTT_3_1_1, null);
    }

    static MqttSession mac(String endpoint,
                           String clientId,
                           int inflight,
                           int queued,
                           MqttProtocol protocol,
                           Path persistence) {
        MqttConfig.Builder builder = MqttConfig.builder(endpoint)
                .clientId(clientId)
                .keepAliveSeconds(0)
                .connectTimeoutMillis(5_000)
                .maxInflight(inflight)
                .maxQueued(queued)
                .protocol(protocol)
                .reconnect(ReconnectPolicy.none());
        if (persistence != null) {
            builder.persistence(persistence);
        }
        return MqttSession.connect(builder.build());
    }

    static MqttAsyncClient paho(String endpoint, String clientId, int inflight, MqttClientPersistence persistence)
            throws Exception {
        MqttAsyncClient client = new MqttAsyncClient(endpoint, clientId, persistence);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        options.setKeepAliveInterval(0);
        options.setMaxInflight(inflight);
        client.connect(options).waitForCompletion(5_000);
        return client;
    }

    static void close(MqttSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException ignored) {
        }
    }

    static void close(MqttAsyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.disconnect().waitForCompletion(2_000);
            client.close();
        } catch (Exception ignored) {
        }
    }

    static void close(TestBroker broker) {
        if (broker == null) {
            return;
        }
        try {
            broker.close();
        } catch (Exception ignored) {
        }
    }
}
