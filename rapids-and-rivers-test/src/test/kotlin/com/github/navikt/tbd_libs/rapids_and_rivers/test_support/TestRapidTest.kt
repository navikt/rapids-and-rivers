package com.github.navikt.tbd_libs.rapids_and_rivers.test_support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class TestRapidTest {

    private val rapid = TestRapid()

    @BeforeEach
    fun setup() {
        rapid.reset()
    }

    @Test
    fun `send and read messages`() {
        rapid.publish("""{ "hello": "world" }""")
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
            rapid.publish("""{ "hello": "world" }""")
            rapid.inspektør.field(0, "does_not_exist")
        }
    }
}
