package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class PingPong(rapidsConnection: RapidsConnection, private val appName: String, private val instanceId: String) :
    River.PacketValidationSuccessListener {

    private val log = LoggerFactory.getLogger(this::class.java)

    init {
        log.info("registering ping river for app_name=$appName instance_id=$instanceId")

        River(rapidsConnection).validate {
            it.requireValue("@event_name", "ping")
            it.require("ping_time", JsonNode::asLocalDateTime)
        }.onSuccess(this)
    }

    override fun onPacket(packet: JsonMessage, context: MessageContext) {
        val pingTime = packet["ping_time"].asLocalDateTime()
        if (pingTime < LocalDateTime.now().minusHours(1)) return
        log.debug("responding to ping as app_name=$appName instance_id=$instanceId")

        packet["@event_name"] = "pong"
        packet["pong_time"] = LocalDateTime.now()
        packet["app_name"] = appName
        packet["instance_id"] = instanceId
        context.publish(packet.toJson())
    }
}
