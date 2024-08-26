package no.nav.helse.rapids_rivers

import no.nav.helse.rapids_rivers.MessageProblemType.DEMANDED_IS_NOT_STRING
import org.junit.jupiter.api.Assertions.assertEquals
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
        problems.error(message, MessageProblemType.DEMANDED_DOES_NOT_CONTAIN)
        assertFalse(problems.toString().contains(Message))
    }

    @Test
    internal fun `contains original message in extended report`() {
        assertFalse(problems.toExtendedReport().contains(Message))
        val message = "a message"
        problems.error(message, MessageProblemType.REQUIRED_KEY_IS_NULL)
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
        problems.error(message, MessageProblemType.MISSING_REQUIRED_KEY)
        assertTrue(problems.hasErrors())
        assertTrue(problems.toString().contains(message))
    }

    @Test
    internal fun `types error`() {
        problems.typedError("Message1", DEMANDED_IS_NOT_STRING, "@event_name")
        assertTrue(problems.hasErrors())
    }

    @Test
    internal fun `types severe`() {
        assertThrows<MessageProblems.MessageException> {
            problems.typedSevere(
                "Message2",
                DEMANDED_IS_NOT_STRING,
                "@event_name"
            )
        }
        assertTrue(problems.hasErrors())
    }

    @Test
    internal fun `all errors return everything`() {
        problems.typedError("Message1", DEMANDED_IS_NOT_STRING, "@event_name")
        assertThrows<MessageProblems.MessageException> {
            problems.typedSevere(
                "Message2",
                DEMANDED_IS_NOT_STRING,
                "@event_name"
            )
        }
        assertEquals(listOf(MessageProblem("Message1", DEMANDED_IS_NOT_STRING, "@event_name")), problems.listErrors())
        assertEquals(listOf(MessageProblem("Message2", DEMANDED_IS_NOT_STRING, "@event_name")), problems.listSevere())
    }
}
