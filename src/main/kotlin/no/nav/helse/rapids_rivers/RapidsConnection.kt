package no.nav.helse.rapids_rivers

abstract class RapidsConnection {

    protected val statusListeners = mutableListOf<StatusListener>()
    protected val listeners = mutableListOf<MessageListener>()

    fun register(listener: StatusListener) {
        statusListeners.add(listener)
    }

    fun register(listener: MessageListener) {
        listeners.add(listener)
    }

    abstract fun publish(message: String)
    abstract fun publish(key: String, message: String)

    abstract fun start()
    abstract fun stop()

    interface MessageContext {
        fun send(message: String)
        fun send(key: String, message: String)
        fun getKey(): String? = null
    }

    interface StatusListener {
        fun onStartup(rapidsConnection: RapidsConnection) {}
        fun onReady(rapidsConnection: RapidsConnection) {}
        fun onNotReady(rapidsConnection: RapidsConnection) {}
        fun onShutdown(rapidsConnection: RapidsConnection) {}
    }

    interface MessageListener {
        fun onMessage(message: String, context: MessageContext)
    }
}
