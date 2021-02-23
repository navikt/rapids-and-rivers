package no.nav.helse.rapids_rivers.testsupport

import no.nav.helse.rapids_rivers.JsonMessage
import no.nav.helse.rapids_rivers.MessageContext
import no.nav.helse.rapids_rivers.RapidsConnection
import no.nav.helse.rapids_rivers.River
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class TestRapidTest {

    private val rapid = TestRapid().apply {
        TestRiver(this)
    }

    @BeforeEach
    fun setup() {
        rapid.reset()
    }

    @Test
    fun `send and read messages`() {
        rapid.sendTestMessage("{}")
        assertEquals(1, rapid.inspektør.size)
        assertEquals("world", rapid.inspektør.message(0).path("hello").asText())
        assertEquals("world", rapid.inspektør.field(0, "hello").asText())
        rapid.reset()
        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `throws exception for invalid scenarios`() {
        assertThrows<IndexOutOfBoundsException> { rapid.inspektør.message(0) }
        assertThrows<IndexOutOfBoundsException> { rapid.inspektør.field(0, "does_not_exist") }
        assertThrows<IllegalArgumentException> {
            rapid.sendTestMessage("{}")
            rapid.inspektør.field(0, "does_not_exist")
        }
    }

    private class TestRiver(rapidsConnection: RapidsConnection) : River.PacketListener {
        init {
            River(rapidsConnection)
                .register(this)
        }

        override fun onPacket(packet: JsonMessage, context: MessageContext) {
            packet["hello"] = "world"
            context.publish(packet.toJson())
        }
    }
}
