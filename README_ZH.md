![ChatGPT Image 2025年5月23日 16_45_26](https://github.com/user-attachments/assets/55987aad-0e91-4c4b-982f-3162a5c3c47c)
# ☕ MQTT Macchiatto
> ☁️ Spring Boot 下优雅的 MQTT 消息通信封装工具，让你的开发像一杯玛奇朵一样顺滑。

[📦GitHub](https://github.com/rururunu/MQTT-Macchiatto) | [🔗Gitee](https://gitee.com/guolvaita/mqtt-macchiatto) | [👆官网](https://rururunu.github.io/MQTT-Macchiatto/web/index.html)
<br/>



![Maven Central](https://img.shields.io/maven-central/v/io.github.rururunu/MQTT-Macchiatto)
![License](https://img.shields.io/github/license/rururunu/MQTT-Macchiatto)
![Stars](https://img.shields.io/github/stars/rururunu/MQTT-Macchiatto?style=social)

<br/>
<br/>

## ✨ 项目亮点 Highlights
* 🚀 快速集成：仅需几行配置即可启动 MQTT 通信。
* 🧩 高度封装：屏蔽繁琐 API 调用，简洁明了。
* 🔌 灵活扩展：支持自定义多服务连接、手动配置。
* 💡 支持异常重连机制：让连接更加稳定可靠。
  <br/>
  <br/>
  <br/>

## 🧃 快速开始 Quick Start
<br/>
<br/>

### 1. 添加依赖
```xml
<dependency>
    <groupId>io.github.rururunu</groupId>
    <artifactId>MQTT-Macchiatto</artifactId>
    <version>0.1.5</version>
</dependency>
```
<br/>

### 2. 配置 application.yml
在 application.yml 中编写配置 Write configuration in application.yml:
```yaml
mto-mqtt:
    # 主机
    host: tcp://your-host:1883
    # 用户名
    username: your-username
    # 密码
    password: your-password
    # 超时时间
    timeout: 10000
    # 心跳
    keepalive: 60
    # 重连间隔
    reconnect-frequency-ms: 5000
```
<br/>

### 3. 启动类配置
```java
@SpringBootApplication(scanBasePackages = {"Your project path","io.github.rururunu"})
```
<br/>

## 📥 接收消息 - Listen
```java
MqttPut.of("rsp/")
    .response((topic, message) -> {
        // 在这里编写收到消息的响应操作
        // Write the response operation for receiving messages here
        System.out.println("topic:" + topic + "message:" + message);
    }).start();
```
<br/>
<br/>

## 📤 发布消息 - Publish
```java
MqttPush mqttPush = new MqttPush();
mqttPush.push("your/topic", "Hello MQTT", MQTTQos.AT_LEAST_ONCE);
```
或者带回调
```java
mqttPush.push("your/topic", "Message", MQTTQos.AT_LEAST_ONCE,
    token -> System.out.println("Sent successfully"),
    (token, throwable) -> System.err.println("Send failed")
);
```
<br/>
<br/>
<br/>

## 🧪 高级用法 Advanced Usage
* ✅ 支持构建多个 MQTT 客户端连接多个服务

* 🔒 支持 CleanSession、Qos 等完整参数配置

* 🔄 支持断线重连（reconnect()）

### 📥 接收消息其他示例- Listen
```java
MqttPut.of()
    .topic("topic")
    .serviceId("serviceId")
    .cleanSession(true)
    .response((message) -> {
        // 在这里编写收到消息的响应操作
        // Write the response operation for receiving messages here
    })
    .start();
```
使用MQTTMonitor对象
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
自定义服务
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

### 📤 发布消息其他示例 - Publish
使用 MQTTReport 连接
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
使用 MqttMessage 构建信息
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
自定义服务
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

### 📤 发布消息长连接


```java
class MqttMacchiatto {

	private MqttPush mqttPush = new MqttPush();

	public void push() {
		mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE);
	}
}
```
<br/>
<br/>
<br/>

## 🧠 为什么选择 MQTT Macchiatto？
### 📚 Spring Boot 项目中，使用原生 MQTT 往往意味着：

* 多层回调配置

* 错误处理繁琐

* 多服务连接困难

### 而 MQTT Macchiatto 提供：

* ☕ 一行配置连接服务

* ☕ 高度封装的发布和订阅工具类

* ☕ 更清晰的代码组织和响应方式

<br/>
<br/>
<br/>

## 🌟 Star 支持 Support Me
如果你觉得这个项目对你有帮助，请不要吝啬点一个 🌟 Star，这是我持续优化的最大动力！
