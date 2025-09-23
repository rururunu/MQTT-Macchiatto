# MQTTReport 消息上报

`MQTTReport` 是专门用于消息上报的工具类，提供更底层的消息发布控制。

## 基础用法

```java
// 创建连接
MQTTReport reporter = new MQTTReport();
reporter.setTopic("system/metrics");
reporter.setServiceId("metrics-reporter");
reporter.setCleanSession(false);
reporter.start();

// 发送消息
reporter.getMessage().setQos(MQTTQos.AT_LEAST_ONCE.getValue());
reporter.getMessage().setPayload("系统指标数据".getBytes());
reporter.publish(reporter.getMqttTopic(), reporter.getMessage());
```

## 构造方法

| 构造方法 | 参数 | 说明 |
|----------|------|------|
| `new MQTTReport()` | 无 | 使用默认配置创建实例 |
| `new MQTTReport(String topic, String serviceId)` | 主题名称, 服务ID | 创建并设置主题和服务ID的实例 |

## 配置方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `setServiceId(String serviceId)` | 服务ID | 设置客户端ID |
| `setTopic(String topic)` | 主题名称 | 设置发布主题 |
| `setCleanSession(boolean clean)` | 是否清理会话 | 设置会话清理标志 |

## 连接和发布方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `start()` | 无 | 开启MQTT连接 |
| `publish(MqttTopic topic, MqttMessage message)` | MQTT主题对象, MQTT消息对象 | 发布消息到指定主题 |

## 获取器方法

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getServiceId()` | `String` | 获取服务ID |
| `getTopic()` | `String` | 获取主题名称 |
| `getClient()` | `MqttClient` | 获取MQTT客户端 |
| `getMqttTopic()` | `MqttTopic` | 获取MQTT主题对象 |
| `getMessage()` | `MqttMessage` | 获取MQTT消息对象 |
| `isCleanSession()` | `boolean` | 获取会话清理标志 |

## 使用示例

### 系统指标上报示例

```java
@Component
public class SystemMetricsReporter {
    
    private MQTTReport reporter;
    
    @PostConstruct
    public void initReporter() {
        try {
            reporter = new MQTTReport("system/metrics", "metrics-reporter");
            reporter.setCleanSession(false);
            reporter.start();
        } catch (MqttException e) {
            log.error("初始化MQTT上报器失败", e);
        }
    }
    
    @Scheduled(fixedRate = 60000) // 每分钟上报一次
    public void reportSystemMetrics() {
        try {
            SystemMetrics metrics = collectSystemMetrics();
            String metricsJson = JSON.toJSONString(metrics);
            
            MqttMessage message = new MqttMessage();
            message.setQos(MQTTQos.AT_LEAST_ONCE.getValue());
            message.setPayload(metricsJson.getBytes());
            
            reporter.publish(reporter.getMqttTopic(), message);
            log.debug("系统指标上报成功");
        } catch (Exception e) {
            log.error("系统指标上报失败", e);
        }
    }
    
    private SystemMetrics collectSystemMetrics() {
        SystemMetrics metrics = new SystemMetrics();
        metrics.setCpuUsage(getCpuUsage());
        metrics.setMemoryUsage(getMemoryUsage());
        metrics.setDiskUsage(getDiskUsage());
        metrics.setTimestamp(System.currentTimeMillis());
        return metrics;
    }
    
    private double getCpuUsage() {
        // 获取CPU使用率
        return ManagementFactory.getOperatingSystemMXBean().getProcessCpuLoad() * 100;
    }
    
    private double getMemoryUsage() {
        // 获取内存使用率
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
    }
    
    private double getDiskUsage() {
        // 获取磁盘使用率
        File root = new File("/");
        return (double) (root.getTotalSpace() - root.getFreeSpace()) / root.getTotalSpace() * 100;
    }
}
```

### 设备数据上报示例

```java
@Service
public class DeviceDataReporter {
    
    private final Map<String, MQTTReport> reporters = new ConcurrentHashMap<>();
    
    public void reportDeviceData(String deviceId, DeviceData data) {
        try {
            MQTTReport reporter = getOrCreateReporter(deviceId);
            
            String dataJson = JSON.toJSONString(data);
            MqttMessage message = new MqttMessage();
            message.setQos(MQTTQos.AT_LEAST_ONCE.getValue());
            message.setPayload(dataJson.getBytes());
            
            reporter.publish(reporter.getMqttTopic(), message);
            log.debug("设备 {} 数据上报成功", deviceId);
        } catch (Exception e) {
            log.error("设备 {} 数据上报失败", deviceId, e);
        }
    }
    
    private MQTTReport getOrCreateReporter(String deviceId) throws MqttException {
        return reporters.computeIfAbsent(deviceId, id -> {
            try {
                String topic = "device/" + id + "/data";
                String serviceId = "device-reporter-" + id;
                
                MQTTReport reporter = new MQTTReport(topic, serviceId);
                reporter.setCleanSession(false);
                reporter.start();
                
                return reporter;
            } catch (MqttException e) {
                throw new RuntimeException("创建设备上报器失败: " + id, e);
            }
        });
    }
    
    @PreDestroy
    public void cleanup() {
        reporters.values().forEach(reporter -> {
            try {
                reporter.getClient().disconnect();
            } catch (Exception e) {
                log.warn("关闭MQTT连接失败", e);
            }
        });
    }
}
```

### 批量数据上报示例

```java
@Component
public class BatchDataReporter {
    
    private MQTTReport reporter;
    private final BlockingQueue<ReportData> dataQueue = new LinkedBlockingQueue<>(10000);
    
    @PostConstruct
    public void init() {
        try {
            reporter = new MQTTReport("batch/data", "batch-reporter");
            reporter.setCleanSession(false);
            reporter.start();
            
            // 启动批量处理线程
            startBatchProcessor();
        } catch (MqttException e) {
            log.error("初始化批量上报器失败", e);
        }
    }
    
    public void addData(ReportData data) {
        if (!dataQueue.offer(data)) {
            log.warn("数据队列已满，丢弃数据: {}", data);
        }
    }
    
    private void startBatchProcessor() {
        Thread processor = new Thread(() -> {
            List<ReportData> batch = new ArrayList<>();
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 收集批量数据
                    ReportData data = dataQueue.poll(1, TimeUnit.SECONDS);
                    if (data != null) {
                        batch.add(data);
                        dataQueue.drainTo(batch, 99); // 最多100条一批
                        
                        // 批量上报
                        reportBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("批量处理失败", e);
                }
            }
        });
        
        processor.setName("mqtt-batch-processor");
        processor.setDaemon(true);
        processor.start();
    }
    
    private void reportBatch(List<ReportData> batch) {
        try {
            String batchJson = JSON.toJSONString(batch);
            
            MqttMessage message = new MqttMessage();
            message.setQos(MQTTQos.AT_LEAST_ONCE.getValue());
            message.setPayload(batchJson.getBytes());
            
            reporter.publish(reporter.getMqttTopic(), message);
            log.debug("批量上报成功，数据条数: {}", batch.size());
        } catch (Exception e) {
            log.error("批量上报失败，数据条数: {}", batch.size(), e);
        }
    }
}
```

### 日志上报示例

```java
@Component
public class LogReporter {
    
    private MQTTReport reporter;
    
    @PostConstruct
    public void init() {
        try {
            reporter = new MQTTReport("logs/application", "log-reporter");
            reporter.setCleanSession(false);
            reporter.start();
        } catch (MqttException e) {
            log.error("初始化日志上报器失败", e);
        }
    }
    
    public void reportLog(LogLevel level, String message, String className, String methodName) {
        try {
            LogEntry logEntry = new LogEntry();
            logEntry.setLevel(level.name());
            logEntry.setMessage(message);
            logEntry.setClassName(className);
            logEntry.setMethodName(methodName);
            logEntry.setTimestamp(System.currentTimeMillis());
            logEntry.setThreadName(Thread.currentThread().getName());
            
            String logJson = JSON.toJSONString(logEntry);
            
            MqttMessage mqttMessage = new MqttMessage();
            mqttMessage.setQos(getQosForLogLevel(level));
            mqttMessage.setPayload(logJson.getBytes());
            
            reporter.publish(reporter.getMqttTopic(), mqttMessage);
        } catch (Exception e) {
            // 避免日志上报失败影响主业务
            System.err.println("日志上报失败: " + e.getMessage());
        }
    }
    
    private int getQosForLogLevel(LogLevel level) {
        switch (level) {
            case ERROR:
            case FATAL:
                return MQTTQos.EXACTLY_ONCE.getValue();
            case WARN:
                return MQTTQos.AT_LEAST_ONCE.getValue();
            default:
                return MQTTQos.AT_MOST_ONCE.getValue();
        }
    }
    
    public enum LogLevel {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL
    }
    
    public static class LogEntry {
        private String level;
        private String message;
        private String className;
        private String methodName;
        private long timestamp;
        private String threadName;
        
        // getters and setters
    }
}
```

### 事件上报示例

```java
@Component
public class EventReporter {
    
    private final Map<String, MQTTReport> eventReporters = new HashMap<>();
    
    @PostConstruct
    public void init() {
        try {
            // 用户事件上报器
            MQTTReport userEventReporter = new MQTTReport("events/user", "user-event-reporter");
            userEventReporter.setCleanSession(false);
            userEventReporter.start();
            eventReporters.put("user", userEventReporter);
            
            // 系统事件上报器
            MQTTReport systemEventReporter = new MQTTReport("events/system", "system-event-reporter");
            systemEventReporter.setCleanSession(false);
            systemEventReporter.start();
            eventReporters.put("system", systemEventReporter);
            
            // 业务事件上报器
            MQTTReport businessEventReporter = new MQTTReport("events/business", "business-event-reporter");
            businessEventReporter.setCleanSession(false);
            businessEventReporter.start();
            eventReporters.put("business", businessEventReporter);
            
        } catch (MqttException e) {
            log.error("初始化事件上报器失败", e);
        }
    }
    
    public void reportUserEvent(String userId, String eventType, Object eventData) {
        UserEvent event = new UserEvent();
        event.setUserId(userId);
        event.setEventType(eventType);
        event.setEventData(eventData);
        event.setTimestamp(System.currentTimeMillis());
        
        reportEvent("user", event);
    }
    
    public void reportSystemEvent(String component, String eventType, Object eventData) {
        SystemEvent event = new SystemEvent();
        event.setComponent(component);
        event.setEventType(eventType);
        event.setEventData(eventData);
        event.setTimestamp(System.currentTimeMillis());
        
        reportEvent("system", event);
    }
    
    public void reportBusinessEvent(String businessType, String eventType, Object eventData) {
        BusinessEvent event = new BusinessEvent();
        event.setBusinessType(businessType);
        event.setEventType(eventType);
        event.setEventData(eventData);
        event.setTimestamp(System.currentTimeMillis());
        
        reportEvent("business", event);
    }
    
    private void reportEvent(String category, Object event) {
        try {
            MQTTReport reporter = eventReporters.get(category);
            if (reporter == null) {
                log.warn("未找到事件上报器: {}", category);
                return;
            }
            
            String eventJson = JSON.toJSONString(event);
            
            MqttMessage message = new MqttMessage();
            message.setQos(MQTTQos.AT_LEAST_ONCE.getValue());
            message.setPayload(eventJson.getBytes());
            
            reporter.publish(reporter.getMqttTopic(), message);
            log.debug("事件上报成功: {} -> {}", category, event.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("事件上报失败: {}", category, e);
        }
    }
}
```

### 性能监控上报示例

```java
@Component
public class PerformanceReporter {
    
    private MQTTReport reporter;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    @PostConstruct
    public void init() {
        try {
            reporter = new MQTTReport("performance/metrics", "performance-reporter");
            reporter.setCleanSession(false);
            reporter.start();
            
            // 启动定时上报
            startPerformanceReporting();
        } catch (MqttException e) {
            log.error("初始化性能监控上报器失败", e);
        }
    }
    
    private void startPerformanceReporting() {
        // 每30秒上报一次性能指标
        scheduler.scheduleAtFixedRate(this::reportPerformanceMetrics, 0, 30, TimeUnit.SECONDS);
        
        // 每5分钟上报一次GC信息
        scheduler.scheduleAtFixedRate(this::reportGCMetrics, 0, 5, TimeUnit.MINUTES);
    }
    
    private void reportPerformanceMetrics() {
        try {
            PerformanceMetrics metrics = new PerformanceMetrics();
            
            // CPU使用率
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            metrics.setCpuUsage(osBean.getProcessCpuLoad() * 100);
            
            // 内存使用情况
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            metrics.setHeapUsed(heapUsage.getUsed());
            metrics.setHeapMax(heapUsage.getMax());
            metrics.setHeapUsage((double) heapUsage.getUsed() / heapUsage.getMax() * 100);
            
            // 线程数
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            metrics.setThreadCount(threadBean.getThreadCount());
            
            // 类加载数
            ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();
            metrics.setLoadedClassCount(classBean.getLoadedClassCount());
            
            metrics.setTimestamp(System.currentTimeMillis());
            
            String metricsJson = JSON.toJSONString(metrics);
            
            MqttMessage message = new MqttMessage();
            message.setQos(MQTTQos.AT_LEAST_ONCE.getValue());
            message.setPayload(metricsJson.getBytes());
            
            reporter.publish(reporter.getMqttTopic(), message);
            log.debug("性能指标上报成功");
        } catch (Exception e) {
            log.error("性能指标上报失败", e);
        }
    }
    
    private void reportGCMetrics() {
        try {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                GCMetrics gcMetrics = new GCMetrics();
                gcMetrics.setGcName(gcBean.getName());
                gcMetrics.setCollectionCount(gcBean.getCollectionCount());
                gcMetrics.setCollectionTime(gcBean.getCollectionTime());
                gcMetrics.setTimestamp(System.currentTimeMillis());
                
                String gcJson = JSON.toJSONString(gcMetrics);
                
                MqttMessage message = new MqttMessage();
                message.setQos(MQTTQos.AT_LEAST_ONCE.getValue());
                message.setPayload(gcJson.getBytes());
                
                reporter.publish(reporter.getMqttTopic(), message);
            }
            
            log.debug("GC指标上报成功");
        } catch (Exception e) {
            log.error("GC指标上报失败", e);
        }
    }
    
    @PreDestroy
    public void cleanup() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        
        if (reporter != null) {
            try {
                reporter.getClient().disconnect();
            } catch (Exception e) {
                log.warn("关闭性能监控上报器失败", e);
            }
        }
    }
}
```

## 最佳实践

1. **资源管理**：正确管理MQTT连接，应用关闭时释放资源
2. **异常处理**：捕获并处理上报过程中的异常，避免影响主业务
3. **批量处理**：对于高频数据，考虑使用批量上报提高性能
4. **QoS选择**：根据数据重要性选择合适的QoS等级
5. **连接复用**：对于相同主题的数据，复用MQTTReport实例
6. **监控告警**：监控上报成功率，及时发现问题

```java
// 上报成功率监控示例
@Component
public class ReportMonitor {
    
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    
    public void recordSuccess() {
        successCount.incrementAndGet();
    }
    
    public void recordFailure() {
        failureCount.incrementAndGet();
    }
    
    @Scheduled(fixedRate = 300000) // 每5分钟检查一次
    public void checkReportHealth() {
        long success = successCount.getAndSet(0);
        long failure = failureCount.getAndSet(0);
        long total = success + failure;
        
        if (total > 0) {
            double successRate = (double) success / total * 100;
            log.info("MQTT上报统计 - 成功: {}, 失败: {}, 成功率: {:.2f}%", 
                    success, failure, successRate);
            
            if (successRate < 90) {
                log.warn("MQTT上报成功率过低: {:.2f}%", successRate);
                // 发送告警
                alertService.sendReportAlert(successRate);
            }
        }
    }
}
```