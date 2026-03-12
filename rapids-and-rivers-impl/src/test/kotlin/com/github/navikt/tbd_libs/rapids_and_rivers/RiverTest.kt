package com.github.navikt.tbd_libs.rapids_and_rivers

import com.github.navikt.tbd_libs.rapids_and_rivers_api.FailedMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.rapids_and_rivers_api.SentMessage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

internal class RiverTest {

    @Test
    internal fun `sets id if missing`() {
        river.onMessage("{}", context, MessageMetadata("", -1, -1, null, emptyMap()), SimpleMeterRegistry())
        assertTrue(gotMessage)
        assertDoesNotThrow { gotPacket.id.toUUID() }
    }

    @Test
    internal fun `sets custom id if missing`() {
        val expected = "notSoRandom"
        river = configureRiver(River(rapid) { expected })
        river.onMessage("{}", context, MessageMetadata("", -1, -1, null, emptyMap()), SimpleMeterRegistry())
        assertTrue(gotMessage)
        assertEquals(expected, gotPacket.id)
    }

    @Test
    internal fun `invalid json`() {
        river.onMessage("invalid json", context, MessageMetadata("", -1, -1, null, emptyMap()), SimpleMeterRegistry())
        assertFalse(gotMessage)
        assertTrue(messageProblems.hasErrors())
    }

    @Test
    internal fun `no validations`() {
        river.onMessage("{}", context, MessageMetadata("", -1, -1, null, emptyMap()), SimpleMeterRegistry())
        assertTrue(gotMessage)
        assertFalse(messageProblems.hasErrors())
    }

    @Test
    internal fun `failed preconditions`() {
        river.precondition { it.requireValue("@event_name", "tick") }
        river.validate { error("this should not be called") }
        river.onMessage("{}", context, MessageMetadata("", -1, -1, null, emptyMap()), SimpleMeterRegistry())
        assertFalse(gotMessage)
        assertTrue(messageProblems.hasErrors())
        assertEquals(RiverValidationResult.PRECONDITION_FAILED, validationResult)
    }

    @Test
    internal fun `failed validations`() {
        river.validate { it.requireKey("key") }
        river.onMessage("{}", context, MessageMetadata("", -1, -1, null, emptyMap()), SimpleMeterRegistry())
        assertFalse(gotMessage)
        assertTrue(messageProblems.hasErrors())
        assertEquals(RiverValidationResult.VALIDATION_FAILED, validationResult)
    }

    @Test
    internal fun `passing validations`() {
        river.precondition { it.requireValue("@event_name", "greeting") }
        river.validate { it.requireValue("hello", "world") }
        @Language("JSON")
        val message = """{ "@event_name": "greeting", "hello": "world" }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), SimpleMeterRegistry())
        assertTrue(gotMessage)
        assertFalse(messageProblems.hasErrors())
        assertEquals(RiverValidationResult.PASSED, validationResult)
    }

    @Test
    internal fun `participating_services tag on message_counter with participating services`() {
        val metrics = SimpleMeterRegistry()
        @Language("JSON")
        val message = """{
            "@event_name": "test_event",
            "system_participating_services": [
                {"service": "original-app", "id": "123", "time": "2024-01-01T10:00:00"},
                {"service": "other-app", "id": "456", "time": "2024-01-01T11:00:00"}
            ]
        }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertTrue(gotMessage)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        assertEquals("original-app,other-app", counter!!.id.getTag("participating_services"))
    }

    @Test
    internal fun `participating_services tag none on message_counter without participating services`() {
        val metrics = SimpleMeterRegistry()
        @Language("JSON")
        val message = """{ "@event_name": "test_event" }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertTrue(gotMessage)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        // Since NAIS_APP_NAME is null in tests, the participating service entry has null service name, filtered out
        assertEquals("none", counter!!.id.getTag("participating_services"))
    }

    @Test
    internal fun `participating_services tag on message_counter with validation error`() {
        val metrics = SimpleMeterRegistry()
        river.validate { it.requireKey("missing_key") }
        @Language("JSON")
        val message = """{
            "@event_name": "test_event",
            "system_participating_services": [
                {"service": "failing-app", "id": "789", "time": "2024-01-01T12:00:00"}
            ]
        }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertFalse(gotMessage)
        assertEquals(RiverValidationResult.VALIDATION_FAILED, validationResult)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        assertEquals("failing-app", counter!!.id.getTag("participating_services"))
    }


    private val context = object : MessageContext {
        override fun publish(message: String) {}
        override fun publish(key: String, message: String) {}
        override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> {
            return emptyList<SentMessage>() to emptyList()
        }
        override fun rapidName(): String {return "test"}
    }

    private var gotMessage = false
    private lateinit var gotPacket: JsonMessage
    private lateinit var messageProblems: MessageProblems
    private lateinit var river: River
    private lateinit var validationResult: RiverValidationResult
    private val rapid = object : RapidsConnection() {
        override fun publish(message: String) {}
        override fun publish(key: String, message: String) {}
        override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> {
            return emptyList<SentMessage>() to emptyList()
        }

        override fun rapidName(): String {
            return "test"
        }

        override fun start() {}

        override fun stop() {}
    }

    @BeforeEach
    internal fun setup() {
        messageProblems = MessageProblems("{}")
        river = configureRiver(River(rapid))
    }

    private enum class RiverValidationResult {
        PASSED, PRECONDITION_FAILED, VALIDATION_FAILED
    }
    private fun configureRiver(river: River): River =
        river.register(object : River.PacketListener {
            override fun onPacket(packet: JsonMessage, context: MessageContext, metadata: MessageMetadata, meterRegistry: MeterRegistry) {
                gotPacket = packet
                gotMessage = true
                validationResult = RiverValidationResult.PASSED
            }

            override fun onPreconditionError(
                error: MessageProblems,
                context: MessageContext,
                metadata: MessageMetadata
            ) {
                messageProblems = error
                validationResult = RiverValidationResult.PRECONDITION_FAILED
            }

            override fun onSevere(
                error: MessageProblems.MessageException,
                context: MessageContext
            ) {
                messageProblems = error.problems
                validationResult = RiverValidationResult.PRECONDITION_FAILED
            }

            override fun onError(problems: MessageProblems, context: MessageContext, metadata: MessageMetadata) {
                messageProblems = problems
                validationResult = RiverValidationResult.VALIDATION_FAILED
            }
        })

    @Test
    internal fun `behov tag on message_counter with behov array`() {
        val metrics = SimpleMeterRegistry()
        @Language("JSON")
        val message = """{
            "@event_name": "test_event",
            "@behov": ["Sykepengehistorikk", "Inntekt"]
        }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertTrue(gotMessage)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        assertEquals("Inntekt,Sykepengehistorikk", counter!!.id.getTag("behov"))
    }

    @Test
    internal fun `behov tag none on message_counter when behov is missing`() {
        val metrics = SimpleMeterRegistry()
        @Language("JSON")
        val message = """{ "@event_name": "test_event" }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertTrue(gotMessage)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        assertEquals("none", counter!!.id.getTag("behov"))
    }

    @Test
    internal fun `losninger tag on message_counter when losning key exists`() {
        val metrics = SimpleMeterRegistry()
        @Language("JSON")
        val message = """{
            "@event_name": "test_event",
            "@behov": ["Sykepengehistorikk", "Inntekt"],
            "@løsning": {"Inntekt": {"beløp": 50000}}
        }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertTrue(gotMessage)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        assertEquals("Inntekt", counter!!.id.getTag("losninger"))
    }

    @Test
    internal fun `losninger tag none on message_counter when losning key is missing`() {
        val metrics = SimpleMeterRegistry()
        @Language("JSON")
        val message = """
            { "@event_name": "test_event",
              "@behov": ["Sykepengehistorikk", "Inntekt"]
            }""".trimIndent()
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertTrue(gotMessage)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        assertEquals("none", counter!!.id.getTag("losninger"))
    }

    @Test
    internal fun `all tags together - participating_services, behov, and losninger`() {
        val metrics = SimpleMeterRegistry()
        @Language("JSON")
        val message = """{
            "@event_name": "behov_event",
            "@behov": ["Inntekt", "Medlemskap"],
            "@løsning": {"Inntekt": {"beløp": 50000}},
            "system_participating_services": [
                {"service": "test-app", "id": "123", "time": "2024-01-01T10:00:00"}
            ]
        }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertTrue(gotMessage)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        assertEquals("test-app", counter!!.id.getTag("participating_services"))
        assertEquals("Inntekt,Medlemskap", counter.id.getTag("behov"))
        assertEquals("Inntekt", counter.id.getTag("losninger"))
        assertEquals("behov_event", counter.id.getTag("event_name"))
    }

    @Test
    internal fun `behov tag on message_counter with validation error`() {
        val metrics = SimpleMeterRegistry()
        river.validate { it.requireKey("missing_key") }
        @Language("JSON")
        val message = """{
            "@event_name": "test_event",
            "@behov": ["Inntekt"]
        }"""
        river.onMessage(message, context, MessageMetadata("", -1, -1, null, emptyMap()), metrics)
        assertFalse(gotMessage)
        assertEquals(RiverValidationResult.VALIDATION_FAILED, validationResult)
        
        val counter = metrics.find("message_counter").counter()
        assertNotNull(counter)
        assertEquals("Inntekt", counter!!.id.getTag("behov"))
        assertEquals("none", counter.id.getTag("losninger"))
    }
}
