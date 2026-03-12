package com.github.navikt.tbd_libs.rapids_and_rivers_api

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory

interface MessageContext {
    fun publish(message: String)
    fun publish(key: String, message: String)
    fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>>
    fun rapidName(): String
}

data class OutgoingMessage(
    val body: String,
    val key: String? = null
)

data class SentMessage(
    val index: Int,
    val message: OutgoingMessage,
    val partition: Int,
    val offset: Long,
)

data class FailedMessage(
    val index: Int,
    val message: OutgoingMessage,
    val error: Throwable
)

abstract class RapidsConnection : MessageContext {
    private companion object {
        private val log = LoggerFactory.getLogger(RapidsConnection::class.java)
        private val sikkerLogg = LoggerFactory.getLogger("tjenestekall")
    }

    private val statusListeners = mutableListOf<StatusListener>()
    private val listeners = mutableListOf<MessageListener>()

    fun register(listener: StatusListener) {
        statusListeners.add(listener)
    }

    fun register(listener: MessageListener) {
        listeners.add(listener)
    }

    protected fun notifyMessage(
        message: String,
        context: MessageContext,
        metadata: MessageMetadata,
        metrics: MeterRegistry
    ) {
        listeners.forEach { it.onMessage(message, context, metadata, metrics) }
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

    protected fun notifyShutdownComplete() {
        statusListeners.forEach {
            try {
                it.onShutdownComplete(this)
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
        fun onShutdownComplete(rapidsConnection: RapidsConnection) {}
    }

    fun interface MessageListener {
        fun onMessage(message: String, context: MessageContext, metadata: MessageMetadata, metrics: MeterRegistry)
    }
}
