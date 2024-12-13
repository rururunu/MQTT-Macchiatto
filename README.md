# MQTT Macchiatto
----

服务于Spring Boot 的 MQTT 快捷封装, 帮你快速编写 接收/发布消息 的代码

MQTT Quick Encapsulation for Spring Boot, helping you quickly write code to receive/publish messages


[GitHub](https://github.com/rururunu/MQTT-Macchiatto) | [Gitee](https://gitee.com/guolvaita/mqtt-macchiatto)


### 快速开始 Quick Start

#### 在 pom.xml 中引入我们 Introduce us in pom.xml
```xml
<dependency>
    <groupId>io.github.rururunu</groupId>
    <artifactId>MQTT-Macchiatto</artifactId>
    <version>1.0</version>
</dependency>
```
#### 配置 to configure
在 application.yml 中编写配置 Write configuration in application.yml:
```yaml
mto-mqtt:
    # 主机
    host: tcp://192.168.1.125:1883
    # 端口
    port: 1883
    # 用户名
    username: nuolong
    # 密码
    password: nl@12345
    # 超时时间
    tiemout: 10000
    # 心跳
    keepalive: 60
    # 重连间隔
    reconnect-frequency-ms: 5000
```
#### 监听 Monitor
```java
MqttPut.of("rsp/")
    .response((topic, message) -> {
        // 在这里编写收到消息的响应操作
        // Write the response operation for receiving messages here
        System.out.println("topic:" + topic + "message:" + message);
    }).start();
```
或 or
```java
MqttPut.of()
    .setTopic("topic")
    .setServiceId("serviceId")
    .setCleanSession(true)
    .response((message) -> {
        // 在这里编写收到消息的响应操作
        // Write the response operation for receiving messages here
    })
    .start();
```
或 or
```java
MQTTMonitor mqttMonitor = new MQTTMonitor();
mqttMonitor.setClientId("clientId");
mqttMonitor.setCleanSession(false);
mqttMonitor.setQos(MQTTQos.EXACTLY_ONCE);
mqttMonitor.setMqttCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable throwable) {
                    // 编写异常断开的代码
                    // Write code for abnormal disconnection
                    // 可以直接使用封装好的 mqttMonitor.reconnect();
                    // You can directly use the packaged product
                    mqttMonitor.reconnect();
                }

                @Override
                public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
                           // 在这里编写收到消息的响应操作
                           // Write the response operation for receiving messages here
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                }
});
// 开启订阅
//Activate subscription
mqttMonitor.start("topic");
```

#### 上报 Report
```java
// 创建连接
// Create connection
MQTTReport mqttReport = new MQTTReport();
mqttReport.setTopic("topic");
mqttReport.setServiceId("serviceId");
mqttReport.setCleanSession(false);
mqttReport.start();
// 发送消息
// send message
mqttReport.getMessage().setQos(MQTTQos.EXACTLY_ONCE.getValue());
mqttReport.getMessage().setPayload("hello".getBytes());
mqttReport.publish(mqttReport.getMqttTopic(), mqttReport.getMessage());
```
或 or
```java
// 创建连接
// Create connection
MQTTReport mqttReport = new MQTTReport();
mqttReport.setTopic("topic");
mqttReport.setServiceId("serviceId");
mqttReport.start();
// 发送消息
// Create connection
MqttMessage message = new MqttMessage();
message.setQos(MQTTQos.EXACTLY_ONCE.getValue());
message.setPayload("hello".getBytes());
mqttReport.publish("topic", message);
```

----

鄙人是第一次造轮子,有问题的地方,还请多多指教

如果可以帮助到您希望您能献出宝贵的🌟Star🫶感谢

This is my first time making wheels. If there are any issues, please advise me

If it can help you, I hope you can contribute your valuable resources 🌟 Star 🫶 Thank you


联系我:

邮箱: guolvaita@gmail.com

微信: AfterTheMoonlight



Contact me:

Email: guolvaita@gmail.com

WeChat: AfterTheMoonlight

