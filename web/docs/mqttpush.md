# MqttPush 消息发布

`MqttPush` 是用于发布 MQTT 消息的核心工具类，支持同步和异步发布，提供丰富的配置选项。

## 基础用法

```java
// 最简单的用法
MqttPush mqttPush = new MqttPush();
mqttPush.push("device/status", "online", MQTTQos.AT_LEAST_ONCE);

// 带回调的发布
mqttPush.push("sensor/temperature", "25.6°C", 
    MQTTQos.AT_LEAST_ONCE,
    token -> System.out.println("发送成功"),
    (token, error) -> System.err.println("发送失败: " + error)
);
```

## 构造方法

| 构造方法 | 说明 |
|----------|------|
| `new MqttPush()` | 使用默认配置创建实例 |
| `new MqttPush.builder()` | 使用建造者模式创建实例 |

## 连接配置方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `host(String host)` | MQTT服务器地址 | `MqttPush` | 设置MQTT服务器地址 |
| `username(String username)` | 用户名 | `MqttPush` | 设置连接用户名 |
| `password(String password)` | 密码 | `MqttPush` | 设置连接密码 |
| `timeout(Integer timeout)` | 超时时间(毫秒) | `MqttPush` | 设置连接超时时间 |
| `keepalive(Integer keepalive)` | 心跳间隔(秒) | `MqttPush` | 设置心跳检测间隔 |
| `serviceId(String serviceId)` | 服务ID | `MqttPush` | 设置客户端ID |
| `cleanSession(boolean cleanSession)` | 是否清理会话 | `MqttPush` | 设置会话清理标志 |

## 连接管理方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `start()` | 无 | `void` | 初始化配置并开启连接，可能抛出MqttException |
| `init()` | 无 | `MqttPush` | 初始化配置并开启连接，异常转为RuntimeException |
| `init(Consumer<Exception> error)` | 错误回调函数 | `MqttPush` | 初始化连接，出错时调用回调函数 |
| `stop()` | 无 | `void` | 关闭MQTT连接 |

## 消息发布方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `foundTopic(String topic)` | 主题名称 | 预先创建主题对象，提高发布性能 |
| `push(String topic, String message, MQTTQos qos)` | 主题, 消息, QoS等级 | 同步发布消息 |
| `push(String topic, String message, MQTTQos qos, Consumer<IMqttToken> success, BiConsumer<IMqttToken, Throwable> failure)` | 主题, 消息, QoS等级, 成功回调, 失败回调 | 异步发布消息，带成功和失败回调 |

## Builder 模式方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `builder.host(String host)` | MQTT服务器地址 | `builder` | 设置服务器地址 |
| `builder.username(String username)` | 用户名 | `builder` | 设置用户名 |
| `builder.password(String password)` | 密码 | `builder` | 设置密码 |
| `builder.timeout(Integer timeout)` | 超时时间 | `builder` | 设置超时时间 |
| `builder.keepalive(Integer keepalive)` | 心跳间隔 | `builder` | 设置心跳间隔 |
| `builder.serviceId(String serviceId)` | 服务ID | `builder` | 设置客户端ID |
| `builder.cleanSession(boolean clean)` | 是否清理会话 | `builder` | 设置会话清理标志 |
| `builder.build()` | 无 | `MqttPush` | 构建MqttPush实例 |

## 使用示例

### 基础发布示例

```java
@Service
public class DeviceService {
    
    private MqttPush mqttPush = new MqttPush();
    
    // 简单发布
    public void reportDeviceStatus(String deviceId, String status) {
        String topic = "device/" + deviceId + "/status";
        mqttPush.push(topic, status, MQTTQos.AT_LEAST_ONCE);
    }
    
    // 带回调的发布
    public void sendCriticalAlert(String message) {
        mqttPush.push("alerts/critical", message, MQTTQos.EXACTLY_ONCE,
            token -> log.info("告警发送成功: {}", token.getMessageId()),
            (token, error) -> log.error("告警发送失败", error)
        );
    }
}
```

### Builder 模式示例

```java
// 使用Builder模式创建自定义配置的MqttPush
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
        log.error("MQTT连接失败", error);
    });

// 发布关键业务数据
customPush.push("business/orders", orderJson, 
    MQTTQos.EXACTLY_ONCE,
    token -> log.info("订单数据发送成功"),
    (token, error) -> handlePublishError("订单发送失败", error)
);
```

### 链式配置示例

```java
// 链式配置
MqttPush mqttPush = new MqttPush()
    .host("tcp://iot.example.com:1883")
    .username("iot-publisher")
    .password("secure-password")
    .timeout(10000)
    .keepalive(60)
    .serviceId("iot-device-001")
    .cleanSession(false);

// 启动连接
mqttPush.start();

// 预创建主题以提高性能
mqttPush.foundTopic("sensor/temperature");
mqttPush.foundTopic("sensor/humidity");

// 发布消息
mqttPush.push("sensor/temperature", "25.6", MQTTQos.AT_LEAST_ONCE);
mqttPush.push("sensor/humidity", "60.2", MQTTQos.AT_LEAST_ONCE);
```

### Spring Boot 集成示例

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
            token -> log.info("生产环境消息发送成功"),
            (token, error) -> log.error("生产环境消息发送失败", error)
        );
    }
}
```

### 批量发布示例

```java
@Service
public class BatchPublisher {
    
    private MqttPush mqttPush = new MqttPush();
    
    public void publishSensorData(List<SensorData> dataList) {
        // 预创建主题
        Set<String> topics = dataList.stream()
            .map(data -> "sensor/" + data.getSensorId() + "/data")
            .collect(Collectors.toSet());
        
        topics.forEach(mqttPush::foundTopic);
        
        // 批量发布
        dataList.forEach(data -> {
            String topic = "sensor/" + data.getSensorId() + "/data";
            String message = JSON.toJSONString(data);
            
            mqttPush.push(topic, message, MQTTQos.AT_LEAST_ONCE,
                token -> log.debug("传感器数据发送成功: {}", data.getSensorId()),
                (token, error) -> {
                    log.error("传感器数据发送失败: {}", data.getSensorId(), error);
                    // 可以实现重试逻辑
                    retryPublish(topic, message);
                }
            );
        });
    }
    
    private void retryPublish(String topic, String message) {
        // 实现重试逻辑
        CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
            .execute(() -> {
                mqttPush.push(topic, message, MQTTQos.AT_LEAST_ONCE);
            });
    }
}
```

### 高性能发布示例

```java
@Service
public class HighPerformancePublisher {
    
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private final MqttPush mqttPush = new MqttPush();
    private final BlockingQueue<MqttMessage> messageQueue = 
        new LinkedBlockingQueue<>(10000);
    
    @PostConstruct
    public void init() {
        mqttPush.init();
        startBatchProcessor();
    }
    
    public void publishAsync(String topic, String message) {
        MqttMessage mqttMessage = new MqttMessage();
        mqttMessage.setTopic(topic);
        mqttMessage.setPayload(message);
        
        if (!messageQueue.offer(mqttMessage)) {
            log.warn("消息队列已满，丢弃消息: {}", topic);
        }
    }
    
    private void startBatchProcessor() {
        executor.submit(() -> {
            List<MqttMessage> batch = new ArrayList<>();
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 批量收集消息
                    MqttMessage message = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        batch.add(message);
                        messageQueue.drainTo(batch, 99); // 最多100条一批
                        
                        // 批量发布
                        publishBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
    
    private void publishBatch(List<MqttMessage> batch) {
        batch.parallelStream().forEach(msg -> {
            mqttPush.push(msg.getTopic(), msg.getPayload(), 
                         MQTTQos.AT_LEAST_ONCE);
        });
    }
}
```

## 最佳实践

1. **连接复用**：尽量复用 MqttPush 实例，避免频繁创建连接
2. **预创建主题**：对于频繁发布的主题，使用 `foundTopic()` 预创建
3. **合理设置QoS**：根据业务需求选择合适的QoS等级
4. **异步处理**：对于高频发布场景，使用异步回调避免阻塞
5. **错误处理**：实现重试机制和错误日志记录
6. **资源管理**：应用关闭时记得调用 `stop()` 方法释放连接

```java
@PreDestroy
public void cleanup() {
    try {
        mqttPush.stop();
    } catch (Exception e) {
        log.warn("关闭MQTT连接失败", e);
    }
}
```