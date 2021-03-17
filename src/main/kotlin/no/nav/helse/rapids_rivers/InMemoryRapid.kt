package no.nav.helse.rapids_rivers

import io.ktor.server.engine.ApplicationEngine

@Deprecated("Bruk heller TestRapid: den trenger ingen ktor")
fun inMemoryRapid(config: InMemoryRapidConfig.() -> Unit) = InMemoryRapidConfig().apply(config).build()

@Deprecated("Bruk heller TestRapid: den trenger ingen ktor")
class InMemoryRapid(private val ktor: ApplicationEngine) : RapidsConnection() {
    private val messagesSendt = mutableListOf<RapidMessage>()
    val outgoingMessages get() = messagesSendt.toList()

    override fun publish(message: String) {
        messagesSendt.add(RapidMessage(null, message))
    }

    override fun publish(key: String, message: String) {
        messagesSendt.add(RapidMessage(key, message))
    }

    override fun start() {
        ktor.start(wait = false)
    }

    override fun stop() {
        ktor.stop(5000, 5000)
    }

    fun sendToListeners(message: String) {
        val context = object: MessageContext {
            override fun publish(message: String) {
                this@InMemoryRapid.publish(message)
            }

            override fun publish(key: String, message: String) {
                this@InMemoryRapid.publish(key, message)
            }
        }

        notifyMessage(message, context)
    }

    data class RapidMessage(val key: String?, val value: String)
}

class InMemoryRapidConfig internal constructor() {
    private val ktor = KtorBuilder()

    fun ktor(config: KtorBuilder.() -> Unit) {
        ktor.config()
    }

    internal fun build() = InMemoryRapid(ktor.build())
}
