package no.nav.helse.rapids_rivers

data class MessageProblem(
    val message: String,
    val type: MessageProblemType?,
    val field: String?
)

class MessageProblems(private val originalMessage: String) {
    private val errors = mutableListOf<MessageProblem>()
    private val severe = mutableListOf<MessageProblem>()

    @Deprecated("Use typedError instead", ReplaceWith("typedError"))
    fun error(melding: String, vararg params: Any) = typedError(melding, null, null, params)

    fun typedError(melding: String, type: MessageProblemType? = null, key: String? = null, vararg params: Any) {
        errors.add(MessageProblem(String.format(melding, *params), type, key))
    }

    internal fun error(melding: String, other: MessageProblems) {
        other.errors.forEach { errors.add(MessageProblem("$melding ${it.message}", it.type, it.field)) }
    }

    @Deprecated("Use typedSevere instead", ReplaceWith("typedSevere"))
    fun severe(melding: String, vararg params: Any): Nothing = typedSevere(melding, null, null, params)

    fun typedSevere(melding: String, type: MessageProblemType? = null, key: String? = null, vararg params: Any): Nothing {
        severe.add(MessageProblem(String.format(melding, *params), type, key))
        throw MessageException(this)
    }

    fun hasErrors() = severe.isNotEmpty() || errors.isNotEmpty()

    fun toExtendedReport(): String {
        if (!hasErrors()) return "No errors in message\n"
        val results = StringBuffer()
        results.append("Message has errors:\n\t")
        append("Severe errors", severe.map { it.message }, results)
        append("Errors", errors.map { it.message }, results)
        results.append("\n")
        results.append("Original message: $originalMessage\n")
        return results.toString()
    }

    override fun toString(): String {
        return (severe.map { "S: $it" } + errors.map { "E: $it" })
            .joinToString(separator = "\n")
    }

    private fun append(label: String, messages: List<String>, results: StringBuffer) {
        if (messages.isEmpty()) return
        results.append("\n")
        results.append(label)
        results.append(": ")
        results.append(messages.size)
        for (message in messages) {
            results.append("\n\t")
            results.append(message)
        }
    }

    class MessageException(val problems: MessageProblems) : RuntimeException(problems.toString())
}

enum class MessageProblemType {
    REJECTED_KEY_EXISTS,
    REJECTED_KEY_WITH_VALUE,
    DEMANDED_KEY_IS_NULL,
    MISSING_DEMANDED_KEY,
    DEMANDED_IS_NOT_STRING,
    DEMANDED_IS_NOT_NUMBER,
    DEMANDED_IS_NOT_BOOLEAN,
    DEMANDED_DOES_NOT_CONTAIN,
    DEMANDED_MUST_BE_ONE_OF,
    DEMANDED_ARRAY_DOES_NOT_CONTAIN_ONE_OF,
    DEMANDED_DID_NOT_MATCH_THE_PREDICATE,
    MISSING_REQUIRED_KEY,
    REQUIRED_IS_NOT_NUMBER,
    REQUIRED_MUST_BE_ONE_OF,
    REQUIRED_IS_NOT_AN_ARRAY,
    REQUIRED_ARRAY_DOES_NOT_CONTAIN_ONE_OF,
    REQUIRED_DOES_NOT_CONTAIN,
    REQUIRED_DID_NOT_MATCH_THE_PREDICATE,
    REQUIRED_IS_ONE_OF,
    OPTIONAL_DID_NOT_MATCH_THE_PREDICATE,
    REQUIRED_KEY_IS_NULL,
    FORBIDDEN_KEY_EXISTS
}