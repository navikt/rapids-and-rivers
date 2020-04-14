package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class InMemoryRapidTest {

    @Test
    internal fun test() {
        val rapid = inMemoryRapid { }
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

    @Test
    internal fun `Producing packets on inmemory rapid is thread safe`() {
        inMemoryRapid { }.also { rapid ->
            object : InMemoryRiver(rapid) {
                private val coroutineScope = CoroutineScope(Dispatchers.IO)
                override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
                    coroutineScope.launch {
                        super.onPacket(packet, context)
                    }
                }
            }
            (1..100).forEach { _ -> rapid.sendToListeners("""{"@behov":"hei"}""") }
        }.also {
            Thread.sleep(1000) //simple wait for coroutines to finish
            assertEquals(100, it.outgoingMessages.size)
        }
    }

    internal open class InMemoryRiver(rapidsConnection: RapidsConnection) : River.PacketListener {
        init {
            River(rapidsConnection).apply {
                validate { it.requireKey("@behov") }
            }.register(this)
        }

        override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
            packet.set("ut", "ut")
            context.send(packet.toJson())
        }

        override fun onError(problems: MessageProblems, context: RapidsConnection.MessageContext) {}
    }

}
