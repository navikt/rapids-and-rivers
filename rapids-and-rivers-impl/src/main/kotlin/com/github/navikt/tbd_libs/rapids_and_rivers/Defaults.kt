package com.github.navikt.tbd_libs.rapids_and_rivers

import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.clients.producer.ProducerConfig
import java.net.InetAddress
import java.time.Duration
import java.util.*

fun createDefaultKafkaRapid(
    factory: ConsumerProducerFactory,
    consumerGroupId: String,
    instanceId: String,
    topic: String,
    meterRegistry: MeterRegistry,
    autoCommit: Boolean = false,
    extraTopics: List<String>,
    offsetResetStrategy: OffsetResetStrategy = OffsetResetStrategy.LATEST,
    maxPollRecords: Int = ConsumerConfig.DEFAULT_MAX_POLL_RECORDS,
    maxIntervalMs: Long = Duration.ofMinutes(30).toMillis()
): KafkaRapid {
    val consumerProperties = Properties().apply {
        put(ConsumerConfig.CLIENT_ID_CONFIG, "consumer-${instanceId}")
        put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, instanceId)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetResetStrategy.name.lowercase())
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "$maxPollRecords")
        put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "$maxIntervalMs")
    }
    val producerProperties = Properties().apply {
        put(ProducerConfig.CLIENT_ID_CONFIG, "producer-${instanceId}")
    }

    return KafkaRapid(
        factory = factory,
        groupId = consumerGroupId,
        rapidTopic = topic,
        meterRegistry = meterRegistry,
        consumerProperties = consumerProperties,
        producerProperties = producerProperties,
        autoCommit = autoCommit,
        extraTopics = extraTopics
    )
}

fun createDefaultKafkaRapidFromEnv(factory: ConsumerProducerFactory, meterRegistry: MeterRegistry, env: Map<String, String> = System.getenv()): KafkaRapid {
    val resetPolicy = env["KAFKA_RESET_POLICY"]?.let { OffsetResetStrategy.valueOf(it.uppercase()) } ?: OffsetResetStrategy.LATEST
    return createDefaultKafkaRapid(
        factory = factory,
        consumerGroupId = env.getValue("KAFKA_CONSUMER_GROUP_ID"),
        instanceId = generateInstanceId(env),
        topic = env.getValue("KAFKA_RAPID_TOPIC"),
        meterRegistry = meterRegistry,
        autoCommit = env["KAFKA_AUTO_COMMIT"]?.toBoolean() ?: false,
        extraTopics = env["KAFKA_EXTRA_TOPIC"]?.split(',')?.map(String::trim) ?: emptyList(),
        offsetResetStrategy = resetPolicy,
        maxPollRecords = env["KAFKA_MAX_RECORDS"]?.toInt() ?: ConsumerConfig.DEFAULT_MAX_POLL_RECORDS,
        maxIntervalMs = env["KAFKA_MAX_POLL_INTERVAL_MS"]?.toLong() ?: Duration.ofMinutes(30).toMillis()
    )
}

private fun generateInstanceId(env: Map<String, String>): String {
    if (env.containsKey("NAIS_APP_NAME")) return InetAddress.getLocalHost().hostName
    return UUID.randomUUID().toString()
}
