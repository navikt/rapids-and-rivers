package no.nav.helse.rapids_rivers

typealias Validation = (JsonMessage) -> Unit

class River(rapidsConnection: RapidsConnection) : RapidsConnection.MessageListener {

    private val validations = mutableListOf<Validation>()
    private val listeners = mutableListOf<PacketListener>()

    init {
        rapidsConnection.register(this)
    }

    fun validate(validation: Validation) {
        validations.add(validation)
    }

    fun register(listener: PacketListener) {
        listeners.add(listener)
    }

    override fun onMessage(message: String, context: RapidsConnection.MessageContext) {
        val problems = MessageProblems(message)
        try {
            val packet = JsonMessage(message, problems)
            validations.forEach { it(packet) }
            if (problems.hasErrors()) return onError(problems, context)
            onPacket(packet, context)
        } catch (err: MessageProblems.MessageException) {
            return onError(problems, context)
        }
    }

    private fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        listeners.forEach { it.onPacket(packet, context) }
    }

    private fun onError(problems: MessageProblems, context: RapidsConnection.MessageContext) {
        listeners.forEach { it.onError(problems, context) }
    }

    interface PacketListener {
        fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext)
        fun onError(problems: MessageProblems, context: RapidsConnection.MessageContext) {}
    }
}
