package no.nav.helse.rapids_rivers

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

    override fun onMessage(message: String, context: MessageContext) {
        val problems = MessageProblems(message)
        try {
            val packet = JsonMessage(message, problems, randomIdGenerator)
            validations.forEach { it.validate(packet) }
            if (problems.hasErrors()) {
                return onError(problems, context)
            }
            onPacket(packet, JsonMessageContext(context, packet))
        } catch (err: MessageProblems.MessageException) {
            return onSevere(err, context)
        }
    }

    private fun onPacket(packet: JsonMessage, context: MessageContext) {
        packet.interestedIn("@event_name")
        listeners.forEach {
            val eventName = packet["@event_name"].textValue() ?: "ukjent"
            Metrics.onPacketHistorgram.labels(
                context.rapidName(),
                it.name(),
                eventName
            ).time {
                it.onPacket(packet, context)
            }
            Metrics.onMessageCounter.labels(context.rapidName(), it.name(), "ok").inc()
        }
    }

    private fun onSevere(error: MessageProblems.MessageException, context: MessageContext) {
        listeners.forEach {
            Metrics.onMessageCounter.labels(context.rapidName(), it.name(), "severe").inc()
            it.onSevere(error, context)
        }
    }

    private fun onError(problems: MessageProblems, context: MessageContext) {
        listeners.forEach {
            Metrics.onMessageCounter.labels(context.rapidName(), it.name(), "error").inc()
            it.onError(problems, context)
        }
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
