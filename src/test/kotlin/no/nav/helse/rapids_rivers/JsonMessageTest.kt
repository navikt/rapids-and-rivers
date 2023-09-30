package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.*
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.*

internal class JsonMessageTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val ValidJson = "{\"foo\": \"bar\"}"
    private val InvalidJson = "foo"
    private val ValidJsonNoObject = "[]"

    @Test
    fun `create message from map`() {
        val node = objectMapper.readTree(JsonMessage.newMessage(mapOf("foo" to "bar")).toJson())
        assertDoesNotThrow { node.path("@id").asText().toUUID() }
        assertDoesNotThrow { LocalDateTime.parse(node.path("@opprettet").asText()) }
        assertEquals("bar", node.path("foo").asText())
    }

    @Test
    fun `create need from map`() {
        val behovsliste = listOf("behov1", "behov2")
        val node = objectMapper.readTree(JsonMessage.newNeed(behovsliste, mapOf("foo" to "bar")).toJson())
        assertEquals(behovsliste, node.path("@behov").map(JsonNode::asText))
        assertDoesNotThrow { node.path("@behovId").asText().toUUID() }
        assertDoesNotThrow { node.path("@id").asText().toUUID() }
        assertDoesNotThrow { LocalDateTime.parse(node.path("@opprettet").asText()) }
        assertEquals("behov", node.path("@event_name").asText())
        assertEquals("bar", node.path("foo").asText())
    }

    @Test
    fun `overwrite default values`() {
        val node = objectMapper.readTree(JsonMessage.newNeed(listOf("behov1"), mapOf("@event_name" to "other_name")).toJson())
        assertEquals("other_name", node.path("@event_name").asText())
    }

    @Test
    fun `does not overwrite id`() {
        val customId = UUID.randomUUID()
        val msg = JsonMessage.newMessage(mapOf("@id" to customId))
        val node = objectMapper.readTree(msg.toJson())
        assertEquals(customId.toString(), msg.id)
        assertEquals(msg.id, node.path("@id").asText())
    }

    @Test
    fun `sets id if missing`() {
        val msg = JsonMessage.newMessage()
        val node = objectMapper.readTree(msg.toJson())
        assertDoesNotThrow { node.path("@id").asText().toUUID() }
    }

    @Test
    fun `custom id generator`() {
        val expected = "notSoRandom"
        val msg = JsonMessage.newMessage() { expected }
        assertEquals(expected, msg.id)
    }

    @Test
    fun `populate missing fields`() {
        val expected = "notSoRandom"
        val msg = JsonMessage.newMessage() { expected }
        val json1 = JsonMessage.populateStandardFields(msg, msg.toJson())
        val node1 = objectMapper.readTree(json1)
        assertEquals(expected, node1.path("@forårsaket_av").path("id").asText())
        assertEquals(expected, node1.path("@id").asText())
        val expected2 = "differentRandom"
        val json2 = JsonMessage.populateStandardFields(msg, msg.toJson()) { expected2 }
        val node2 = objectMapper.readTree(json2)
        assertEquals(expected, node2.path("@forårsaket_av").path("id").asText())
        assertEquals(expected2, node2.path("@id").asText())
    }

    @Test
    fun `invalid json`() {
        MessageProblems(InvalidJson).also {
            assertThrows<MessageProblems.MessageException> {
                JsonMessage(InvalidJson, it)
            }
            assertTrue(it.hasErrors()) { "was not supposed to recognize $InvalidJson" }
        }
    }

    @Test
    fun `valid json - but not object`() {
        MessageProblems(ValidJsonNoObject).also {
            assertThrows<MessageProblems.MessageException> {
                JsonMessage(ValidJsonNoObject, it)
            }
            assertTrue(it.hasErrors()) { "was not supposed to recognize $ValidJsonNoObject" }
        }
    }

    @Test
    fun `require custom parser`() {
        MessageProblems("").apply {
            JsonMessage("{\"foo\": \"bar\"}", this).apply {
                require("foo", JsonNode::asLocalDate)
            }
            assertTrue(hasErrors())
        }
        MessageProblems("").apply {
            JsonMessage("{\"foo\": \"2020-01-01\"}", this).apply {
                require("foo", JsonNode::asLocalDate)
            }
            assertFalse(hasErrors())
        }
        MessageProblems("").apply {
            JsonMessage("{\"foo\": null}", this).apply {
                require("foo", JsonNode::asLocalDate)
            }
            assertTrue(hasErrors())
        }
    }

    @Test
    fun `interested in custom parser`() {
        MessageProblems("").apply {
            JsonMessage("{\"foo\": \"bar\"}", this).apply {
                interestedIn("foo", JsonNode::asLocalDate)
            }
            assertTrue(hasErrors())
        }
        MessageProblems("").apply {
            JsonMessage("{\"foo\": \"2020-01-01\"}", this).apply {
                interestedIn("foo", JsonNode::asLocalDate)
            }
            assertFalse(hasErrors())
        }
        MessageProblems("").apply {
            JsonMessage("{\"foo\": null}", this).apply {
                interestedIn("foo", JsonNode::asLocalDate)
            }
            assertFalse(hasErrors())
        }
    }

    @Test
    fun `demand custom parser`() {
        MessageProblems("").apply {
            JsonMessage("{\"foo\": \"bar\"}", this).apply {
                assertThrows<MessageProblems.MessageException> {
                    demand("foo", JsonNode::asLocalDate)
                }
            }
            assertTrue(hasErrors())
        }
        MessageProblems("").apply {
            JsonMessage("{\"foo\": \"2020-01-01\"}", this).apply {
                demand("foo", JsonNode::asLocalDate)
            }
            assertFalse(hasErrors())
        }
        MessageProblems("").apply {
            JsonMessage("{\"foo\": null}", this).apply {
                assertThrows<MessageProblems.MessageException> {
                    demand("foo", JsonNode::asLocalDate)
                }
            }
            assertTrue(hasErrors())
        }
    }

    @Test
    fun `rejected booolean`() {
        assertThrows<MessageProblems.MessageException> { message("{\"key\": true}").apply { rejectValue("key", true) } }
        assertDoesNotThrow { message("{\"key\": false}").apply { rejectValue("key", true) } }
        assertDoesNotThrow { message("{\"key\": null}").apply { rejectValue("key", true) } }
        assertDoesNotThrow { message("{\"otherkey\": \"foo\"}").apply { rejectValue("key", true) } }
    }

    @Test
    fun `rejected value`() {
        assertThrows<MessageProblems.MessageException> { message("{\"key\": \"bar\"}").apply { rejectValues("key", listOf("bar")) } }
        assertDoesNotThrow { message("{\"key\": null}").apply { rejectValues("key", listOf("bar")) } }
        assertDoesNotThrow { message("{\"otherkey\": \"foo\"}").apply { rejectValues("key", listOf("bar")) } }
        assertDoesNotThrow { message("{\"key\": \"foo\"}").apply { rejectValues("key", listOf("bar")) } }
    }

    @Test
    fun `valid json`() {
        val problems = MessageProblems(ValidJson)
        JsonMessage(ValidJson, problems)
        assertFalse(problems.hasErrors())
    }

    @Test
    fun `read count`() {
        val problems = MessageProblems("{}")
        val firstMessage = JsonMessage("{}", problems).apply {
            interestedIn("system_read_count")
        }
        assertEquals(0, firstMessage["system_read_count"].intValue())

        val secondMessage = JsonMessage(firstMessage.toJson(), problems).apply {
            interestedIn("system_read_count")
        }
        assertEquals(1, secondMessage["system_read_count"].intValue())
    }

    @Test
    fun `set value`() {
        val problems = MessageProblems("{}")
        val message = JsonMessage("{}", problems)
        assertThrows<IllegalArgumentException> { message["key"] }
        message["key"] = "Hello!"
        assertEquals("Hello!", message["key"].asText())
    }

    @Test
    fun `update value`() {
        val problems = MessageProblems("{}")
        val message = JsonMessage("{}", problems).apply {
            interestedIn("key")
        }
        assertTrue(message["key"].isMissingNode)
        message["key"] = "Hello!"
        assertEquals("Hello!", message["key"].asText())
    }

    @Test
    fun `extended message`() {
        "not_valid_json".also { json ->
            MessageProblems(json).also {
                assertThrows<MessageProblems.MessageException> {
                    ExtendedMessage(json, it)
                }
                assertTrue(it.hasErrors()) { "was not supposed to recognize $json" }
            }
        }

        "{}".also { json ->
            val problems = MessageProblems(json)
            ExtendedMessage(json, problems)
            assertTrue(problems.hasErrors())
        }

        "{\"required_key\": \"foo\"}".also { json ->
            val problems = MessageProblems(json)
            ExtendedMessage(json, problems)
            assertFalse(problems.hasErrors())
        }
    }

    @Test
    fun `keys must be declared before use`() {
        "{\"foo\": \"bar\"}".also { json ->
            message(json).also {
                assertThrows<IllegalArgumentException> { it["foo"] }
                it.requireKey("foo")
                assertEquals("bar", it["foo"].textValue())
            }
            message(json).also {
                it.requireKey("foo", "baz")
                assertEquals("bar", it["foo"].textValue())
                assertThrows<IllegalArgumentException> { it["baz"] }
            }
            message(json).also {
                assertThrows<IllegalArgumentException> { it["foo"] }
                it.interestedIn("foo")
                assertEquals("bar", it["foo"].textValue())
            }
        }
    }

    @Test
    fun `nested keys`() {
        "{\"foo\": { \"bar\": \"baz\" }}".also { json ->
            message(json).also {
                assertThrows<IllegalArgumentException> { it["foo.bar"] }
                it.requireKey("foo.bar")
                assertEquals("baz", it["foo.bar"].textValue())
            }
            message(json).also {
                assertThrows<IllegalArgumentException> { it["foo.bar"] }
                it.interestedIn("foo.bar")
                assertEquals("baz", it["foo.bar"].textValue())
            }
        }
    }

    @Test
    fun rejectKey() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    rejectKey("foo")
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    rejectKey("foo")
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": \"baz\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { rejectKey("foo") }
                    assertTrue(problems.hasErrors())
                }
            }
        }
    }

    @Test
    fun rejectValue() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    rejectValue("foo", "bar")
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    rejectValue("foo", "bar")
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": \"baz\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    rejectValue("foo", "bar")
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": \"bar\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { rejectValue("foo", "bar") }
                    assertTrue(problems.hasErrors())
                }
            }
        }
    }

    @Test
    fun demandKey() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandKey("foo") }
                    assertTrue(problems.hasErrors())
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandKey("foo") }
                    assertTrue(problems.hasErrors())
                }
            }
        }
        "{\"foo\": \"baz\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandKey("foo")
                    assertFalse(problems.hasErrors())
                }
            }
        }
    }

    @Test
    fun demandValue() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", "bar") }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", "bar") }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": \"baz\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", "bar") }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": \"bar\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandValue("foo", "bar")
                    assertFalse(problems.hasErrors())
                    assertEquals("bar", this["foo"].asText())
                }
            }
        }
        "{\"foo\": 3.14}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandValue("foo", 3.14)
                    assertFalse(problems.hasErrors())
                    assertEquals(3.14, this["foo"].numberValue())
                }
            }
        }
        "{\"foo\": \"3.14\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", 3.14) }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
    }

    @Test
    fun demandAllOrAny() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandAllOrAny("foo", listOf("bar", "baz")) }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandAllOrAny("foo", listOf("bar", "baz")) }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": \"baz\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandAllOrAny("foo", listOf("bar", "baz")) }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": [\"bar\"]}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandAllOrAny("foo", listOf("bar", "baz"))
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": [\"bar\", \"baz\"]}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandAllOrAny("foo", listOf("bar", "baz"))
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": [\"foo\", \"baz\"]}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandAllOrAny("foo", listOf("bar", "baz"))
                    assertFalse(problems.hasErrors())
                }
            }
        }
    }

    @Test
    fun demand() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", "bar") }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", "bar") }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": \"baz\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", "bar") }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": \"bar\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandValue("foo", "bar")
                    assertFalse(problems.hasErrors())
                    assertEquals("bar", this["foo"].asText())
                }
            }
        }
    }

    @Test
    fun requireAllOrAny() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    requireAllOrAny("foo", listOf("bar", "baz"))
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    requireAllOrAny("foo", listOf("bar", "baz"))
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": \"baz\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    requireAllOrAny("foo", listOf("bar", "baz"))
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": [\"bar\"]}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    requireAllOrAny("foo", listOf("bar", "baz"))
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": [\"bar\", \"baz\"]}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    requireAllOrAny("foo", listOf("bar", "baz"))
                    assertFalse(problems.hasErrors())
                }
            }
        }
        "{\"foo\": [\"foo\", \"baz\"]}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    requireAllOrAny("foo", listOf("bar", "baz"))
                    assertFalse(problems.hasErrors())
                }
            }
        }
    }

    @Test
    fun demandAll() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandAll("foo", listOf("bar", "baz")) }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandAll("foo", listOf("bar", "baz")) }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": \"bar\"}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandAll("foo", listOf("bar", "baz")) }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": [\"bar\"]}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandAll("foo", listOf("bar", "baz")) }
                    assertTrue(problems.hasErrors())
                    assertThrows(this, "foo")
                }
            }
        }
        "{\"foo\": [\"bar\", \"baz\"]}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandAll("foo", listOf("bar", "baz"))
                    assertFalse(problems.hasErrors())
                }
            }
        }
    }

    @Test
    fun demandValueOneOf() {
        "{\"foo\": \"bar\" }".also { json ->
            message(json).also {
                assertThrows(MessageProblems.MessageException::class.java) {
                    it.demandAny("foo", listOf("foo"))
                }
                assertThrows<IllegalArgumentException> { it["foo"] }
            }
            message(json).also {
                assertDoesNotThrow {
                    it.demandAny("foo", listOf("bar", "foobar"))
                }
                assertFalse(problems.hasErrors()) { "did not expect errors: $problems" }
                assertDoesNotThrow { it["foo"] }
            }
        }
    }

    @Test
    fun demandValueBoolean() {
        "{}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", false) }
                }
            }
        }
        "{\"foo\": null}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", false) }
                }
            }
        }
        "{\"foo\": true}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    assertThrows<MessageProblems.MessageException> { demandValue("foo", false) }
                }
            }
        }
        "{\"foo\": false}".also { json ->
            MessageProblems(json).also { problems ->
                JsonMessage(json, problems).apply {
                    demandValue("foo", false)
                    assertFalse(problems.hasErrors())
                }
            }
        }
    }

    @Test
    fun requiredValue() {
        "{}".also {
            assertThrows(it, "foo", "bar")
            assertThrows(it, "foo", false)
        }
        "{\"foo\": null}".also {
            assertThrows(it, "foo", "bar")
            assertThrows(it, "foo", false)
        }
        assertThrows("{\"foo\": \"baz\"}", "foo", "bar")
        assertThrows("{\"foo\": true}", "foo", false)

        assertEquals("{\"foo\": \"bar\"}", "foo", "bar")
        assertEquals("{\"foo\": false}", "foo", false)
        assertEquals("{\"foo\": 3.14}", "foo", 3.14)
        assertEquals("{\"foo\": 3}", "foo", 3)
    }

    @Test
    fun `string is not a number`() {
        @Language("JSON")
        val msg = """{"foo": "3"}"""
        val key = "foo"
        val expectedValue = 3
        val problems = MessageProblems(msg)
        JsonMessage(msg, problems).also {
            it.requireValue(key, expectedValue)
            assertTrue(problems.hasErrors())
        }
    }

    @Test
    fun requiredNestedValue() {
        assertEquals("{\"foo\": { \"bar\": \"baz\" } }", "foo.bar", "baz")
        assertEquals("{\"foo\": { \"bar\": true } }", "foo.bar", true)
    }

    @Test
    fun requiredValues() {
        assertThrows("{}", "foo", listOf("bar"))
        assertThrows("{\"foo\": null}", "foo", listOf("bar"))
        assertThrows("{\"foo\": [\"bar\"]}", "foo", listOf("bar", "foo"))
        message("{\"foo\": [\"bar\"]}").apply {
            requireContains("foo", "bar")
            assertFalse(problems.hasErrors())
        }

        assertEquals("{\"foo\": [\"bar\", \"foo\"]}", "foo", listOf("bar", "foo"))
        assertEquals("{\"foo\": [\"bar\", \"foo\", \"bla\"]}", "foo", listOf("bar", "foo"))
    }

    @Test
    fun requireArray() {
        message("{\"foo\": {}}").apply {
            requireArray("foo")
            assertTrue(problems.hasErrors())
        }
        message("{\"foo\": []}").apply {
            requireArray("foo")
            assertFalse(problems.hasErrors())
        }
    }

    @Test
    fun requireArrayElements() {
        message("{\"foo\": []}").apply {
            requireArray("foo") {
                requireKey("bar")
            }
            assertFalse(problems.hasErrors())
        }
        message("{\"foo\": [{}]}").apply {
            requireArray("foo") {
                requireKey("bar")
            }
            assertTrue(problems.hasErrors())
        }
        message("{\"foo\": [{\"bar\":\"baz\"}]}").apply {
            requireArray("foo") {
                requireKey("bar")
            }
            assertFalse(problems.hasErrors())
        }
    }

    @Test
    fun requiredValueOneOf() {
        "{\"foo\": \"bar\" }".also { json ->
            message(json).also {
                it.requireAny("foo", listOf("foo"))
                assertTrue(problems.hasErrors())
                assertThrows<IllegalArgumentException> { it["foo"] }
            }
            message(json).also {
                it.requireAny("foo", listOf("bar", "foobar"))
                assertFalse(problems.hasErrors()) { "did not expect errors: $problems" }
                assertDoesNotThrow { it["foo"] }
            }
        }
    }

    @Test
    fun requiredNestedValues() {
        assertEquals("{\"foo\": { \"bar\": [ \"baz\", \"foobar\" ] }}", "foo.bar", listOf("baz", "foobar"))
    }

    @Test
    fun `requiredKey can not return null`() {
        val message = message("{\"foo\": null}").apply {
            requireKey("foo")
        }

        assertThrows<IllegalArgumentException> {
            message["foo"]
        }
    }

    @Test
    fun `interestedIn can return null`() {
        val message = message("{\"foo\": null}").apply {
            interestedIn("foo", "bar")
        }
        assertTrue(message["foo"].isNull)
        assertTrue(message["bar"].isMissingNode)
        assertNull(message["foo"].textValue())
        assertNull(message["bar"].textValue())
    }

    @Test
    fun `forbidden key`() {
        message("{\"key\": \"foo\"}").apply { forbid("key") }
        assertTrue(problems.hasErrors())
        message("{}").apply { forbid("key") }
        assertFalse(problems.hasErrors())
        message("{\"key\": null}").apply { forbid("key") }
        assertFalse(problems.hasErrors())
        message("{\"other\": \"baz\", \"key2\": \"foo\"}").apply { forbid("key1", "key2") }
        assertTrue(problems.hasErrors())
    }

    @Test
    fun `forbidden values`() {
        message("{\"key\": \"foo\"}").apply { forbidValues("key", listOf("bar")) }
        assertFalse(problems.hasErrors())
        message("{\"key\": \"foo\"}").apply { forbidValues("key", listOf("foo")) }
        assertTrue(problems.hasErrors())
        message("{\"key\": \"bar\"}").apply { forbidValues("key", listOf("foo", "bar")) }
        assertTrue(problems.hasErrors())
        message("{}").apply { forbidValues("key", listOf("foo")) }
        assertFalse(problems.hasErrors())
        message("{\"key\": null}").apply { forbidValues("key", listOf("foo")) }
        assertFalse(problems.hasErrors())
    }

    @Test
    fun asLocalDate() {
        assertThrows<DateTimeParseException> { MissingNode.getInstance().asLocalDate() }
        assertThrows<DateTimeParseException> { NullNode.instance.asLocalDate() }
        assertThrows<DateTimeParseException> { BooleanNode.TRUE.asLocalDate() }
        assertThrows<DateTimeParseException> { IntNode(0).asLocalDate() }
        assertThrows<DateTimeParseException> { TextNode.valueOf("").asLocalDate() }
        with("2020-01-01") {
            assertEquals(LocalDate.parse(this), TextNode.valueOf(this).asLocalDate())
        }
    }

    @Test
    fun asYearMonth() {
        assertThrows<DateTimeParseException> { MissingNode.getInstance().asYearMonth() }
        assertThrows<DateTimeParseException> { NullNode.instance.asYearMonth() }
        assertThrows<DateTimeParseException> { BooleanNode.TRUE.asYearMonth() }
        assertThrows<DateTimeParseException> { IntNode(0).asYearMonth() }
        assertThrows<DateTimeParseException> { TextNode.valueOf("").asYearMonth() }
        with("2020-01") {
            assertEquals(YearMonth.parse(this), TextNode.valueOf(this).asYearMonth())
        }
    }

    @Test
    fun asOptionalLocalDate() {
        assertNull(MissingNode.getInstance().asOptionalLocalDate())
        assertNull(NullNode.instance.asOptionalLocalDate())
        assertNull(BooleanNode.TRUE.asOptionalLocalDate())
        assertNull(IntNode(0).asOptionalLocalDate())
        assertNull(TextNode.valueOf("").asOptionalLocalDate())
        with("2020-01-01") {
            assertEquals(LocalDate.parse(this), TextNode.valueOf(this).asOptionalLocalDate())
        }
    }


    @Test
    fun asLocalDateTime() {
        assertThrows<DateTimeParseException> { MissingNode.getInstance().asLocalDateTime() }
        assertThrows<DateTimeParseException> { NullNode.instance.asLocalDateTime() }
        assertThrows<DateTimeParseException> { BooleanNode.TRUE.asLocalDateTime() }
        assertThrows<DateTimeParseException> { IntNode(0).asLocalDateTime() }
        assertThrows<DateTimeParseException> { TextNode.valueOf("").asLocalDateTime() }
        with("2020-01-01T00:00:00.000000") {
            assertEquals(LocalDateTime.parse(this), TextNode.valueOf(this).asLocalDateTime())
        }
    }

    private fun assertEquals(msg: String, key: String, expectedValue: String) {
        val problems = MessageProblems(msg)
        JsonMessage(msg, problems).also {
            it.requireValue(key, expectedValue)
            assertFalse(problems.hasErrors())
            assertEquals(expectedValue, it[key].textValue())
        }
    }

    private fun assertEquals(msg: String, key: String, expectedValue: Number) {
        val problems = MessageProblems(msg)
        JsonMessage(msg, problems).also {
            it.requireValue(key, expectedValue)
            assertFalse(problems.hasErrors())
            assertEquals(expectedValue, it[key].numberValue())
        }
    }

    private fun assertEquals(msg: String, key: String, expectedValue: Boolean) {
        val problems = MessageProblems(msg)
        JsonMessage(msg, problems).also {
            it.requireValue(key, expectedValue)
            assertFalse(problems.hasErrors())
            assertEquals(expectedValue, it[key].booleanValue())
        }
    }

    private fun assertEquals(msg: String, key: String, expectedValues: List<String>) {
        val problems = MessageProblems(msg)
        JsonMessage(msg, problems).also {
            it.requireAll(key, expectedValues)
            assertFalse(problems.hasErrors())
        }
    }

    private fun assertThrows(msg: String, key: String, expectedValues: List<String>) {
        val problems = MessageProblems(msg)
        JsonMessage(msg, problems).also {
            it.requireAll(key, expectedValues)
            assertTrue(problems.hasErrors())
        }
    }

    private fun assertThrows(msg: String, key: String, expectedValue: Boolean) {
        val problems = MessageProblems(msg)
        JsonMessage(msg, problems).also {
            it.requireValue(key, expectedValue)
            assertTrue(problems.hasErrors())
            assertThrows(it, key)
        }
    }

    private fun assertThrows(msg: String, key: String, expectedValue: String) {
        val problems = MessageProblems(msg)
        JsonMessage(msg, problems).also {
            it.requireValue(key, expectedValue)
            assertTrue(problems.hasErrors())
            assertThrows(it, key)
        }
    }

    private fun assertThrows(message: JsonMessage, key: String) {
        assertThrows<IllegalArgumentException>({ "should throw exception, instead returned ${message[key]}" }) {
            message[key]
        }
    }

    private lateinit var problems: MessageProblems
    private fun message(json: String): JsonMessage {
        problems = MessageProblems(json)
        return JsonMessage(json, problems)
    }

    private class ExtendedMessage(originalMessage: String, problems: MessageProblems) :
        JsonMessage(originalMessage, problems) {
        init {
            requireKey("required_key")
        }
    }
}
