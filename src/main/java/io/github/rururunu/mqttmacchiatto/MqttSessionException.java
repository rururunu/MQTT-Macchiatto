package io.github.rururunu.mqttmacchiatto;

/**
 * Connection or protocol failure. {@code reasonCode} is a CONNACK code when the broker refused
 * the connection, and {@code -1} otherwise.
 */
public class MqttSessionException extends RuntimeException {
    public static final int NONE = -1;
    public static final int UNACCEPTABLE_PROTOCOL = 1;
    public static final int IDENTIFIER_REJECTED = 2;
    public static final int SERVER_UNAVAILABLE = 3;
    public static final int BAD_CREDENTIALS = 4;
    public static final int NOT_AUTHORIZED = 5;

    private final int reasonCode;
    private final boolean retryable;

    public MqttSessionException(String message) {
        this(message, null, NONE, true);
    }

    public MqttSessionException(String message, Throwable cause) {
        this(message, cause, NONE, true);
    }

    public MqttSessionException(String message, int reasonCode) {
        this(message, null, reasonCode, reasonCode == SERVER_UNAVAILABLE || reasonCode == NONE);
    }

    public MqttSessionException(String message, Throwable cause, int reasonCode) {
        this(message, cause, reasonCode, reasonCode == SERVER_UNAVAILABLE || reasonCode == NONE);
    }

    public MqttSessionException(String message, Throwable cause, int reasonCode, boolean retryable) {
        super(message, cause);
        this.reasonCode = reasonCode;
        this.retryable = retryable;
    }

    public int reasonCode() {
        return reasonCode;
    }

    /** IO failures and CONNACK 3 are retried. A malformed packet or a credential refusal is not. */
    public boolean retryable() {
        return retryable && !permanent();
    }

    /** Codes 1, 2, 4 and 5 will not succeed by dialing again. */
    public boolean permanent() {
        return reasonCode == UNACCEPTABLE_PROTOCOL
                || reasonCode == IDENTIFIER_REJECTED
                || reasonCode == BAD_CREDENTIALS
                || reasonCode == NOT_AUTHORIZED;
    }
}
