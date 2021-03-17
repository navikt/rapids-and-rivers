package no.nav.helse.rapids_rivers

import no.nav.helse.rapids_rivers.testsupport.TestRapid
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ReplayableRapidsConnectionTest {
    private lateinit var testRapid: TestRapid
    private val packetInspector = PacketInspector()

    @BeforeEach
    fun setup() {
        testRapid = TestRapid()
        testRapid.register(packetInspector)
        packetInspector.clear()
    }

    @Test
    fun `køede meldinger publiseres ikke`() {
        testRapid.queueReplayMessage("a key", "a message")
        assertEquals(0, packetInspector.size)
    }

    @Test
    fun `replayer melding etter mottak av melding`() {
        val originalMessage = "a test message!"
        val key = "a key"
        val replayedMessage = "a message"
        testRapid.queueReplayMessage(key, replayedMessage)
        testRapid.sendTestMessage(originalMessage)
        assertEquals(2, packetInspector.size)
        assertEquals(originalMessage, packetInspector[0])
        assertEquals(replayedMessage, packetInspector[1])
    }

    @Test
    fun `kan køe opp flere replays`() {
        val originalMessage = "a test message!"
        val key = "a key"
        val replayedMessage = "a message"
        val secondReplayedMessage = "a second message"

        testRapid.register { message: String, _: MessageContext ->
            if (message != replayedMessage) return@register
            repeat(5) { testRapid.queueReplayMessage(key, secondReplayedMessage) }
        }

        testRapid.queueReplayMessage(key, replayedMessage)
        testRapid.sendTestMessage(originalMessage)
        assertEquals(7, packetInspector.size)
        assertEquals(secondReplayedMessage, packetInspector[2])
    }

    @Test
    fun `kan republisere på en replaymelding`() {
        val originalMessage = "a test message!"
        val key = "a key"
        val replayedMessage = "a message"
        val secondMessage = "{ \"foo\": \"bar\" }"

        testRapid.register { _: String, context: MessageContext ->
            context.publish(secondMessage)
        }

        testRapid.queueReplayMessage(key, replayedMessage)
        testRapid.sendTestMessage(originalMessage)
        assertEquals(2, packetInspector.size)
        assertEquals(2, testRapid.inspektør.size)
        assertEquals(key, testRapid.inspektør.key(1))
        assertEquals("bar", testRapid.inspektør.message(1).path("foo").asText())
    }

    @Test
    fun `kan republisere med ny melding`() {
        val originalMessage = "a test message!"
        val key = "a key"
        val newKey = "a key"
        val replayedMessage = "a message"
        val secondMessage = "{ \"foo\": \"bar\" }"

        testRapid.register { _: String, context: MessageContext ->
            context.publish(newKey, secondMessage)
        }

        testRapid.queueReplayMessage(key, replayedMessage)
        testRapid.sendTestMessage(originalMessage)
        assertEquals(2, packetInspector.size)
        assertEquals(2, testRapid.inspektør.size)
        assertEquals(newKey, testRapid.inspektør.key(1))
        assertEquals("bar", testRapid.inspektør.message(1).path("foo").asText())
    }

    private class PacketInspector(
        private val packets: MutableList<String> = mutableListOf()
    ) : RapidsConnection.MessageListener, List<String> by (packets) {

        fun clear() {
            packets.clear()
        }

        override fun onMessage(message: String, context: MessageContext) {
            packets.add(message)
        }
    }
}