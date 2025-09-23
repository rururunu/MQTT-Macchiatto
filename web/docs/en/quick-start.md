# Quick Start

## First Message Listener

Create a simple message listener:

```java
@Component
public class MqttMessageListener {
    
    @PostConstruct
    public void initListener() {
        MqttPut.of("sensor/temperature")
            .response((topic, message) -> {
                System.out.println("Received temperature data: " + message);
                // Business logic for handling temperature data
            })
            .start();
    }
}
```

## Send Your First Message

```java
@Service
public class TemperatureService {
    
    private MqttPush mqttPush = new MqttPush();
    
    public void reportTemperature(double temperature) {
        String message = "Current temperature: " + temperature + "°C";
        mqttPush.push("sensor/temperature", message, MQTTQos.AT_LEAST_ONCE);
    }
}
```

## Complete Example

Here's a complete Spring Boot application example:

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
            return "Message sent successfully";
        } catch (Exception e) {
            return "Message send failed: " + e.getMessage();
        }
    }
}

@Component
public class MqttListener {
    
    @PostConstruct
    public void initListeners() {
        // Listen to all sensor data
        MqttPut.of("sensor/+/data")
            .response(this::handleSensorData)
            .start();
        
        // Listen to device status
        MqttPut.of("device/+/status")
            .response(this::handleDeviceStatus)
            .start();
    }
    
    private void handleSensorData(String topic, String message) {
        System.out.println("Sensor data: " + topic + " -> " + message);
    }
    
    private void handleDeviceStatus(String topic, String message) {
        System.out.println("Device status: " + topic + " -> " + message);
    }
}
```

## Configuration File

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

## Test the Application

1. Start the application
2. Use an MQTT client tool (like MQTTX) to connect to the same MQTT server
3. Send a message to the `sensor/room1/data` topic, you should see output in the console
4. Call the REST API: `POST http://localhost:8080/send?topic=test/message&message=Hello`

## Common Issues

### Connection Failed

If you encounter connection failures, please check:

1. Whether the MQTT server address is correct
2. Whether the username and password are correct
3. Whether the network connection is normal
4. Whether the firewall is blocking the connection

### Messages Not Received

If the listener doesn't receive messages, please check:

1. Whether the topic name is correct
2. Whether the QoS level matches
3. Whether package scanning is configured correctly

### Auto Reconnection

MQTT Macchiatto has built-in auto-reconnection mechanism. When the connection is lost, it will automatically attempt to reconnect. You can observe the reconnection process through logs:

```
WARN  - MQTT Connection disconnected, attempting to reconnect...
INFO  - MQTT reconnected successfully
```

> ✅ **Congratulations!**
> 
> You have successfully created your first MQTT application! Next, you can dive deeper into the detailed usage of each component.