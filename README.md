![ChatGPT Image 2025年5月23日 16_45_26](https://github.com/user-attachments/assets/55987aad-0e91-4c4b-982f-3162a5c3c47c)
# ☕ MQTT Macchiatto
> ☁️ A graceful MQTT messaging encapsulation tool for Spring Boot, as smooth as a cup of macchiato.

[📦GitHub](https://github.com/rururunu/MQTT-Macchiatto) | [🔗Gitee](https://gitee.com/guolvaita/mqtt-macchiatto) | 📖 [中文文档](./README_ZH.md) | [👆 official website](https://rururunu.github.io/MQTT-Macchiatto/web/index.html)
<br>



![Maven Central](https://img.shields.io/maven-central/v/io.github.rururunu/MQTT-Macchiatto)
![License](https://img.shields.io/github/license/rururunu/MQTT-Macchiatto)
![Stars](https://img.shields.io/github/stars/rururunu/MQTT-Macchiatto?style=social)

<br>
<br>


## ✨ Highlights

* 🚀 Quick Integration: Start MQTT communication with just a few lines of configuration.
* 🧩 High Abstraction: Hide complex API calls with a clean and clear interface.
* 🔌 Flexible Extension: Supports multiple service connections and manual configuration.
* 💡 Auto Reconnection: Built-in reconnect mechanism for better stability.
  <br>
  <br>
  <br>

## 🧃 Quick Start
<br>
<br>

### 1. Add Dependency
```xml
<dependency>
    <groupId>io.github.rururunu</groupId>
    <artifactId>MQTT-Macchiatto</artifactId>
    <version>0.1.5</version>
</dependency>
```
<br>

### 2. Configure application.yml
```yaml
mto-mqtt:
    host: tcp://your-host:1883
    username: your-username
    password: your-password
    timeout: 10000
    keepalive: 60
    reconnect-frequency-ms: 5000
```
<br>

### 3. Configure Main Class
```java
@SpringBootApplication(scanBasePackages = {"Your project path","io.github.rururunu"})
```
<br>

## 📥 Listen to Messages
```java
MqttPut.of("rsp/")
    .response((topic, message) -> {
        // Write the response operation for receiving messages here
        System.out.println("topic:" + topic + "message:" + message);
    }).start();
```
<br>
<br>

## 📤 Publish Messages
```java
MqttPush mqttPush = new MqttPush();
mqttPush.push("your/topic", "Hello MQTT", MQTTQos.AT_LEAST_ONCE);
```
With callback:
```java
mqttPush.push("your/topic", "Message", MQTTQos.AT_LEAST_ONCE,
    token -> System.out.println("Sent successfully"),
    (token, throwable) -> System.err.println("Send failed")
);
```
<br>
<br>
<br>

## 🧪 Advanced Usage
* ✅ Multiple MQTT client instances for different services

* 🔒 Support full configuration like CleanSession, Qos

* 🔄 Built-in reconnect mechanism (reconnect())

### 📥 Other Message Listening Examples
```java
MqttPut.of()
    .setTopic("topic")
    .setServiceId("serviceId")
    .setCleanSession(true)
    .response((message) -> {
        // Write the response operation for receiving messages here
    })
    .start();
```
Using MQTTMonitor:
```java
MQTTMonitor mqttMonitor = new MQTTMonitor();
mqttMonitor.setClientId("clientId");
mqttMonitor.setCleanSession(false);
mqttMonitor.setQos(MQTTQos.EXACTLY_ONCE);
mqttMonitor.setMqttCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable throwable) {
                    // Write code for abnormal disconnection
                    // You can directly use the packaged product
                    mqttMonitor.reconnect();
                }

                @Override
                public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
                           // Write the response operation for receiving messages here
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                }
});
//Activate subscription
mqttMonitor.start("topic");
```
Custom service:
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

### 📤 Other Message Publishing Examples
Using MQTTReport:
```java
// Create connection
MQTTReport mqttReport = new MQTTReport();
mqttReport.setTopic("topic");
mqttReport.setServiceId("serviceId");
mqttReport.setCleanSession(false);
mqttReport.start();
// send message
mqttReport.getMessage().setQos(MQTTQos.EXACTLY_ONCE.getValue());
mqttReport.getMessage().setPayload("hello".getBytes());
mqttReport.publish(mqttReport.getMqttTopic(), mqttReport.getMessage());
```
Using MqttMessage:
```java
// Create connection
MQTTReport mqttReport = new MQTTReport();
mqttReport.setTopic("topic");
mqttReport.setServiceId("serviceId");
mqttReport.start();
// Create connection
MqttMessage message = new MqttMessage();
message.setQos(MQTTQos.EXACTLY_ONCE.getValue());
message.setPayload("hello".getBytes());
mqttReport.publish("topic", message);
```
Custom connection with builder:
```java
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
// Report message
mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE,
                (iMqttToken) -> System.out.println("success"),
                (iMqttToken, throwable) -> System.out.println("failure")
        );
```

Or simple initialization:

```java
// Initialize host information
MqttPush mqttPush = new MqttPush()
                 .host("tcp://127.0.0.1:1883")
                 .username("username")
                 .password("password")
                 .timeout(10000)
                 .keepalive(60)
                 .cleanSession(false)
                 .reconnectFrequencyMs(5000);
// Open the host
mqttPush.start();
// found topic You can ignore this step. If the topic is not created,
// an MqttTopic object for the topic will be automatically created and placed
// in memory when calling the push method
mqttPush.foundTopic("test/");
// Report message
mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE,
                 (iMqttToken) -> System.out.println("success"),
                 (iMqttToken, throwable) -> System.out.println("failure")
);
MqttPush.stop();
```

### 📤 Long Connection Publishing


```java
class MqttMacchiatto {

	private MqttPush mqttPush = new MqttPush();

	public void push() {
		mqttPush.push("test/", "test", MQTTQos.AT_LEAST_ONCE);
	}
}
```
<br>
<br>
<br>

## 🧠 Why Choose MQTT Macchiatto?
### 📚 Using raw MQTT in Spring Boot often means:

* Multiple levels of callback configuration

* Tedious error handling

* Difficult multi-service connection management

### But MQTT Macchiatto offers:

* ☕ One-line connection setup

* ☕ Fully encapsulated publishing/subscribing utility classes

* ☕ Clearer code structure and response handling

<br>
<br>
<br>

## 💬 Contact
For suggestions, issues, or collaboration:

* 📧 Email: guolvaita@gmail.com

* 💬 WeChat: AfterTheMoonlight
  <br>
  <br>
  <br>

## 🌟 Support Me
If you find this project helpful, consider giving it a 🌟 Star. It's my biggest motivation to keep improving!
