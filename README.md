![MQTT Macchiatto](MQTT-Macchiatto-Logo.svg)

# MQTT Macchiatto

An MQTT 3.1, 3.1.1, and 5 client for Java 25 and newer. One session owns the socket. Subscribe and publish stay short, and the hot path allocates less on the heap. There is no Spring and no Eclipse Paho on the runtime path.

The library is built with [Jenesis](https://jenesis.build/).

[中文](README_ZH.md) · [Design](DESIGN.en.md) · [0.1.x](legacy/README.md) · [Site](https://rururunu.github.io/MQTT-Macchiatto-Docs/index.html)

## Install

JDK 25 or newer. The coordinate is `io.github.rururunu:MQTT-Macchiatto:1.0.0`. Maven and Gradle resolve it from Maven Central. The 0.1.x artifact on Central is the Spring Boot wrapper, kept in [legacy/README.md](legacy/README.md).

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

The public API is `io.github.rururunu.mqttmacchiatto`. The `internal` package is not for applications. Applications do not install Jenesis.

## Quick start

This connects to port 1883, keeps the broker session, subscribes to `sensors/#`, and publishes one QoS 1 message.

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

`connect` returns after the broker accepts the connection. The third argument `false` keeps the session. Reconnect uses `ReconnectPolicy.standard()`, and after a drop the session sends the filter again. `orTimeout` bounds that handshake. `join()` returns after PUBACK for QoS 1. Leaving `try` calls `close()`, which sends DISCONNECT.

A username, a will, or several endpoints go on `MqttConfig.builder`. Write the endpoint as `tcp://host:port`. A password requires a username. Omit the client id and the library generates one. An empty client id requires `cleanSession(true)`.

For a process that stays up and does not need a completion signal, use `emit`. Do not mutate the payload array until the write has gone out. `emit` has no future, and `close()` does not wait for it.

## Spring Boot

There is no Spring starter. On Spring Boot 3.5 or newer, declare one session bean and close it with the context. `MqttPut`, `MqttPush`, and `mto-mqtt` are the 0.1.x wrapper, not this client. One session is one connection.

`MqttSession.connect` waits for the first CONNACK. With `ReconnectPolicy.standard()`, a broker that is down holds application startup on this bean. `ReconnectPolicy.none()` fails startup on the first refusal instead.

```yaml
macchiatto:
  endpoint: tcp://127.0.0.1:1883
  client-id: sensor-1
  username: user
  password: secret
  clean-session: false
  keep-alive-seconds: 60
```

A long-running process sets `client-id`. A generated id changes every start, so the broker session from last time does not match. A password requires a username.

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

Subscribe from a component that takes the session. The listener runs on the callback thread and returns immediately. Do not `publish(...).join()` on that same session from inside the listener.

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

Publish on a business thread, with a timeout:

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

`MqttSession` is final. Tests replace an interface of yours, rather than mocking the session or opening a broker for this call.

## What the session does

Dialing, backoff, and socket I/O stay on one thread. `publish` returns a `CompletableFuture`. `subscribe` returns a handle that closes with try-with-resources. QoS 1 and 2 keep their packet ids across reconnect. A directory passed to `persistence` is forced to disk before those bytes are written to the socket. The topic and payload a listener receives are copies, and they stay valid after the callback returns.

The reasons and the code are in [DESIGN.en.md](DESIGN.en.md).

## Compared with Eclipse Paho 1.2.5

2026-11-03, one Windows machine, Temurin 26.0.2, JMH 1.37. One fork, two 1-second warmups, three 1-second measurements. The tables use that average. Heap figures omit direct buffers. The sample spans and the raw logs are in [benchmark/RESULTS.md](benchmark/RESULTS.md).

### Against EMQX 5.8.6

Local Docker on `127.0.0.1:1883`. The payload is 128 bytes and nothing parses it. This set is broker-bound.

Publish, nobody subscribed. QoS 0 sends 200 messages and joins the last write. QoS 1 and 2 send 32 and wait for every handshake. Both in-flight windows are 128.

| | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 0 | 216 k msg/s, 5.3 B/op | 169 k msg/s, 1017 B/op |
| QoS 1 | 30.1 k msg/s, 156 B/op | 22.4 k msg/s, 2301 B/op |
| QoS 2 | 19.4 k msg/s, 179 B/op | 9.2 k msg/s, 3533 B/op |

QoS 0 at 128 bytes sits in a narrow band on both sides, and EMQX is the ceiling. QoS 1 moves by a few thousand messages per second across the three samples. The allocation gap is the stable part.

Receive, until the subscriber has counted the batch. The subscriber is subscribed at QoS 2, and each row publishes at that row's QoS.

| | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 0 | 49.1 k msg/s, 288 B/op | 15.7 k msg/s, 2434 B/op |
| QoS 1 | 14.1 k msg/s, 453 B/op | 11.7 k msg/s, 4290 B/op |
| QoS 2 | 9.7 k msg/s, 555 B/op | 8.2 k msg/s, 6294 B/op |

One message, from `publish` to the subscriber callback, has a p50 under a millisecond for both clients. QoS 1 p95 is 2.1 ms for Macchiatto and 1.1 ms for Paho. The tails overlap. A cycle that connects, publishes one QoS 1 message, and closes has p50 3.9 ms for Macchiatto, with one sample at 57 ms. Paho's p50 is 307 ms on every cycle.

### In-process broker

Neither client subscribes. QoS 0 sends 1,000 messages and joins the last write. QoS 1 sends 200 and joins the last acknowledgement. These rates are separate from the EMQX table above.

| | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 0, 64 B | 4.27 M msg/s, 8.8 B/op | 151 k msg/s, 908 B/op |
| QoS 1, 64 B | 208 k msg/s, 47.6 B/op | 185 k msg/s, 1912 B/op |

The allocation figure includes the test broker. QoS 1 throughput follows the acknowledgement round trip.

## Build

Jenesis reads `module-info.java`. `@jenesis.release 25` compiles the client for Java 25. The test module pins JUnit, JMH, and Paho with `@jenesis.pin`. The tool's sources are in `build/jenesis`. A JDK is enough to build, dependencies still come from Maven, and the published coordinate stays the one above. Jenesis is at <https://jenesis.build/>.

## 0.1.x

The Spring Boot wrapper (`MqttPut`, `MqttPush`, `application.yml`) is the documentation preserved in [legacy/README.md](legacy/README.md) and on the [old site](https://rururunu.github.io/MQTT-Macchiatto-Docs/legacy/index.html). Those types are not in this client.
