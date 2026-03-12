package com.github.navikt.tbd_libs.rapids_and_rivers

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TestRapidTest {
    private lateinit var testRapid: TestRapid

    @BeforeEach
    fun setup() {
        testRapid = TestRapid()
    }

    @Test
    fun `kan publisere test melding uten key og MessageContext har null som key`() {
        val originalMessage = "a test message!"
        val key = "a key"

        testRapid.register { _: String, context: MessageContext, _, _ ->
            context.publish(originalMessage)
        }

        testRapid.sendTestMessage(originalMessage)

        assertEquals(1, testRapid.inspektør.size)
        assertNull(testRapid.inspektør.key(0))
    }

    @Test
    fun `kan publisere test melding med key og den blir med i MessageContext`() {
        val originalMessage = "a test message!"
        val key = "a key"

        testRapid.register { _: String, context: MessageContext, _, _ ->
            context.publish(originalMessage)
        }

        testRapid.sendTestMessage(originalMessage, key)

        assertEquals(1, testRapid.inspektør.size)
        assertEquals(key, testRapid.inspektør.key(0))
    }
}