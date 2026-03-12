package com.github.navikt.tbd_libs.rapids_and_rivers

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.test_support.KafkaContainers
import com.github.navikt.tbd_libs.test_support.TestTopic
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS

internal class RapidIntegrationTest {
    private companion object {
        private val kafkaContainer = KafkaContainers.container("tbd-rapid-and-rivers")
    }
    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun rapidE2E(testblokk: suspend TestContext.(Job) -> Unit) = runBlocking {
        val (testTopic, extraTestTopic) = kafkaContainer.nyeTopics(2)
        try {
            val caller = Exception().stackTrace.last { it.className.contains(RapidIntegrationTest::class.java.packageName) }.methodName
            println("<$caller> får ${testTopic.topicnavn} og ${extraTestTopic.topicnavn}")
            startRapid(testTopic, extraTestTopic, testblokk)
        } finally {
            kafkaContainer.droppTopics(listOf(testTopic, extraTestTopic))
        }
    }

    private suspend fun CoroutineScope.startRapid(testTopic: TestTopic, extraTestTopic: TestTopic, testblokk: suspend TestContext.(Job) -> Unit) {
        val consumerGroupId = "kafkarapid-${Random.nextInt()}"
        val rapid = createTestRapid(consumerGroupId, testTopic, extraTestTopic)
        val job = launch(Dispatchers.IO) { rapid.start() }

        try {
            await("wait until the rapid has started")
                .atMost(20, SECONDS)
                .until {
                    rapid.isRunning()
                }

            testblokk(TestContext(consumerGroupId, rapid, testTopic, extraTestTopic), job)
        } finally {
            rapid.stop()
            job.cancelAndJoin()
        }
    }

    private data class TestContext(
        val consumerGroupId: String,
        val rapid: KafkaRapid,
        val mainTopic: TestTopic,
        val extraTopic: TestTopic
    )

    @Test
    fun `no effect calling start multiple times`() = rapidE2E {
        assertDoesNotThrow { rapid.start() }
        assertTrue(rapid.isRunning())
    }

    @Test
    fun `can stop`() = rapidE2E {
        rapid.stop()
        assertFalse(rapid.isRunning())
        assertDoesNotThrow { rapid.stop() }
    }

    @Test
    fun `stops while handling messages`() = rapidE2E {
        val handlingMessages = AtomicBoolean(false)
        rapid.register { message, context, metadata, metrics ->
            handlingMessages.set(true)
            runBlocking { delay(1000) }
        }
        repeat(100) { mainTopic.send("{}") }
        await()
            .atMost(10, SECONDS)
            .until { handlingMessages.get() }

        rapid.stop()

        await()
            .atMost(10, SECONDS)
            .until {
                runBlocking {  it.join() }
                true
            }
    }

    @Test
    fun `should stop on errors`() {
        assertThrows<RuntimeException> {
            rapidE2E {
                rapid.register { _, _, _, _ -> throw RuntimeException("oh shit") }

                await("wait until the rapid stops")
                    .atMost(20, SECONDS)
                    .until {
                        mainTopic.send(UUID.randomUUID().toString())
                        !rapid.isRunning()
                    }
            }
        }.also {
            assertEquals("oh shit", it.message)
        }
    }

    @Test
    fun `in case of exception, the offset committed is the erroneous record`() = rapidE2E {
        ensureRapidIsActive()

        // stop rapid so we can queue up records
        rapid.stop()
        it.cancelAndJoin()

        val offsets = (0..100).map {
            val key = UUID.randomUUID().toString()
            mainTopic.send(key, "{\"test_message_index\": $it}").get()
        }
            .also {
                assertEquals(1, it.distinctBy { it.partition() }.size) { "testen forutsetter én unik partisjon" }
            }
            .map { it.offset() }

        val failOnMessage = 50
        val expectedOffset = offsets[failOnMessage]
        var readFailedMessage = false

        val rapid = createTestRapid(consumerGroupId, mainTopic, extraTopic)

        River(rapid)
            .validate { it.requireKey("test_message_index") }
            .onSuccess { packet: JsonMessage, _: MessageContext, _, _ ->
                val index = packet["test_message_index"].asInt()
                println("Read test_message_index=$index")
                if (index == failOnMessage) {
                    readFailedMessage = true
                    throw RuntimeException("an unexpected error happened")
                }
            }

        try {
            runBlocking(Dispatchers.IO) { launch { rapid.start() } }
        } catch (err: RuntimeException) {
            assertEquals("an unexpected error happened", err.message)
        } finally {
            rapid.stop()
        }

        await("wait until the rapid stops")
                .atMost(20, SECONDS)
                .until { !rapid.isRunning() }

        val actualOffset = await().atMost(Duration.ofSeconds(5)).until({
            val offsets = mainTopic.adminClient
                .listConsumerGroupOffsets(consumerGroupId)
                ?.partitionsToOffsetAndMetadata()
                ?.get()
                ?: fail { "was not able to fetch committed offset for consumer $consumerGroupId" }
            offsets[TopicPartition(mainTopic.topicnavn, 0)]
        }) { it != null }

        val metadata = actualOffset?.metadata() ?: fail { "expected metadata to be present in OffsetAndMetadata" }
        assertEquals(expectedOffset, actualOffset.offset())
        assertTrue(objectMapper.readTree(metadata).has("groupInstanceId"))
        assertDoesNotThrow { LocalDateTime.parse(objectMapper.readTree(metadata).path("time").asText()) }
    }

    @Test
    fun `in case of shutdown, the offset committed is the next record to be processed`() = rapidE2E {
        ensureRapidIsActive()

        // stop rapid so we can queue up records
        rapid.stop()
        it.cancelAndJoin()

        val offsets = (0..100).map {
            val key = UUID.randomUUID().toString()
            mainTopic.send(key, "{\"test_message_index\": $it}").get()
        }
            .also {
                assertEquals(1, it.distinctBy { it.partition() }.size) { "testen forutsetter én unik partisjon" }
            }
            .map { it.offset() }

        val stopProcessingOnMessage = 5
        val expectedOffset = offsets[stopProcessingOnMessage] + 1
        val synchronizationBarrier = Channel<Boolean>(RENDEZVOUS)
        val rapid = createTestRapid(consumerGroupId, mainTopic, extraTopic)

        River(rapid)
            .validate { it.requireKey("test_message_index") }
            .onSuccess { packet: JsonMessage, _: MessageContext, _, _ ->
                val index = packet["test_message_index"].asInt()
                println("Read test_message_index=$index")
                if (index == stopProcessingOnMessage) {
                    // notify test that we are ready to go forward
                    runBlocking { synchronizationBarrier.send(true) }

                    // wait until test has signalled that shutdown has been started
                    await("wait until test is ready to go forward")
                        .atMost(20, SECONDS)
                        .until { runBlocking { synchronizationBarrier.receive() } }
                }
            }

        try {
            runBlocking {
                val rapidJob = launch(Dispatchers.IO) { rapid.start() }

                await("wait until test is ready to go forward")
                    .atMost(20, SECONDS)
                    .until { runBlocking { synchronizationBarrier.receive() } }

                rapid.stop()
                synchronizationBarrier.send(true)
            }
        } catch (err: RuntimeException) {
            assertEquals("an unexpected error happened", err.message)
        } finally {
            rapid.stop()
        }

        await("wait until the rapid stops")
                .atMost(20, SECONDS)
                .until { !rapid.isRunning() }

        val actualOffset = await().atMost(Duration.ofSeconds(5)).until({
            val offsets = mainTopic.adminClient
                .listConsumerGroupOffsets(consumerGroupId)
                ?.partitionsToOffsetAndMetadata()
                ?.get()
                ?: fail { "was not able to fetch committed offset for consumer $consumerGroupId" }
            offsets[TopicPartition(mainTopic.topicnavn, 0)]
        }) { it != null }

        val metadata = actualOffset?.metadata() ?: fail { "expected metadata to be present in OffsetAndMetadata" }
        assertEquals(expectedOffset, actualOffset.offset())
        assertTrue(objectMapper.readTree(metadata).has("groupInstanceId"))
        assertDoesNotThrow { LocalDateTime.parse(objectMapper.readTree(metadata).path("time").asText()) }
    }

    private fun TestContext.ensureRapidIsActive() {
        val readMessages = mutableListOf<JsonMessage>()
        River(rapid).onSuccess { packet: JsonMessage, _: MessageContext, _, _ -> readMessages.add(packet) }

        await("wait until the rapid has read the test message")
                .atMost(5, SECONDS)
                .until {
                    rapid.publish("{\"foo\": \"bar\"}")
                    readMessages.isNotEmpty()
                }
    }

    @Test
    fun `ignore tombstone messages`() = rapidE2E {
        val serviceId = "my-service"
        val eventName = "heartbeat"

        testRiver(eventName, serviceId)
        println("sender null-melding på ${mainTopic.topicnavn}")
        val recordMetadata = mainTopic.waitForReply(serviceId, eventName, null)

        val actualOffset = await().atMost(Duration.ofSeconds(5)).until({
            val offsets = mainTopic.adminClient
                .listConsumerGroupOffsets(consumerGroupId)
                ?.partitionsToOffsetAndMetadata()
                ?.get()
                ?: fail { "was not able to fetch committed offset for consumer $consumerGroupId" }
            offsets[TopicPartition(recordMetadata.topic(), recordMetadata.partition())]
        }) { it != null }
        val metadata = actualOffset?.metadata() ?: fail { "expected metadata to be present in OffsetAndMetadata" }
        assertTrue(actualOffset.offset() >= recordMetadata.offset()) { "expected $actualOffset to be equal or greater than $recordMetadata" }
        assertTrue(objectMapper.readTree(metadata).has("groupInstanceId"))
        assertDoesNotThrow { LocalDateTime.parse(objectMapper.readTree(metadata).path("time").asText()) }
    }

    @Test
    fun `read and produce message`() = rapidE2E {
        val serviceId = "my-service"
        val eventName = "heartbeat"
        val value = "{ \"@event\": \"$eventName\" }"

        testRiver(eventName, serviceId)
        val recordMetadata = mainTopic.waitForReply(serviceId, eventName, value)

        val actualOffset = await().atMost(Duration.ofSeconds(5)).until({
            val offsets = mainTopic.adminClient
                .listConsumerGroupOffsets(consumerGroupId)
                ?.partitionsToOffsetAndMetadata()
                ?.get()
                ?: fail { "was not able to fetch committed offset for consumer $consumerGroupId" }
            offsets[TopicPartition(recordMetadata.topic(), recordMetadata.partition())]
        }) { it != null }
        val metadata = actualOffset?.metadata() ?: fail { "expected metadata to be present in OffsetAndMetadata" }
        assertTrue(actualOffset.offset() >= recordMetadata.offset())
        assertTrue(objectMapper.readTree(metadata).has("groupInstanceId"))
        assertDoesNotThrow { LocalDateTime.parse(objectMapper.readTree(metadata).path("time").asText()) }
    }

    @Test
    fun `read from others topics and produce to rapid topic`() = rapidE2E {
        val serviceId = "my-service"
        val eventName = "heartbeat"
        val value = "{ \"@event\": \"$eventName\" }"

        testRiver(eventName, serviceId)
        extraTopic.waitForReply(serviceId, eventName, value)
    }

    @Test
    fun `send messages in bulk`() = rapidE2E {
        val serviceId1 = "my-service-1"
        val serviceId2 = "my-service-2"
        val eventName = "heartbeat"
        val value = "{ \"@event\": \"$eventName\" }"

        River(rapid).apply {
            validate { it.requireValue("@event", eventName) }
            validate { it.forbid("service_id") }
            register(object : River.PacketListener {
                override fun onPacket(packet: JsonMessage, context: MessageContext, metadata: MessageMetadata, meterRegistry: MeterRegistry) {
                    packet["service_id"] = serviceId1
                    val message1 = OutgoingMessage(packet.toJson())
                    packet["service_id"] = serviceId2
                    val message2 = OutgoingMessage(packet.toJson())
                    context.publish(listOf(message1, message2))
                }

                override fun onError(problems: MessageProblems, context: MessageContext, metadata: MessageMetadata) {}
            })
        }

        mainTopic.waitForReply(serviceId1, eventName, value)
        mainTopic.waitForReply(serviceId2, eventName, value)
    }

    private fun createTestRapid(consumerGroupId: String, mainTopic: TestTopic, extraTopic: TestTopic): KafkaRapid {
        val localConfig = LocalKafkaConfig(kafkaContainer.connectionProperties)
        val factory: ConsumerProducerFactory = ConsumerProducerFactory(localConfig)
        return KafkaRapid(factory, consumerGroupId, mainTopic.topicnavn, SimpleMeterRegistry(), extraTopics = listOf(extraTopic.topicnavn))
    }

    private fun TestContext.testRiver(eventName: String, serviceId: String) {
        River(rapid).apply {
            validate { it.requireValue("@event", eventName) }
            validate { it.forbid("service_id") }
            register(object : River.PacketListener {
                override fun onPacket(packet: JsonMessage, context: MessageContext, metadata: MessageMetadata, meterRegistry: MeterRegistry) {
                    packet["service_id"] = serviceId
                    context.publish(packet.toJson())
                }

                override fun onError(problems: MessageProblems, context: MessageContext, metadata: MessageMetadata) {}
            })
        }
    }

    private fun TestTopic.waitForReply(serviceId: String, eventName: String, event: String?): RecordMetadata {
        val sentMessages = mutableListOf<String>()
        val key = UUID.randomUUID().toString()
        val recordMetadata = send(key, event).get(5000, SECONDS)
        sentMessages.add(key)
        await("wait until we get a reply")
            .atMost(20, SECONDS)
            .until {
                runBlocking {
                    pollRecords().any {
                        sentMessages.contains(it.key()) && it.key() == key
                    }
                }
            }
        return recordMetadata
    }

}

private class LocalKafkaConfig(private val connectionProperties: Properties) : Config {
    override fun producerConfig(properties: Properties): Properties {
        return properties.apply {
            putAll(connectionProperties)
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
            put(ProducerConfig.LINGER_MS_CONFIG, "0")
            put(ProducerConfig.RETRIES_CONFIG, "0")
        }
    }

    override fun consumerConfig(groupId: String, properties: Properties): Properties {
        return properties.apply {
            putAll(connectionProperties)
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
    }

    override fun adminConfig(properties: Properties): Properties {
        return properties.apply {
            putAll(connectionProperties)
        }
    }
}
