# MQTTQos Quality Levels

`MQTTQos` is an enumeration class that defines MQTT message quality levels, providing three different message delivery guarantee levels.

## Enumeration Values

| Enum Value | Numeric Value | Description | Use Cases |
|------------|---------------|-------------|-----------|
| `AT_MOST_ONCE` | 0 | At most once, messages may be lost but not duplicated | Scenarios insensitive to message loss, such as sensor data |
| `AT_LEAST_ONCE` | 1 | At least once, messages won't be lost but may be duplicated | Recommended choice for most application scenarios |
| `EXACTLY_ONCE` | 2 | Exactly once, messages won't be lost or duplicated | Scenarios requiring extremely high message accuracy, such as financial transactions |

## Method Details

| Method | Return Value | Description |
|--------|--------------|-------------|
| `getValue()` | `int` | Get the numeric value of the QoS level |

## Usage Examples

### Using in Message Publishing

```java
// QoS 0 - Suitable for high-frequency, loss-tolerant data
mqttPush.push("sensor/data", "temperature:25.6", MQTTQos.AT_MOST_ONCE);

// QoS 1 - Recommended choice for most business scenarios
mqttPush.push("device/command", "restart", MQTTQos.AT_LEAST_ONCE);

// QoS 2 - Critical business data
mqttPush.push("payment/transaction", transactionData, MQTTQos.EXACTLY_ONCE);
```

### Using in Message Listening

```java
// Set listener's QoS level
MqttPut.of("critical/alerts")
    .qos(MQTTQos.EXACTLY_ONCE)
    .response((topic, message) -> {
        handleCriticalAlert(message);
    })
    .start();
```

### Getting Numeric Value

```java
// Get numeric value of QoS level
int qosValue = MQTTQos.AT_LEAST_ONCE.getValue(); // Returns 1

// Use in MqttMessage
MqttMessage message = new MqttMessage();
message.setQos(MQTTQos.EXACTLY_ONCE.getValue());
message.setPayload("Important data".getBytes());
```

## Detailed QoS Level Explanation

### QoS 0 - AT_MOST_ONCE (At Most Once)

**Characteristics:**
- No acknowledgment waited after message sending
- Messages may be lost
- No duplicate sending
- Highest performance, lowest overhead

**Use Cases:**
- Sensor data collection (temperature, humidity, etc.)
- Log data
- Status updates (frequently updated status)
- Scenarios requiring high real-time performance with loss tolerance

**Example:**
```java
// Sensor data reporting - losing one or two data points doesn't affect overall trend
mqttPush.push("sensor/room1/temperature", "25.6", MQTTQos.AT_MOST_ONCE);
mqttPush.push("sensor/room1/humidity", "60.2", MQTTQos.AT_MOST_ONCE);
```

### QoS 1 - AT_LEAST_ONCE (At Least Once)

**Characteristics:**
- Guarantees message is delivered at least once
- May receive duplicate messages
- Requires acknowledgment mechanism
- Balance between performance and reliability

**Use Cases:**
- Device control commands
- Business notifications
- General data transmission
- Most application scenarios

**Example:**
```java
// Device control commands - need to ensure commands are executed, duplicate execution is usually idempotent
mqttPush.push("device/001/command", "restart", MQTTQos.AT_LEAST_ONCE);

// Business notifications - need to ensure notifications are received
mqttPush.push("notification/user/123", "Order shipped", MQTTQos.AT_LEAST_ONCE);
```

### QoS 2 - EXACTLY_ONCE (Exactly Once)

**Characteristics:**
- Guarantees message is delivered exactly once
- No loss, no duplication
- Requires four-way handshake confirmation
- Highest performance overhead

**Use Cases:**
- Financial transactions
- Billing data
- Critical business data
- Operations that cannot be repeated

**Example:**
```java
// Payment transactions - absolutely cannot be duplicated or lost
mqttPush.push("payment/transaction", paymentData, MQTTQos.EXACTLY_ONCE);

// Inventory deduction - cannot be deducted repeatedly
mqttPush.push("inventory/deduct", inventoryData, MQTTQos.EXACTLY_ONCE);
```

## QoS Selection Guidelines

### Performance Comparison

| QoS Level | Network Overhead | CPU Overhead | Memory Overhead | Reliability |
|-----------|------------------|--------------|-----------------|-------------|
| QoS 0 | Lowest | Lowest | Lowest | Lowest |
| QoS 1 | Medium | Medium | Medium | High |
| QoS 2 | Highest | Highest | Highest | Highest |

### Selection Principles

1. **Default choice QoS 1**: Suitable for most scenarios
2. **High-frequency data use QoS 0**: Such as sensor data, logs
3. **Critical data use QoS 2**: Such as transactions, billing data
4. **Consider network environment**: Unstable networks recommend higher QoS
5. **Consider business characteristics**: Idempotent operations can use QoS 1

### Practical Application Example

```java
@Service
public class SmartHomeService {
    
    private MqttPush mqttPush = new MqttPush();
    
    // Sensor data - QoS 0
    public void reportSensorData(String sensorId, double value) {
        String topic = "sensor/" + sensorId + "/data";
        String message = String.valueOf(value);
        mqttPush.push(topic, message, MQTTQos.AT_MOST_ONCE);
    }
    
    // Device control - QoS 1
    public void controlDevice(String deviceId, String command) {
        String topic = "device/" + deviceId + "/command";
        mqttPush.push(topic, command, MQTTQos.AT_LEAST_ONCE);
    }
    
    // Security alerts - QoS 2
    public void sendSecurityAlert(String alertData) {
        mqttPush.push("security/alert", alertData, MQTTQos.EXACTLY_ONCE);
    }
    
    // User notifications - QoS 1
    public void sendNotification(String userId, String message) {
        String topic = "notification/" + userId;
        mqttPush.push(topic, message, MQTTQos.AT_LEAST_ONCE);
    }
}
```

## Important Notes

1. **Publisher and Subscriber QoS**: The actual QoS level is the smaller value set by publisher and subscriber
2. **Network Impact**: In unstable network environments, higher QoS levels are more important
3. **Performance Trade-off**: Higher QoS levels mean greater performance overhead
4. **Duplicate Processing**: QoS 1 may cause duplicate messages, need to handle at business layer
5. **Session State**: QoS 1 and QoS 2 need to consider CleanSession settings

```java
// Example of handling duplicate messages
@Component
public class DuplicateMessageHandler {
    
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();
    
    @PostConstruct
    public void init() {
        MqttPut.of("business/orders")
            .qos(MQTTQos.AT_LEAST_ONCE)
            .response(this::handleOrderMessage)
            .start();
    }
    
    private void handleOrderMessage(String topic, String message) {
        // Use message ID or business ID for deduplication
        String messageId = extractMessageId(message);
        
        if (processedMessages.contains(messageId)) {
            log.debug("Ignoring duplicate message: {}", messageId);
            return;
        }
        
        processedMessages.add(messageId);
        processOrder(message);
        
        // Periodically clean up processed message IDs
        if (processedMessages.size() > 10000) {
            cleanupOldMessages();
        }
    }
}
```