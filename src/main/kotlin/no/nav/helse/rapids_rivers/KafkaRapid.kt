package no.nav.helse.rapids_rivers

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class KafkaRapid(
    consumerConfig: Properties,
    producerConfig: Properties,
    private val rapidTopic: String,
    extraTopics: List<String> = emptyList()
) : RapidsConnection(), ConsumerRebalanceListener {

    private val log = LoggerFactory.getLogger(KafkaRapid::class.java)

    private val running = AtomicBoolean(Stopped)
    private val ready = AtomicBoolean(false)

    private val stringDeserializer = StringDeserializer()
    private val stringSerializer = StringSerializer()
    private val consumer = KafkaConsumer(consumerConfig, stringDeserializer, stringDeserializer)
    private val producer = KafkaProducer(producerConfig, stringSerializer, stringSerializer)

    private val topics = listOf(rapidTopic) + extraTopics

    private var seekToBeginning = false

    fun seekToBeginning() {
        check(Stopped == running.get()) { "cannot reset consumer after rapid has started" }
        seekToBeginning = true
    }

    fun isRunning() = running.get()
    fun isReady() = isRunning() && ready.get()

    override fun publish(message: String) {
        producer.send(ProducerRecord(rapidTopic, message))
    }

    override fun publish(key: String, message: String) {
        producer.send(ProducerRecord(rapidTopic, key, message))
    }

    override fun start() {
        log.info("starting rapid")
        if (Started == running.getAndSet(Started)) return log.info("rapid already started")
        consumeMessages()
    }

    override fun stop() {
        log.info("stopping rapid")
        if (Stopped == running.getAndSet(Stopped)) return log.info("rapid already stopped")
        consumer.wakeup()
    }

    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
        if (partitions.isEmpty()) return
        log.info("partitions assigned: $partitions")
        ensureConsumerPosition(partitions)
        statusListeners.forEach { it.onReady(this) }
    }

    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
        log.info("partitions revoked: $partitions")
        statusListeners.forEach { it.onNotReady(this) }
    }

    private fun ensureConsumerPosition(partitions: Collection<TopicPartition>) {
        if (!seekToBeginning) return
        log.info("seeking to beginning for $partitions")
        consumer.seekToBeginning(partitions)
        seekToBeginning = false
    }

    private fun onRecord(record: ConsumerRecord<String, String>) {
        val context = KafkaMessageContext(record, this)
        listeners.forEach { it.onMessage(record.value(), context) }
    }

    private fun consumeMessages() {
        try {
            statusListeners.forEach { it.onStartup(this) }
            ready.set(true)
            consumer.subscribe(topics, this)
            while (running.get()) {
                consumer.poll(Duration.ofSeconds(1))
                    .forEach(::onRecord)
                    .also { consumer.commitSync() }
            }
        } catch (err: WakeupException) {
            // throw exception if we have not been told to stop
            if (running.get()) throw err
        } finally {
            statusListeners.forEach { it.onShutdown(this) }
            closeResources()
        }
    }

    private fun closeResources() {
        if (Started == running.getAndSet(Stopped)) {
            log.info("stopped consuming messages due to an error")
        } else {
            log.info("stopped consuming messages after receiving stop signal")
        }
        producer.close()
        consumer.unsubscribe()
        consumer.close()
    }

    private class KafkaMessageContext(
        private val record: ConsumerRecord<String, String>,
        private val rapidsConnection: RapidsConnection
    ) : MessageContext {
        override fun send(message: String) {
            if (record.key() == null) return rapidsConnection.publish(message)
            send(record.key(), message)
        }

        override fun send(key: String, message: String) {
            rapidsConnection.publish(key, message)
        }
    }

    companion object {
        private const val Stopped = false
        private const val Started = true

        fun create(kafkaConfig: KafkaConfig, topic: String, extraTopics: List<String> = emptyList()) = KafkaRapid(
            consumerConfig = kafkaConfig.consumerConfig(),
            producerConfig = kafkaConfig.producerConfig(),
            rapidTopic = topic,
            extraTopics = extraTopics
        )
    }
}
