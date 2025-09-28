package io.github.rururunu.mqttmacchiatto.tool;

import io.github.rururunu.mqttmacchiatto.content.MQTTQos;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * <h3>接收来自MQTT的消息</h3>
 * <h3>Receive messages from MQTT</h3>
 * <pre>{@code
 *         MqttPut.of()
 *                 .setTopic("topic")
 *                 .setServiceId("serviceId")
 *                 .setCleanSession(true)
 *                 .response(System.out::println)
 *                 .start();
 * }</pre>
 * <pre>{@code
 *         MqttPut mqttPut = MqttPut.of("topic")
 *                 .response(System.out::println);
 *         mqttPut.start();
 *         mqttPut.stop();
 * }</pre>
 * <pre>{@code
 *         MqttPut.of("test/")
 *                 .host("tcp://127.0.0.1:1883")
 *                 .username("username")
 *                 .password("password")
 *                 .timeout(10000)
 *                 .keepalive(60)
 *                 .cleanSession(false)
 *                 .reconnectFrequencyMs(5000)
 *                 .response((topic, msg) -> System.out.println(topic + ":" + msg))
 *                 .start();
 * }</pre>
 *
 * @author rururunu
 * @version 1.0
 * @since 1.0
 */
public class MqttPut {

    private final MQTTMonitor monitor = new MQTTMonitor();

    private String topic;

    public MqttPut() {
    }

    public MqttPut(String topic) {
        this.topic = topic;
    }

    public static MqttPut of() {
        return new MqttPut();
    }

    public static MqttPut of(String topic) {
        return new MqttPut(topic);
    }

    public MqttPut host(String host) {
        monitor.host(host);
        return this;
    }

    public MqttPut username(String username) {
        monitor.username(username);
        return this;
    }

    public MqttPut password(String password) {
        monitor.password(password);
        return this;
    }

    public MqttPut timeout(int timeout) {
        monitor.timeout(timeout);
        return this;
    }

    public MqttPut keepalive(int keepalive) {
        monitor.keepalive(keepalive);
        return this;
    }

    public MqttPut cleanSession(boolean cleanSession) {
        monitor.setCleanSession(cleanSession);
        return this;
    }

    public MqttPut reconnectFrequencyMs(int reconnectFrequencyMs) {
        monitor.reconnectFrequencyMs(reconnectFrequencyMs);
        return this;
    }

    public MqttPut qos(MQTTQos qos) {
        monitor.setQos(qos);
        return this;
    }

    public MqttPut clientId(String serviceId) {
        monitor.setClientId(serviceId);
        return this;
    }


    public MqttPut setTopic(String topic) {
        this.topic = topic;
        return this;
    }

    public MqttPut setServiceId(String serviceId) {
        monitor.setClientId(serviceId);
        return this;
    }

    public MqttPut setCleanSession(boolean cleanSession) {
        monitor.setCleanSession(cleanSession);
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param consumer Response after listening to the message 监听到消息后的响应
     *                 <br/>
     *                 consumer 的参数值是消息内容为String类型
     *                 <br/>
     *                 The parameter value of the consumer is that the message content is of type String
     * @return MqttPut
     */
    public MqttPut response(Consumer<String> consumer) {
        monitor.setClientId(String.valueOf(UUID.randomUUID()));
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected " + throwable);
                        monitor.reconnect();
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        consumer.accept(msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param consumer      Response after listening to the message 监听到消息后的响应
     *                      <br/>
     *                      consumer 的参数值是消息内容为String类型
     *                      <br/>
     *                      The parameter value of the consumer is that the message content is of type String
     * @param connectionLost   Operation after listening for exceptions 监听发生异常后的操作
     *                         <br/>
     *                         connectionLost 的参数值是消息内容是 Throwable 类型
     *                         <br/>
     *                         The parameter value of connectionLost is that the message content is of type Throwable
     * @return MqttPut
     */
    public MqttPut response(Consumer<String> consumer, Consumer<Throwable> connectionLost) {
        monitor.setClientId(String.valueOf(UUID.randomUUID()));
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected " + throwable);
                        monitor.reconnect();
                        connectionLost.accept(throwable);
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        consumer.accept(msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param consumer         Response after listening to the message 监听到消息后的响应
     *                         <br/>
     *                         consumer 的参数值是消息内容为String类型
     *                         <br/>
     *                         The parameter value of the consumer is that the message content is of type String
     * @param connectionLost   Operation after listening for exceptions 监听发生异常后的操作
     *                         <br/>
     *                         connectionLost 的参数值是消息内容是 Throwable 类型
     *                         <br/>
     *                         The parameter value of connectionLost is that the message content is of type Throwable
     * @param deliveryComplete Monitor whether a receipt is received from the server 监听是否到服务端的回执
     *                         <br/>
     *                         deliveryComplete 的参数值是消息内容是 deliveryComplete 类型
     *                         <br/>
     *                         The parameter value for deliveryComplete is that the message content is of type deliveryComplete
     * @return MqttPut
     */
    public MqttPut response(
            Consumer<String> consumer,
            Consumer<Throwable> connectionLost,
            Consumer<IMqttDeliveryToken> deliveryComplete
    ) {
        monitor.setClientId(String.valueOf(UUID.randomUUID()));
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected " + throwable);
                        monitor.reconnect();
                        connectionLost.accept(throwable);
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        consumer.accept(msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                        deliveryComplete.accept(iMqttDeliveryToken);
                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param biConsumer Response after listening to the message 监听到消息后的响应
     *                   <br/>
     *                   biConsumer param1 topic:string
     *                   <br/>
     *                   biConsumer param2 msg:string
     *                   <br/>
     *                   biConsumer 参数1 主题:string
     *                   <br/>
     *                   biConsumer 参数2 消息:string
     * @return MqttPut
     */
    public MqttPut response(BiConsumer<String, String> biConsumer) {
        monitor.setClientId(UUID.randomUUID().toString());
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        biConsumer.accept(topic, msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param biConsumer     Response after listening to the message 监听到消息后的响应
     *                       <br/>
     *                       biConsumer param1 topic:string
     *                       <br/>
     *                       biConsumer param2 msg:string
     *                       <br/>
     *                       biConsumer 参数1 主题:string
     *                       <br/>
     *                       biConsumer 参数2 消息:string
     * @param connectionLost Operation after listening for exceptions 监听发生异常后的操作
     *                       <br/>
     *                       connectionLost 的参数值是消息内容是 Throwable 类型
     *                       <br/>
     *                       The parameter value of connectionLost is that the message content is of type Throwable
     * @return MqttPut
     */
    public MqttPut response(BiConsumer<String, String> biConsumer, Consumer<Throwable> connectionLost) {
        monitor.setClientId(UUID.randomUUID().toString());
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                        connectionLost.accept(throwable);
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        biConsumer.accept(topic, msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param biConsumer       Response after listening to the message 监听到消息后的响应
     *                         <br/>
     *                         biConsumer param1 topic:string
     *                         <br/>
     *                         biConsumer param2 msg:string
     *                         <br/>
     *                         biConsumer 参数1 主题:string
     *                         <br/>
     *                         biConsumer 参数2 消息:string
     * @param connectionLost   Operation after listening for exceptions 监听发生异常后的操作
     *                         <br/>
     *                         connectionLost 的参数值是消息内容是 Throwable 类型
     *                         <br/>
     *                         The parameter value of connectionLost is that the message content is of type Throwable
     * @param deliveryComplete Monitor whether a receipt is received from the server 监听是否到服务端的回执
     *                         <br/>
     *                         deliveryComplete 的参数值是消息内容是 deliveryComplete 类型
     *                         <br/>
     *                         The parameter value for deliveryComplete is that the message content is of type deliveryComplete
     * @return MqttPut
     */
    public MqttPut response(
            BiConsumer<String, String> biConsumer,
            Consumer<Throwable> connectionLost,
            Consumer<IMqttDeliveryToken> deliveryComplete
    ) {
        monitor.setClientId(UUID.randomUUID().toString());
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                        connectionLost.accept(throwable);
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        biConsumer.accept(topic, msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                        deliveryComplete.accept(iMqttDeliveryToken);
                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param clientId Client ID
     * @param consumer Response after listening to the message 监听到消息后的响应
     *                 <br/>
     *                 consumer 的参数值是消息内容为String类型
     *                 <br/>
     *                 The parameter value of the consumer is that the message content is of type String
     * @return MqttPut
     */
    public MqttPut response(String clientId, Consumer<String> consumer) {
        monitor.setClientId(clientId);
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                    }

                    @Override
                    public void messageArrived(String s, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        consumer.accept(msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param clientId       Client ID
     * @param consumer       Response after listening to the message 监听到消息后的响应
     *                       <br/>
     *                       consumer 的参数值是消息内容为String类型
     *                       <br/>
     *                       The parameter value of the consumer is that the message content is of type String
     * @param connectionLost Operation after listening for exceptions 监听发生异常后的操作
     *                       <br/>
     *                       connectionLost 的参数值是消息内容是 Throwable 类型
     *                       <br/>
     *                       The parameter value of connectionLost is that the message content is of type Throwable
     * @return MqttPut
     */
    public MqttPut response(
            String clientId,
            Consumer<String> consumer,
            Consumer<Throwable> connectionLost
    ) {
        monitor.setClientId(clientId);
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                        connectionLost.accept(throwable);
                    }

                    @Override
                    public void messageArrived(String s, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        consumer.accept(msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param clientId         Client ID
     * @param consumer         Response after listening to the message 监听到消息后的响应
     *                         <br/>
     *                         consumer 的参数值是消息内容为String类型
     *                         <br/>
     *                         The parameter value of the consumer is that the message content is of type String
     * @param connectionLost   Operation after listening for exceptions 监听发生异常后的操作
     *                         <br/>
     *                         connectionLost 的参数值是消息内容是 Throwable 类型
     *                         <br/>
     *                         The parameter value of connectionLost is that the message content is of type Throwable
     * @param deliveryComplete Monitor whether a receipt is received from the server 监听是否到服务端的回执
     *                         <br/>
     *                         deliveryComplete 的参数值是消息内容是 deliveryComplete 类型
     *                         <br/>
     *                         The parameter value for deliveryComplete is that the message content is of type deliveryComplete
     * @return MqttPut
     */
    public MqttPut response(
            String clientId,
            Consumer<String> consumer,
            Consumer<Throwable> connectionLost,
            Consumer<IMqttDeliveryToken> deliveryComplete
    ) {
        monitor.setClientId(clientId);
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                        connectionLost.accept(throwable);
                    }

                    @Override
                    public void messageArrived(String s, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        consumer.accept(msg);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                        deliveryComplete.accept(iMqttDeliveryToken);
                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param clientId   Client ID
     * @param biConsumer Response after listening to the message 监听到消息后的响应
     *                   <br/>
     *                   biConsumer param1 topic:string
     *                   <br/>
     *                   biConsumer param2 msg:string
     *                   <br/>
     *                   biConsumer 参数1 主题:string
     *                   <br/>
     *                   biConsumer 参数2 消息:string
     * @return MqttPut
     */
    public MqttPut response(String clientId, BiConsumer<String, MqttMessage> biConsumer) {
        monitor.setClientId(clientId);
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                    }

                    @Override
                    public void messageArrived(String s, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        biConsumer.accept(msg, mqttMessage);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param clientId       Client ID
     * @param biConsumer     Response after listening to the message 监听到消息后的响应
     *                       <br/>
     *                       biConsumer param1 topic:string
     *                       <br/>
     *                       biConsumer param2 msg:string
     *                       <br/>
     *                       biConsumer 参数1 主题:string
     *                       <br/>
     *                       biConsumer 参数2 消息:string
     * @param connectionLost Operation after listening for exceptions 监听发生异常后的操作
     *                       <br/>
     *                       connectionLost 的参数值是消息内容是 Throwable 类型
     *                       <br/>
     *                       The parameter value of connectionLost is that the message content is of type Throwable
     * @return MqttPut
     */
    public MqttPut response(
            String clientId,
            BiConsumer<String, MqttMessage> biConsumer,
            Consumer<Throwable> connectionLost
    ) {
        monitor.setClientId(clientId);
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                        connectionLost.accept(throwable);
                    }

                    @Override
                    public void messageArrived(String s, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        biConsumer.accept(msg, mqttMessage);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                    }
                }
        );
        return this;
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param clientId         Client ID
     * @param biConsumer       Response after listening to the message 监听到消息后的响应
     *                         <br/>
     *                         biConsumer param1 topic:string
     *                         <br/>
     *                         biConsumer param2 msg:string
     *                         <br/>
     *                         biConsumer 参数1 主题:string
     *                         <br/>
     *                         biConsumer 参数2 消息:string
     * @param connectionLost   Operation after listening for exceptions 监听发生异常后的操作
     *                         <br/>
     *                         connectionLost 的参数值是消息内容是 Throwable 类型
     *                         <br/>
     *                         The parameter value of connectionLost is that the message content is of type Throwable
     * @param deliveryComplete Monitor whether a receipt is received from the server 监听是否到服务端的回执
     *                         <br/>
     *                         deliveryComplete 的参数值是消息内容是 deliveryComplete 类型
     *                         <br/>
     *                         The parameter value for deliveryComplete is that the message content is of type deliveryComplete
     * @return MqttPut
     */
    public MqttPut response(
            String clientId,
            BiConsumer<String, MqttMessage> biConsumer,
            Consumer<Throwable> connectionLost,
            Consumer<IMqttDeliveryToken> deliveryComplete
    ) {
        monitor.setClientId(clientId);
        monitor.setMqttCallback(
                new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable throwable) {
                        System.err.println(monitor.getClientId() + " MQTT Connection disconnected ");
                        monitor.reconnect();
                        connectionLost.accept(throwable);
                    }

                    @Override
                    public void messageArrived(String s, MqttMessage mqttMessage) {
                        String msg = new String(mqttMessage.getPayload());
                        biConsumer.accept(msg, mqttMessage);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                        deliveryComplete.accept(iMqttDeliveryToken);
                    }
                }
        );
        return this;
    }

    /**
     * 开启MQTT连接
     */
    public void start() {
        monitor.start(topic);
    }

    /**
     * 停止MQTT连接
     */
    public void stop() {
        try {
            monitor.getClient().disconnect();
        } catch (MqttException e) {
            throw new RuntimeException("MQTT" + topic + "Termination of connection exception", e);
        }
    }

    public MQTTMonitor getMonitor() {
        return monitor;
    }
}
