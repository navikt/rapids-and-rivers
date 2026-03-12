package com.github.navikt.tbd_libs.rapids_and_rivers

import com.fasterxml.jackson.databind.JsonNode
import com.github.navikt.tbd_libs.rapids_and_rivers.ValidationResult.Invalid
import com.github.navikt.tbd_libs.rapids_and_rivers.ValidationResult.Valid
import com.github.navikt.tbd_libs.rapids_and_rivers.ValidationSpec.Companion.allAreOK
import com.github.navikt.tbd_libs.rapids_and_rivers.ValueValidation.Companion.optional
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems

val exist = ValidationResult.create("Feltet finnes ikke") { node ->
    !node.isMissingOrNull()
}
fun be(expectedValue: String) = ValidationResult.create("Feltet har ikke forventet verdi $expectedValue") { node ->
    node.asText() == expectedValue
}


fun validate(validationSpec: MessageValidation.() -> Unit): MessageValidation {
    val spec = MessageValidation()
    spec.validationSpec()
    return spec
}

/**
 * validation = MessageValidation {
 *      "key" should exist // samme som requireKey("key")
 *      "key" must exist" // samme som demandKey("key")
 *      "key" must be("value")
 * }
 */
fun interface ValueValidation {
    fun validate(node: JsonNode): ValidationResult

    companion object {
        fun ValueValidation.optional() = ValueValidation { node ->
            if (node.isMissingOrNull()) Valid else validate(node)
        }
    }
}

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val message: String) : ValidationResult()
    companion object {
        fun create(message: String, validation: (JsonNode) -> Boolean) = ValueValidation { node ->
            when (validation(node)) {
                true -> Valid
                false -> Invalid(message)
            }

        }
    }
}

class MessageValidation {
    private val fields = mutableMapOf<String, MutableList<ValidationSpec>>()

    fun validatedKeys(node: JsonNode, problems: MessageProblems): Set<String> {
        return fields
            .filter { (key, validations) ->
                val valueToBeEvaluated = node.path(key)
                validations.allAreOK(key, valueToBeEvaluated, problems)
            }
            .keys
    }

    infix fun String.should(what: ValueValidation) =
        addValidation(this, ValidationSpec(what, MessageProblems::error))

    infix fun String.must(what: ValueValidation) =
        addValidation(this, ValidationSpec(what, MessageProblems::severe))

    infix fun String.can(what: ValueValidation) =
        should(what.optional())

    private fun addValidation(key: String, validation: ValidationSpec) = validation.also {
        fields.getOrPut(key) { mutableListOf() }.add(it)
    }
}

class ValidationSpec(private val validation: ValueValidation, private val errorStrategy: MessageProblems.(String) -> Unit) {
    companion object {
        fun List<ValidationSpec>.allAreOK(key: String, valueToBeEvaluated: JsonNode, problems: MessageProblems): Boolean {
            return all { spec -> spec.validate(key, valueToBeEvaluated, problems) is Valid }
        }
    }
    fun validate(key: String, node: JsonNode, problems: MessageProblems): ValidationResult {
        val result = validation.validate(node)
        when (result) {
            is Valid -> { /* 😌 */ }
            is Invalid -> errorStrategy(problems, "$key: ${result.message}")
        }
        return result
    }
}