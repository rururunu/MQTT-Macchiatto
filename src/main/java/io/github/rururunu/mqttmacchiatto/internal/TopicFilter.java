package io.github.rururunu.mqttmacchiatto.internal;

/**
 * Compiled MQTT 3.1.1 topic filter. Matching does not allocate.
 * A leading {@code +} or {@code #} does not match a topic that starts with {@code $}.
 */
public final class TopicFilter {
    private final String filter;
    private final String[] levels;
    private final boolean wild;
    private final boolean firstIsWild;

    private TopicFilter(String filter, String[] levels, boolean wild, boolean firstIsWild) {
        this.filter = filter;
        this.levels = levels;
        this.wild = wild;
        this.firstIsWild = firstIsWild;
    }

    public static TopicFilter compile(String filter) {
        if (filter == null || filter.isEmpty()) {
            throw new IllegalArgumentException("topic filter is required");
        }
        if (filter.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("topic filter contains NUL");
        }
        String[] levels = filter.split("/", -1);
        boolean wild = false;
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.equals("#")) {
                if (i != levels.length - 1) {
                    throw new IllegalArgumentException("'#' must be the last filter level");
                }
                wild = true;
            } else if (level.equals("+")) {
                wild = true;
            } else if (level.indexOf('+') >= 0 || level.indexOf('#') >= 0) {
                throw new IllegalArgumentException("wildcard must occupy a whole filter level");
            }
        }
        boolean firstIsWild = levels[0].equals("+") || levels[0].equals("#");
        return new TopicFilter(filter, levels, wild, firstIsWild);
    }

    public String text() {
        return filter;
    }

    public boolean matches(String topic) {
        if (!wild) {
            return filter.equals(topic);
        }
        if (firstIsWild && !topic.isEmpty() && topic.charAt(0) == '$') {
            return false;
        }
        int topicIndex = 0;
        int topicLength = topic.length();
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.equals("#")) {
                return true;
            }
            int slash = topic.indexOf('/', topicIndex);
            int end = slash < 0 ? topicLength : slash;
            if (!level.equals("+")) {
                int length = end - topicIndex;
                if (level.length() != length || !topic.regionMatches(topicIndex, level, 0, length)) {
                    return false;
                }
            }
            if (slash < 0) {
                if (i == levels.length - 1) {
                    return true;
                }
                return i + 1 == levels.length - 1 && levels[i + 1].equals("#");
            }
            if (i == levels.length - 1) {
                return false;
            }
            topicIndex = slash + 1;
        }
        return topicIndex >= topicLength;
    }
}
