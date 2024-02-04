package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.Consumer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS
import java.util.stream.Collectors

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RapidApplicationComponentTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)


    private val testTopic = "a-test-topic"
    private val kafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.2.1"))

    private val localConfig = LocalKafkaConfig(kafkaContainer)
    private val factory = ConsumerProducerFactory(localConfig)
    private lateinit var appUrl: String
    private lateinit var testConsumer: Consumer<String, String>
    private lateinit var consumerJob: Job
    private val messages = mutableListOf<String>()

    @DelicateCoroutinesApi
    @BeforeAll
    internal fun setup() {
        kafkaContainer.start()
        testConsumer = factory.createConsumer("test-consumer").apply {
            subscribe(listOf(testTopic))
        }
        consumerJob = GlobalScope.launch {
            while (this.isActive) testConsumer.poll(Duration.ofSeconds(1)).forEach { messages.add(it.value()) }
        }
    }

    @AfterAll
    internal fun teardown() {
        runBlocking { consumerJob.cancelAndJoin() }
        testConsumer.close()
        kafkaContainer.stop()
    }

    @BeforeEach
    fun clearMessages() {
        messages.clear()
    }

    @DelicateCoroutinesApi
    @Test
    fun `custom endpoint`() {
        val expectedText = "Hello, World!"
        val endpoint = "/custom"
        withRapid({ port ->
            embeddedServer(CIO, port = port) {
                routing {
                    get(endpoint) {
                        call.respondText(expectedText, ContentType.Text.Plain)
                    }
                }
            }
        }) {
            await("wait until the custom endpoint responds")
                .atMost(40, SECONDS)
                .until { isOkResponse(endpoint) }
            assertEquals(expectedText, response(endpoint))
        }
    }

    @DelicateCoroutinesApi
    @Test
    fun `nais endpoints`() {
        withRapid { rapid ->
            await("wait until the rapid has started")
                .atMost(40, SECONDS)
                .until { isOkResponse("/isalive") }

            await("wait until the rapid has been assigned partitions")
                .atMost(40, SECONDS)
                .until { isOkResponse("/isready") }

            rapid.stop()

            await("wait until the rapid has stopped")
                .atMost(40, SECONDS)
                .until { !isOkResponse("/isalive") }
        }
    }

    @DelicateCoroutinesApi
    @Test
    fun `pre stop hook`() {
        withRapid { _ ->
            await("wait until the rapid has started")
                .atMost(40, SECONDS)
                .until { isOkResponse("/isalive") }

            await("wait until the rapid has been assigned partitions")
                .atMost(40, SECONDS)
                .until { isOkResponse("/isready") }

            await("wait until the rapid has stopped after receiving signal")
                .atMost(40, SECONDS)
                .until { isOkResponse("/stop") }
        }
    }

    @DelicateCoroutinesApi
    @Test
    fun `metrics endpoints`() {
        withRapid { _ ->
            await("wait until metrics are available")
                .atMost(40, SECONDS)
                .until { isOkResponse("/metrics") }

            await("ensure metrics are still available")
                .atMost(40, SECONDS)
                .until { isOkResponse("/metrics") }
        }
    }

    @DelicateCoroutinesApi
    @Test
    fun `metric values`() {
        withRapid(collectorRegistry = CollectorRegistry.defaultRegistry) { rapid ->
            waitForEvent("application_ready")
            rapid.publish("""{"@event_name":"ping","@id":"${UUID.randomUUID()}","ping_time":"${LocalDateTime.now()}"}""")
            waitForEvent("ping")
            await("wait until metrics are available")
                .atMost(40, SECONDS)
                .until { isOkResponse("/metrics") }

            val response =
                BufferedReader(InputStreamReader((URL("$appUrl/metrics").openConnection() as HttpURLConnection).inputStream)).lines()
                    .collect(Collectors.joining())
            assertTrue(response.contains("message_counter"))
            assertTrue(response.contains("on_packet_seconds"))
        }
    }

    @DelicateCoroutinesApi
    @Test
    fun `creates events for up and down`() {
        withRapid { rapid ->
            waitForEvent("application_up")
            rapid.stop()
            waitForEvent("application_down")
        }
    }

    @DelicateCoroutinesApi
    @Test
    fun `ping pong`() {
        withRapid { rapid ->
            waitForEvent("application_ready")

            val pingId = UUID.randomUUID().toString()
            val pingTime = LocalDateTime.now()
            rapid.publish("""{"@event_name":"ping","@id":"$pingId","ping_time":"$pingTime"}""")

            val pong = requireNotNull(waitForEvent("pong")) { "did not receive pong before timeout" }
            assertNotEquals(pingId, pong["@id"].asText())
            assertEquals(pingTime.toString(), pong["ping_time"].asText())
            assertDoesNotThrow { LocalDateTime.parse(pong["pong_time"].asText()) }
            assertEquals("app-name", pong["app_name"].asText())
            assertEquals(pingId, pong.path("@forårsaket_av").path("id").asText())
            assertEquals("ping", pong.path("@forårsaket_av").path("event_name").asText())
            assertTrue(pong.hasNonNull("instance_id"))
        }
    }

    private fun waitForEvent(event: String): JsonNode? {
        return await("wait until $event")
            .atMost(60, SECONDS)
            .until({
                messages.map { objectMapper.readTree(it) }
                    .firstOrNull { it.path("@event_name").asText() == event }
            }) { it != null }
    }

    @DelicateCoroutinesApi
    private fun withRapid(
        ktor: (port: Int) -> ApplicationEngine? = { null },
        collectorRegistry: CollectorRegistry = CollectorRegistry(),
        block: (RapidsConnection) -> Unit
    ) {
        val randomPort = ServerSocket(0).use { it.localPort }
        appUrl = "http://localhost:$randomPort"
        val rapidApplicationConfig = RapidApplication.RapidApplicationConfig(
            appName = "app-name",
            instanceId = "app-name-0",
            rapidTopic = testTopic,
            kafkaConfig = localConfig,
            consumerGroupId = "component-test",
            httpPort = randomPort,
            collectorRegistry = collectorRegistry
        )
        val builder = RapidApplication.Builder(rapidApplicationConfig)
        ktor(randomPort)?.let { builder.withKtor(it) }
        val rapidsConnection = builder.build()
        val job = GlobalScope.launch { rapidsConnection.start() }
        try {
            block(rapidsConnection)
        } finally {
            rapidsConnection.stop()
            runBlocking { job.cancelAndJoin() }
        }
    }

    private fun response(path: String) =
        URL("$appUrl$path").openStream().use { it.bufferedReader().readText() }

    private fun isOkResponse(path: String): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$appUrl$path").openConnection() as HttpURLConnection)
            return conn.responseCode in 200..299
        } catch (err: IOException) {
            System.err.println("$appUrl$path: ${err.message}")
            //err.printStackTrace(System.err)
        } finally {
            conn?.disconnect()
        }
        return false
    }
}
