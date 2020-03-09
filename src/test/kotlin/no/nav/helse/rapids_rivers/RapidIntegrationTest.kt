package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.common.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS

internal class RapidIntegrationTest {
    private companion object {

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

        private fun consumerProperties(): MutableMap<String, Any>? {
            return HashMap<String, Any>().apply {
                put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaEnvironment.brokersURL)
                put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
                put(SaslConfigs.SASL_MECHANISM, "PLAIN")
                put(ConsumerConfig.GROUP_ID_CONFIG, "integration-test")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            }
        }

        @BeforeAll
        @JvmStatic
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
        @JvmStatic
        internal fun `teardown`() {
            kafkaConsumer.unsubscribe()
            kafkaConsumer.close()
            kafkaProducer.close()
            embeddedKafkaEnvironment.tearDown()
        }
    }

    @BeforeEach
    internal fun start() {
        rapid = KafkaRapid(config.consumerConfig(), config.producerConfig(), testTopic, listOf(anotherTestTopic))

        GlobalScope.launch {
            try {
                rapid.start()
            } catch (err: Exception) {
                // swallow
            }
        }

        await("wait until the rapid has started")
            .atMost(5, SECONDS)
            .until(rapid::isRunning)
    }

    @AfterEach
    internal fun stop() {
        rapid.stop()
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
        rapid.register(object : RapidsConnection.MessageListener {
            override fun onMessage(message: String, context: RapidsConnection.MessageContext) {
                throw RuntimeException()
            }
        })

        await("wait until the rapid stops")
            .atMost(10, SECONDS)
            .until {
                kafkaProducer.send(ProducerRecord(testTopic, UUID.randomUUID().toString()))
                !rapid.isRunning()
            }
    }

    @Test
    fun `read and produce message`() {
        val serviceId = "my-service"
        val eventName = "heartbeat"
        val value = "{ \"@event\": \"$eventName\" }"

        testRiver(eventName, serviceId)
        waitForReply(testTopic, serviceId, eventName, value)
    }

    @Test
    fun `seek to beginning`() {

        val readMessages = mutableListOf<JsonMessage>()
        River(rapid).apply {
            register(object : River.PacketListener {
                override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
                    readMessages.add(packet)
                }
            })
        }

        var producedMessages = 0
        await("wait until the rapid has read the test message")
            .atMost(5, SECONDS)
            .until {
                rapid.publish("{\"foo\": \"bar\"}")
                producedMessages += 1
                readMessages.size >= 1
            }

        rapid.stop()

        readMessages.clear()

        rapid.seekToBeginning()
        GlobalScope.launch { rapid.start() }

        await("wait until the rapid has read more than one message")
            .atMost(5, SECONDS)
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

    private fun testRiver(eventName: String, serviceId: String) {
        River(rapid).apply {
            validate { it.requireValue("@event", eventName) }
            validate { it.forbid("service_id") }
            register(object : River.PacketListener {
                override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
                    packet["service_id"] = serviceId
                    context.send(packet.toJson())
                }

                override fun onError(problems: MessageProblems, context: RapidsConnection.MessageContext) {}
            })
        }
    }

    private fun waitForReply(topic: String, serviceId: String, eventName: String, event: String) {
        val sentMessages = mutableListOf<String>()
        await("wait until we get a reply")
            .atMost(10, SECONDS)
            .until {
                val key = UUID.randomUUID().toString()
                kafkaProducer.send(ProducerRecord(topic, key, event))
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
    }
}
