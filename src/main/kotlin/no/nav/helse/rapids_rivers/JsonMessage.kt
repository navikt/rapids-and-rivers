package no.nav.helse.rapids_rivers


import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

// Understands a specific JSON-formatted message
open class JsonMessage(
    originalMessage: String,
    private val problems: MessageProblems
) {
    private companion object {
        private val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        private const val nestedKeySeparator = '.'
        private const val ReadCountKey = "system_read_count"
    }

    private val json: JsonNode

    private val recognizedKeys = mutableMapOf<String, JsonNode>()

    init {
        json = try {
            objectMapper.readTree(originalMessage)
        } catch (err: JsonParseException) {
            problems.severe("Invalid JSON per Jackson library: ${err.message}")
        }

        set(ReadCountKey, json.path(ReadCountKey).asInt(-1) + 1)
    }

    fun requireKey(vararg keys: String) {
        keys.forEach { requireKey(it) }
    }

    fun requireKey(key: String) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        if (node.isNull) return problems.error("Required key $key is null")
        accessor(key)
    }

    fun requireValue(key: String, value: Boolean) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        if (!node.isBoolean || node.booleanValue() != value) return problems.error("Required $key is not boolean $value")
        accessor(key)
    }

    fun requireValue(key: String, value: String) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        if (!node.isTextual || node.asText() != value) return problems.error("Required $key is not string $value")
        accessor(key)
    }

    fun requireAny(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        if (!node.isTextual || node.asText() !in values) return problems.error("Required $key must be one of $values")
        accessor(key)
    }

    fun requireAll(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        if (!node.isArray || !node.map(JsonNode::asText).containsAll(values)) {
            return problems.error("Required $key does not contains $values")
        }
        accessor(key)
    }

    fun requireAll(key: String, vararg values: Enum<*>) {
        requireAll(key, values.map(Enum<*>::name))
    }

    fun forbid(key: String) {
        val node = node(key)
        if (!node.isMissingNode && !node.isNull) return problems.error("Forbidden key $key exists")
        accessor(key)
    }

    fun interestedIn(key: String) {
        accessor(key)
    }

    private fun accessor(key: String) {
        recognizedKeys.computeIfAbsent(key) { node(key) }
    }

    private fun node(path: String): JsonNode {
        if (!path.contains(nestedKeySeparator)) return json.path(path)
        return path.split(nestedKeySeparator).fold(json) { result, key ->
            result.path(key)
        }
    }

    operator fun get(key: String): JsonNode =
        requireNotNull(recognizedKeys[key]) { "$key is unknown; keys must be declared as required, forbidden, or interesting" }

    operator fun set(key: String, value: Any) {
        (json as ObjectNode).replace(key, objectMapper.valueToTree(value))
    }

    fun toJson(): String = objectMapper.writeValueAsString(json)
}

fun JsonNode.isMissingOrNull() = isMissingNode || isNull

fun JsonNode.asLocalDate(): LocalDate =
    asText().let { LocalDate.parse(it) }

fun JsonNode.asYearMonth(): YearMonth =
    asText().let { YearMonth.parse(it) }

fun JsonNode.asOptionalLocalDate() =
    takeIf(JsonNode::isTextual)
        ?.asText()
        ?.takeIf(String::isNotEmpty)
        ?.let { LocalDate.parse(it) }

fun JsonNode.asOptionalLocalDateTime() =
    takeIf(JsonNode::isTextual)
        ?.asText()
        ?.takeIf(String::isNotEmpty)
        ?.let { LocalDateTime.parse(it) }

fun JsonNode.asLocalDateTime(): LocalDateTime =
    asText().let { LocalDateTime.parse(it) }
