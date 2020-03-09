package no.nav.helse.rapids_rivers

class HeartbeatRiver(rapidsConnection: RapidsConnection,
                     private val serviceId: String) : River.PacketListener {
    init {
        River(rapidsConnection).apply {
            validate { it.requireValue("@event_name", "heartbeat") }
            validate { it.forbid("service_id") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        packet["service_id"] = serviceId
        context.send(packet.toJson())
    }

    override fun onError(problems: MessageProblems, context: RapidsConnection.MessageContext) {}
}
