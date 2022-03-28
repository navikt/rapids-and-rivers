package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.nav.helse.rapids_rivers.JsonMessage.Companion.objectMapper

internal class KeyMessageContext(
    private val rapidsConnection: MessageContext,
    private val key: String?,
    private val value: String
) : MessageContext {
    private val node: JsonNode? by lazy {
        try { objectMapper.readTree(value) }
        catch (err: JsonParseException) { null }
    }
    private val tracing by lazy {
        node?.let { json ->
            mutableMapOf<String, Any>(
                "id" to json.path("@id").asText()
            ).apply {
                compute("opprettet") { _, _ -> json.path("@opprettet").asText().takeUnless { it.isBlank() } }
                compute("event_name") { _, _ -> json.path("@event_name").asText().takeUnless { it.isBlank() } }
                compute("behov") { _, _ -> json.path("@behov").map(JsonNode::asText).takeUnless(List<*>::isEmpty) }
            }
        }
    }
    override fun publish(message: String) {
        if (key == null) return rapidsConnection.publish(withTracing(message))
        publish(key, message)
    }

    override fun publish(key: String, message: String) {
        rapidsConnection.publish(key, withTracing(message))
    }

    private fun withTracing(message: String) = tracing?.let { tracing ->
        objectMapper.readTree(message).also {
            (it as ObjectNode).replace("@forårsaket_av", objectMapper.valueToTree(tracing))
        }.toString()
    } ?: message

    override fun rapidName(): String {
        return rapidsConnection.rapidName()
    }
}