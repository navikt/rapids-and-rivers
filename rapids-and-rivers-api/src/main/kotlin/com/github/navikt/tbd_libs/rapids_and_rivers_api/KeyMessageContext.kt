package com.github.navikt.tbd_libs.rapids_and_rivers_api

class KeyMessageContext(
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

    override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> {
        return rapidsConnection.publish(
            messages.map {
                it.copy(key = it.key ?: key)
            }
        )
    }

    override fun rapidName(): String {
        return rapidsConnection.rapidName()
    }
}
