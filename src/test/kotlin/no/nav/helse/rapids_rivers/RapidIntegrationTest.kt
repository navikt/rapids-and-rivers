package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import no.nav.common.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RapidIntegrationTest {
    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val consumerId = "test-app"

    private val testTopic = "a-test-topic"
    private val anotherTestTopic = "a-test-topic"

    private val embeddedKafkaEnvironment = KafkaEnvironment(
        autoStart = false,
        noOfBrokers = 1,
        topicInfos = listOf(testTopic, anotherTestTopic).map { KafkaEnvironment.TopicInfo(it, partitions = 1) },
        withSchemaRegistry = false,
        withSecurity = false
    )

    private lateinit var kafkaProducer: Producer<String, String>
    private lateinit var kafkaConsumer: Consumer<String, String>

    private lateinit var config: KafkaConfig
    private lateinit var rapid: KafkaRapid
    private lateinit var rapidJob: Job

    @BeforeAll
    internal fun setup() {
        embeddedKafkaEnvironment.start()

        kafkaProducer = KafkaProducer(producerProperties(), StringSerializer(), StringSerializer())
        kafkaConsumer = KafkaConsumer(consumerProperties(), StringDeserializer(), StringDeserializer())
        kafkaConsumer.subscribe(listOf(testTopic))

        config = KafkaConfig(
            bootstrapServers = embeddedKafkaEnvironment.brokersURL,
            consumerGroupId = consumerId
        )
    }

    @AfterAll
    internal fun teardown() {
        kafkaConsumer.unsubscribe()
        kafkaConsumer.close()
        kafkaProducer.close()
        embeddedKafkaEnvironment.tearDown()
    }

    @DelicateCoroutinesApi
    @BeforeEach
    internal fun start() {
        rapid = createTestRapid()
        rapid.startNonBlocking()

        await("wait until the rapid has started")
            .atMost(20, SECONDS)
            .until(rapid::isRunning)
    }

    @AfterEach
    internal fun stop() {
        rapid.stop()
        runBlocking { rapidJob.cancelAndJoin() }
    }

    @Test
    fun `no effect calling start multiple times`() {
        assertDoesNotThrow { rapid.start() }
        assertTrue(rapid.isRunning())
    }

    @Test
    fun `can stop`() {
        rapid.stop()
        assertFalse(rapid.isRunning())
        assertDoesNotThrow { rapid.stop() }
    }

    @Test
    fun `should stop on errors`() {
        rapid.register { _, _ -> throw RuntimeException() }

        await("wait until the rapid stops")
            .atMost(20, SECONDS)
            .until {
                kafkaProducer.send(ProducerRecord(testTopic, UUID.randomUUID().toString()))
                !rapid.isRunning()
            }
    }

    @DelicateCoroutinesApi
    @Test
    fun `in case of exception, the offset committed is the erroneous record`() {
        ensureRapidIsActive()

        // stop rapid so we can queue up records
        rapid.stop()
        runBlocking { rapidJob.cancelAndJoin() }

        val offsets = (0..100).map {
            val key = UUID.randomUUID().toString()
            kafkaProducer.send(ProducerRecord(testTopic, key, "{\"test_message_index\": $it}"))
                    .get()
                    .offset()
        }

        val failOnMessage = 50
        val expectedOffset = offsets[failOnMessage]
        var readFailedMessage = false

        rapid = createTestRapid()
        River(rapid)
            .validate { it.requireKey("test_message_index") }
            .onSuccess { packet: JsonMessage, _: MessageContext ->
                val index = packet["test_message_index"].asInt()
                println("Read test_message_index=$index")
                if (index == failOnMessage) {
                    readFailedMessage = true
                    throw RuntimeException("an unexpected error happened")
                }
            }

        rapid.startNonBlocking()

        await("wait until the failed message has been read")
                .atMost(20, SECONDS)
                .until { readFailedMessage }
        await("wait until the rapid stops")
                .atMost(20, SECONDS)
                .until { !rapid.isRunning() }

        val actualOffset = embeddedKafkaEnvironment.adminClient
                ?.listConsumerGroupOffsets(consumerId)
                ?.partitionsToOffsetAndMetadata()
                ?.get()
                ?.getValue(TopicPartition(testTopic, 0))
                ?: fail { "was not able to fetch committed offset for consumer $consumerId" }
        val metadata = actualOffset.metadata() ?: fail { "expected metadata to be present in OffsetAndMetadata" }
        assertEquals(expectedOffset, actualOffset.offset())
        assertTrue(objectMapper.readTree(metadata).has("groupInstanceId"))
        assertDoesNotThrow { LocalDateTime.parse(objectMapper.readTree(metadata).path("time").asText()) }
    }

    private fun ensureRapidIsActive() {
        val readMessages = mutableListOf<JsonMessage>()
        River(rapid).onSuccess { packet: JsonMessage, _: MessageContext -> readMessages.add(packet) }

        await("wait until the rapid has read the test message")
                .atMost(5, SECONDS)
                .until {
                    rapid.publish("{\"foo\": \"bar\"}")
                    readMessages.size >= 1
                }
    }

    @Test
    fun `read and produce message`() {
        val serviceId = "my-service"
        val eventName = "heartbeat"
        val value = "{ \"@event\": \"$eventName\" }"

        testRiver(eventName, serviceId)
        val recordMetadata = waitForReply(testTopic, serviceId, eventName, value)

        val offsets = embeddedKafkaEnvironment.adminClient
            ?.listConsumerGroupOffsets(consumerId)
            ?.partitionsToOffsetAndMetadata()
            ?.get()
            ?: fail { "was not able to fetch committed offset for consumer $consumerId" }
        val actualOffset = offsets.getValue(TopicPartition(recordMetadata.topic(), recordMetadata.partition()))
        val metadata = actualOffset.metadata() ?: fail { "expected metadata to be present in OffsetAndMetadata" }
        assertTrue(actualOffset.offset() >= recordMetadata.offset())
        assertTrue(objectMapper.readTree(metadata).has("groupInstanceId"))
        assertDoesNotThrow { LocalDateTime.parse(objectMapper.readTree(metadata).path("time").asText()) }
    }

    @DelicateCoroutinesApi
    @Test
    fun `seek to beginning`() {
        val readMessages = mutableListOf<JsonMessage>()
        River(rapid).onSuccess { packet: JsonMessage, _: MessageContext -> readMessages.add(packet) }

        var producedMessages = 0
        await("wait until the rapid has read the test message")
            .atMost(5, SECONDS)
            .until {
                rapid.publish("{\"foo\": \"bar\"}")
                producedMessages += 1
                readMessages.size >= 1
            }

        rapid.stop()
        runBlocking { rapidJob.cancelAndJoin() }

        readMessages.clear()

        rapid = createTestRapid()
        River(rapid).onSuccess { packet: JsonMessage, _: MessageContext -> readMessages.add(packet) }
        rapid.seekToBeginning()
        rapid.startNonBlocking()

        await("wait until the rapid has read more than one message")
            .atMost(20, SECONDS)
            .until { readMessages.size >= producedMessages }
    }

    @Test
    fun `read from others topics and produce to rapid topic`() {
        val serviceId = "my-service"
        val eventName = "heartbeat"
        val value = "{ \"@event\": \"$eventName\" }"

        testRiver(eventName, serviceId)
        waitForReply(anotherTestTopic, serviceId, eventName, value)
    }

    @DelicateCoroutinesApi
    private fun KafkaRapid.startNonBlocking() {
        rapidJob = GlobalScope.launch {
            try {
                this@startNonBlocking.start()
            } catch (err: Exception) {
                // swallow
            }
        }
    }

    private fun createTestRapid(): KafkaRapid {
        return KafkaRapid(config.consumerConfig(), config.producerConfig(), testTopic, listOf(anotherTestTopic))
    }

    private fun testRiver(eventName: String, serviceId: String) {
        River(rapid).apply {
            validate { it.requireValue("@event", eventName) }
            validate { it.forbid("service_id") }
            register(object : River.PacketListener {
                override fun onPacket(packet: JsonMessage, context: MessageContext) {
                    packet["service_id"] = serviceId
                    context.publish(packet.toJson())
                }

                override fun onError(problems: MessageProblems, context: MessageContext) {}
            })
        }
    }

    private fun waitForReply(topic: String, serviceId: String, eventName: String, event: String): RecordMetadata {
        var future: Future<RecordMetadata>? = null
        val sentMessages = mutableListOf<String>()
        await("wait until we get a reply")
            .atMost(20, SECONDS)
            .until {
                val key = UUID.randomUUID().toString()
                future = kafkaProducer.send(ProducerRecord(topic, key, event))
                sentMessages.add(key)

                kafkaConsumer.poll(Duration.ZERO).forEach {
                    if (!sentMessages.contains(it.key())) return@forEach
                    val json = objectMapper.readTree(it.value())
                    if (eventName != json.path("@event").asText()) return@forEach
                    if (serviceId != json.path("service_id").asText()) return@forEach
                    return@until true
                }
                return@until false
            }
        return requireNotNull(future).get()
    }

    private fun producerProperties() =
        Properties().apply {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaEnvironment.brokersURL)
            put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
            put(ProducerConfig.LINGER_MS_CONFIG, "0")
            put(ProducerConfig.RETRIES_CONFIG, "0")
            put(SaslConfigs.SASL_MECHANISM, "PLAIN")
        }

    private fun consumerProperties(): MutableMap<String, Any> {
        return HashMap<String, Any>().apply {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaEnvironment.brokersURL)
            put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
            put(SaslConfigs.SASL_MECHANISM, "PLAIN")
            put(ConsumerConfig.GROUP_ID_CONFIG, "integration-test")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
    }
}
