package no.nav.helse.rapids_rivers

internal class KeyMessageContext(
    private val rapidsConnection: MessageContext,
    private val key: String?
) : MessageContext {
    override fun publish(message: String) {
        if (key == null) return rapidsConnection.publish(message)
        publish(key, message)
    }

    override fun publish(key: String, message: String) {
        rapidsConnection.publish(key, message)
    }

    override fun rapidName(): String {
        return rapidsConnection.rapidName()
    }
}