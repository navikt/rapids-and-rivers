package com.github.navikt.tbd_libs.kafka

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.security.auth.SecurityProtocol
import java.io.File
import java.util.*

class AivenConfig(
    private val brokers: List<String>,
    private val truststorePath: String,
    private val truststorePw: String,
    private val keystorePath: String,
    private val keystorePw: String
) : Config {
    companion object {
        val default get() = AivenConfig(
            brokers = requireNotNull(System.getenv("KAFKA_BROKERS")) { "Expected KAFKA_BROKERS" }.split(',').map(String::trim),
            truststorePath = requireNotNull(System.getenv("KAFKA_TRUSTSTORE_PATH")) { "Expected KAFKA_TRUSTSTORE_PATH" },
            truststorePw = requireNotNull(System.getenv("KAFKA_CREDSTORE_PASSWORD")) { "Expected KAFKA_CREDSTORE_PASSWORD" },
            keystorePath = requireNotNull(System.getenv("KAFKA_KEYSTORE_PATH")) { "Expected KAFKA_KEYSTORE_PATH" },
            keystorePw = requireNotNull(System.getenv("KAFKA_CREDSTORE_PASSWORD")) { "Expected KAFKA_CREDSTORE_PASSWORD" }
        )

        private fun String.readFile() =
            File(this).readText(Charsets.UTF_8)
    }

    init {
        check(brokers.isNotEmpty())
    }

    override fun producerConfig(properties: Properties) = Properties().apply {
        putAll(kafkaBaseConfig())
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.LINGER_MS_CONFIG, "0")
        put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
        putAll(properties)
    }

    override fun consumerConfig(groupId: String, properties: Properties) = Properties().apply {
        putAll(kafkaBaseConfig())
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        putAll(properties)
        put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    }

    override fun adminConfig(properties: Properties) = Properties().apply {
        putAll(kafkaBaseConfig())
        putAll(properties)
    }

    private fun kafkaBaseConfig() = Properties().apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, brokers)
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name)
        put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "")
        put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "jks")
        put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PKCS12")
        put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststorePath)
        put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, truststorePw)
        put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, keystorePath)
        put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, keystorePw)
    }
}
