package io.github.rururunu.mqttmacchiatto.tool;

import io.github.rururunu.mqttmacchiatto.config.MQTTBase;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.UUID;

/**
 * <h3>MQTT reporting</h3>
 * <p>
 * Used to report messages to MQTT
 * <h3>MQTT 上报</h3>
 * <p>
 * 用于上报消息至MQTT
 *
 * <pre>{@code
 *             // 创建连接 Create connection
 *             MQTTReport mqttReport = new MQTTReport();
 *             mqttReport.setTopic("topic");
 *             mqttReport.setServiceId("serviceId");
 *             mqttReport.setCleanSession(false);
 *             mqttReport.start();
 *             // 发送消息 send message
 *             mqttReport.getMessage().setQos(MQTTQos.EXACTLY_ONCE.getValue());
 *             mqttReport.getMessage().setPayload("hello".getBytes());
 *             mqttReport.publish(mqttReport.getMqttTopic(), mqttReport.getMessage());
 * }</pre>
 *
 * <pre>{@code
 *             // 创建连接 Create connection
 *             MQTTReport mqttReport = new MQTTReport();
 *             mqttReport.setTopic("topic");
 *             mqttReport.setServiceId("serviceId");
 *             mqttReport.start();
 *             // 发送消息 Create connection
 *             MqttMessage message = new MqttMessage();
 *             message.setQos(MQTTQos.EXACTLY_ONCE.getValue());
 *             message.setPayload("hello".getBytes());
 *             mqttReport.publish("topic", message);
 * }</pre>
 *
 * @author rururunu
 * @version 1.0
 * @since 1.0
 */
@EnableConfigurationProperties({MQTTBase.class})
public class MQTTReport {

    private String serviceId = UUID.randomUUID().toString();
    private String topic;
    private MqttClient client;
    private MqttTopic mqttTopic;
    private MqttConnectOptions options;
    private MqttMessage message;
    private boolean cleanSession = false;

    /**
     * <h3>Create an MQTT report</h3>
     * Accessing client information through getClient
     * <br/>
     * <h3>创建一个MQTT上报</h3>
     * 通过 getClient 访问到 client信息
     *
     * @throws MqttException MQTT ERROR
     */
    public MQTTReport() throws MqttException {
        client = new MqttClient(MQTTBase.HOST, getServiceId(), new MemoryPersistence());
        options = new MqttConnectOptions();
        options.setCleanSession(isCleanSession());
        options.setUserName(MQTTBase.USER_NAME);
        options.setPassword(MQTTBase.PASSWORD.toCharArray());
        options.setConnectionTimeout(MQTTBase.TIMEOUT);
        options.setKeepAliveInterval(MQTTBase.KEEP_ALIVE);
        setMessage(new MqttMessage());
    }

    /**
     * <h3>Create an MQTT report</h3>
     * Accessing client information through getClient
     * <br/>
     * <h3>创建一个MQTT上报</h3>
     * 通过 getClient 访问到 client信息
     *
     * @param topic     Report Topic 上报主题
     * @param serviceId Service ID 服务ID
     * @throws MqttException MQTT ERROR
     */
    public MQTTReport(String topic, String serviceId) throws MqttException {
        setTopic(topic);
        setServiceId(serviceId);
        client = new MqttClient(MQTTBase.HOST, getServiceId(), new MemoryPersistence());
        options = new MqttConnectOptions();
        options.setCleanSession(isCleanSession());
        options.setUserName(MQTTBase.USER_NAME);
        options.setPassword(MQTTBase.PASSWORD.toCharArray());
        setMessage(new MqttMessage());
    }


    /**
     * <h3>Open connection</h3>
     * <br/>
     * <h3>开启连接</h3>
     */
    public void start() {
        try {
            client.connect(options);
            setMqttTopic(client.getTopic(getTopic()));
        } catch (MqttException e) {
            throw new RuntimeException("MQTT connection exception", e);
        }
    }

    /**
     * <h3>Publish a message</h3>
     * <br/>
     * <h3>发布消息</h3>
     *
     * @param topic   Topic for publishing messages 发布消息的主题
     * @param message message 消息
     */
    public void publish(MqttTopic topic, MqttMessage message) throws MqttException {
        MqttDeliveryToken token = topic.publish(message);
        token.waitForCompletion();
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
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

    public MqttTopic getMqttTopic() {
        return mqttTopic;
    }

    public void setMqttTopic(MqttTopic mqttTopic) {
        this.mqttTopic = mqttTopic;
    }

    public MqttMessage getMessage() {
        return message;
    }

    public void setMessage(MqttMessage message) {
        this.message = message;
    }

    public boolean isCleanSession() {
        return cleanSession;
    }

    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }
}
