package io.github.rururunu.mqttmacchiatto;

/**
 * Delay before the next connection attempt. Attempt numbers start at 1.
 * A negative delay means the session stops trying.
 */
public record ReconnectPolicy(long initialDelayMillis, long maxDelayMillis, double multiplier, int maxAttempts) {

    public ReconnectPolicy {
        if (initialDelayMillis < 0 || maxDelayMillis < 0) {
            throw new IllegalArgumentException("delay must be >= 0");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0");
        }
    }

    /** Do not reconnect. The first attempt is still made by {@code connect}. */
    public static ReconnectPolicy none() {
        return new ReconnectPolicy(0, 0, 1.0, 0);
    }

    /** 200 ms, doubling, capped at 10 s. */
    public static ReconnectPolicy standard() {
        return new ReconnectPolicy(200, 10_000, 2.0, Integer.MAX_VALUE);
    }

    public long delayMillis(int attempt) {
        if (attempt < 1 || attempt > maxAttempts) {
            return -1L;
        }
        double delay = initialDelayMillis * Math.pow(multiplier, attempt - 1.0);
        if (!Double.isFinite(delay) || delay >= maxDelayMillis) {
            return maxDelayMillis;
        }
        return (long) delay;
    }

    /**
     * Equal jitter around {@link #delayMillis}: half of the base delay plus a random share of the
     * rest. {@code unitRandom} returns a value in {@code [0, 1)}. A fleet therefore does not dial
     * on the same millisecond, and a retry is never longer than the base delay.
     */
    public long jitteredDelayMillis(int attempt, java.util.function.DoubleSupplier unitRandom) {
        long base = delayMillis(attempt);
        if (base <= 1) {
            return base;
        }
        double unit = unitRandom.getAsDouble();
        if (unit < 0.0) {
            unit = 0.0;
        } else if (unit > 0.999999) {
            unit = 0.999999;
        }
        long half = base / 2;
        return half + (long) (unit * (base - half));
    }
}
