package io.github.rururunu.mqttmacchiatto;

/**
 * Replaces the built-in session file. {@link #save} receives the whole image and must make
 * that image durable before it returns. A crash may lose only an image that has not been saved.
 * {@link #load} returns {@link MqttSessionImage#EMPTY} when nothing is stored.
 */
public interface MqttStore {
    MqttSessionImage load();

    void save(MqttSessionImage image);

    void discard();
}
