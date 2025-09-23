# MQTTMonitor Listener

`MQTTMonitor` is a low-level MQTT listener class that provides fine-grained control and custom callback handling.

## Basic Usage

```java
MQTTMonitor monitor = new MQTTMonitor();
monitor.setClientId("my-monitor-client");
monitor.setCleanSession(false);
monitor.setQos(MQTTQos.AT_LEAST_ONCE);

monitor.setMqttCallback(new MqttCallback() {
    @Override
    public void connectionLost(Throwable cause) {
        System.err.println("Connection lost, reconnecting...");
        monitor.reconnect();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload());
        System.out.println("Message received: " + topic + " -> " + payload);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        System.out.println("Message delivery complete");
    }
});

monitor.start("system/events");
```

## Connection Configuration Methods

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `host(String host)` | MQTT server address | `MQTTMonitor` | Set MQTT server address |
| `username(String username)` | Username | `MQTTMonitor` | Set connection username |
| `password(String password)` | Password | `MQTTMonitor` | Set connection password |
| `timeout(Integer timeout)` | Timeout (milliseconds) | `MQTTMonitor` | Set connection timeout |
| `keepalive(Integer keepalive)` | Heartbeat interval (seconds) | `MQTTMonitor` | Set heartbeat detection interval |
| `reconnectFrequencyMs(Integer ms)` | Reconnection interval (milliseconds) | `MQTTMonitor` | Set auto-reconnection interval |

## Client Configuration Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `setClientId(String clientId)` | Client ID | Set MQTT client ID |
| `setTopic(String topic)` | Topic name | Set subscription topic |
| `setQos(MQTTQos qos)` | QoS level | Set message quality level |
| `setCleanSession(boolean clean)` | Whether to clean session | Set session cleanup flag |
| `setMqttCallback(MqttCallback callback)` | Callback handler | Set MQTT event callback handler |

## Control Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `start(String topic)` | Subscription topic | Start MQTT listening, subscribe to specified topic |
| `reconnect()` | None | Manually trigger reconnection |

## Getter Methods

| Method | Return Value | Description |
|--------|--------------|-------------|
| `getClientId()` | `String` | Get client ID |
| `getTopic()` | `String` | Get subscription topic |
| `getQos()` | `MQTTQos` | Get QoS level |
| `getClient()` | `MqttClient` | Get underlying MQTT client |
| `getMqttCallback()` | `MqttCallback` | Get callback handler |
| `isCleanSession()` | `boolean` | Get session cleanup flag |

## Usage Examples

### Advanced Listening Example

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
        
        // Custom connection configuration
        monitor.host("tcp://advanced-mqtt.company.com:1883")
               .username("advanced-user")
               .password("advanced-password")
               .timeout(20000)
               .keepalive(180)
               .reconnectFrequencyMs(2000);
        
        monitor.setMqttCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.error("MQTT connection lost", cause);
                // Auto reconnect
                monitor.reconnect();
                // Send alert
                sendConnectionLostAlert(cause);
            }
            
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                try {
                    String payload = new String(message.getPayload());
                    log.info("Message received: {} -> {}", topic, payload);
                    
                    // Route message based on topic
                    routeMessage(topic, payload, message);
                } catch (Exception e) {
                    log.error("Message processing failed: {}", topic, e);
                }
            }
            
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                log.debug("Message delivery complete: {}", token.getMessageId());
            }
        });
        
        // Start listening
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
        // Send connection lost alert
        AlertMessage alert = new AlertMessage();
        alert.setLevel("CRITICAL");
        alert.setMessage("MQTT connection lost: " + cause.getMessage());
        alert.setTimestamp(System.currentTimeMillis());
        
        alertService.sendAlert(alert);
    }
}
```

### Multi-Topic Listening Example

```java
@Service
public class MultiTopicListener {
    
    private final List<MQTTMonitor> monitors = new ArrayList<>();
    
    @PostConstruct
    public void initMonitors() {
        // Listen to device status
        createMonitor("device-status-monitor", "device/+/status", this::handleDeviceStatus);
        
        // Listen to sensor data
        createMonitor("sensor-data-monitor", "sensor/+/data", this::handleSensorData);
        
        // Listen to system alerts
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
                log.warn("Monitor {} connection lost, reconnecting...", clientId);
                monitor.reconnect();
            }
            
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload());
                handler.accept(topic, payload);
            }
            
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Listeners usually don't need to handle delivery complete events
            }
        });
        
        monitor.start(topic);
        monitors.add(monitor);
    }
    
    private void handleDeviceStatus(String topic, String message) {
        String deviceId = extractDeviceId(topic);
        log.info("Device {} status update: {}", deviceId, message);
        
        // Update device status to database
        deviceService.updateStatus(deviceId, message);
    }
    
    private void handleSensorData(String topic, String message) {
        String sensorId = extractSensorId(topic);
        log.info("Sensor {} data: {}", sensorId, message);
        
        // Process sensor data
        sensorService.processData(sensorId, message);
    }
    
    private void handleSystemAlert(String topic, String message) {
        log.warn("System alert: {} -> {}", topic, message);
        
        // Handle system alert
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

### Health Check Monitor

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
                log.warn("Health check monitor connection lost", cause);
                monitor.reconnect();
            }
            
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                lastHeartbeatTime.set(System.currentTimeMillis());
                messageCount.incrementAndGet();
                
                String payload = new String(message.getPayload());
                log.debug("Heartbeat message received: {} -> {}", topic, payload);
            }
            
            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Not needed
            }
        });
        
        // Listen to heartbeat messages
        monitor.start("system/heartbeat");
    }
    
    @Scheduled(fixedRate = 30000) // Check every 30 seconds
    public void checkHealth() {
        long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime.get();
        
        if (timeSinceLastHeartbeat > 60000) { // No heartbeat for more than 1 minute
            log.warn("MQTT system may be abnormal, no heartbeat received for {}ms", timeSinceLastHeartbeat);
            
            // Send alert
            sendHealthAlert(timeSinceLastHeartbeat);
        }
        
        log.info("MQTT health status - Message count: {}, Last heartbeat: {}ms ago", 
                messageCount.get(), timeSinceLastHeartbeat);
    }
    
    private void sendHealthAlert(long timeSinceLastHeartbeat) {
        HealthAlert alert = new HealthAlert();
        alert.setType("MQTT_HEALTH_CHECK");
        alert.setMessage("MQTT system heartbeat abnormal, no heartbeat for " + timeSinceLastHeartbeat + "ms");
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

## Best Practices

1. **Reasonable Client ID**: Use unique client IDs to avoid conflicts
2. **Implement Reconnection Logic**: Implement auto-reconnection in `connectionLost` callback
3. **Exception Handling**: Catch and handle exceptions in `messageArrived`
4. **Resource Management**: Properly release resources when application shuts down
5. **Logging**: Log connection status and message processing
6. **Monitoring and Alerting**: Implement connection status monitoring and exception alerting

```java
@PreDestroy
public void cleanup() {
    if (monitor != null && monitor.getClient() != null) {
        try {
            monitor.getClient().disconnect();
            log.info("MQTT monitor closed");
        } catch (Exception e) {
            log.warn("Failed to close MQTT monitor", e);
        }
    }
}
```