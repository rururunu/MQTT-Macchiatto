![MQTT Macchiatto](MQTT-Macchiatto-Logo.svg)

# MQTT Macchiatto

给 Java 25 及以上用的 MQTT 客户端。协议覆盖 MQTT 3.1、MQTT 3.1.1 和 MQTT 5。一条会话握着套接字，订阅和发布写得很短，热路径上的堆分配也更少。运行时没有 Spring，也没有 Eclipse Paho。

这个库用 [Jenesis](https://jenesis.build/) 构建。

[English](README.md) · [设计](DESIGN.md) · [0.1.x](legacy/README_ZH.md) · [旧版网站](https://rururunu.github.io/MQTT-Macchiatto-Docs/legacy/index.html)

## 安装

需要 JDK 25 或更新的版本。坐标是 `io.github.rururunu:MQTT-Macchiatto:1.0.0`。Maven 和 Gradle 从 Maven Central 拉取。Central 上的 0.1.x 是 Spring Boot 封装，文档留在 [legacy/README_ZH.md](legacy/README_ZH.md)。

```xml
<dependency>
    <groupId>io.github.rururunu</groupId>
    <artifactId>MQTT-Macchiatto</artifactId>
    <version>1.0.0</version>
</dependency>
```

```kotlin
dependencies {
    implementation("io.github.rururunu:MQTT-Macchiatto:1.0.0")
}
```

```java
module your.app {
    requires io.github.rururunu.mqttmacchiatto;
}
```

公开 API 在 `io.github.rururunu.mqttmacchiatto`。`internal` 包不是给应用用的。使用方不需要安装 Jenesis。

## 快速开始

下面这段连到本机 1883，保留 Broker 会话，订阅 `sensors/#`，再发一条 QoS 1。

```java
try (MqttSession session = MqttSession.connect(
        "tcp://127.0.0.1:1883", "sensor-1", false)) {
    session.subscribe("sensors/#",
            (topic, body) -> System.out.println(topic + " " + body.length));
    session.publish("sensors/temp", "{\"temp\":21}", MqttQos.AT_LEAST_ONCE)
            .orTimeout(10, TimeUnit.SECONDS)
            .join();
}
```

`connect` 在 Broker 接受连接之后返回。第三个参数 `false` 表示保留会话。重连用 `ReconnectPolicy.standard()`，断线之后会话会把过滤器再发一遍。`orTimeout` 给这次握手加上限。QoS 1 的 `join()` 在 PUBACK 之后返回。`try` 结束时 `close()` 会发 DISCONNECT。

用户名、遗嘱、多个地址用 `MqttConfig.builder`。地址写成 `tcp://主机:端口`。有密码就必须有用户名。Client ID 可以不写，库会自己生成一个。空字符串的 Client ID 必须同时 `cleanSession(true)`。

进程还要继续跑、不需要完成信号时，用 `emit`。载荷数组在写出之前不要改。`emit` 没有 Future，`close()` 也不会等它。

## 接入 Spring Boot

没有 Spring 起步依赖。Spring Boot 3.5 及以上里，声明一个会话 Bean，随上下文关闭。`MqttPut`、`MqttPush` 和 `mto-mqtt` 是 0.1.x 的封装，不是这个客户端。一条连接一个会话。

`MqttSession.connect` 会等到第一条 CONNACK。默认的 `ReconnectPolicy.standard()` 不限制次数，Broker 没起来时，应用会停在这个 Bean 上。改成 `ReconnectPolicy.none()`，第一次失败就终止启动。

```yaml
macchiatto:
  endpoint: tcp://127.0.0.1:1883
  client-id: sensor-1
  username: user
  password: secret
  clean-session: false
  keep-alive-seconds: 60
```

长期运行要写死 `client-id`。不写的话每次启动都是新的，对不上 Broker 上的旧会话。有密码就必须有用户名。

```java
@ConfigurationProperties(prefix = "macchiatto")
public class MacchiattoSettings {
    private String endpoint = "tcp://127.0.0.1:1883";
    private String clientId = "sensor-1";
    private String username;
    private String password;
    private boolean cleanSession = true;
    private int keepAliveSeconds = 60;

    public MqttConfig toConfig() {
        MqttConfig.Builder builder = MqttConfig.builder(endpoint)
                .clientId(clientId)
                .cleanSession(cleanSession)
                .keepAliveSeconds(keepAliveSeconds);
        if (username != null && !username.isEmpty()) {
            builder.username(username);
        }
        if (password != null) {
            builder.password(password);
        }
        return builder.build();
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isCleanSession() { return cleanSession; }
    public void setCleanSession(boolean cleanSession) { this.cleanSession = cleanSession; }
    public int getKeepAliveSeconds() { return keepAliveSeconds; }
    public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }
}
```

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class SensorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SensorApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    MqttSession mqttSession(MacchiattoSettings settings) {
        return MqttSession.connect(settings.toConfig());
    }
}
```

订阅放在依赖这个会话的组件里。监听器在回调线程上，要马上返回。不要在监听器里对同一个会话 `publish(...).join()`。

```java
@Component
class SensorListener {
    private final MqttSession session;

    SensorListener(MqttSession session) {
        this.session = session;
    }

    @PostConstruct
    void subscribe() {
        session.subscribe("sensors/#", (topic, body) ->
                System.out.println(topic + " " + body.length));
    }
}
```

发布放在业务线程上，并给这一次加上限：

```java
@Service
public class TemperaturePublisher {
    private final MqttSession session;

    public TemperaturePublisher(MqttSession session) {
        this.session = session;
    }

    public void publish(String text) {
        session.publish("sensors/temp", text, MqttQos.AT_LEAST_ONCE)
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
    }
}
```

`MqttSession` 是 final 的。测试替换你自己的接口，不要为了这一行去模拟会话或连一个 Broker。

## 会话在做什么

拨号、退避和套接字读写都在同一条线程上。`publish` 返回 `CompletableFuture`。`subscribe` 返回可以用 try-with-resources 关闭的句柄。QoS 1 和 2 的包标识符会跨过重连留下来。`persistence` 指定的目录会先强制刷盘，再把这些字节写进套接字。监听器拿到的主题和载荷是拷出来的，回调返回之后仍然有效。

原因和代码在 [DESIGN.md](DESIGN.md)。

## 和 Eclipse Paho 1.2.5 比

2026-11-03，一台 Windows，Temurin 26.0.2，JMH 1.37。一次 fork，两次 1 秒预热，三次 1 秒测量。表里是这三次的平均。堆上的数字不含 direct buffer。样本区间和原始日志在 [benchmark/RESULTS.md](benchmark/RESULTS.md)。

### 对上 EMQX 5.8.6

本机 Docker，`127.0.0.1:1883`。载荷 128 字节，没有被解析。这组数受 Broker 限制。

发布，没有订阅者。QoS 0 发 200 条，等最后一次写完。QoS 1 和 2 发 32 条，等每一次握手结束。两边的在途窗口都是 128。

| | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 0 | 216 k msg/s，5.3 B/op | 169 k msg/s，1017 B/op |
| QoS 1 | 30.1 k msg/s，156 B/op | 22.4 k msg/s，2301 B/op |
| QoS 2 | 19.4 k msg/s，179 B/op | 9.2 k msg/s，3533 B/op |

128 字节的 QoS 0 两边都落在很窄的一段里，上限是 EMQX。QoS 1 在三次样本里会差几千条每秒。稳的是分配上的差距。

接收，计时一直到订阅端数完这一批。订阅用 QoS 2，每一行按该行的 QoS 发布。

| | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 0 | 49.1 k msg/s，288 B/op | 15.7 k msg/s，2434 B/op |
| QoS 1 | 14.1 k msg/s，453 B/op | 11.7 k msg/s，4290 B/op |
| QoS 2 | 9.7 k msg/s，555 B/op | 8.2 k msg/s，6294 B/op |

一条消息从 `publish` 到订阅回调，两边的 p50 都在 1 毫秒以内。QoS 1 的 p95 是 Macchiatto 2.1 ms、Paho 1.1 ms，尾部叠在一起。连上、发一条 QoS 1、再关掉，Macchiatto 的 p50 是 3.9 ms，有一次样本到 57 ms。Paho 的 p50 每次都在 307 ms 附近。

### 进程内 Broker

两边都不订阅。QoS 0 发 1000 条，等最后一次写完。QoS 1 发 200 条，等最后一次确认。这组速率和上面的 EMQX 表不是同一件事。

| | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 0，64 B | 4.27 M msg/s，8.8 B/op | 151 k msg/s，908 B/op |
| QoS 1，64 B | 208 k msg/s，47.6 B/op | 185 k msg/s，1912 B/op |

分配数字包含测试用的 Broker。QoS 1 的吞吐跟着确认的往返走。

## 构建

Jenesis 读 `module-info.java`。`@jenesis.release 25` 表示按 Java 25 编译。测试模块用 `@jenesis.pin` 钉住 JUnit、JMH 和 Paho。工具的源码在 `build/jenesis`。有 JDK 就能构建，依赖仍从 Maven 来，发出去的坐标还是上面那一个。官网是 <https://jenesis.build/>。

## 0.1.x

Spring Boot 封装（`MqttPut`、`MqttPush`、`application.yml`）的文档留在 [legacy/README_ZH.md](legacy/README_ZH.md)，网站在[旧版页面](https://rururunu.github.io/MQTT-Macchiatto-Docs/legacy/index.html)。这些类型不在这个客户端里。
