package no.nav.helse.rapids_rivers

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.testcontainers.containers.KafkaContainer
import java.util.*

class LocalKafkaConfig(private val kafkaContainer: KafkaContainer) : Config {
    override fun producerConfig(properties: Properties): Properties {
        return properties.apply {
            connectionConfig(this)
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
            put(ProducerConfig.LINGER_MS_CONFIG, "0")
            put(ProducerConfig.RETRIES_CONFIG, "0")
        }
    }

    override fun consumerConfig(groupId: String, properties: Properties): Properties {
        return properties.apply {
            connectionConfig(this)
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
    }

    override fun adminConfig(properties: Properties): Properties {
        return properties.apply {
            connectionConfig(this)
        }
    }

    private fun connectionConfig(properties: Properties) = properties.apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.bootstrapServers)
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
        put(SaslConfigs.SASL_MECHANISM, "PLAIN")
    }
}