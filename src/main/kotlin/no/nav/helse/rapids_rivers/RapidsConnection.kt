package no.nav.helse.rapids_rivers

interface MessageContext {
    fun publish(message: String)
    fun publish(key: String, message: String)
}

abstract class RapidsConnection : MessageContext {

    protected val statusListeners = mutableListOf<StatusListener>()
    protected val listeners = mutableListOf<MessageListener>()

    fun register(listener: StatusListener) {
        statusListeners.add(listener)
    }

    fun register(listener: MessageListener) {
        listeners.add(listener)
    }

    abstract fun start()
    abstract fun stop()

    interface StatusListener {
        fun onStartup(rapidsConnection: RapidsConnection) {}
        fun onReady(rapidsConnection: RapidsConnection) {}
        fun onNotReady(rapidsConnection: RapidsConnection) {}
        fun onShutdown(rapidsConnection: RapidsConnection) {}
    }

    fun interface MessageListener {
        fun onMessage(message: String, context: MessageContext)
    }
}
