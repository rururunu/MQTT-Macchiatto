# Installation

## Maven Dependency

Add the following dependency to your `pom.xml` file:

```xml
<dependency>
    <groupId>io.github.rururunu</groupId>
    <artifactId>MQTT-Macchiatto</artifactId>
    <version>0.1.5</version>
</dependency>
```

## Gradle Dependency

```gradle
implementation 'io.github.rururunu:MQTT-Macchiatto:0.1.3'
```

## Spring Boot Configuration

Configure MQTT connection information in `application.yml`:

```yaml
mto-mqtt:
  host: tcp://your-mqtt-broker:1883
  username: your-username
  password: your-password
  timeout: 10000
  keepalive: 60
  reconnect-frequency-ms: 5000
```

### Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `host` | String | - | MQTT server address, format: tcp://host:port |
| `username` | String | - | MQTT connection username |
| `password` | String | - | MQTT connection password |
| `timeout` | Integer | 10000 | Connection timeout (milliseconds) |
| `keepalive` | Integer | 60 | Heartbeat interval (seconds) |
| `reconnect-frequency-ms` | Integer | 5000 | Reconnection interval (milliseconds) |

## Application Class Configuration

Add package scanning to your Spring Boot application class:

```java
@SpringBootApplication(
    scanBasePackages = {
        "your.project.package",
        "io.github.rururunu"
    }
)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

> ⚠️ **Important Note**
> 
> Make sure to include `io.github.rururunu` in `scanBasePackages`, otherwise MQTT Macchiatto components cannot be scanned by Spring.

## Environment Variable Configuration

You can also use environment variables to configure MQTT connection information:

```yaml
mto-mqtt:
  host: ${MQTT_HOST:tcp://localhost:1883}
  username: ${MQTT_USERNAME:admin}
  password: ${MQTT_PASSWORD:password}
  timeout: ${MQTT_TIMEOUT:10000}
  keepalive: ${MQTT_KEEPALIVE:60}
  reconnect-frequency-ms: ${MQTT_RECONNECT_MS:5000}
```

## SSL/TLS Configuration

If your MQTT server supports SSL/TLS, you can configure it like this:

```yaml
mto-mqtt:
  host: ssl://your-mqtt-broker:8883
  username: your-username
  password: your-password
```

## Verify Configuration

After configuration is complete, start the application. If you see logs similar to the following, the configuration is successful:

```
INFO  - MQTT Macchiatto initialized successfully
INFO  - Connected to MQTT broker: tcp://your-mqtt-broker:1883
```