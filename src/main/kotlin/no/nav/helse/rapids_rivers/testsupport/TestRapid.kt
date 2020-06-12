package no.nav.helse.rapids_rivers.testsupport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.nav.helse.rapids_rivers.RapidsConnection
import no.nav.helse.rapids_rivers.isMissingOrNull

class TestRapid : RapidsConnection() {
    private companion object {
        private val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    private val context = TestContext()
    private val messages = mutableListOf<Pair<String?, String>>()
    val inspektør get() = RapidInspector(messages.toList())

    fun reset() {
        messages.clear()
    }

    fun sendTestMessage(message: String) {
        listeners.forEach { it.onMessage(null, message, context) }
    }

    override fun publish(message: String) {
        messages.add(null to message)
    }

    override fun publish(key: String, message: String) {
        messages.add(key to message)
    }

    override fun start() {}
    override fun stop() {}

    private inner class TestContext : MessageContext {
        override fun send(message: String) {
            publish(message)
        }

        override fun send(key: String, message: String) {
            publish(key, message)
        }
    }

    class RapidInspector(private val messages: List<Pair<String?, String>>) {
        private val jsonMessages = mutableMapOf<Int, JsonNode>()
        val size get() = messages.size

        fun key(index: Int) = messages[index].first
        fun message(index: Int) = jsonMessages.getOrPut(index) { objectMapper.readTree(messages[index].second) }
        fun field(index: Int, field: String) = requireNotNull(message(index).path(field).takeUnless(JsonNode::isMissingOrNull)) {
            "Message does not contain field '$field'"
        }
    }
}
