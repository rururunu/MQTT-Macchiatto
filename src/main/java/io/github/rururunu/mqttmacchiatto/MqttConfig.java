package io.github.rururunu.mqttmacchiatto;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable session configuration. Plain TCP endpoints are {@code tcp://}, {@code mqtt://},
 * {@code ssl://}, {@code tls://}, and {@code mqtts://}. WebSocket endpoints are {@code ws://}
 * and {@code wss://}, with an optional path such as {@code ws://127.0.0.1:8080/mqtt}.
 * IPv6 hosts are written as {@code tcp://[::1]:1883}.
 * Additional endpoints are tried, in order, when a dial or a retryable refusal fails.
 */
public final class MqttConfig {
    private final String[] endpoints;
    private final String clientId;
    private final String username;
    private final String password;
    private final boolean cleanSession;
    private final int keepAliveSeconds;
    private final int connectTimeoutMillis;
    private final int maxInflight;
    private final int maxQueued;
    private final int retryMillis;
    private final int maxPacketBytes;
    private final MqttWill will;
    private final ReconnectPolicy reconnect;
    private final MqttEvents events;
    private final SSLContext sslContext;
    private final boolean verifyHostname;
    private final String serverName;
    private final HostnameVerifier hostnameVerifier;
    private final Map<String, String> webSocketHeaders;
    private final MqttProtocol protocol;
    private final MqttProtocol protocolFallback;
    private final Path persistence;
    private final MqttStore store;
    private final MqttOverflow overflow;
    private final long sessionExpirySeconds;
    private final int topicAliasMaximum;
    private final MqttUserProperty[] connectUserProperties;
    private final boolean requestResponseInfo;
    private final boolean requestProblemInfo;
    private final String authMethod;
    private final byte[] authData;
    private final MqttAuthenticator authenticator;

    private MqttConfig(Builder builder) {
        this.endpoints = builder.endpoints.clone();
        this.clientId = builder.clientId;
        this.username = builder.username;
        this.password = builder.password;
        this.cleanSession = builder.cleanSession;
        this.keepAliveSeconds = builder.keepAliveSeconds;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.maxInflight = builder.maxInflight;
        this.maxQueued = builder.maxQueued;
        this.retryMillis = builder.retryMillis;
        this.maxPacketBytes = builder.maxPacketBytes;
        this.will = builder.will;
        this.reconnect = builder.reconnect;
        this.events = builder.events;
        this.sslContext = builder.sslContext;
        this.verifyHostname = builder.verifyHostname;
        this.serverName = builder.serverName;
        this.hostnameVerifier = builder.hostnameVerifier;
        this.webSocketHeaders = Map.copyOf(builder.webSocketHeaders);
        this.protocol = builder.protocol;
        this.protocolFallback = builder.protocolFallback;
        this.persistence = builder.persistence;
        this.store = builder.store;
        this.overflow = builder.overflow;
        this.sessionExpirySeconds = builder.sessionExpirySeconds;
        this.topicAliasMaximum = builder.topicAliasMaximum;
        this.connectUserProperties = builder.connectUserProperties.clone();
        this.requestResponseInfo = builder.requestResponseInfo;
        this.requestProblemInfo = builder.requestProblemInfo;
        this.authMethod = builder.authMethod;
        this.authData = builder.authData == null ? null : builder.authData.clone();
        this.authenticator = builder.authenticator;
    }

    /** The first endpoint. */
    public String endpoint() {
        return endpoints[0];
    }

    public List<String> endpoints() {
        return List.of(endpoints);
    }

    public String clientId() {
        return clientId;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public boolean cleanSession() {
        return cleanSession;
    }

    public int keepAliveSeconds() {
        return keepAliveSeconds;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int maxInflight() {
        return maxInflight;
    }

    public int maxQueued() {
        return maxQueued;
    }

    public int retryMillis() {
        return retryMillis;
    }

    public int maxPacketBytes() {
        return maxPacketBytes;
    }

    public MqttWill will() {
        return will;
    }

    public ReconnectPolicy reconnect() {
        return reconnect;
    }

    public MqttEvents events() {
        return events;
    }

    public SSLContext sslContext() {
        return sslContext;
    }

    /** When false, TLS does not check the certificate against the host name. The default is true. */
    public boolean verifyHostname() {
        return verifyHostname;
    }

    /** Server Name Indication override. Null sends the endpoint host. */
    public String serverName() {
        return serverName;
    }

    public HostnameVerifier hostnameVerifier() {
        return hostnameVerifier;
    }

    public Map<String, String> webSocketHeaders() {
        return webSocketHeaders;
    }

    public MqttProtocol protocol() {
        return protocol;
    }

    /** Tried once on the same endpoint after CONNACK "unacceptable protocol version". Null disables it. */
    public MqttProtocol protocolFallback() {
        return protocolFallback;
    }

    /** Directory for the durable session image, or null when a {@link #store()} is used or nothing is durable. */
    public Path persistence() {
        return persistence;
    }

    public MqttStore store() {
        return store;
    }

    public MqttOverflow overflow() {
        return overflow;
    }

    /**
     * MQTT 5 session expiry. {@code -1} uses the maximum when {@link #cleanSession()} is false
     * and zero when it is true. Ignored for MQTT 3.1.1.
     */
    public long sessionExpirySeconds() {
        return sessionExpirySeconds;
    }

    /** MQTT 5 topic alias maximum this client is willing to accept. Zero disables inbound aliases. */
    public int topicAliasMaximum() {
        return topicAliasMaximum;
    }

    public MqttUserProperty[] connectUserProperties() {
        return connectUserProperties.clone();
    }

    public boolean requestResponseInfo() {
        return requestResponseInfo;
    }

    public boolean requestProblemInfo() {
        return requestProblemInfo;
    }

    public String authMethod() {
        return authMethod;
    }

    public byte[] authData() {
        return authData == null ? null : authData.clone();
    }

    public MqttAuthenticator authenticator() {
        return authenticator;
    }

    public static Builder builder(String endpoint, String... more) {
        return new Builder(endpoint, more);
    }

    public static final class Builder {
        private final String[] endpoints;
        private String clientId;
        private String username;
        private String password;
        private boolean cleanSession = true;
        private int keepAliveSeconds = 60;
        private int connectTimeoutMillis = 10_000;
        private int maxInflight = 32;
        private int maxQueued = 1024;
        private int retryMillis = 5_000;
        private int maxPacketBytes = 1024 * 1024;
        private MqttWill will;
        private ReconnectPolicy reconnect = ReconnectPolicy.standard();
        private MqttEvents events = new MqttEvents() {
        };
        private SSLContext sslContext;
        private boolean verifyHostname = true;
        private String serverName;
        private HostnameVerifier hostnameVerifier;
        private final Map<String, String> webSocketHeaders = new LinkedHashMap<>();
        private MqttProtocol protocol = MqttProtocol.MQTT_3_1_1;
        private MqttProtocol protocolFallback;
        private Path persistence;
        private MqttStore store;
        private MqttOverflow overflow = MqttOverflow.REJECT;
        private long sessionExpirySeconds = -1;
        private int topicAliasMaximum = 8;
        private MqttUserProperty[] connectUserProperties = MqttProperties.NO_USERS;
        private boolean requestResponseInfo;
        private boolean requestProblemInfo = true;
        private String authMethod;
        private byte[] authData;
        private MqttAuthenticator authenticator;

        private Builder(String endpoint, String... more) {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("endpoint is required");
            }
            String[] all = new String[1 + (more == null ? 0 : more.length)];
            all[0] = endpoint;
            if (more != null) {
                System.arraycopy(more, 0, all, 1, more.length);
            }
            this.endpoints = all;
        }

        /** Null generates a short id. An empty id is sent as-is and requires a clean session. */
        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder cleanSession(boolean cleanSession) {
            this.cleanSession = cleanSession;
            return this;
        }

        public Builder keepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
            return this;
        }

        public Builder connectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder maxInflight(int maxInflight) {
            this.maxInflight = maxInflight;
            return this;
        }

        public Builder maxQueued(int maxQueued) {
            this.maxQueued = maxQueued;
            return this;
        }

        public Builder retryMillis(int retryMillis) {
            this.retryMillis = retryMillis;
            return this;
        }

        public Builder maxPacketBytes(int maxPacketBytes) {
            this.maxPacketBytes = maxPacketBytes;
            return this;
        }

        public Builder will(MqttWill will) {
            this.will = will;
            return this;
        }

        public Builder reconnect(ReconnectPolicy reconnect) {
            this.reconnect = reconnect;
            return this;
        }

        public Builder events(MqttEvents events) {
            this.events = events;
            return this;
        }

        /** Used for {@code ssl://}, {@code tls://}, {@code mqtts://}, and {@code wss://}. Null uses {@link SSLContext#getDefault()}. */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        public Builder verifyHostname(boolean verifyHostname) {
            this.verifyHostname = verifyHostname;
            return this;
        }

        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        public Builder hostnameVerifier(HostnameVerifier hostnameVerifier) {
            this.hostnameVerifier = hostnameVerifier;
            return this;
        }

        public Builder webSocketHeader(String name, String value) {
            if (name == null || value == null || name.isBlank()) {
                throw new IllegalArgumentException("websocket header name and value are required");
            }
            if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || name.indexOf(':') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("websocket header contains a line break");
            }
            webSocketHeaders.put(name, value);
            return this;
        }

        public Builder protocol(MqttProtocol protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder protocolFallback(MqttProtocol protocolFallback) {
            this.protocolFallback = protocolFallback;
            return this;
        }

        /**
         * Persists unacknowledged QoS 1 and 2 messages and subscriptions in this directory.
         * The same client id and a clean session of false resume them after the process restarts.
         */
        public Builder persistence(Path directory) {
            this.persistence = directory;
            return this;
        }

        /** Custom durable image. Replaces {@link #persistence(Path)}. */
        public Builder store(MqttStore store) {
            this.store = store;
            return this;
        }

        public Builder overflow(MqttOverflow overflow) {
            this.overflow = overflow;
            return this;
        }

        public Builder sessionExpirySeconds(long sessionExpirySeconds) {
            this.sessionExpirySeconds = sessionExpirySeconds;
            return this;
        }

        public Builder topicAliasMaximum(int topicAliasMaximum) {
            this.topicAliasMaximum = topicAliasMaximum;
            return this;
        }

        public Builder connectUserProperty(String name, String value) {
            MqttUserProperty[] next = Arrays.copyOf(connectUserProperties, connectUserProperties.length + 1);
            next[connectUserProperties.length] = new MqttUserProperty(name, value);
            connectUserProperties = next;
            return this;
        }

        public Builder requestResponseInfo(boolean requestResponseInfo) {
            this.requestResponseInfo = requestResponseInfo;
            return this;
        }

        public Builder requestProblemInfo(boolean requestProblemInfo) {
            this.requestProblemInfo = requestProblemInfo;
            return this;
        }

        public Builder authMethod(String authMethod) {
            this.authMethod = authMethod;
            return this;
        }

        /** Not copied until {@link #build()}. */
        public Builder authData(byte[] authData) {
            this.authData = authData;
            return this;
        }

        public Builder authenticator(MqttAuthenticator authenticator) {
            this.authenticator = authenticator;
            return this;
        }

        public MqttConfig build() {
            for (String endpoint : endpoints) {
                if (endpoint == null || endpoint.isBlank()) {
                    throw new IllegalArgumentException("endpoint is required");
                }
            }
            if (keepAliveSeconds < 0 || keepAliveSeconds > 65_535) {
                throw new IllegalArgumentException("keepAliveSeconds out of range");
            }
            if (connectTimeoutMillis <= 0) {
                throw new IllegalArgumentException("connectTimeoutMillis must be > 0");
            }
            if (maxInflight < 1 || maxInflight > 65_535) {
                throw new IllegalArgumentException("maxInflight out of range");
            }
            if (maxQueued < 1) {
                throw new IllegalArgumentException("maxQueued must be > 0");
            }
            if (retryMillis <= 0) {
                throw new IllegalArgumentException("retryMillis must be > 0");
            }
            if (maxPacketBytes < 8 || maxPacketBytes > 268_435_455) {
                throw new IllegalArgumentException("maxPacketBytes out of range");
            }
            if (password != null && (username == null || username.isEmpty())) {
                throw new IllegalArgumentException("password requires username");
            }
            if (clientId != null && clientId.isEmpty() && !cleanSession) {
                throw new IllegalArgumentException("an empty client id requires a clean session");
            }
            if (reconnect == null || events == null || protocol == null || overflow == null) {
                throw new IllegalArgumentException("reconnect, events, protocol, and overflow are required");
            }
            if (protocolFallback == protocol) {
                throw new IllegalArgumentException("protocol fallback must differ from the protocol");
            }
            if (persistence != null && store != null) {
                throw new IllegalArgumentException("use either a persistence directory or a store");
            }
            if (sessionExpirySeconds < -1 || sessionExpirySeconds > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("sessionExpirySeconds out of range");
            }
            if (topicAliasMaximum < 0 || topicAliasMaximum > 65_535) {
                throw new IllegalArgumentException("topicAliasMaximum out of range");
            }
            if ((authData != null || authenticator != null) && (authMethod == null || authMethod.isEmpty())) {
                throw new IllegalArgumentException("enhanced authentication requires a method");
            }
            for (MqttUserProperty property : connectUserProperties) {
                if (property.name() == null || property.value() == null) {
                    throw new IllegalArgumentException("connect user property requires a name and a value");
                }
            }
            return new MqttConfig(this);
        }
    }
}
