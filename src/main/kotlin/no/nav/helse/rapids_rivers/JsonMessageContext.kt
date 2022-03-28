package no.nav.helse.rapids_rivers

internal class JsonMessageContext(
    private val rapidsConnection: MessageContext,
    private val packet: JsonMessage
) : MessageContext {
    override fun publish(message: String) {
        rapidsConnection.publish(populateStandardFields(message))
    }

    override fun publish(key: String, message: String) {
        rapidsConnection.publish(key, populateStandardFields(message))
    }

    private fun populateStandardFields(message: String) =
        JsonMessage.populateStandardFields(packet, message)

    override fun rapidName(): String {
        return rapidsConnection.rapidName()
    }
}