package no.nav.helse.rapids_rivers

import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class PingPong(rapidsConnection: RapidsConnection, private val appName: String, private val instanceId: String) :
    River.PacketListener {

    private val log = LoggerFactory.getLogger(this::class.java)

    init {
        log.info("registering ping river for app_name=$appName instance_id=$instanceId")

        River(rapidsConnection).apply {
            validate { it.requireValue("@event_name", "ping") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        log.info("responding to ping as app_name=$appName instance_id=$instanceId")
        packet["@event_name"] = "pong"
        packet["pong_time"] = LocalDateTime.now()
        packet["app_name"] = appName
        packet["instance_id"] = instanceId
        context.send(packet.toJson())
    }
}
