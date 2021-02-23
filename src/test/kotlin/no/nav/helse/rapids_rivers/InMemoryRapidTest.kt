package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class InMemoryRapidTest {

    @Test
    internal fun test() {
        val rapid = inMemoryRapid {  }
        InMemoryRiver(rapid)

        rapid.sendToListeners("sldjjfnqaolsdjcb")
        rapid.sendToListeners("""{"@behov":"hei"}""")

        rapid.outgoingMessages.map { it.value }.also {
            assertEquals(1, it.size)
            jacksonObjectMapper().readTree(it.first()).also {
                assertEquals("hei", it["@behov"].asText())
                assertEquals("ut", it["ut"].asText())
            }
        }
    }

    internal class InMemoryRiver(rapidsConnection: RapidsConnection) : River.PacketListener {
        init {
            River(rapidsConnection).apply {
                validate { it.requireKey("@behov") }
            }.register(this)
        }

        override fun onPacket(packet: JsonMessage, context: MessageContext) {
            packet["ut"] = "ut"
            context.publish(packet.toJson())
        }

        override fun onError(problems: MessageProblems, context: MessageContext) {}
    }

}
