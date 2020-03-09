package no.nav.helse.rapids_rivers

import java.time.LocalDateTime

class PingPong(rapidsConnection: RapidsConnection, private val appName: String, private val instanceId: String) :
    River.PacketListener {
    init {
        River(rapidsConnection).apply {
            validate { it.requireValue("@event_name", "ping") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        packet["@event_name"] = "pong"
        packet["pong_time"] = LocalDateTime.now()
        packet["app_name"] = appName
        packet["instance_id"] = instanceId
        context.send(packet.toJson())
    }
}
