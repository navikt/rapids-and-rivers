package no.nav.helse.rapids_rivers

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class MessageProblemsTest {

    private lateinit var problems: MessageProblems

    private val Message = "the message"

    @BeforeEach
    internal fun setUp() {
        problems = MessageProblems(Message)
    }

    @Test
    internal fun `does not contain original message`() {
        assertFalse(problems.toString().contains(Message))
        val message = "a message"
        problems.error(message)
        assertFalse(problems.toString().contains(Message))
    }

    @Test
    internal fun `contains original message in extended report`() {
        assertFalse(problems.toExtendedReport().contains(Message))
        val message = "a message"
        problems.error(message)
        assertTrue(problems.toExtendedReport().contains(Message))
    }

    @Test
    internal fun `have no messages by default`() {
        assertFalse(problems.hasErrors())
    }

    @Test
    internal fun `severe throws`() {
        val message = "Severe error"
        assertThrows<MessageProblems.MessageException> { problems.severe(message) }
        assertTrue(problems.hasErrors())
        assertTrue(problems.toString().contains(message))
    }

    @Test
    internal fun `errors`() {
        val message = "Error"
        problems.error(message)
        assertTrue(problems.hasErrors())
        assertTrue(problems.toString().contains(message))
    }
}
