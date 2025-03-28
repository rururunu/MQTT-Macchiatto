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
    <version>0.1.3</version>
</dependency>
```
#### 📝 配置 to configure
在 application.yml 中编写配置 Write configuration in application.yml:
```yaml
mto-mqtt:
    # 主机
    host: tcp://${ip}:${port}
    # 用户名
    username: ${username}
    # 密码
    password: ${password}
    # 超时时间
    timeout: 10000
    # 心跳
    keepalive: 60
    # 重连间隔
    reconnect-frequency-ms: 5000
```

启动类上添加 Add to Startup Class
```java
@SpringBootApplication(scanBasePackages = {"io.github.rururunu"})
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
MqttPush mqttPush = new MqttPush();
mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE);
```
或 or
```java
MqttPush mqttPush = new MqttPush();
mqttPush.start();
mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE,
	(iMqttToken) -> System.out.println("success"),
	(iMqttToken, throwable) -> System.out.println("failure")
);
```

或 or

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

### 🪢 自定义 MQTT 服务信息 



如果可以通过其他方式获取MQTT 服务的信息，可以省略配置信息，直接通过构建MQTT 服务信息来进行消息的监听和上报，也可以通过创建多个对象来连接不同的 MQTT 服务



If information about MQTT services can be obtained through other means, configuration information can be omitted and messages can be monitored and reported directly by building MQTT service information. Multiple objects can also be created to connect different MQTT services

#### 自定义MQTT 服务监听 Custom host monitoring

``` java
MqttPut.of("test/")
        .host("tcp://127.0.0.1:1883")
        .username("username")
        .password("password")
        .timeout(10000)
        .keepalive(60)
        .cleanSession(false)
        .reconnectFrequencyMs(5000)
        .response((topic, msg) -> System.out.println(topic + ":" + msg))
        .start();
```

#### 自定义MQTT 服务上报 Custom host reporting

```java
// 使用 builder 初始化主机信息并使用 init 加载 
// Initialize host information using builder and load it using init
MqttPush mqttPush = new MqttPush.builder()
            .host("tcp://127.0.0.1:1883")
            .username("username")
            .password("password")
            .timeout(10000)
            .keepalive(60)
            .cleanSession(false)
            .build()
            .init((e) -> {
                System.out.println("Mqtt Creation failed" + e);
            });
// 上报消息
// Report message
mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE,
                (iMqttToken) -> System.out.println("success"),
                (iMqttToken, throwable) -> System.out.println("failure")
        );
```

或 or

```java
// 初始化主机信息
// Initialize host information
MqttPush mqttPush = new MqttPush()
                 .host("tcp://127.0.0.1:1883")
                 .username("username")
                 .password("password")
                 .timeout(10000)
                 .keepalive(60)
                 .cleanSession(false)
                 .reconnectFrequencyMs(5000);
// 开启主机
// Open the host
mqttPush.start();
// 创建主题 可以忽略这一步,若topic没有创建在调用 push方法时会自动创建一个 topic 的 MqttTopic 对象放入内存
// found topic You can ignore this step. If the topic is not created,
// an MqttTopic object for the topic will be automatically created and placed
// in memory when calling the push method
mqttPush.foundTopic("test/");
// 上报消息
// Report message
mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE,
                 (iMqttToken) -> System.out.println("success"),
                 (iMqttToken, throwable) -> System.out.println("failure")
);
// 可以选择手动关闭
MqttPush.stop();
```



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

