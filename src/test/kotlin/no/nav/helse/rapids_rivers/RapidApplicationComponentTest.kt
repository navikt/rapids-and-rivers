package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import kotlinx.coroutines.*
import no.nav.common.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.time.Duration
import java.util.*
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
        private lateinit var testConsumer: Consumer<String, String>
        private lateinit var consumerJob: Job
        private val messages = mutableListOf<String>()

        @BeforeAll
        @JvmStatic
        internal fun setup() {
            embeddedKafkaEnvironment.start()
            testConsumer = KafkaConsumer(consumerProperties(), StringDeserializer(), StringDeserializer()).apply {
                subscribe(listOf(testTopic))
            }
            consumerJob = GlobalScope.launch {
                while (this.isActive) testConsumer.poll(Duration.ofSeconds(1)).forEach { messages.add(it.value()) }
            }
        }

        @AfterAll
        @JvmStatic
        internal fun teardown() {
            runBlocking { consumerJob.cancelAndJoin() }
            testConsumer.close()
            embeddedKafkaEnvironment.tearDown()
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

        private fun createConfig(): Map<String, String> {
            val randomPort = ServerSocket(0).use { it.localPort }
            appUrl = "http://localhost:$randomPort"
            return mapOf(
                "KAFKA_BOOTSTRAP_SERVERS" to embeddedKafkaEnvironment.brokersURL,
                "KAFKA_CONSUMER_GROUP_ID" to "integration-test-$randomPort",
                "KAFKA_RAPID_TOPIC" to testTopic,
                "HTTP_PORT" to "$randomPort"
            )
        }
    }

    @BeforeEach
    fun clearMessages() {
        messages.clear()
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

        val job = GlobalScope.launch { rapid.start() }

        await("wait until the custom endpoint responds")
            .atMost(20, SECONDS)
            .until { isOkResponse(endpoint) }

        assertEquals(expectedText, response(endpoint))

        rapid.stop()
        runBlocking { job.cancelAndJoin() }
    }

    @Test
    fun `nais endpoints`() {
        rapid = RapidApplication.create(createConfig())

        val job = GlobalScope.launch { rapid.start() }
        await("wait until the rapid has started")
            .atMost(20, SECONDS)
            .until { isOkResponse("/isalive") }

        await("wait until the rapid has been assigned partitions")
            .atMost(20, SECONDS)
            .until { isOkResponse("/isready") }

        assertTrue(isOkResponse("/metrics"))

        rapid.stop()
        runBlocking { job.cancelAndJoin() }

        await("wait until the rapid has stopped")
            .atMost(20, SECONDS)
            .until { !isOkResponse("/isalive") }
    }

    @Test
    fun `creates events for up and down`() {
        rapid = RapidApplication.create(createConfig().let {
            it.toMutableMap().apply { put("RAPID_APP_NAME", "rapid-app") }
        })

        val job = GlobalScope.launch { rapid.start() }

        waitForEvent("application_up")

        rapid.stop()

        waitForEvent("application_down")

        runBlocking { job.cancelAndJoin() }
    }

    @Test
    fun `ping pong`() {
        val appName = "rapid-app"
        rapid = RapidApplication.create(createConfig().let {
            it.toMutableMap().apply { put("RAPID_APP_NAME", appName) }
        })

        val job = GlobalScope.launch { rapid.start() }

        waitForEvent("application_ready")

        val pingId = UUID.randomUUID().toString()
        rapid.publish("""{"@event_name":"ping","@id":"$pingId"}""")

        val pong = requireNotNull(waitForEvent("pong")) { "did not receive pong before timeout" }
        assertEquals(pingId, pong["@id"].asText())
        assertEquals(appName, pong["app_name"].asText())
        assertTrue(pong.hasNonNull("instance_id"))

        rapid.stop()
        runBlocking { job.cancelAndJoin() }
    }

    private fun waitForEvent(event: String): JsonNode? {
        return await("wait until $event")
            .atMost(20, SECONDS)
            .until({
                messages.map { objectMapper.readTree(it) }
                    .firstOrNull { it.path("@event_name").asText() == event }
            }) { it != null }
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
