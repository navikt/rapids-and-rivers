package com.github.navikt.tbd_libs.rapids_and_rivers

import com.github.navikt.tbd_libs.rapids_and_rivers_api.FailedMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.SentMessage

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

    override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> {
        return rapidsConnection.publish(
            messages.map {
                it.copy(body = populateStandardFields(it.body))
            }
        )
    }

    private fun populateStandardFields(message: String) =
        JsonMessage.populateStandardFields(packet, message)

    override fun rapidName(): String {
        return rapidsConnection.rapidName()
    }
}
