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
 *                 .topic("topic")
 *                 .serviceId("serviceId")
 *                 .cleanSession(true)
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
 * @version 1.5
 * @since 1.5
 */
public final class MqttPut {

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

    public MqttPut reconnectFrequencyMs(int ms) {
        monitor.reconnectFrequencyMs(ms);
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

    public MqttPut topic(String topic) {
        this.topic = topic;
        return this;
    }

    public MqttPut serviceId(String serviceId) {
        monitor.setClientId(serviceId);
        return this;
    }


    /**
     * 只返回 String payload
     */
    private MqttPut doResponseToMessString(String clientId,
                                           boolean withTopic,
                                           BiConsumer<String, String> handler,
                                           Consumer<Throwable> lost,
                                           Consumer<IMqttDeliveryToken> complete) {
        monitor.setClientId(clientId);
        monitor.setMqttCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                System.err.println(clientId + " MQTT Connection disconnected " + cause);
                monitor.reconnect();
                if (lost != null) lost.accept(cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload());
                if (withTopic) handler.accept(topic, payload);
                else handler.accept(null, payload);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                if (complete != null) complete.accept(token);
            }
        });
        return this;
    }

    /**
     * 直接返回 MqttMessage
     */
    private MqttPut doResponse(String clientId,
                               boolean withTopic,
                               BiConsumer<String, MqttMessage> handler,
                               Consumer<Throwable> lost,
                               Consumer<IMqttDeliveryToken> complete) {
        monitor.setClientId(clientId);
        monitor.setMqttCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                System.err.println(clientId + " MQTT Connection disconnected " + cause);
                monitor.reconnect();
                if (lost != null) lost.accept(cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                if (withTopic) handler.accept(topic, message);
                else handler.accept(null, message);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                if (complete != null) complete.accept(token);
            }
        });
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
        return doResponseToMessString(String.valueOf(UUID.randomUUID()), false, (t, m) -> consumer.accept(m), null, null);
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param consumer Response after listening to the message 监听到消息后的响应
     *                 <br/>
     *                 consumer 的参数值是消息内容为 MqttMessage 类型
     *                 <br/>
     *                 The parameter value of the consumer is that the message content is of type MqttMessage
     * @return MqttPut
     */
    public MqttPut responseRow(Consumer<MqttMessage> consumer) {
        return doResponse(String.valueOf(UUID.randomUUID()), false, (t, m) -> consumer.accept(m), null, null);
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
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
    public MqttPut response(Consumer<String> consumer, Consumer<Throwable> connectionLost) {
        return doResponseToMessString(String.valueOf(UUID.randomUUID()), false, (t, m) -> consumer.accept(m), connectionLost, null);
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param consumer       Response after listening to the message 监听到消息后的响应
     *                       <br/>
     *                       consumer 的参数值是消息内容为 MqttMessage 类型
     *                       <br/>
     *                       The parameter value of the consumer is that the message content is of type MqttMessage
     * @param connectionLost Operation after listening for exceptions 监听发生异常后的操作
     *                       <br/>
     *                       connectionLost 的参数值是消息内容是 Throwable 类型
     *                       <br/>
     *                       The parameter value of connectionLost is that the message content is of type Throwable
     * @return MqttPut
     */
    public MqttPut responseRow(Consumer<MqttMessage> consumer, Consumer<Throwable> connectionLost) {
        return doResponse(String.valueOf(UUID.randomUUID()), false, (t, m) -> consumer.accept(m), connectionLost, null);
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
        return doResponseToMessString(String.valueOf(UUID.randomUUID()), false, (t, m) -> consumer.accept(m), connectionLost, deliveryComplete);
    }

    /**
     * Set the response after listening to messages
     * <br/>
     * 设定监听到消息后的响应
     *
     * @param consumer         Response after listening to the message 监听到消息后的响应
     *                         <br/>
     *                         consumer 的参数值是消息内容为 MqttMessage 类型
     *                         <br/>
     *                         The parameter value of the consumer is that the message content is of type MqttMessage
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
    public MqttPut responseRow(
            Consumer<MqttMessage> consumer,
            Consumer<Throwable> connectionLost,
            Consumer<IMqttDeliveryToken> deliveryComplete
    ) {
        return doResponse(String.valueOf(UUID.randomUUID()), false, (t, m) -> consumer.accept(m), connectionLost, deliveryComplete);
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
        return doResponseToMessString(UUID.randomUUID().toString(), true, biConsumer, null, null);
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
     *                   biConsumer param2 msg:MqttMessage
     *                   <br/>
     *                   biConsumer 参数1 主题:string
     *                   <br/>
     *                   biConsumer 参数2 消息:MqttMessage
     * @return MqttPut
     */
    public MqttPut responseRow(BiConsumer<String, MqttMessage> biConsumer) {
        return doResponse(UUID.randomUUID().toString(), true, biConsumer, null, null);
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
        return doResponseToMessString(UUID.randomUUID().toString(), true, biConsumer, connectionLost, null);
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
     *                       biConsumer param2 msg:MqttMessage
     *                       <br/>
     *                       biConsumer 参数1 主题:string
     *                       <br/>
     *                       biConsumer 参数2 消息:MqttMessage
     * @param connectionLost Operation after listening for exceptions 监听发生异常后的操作
     *                       <br/>
     *                       connectionLost 的参数值是消息内容是 Throwable 类型
     *                       <br/>
     *                       The parameter value of connectionLost is that the message content is of type Throwable
     * @return MqttPut
     */
    public MqttPut responseRow(BiConsumer<String, MqttMessage> biConsumer, Consumer<Throwable> connectionLost) {
        return doResponse(UUID.randomUUID().toString(), true, biConsumer, connectionLost, null);
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
     *                         biConsumer param2 msg:MqttMessage
     *                         <br/>
     *                         biConsumer 参数1 主题:string
     *                         <br/>
     *                         biConsumer 参数2 消息:MqttMessage
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
        return doResponseToMessString(UUID.randomUUID().toString(), true, biConsumer, connectionLost, deliveryComplete);
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