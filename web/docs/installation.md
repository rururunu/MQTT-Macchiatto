# 引入依赖

## Maven 依赖

在你的 `pom.xml` 文件中添加以下依赖：

```xml
<dependency>
    <groupId>io.github.rururunu</groupId>
    <artifactId>MQTT-Macchiatto</artifactId>
    <version>0.1.5</version>
</dependency>
```

## Gradle 依赖

```gradle
implementation 'io.github.rururunu:MQTT-Macchiatto:0.1.3'
```

## Spring Boot 配置

在 `application.yml` 中配置 MQTT 连接信息：

```yaml
mto-mqtt:
  host: tcp://your-mqtt-broker:1883
  username: your-username
  password: your-password
  timeout: 10000
  keepalive: 60
  reconnect-frequency-ms: 5000
```

### 配置参数说明

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `host` | String | - | MQTT 服务器地址，格式：tcp://host:port |
| `username` | String | - | MQTT 连接用户名 |
| `password` | String | - | MQTT 连接密码 |
| `timeout` | Integer | 10000 | 连接超时时间（毫秒） |
| `keepalive` | Integer | 60 | 心跳间隔（秒） |
| `reconnect-frequency-ms` | Integer | 5000 | 重连间隔（毫秒） |

## 启动类配置

在你的 Spring Boot 启动类中添加包扫描：

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

> ⚠️ **重要提示**
> 
> 确保在 `scanBasePackages` 中包含 `io.github.rururunu`，否则 MQTT Macchiatto 的组件无法被 Spring 扫描到。

## 环境变量配置

你也可以使用环境变量来配置 MQTT 连接信息：

```yaml
mto-mqtt:
  host: ${MQTT_HOST:tcp://localhost:1883}
  username: ${MQTT_USERNAME:admin}
  password: ${MQTT_PASSWORD:password}
  timeout: ${MQTT_TIMEOUT:10000}
  keepalive: ${MQTT_KEEPALIVE:60}
  reconnect-frequency-ms: ${MQTT_RECONNECT_MS:5000}
```

## SSL/TLS 配置

如果你的 MQTT 服务器支持 SSL/TLS，可以这样配置：

```yaml
mto-mqtt:
  host: ssl://your-mqtt-broker:8883
  username: your-username
  password: your-password
```

## 验证配置

配置完成后，启动应用程序，如果看到类似以下日志，说明配置成功：

```
INFO  - MQTT Macchiatto initialized successfully
INFO  - Connected to MQTT broker: tcp://your-mqtt-broker:1883
```