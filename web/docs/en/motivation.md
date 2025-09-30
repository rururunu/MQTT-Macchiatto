# Development Motivation

## Why Develop MQTT Macchiatto?

In daily Spring Boot project development, we often need to interact with MQTT message queues. However, traditional MQTT client usage often has the following pain points:

### 🤔 Development Pain Points

**1. Complex Configuration**
- Need to manually create MQTT client instances
- Connection parameter configuration is tedious and error-prone
- Lack of unified configuration management

**2. Code Redundancy**
- Repetitive boilerplate code needed for each message send
- Complex registration and management of message listeners
- Exception handling and reconnection logic needs repeated implementation

**3. Maintenance Difficulties**
- Complex connection state management
- Lack of unified logging and monitoring
- Chaotic management when connecting to multiple MQTT services

**4. High Learning Curve**
- Need deep understanding of MQTT protocol details
- Eclipse Paho client API is relatively complex
- Lack of out-of-the-box best practices

### 💡 Solution

Based on these pain points, we decided to develop MQTT Macchiatto, an MQTT wrapper tool designed specifically for Spring Boot:

**Simplified Configuration**
```yaml
# Only simple configuration needed
mqtt:
  macchiatto:
    broker: tcp://localhost:1883
    client-id: my-client
    username: admin
    password: password
```

**Elegant API**
```java
// Send message with just one line of code
mqttPush.push("topic/test", "Hello World", MQTTQos.AT_LEAST_ONCE);

// One line subscription+one line processing+one line startup
MqttPut.of("demo/hello")
    .response((topic, msg) -> System.out.println("received: " + msg))
    .start();
```

**Automated Management**
- Automatic connection management and reconnection
- Unified exception handling
- Built-in monitoring and logging

### 🎯 Design Goals

**1. Out-of-the-Box**
- Zero configuration startup (using default settings)
- Auto-configuration and dependency injection
- Built-in best practices

**2. Simple and Elegant**
- Annotation-driven development approach
- Fluent API with method chaining
- Aligned with Spring Boot design philosophy

**3. Feature Complete**
- Support for all MQTT QoS levels
- Support for multiple service connections
- Support for message filtering and routing

**4. Production Ready**
- Built-in reconnection mechanism
- Comprehensive error handling
- Performance monitoring and metrics

### 🌟 Name Origin

**Macchiatto** is an Italian coffee known for its distinct layers and smooth taste. We hope MQTT Macchiatto can be like a carefully crafted macchiatto:

- **Distinct Layers**: Clear architectural design with well-defined responsibilities
- **Smooth Taste**: Fluent development experience with clean APIs
- **Carefully Crafted**: Thoughtfully designed and optimized
- **Memorable**: Make developers fall in love with MQTT development

### 🚀 Development History

**v0.1.0** - Initial Release
- Basic message sending and receiving functionality
- Simple configuration management

**v0.1.1** - Feature Enhancement
- Added multiple service connection support
- Optimized reconnection mechanism

**v0.1.2** - Stability Improvement
- Fixed known issues
- Enhanced error handling

**v0.1.3** - Current Version
- Improved documentation system
- Added more examples
- Performance optimization

**V0.1.4** - Optimize reconnection
- Enhance reconnection
- Enhance MqttPut`

**V0.1.5** - Optimized Listening (Current Version)
- Optimize the calling and writing experience of MqttPut
- Fix omissions and errors in the document

### 🤝 Community Contribution

MQTT Macchiatto is an open-source project, and we welcome community contributions and feedback:

- **Bug Reports**: Help us discover and fix issues
- **Feature Suggestions**: Propose new feature requirements
- **Code Contributions**: Submit Pull Requests
- **Documentation Improvements**: Enhance documentation and examples

### 📞 Contact Us

If you encounter problems during use or have any suggestions, feel free to contact us:

- **Email**: guolvaita@gmail.com
- **WeChat**: AfterTheMoonlight
- **GitHub Issues**: [Submit Issues](https://github.com/rururunu/MQTT-Macchiatto/issues)

---

> 💡 **Developer's Message**
> 
> We hope MQTT Macchiatto can make your MQTT development simpler and more enjoyable. If this project helps you, please give us a ⭐ Star - it's the greatest encouragement for us!