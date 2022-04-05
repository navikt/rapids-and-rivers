package no.nav.helse.rapids_rivers

import org.slf4j.LoggerFactory

interface MessageContext {
    fun publish(message: String)
    fun publish(key: String, message: String)
    fun rapidName(): String
}

abstract class RapidsConnection : MessageContext {
    private companion object {
        private val log = LoggerFactory.getLogger(RapidsConnection::class.java)
        private val sikkerLogg = LoggerFactory.getLogger("tjenestekall")
    }

    private val statusListeners = mutableListOf<StatusListener>()
    private val listeners = mutableListOf<MessageListener>()
    private val replayMessages = mutableListOf<Pair<String, MessageContext>>()

    fun register(listener: StatusListener) {
        statusListeners.add(listener)
    }

    fun register(listener: MessageListener) {
        listeners.add(listener)
    }

    fun queueReplayMessage(key: String, message: String) {
        val context = KeyMessageContext(this, key)
        replayMessages.add(message to context)
    }

    private fun replayMessage(replayMessage: Pair<String, MessageContext>) {
        sikkerLogg.info("replayer melding:\n\t${replayMessage.first}")
        notifyMessage(replayMessage.first, replayMessage.second)
    }

    protected fun notifyMessage(message: String, context: MessageContext) {
        listeners.forEach { it.onMessage(message, context) }
        replayMessages()
    }

    private fun replayMessages() {
        if (replayMessages.isEmpty()) return
        log.info("det er ${replayMessages.size} meldinger køet for replay")
        sikkerLogg.info("det er ${replayMessages.size} meldinger køet for replay")
        replayMessage(replayMessages.removeAt(0))
    }

    protected fun notifyStartup() {
        statusListeners.forEach { it.onStartup(this) }
    }
    protected fun notifyReady() {
        statusListeners.forEach { it.onReady(this) }
    }
    protected fun notifyNotReady() {
        statusListeners.forEach { it.onNotReady(this) }
    }
    protected fun notifyShutdownSignal() {
        statusListeners.forEach {
            try {
                it.onShutdownSignal(this)
            } catch (err: Exception) {
                log.error("A shutdown signal callback threw an exception: ${err.message}", err)
            }
        }
    }

    protected fun notifyShutdown() {
        statusListeners.forEach {
            try {
                it.onShutdown(this)
            } catch (err: Exception) {
                log.error("A shutdown callback threw an exception: ${err.message}", err)
            }
        }
    }

    abstract fun start()
    abstract fun stop()

    interface StatusListener {
        fun onStartup(rapidsConnection: RapidsConnection) {}
        fun onReady(rapidsConnection: RapidsConnection) {}
        fun onNotReady(rapidsConnection: RapidsConnection) {}
        fun onShutdownSignal(rapidsConnection: RapidsConnection) {}
        fun onShutdown(rapidsConnection: RapidsConnection) {}
    }

    fun interface MessageListener {
        fun onMessage(message: String, context: MessageContext)
    }
}
