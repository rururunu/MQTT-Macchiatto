# MqttPush Message Publishing

`MqttPush` is the core utility class for publishing MQTT messages, supporting both synchronous and asynchronous publishing with rich configuration options.

## Basic Usage

```java
// Simplest usage
MqttPush mqttPush = new MqttPush();
mqttPush.push("device/status", "online", MQTTQos.AT_LEAST_ONCE);

// Publishing with callbacks
mqttPush.push("sensor/temperature", "25.6°C", 
    MQTTQos.AT_LEAST_ONCE,
    token -> System.out.println("Send successful"),
    (token, error) -> System.err.println("Send failed: " + error)
);
```

## Constructor Methods

| Constructor | Description |
|-------------|-------------|
| `new MqttPush()` | Create instance with default configuration |
| `new MqttPush.builder()` | Create instance using builder pattern |

## Connection Configuration Methods

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `host(String host)` | MQTT server address | `MqttPush` | Set MQTT server address |
| `username(String username)` | Username | `MqttPush` | Set connection username |
| `password(String password)` | Password | `MqttPush` | Set connection password |
| `timeout(Integer timeout)` | Timeout (milliseconds) | `MqttPush` | Set connection timeout |
| `keepalive(Integer keepalive)` | Heartbeat interval (seconds) | `MqttPush` | Set heartbeat detection interval |
| `serviceId(String serviceId)` | Service ID | `MqttPush` | Set client ID |
| `cleanSession(boolean cleanSession)` | Whether to clean session | `MqttPush` | Set session cleanup flag |

## Connection Management Methods

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `start()` | None | `void` | Initialize configuration and start connection, may throw MqttException |
| `init()` | None | `MqttPush` | Initialize configuration and start connection, convert exceptions to RuntimeException |
| `init(Consumer<Exception> error)` | Error callback function | `MqttPush` | Initialize connection, call callback function on error |
| `stop()` | None | `void` | Close MQTT connection |

## Message Publishing Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `foundTopic(String topic)` | Topic name | Pre-create topic object to improve publishing performance |
| `push(String topic, String message, MQTTQos qos)` | Topic, message, QoS level | Synchronously publish message |
| `push(String topic, String message, MQTTQos qos, Consumer<IMqttToken> success, BiConsumer<IMqttToken, Throwable> failure)` | Topic, message, QoS level, success callback, failure callback | Asynchronously publish message with success and failure callbacks |

## Builder Pattern Methods

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `builder.host(String host)` | MQTT server address | `builder` | Set server address |
| `builder.username(String username)` | Username | `builder` | Set username |
| `builder.password(String password)` | Password | `builder` | Set password |
| `builder.timeout(Integer timeout)` | Timeout | `builder` | Set timeout |
| `builder.keepalive(Integer keepalive)` | Heartbeat interval | `builder` | Set heartbeat interval |
| `builder.serviceId(String serviceId)` | Service ID | `builder` | Set client ID |
| `builder.cleanSession(boolean clean)` | Whether to clean session | `builder` | Set session cleanup flag |
| `builder.build()` | None | `MqttPush` | Build MqttPush instance |

## Usage Examples

### Basic Publishing Example

```java
@Service
public class DeviceService {
    
    private MqttPush mqttPush = new MqttPush();
    
    // Simple publishing
    public void reportDeviceStatus(String deviceId, String status) {
        String topic = "device/" + deviceId + "/status";
        mqttPush.push(topic, status, MQTTQos.AT_LEAST_ONCE);
    }
    
    // Publishing with callbacks
    public void sendCriticalAlert(String message) {
        mqttPush.push("alerts/critical", message, MQTTQos.EXACTLY_ONCE,
            token -> log.info("Alert sent successfully: {}", token.getMessageId()),
            (token, error) -> log.error("Alert send failed", error)
        );
    }
}
```

### Builder Pattern Example

```java
// Create MqttPush with custom configuration using Builder pattern
MqttPush customPush = new MqttPush.builder()
    .host("tcp://production.mqtt.com:1883")
    .username("prod-user")
    .password("prod-password")
    .timeout(15000)
    .keepalive(120)
    .serviceId("production-publisher")
    .cleanSession(false)
    .build()
    .init(error -> {
        log.error("MQTT connection failed", error);
    });

// Publish critical business data
customPush.push("business/orders", orderJson, 
    MQTTQos.EXACTLY_ONCE,
    token -> log.info("Order data sent successfully"),
    (token, error) -> handlePublishError("Order send failed", error)
);
```

### Spring Boot Integration Example

```java
@Configuration
public class MqttConfig {
    
    @Bean
    @Primary
    public MqttPush defaultMqttPush() {
        return new MqttPush().init();
    }
    
    @Bean("productionMqtt")
    public MqttPush productionMqttPush() {
        return new MqttPush.builder()
            .host("tcp://prod-mqtt.company.com:1883")
            .username("prod-publisher")
            .password("prod-password")
            .timeout(15000)
            .keepalive(120)
            .cleanSession(false)
            .build()
            .init();
    }
}

@Service
public class MessagePublisher {
    
    @Autowired
    private MqttPush defaultMqttPush;
    
    @Autowired
    @Qualifier("productionMqtt")
    private MqttPush productionMqtt;
    
    public void publishToDefault(String topic, String message) {
        defaultMqttPush.push(topic, message, MQTTQos.AT_LEAST_ONCE);
    }
    
    public void publishToProduction(String topic, String message) {
        productionMqtt.push(topic, message, MQTTQos.EXACTLY_ONCE,
            token -> log.info("Production message sent successfully"),
            (token, error) -> log.error("Production message send failed", error)
        );
    }
}
```

## Best Practices

1. **Connection Reuse**: Reuse MqttPush instances as much as possible, avoid frequent connection creation
2. **Pre-create Topics**: Use `foundTopic()` to pre-create frequently published topics
3. **Appropriate QoS**: Choose suitable QoS levels based on business requirements
4. **Asynchronous Processing**: Use asynchronous callbacks for high-frequency publishing scenarios to avoid blocking
5. **Error Handling**: Implement retry mechanisms and error logging
6. **Resource Management**: Remember to call `stop()` method to release connections when application shuts down

```java
@PreDestroy
public void cleanup() {
    try {
        mqttPush.stop();
    } catch (Exception e) {
        log.warn("Failed to close MQTT connection", e);
    }
}
```