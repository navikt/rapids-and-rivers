package no.nav.helse.rapids_rivers

internal class KeyMessageContext(
    private val rapidsConnection: MessageContext,
    private val key: String?
) : MessageContext {
    override fun publish(message: String, waitForFlush: Boolean) {
        if (key == null) return rapidsConnection.publish(message, waitForFlush)
        publish(key, message, waitForFlush)
    }

    override fun publish(key: String, message: String, waitForFlush: Boolean) {
        rapidsConnection.publish(key, message, waitForFlush)
    }

    override fun rapidName(): String {
        return rapidsConnection.rapidName()
    }
}