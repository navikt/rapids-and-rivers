package no.nav.helse.rapids_rivers

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import java.time.Duration
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

    private var lastPing: LocalDateTime? = null

    override fun onPacket(packet: JsonMessage, context: MessageContext) {
        val pingTime = packet["ping_time"].asLocalDateTime()
        val now = LocalDateTime.now()
        if (pingTime < now.minusHours(1) || (lastPing != null && Duration.between(lastPing, pingTime) <= Duration.ofSeconds(5))) return // ignoring old pings or very frequent ones
        lastPing = pingTime
        log.debug("responding to ping as app_name=$appName instance_id=$instanceId")

        packet["@event_name"] = "pong"
        packet["pong_time"] = LocalDateTime.now()
        packet["app_name"] = appName
        packet["instance_id"] = instanceId
        context.publish(packet.toJson())
    }
}
