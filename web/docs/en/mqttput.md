# MqttPut Message Listening

`MqttPut` is the core utility class for receiving MQTT messages, providing a clean chained API.

## Quick Start

```java
// Subscribe, handle, start
MqttPut.of("demo/hello")
    .response((topic, msg) -> System.out.println("Got: " + msg))
    .start();
```

Need custom server/auth/QoS?

```java
MqttPut.of("demo/+/events")
    .host("tcp://127.0.0.1:1883")
    .username("user")
    .password("pass")
    .qos(MQTTQos.AT_LEAST_ONCE)
    .response((topic, msg) -> System.out.println(topic + " -> " + msg))
    .start();
```

## Highlights

- Simple chaining: `of()->response()->start()`
- Flexible callbacks: payload-only, with topic, or full `MqttMessage`
- Robustness: auto-reconnect, QoS, clean session, heartbeat, timeout
- Lifecycle hooks: `connectionLost`, `deliveryComplete`
- Easy integration: friendly with Spring Boot and custom routing

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
| `topic(String topic)` | Topic name | Set subscription topic |
| `serviceId(String serviceId)` | Service ID | Set client ID (same as `clientId`) |
| `clientId(String clientId)` | Client ID | Set client ID |
## Message Response Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `response(Consumer<String> consumer)` | Message handler | Receive payload only (String) |
| `response(Consumer<String> consumer, Consumer<Throwable> connectionLost)` | Message handler, error callback | Handle payload and listen for connection lost errors |
| `response(Consumer<String> consumer, Consumer<Throwable> connectionLost, Consumer<IMqttDeliveryToken> deliveryComplete)` | Message handler, error callback, delivery callback | Full lifecycle callbacks (message, error, delivery) |
| `response(BiConsumer<String, String> biConsumer)` | Topic + message handler | Receive topic and payload (String) |
| `response(BiConsumer<String, String> biConsumer, Consumer<Throwable> connectionLost)` | Topic + message handler, error callback | Same as above with error callback |
| `response(BiConsumer<String, String> biConsumer, Consumer<Throwable> connectionLost, Consumer<IMqttDeliveryToken> deliveryComplete)` | Topic + message handler, error callback, delivery callback | Same as above with delivery callback |
| `responseRow(Consumer<MqttMessage> consumer)` | Message handler | Receive full `MqttMessage` object |
| `responseRow(Consumer<MqttMessage> consumer, Consumer<Throwable> connectionLost)` | Message handler, error callback | Full message object + error callback |
| `responseRow(Consumer<MqttMessage> consumer, Consumer<Throwable> connectionLost, Consumer<IMqttDeliveryToken> deliveryComplete)` | Message handler, error callback, delivery callback | Full message object + full lifecycle callbacks |
| `responseRow(BiConsumer<String, MqttMessage> biConsumer)` | Topic + message handler | Receive topic + full `MqttMessage` object |
| `responseRow(BiConsumer<String, MqttMessage> biConsumer, Consumer<Throwable> connectionLost)` | Topic + message handler, error callback | Same as above with error callback |
| `responseRow(BiConsumer<String, MqttMessage> biConsumer, Consumer<Throwable> connectionLost, Consumer<IMqttDeliveryToken> deliveryComplete)` | Topic + message handler, error callback, delivery callback | Same as above with delivery callback |

## Control Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `start()` | None | Start MQTT connection and message listening |
| `stop()` | None | Stop MQTT connection |

## Usage Examples

### Basic Listening Example

```java
// Listen to a single topic
MqttPut.of("device/status")
    .response((topic, message) -> {
        System.out.println("Device status update: " + message);
    })
    .start();

// Use wildcard to listen to multiple topics
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
            .serviceId("device-status-listener")
            .cleanSession(false)
            .response(this::handleDeviceStatus)
            .start();
        
        // Listen to sensor data
        MqttPut.of("sensor/+/data")
            .serviceId("sensor-data-listener")
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

## FAQ

- **How do I set a custom client ID?**
  Use `serviceId("your-id")` or `clientId("your-id")` before `start()`.

- **How do I get the raw MQTT message (headers, QoS, retained)?**
  Use `responseRow(...)` overloads to receive `MqttMessage` directly.

- **Which QoS should I choose?**
  `AT_MOST_ONCE` for best performance, `AT_LEAST_ONCE` for balanced reliability, `EXACTLY_ONCE` for maximum reliability.

- **What if the connection drops?**
  Auto-reconnect is enabled. You can also provide a `connectionLost` callback for logging or recovery.

- **Avoid blocking in handlers?**
  Yes. Offload heavy work to thread pools or async executors to keep the listener responsive.

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