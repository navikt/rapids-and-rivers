package no.nav.helse.rapids_rivers

internal class JsonMessageContext(
    private val rapidsConnection: MessageContext,
    private val packet: JsonMessage
) : MessageContext {
    override fun publish(message: String, waitForFlush: Boolean) {
        rapidsConnection.publish(populateStandardFields(message), waitForFlush)
    }

    override fun publish(key: String, message: String, waitForFlush: Boolean) {
        rapidsConnection.publish(key, populateStandardFields(message), waitForFlush)
    }

    private fun populateStandardFields(message: String) =
        JsonMessage.populateStandardFields(packet, message)

    override fun rapidName(): String {
        return rapidsConnection.rapidName()
    }
}