package com.github.navikt.tbd_libs.rapids_and_rivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageValidationTest {
    @Test
    fun `key can exist`() {
        val validation = validate {
            "@event_name" can exist
        }

        validation.test("""{ "event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }

        validation.test("""{ "@event_name": null }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }

        validation.test("""{ "@event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }
    }

    @Test
    fun `key should exist`() {
        val validation = validate {
            "@event_name" should exist
        }

        validation.test("""{ "event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet finnes ikke", problems.toString())
        }

        validation.test("""{ "@event_name": null }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet finnes ikke", problems.toString())
        }

        validation.test("""{ "@event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }
    }


    @Test
    fun `key must exist`() {
        val validation = validate {
            "@event_name" must exist
        }

        validation.test("""{ "event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet finnes ikke", problems.toString())
        }

        validation.test("""{ "@event_name": null }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet finnes ikke", problems.toString())
        }

        validation.test("""{ "@event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }
    }

    @Test
    fun `key should be`() {
        val validation = validate {
            "@event_name" should be("mitt_eventnavn")
        }

        validation.test("""{ "event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet har ikke forventet verdi mitt_eventnavn", problems.toString())
        }

        validation.test("""{ "@event_name": "uventet_verdi" }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet har ikke forventet verdi mitt_eventnavn", problems.toString())
        }

        validation.test("""{ "@event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }
    }

    @Test
    fun `key must be`() {
        val validation = validate {
            "@event_name" must be("mitt_eventnavn")
        }

        validation.test("""{ "event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet har ikke forventet verdi mitt_eventnavn", problems.toString())
        }

        validation.test("""{ "@event_name": "uventet_verdi" }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet har ikke forventet verdi mitt_eventnavn", problems.toString())
        }

        validation.test("""{ "@event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }
    }



    @Test
    fun `key can be`() {
        val validation = validate {
            "@event_name" can be("mitt_eventnavn")
        }

        validation.test("""{ "event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }

        validation.test("""{ "@event_name": "uventet_verdi" }""") { result, problems ->
            assertEquals(emptySet<String>(), result)
            assertTrue(problems.hasErrors())
            assertContains("@event_name: Feltet har ikke forventet verdi mitt_eventnavn", problems.toString())
        }

        validation.test("""{ "@event_name": "mitt_eventnavn" }""") { result, problems ->
            assertEquals(setOf("@event_name"), result)
            assertFalse(problems.hasErrors())
        }
    }

    private fun MessageValidation.test(@Language("JSON") testMessage: String, assertBlock: (Set<String>, MessageProblems) -> Unit) {
        val problems = MessageProblems(testMessage)
        val node = jacksonObjectMapper().readTree(testMessage)
        val result = try {
            validatedKeys(node, problems)
        } catch (err: MessageProblems.MessageException) {
            emptySet()
        }
        assertBlock(result, problems)
    }

    private fun assertContains(expected: String, actual: String) {
        assertTrue(expected in actual) { "<$actual> does not contain <$expected>" }
    }
}