# MqttPut Message Listening

`MqttPut` is the core utility class for receiving MQTT messages, providing a clean chained API.

## Basic Usage

```java
// Simplest usage
MqttPut.of("device/status")
    .response((topic, message) -> {
        System.out.println("Device status: " + message);
    })
    .start();

// Only care about message content, not topic
MqttPut.of("sensor/data")
    .response(message -> {
        System.out.println("Sensor data: " + message);
    })
    .start();
```

## Static Factory Methods

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `of()` | None | `MqttPut` | Create new MqttPut instance |
| `of(String topic)` | Topic name | `MqttPut` | Create MqttPut instance with topic set |

## Connection Configuration Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `host(String host)` | MQTT server address | Set custom MQTT server address |
| `username(String username)` | Username | Set connection username |
| `password(String password)` | Password | Set connection password |
| `timeout(int timeout)` | Timeout (milliseconds) | Set connection timeout |
| `keepalive(int keepalive)` | Heartbeat interval (seconds) | Set heartbeat detection interval |
| `cleanSession(boolean clean)` | Whether to clean session | Set session cleanup flag |
| `reconnectFrequencyMs(int ms)` | Reconnection interval (milliseconds) | Set auto-reconnection interval |
| `qos(MQTTQos qos)` | QoS level | Set message quality level |

## Topic and Client Configuration

| Method | Parameters | Description |
|--------|------------|-------------|
| `setTopic(String topic)` | Topic name | Set subscription topic |
| `setServiceId(String serviceId)` | Service ID | Set client ID |
| `clientId(String clientId)` | Client ID | Set client ID (same as setServiceId) |
| `setCleanSession(boolean clean)` | Whether to clean session | Set session cleanup flag |

## Message Response Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `response(Consumer<String> consumer)` | Message handler function | Set message handler callback, receives only message content |
| `response(BiConsumer<String, String> biConsumer)` | Topic and message handler function | Set message handler callback, receives topic and message content |
| `response(String clientId, Consumer<String> consumer)` | Client ID, message handler function | Message handler callback with specified client ID |
| `response(String clientId, BiConsumer<String, MqttMessage> biConsumer)` | Client ID, message handler function | Specified client ID, receives message content and MqttMessage object |

## Control Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `start()` | None | Start MQTT connection and message listening |
| `stop()` | None | Stop MQTT connection |

## Usage Examples

### Basic Listening Example

```java
// Listen to single topic
MqttPut.of("device/status")
    .response((topic, message) -> {
        System.out.println("Device status update: " + message);
    })
    .start();

// Use wildcards to listen to multiple topics
MqttPut.of("sensor/+/data")
    .response((topic, message) -> {
        String[] parts = topic.split("/");
        String sensorId = parts[1];
        System.out.println("Sensor " + sensorId + " data: " + message);
    })
    .start();
```

### Custom Configuration Example

```java
// Connect to custom MQTT server
MqttPut.of("production/alerts")
    .host("tcp://prod-mqtt.company.com:1883")
    .username("prod-user")
    .password("secure-password")
    .timeout(15000)
    .keepalive(120)
    .cleanSession(false)
    .reconnectFrequencyMs(3000)
    .qos(MQTTQos.EXACTLY_ONCE)
    .response((topic, message) -> {
        handleProductionAlert(topic, message);
    })
    .start();
```

### Spring Boot Integration Example

```java
@Component
public class DeviceMessageListener {
    
    @Autowired
    private DeviceService deviceService;
    
    @PostConstruct
    public void initListeners() {
        // Listen to device status
        MqttPut.of("device/+/status")
            .setServiceId("device-status-listener")
            .setCleanSession(false)
            .response(this::handleDeviceStatus)
            .start();
        
        // Listen to sensor data
        MqttPut.of("sensor/+/data")
            .setServiceId("sensor-data-listener")
            .qos(MQTTQos.AT_LEAST_ONCE)
            .response(this::handleSensorData)
            .start();
    }
    
    private void handleDeviceStatus(String topic, String message) {
        String deviceId = extractDeviceId(topic);
        deviceService.updateDeviceStatus(deviceId, message);
    }
    
    private void handleSensorData(String topic, String message) {
        String sensorId = extractSensorId(topic);
        deviceService.processSensorData(sensorId, message);
    }
    
    private String extractDeviceId(String topic) {
        return topic.split("/")[1];
    }
    
    private String extractSensorId(String topic) {
        return topic.split("/")[1];
    }
}
```

## Topic Wildcards

MQTT supports two types of wildcards:

- `+`: Single-level wildcard, matches one level
- `#`: Multi-level wildcard, matches multiple levels

Examples:
- `sensor/+/temperature` can match `sensor/room1/temperature`, `sensor/room2/temperature`, etc.
- `sensor/#` can match `sensor/room1/temperature`, `sensor/room1/humidity`, `sensor/room2/data/current`, etc.

## Best Practices

1. **Use meaningful client IDs**: Avoid client ID conflicts
2. **Set appropriate QoS levels**: Choose suitable QoS based on business requirements
3. **Handle connection exceptions**: Listeners will auto-reconnect, but handle business logic properly
4. **Avoid blocking operations**: Avoid long-running blocking operations in message handler callbacks
5. **Use thread pools**: Consider asynchronous processing for time-consuming message handling

```java
@Component
public class AsyncMessageListener {
    
    @Autowired
    private TaskExecutor taskExecutor;
    
    @PostConstruct
    public void init() {
        MqttPut.of("heavy/processing")
            .response((topic, message) -> {
                // Process message asynchronously
                taskExecutor.execute(() -> {
                    processHeavyMessage(message);
                });
            })
            .start();
    }
}
```