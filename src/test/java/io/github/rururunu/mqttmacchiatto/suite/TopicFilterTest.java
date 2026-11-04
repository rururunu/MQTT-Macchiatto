package io.github.rururunu.mqttmacchiatto.suite;

import io.github.rururunu.mqttmacchiatto.internal.TopicFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicFilterTest {
    @Test
    void literalsAndWildcards() {
        assertTrue(TopicFilter.compile("a/b").matches("a/b"));
        assertFalse(TopicFilter.compile("a/b").matches("a/b/c"));
        assertFalse(TopicFilter.compile("a/b").matches("a/b/"));
        assertTrue(TopicFilter.compile("a/#").matches("a"));
        assertTrue(TopicFilter.compile("a/#").matches("a/b"));
        assertTrue(TopicFilter.compile("a/#").matches("a/b/c"));
        assertFalse(TopicFilter.compile("a/#").matches("b"));
        assertTrue(TopicFilter.compile("a/+").matches("a/b"));
        assertFalse(TopicFilter.compile("a/+").matches("a/b/c"));
        assertFalse(TopicFilter.compile("a/+").matches("a"));
        assertTrue(TopicFilter.compile("+/+").matches("/b"));
        assertTrue(TopicFilter.compile("#").matches("a/b"));
        assertFalse(TopicFilter.compile("#").matches("$SYS/a"));
        assertFalse(TopicFilter.compile("+/a").matches("$SYS/a"));
        assertTrue(TopicFilter.compile("$SYS/#").matches("$SYS/a"));
    }

    @Test
    void rejectsAWildcardThatIsNotAWholeLevel() {
        assertThrows(IllegalArgumentException.class, () -> TopicFilter.compile("a/+b"));
        assertThrows(IllegalArgumentException.class, () -> TopicFilter.compile("a/#/b"));
    }
}
