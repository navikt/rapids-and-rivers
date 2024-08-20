package no.nav.helse.rapids_rivers

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import no.nav.helse.rapids_rivers.River.PacketListener.Companion.Name
import java.util.*

fun interface RandomIdGenerator {
    companion object {
        internal val Default = RandomIdGenerator { UUID.randomUUID().toString() }
    }
    fun generateId(): String
}

class River(rapidsConnection: RapidsConnection, private val randomIdGenerator: RandomIdGenerator = RandomIdGenerator.Default) : RapidsConnection.MessageListener {
    private val validations = mutableListOf<PacketValidation>()

    private val listeners = mutableListOf<PacketListener>()

    init {
        rapidsConnection.register(this)
    }

    fun validate(validation: PacketValidation): River {
        validations.add(validation)
        return this
    }

    fun onSuccess(listener: PacketValidationSuccessListener): River {
        listeners.add(DelegatedPacketListener(listener))
        return this
    }

    fun onError(listener: PacketValidationErrorListener): River {
        listeners.add(DelegatedPacketListener(listener))
        return this
    }

    fun register(listener: PacketListener): River {
        listeners.add(listener)
        return this
    }

    override fun onMessage(message: String, context: MessageContext, metrics: MeterRegistry) {
        val problems = MessageProblems(message)
        try {
            val packet = JsonMessage(message, problems, metrics, randomIdGenerator)
            validations.forEach { it.validate(packet) }
            if (problems.hasErrors()) {
                return onError(metrics, problems, context)
            }
            onPacket(packet, JsonMessageContext(context, packet), metrics)
        } catch (err: MessageProblems.MessageException) {
            return onSevere(metrics, err, context)
        }
    }

    private fun onPacket(packet: JsonMessage, context: MessageContext, metrics: MeterRegistry) {
        packet.interestedIn("@event_name")
        val eventName = packet["@event_name"].textValue() ?: "ukjent"
        listeners.forEach {
            notifyPacketListener(metrics, eventName, it, packet, context)
        }
    }

    private fun notifyPacketListener(metrics: MeterRegistry, eventName: String, packetListener: PacketListener, packet: JsonMessage, context: MessageContext) {
        onMessageCounter(metrics, context.rapidName(), packetListener.name(), "ok", eventName)
        val timer = Timer.start(metrics)
        packetListener.onPacket(packet, context)
        timer.stop(
            Timer.builder("on_packet_seconds")
            .description("Hvor lang det tar å lese en gjenkjent melding i sekunder")
            .tag("rapid", context.rapidName())
            .tag("river", packetListener.name())
            .tag("event_name", eventName)
            .register(metrics)
        )
    }

    private fun onSevere(metrics: MeterRegistry, error: MessageProblems.MessageException, context: MessageContext) {
        listeners.forEach {
            onMessageCounter(metrics, context.rapidName(), it.name(), "severe")
            it.onSevere(error, context)
        }
    }

    private fun onError(metrics: MeterRegistry, problems: MessageProblems, context: MessageContext) {
        listeners.forEach {
            onMessageCounter(metrics, context.rapidName(), it.name(), "error")
            it.onError(problems, context)
        }
    }

    private fun onMessageCounter(metrics: MeterRegistry, rapidName: String, riverName: String, validated: String, eventName: String? = null) {
        Counter.builder("message_counter")
            .description("Hvor mange meldinger som er lest inn")
            .tag("rapid", rapidName)
            .tag("river", riverName)
            .tag("validated", validated)
            .tag("event_name", eventName ?: "")
            .register(metrics)
            .increment()
    }


    fun interface PacketValidation {
        fun validate(message: JsonMessage)
    }

    fun interface PacketValidationSuccessListener {
        fun onPacket(packet: JsonMessage, context: MessageContext)
    }

    fun interface PacketValidationErrorListener {
        fun onError(problems: MessageProblems, context: MessageContext)
    }

    interface PacketListener : PacketValidationErrorListener, PacketValidationSuccessListener {
        companion object {
            fun Name(obj: Any) = obj::class.simpleName ?: "ukjent"
        }
        override fun onError(problems: MessageProblems, context: MessageContext) {}

        fun onSevere(error: MessageProblems.MessageException, context: MessageContext) {}

        fun name(): String = Name(this)
    }

    private class DelegatedPacketListener private constructor(
        private val packetHandler: PacketValidationSuccessListener,
        private val errorHandler: PacketValidationErrorListener
    ) : PacketListener {
        constructor(packetHandler: PacketValidationSuccessListener) : this(packetHandler, { _, _ -> })
        constructor(errorHandler: PacketValidationErrorListener) : this({ _, _ -> }, errorHandler)

        override fun name() = Name(packetHandler)

        override fun onError(problems: MessageProblems, context: MessageContext) {
            errorHandler.onError(problems, context)
        }

        override fun onPacket(packet: JsonMessage, context: MessageContext) {
            packetHandler.onPacket(packet, context)
        }
    }

}
