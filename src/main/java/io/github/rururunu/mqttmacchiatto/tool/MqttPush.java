package io.github.rururunu.mqttmacchiatto.tool;

import io.github.rururunu.mqttmacchiatto.config.MQTTBase;
import io.github.rururunu.mqttmacchiatto.content.MQTTQos;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * <h3>向 MQTT 推送消息</h3>
 * <h3>Push messages to MQTT</h3>
 * <pre>{@code
 *   MqttPush mqttPush = new MqttPush();
 *   mqttPush.start();
 *   mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE,
 *      (iMqttToken) -> System.out.println("success"),
 *      (iMqttToken, throwable) -> System.out.println("failure")
 *   );
 * }
 * </pre>
 * <pre>{@code
 *   MqttPush mqttPush = new MqttPush()
 *                 .host("tcp://127.0.0.1:1883")
 *                 .username("username")
 *                 .password("password")
 *                 .timeout(10000)
 *                 .keepalive(60)
 *                 .cleanSession(false)
 *                 .reconnectFrequencyMs(5000);
 *         mqttPush.start();
 *         mqttPush.foundTopic("test/");
 *         mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE,
 *                 (iMqttToken) -> System.out.println("success"),
 *                 (iMqttToken, throwable) -> System.out.println("failure")
 *         );
 * }
 * </pre>
 *
 * @author rururunu
 * @version 0.1.1
 * @since 0.1.1
 */
@EnableConfigurationProperties({MQTTBase.class})
public class MqttPush {

    private MqttClient client;
    private MqttConnectOptions options;
    private Map<String, MqttTopic> topicMap = new HashMap<>();

    private String host = MQTTBase.HOST;
    private String username = MQTTBase.USER_NAME;
    private String password = MQTTBase.PASSWORD;
    private Integer timeout = MQTTBase.TIMEOUT;
    private Integer keepalive = MQTTBase.KEEP_ALIVE;
    private Integer reconnectFrequencyMs = MQTTBase.RECONNECT_FREQUENCY_MS;
    private String serviceId = UUID.randomUUID().toString();
    private boolean cleanSession = false;

    /**
     * 连接 host 地址
     * Connect to host address
     *
     * @param host host 地址 Host address
     * @return this
     */
    public MqttPush host(String host) {
        this.host = host;
        return this;
    }

    /**
     * 用户名 username
     *
     * @param username 用户名
     * @return this
     */
    public MqttPush username(String username) {
        this.username = username;
        return this;
    }

    /**
     * 密码 password
     *
     * @param password 密码
     * @return this
     */
    public MqttPush password(String password) {
        this.password = password;
        return this;
    }

    /**
     * 超时时间 timeout
     *
     * @param timeout 超时时间
     * @return this
     */
    public MqttPush timeout(Integer timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 心跳检测时间 keepalive
     *
     * @param keepalive 心跳检测时间
     * @return this
     */
    public MqttPush keepalive(Integer keepalive) {
        this.keepalive = keepalive;
        return this;
    }

    /**
     * 重连间隔时间 reconnectFrequencyMs
     *
     * @param reconnectFrequencyMs 重连间隔时间
     * @return this
     */
    public MqttPush reconnectFrequencyMs(Integer reconnectFrequencyMs) {
        this.reconnectFrequencyMs = reconnectFrequencyMs;
        return this;
    }

    /**
     * 服务id serviceId
     *
     * @param serviceId 服务id
     * @return this
     */
    public MqttPush serviceId(String serviceId) {
        this.serviceId = serviceId;
        return this;
    }

    /**
     * 清除会话 cleanSession
     *
     * @param cleanSession 清除会话
     * @return this
     */
    public MqttPush cleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
        return this;
    }

    /**
     * 将配置初始化并开启连接 Initialize the configuration and open the connection
     *
     * @throws MqttException MqttException
     */
    public void start() throws MqttException {
        client = new MqttClient(host, serviceId, new MemoryPersistence());
        options = new MqttConnectOptions();
        options.setCleanSession(cleanSession);
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setConnectionTimeout(timeout);
        options.setKeepAliveInterval(keepalive);
        client.connect(options);
    }

    /**
     * 设立主题 Set the topic
     * <br></br>
     * 将主题寄存
     *
     * @param topic 主题 Topic
     */
    public void foundTopic(String topic) {
        topicMap.put(topic, client.getTopic(topic));
    }

    /**
     * 消息推送 Push message
     *
     * @param topic   主题
     * @param message 消息
     * @param qos     等级
     * @throws MqttException MqttException
     */
    public void push(String topic, String message, MQTTQos qos) throws MqttException {
        if (!topicMap.containsKey(topic)) {
            topicMap.put(topic, client.getTopic(topic));
        }
        MqttMessage mqttMessage = new MqttMessage(message.getBytes());
        mqttMessage.setQos(qos.getValue());
        IMqttDeliveryToken token = topicMap.get(topic).publish(mqttMessage);
        token.waitForCompletion();
    }

    /**
     * 消息推送 Push message
     *
     * @param topic   主题
     * @param message 消息
     * @param qos     等级
     * @param success success 成功回调
     * @param failure failure 失败回调
     * @throws MqttException MqttException
     */
    public void push(
            String topic,
            String message,
            MQTTQos qos,
            Consumer<IMqttToken> success,
            BiConsumer<IMqttToken, Throwable> failure
    ) throws MqttException {
        if (!topicMap.containsKey(topic)) {
            topicMap.put(topic, client.getTopic(topic));
        }
        MqttMessage mqttMessage = new MqttMessage(message.getBytes());
        mqttMessage.setQos(qos.getValue());
        IMqttDeliveryToken token = topicMap.get(topic).publish(mqttMessage);
        token.setActionCallback(new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken iMqttToken) {
                success.accept(iMqttToken);
            }

            @Override
            public void onFailure(IMqttToken iMqttToken, Throwable throwable) {
                failure.accept(iMqttToken, throwable);
            }
        });
        token.waitForCompletion();
    }

    /**
     * 关闭推送监听 Turn off push monitoring
     *
     * @throws MqttException MqttException
     */
    public void stop() throws MqttException {
        client.disconnect();
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public Integer getKeepalive() {
        return keepalive;
    }

    public void setKeepalive(Integer keepalive) {
        this.keepalive = keepalive;
    }

    public Integer getReconnectFrequencyMs() {
        return reconnectFrequencyMs;
    }

    public void setReconnectFrequencyMs(Integer reconnectFrequencyMs) {
        this.reconnectFrequencyMs = reconnectFrequencyMs;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public MqttClient getClient() {
        return client;
    }

    public void setClient(MqttClient client) {
        this.client = client;
    }

    public MqttConnectOptions getOptions() {
        return options;
    }

    public void setOptions(MqttConnectOptions options) {
        this.options = options;
    }

    public boolean isCleanSession() {
        return cleanSession;
    }

    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }
}
