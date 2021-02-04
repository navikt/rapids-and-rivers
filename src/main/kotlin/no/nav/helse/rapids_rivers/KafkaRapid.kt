package no.nav.helse.rapids_rivers

import org.apache.kafka.clients.consumer.*
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
    private val producerClosed = AtomicBoolean(false)

    private val stringDeserializer = StringDeserializer()
    private val stringSerializer = StringSerializer()
    private val autoCommit = consumerConfig[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG]?.let { if (it is Boolean) it else "true" == "$it".toLowerCase() } ?: false
    private val consumer = KafkaConsumer(consumerConfig, stringDeserializer, stringDeserializer)
    private val producer = KafkaProducer(producerConfig, stringSerializer, stringSerializer)

    private val topics = listOf(rapidTopic) + extraTopics

    private var seekToBeginning = false

    init {
        log.info("rapid initialized, autoCommit=$autoCommit")
    }

    fun seekToBeginning() {
        check(Stopped == running.get()) { "cannot reset consumer after rapid has started" }
        seekToBeginning = true
    }

    fun isRunning() = running.get()
    fun isReady() = isRunning() && ready.get()

    override fun publish(message: String) {
        check(!producerClosed.get()) { "can't publish messages when producer is closed" }
        producer.send(ProducerRecord(rapidTopic, message))
    }

    override fun publish(key: String, message: String) {
        check(!producerClosed.get()) { "can't publish messages when producer is closed" }
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
        partitions.forEach { it.commitSync() }
        statusListeners.forEach { it.onNotReady(this) }
    }

    private fun ensureConsumerPosition(partitions: Collection<TopicPartition>) {
        if (!seekToBeginning) return
        log.info("seeking to beginning for $partitions")
        consumer.seekToBeginning(partitions)
        seekToBeginning = false
    }

    private fun onRecords(records: ConsumerRecords<String, String>) {
        if (records.isEmpty) return // poll returns an empty collection in case of rebalancing
        val currentPositions = records
            .groupBy { TopicPartition(it.topic(), it.partition()) }
            .mapValues { it.value.minOf { it.offset() } }
            .toMutableMap()
        try {
            records.onEach { record ->
                onRecord(record)
                currentPositions[TopicPartition(record.topic(), record.partition())] = record.offset() + 1
            }
        } catch (err: Exception) {
            log.info("due to an error during processing, positions are reset to each next message (after each record that was processed OK):" +
                    currentPositions.map { "\tpartition=${it.key}, offset=${it.value}" }.joinToString(separator = "\n", prefix = "\n", postfix = "\n"), err)
            currentPositions.forEach { (partition, offset) -> consumer.seek(partition, offset) }
            throw err
        } finally {
            consumer.commitSync()
        }
    }

    private fun onRecord(record: ConsumerRecord<String, String>) {
        val context = KafkaMessageContext(record, this)
        listeners.forEach { it.onMessage(record.value(), context) }
    }

    private fun consumeMessages() {
        var lastException: Exception? = null
        try {
            statusListeners.forEach { it.onStartup(this) }
            ready.set(true)
            consumer.subscribe(topics, this)
            while (running.get()) {
                consumer.poll(Duration.ofSeconds(1)).also { onRecords(it) }
            }
        } catch (err: WakeupException) {
            // throw exception if we have not been told to stop
            if (running.get()) throw err
        } catch (err: Exception) {
            lastException = err
            throw err
        } finally {
            statusListeners.forEach {
                try {
                    it.onShutdown(this)
                } catch (err: Exception) {
                    log.error("A shutdown callback threw an exception: ${err.message}", err)
                }
            }
            closeResources(lastException)
        }
    }

    private fun TopicPartition.commitSync() {
        if (autoCommit) return
        val offset = consumer.position(this)
        log.info("committing offset offset=$offset for partition=$this")
        consumer.commitSync(mapOf(this to OffsetAndMetadata(offset)))
    }

    private fun closeResources(lastException: Exception?) {
        if (Started == running.getAndSet(Stopped)) {
            log.warn("stopped consuming messages due to an error", lastException)
        } else {
            log.info("stopped consuming messages after receiving stop signal")
        }
        producerClosed.set(true)
        tryAndLog(producer::close)
        tryAndLog(consumer::unsubscribe)
        tryAndLog(consumer::close)
    }

    private fun tryAndLog(block: () -> Unit) {
        try {
            block()
        } catch (err: Exception) {
            log.error(err.message, err)
        }
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
