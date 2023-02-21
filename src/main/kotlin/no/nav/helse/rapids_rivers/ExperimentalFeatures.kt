package no.nav.helse.rapids_rivers

import java.util.concurrent.atomic.AtomicBoolean

object ExperimentalFeatures {
    /**
     * KafkaProducer suggests always calling KafkaProducer::flush() when acting as a filter between two kafka topics to
     * ensure you do not commit KafkaConsumer offsets before kafka has accepted the filter outputs again. This may
     * introduce unwanted slow-downs for some teams, therefore we start by adding this as an experimental feature that
     * is off as default.
     *
     * For more details on when this might be useful see the slack discussion:
     *     https://nav-it.slack.com/archives/C01AAM0R35F/p1676629562246079
     *
     * From KafkaProducer::flush()'es documentation:
     *     This method can be useful when consuming from some input system and producing into Kafka. The flush() call
     *     gives a convenient way to ensure all previously sent messages have actually completed.
     *     This example shows how to consume from one Kafka topic and produce to another Kafka topic:
     *
     *     for (ConsumerRecord<String, String> record: consumer.poll(100))
     *         producer.send(new ProducerRecord("my-topic", record.key(), record.value());
     *     producer.flush();
     *     consumer.commitSync();
     */
    var inlineFlushOnEveryProducerSend: AtomicBoolean = AtomicBoolean(false)
}
