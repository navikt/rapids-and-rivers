package no.nav.helse.rapids_rivers


import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetAddress
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

// Understands a specific JSON-formatted message
open class JsonMessage(
    originalMessage: String,
    private val problems: MessageProblems
) {
    companion object {
        private val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        private const val nestedKeySeparator = '.'
        private const val ReadCountKey = "system_read_count"
        private const val ParticipatingServicesKey = "system_participating_services"

        private val serviceName: String? = System.getenv("NAIS_APP_NAME")
        private val serviceHostname = serviceName?.let { InetAddress.getLocalHost().hostName }

        fun newMessage(map: Map<String, Any> = emptyMap()) =
            objectMapper.writeValueAsString(map).let { JsonMessage(it, MessageProblems(it)) }
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

        if (serviceName != null && serviceHostname != null) {
            val entry = mapOf(
                "service" to serviceName,
                "instance" to serviceHostname,
                "time" to LocalDateTime.now()
            )
            if (json.path(ParticipatingServicesKey).isMissingOrNull()) set(ParticipatingServicesKey, listOf(entry))
            else (json.path(ParticipatingServicesKey) as ArrayNode).add(objectMapper.valueToTree<JsonNode>(entry))
        }
    }

    fun rejectKey(vararg key: String) {
        key.forEach { rejectKey(it) }
    }

    private fun rejectKey(key: String) {
        val node = node(key)
        if (!node.isMissingNode && !node.isNull) problems.severe("Rejected key $key exists")
        accessor(key)
    }

    fun rejectValue(key: String, value: String) {
        val node = node(key)
        if (!node.isMissingOrNull() && node.isTextual && node.asText() == value) problems.severe("Rejected key $key with value $value")
        accessor(key)
    }

    fun demandKey(key: String) {
        val node = node(key)
        if (node.isMissingNode) problems.severe("Missing demanded key $key")
        if (node.isNull) problems.severe("Demanded key $key is null")
        accessor(key)
    }

    fun demandValue(key: String, value: String) {
        val node = node(key)
        if (node.isMissingNode) problems.severe("Missing demanded key $key")
        if (!node.isTextual || node.asText() != value) problems.severe("Demanded $key is not string $value")
        accessor(key)
    }

    fun demandValue(key: String, value: Boolean) {
        val node = node(key)
        if (node.isMissingNode) problems.severe("Missing demanded key $key")
        if (!node.isBoolean || node.booleanValue() != value) problems.severe("Demanded $key is not boolean $value")
        accessor(key)
    }

    fun demandAll(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) problems.severe("Missing demanded key $key")
        if (!node.isArray || !node.map(JsonNode::asText).containsAll(values)) problems.severe("Demanded $key does not contains $values")
        accessor(key)
    }

    fun demandAny(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) problems.severe("Missing demanded key $key")
        if (!node.isTextual || node.asText() !in values) problems.severe("Demanded $key must be one of $values")
        accessor(key)
    }

    fun demandAllOrAny(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) problems.severe("Missing demanded key $key")
        if (!node.isArray || node.map(JsonNode::asText).none { it in values }) problems.severe("Demanded array $key does not contain one of $values")
        accessor(key)
    }

    fun demandAll(key: String, vararg values: Enum<*>) {
        demandAll(key, values.map(Enum<*>::name))
    }

    fun demand(key: String, parser: (JsonNode) -> Any) {
        val node = node(key)
        if (node.isMissingNode) problems.severe("Missing demanded key $key")
        try {
            parser(node)
        } catch (err: Exception) {
            problems.severe("Demanded $key did not match the predicate: ${err.message}")
        }
        accessor(key)
    }

    fun requireKey(vararg keys: String) {
        keys.forEach { requireKey(it) }
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

    fun requireArray(key: String, elementsValidation: (JsonMessage.() -> Unit)? = null) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        if (!node.isArray) return problems.error("Required $key is not an array")
        elementsValidation?.also {
            node.forEachIndexed { index, element ->
                val elementJson = element.toString()
                val elementProblems = MessageProblems(elementJson)
                JsonMessage(elementJson, elementProblems).apply(elementsValidation)
                if (elementProblems.hasErrors()) problems.error("Array element #$index at $key did not pass validation:", elementProblems)
            }
        }
        if (!problems.hasErrors()) accessor(key)
    }

    fun requireContains(key: String, value: String) {
        requireAll(key, listOf(value))
    }

    fun requireAllOrAny(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        if (!node.isArray || node.map(JsonNode::asText).none { it in values }) {
            return problems.error("Required array $key does not contain one of $values")
        }
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

    fun require(key: String, parser: (JsonNode) -> Any) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        try {
            parser(node)
        } catch (err: Exception) {
            return problems.error("Required $key did not match the predicate: ${err.message}")
        }
        accessor(key)
    }

    fun forbid(vararg key: String) {
        key.forEach { forbid(it) }
    }

    fun interestedIn(vararg key: String) {
        key.forEach { accessor(it) }
    }

    fun interestedIn(key: String, parser: (JsonNode) -> Any) {
        val node = node(key)
        try {
            node.takeUnless(JsonNode::isMissingOrNull)?.also { parser(it) }
        } catch (err: Exception) {
            return problems.error("Optional $key did not match the predicate: ${err.message}")
        }
        accessor(key)
    }

    private fun requireKey(key: String) {
        val node = node(key)
        if (node.isMissingNode) return problems.error("Missing required key $key")
        if (node.isNull) return problems.error("Required key $key is null")
        accessor(key)
    }

    private fun forbid(key: String) {
        val node = node(key)
        if (!node.isMissingNode && !node.isNull) return problems.error("Forbidden key $key exists")
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
        (json as ObjectNode).replace(key, objectMapper.valueToTree<JsonNode>(value).also {
            recognizedKeys[key] = it
        })
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
