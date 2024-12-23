package io.github.rururunu.mqttmacchiatto.tool;

import io.github.rururunu.mqttmacchiatto.config.MQTTBase;
import io.github.rururunu.mqttmacchiatto.content.MQTTQos;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.UUID;

/**
 * <h3>MQTT monitor class</h3>
 * <p>
 * Used for creating listeners and reconnecting MQTT
 * <h3>MQTT 监听器</h3>
 * <p>
 * 用于MQTT创建监听器 和 重连
 * <pre>{@code
 *         MQTTMonitor mqttMonitor = new MQTTMonitor();
 *         mqttMonitor.setClientId("clientId");
 *         mqttMonitor.setCleanSession(false);
 *         mqttMonitor.setQos(MQTTQos.EXACTLY_ONCE);
 *         mqttMonitor.setMqttCallback(new MqttCallback() {
 *         });
 *         // 开启订阅 Activate subscription
 *         mqttMonitor.start("topic");
 * }</pre>
 *
 * @author rururunu
 * @version 1.0
 * @since 1.0
 */
@EnableConfigurationProperties({MQTTBase.class})
public class MQTTMonitor {

    private String clientId = UUID.randomUUID().toString();
    private String topic;
    private MQTTQos qos = MQTTQos.AT_MOST_ONCE;
    private MqttClient client;
    private MqttConnectOptions options;
    private MqttCallback mqttCallback;
    private boolean cleanSession = false;

    /**
     * <h3>Enable monitoring MQTT</h3>
     * <p>
     * If there is a disconnection, it will automatically reconnect
     * <h3>开启监听MQTT</h3>
     * <p>
     * 若其中发生断线自动重连
     *
     * @param topicStr subscribe topic 订阅主题
     */
    public void start(String topicStr) {
        try {
            this.topic = topicStr;
            client = new MqttClient(MQTTBase.HOST, getClientId(), new MemoryPersistence());
            options = new MqttConnectOptions();
            options.setCleanSession(false);
            options.setUserName(MQTTBase.USER_NAME);
            options.setPassword(MQTTBase.PASSWORD.toCharArray());
            options.setConnectionTimeout(MQTTBase.TIMEOUT);
            options.setKeepAliveInterval(MQTTBase.KEEP_ALIVE);
            client.setCallback(mqttCallback);
            client.connect(options);
            int[] qos = {getQos().getValue()};
            String[] topic1 = {this.topic};
            client.subscribe(topic1, qos);
        } catch (MqttException e) {
            reconnect();
            throw new RuntimeException("MQTT connection exception", e);
        } catch (Exception e) {
            reconnect();
            throw new RuntimeException("MQTT encountered other exceptions while connecting", e);
        }
    }

    public void reconnect() {
        System.out.println("Preparing to reconnect to MQTT[{" + clientId + "}]<{" + topic + "}>");
        if (client.isConnected()) {
            System.out.println("MQTT[{" + clientId + "}]<{" + topic + "}>Connection is normal");
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                long sleepTime = System.currentTimeMillis();
                while (!client.isConnected()) {
                    if (System.currentTimeMillis() - sleepTime > MQTTBase.RECONNECT_FREQUENCY_MS) {
                        try {
                            client.connect();
                            client.subscribe(topic);
                            start(topic);
                        } catch (Exception e) {
                            throw new RuntimeException("Reconnect MQTT[{" + clientId + "}]<{" + topic + "}> Exception", e);
                        }
                        sleepTime = System.currentTimeMillis();
                    }
                }
                System.out.println("MQTT[{" + clientId + "}]<{" + topic + "}> Reconnect successfully");
            } catch (Exception e) {
                throw new RuntimeException("Reconnect MQTT[{" + clientId + "}]<{" + topic + "}> Exception", e);
            }
        });
        thread.start();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public MqttConnectOptions getOptions() {
        return options;
    }

    public void setOptions(MqttConnectOptions options) {
        this.options = options;
    }

    public MqttClient getClient() {
        return client;
    }

    public void setClient(MqttClient client) {
        this.client = client;
    }


    public MQTTQos getQos() {
        return qos;
    }

    public void setQos(MQTTQos qos) {
        this.qos = qos;
    }

    public MqttCallback getMqttCallback() {
        return mqttCallback;
    }

    public void setMqttCallback(MqttCallback mqttCallback) {
        this.mqttCallback = mqttCallback;
    }

    public boolean isCleanSession() {
        return cleanSession;
    }

    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }
}
