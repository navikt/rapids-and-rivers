package no.nav.helse.rapids_rivers

import org.slf4j.LoggerFactory

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

    class Replayable(private val rapidsConnection: RapidsConnection) : RapidsConnection(), MessageListener {
        private companion object {
            private val log = LoggerFactory.getLogger(Replayable::class.java)
            private val sikkerLogg = LoggerFactory.getLogger("tjenestekall")
        }

        private val replayMessages = mutableListOf<Pair<String, MessageContext>>()

        init {
            rapidsConnection.register(this)
        }

        override fun onMessage(message: String, context: MessageContext) {
            listeners.forEach { it.onMessage(message, context) }
            if (replayMessages.isEmpty()) return
            log.info("det er ${replayMessages.size} meldinger køet for replay")
            sikkerLogg.info("det er ${replayMessages.size} meldinger køet for replay")
            replayMessage(replayMessages.removeAt(0))
        }

        fun queueReplayMessage(key: String, message: String) {
            val context = KeyMessageContext(this, key)
            replayMessages.add(message to context)
        }

        private fun replayMessage(replayMessage: Pair<String, MessageContext>) {
            sikkerLogg.info("replayer melding:\n\t${replayMessage.first}")
            onMessage(replayMessage.first, replayMessage.second)
        }

        override fun publish(message: String) {
            rapidsConnection.publish(message)
        }

        override fun publish(key: String, message: String) {
            rapidsConnection.publish(key, message)
        }

        override fun start() {
            rapidsConnection.start()
        }

        override fun stop() {
            rapidsConnection.stop()
        }
    }
}
