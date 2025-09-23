# 快速开始

## 第一个消息监听器

创建一个简单的消息监听器：

```java
@Component
public class MqttMessageListener {
    
    @PostConstruct
    public void initListener() {
        MqttPut.of("sensor/temperature")
            .response((topic, message) -> {
                System.out.println("收到温度数据: " + message);
                // 处理温度数据的业务逻辑
            })
            .start();
    }
}
```

## 发送第一条消息

```java
@Service
public class TemperatureService {
    
    private MqttPush mqttPush = new MqttPush();
    
    public void reportTemperature(double temperature) {
        String message = "当前温度: " + temperature + "°C";
        mqttPush.push("sensor/temperature", message, MQTTQos.AT_LEAST_ONCE);
    }
}
```

## 完整示例

下面是一个完整的 Spring Boot 应用示例：

```java
@SpringBootApplication(
    scanBasePackages = {"com.example.demo", "io.github.rururunu"}
)
public class MqttDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(MqttDemoApplication.class, args);
    }
}

@RestController
public class MqttController {
    
    private MqttPush mqttPush = new MqttPush();
    
    @PostMapping("/send")
    public String sendMessage(@RequestParam String topic, 
                             @RequestParam String message) {
        try {
            mqttPush.push(topic, message, MQTTQos.AT_LEAST_ONCE);
            return "消息发送成功";
        } catch (Exception e) {
            return "消息发送失败: " + e.getMessage();
        }
    }
}

@Component
public class MqttListener {
    
    @PostConstruct
    public void initListeners() {
        // 监听所有传感器数据
        MqttPut.of("sensor/+/data")
            .response(this::handleSensorData)
            .start();
        
        // 监听设备状态
        MqttPut.of("device/+/status")
            .response(this::handleDeviceStatus)
            .start();
    }
    
    private void handleSensorData(String topic, String message) {
        System.out.println("传感器数据: " + topic + " -> " + message);
    }
    
    private void handleDeviceStatus(String topic, String message) {
        System.out.println("设备状态: " + topic + " -> " + message);
    }
}
```

## 配置文件

`application.yml`:

```yaml
server:
  port: 8080

mto-mqtt:
  host: tcp://localhost:1883
  username: admin
  password: password
  timeout: 10000
  keepalive: 60
  reconnect-frequency-ms: 5000

logging:
  level:
    io.github.rururunu: DEBUG
```

## 测试应用

1. 启动应用程序
2. 使用 MQTT 客户端工具（如 MQTTX）连接到同一个 MQTT 服务器
3. 发送消息到 `sensor/room1/data` 主题，应该能在控制台看到输出
4. 调用 REST API：`POST http://localhost:8080/send?topic=test/message&message=Hello`

## 常见问题

### 连接失败

如果遇到连接失败，请检查：

1. MQTT 服务器地址是否正确
2. 用户名和密码是否正确
3. 网络连接是否正常
4. 防火墙是否阻止了连接

### 消息收不到

如果监听器收不到消息，请检查：

1. 主题名称是否正确
2. QoS 等级是否匹配
3. 是否正确配置了包扫描

### 自动重连

MQTT Macchiatto 内置了自动重连机制，当连接断开时会自动尝试重连。你可以通过日志观察重连过程：

```
WARN  - MQTT Connection disconnected, attempting to reconnect...
INFO  - MQTT reconnected successfully
```

> ✅ **恭喜！**
> 
> 你已经成功创建了第一个 MQTT 应用！接下来可以深入了解各个组件的详细用法。