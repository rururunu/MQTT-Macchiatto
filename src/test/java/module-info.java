/**
 * @jenesis.test io.github.rururunu.mqttmacchiatto
 * @jenesis.release 25
 * @jenesis.pin org.junit.jupiter 5.11.4
 * @jenesis.alias org.openjdk.jmh org.openjdk.jmh/jmh-core
 * @jenesis.pin org.openjdk.jmh/jmh-core 1.37
 * @jenesis.alias org.eclipse.paho.client.mqttv3 org.eclipse.paho/org.eclipse.paho.client.mqttv3
 * @jenesis.pin org.eclipse.paho/org.eclipse.paho.client.mqttv3 1.2.5
 * @jenesis.plugin maven/org.openjdk.jmh/jmh-generator-annprocess
 */
module io.github.rururunu.mqttmacchiatto.test {
    requires io.github.rururunu.mqttmacchiatto;
    requires org.junit.jupiter;
    requires org.openjdk.jmh;
    requires org.eclipse.paho.client.mqttv3;
}
