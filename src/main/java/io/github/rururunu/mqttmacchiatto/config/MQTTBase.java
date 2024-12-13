package io.github.rururunu.mqttmacchiatto.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MQTT 配置连接的基础信息
 */
@Component
@ConfigurationProperties(prefix = "mto-mqtt")
public class MQTTBase implements InitializingBean {

    private String host;

    private String username;

    private String password;

    private Integer timeout;

    private Integer keepalive;

    private Integer reconnectFrequencyMs;

    // 连接Host
    public static String HOST;

    // 用户名
    public static String USER_NAME;

    // 密码
    public static String PASSWORD;

    // 连接超时时间
    public static Integer TIMEOUT;

    // 心跳检测时间
    public static Integer KEEP_ALIVE;

    // 重连频率ms
    public static Integer RECONNECT_FREQUENCY_MS;

    @Override
    public void afterPropertiesSet() throws Exception {
        HOST = getHost();
        USER_NAME = getUsername();
        PASSWORD = getPassword();
        TIMEOUT = getTimeout();
        KEEP_ALIVE = getKeepalive();
        RECONNECT_FREQUENCY_MS = getReconnectFrequencyMs();
    }

    public Integer getReconnectFrequencyMs() {
        return reconnectFrequencyMs;
    }

    public void setReconnectFrequencyMs(Integer reconnectFrequencyMs) {
        this.reconnectFrequencyMs = reconnectFrequencyMs;
    }

    public Integer getKeepalive() {
        return keepalive;
    }

    public void setKeepalive(Integer keepalive) {
        this.keepalive = keepalive;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

}
