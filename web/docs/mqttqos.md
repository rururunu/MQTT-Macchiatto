# MQTTQos 质量等级

`MQTTQos` 是定义 MQTT 消息质量等级的枚举类，提供三种不同的消息传递保证级别。

## 枚举值详解

| 枚举值 | 数值 | 说明 | 使用场景 |
|--------|------|------|----------|
| `AT_MOST_ONCE` | 0 | 最多一次，消息可能丢失，但不会重复 | 对消息丢失不敏感的场景，如传感器数据 |
| `AT_LEAST_ONCE` | 1 | 至少一次，消息不会丢失，但可能重复 | 大多数应用场景的推荐选择 |
| `EXACTLY_ONCE` | 2 | 恰好一次，消息不会丢失也不会重复 | 对消息准确性要求极高的场景，如金融交易 |

## 方法详解

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getValue()` | `int` | 获取QoS等级的数值 |

## 使用示例

### 在消息发布中使用

```java
// QoS 0 - 适用于高频率、可容忍丢失的数据
mqttPush.push("sensor/data", "temperature:25.6", MQTTQos.AT_MOST_ONCE);

// QoS 1 - 大多数业务场景的推荐选择
mqttPush.push("device/command", "restart", MQTTQos.AT_LEAST_ONCE);

// QoS 2 - 关键业务数据
mqttPush.push("payment/transaction", transactionData, MQTTQos.EXACTLY_ONCE);
```

### 在消息监听中使用

```java
// 设置监听器的QoS等级
MqttPut.of("critical/alerts")
    .qos(MQTTQos.EXACTLY_ONCE)
    .response((topic, message) -> {
        handleCriticalAlert(message);
    })
    .start();
```

### 获取数值

```java
// 获取QoS等级的数值
int qosValue = MQTTQos.AT_LEAST_ONCE.getValue(); // 返回 1

// 在MqttMessage中使用
MqttMessage message = new MqttMessage();
message.setQos(MQTTQos.EXACTLY_ONCE.getValue());
message.setPayload("重要数据".getBytes());
```

## QoS 等级详细说明

### QoS 0 - AT_MOST_ONCE（最多一次）

**特点：**
- 消息发送后不等待确认
- 可能丢失消息
- 不会重复发送
- 性能最高，开销最小

**适用场景：**
- 传感器数据采集（温度、湿度等）
- 日志数据
- 状态更新（频繁更新的状态）
- 对实时性要求高，对丢失容忍的场景

**示例：**
```java
// 传感器数据上报 - 即使丢失一两条也不影响整体趋势
mqttPush.push("sensor/room1/temperature", "25.6", MQTTQos.AT_MOST_ONCE);
mqttPush.push("sensor/room1/humidity", "60.2", MQTTQos.AT_MOST_ONCE);
```

### QoS 1 - AT_LEAST_ONCE（至少一次）

**特点：**
- 保证消息至少被传递一次
- 可能会重复接收消息
- 需要确认机制
- 性能和可靠性的平衡

**适用场景：**
- 设备控制命令
- 业务通知
- 一般的数据传输
- 大多数应用场景

**示例：**
```java
// 设备控制命令 - 需要确保命令被执行，重复执行通常是幂等的
mqttPush.push("device/001/command", "restart", MQTTQos.AT_LEAST_ONCE);

// 业务通知 - 需要确保通知被接收
mqttPush.push("notification/user/123", "订单已发货", MQTTQos.AT_LEAST_ONCE);
```

### QoS 2 - EXACTLY_ONCE（恰好一次）

**特点：**
- 保证消息恰好被传递一次
- 不会丢失，也不会重复
- 需要四次握手确认
- 性能开销最大

**适用场景：**
- 金融交易
- 计费数据
- 关键业务数据
- 不能重复执行的操作

**示例：**
```java
// 支付交易 - 绝对不能重复或丢失
mqttPush.push("payment/transaction", paymentData, MQTTQos.EXACTLY_ONCE);

// 库存扣减 - 不能重复扣减
mqttPush.push("inventory/deduct", inventoryData, MQTTQos.EXACTLY_ONCE);
```

## QoS 选择建议

### 性能对比

| QoS等级 | 网络开销 | CPU开销 | 内存开销 | 可靠性 |
|---------|----------|---------|----------|--------|
| QoS 0 | 最低 | 最低 | 最低 | 最低 |
| QoS 1 | 中等 | 中等 | 中等 | 高 |
| QoS 2 | 最高 | 最高 | 最高 | 最高 |

### 选择原则

1. **默认选择 QoS 1**：适用于大多数场景
2. **高频数据用 QoS 0**：如传感器数据、日志
3. **关键数据用 QoS 2**：如交易、计费数据
4. **考虑网络环境**：不稳定网络建议使用更高QoS
5. **考虑业务特性**：幂等操作可以使用 QoS 1

### 实际应用示例

```java
@Service
public class SmartHomeService {
    
    private MqttPush mqttPush = new MqttPush();
    
    // 传感器数据 - QoS 0
    public void reportSensorData(String sensorId, double value) {
        String topic = "sensor/" + sensorId + "/data";
        String message = String.valueOf(value);
        mqttPush.push(topic, message, MQTTQos.AT_MOST_ONCE);
    }
    
    // 设备控制 - QoS 1
    public void controlDevice(String deviceId, String command) {
        String topic = "device/" + deviceId + "/command";
        mqttPush.push(topic, command, MQTTQos.AT_LEAST_ONCE);
    }
    
    // 安全告警 - QoS 2
    public void sendSecurityAlert(String alertData) {
        mqttPush.push("security/alert", alertData, MQTTQos.EXACTLY_ONCE);
    }
    
    // 用户通知 - QoS 1
    public void sendNotification(String userId, String message) {
        String topic = "notification/" + userId;
        mqttPush.push(topic, message, MQTTQos.AT_LEAST_ONCE);
    }
}
```

## 注意事项

1. **发布者和订阅者QoS**：实际的QoS等级是发布者和订阅者设置的较小值
2. **网络影响**：不稳定的网络环境下，高QoS等级更重要
3. **性能权衡**：QoS等级越高，性能开销越大
4. **重复处理**：QoS 1 可能导致重复消息，需要在业务层面处理
5. **会话状态**：QoS 1 和 QoS 2 需要考虑 CleanSession 设置

```java
// 处理重复消息的示例
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
        // 使用消息ID或业务ID去重
        String messageId = extractMessageId(message);
        
        if (processedMessages.contains(messageId)) {
            log.debug("忽略重复消息: {}", messageId);
            return;
        }
        
        processedMessages.add(messageId);
        processOrder(message);
        
        // 定期清理已处理的消息ID
        if (processedMessages.size() > 10000) {
            cleanupOldMessages();
        }
    }
}
```