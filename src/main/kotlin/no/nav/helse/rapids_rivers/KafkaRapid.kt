package no.nav.helse.rapids_rivers

import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.*
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
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
    private val autoCommit =
        consumerConfig[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG]?.let { if (it is Boolean) it else "true" == "$it".lowercase() }
            ?: false
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
        publish(ProducerRecord(rapidTopic, message))
    }

    override fun publish(key: String, message: String) {
        publish(ProducerRecord(rapidTopic, key, message))
    }

    override fun rapidName(): String {
        return rapidTopic
    }

    private fun publish(producerRecord: ProducerRecord<String, String>) {
        check(!producerClosed.get()) { "can't publish messages when producer is closed" }
        producer.send(producerRecord) { _, err ->
            if (err == null || !isFatalError(err)) return@send
            log.error("Shutting down rapid due to fatal error: ${err.message}", err)
            stop()
        }
    }

    override fun start() {
        log.info("starting rapid")
        if (Started == running.getAndSet(Started)) return log.info("rapid already started")
        consumeMessages()
    }

    override fun stop() {
        log.info("stopping rapid")
        if (Stopped == running.getAndSet(Stopped)) return log.info("rapid already stopped")
        notifyShutdownSignal()
        tryAndLog { producer.flush() }
        consumer.wakeup()
    }

    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
        if (partitions.isEmpty()) return
        log.info("partitions assigned: $partitions")
        ensureConsumerPosition(partitions)
        notifyReady()
    }

    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
        log.info("partitions revoked: $partitions")
        partitions.forEach { it.commitSync() }
        notifyNotReady()
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
            log.info(
                "due to an error during processing, positions are reset to each next message (after each record that was processed OK):" +
                        currentPositions.map { "\tpartition=${it.key}, offset=${it.value}" }
                            .joinToString(separator = "\n", prefix = "\n", postfix = "\n"), err
            )
            currentPositions.forEach { (partition, offset) -> consumer.seek(partition, offset) }
            throw err
        } finally {
            consumer.commitSync(currentPositions.mapValues { (_, offset) -> offsetMetadata(offset) })
        }
    }

    private fun onRecord(record: ConsumerRecord<String, String>) {
        withMDC(recordDiganostics(record)) {
            val context = KeyMessageContext(this, record.key())
            notifyMessage(record.value(), context)
        }
    }

    private fun consumeMessages() {
        var lastException: Exception? = null
        try {
            notifyStartup()
            ready.set(true)
            consumer.subscribe(topics, this)
            while (running.get()) {
                consumer.poll(Duration.ofSeconds(1)).also {
                    withMDC(pollDiganostics(it)) {
                        onRecords(it)
                    }
                }
            }
        } catch (err: WakeupException) {
            // throw exception if we have not been told to stop
            if (running.get()) throw err
        } catch (err: Exception) {
            lastException = err
            throw err
        } finally {
            notifyShutdown()
            closeResources(lastException)
        }
    }

    private fun pollDiganostics(records: ConsumerRecords<String, String>) = mapOf(
        "rapids_poll_id" to "${UUID.randomUUID()}",
        "rapids_poll_time" to "${LocalDateTime.now()}",
        "rapids_poll_count" to "${records.count()}"
    )

    private fun recordDiganostics(record: ConsumerRecord<String, String>) = mapOf(
        "rapids_record_id" to "${UUID.randomUUID()}",
        "rapids_record_before_notify_time" to "${LocalDateTime.now()}",
        "rapids_record_produced_time" to "${record.timestamp()}",
        "rapids_record_produced_time_type" to "${record.timestampType()}",
        "rapids_record_topic" to record.topic(),
        "rapids_record_partition" to "${record.partition()}",
        "rapids_record_offset" to "${record.offset()}"
    )

    private fun TopicPartition.commitSync() {
        if (autoCommit) return
        val offset = consumer.position(this)
        log.info("committing offset offset=$offset for partition=$this")
        consumer.commitSync(mapOf(this to offsetMetadata(offset)))
    }

    private fun offsetMetadata(offset: Long): OffsetAndMetadata {
        val clientId = consumer.groupMetadata().groupInstanceId().map { "\"$it\"" }.orElse("null")
        @Language("JSON")
        val metadata = """{"time": "${LocalDateTime.now()}","groupInstanceId": $clientId}"""
        return OffsetAndMetadata(offset, metadata)
    }

    private fun closeResources(lastException: Exception?) {
        if (Started == running.getAndSet(Stopped)) {
            log.warn("stopped consuming messages due to an error", lastException)
        } else {
            log.info("stopped consuming messages after receiving stop signal")
        }
        producerClosed.set(true)
        tryAndLog(producer::flush)
        tryAndLog(producer::close)
        tryAndLog(consumer::close)
    }

    private fun tryAndLog(block: () -> Unit) {
        try {
            block()
        } catch (err: Exception) {
            log.error(err.message, err)
        }
    }

    internal fun getMetrics() = listOf(KafkaClientMetrics(consumer), KafkaClientMetrics(producer))

    companion object {
        private const val Stopped = false
        private const val Started = true

        fun create(kafkaConfig: KafkaConfig, topic: String, extraTopics: List<String> = emptyList()) = KafkaRapid(
            consumerConfig = kafkaConfig.consumerConfig(),
            producerConfig = kafkaConfig.producerConfig(),
            rapidTopic = topic,
            extraTopics = extraTopics
        )

        private fun isFatalError(err: Exception) = when (err) {
            is InvalidTopicException,
            is RecordBatchTooLargeException,
            is RecordTooLargeException,
            is UnknownServerException,
            is AuthorizationException -> true
            else -> false
        }
    }
}
