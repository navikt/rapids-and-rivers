package no.nav.helse.rapids_rivers


import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.InetAddress
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.*

// Understands a specific JSON-formatted message
open class JsonMessage(
    originalMessage: String,
    private val problems: MessageProblems,
    private val metrics: MeterRegistry,
    randomIdGenerator: RandomIdGenerator? = null
) {
    private val idGenerator = randomIdGenerator ?: RandomIdGenerator.Default
    val id: String

    companion object {
        private val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        private const val nestedKeySeparator = '.'
        private const val IdKey = "@id"
        private const val OpprettetKey = "@opprettet"
        private const val EventNameKey = "@event_name"
        private const val NeedKey = "@behov"
        private const val ReadCountKey = "system_read_count"
        private const val ParticipatingServicesKey = "system_participating_services"

        private val serviceName: String? = System.getenv("NAIS_APP_NAME")
        private val serviceImage: String? = System.getenv("NAIS_APP_IMAGE")
        private val serviceHostname = serviceName?.let { InetAddress.getLocalHost().hostName }

        fun newMessage(
            map: Map<String, Any> = emptyMap(),
            metrics: MeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
            randomIdGenerator: RandomIdGenerator? = null
        ) = objectMapper.writeValueAsString(map).let {
            JsonMessage(it, MessageProblems(it), metrics, randomIdGenerator)
        }

        fun newMessage(
            eventName: String,
            map: Map<String, Any> = emptyMap(),
            metrics: MeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
            randomIdGenerator: RandomIdGenerator? = null
        ) = newMessage(mapOf(EventNameKey to eventName) + map, metrics, randomIdGenerator)

        fun newNeed(
            behov: Collection<String>,
            map: Map<String, Any> = emptyMap(),
            metrics: MeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
            randomIdGenerator: RandomIdGenerator? = null
        ) = newMessage("behov", mapOf(
            "@behovId" to UUID.randomUUID(),
            NeedKey to behov
        ) + map, metrics, randomIdGenerator)

        internal fun populateStandardFields(originalMessage: JsonMessage, message: String, randomIdGenerator: RandomIdGenerator = originalMessage.idGenerator): String {
            return (objectMapper.readTree(message) as ObjectNode).also {
                it.replace("@forårsaket_av", objectMapper.valueToTree(originalMessage.tracing))
                if (it.path("@id").isMissingOrNull() || it.path("@id").asText() == originalMessage.id) {
                    val id = randomIdGenerator.generateId()
                    val opprettet = LocalDateTime.now()
                    it.put(IdKey, id)
                    it.put(OpprettetKey, "$opprettet")
                    initializeOrSetParticipatingServices(it, id, opprettet)
                }
            }.toString()
        }

        private fun initializeOrSetParticipatingServices(node: JsonNode, id: String, opprettet: LocalDateTime) {
            val entry = mutableMapOf(
                "id" to id,
                "time" to "$opprettet"
            ).apply {
                compute("service") { _, _ -> serviceName }
                compute("instance") { _, _ -> serviceHostname }
                compute("image") { _, _ -> serviceImage }
            }
            if (node.path(ParticipatingServicesKey).isMissingOrNull()) (node as ObjectNode).putArray(ParticipatingServicesKey).add(objectMapper.valueToTree<ObjectNode>(entry))
            else (node.path(ParticipatingServicesKey) as ArrayNode).add(objectMapper.valueToTree<JsonNode>(entry))
        }

        private fun parseMessageAsJsonObject(message: String, problems: MessageProblems): ObjectNode {
            val jsonNode = try {
                objectMapper.readTree(message)
            } catch (err: JsonParseException) {
                problems.typedSevere("Invalid JSON per Jackson library: ${err.message}")
            }
            if (!jsonNode.isObject) problems.typedSevere("Incomplete json. Should be able to cast as ObjectNode.")
            return jsonNode as ObjectNode
        }
    }

    private val json: ObjectNode
    private val recognizedKeys = mutableMapOf<String, JsonNode>()

    init {
        json = parseMessageAsJsonObject(originalMessage, problems)
        id = json.path("@id").takeUnless { it.isMissingOrNull() }?.asText() ?: idGenerator.generateId().also {
            set("@id", it)
        }
        val opprettet = LocalDateTime.now()
        if (!json.hasNonNull("@opprettet")) set(OpprettetKey, opprettet)
        set(ReadCountKey, json.path(ReadCountKey).asInt(-1) + 1)
        initializeOrSetParticipatingServices(json, id, opprettet)
    }

    private val tracing =
        mutableMapOf<String, Any>(
            "id" to json.path(IdKey).asText()
        ).apply {
            compute("opprettet") { _, _ -> json.path(OpprettetKey).asText().takeUnless { it.isBlank() } }
            compute("event_name") { _, _ -> json.path(EventNameKey).asText().takeUnless { it.isBlank() } }
            compute("behov") { _, _ -> json.path(NeedKey).map(JsonNode::asText).takeUnless(List<*>::isEmpty) }
        }.toMap()

    fun rejectKey(vararg key: String) {
        key.forEach { rejectKey(it) }
    }

    private fun rejectKey(key: String) {
        val node = node(key)
        if (!node.isMissingNode && !node.isNull) problems.typedSevere("Rejected key $key exists", MessageProblemType.REJECTED_KEY_EXISTS)
        accessor(key)
    }

    fun rejectValue(key: String, value: String) {
        val node = node(key)
        if (!node.isMissingOrNull() && node.isTextual && node.asText() == value) problems.typedSevere("Rejected key $key with value $value", MessageProblemType.REJECTED_KEY_WITH_VALUE)
        accessor(key)
    }

    fun rejectValue(key: String, value: Boolean) {
        val node = node(key)
        if (!node.isMissingOrNull() && node.isBoolean && node.asBoolean() == value) problems.typedSevere("Rejected key $key with value $value", MessageProblemType.REJECTED_KEY_WITH_VALUE)
        accessor(key)
    }

    fun rejectValues(key: String, values: List<String>) {
        val node = node(key)
        if (!node.isMissingOrNull() && node.asText() in values) problems.typedSevere("Rejected key $key with value ${node.asText()}", MessageProblemType.REJECTED_KEY_WITH_VALUE)
        accessor(key)
    }

    fun demandKey(key: String) {
        val node = node(key)
        if (node.isMissingNode) problems.typedSevere("Missing demanded key $key", MessageProblemType.MISSING_DEMANDED_KEY)
        if (node.isNull) problems.typedSevere("Demanded key $key is null", MessageProblemType.DEMANDED_KEY_IS_NULL)
        accessor(key)
    }

    fun demandValue(key: String, value: String) {
        val node = node(key)
        if (node.isMissingNode) problems.typedSevere("Missing demanded key $key", MessageProblemType.MISSING_DEMANDED_KEY)
        if (!node.isTextual || node.asText() != value) problems.typedSevere("Demanded $key is not string $value", MessageProblemType.DEMANDED_IS_NOT_STRING)
        accessor(key)
    }

    fun demandValue(key: String, value: Number) {
        val node = node(key)
        if (node.isMissingNode) problems.typedSevere("Missing demanded key $key", MessageProblemType.MISSING_DEMANDED_KEY)
        if (!node.isNumber || node.numberValue() != value) problems.typedSevere("Demanded $key is not number $value", MessageProblemType.DEMANDED_IS_NOT_NUMBER)
        accessor(key)
    }

    fun demandValue(key: String, value: Boolean) {
        val node = node(key)
        if (node.isMissingNode) problems.typedSevere("Missing demanded key $key", MessageProblemType.MISSING_DEMANDED_KEY)
        if (!node.isBoolean || node.booleanValue() != value) problems.typedSevere("Demanded $key is not boolean $value", MessageProblemType.DEMANDED_IS_NOT_BOOLEAN)
        accessor(key)
    }

    fun demandAll(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) problems.typedSevere("Missing demanded key $key", MessageProblemType.MISSING_DEMANDED_KEY)
        if (!node.isArray || !node.map(JsonNode::asText).containsAll(values)) problems.typedSevere("Demanded $key does not contains $values", MessageProblemType.DEMANDED_DOES_NOT_CONTAIN)
        accessor(key)
    }

    fun demandAny(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) problems.typedSevere("Missing demanded key $key", MessageProblemType.MISSING_DEMANDED_KEY)
        if (!node.isTextual || node.asText() !in values) problems.typedSevere("Demanded $key must be one of $values", MessageProblemType.DEMANDED_MUST_BE_ONE_OF)
        accessor(key)
    }

    fun demandAllOrAny(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) problems.typedSevere("Missing demanded key $key", MessageProblemType.MISSING_DEMANDED_KEY)
        if (!node.isArray || node.map(JsonNode::asText).none { it in values }) problems.typedSevere("Demanded array $key does not contain one of $values", MessageProblemType.DEMANDED_ARRAY_DOES_NOT_CONTAIN_ONE_OF)
        accessor(key)
    }

    fun demandAll(key: String, vararg values: Enum<*>) {
        demandAll(key, values.map(Enum<*>::name))
    }

    fun demand(key: String, parser: (JsonNode) -> Any) {
        val node = node(key)
        if (node.isMissingNode) problems.typedSevere("Missing demanded key $key", MessageProblemType.MISSING_DEMANDED_KEY)
        try {
            parser(node)
        } catch (err: Exception) {
            problems.typedSevere("Demanded $key did not match the predicate: ${err.message}", MessageProblemType.DEMANDED_DID_NOT_MATCH_THE_PREDICATE)
        }
        accessor(key)
    }

    fun requireKey(vararg keys: String) {
        keys.forEach { requireKey(it) }
    }

    fun requireValue(key: String, value: Boolean) {
        val node = node(key)
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        if (!node.isBoolean || node.booleanValue() != value) return problems.typedError("Required $key is not boolean $value", MessageProblemType.DEMANDED_IS_NOT_BOOLEAN, key)
        accessor(key)
    }
    fun requireValue(key: String, value: String) {
        val node = node(key)
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        if (!node.isTextual || node.asText() != value) return problems.typedError("Required $key is not string $value", MessageProblemType.DEMANDED_IS_NOT_STRING, key)
        accessor(key)
    }

    fun requireValue(key: String, value: Number) {
        val node = node(key)
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        if (!node.isNumber || node.numberValue() != value) return problems.typedError("Required $key is not number $value", MessageProblemType.REQUIRED_IS_NOT_NUMBER, key)
        accessor(key)
    }

    fun requireAny(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        if (!node.isTextual || node.asText() !in values) return problems.typedError("Required $key must be one of $values", MessageProblemType.REQUIRED_MUST_BE_ONE_OF, key)
        accessor(key)
    }

    fun requireArray(key: String, elementsValidation: (JsonMessage.() -> Unit)? = null) {
        val node = node(key)
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        if (!node.isArray) return problems.typedError("Required $key is not an array", MessageProblemType.REQUIRED_IS_NOT_AN_ARRAY, key)
        elementsValidation?.also {
            node.forEachIndexed { index, element ->
                val elementJson = element.toString()
                val elementProblems = MessageProblems(elementJson)
                JsonMessage(elementJson, elementProblems, metrics).apply(elementsValidation)
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
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        if (!node.isArray || node.map(JsonNode::asText).none { it in values }) {
            return problems.typedError("Required array $key does not contain one of $values", MessageProblemType.REQUIRED_ARRAY_DOES_NOT_CONTAIN_ONE_OF, key)
        }
        accessor(key)
    }

    fun requireAll(key: String, values: List<String>) {
        val node = node(key)
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        if (!node.isArray || !node.map(JsonNode::asText).containsAll(values)) {
            return problems.typedError("Required $key does not contains $values", MessageProblemType.REQUIRED_DOES_NOT_CONTAIN, key)
        }
        accessor(key)
    }

    fun requireAll(key: String, vararg values: Enum<*>) {
        requireAll(key, values.map(Enum<*>::name))
    }

    fun require(key: String, parser: (JsonNode) -> Any) {
        val node = node(key)
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        try {
            parser(node)
        } catch (err: Exception) {
            return problems.typedError("Required $key did not match the predicate: ${err.message}", MessageProblemType.REQUIRED_DID_NOT_MATCH_THE_PREDICATE, key)
        }
        accessor(key)
    }

    fun forbid(vararg key: String) {
        key.forEach { forbid(it) }
    }

    fun forbidValues(key: String, values: List<String>) {
        val node = node(key)
        if (!node.isMissingOrNull() && node.isTextual && node.asText() in values) return problems.typedError("Required $key is one of $values", MessageProblemType.REQUIRED_IS_ONE_OF, key)
        accessor(key)
    }

    fun interestedIn(vararg key: String) {
        key.forEach { accessor(it) }
    }

    fun interestedIn(key: String, parser: (JsonNode) -> Any) {
        val node = node(key)
        try {
            node.takeUnless(JsonNode::isMissingOrNull)?.also { parser(it) }
        } catch (err: Exception) {
            return problems.typedError("Optional $key did not match the predicate: ${err.message}", MessageProblemType.OPTIONAL_DID_NOT_MATCH_THE_PREDICATE, key)
        }
        accessor(key)
    }

    private fun requireKey(key: String) {
        val node = node(key)
        if (node.isMissingNode) return problems.typedError("Missing required key $key", MessageProblemType.MISSING_REQUIRED_KEY, key)
        if (node.isNull) return problems.typedError("Required key $key is null", MessageProblemType.REQUIRED_KEY_IS_NULL, key)
        accessor(key)
    }

    private fun forbid(key: String) {
        val node = node(key)
        if (!node.isMissingNode && !node.isNull) return problems.typedError("Forbidden key $key exists", MessageProblemType.FORBIDDEN_KEY_EXISTS, key)
        accessor(key)
    }

    private fun accessor(key: String) {
        val eventName = json.path(EventNameKey).asText().takeUnless { it.isBlank() } ?: "unknown_event"
        Counter.builder("message_keys_counter")
            .description("Hvilke nøkler som er i bruk")
            .tag("event_name", eventName)
            .tag("accessor_key", key)
            .register(metrics)
            .increment()

        recognizedKeys.computeIfAbsent(key) { node(key) }
    }

    private fun node(path: String): JsonNode {
        if (!path.contains(nestedKeySeparator)) return json.path(path)
        return path.split(nestedKeySeparator).fold(json) { result: JsonNode, key ->
            result.path(key)
        }
    }

    operator fun get(key: String): JsonNode =
        requireNotNull(recognizedKeys[key]) { "$key is unknown; keys must be declared as required, forbidden, or interesting" }

    operator fun set(key: String, value: Any) {
        json.replace(key, objectMapper.valueToTree<JsonNode>(value).also {
            recognizedKeys[key] = it
        })
    }

    fun toJson(): String = objectMapper.writeValueAsString(json)
}

fun String.toUUID(): UUID = UUID.fromString(this)

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
