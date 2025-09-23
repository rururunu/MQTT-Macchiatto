# MQTTMonitor 监听器

`MQTTMonitor` 是底层的 MQTT 监听器类，提供更细粒度的控制和自定义回调处理。

## 基础用法

```java
MQTTMonitor monitor = new MQTTMonitor();
monitor.setClientId("my-monitor-client");
monitor.setCleanSession(false);
monitor.setQos(MQTTQos.AT_LEAST_ONCE);

monitor.setMqttCallback(new MqttCallback() {
    @Override
    public void connectionLost(Throwable cause) {
        System.err.println("连接丢失，正在重连...");
        monitor.reconnect();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload());
        System.out.println("收到消息: " + topic + " -> " + payload);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        System.out.println("消息投递完成");
    }
});

monitor.start("system/events");
```

## 连接配置方法

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `host(String host)` | MQTT服务器地址 | `MQTTMonitor` | 设置MQTT服务器地址 |
| `username(String username)` | 用户名 | `MQTTMonitor` | 设置连接用户名 |
| `password(String password)` | 密码 | `MQTTMonitor` | 设置连接密码 |
| `timeout(Integer timeout)` | 超时时间(毫秒) | `MQTTMonitor` | 设置连接超时时间 |
| `keepalive(Integer keepalive)` | 心跳间隔(秒) | `MQTTMonitor` | 设置心跳检测间隔 |
| `reconnectFrequencyMs(Integer ms)` | 重连间隔(毫秒) | `MQTTMonitor` | 设置自动重连间隔 |

## 客户端配置方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `setClientId(String clientId)` | 客户端ID | 设置MQTT客户端ID |
| `setTopic(String topic)` | 主题名称 | 设置订阅的主题 |
| `setQos(MQTTQos qos)` | QoS等级 | 设置消息质量等级 |
| `setCleanSession(boolean clean)` | 是否清理会话 | 设置会话清理标志 |
| `setMqttCallback(MqttCallback callback)` | 回调处理器 | 设置MQTT事件回调处理器 |

## 控制方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `start(String topic)` | 订阅主题 | 开启MQTT监听，订阅指定主题 |
| `reconnect()` | 无 | 手动触发重连 |

## 获取器方法

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getClientId()` | `String` | 获取客户端ID |
| `getTopic()` | `String` | 获取订阅的主题 |
| `getQos()` | `MQTTQos` | 获取QoS等级 |
| `getClient()` | `MqttClient` | 获取底层MQTT客户端 |
| `getMqttCallback()` | `MqttCallback` | 获取回调处理器 |
| `isCleanSession()` | `boolean` | 获取会话清理标志 |

## 使用示例

### 高级监听示例

```java
@Component
public class AdvancedMqttListener {
    
    private MQTTMonitor monitor;
    
    @PostConstruct
    public void initMonitor() {
        monitor = new MQTTMonitor();
        monitor.setClientId("advanced-listener-" + UUID.randomUUID());
        monitor.setCleanSession(false);
        monitor.setQos(MQTTQos.EXACTLY_ONCE);
        
        // 自定义连接配置
        monitor.host("tcp://advanced-mqtt.company.com:1883")
               .username("advanced-user")
               .password("advanced-password")
               .timeout(20000)
               .keepalive(180)
               .reconnectFrequencyMs(2000);
        
        monitor.setMqttCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.error("MQTT连接丢失", cause);
                // 自动重连
                monitor.reconnect();
                // 发送告警
                sendConnectionLostAlert(cause);
            }
            
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                try {
                    String payload = new String(message.getPayload());
                    log.info("收到消息: {} -> {}", topic, payload);
                    
                    // 根据主题路由消息
                    routeMessage(topic, payload, message);
                } catch (Exception e) {
                    log.error("处理消息失败: {}", topic, e);
                }
            }
            
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                log.debug("消息投递完成: {}", token.getMessageId());
            }
        });
        
        // 启动监听
        monitor.start("system/+/events");
    }
    
    private void routeMessage(String topic, String payload, MqttMessage message) {
        if (topic.contains("error")) {
            handleErrorMessage(payload, message);
        } else if (topic.contains("warning")) {
            handleWarningMessage(payload, message);
        } else if (topic.contains("info")) {
            handleInfoMessage(payload, message);
        }
    }
    
    private void sendConnectionLostAlert(Throwable cause) {
        // 发送连接丢失告警
        AlertMessage alert = new AlertMessage();
        alert.setLevel("CRITICAL");
        alert.setMessage("MQTT连接丢失: " + cause.getMessage());
        alert.setTimestamp(System.currentTimeMillis());
        
        alertService.sendAlert(alert);
    }
}
```

### 多主题监听示例

```java
@Service
public class MultiTopicListener {
    
    private final List<MQTTMonitor> monitors = new ArrayList<>();
    
    @PostConstruct
    public void initMonitors() {
        // 监听设备状态
        createMonitor("device-status-monitor", "device/+/status", this::handleDeviceStatus);
        
        // 监听传感器数据
        createMonitor("sensor-data-monitor", "sensor/+/data", this::handleSensorData);
        
        // 监听系统告警
        createMonitor("system-alert-monitor", "alert/+/+", this::handleSystemAlert);
    }
    
    private void createMonitor(String clientId, String topic, BiConsumer<String, String> handler) {
        MQTTMonitor monitor = new MQTTMonitor();
        monitor.setClientId(clientId);
        monitor.setCleanSession(false);
        monitor.setQos(MQTTQos.AT_LEAST_ONCE);
        
        monitor.setMqttCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("监听器 {} 连接丢失，正在重连...", clientId);
                monitor.reconnect();
            }
            
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload());
                handler.accept(topic, payload);
            }
            
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 监听器通常不需要处理投递完成事件
            }
        });
        
        monitor.start(topic);
        monitors.add(monitor);
    }
    
    private void handleDeviceStatus(String topic, String message) {
        String deviceId = extractDeviceId(topic);
        log.info("设备 {} 状态更新: {}", deviceId, message);
        
        // 更新设备状态到数据库
        deviceService.updateStatus(deviceId, message);
    }
    
    private void handleSensorData(String topic, String message) {
        String sensorId = extractSensorId(topic);
        log.info("传感器 {} 数据: {}", sensorId, message);
        
        // 处理传感器数据
        sensorService.processData(sensorId, message);
    }
    
    private void handleSystemAlert(String topic, String message) {
        log.warn("系统告警: {} -> {}", topic, message);
        
        // 处理系统告警
        alertService.handleAlert(topic, message);
    }
    
    private String extractDeviceId(String topic) {
        return topic.split("/")[1];
    }
    
    private String extractSensorId(String topic) {
        return topic.split("/")[1];
    }
}
```

### 消息持久化示例

```java
@Component
public class MessagePersistenceMonitor {
    
    private MQTTMonitor monitor;
    
    @Autowired
    private MessageRepository messageRepository;
    
    @PostConstruct
    public void init() {
        monitor = new MQTTMonitor();
        monitor.setClientId("message-persistence-monitor");
        monitor.setCleanSession(false);
        monitor.setQos(MQTTQos.EXACTLY_ONCE);
        
        monitor.setMqttCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.error("消息持久化监听器连接丢失", cause);
                monitor.reconnect();
            }
            
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                persistMessage(topic, message);
            }
            
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 不需要处理
            }
        });
        
        // 监听所有需要持久化的消息
        monitor.start("data/+/+");
    }
    
    private void persistMessage(String topic, MqttMessage message) {
        try {
            MessageEntity entity = new MessageEntity();
            entity.setTopic(topic);
            entity.setPayload(new String(message.getPayload()));
            entity.setQos(message.getQos());
            entity.setRetained(message.isRetained());
            entity.setTimestamp(LocalDateTime.now());
            
            messageRepository.save(entity);
            log.debug("消息已持久化: {}", topic);
        } catch (Exception e) {
            log.error("消息持久化失败: {}", topic, e);
        }
    }
}
```

### 健康检查监听器

```java
@Component
public class HealthCheckMonitor {
    
    private MQTTMonitor monitor;
    private final AtomicLong lastHeartbeatTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger messageCount = new AtomicInteger(0);
    
    @PostConstruct
    public void init() {
        monitor = new MQTTMonitor();
        monitor.setClientId("health-check-monitor");
        monitor.setCleanSession(false);
        monitor.setQos(MQTTQos.AT_LEAST_ONCE);
        
        monitor.setMqttCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("健康检查监听器连接丢失", cause);
                monitor.reconnect();
            }
            
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                lastHeartbeatTime.set(System.currentTimeMillis());
                messageCount.incrementAndGet();
                
                String payload = new String(message.getPayload());
                log.debug("收到心跳消息: {} -> {}", topic, payload);
            }
            
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 不需要处理
            }
        });
        
        // 监听心跳消息
        monitor.start("system/heartbeat");
    }
    
    @Scheduled(fixedRate = 30000) // 每30秒检查一次
    public void checkHealth() {
        long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime.get();
        
        if (timeSinceLastHeartbeat > 60000) { // 超过1分钟没有心跳
            log.warn("MQTT系统可能异常，{}ms 未收到心跳消息", timeSinceLastHeartbeat);
            
            // 发送告警
            sendHealthAlert(timeSinceLastHeartbeat);
        }
        
        log.info("MQTT健康状态 - 消息数: {}, 最后心跳: {}ms 前", 
                messageCount.get(), timeSinceLastHeartbeat);
    }
    
    private void sendHealthAlert(long timeSinceLastHeartbeat) {
        HealthAlert alert = new HealthAlert();
        alert.setType("MQTT_HEALTH_CHECK");
        alert.setMessage("MQTT系统心跳异常，" + timeSinceLastHeartbeat + "ms未收到心跳");
        alert.setTimestamp(System.currentTimeMillis());
        
        alertService.sendHealthAlert(alert);
    }
    
    public HealthStatus getHealthStatus() {
        long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime.get();
        
        HealthStatus status = new HealthStatus();
        status.setHealthy(timeSinceLastHeartbeat < 60000);
        status.setLastHeartbeatTime(lastHeartbeatTime.get());
        status.setMessageCount(messageCount.get());
        status.setTimeSinceLastHeartbeat(timeSinceLastHeartbeat);
        
        return status;
    }
}
```

### 消息过滤和路由

```java
@Component
public class MessageFilterMonitor {
    
    private MQTTMonitor monitor;
    private final Map<String, MessageFilter> filters = new HashMap<>();
    
    @PostConstruct
    public void init() {
        // 注册消息过滤器
        registerFilters();
        
        monitor = new MQTTMonitor();
        monitor.setClientId("message-filter-monitor");
        monitor.setCleanSession(false);
        monitor.setQos(MQTTQos.AT_LEAST_ONCE);
        
        monitor.setMqttCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("消息过滤监听器连接丢失", cause);
                monitor.reconnect();
            }
            
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                filterAndRouteMessage(topic, message);
            }
            
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 不需要处理
            }
        });
        
        // 监听所有消息
        monitor.start("system/+/+");
    }
    
    private void registerFilters() {
        // 错误消息过滤器
        filters.put("error", new MessageFilter() {
            @Override
            public boolean accept(String topic, MqttMessage message) {
                return topic.contains("error") || 
                       new String(message.getPayload()).toLowerCase().contains("error");
            }
            
            @Override
            public void process(String topic, MqttMessage message) {
                handleErrorMessage(topic, new String(message.getPayload()));
            }
        });
        
        // 高优先级消息过滤器
        filters.put("priority", new MessageFilter() {
            @Override
            public boolean accept(String topic, MqttMessage message) {
                return topic.contains("priority") || message.getQos() == 2;
            }
            
            @Override
            public void process(String topic, MqttMessage message) {
                handlePriorityMessage(topic, new String(message.getPayload()));
            }
        });
    }
    
    private void filterAndRouteMessage(String topic, MqttMessage message) {
        for (Map.Entry<String, MessageFilter> entry : filters.entrySet()) {
            MessageFilter filter = entry.getValue();
            if (filter.accept(topic, message)) {
                log.debug("消息匹配过滤器: {} -> {}", entry.getKey(), topic);
                filter.process(topic, message);
            }
        }
    }
    
    private void handleErrorMessage(String topic, String message) {
        log.error("收到错误消息: {} -> {}", topic, message);
        // 发送错误告警
        alertService.sendErrorAlert(topic, message);
    }
    
    private void handlePriorityMessage(String topic, String message) {
        log.info("收到高优先级消息: {} -> {}", topic, message);
        // 优先处理
        priorityMessageService.process(topic, message);
    }
    
    interface MessageFilter {
        boolean accept(String topic, MqttMessage message);
        void process(String topic, MqttMessage message);
    }
}
```

## 最佳实践

1. **合理设置客户端ID**：使用唯一的客户端ID避免冲突
2. **实现重连逻辑**：在 `connectionLost` 回调中实现自动重连
3. **异常处理**：在 `messageArrived` 中捕获并处理异常
4. **资源管理**：应用关闭时正确释放资源
5. **日志记录**：记录连接状态和消息处理情况
6. **监控告警**：实现连接状态监控和异常告警

```java
@PreDestroy
public void cleanup() {
    if (monitor != null && monitor.getClient() != null) {
        try {
            monitor.getClient().disconnect();
            log.info("MQTT监听器已关闭");
        } catch (Exception e) {
            log.warn("关闭MQTT监听器失败", e);
        }
    }
}
```