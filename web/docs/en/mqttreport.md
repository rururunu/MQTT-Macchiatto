# MQTTReport Message Reporting

`MQTTReport` is a utility class specifically designed for message reporting, providing lower-level message publishing control.

## Basic Usage

```java
// Create connection
MQTTReport reporter = new MQTTReport();
reporter.setTopic("system/metrics");
reporter.setServiceId("metrics-reporter");
reporter.setCleanSession(false);
reporter.start();

// Send message
reporter.getMessage().setQos(MQTTQos.AT_LEAST_ONCE.getValue());
reporter.getMessage().setPayload("System metrics data".getBytes());
reporter.publish(reporter.getMqttTopic(), reporter.getMessage());
```

## Constructor Methods

| Constructor | Parameters | Description |
|-------------|------------|-------------|
| `new MQTTReport()` | None | Create instance with default configuration |
| `new MQTTReport(String topic, String serviceId)` | Topic name, Service ID | Create instance with topic and service ID set |

## Configuration Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `setServiceId(String serviceId)` | Service ID | Set client ID |
| `setTopic(String topic)` | Topic name | Set publishing topic |
| `setCleanSession(boolean clean)` | Whether to clean session | Set session cleanup flag |

## Connection and Publishing Methods

| Method | Parameters | Description |
|--------|------------|-------------|
| `start()` | None | Start MQTT connection |
| `publish(MqttTopic topic, MqttMessage message)` | MQTT topic object, MQTT message object | Publish message to specified topic |

## Getter Methods

| Method | Return Value | Description |
|--------|--------------|-------------|
| `getServiceId()` | `String` | Get service ID |
| `getTopic()` | `String` | Get topic name |
| `getClient()` | `MqttClient` | Get MQTT client |
| `getMqttTopic()` | `MqttTopic` | Get MQTT topic object |
| `getMessage()` | `MqttMessage` | Get MQTT message object |
| `isCleanSession()` | `boolean` | Get session cleanup flag |

## Usage Examples

### System Metrics Reporting Example

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
            log.error("Failed to initialize MQTT reporter", e);
        }
    }
    
    @Scheduled(fixedRate = 60000) // Report every minute
    public void reportSystemMetrics() {
        try {
            SystemMetrics metrics = collectSystemMetrics();
            String metricsJson = JSON.toJSONString(metrics);
            
            MqttMessage message = new MqttMessage();
            message.setQos(MQTTQos.AT_LEAST_ONCE.getValue());
            message.setPayload(metricsJson.getBytes());
            
            reporter.publish(reporter.getMqttTopic(), message);
            log.debug("System metrics reported successfully");
        } catch (Exception e) {
            log.error("System metrics reporting failed", e);
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
        // Get CPU usage
        return ManagementFactory.getOperatingSystemMXBean().getProcessCpuLoad() * 100;
    }
    
    private double getMemoryUsage() {
        // Get memory usage
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
    }
    
    private double getDiskUsage() {
        // Get disk usage
        File root = new File("/");
        return (double) (root.getTotalSpace() - root.getFreeSpace()) / root.getTotalSpace() * 100;
    }
}
```

### Device Data Reporting Example

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
            log.debug("Device {} data reported successfully", deviceId);
        } catch (Exception e) {
            log.error("Device {} data reporting failed", deviceId, e);
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
                throw new RuntimeException("Failed to create device reporter: " + id, e);
            }
        });
    }
    
    @PreDestroy
    public void cleanup() {
        reporters.values().forEach(reporter -> {
            try {
                reporter.getClient().disconnect();
            } catch (Exception e) {
                log.warn("Failed to close MQTT connection", e);
            }
        });
    }
}
```

### Batch Data Reporting Example

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
            
            // Start batch processing thread
            startBatchProcessor();
        } catch (MqttException e) {
            log.error("Failed to initialize batch reporter", e);
        }
    }
    
    public void addData(ReportData data) {
        if (!dataQueue.offer(data)) {
            log.warn("Data queue is full, dropping data: {}", data);
        }
    }
    
    private void startBatchProcessor() {
        Thread processor = new Thread(() -> {
            List<ReportData> batch = new ArrayList<>();
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Collect batch data
                    ReportData data = dataQueue.poll(1, TimeUnit.SECONDS);
                    if (data != null) {
                        batch.add(data);
                        dataQueue.drainTo(batch, 99); // Maximum 100 items per batch
                        
                        // Batch report
                        reportBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Batch processing failed", e);
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
            log.debug("Batch report successful, data count: {}", batch.size());
        } catch (Exception e) {
            log.error("Batch report failed, data count: {}", batch.size(), e);
        }
    }
}
```

### Performance Monitoring Reporting Example

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
            
            // Start scheduled reporting
            startPerformanceReporting();
        } catch (MqttException e) {
            log.error("Failed to initialize performance reporter", e);
        }
    }
    
    private void startPerformanceReporting() {
        // Report performance metrics every 30 seconds
        scheduler.scheduleAtFixedRate(this::reportPerformanceMetrics, 0, 30, TimeUnit.SECONDS);
        
        // Report GC information every 5 minutes
        scheduler.scheduleAtFixedRate(this::reportGCMetrics, 0, 5, TimeUnit.MINUTES);
    }
    
    private void reportPerformanceMetrics() {
        try {
            PerformanceMetrics metrics = new PerformanceMetrics();
            
            // CPU usage
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            metrics.setCpuUsage(osBean.getProcessCpuLoad() * 100);
            
            // Memory usage
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            metrics.setHeapUsed(heapUsage.getUsed());
            metrics.setHeapMax(heapUsage.getMax());
            metrics.setHeapUsage((double) heapUsage.getUsed() / heapUsage.getMax() * 100);
            
            // Thread count
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            metrics.setThreadCount(threadBean.getThreadCount());
            
            // Loaded class count
            ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();
            metrics.setLoadedClassCount(classBean.getLoadedClassCount());
            
            metrics.setTimestamp(System.currentTimeMillis());
            
            String metricsJson = JSON.toJSONString(metrics);
            
            MqttMessage message = new MqttMessage();
            message.setQos(MQTTQos.AT_LEAST_ONCE.getValue());
            message.setPayload(metricsJson.getBytes());
            
            reporter.publish(reporter.getMqttTopic(), message);
            log.debug("Performance metrics reported successfully");
        } catch (Exception e) {
            log.error("Performance metrics reporting failed", e);
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
            
            log.debug("GC metrics reported successfully");
        } catch (Exception e) {
            log.error("GC metrics reporting failed", e);
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
                log.warn("Failed to close performance reporter", e);
            }
        }
    }
}
```

## Best Practices

1. **Resource Management**: Properly manage MQTT connections, release resources when application shuts down
2. **Exception Handling**: Catch and handle exceptions during reporting process, avoid affecting main business
3. **Batch Processing**: Consider using batch reporting for high-frequency data to improve performance
4. **QoS Selection**: Choose appropriate QoS levels based on data importance
5. **Connection Reuse**: Reuse MQTTReport instances for data with the same topic
6. **Monitoring and Alerting**: Monitor reporting success rate, detect issues promptly

```java
// Reporting success rate monitoring example
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
    
    @Scheduled(fixedRate = 300000) // Check every 5 minutes
    public void checkReportHealth() {
        long success = successCount.getAndSet(0);
        long failure = failureCount.getAndSet(0);
        long total = success + failure;
        
        if (total > 0) {
            double successRate = (double) success / total * 100;
            log.info("MQTT reporting statistics - Success: {}, Failure: {}, Success rate: {:.2f}%", 
                    success, failure, successRate);
            
            if (successRate < 90) {
                log.warn("MQTT reporting success rate too low: {:.2f}%", successRate);
                // Send alert
                alertService.sendReportAlert(successRate);
            }
        }
    }
}
```