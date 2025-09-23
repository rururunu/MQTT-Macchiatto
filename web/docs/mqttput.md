# MqttPut 消息监听

`MqttPut` 是用于接收 MQTT 消息的核心工具类，提供了简洁的链式调用API。

## 基础用法

```java
// 最简单的用法
MqttPut.of("device/status")
    .response((topic, message) -> {
        System.out.println("设备状态: " + message);
    })
    .start();

// 只关心消息内容，不关心主题
MqttPut.of("sensor/data")
    .response(message -> {
        System.out.println("传感器数据: " + message);
    })
    .start();
```

## 静态工厂方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `of()` | 无 | `MqttPut` | 创建新的 MqttPut 实例 |
| `of(String topic)` | 主题名称 | `MqttPut` | 创建并设置主题的 MqttPut 实例 |

## 连接配置方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `host(String host)` | MQTT服务器地址 | 设置自定义的MQTT服务器地址 |
| `username(String username)` | 用户名 | 设置连接用户名 |
| `password(String password)` | 密码 | 设置连接密码 |
| `timeout(int timeout)` | 超时时间(毫秒) | 设置连接超时时间 |
| `keepalive(int keepalive)` | 心跳间隔(秒) | 设置心跳检测间隔 |
| `cleanSession(boolean clean)` | 是否清理会话 | 设置会话清理标志 |
| `reconnectFrequencyMs(int ms)` | 重连间隔(毫秒) | 设置自动重连间隔 |
| `qos(MQTTQos qos)` | QoS等级 | 设置消息质量等级 |

## 主题和客户端配置

| 方法 | 参数 | 说明 |
|------|------|------|
| `setTopic(String topic)` | 主题名称 | 设置订阅的主题 |
| `setServiceId(String serviceId)` | 服务ID | 设置客户端ID |
| `clientId(String clientId)` | 客户端ID | 设置客户端ID（同setServiceId） |
| `setCleanSession(boolean clean)` | 是否清理会话 | 设置会话清理标志 |

## 消息响应方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `response(Consumer<String> consumer)` | 消息处理函数 | 设置消息处理回调，只接收消息内容 |
| `response(BiConsumer<String, String> biConsumer)` | 主题和消息处理函数 | 设置消息处理回调，接收主题和消息内容 |
| `response(String clientId, Consumer<String> consumer)` | 客户端ID, 消息处理函数 | 指定客户端ID的消息处理回调 |
| `response(String clientId, BiConsumer<String, MqttMessage> biConsumer)` | 客户端ID, 消息处理函数 | 指定客户端ID，接收消息内容和MqttMessage对象 |

## 控制方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `start()` | 无 | 开启MQTT连接和消息监听 |
| `stop()` | 无 | 停止MQTT连接 |

## 使用示例

### 基础监听示例

```java
// 监听单个主题
MqttPut.of("device/status")
    .response((topic, message) -> {
        System.out.println("设备状态更新: " + message);
    })
    .start();

// 使用通配符监听多个主题
MqttPut.of("sensor/+/data")
    .response((topic, message) -> {
        String[] parts = topic.split("/");
        String sensorId = parts[1];
        System.out.println("传感器 " + sensorId + " 数据: " + message);
    })
    .start();
```

### 自定义配置示例

```java
// 连接到自定义MQTT服务器
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

### Spring Boot 集成示例

```java
@Component
public class DeviceMessageListener {
    
    @Autowired
    private DeviceService deviceService;
    
    @PostConstruct
    public void initListeners() {
        // 监听设备状态
        MqttPut.of("device/+/status")
            .setServiceId("device-status-listener")
            .setCleanSession(false)
            .response(this::handleDeviceStatus)
            .start();
        
        // 监听传感器数据
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

### 消息路由示例

```java
@Component
public class MessageRouter {
    
    @PostConstruct
    public void initRouter() {
        MqttPut.of("system/+/+")
            .response(this::routeMessage)
            .start();
    }
    
    private void routeMessage(String topic, String message) {
        String[] parts = topic.split("/");
        if (parts.length >= 3) {
            String category = parts[1];
            String type = parts[2];
            
            switch (category) {
                case "device":
                    handleDeviceMessage(type, message);
                    break;
                case "sensor":
                    handleSensorMessage(type, message);
                    break;
                case "alert":
                    handleAlertMessage(type, message);
                    break;
                default:
                    log.warn("未知消息类别: {}", category);
            }
        }
    }
}
```

## 主题通配符

MQTT 支持两种通配符：

- `+`：单级通配符，匹配一个层级
- `#`：多级通配符，匹配多个层级

示例：
- `sensor/+/temperature` 可以匹配 `sensor/room1/temperature`、`sensor/room2/temperature` 等
- `sensor/#` 可以匹配 `sensor/room1/temperature`、`sensor/room1/humidity`、`sensor/room2/data/current` 等

## 最佳实践

1. **使用有意义的客户端ID**：避免客户端ID冲突
2. **合理设置QoS等级**：根据业务需求选择合适的QoS
3. **处理连接异常**：监听器会自动重连，但要处理好业务逻辑
4. **避免阻塞操作**：在消息处理回调中避免长时间阻塞操作
5. **使用线程池**：对于耗时的消息处理，考虑使用异步处理

```java
@Component
public class AsyncMessageListener {
    
    @Autowired
    private TaskExecutor taskExecutor;
    
    @PostConstruct
    public void init() {
        MqttPut.of("heavy/processing")
            .response((topic, message) -> {
                // 异步处理消息
                taskExecutor.execute(() -> {
                    processHeavyMessage(message);
                });
            })
            .start();
    }
}
```