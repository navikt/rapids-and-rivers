package no.nav.helse.rapids_rivers

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class RiverTest {

    @Test
    internal fun `invalid json`() {
        river.onMessage("invalid json", context)
        assertFalse(gotMessage)
        assertTrue(messageProblems.hasErrors())
    }

    @Test
    internal fun `no validations`() {
        river.onMessage("{}", context)
        assertTrue(gotMessage)
        assertFalse(messageProblems.hasErrors())
    }

    @Test
    internal fun `failed validations`() {
        river.validate { it.requireKey("key") }
        river.onMessage("{}", context)
        assertFalse(gotMessage)
        assertTrue(messageProblems.hasErrors())
    }

    @Test
    internal fun `passing validations`() {
        river.validate { it.requireValue("hello", "world") }
        river.onMessage("{\"hello\": \"world\"}", context)
        assertTrue(gotMessage)
        assertFalse(messageProblems.hasErrors())
    }

    private val context = object : RapidsConnection.MessageContext {
        override fun send(message: String) {}

        override fun send(key: String, message: String) {}
    }

    private var gotMessage = false
    private lateinit var messageProblems: MessageProblems
    private lateinit var river: River
    private val rapid = object : RapidsConnection() {
        override fun publish(message: String) {}

        override fun publish(key: String, message: String) {}

        override fun start() {}

        override fun stop() {}
    }

    @BeforeEach
    internal fun setup() {
        messageProblems = MessageProblems("{}")
        river = River(rapid).apply {
            register(object : River.PacketListener {
                override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
                    gotMessage = true
                }

                override fun onError(problems: MessageProblems, context: RapidsConnection.MessageContext) {
                    messageProblems = problems
                }
            })
        }
    }
}
