/**
 * MQTT 3.1.1 and MQTT 5 client for Java 25 and newer.
 *
 * One session owns the socket, the in-flight window and the subscriptions.
 * Publish and subscribe return plain Java types, with no framework on the runtime path.
 *
 * @jenesis.release 25
 */
module io.github.rururunu.mqttmacchiatto {
    exports io.github.rururunu.mqttmacchiatto;
}
