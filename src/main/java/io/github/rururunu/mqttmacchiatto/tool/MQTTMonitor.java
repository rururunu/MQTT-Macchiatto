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

    private String host = MQTTBase.HOST;
    private String username = MQTTBase.USER_NAME;
    private String password = MQTTBase.PASSWORD;
    private Integer timeout = MQTTBase.TIMEOUT;
    private Integer keepalive = MQTTBase.KEEP_ALIVE;
    private Integer reconnectFrequencyMs = MQTTBase.RECONNECT_FREQUENCY_MS;

    /**
     * 连接 host 地址
     * Connect to host address
     *
     * @param host host 地址 Host address
     * @return this
     */
    public MQTTMonitor host(String host) {
        this.host = host;
        return this;
    }

    /**
     * 用户名 username
     *
     * @param username 用户名
     * @return this
     */
    public MQTTMonitor username(String username) {
        this.username = username;
        return this;
    }

    /**
     * 密码 password
     *
     * @param password 密码
     * @return this
     */
    public MQTTMonitor password(String password) {
        this.password = password;
        return this;
    }

    /**
     * 超时时间 timeout
     *
     * @param timeout 超时时间
     * @return this
     */
    public MQTTMonitor timeout(Integer timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 心跳检测时间 keepalive
     *
     * @param keepalive 心跳检测时间
     * @return this
     */
    public MQTTMonitor keepalive(Integer keepalive) {
        this.keepalive = keepalive;
        return this;
    }

    /**
     * 重连间隔时间 reconnectFrequencyMs
     *
     * @param reconnectFrequencyMs 重连间隔时间
     * @return this
     */
    public MQTTMonitor reconnectFrequencyMs(Integer reconnectFrequencyMs) {
        this.reconnectFrequencyMs = reconnectFrequencyMs;
        return this;
    }

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
            client = new MqttClient(host, getClientId(), new MemoryPersistence());
            options = new MqttConnectOptions();
            options.setCleanSession(false);
            options.setUserName(username);
            options.setPassword(password.toCharArray());
            options.setConnectionTimeout(timeout);
            options.setKeepAliveInterval(keepalive);
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
                    if (System.currentTimeMillis() - sleepTime > reconnectFrequencyMs) {
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
