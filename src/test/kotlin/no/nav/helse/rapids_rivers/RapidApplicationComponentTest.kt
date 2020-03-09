package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.*
import no.nav.common.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.time.Duration
import java.util.HashMap
import java.util.concurrent.TimeUnit.SECONDS

internal class RapidApplicationComponentTest {
    private companion object {

        private val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        private val testTopic = "a-test-topic"
        private val embeddedKafkaEnvironment = KafkaEnvironment(
            autoStart = false,
            noOfBrokers = 1,
            topicInfos = listOf(KafkaEnvironment.TopicInfo(testTopic, partitions = 1)),
            withSchemaRegistry = false,
            withSecurity = false
        )

        private lateinit var rapid: RapidsConnection
        private lateinit var appUrl: String

        @BeforeAll
        @JvmStatic
        internal fun setup() {
            embeddedKafkaEnvironment.start()
        }

        @AfterAll
        @JvmStatic
        internal fun teardown() {
            embeddedKafkaEnvironment.tearDown()
        }

        private fun createConfig(): Map<String, String> {
            val randomPort = ServerSocket(0).use { it.localPort }
            appUrl = "http://localhost:$randomPort"
            return mapOf(
                "KAFKA_BOOTSTRAP_SERVERS" to embeddedKafkaEnvironment.brokersURL,
                "KAFKA_CONSUMER_GROUP_ID" to "integration-test",
                "KAFKA_RAPID_TOPIC" to testTopic,
                "HTTP_PORT" to "$randomPort"
            )
        }
    }

    @Test
    fun `custom endpoint`() {
        val expectedText = "Hello, World!"
        val endpoint = "/custom"
        rapid = RapidApplication.Builder(RapidApplication.RapidApplicationConfig.fromEnv(createConfig()))
            .withKtorModule {
                routing {
                    get(endpoint) {
                        call.respondText(expectedText, ContentType.Text.Plain)
                    }
                }
            }.build()

        GlobalScope.launch { rapid.start() }

        await("wait until the custom endpoint responds")
            .atMost(5, SECONDS)
            .until { isOkResponse(endpoint) }

        assertEquals(expectedText, response(endpoint))

        rapid.stop()
    }

    @Test
    fun `nais endpoints`() {
        rapid = RapidApplication.create(createConfig())

        GlobalScope.launch { rapid.start() }
        await("wait until the rapid has started")
            .atMost(5, SECONDS)
            .until { isOkResponse("/isalive") }

        assertTrue(isOkResponse("/isready"))
        assertTrue(isOkResponse("/metrics"))

        rapid.stop()

        await("wait until the rapid has stopped")
            .atMost(5, SECONDS)
            .until { !isOkResponse("/isalive") }
    }

    @Test
    fun `creates events for up and down`() {
        rapid = RapidApplication.create(createConfig().let {
            it.toMutableMap().apply { put("RAPID_APP_NAME", "rapid-app") }
        })

        KafkaConsumer(consumerProperties(), StringDeserializer(), StringDeserializer()).use {
            it.subscribe(listOf(testTopic))
            val messages = mutableListOf<String>()
            val job = GlobalScope.launch {
                while (this.isActive) it.poll(Duration.ofSeconds(1)).forEach { println("got $it"); messages.add(it.value()) }
            }

            GlobalScope.launch { rapid.start() }

            waitForEvent(messages, "application_up")

            rapid.stop()

            waitForEvent(messages, "application_down")

            runBlocking { job.cancelAndJoin() }
        }
    }

    private fun waitForEvent(messages: List<String>, event: String) {
        await("wait until $event")
            .atMost(10, SECONDS)
            .until { messages.any { objectMapper.readTree(it).path("@event_name").asText() == event } }
    }

    private fun consumerProperties(): MutableMap<String, Any>? {
        return HashMap<String, Any>().apply {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaEnvironment.brokersURL)
            put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
            put(SaslConfigs.SASL_MECHANISM, "PLAIN")
            put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
    }

    private fun response(path: String) =
        URL("$appUrl$path").openStream().use { it.bufferedReader().readText() }

    private fun isOkResponse(path: String) =
        try {
            (URL("$appUrl$path")
                .openConnection() as HttpURLConnection)
                .responseCode in 200..299
        } catch (err: IOException) {
            false
        }
}
