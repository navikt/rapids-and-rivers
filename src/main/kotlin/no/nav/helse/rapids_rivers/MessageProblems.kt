package no.nav.helse.rapids_rivers

class MessageProblems(private val originalMessage: String) {
    private val errors = mutableListOf<String>()
    private val severe = mutableListOf<String>()

    fun error(melding: String, vararg params: Any) {
        errors.add(String.format(melding, *params))
    }

    internal fun error(melding: String, other: MessageProblems) {
        other.errors.forEach { errors.add("$melding $it") }
    }

    fun severe(melding: String, vararg params: Any): Nothing {
        severe.add(String.format(melding, *params))
        throw MessageException(this)
    }

    fun hasErrors() = severe.isNotEmpty() || errors.isNotEmpty()

    fun toExtendedReport(): String {
        if (!hasErrors()) return "No errors in message\n"
        val results = StringBuffer()
        results.append("Message has errors:\n\t")
        append("Severe errors", severe, results)
        append("Errors", errors, results)
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
