package no.nav.helse.rapids_rivers

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.*

/**
 * usage example:
 * ```
 * val factory = ConsumerProducerFactory(Aiven.default)
 * val consumer = factory.createConsumer("my-consumer-group")
 * ```
  */
class ConsumerProducerFactory(private val config: Config) {
    private val stringDeserializer = StringDeserializer()
    private val stringSerializer = StringSerializer()

    internal fun createConsumer(groupId: String, properties: Properties = Properties()): KafkaConsumer<String, String> {
        return KafkaConsumer(config.consumerConfig(groupId, properties), stringDeserializer, stringDeserializer)
    }

    fun createProducer(properties: Properties = Properties()): KafkaProducer<String, String> {
        return KafkaProducer(config.producerConfig(properties), stringSerializer, stringSerializer)
    }

    fun adminClient(properties: Properties = Properties()) = AdminClient.create(config.adminConfig(properties))
}
