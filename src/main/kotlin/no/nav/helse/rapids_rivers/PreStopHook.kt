package no.nav.helse.rapids_rivers

import com.github.navikt.tbd_libs.rapids_and_rivers.KafkaRapid
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

class PreStopHook(private val rapid: KafkaRapid) : RapidsConnection.StatusListener {
    private companion object {
        val log = LoggerFactory.getLogger(this::class.java)
    }

    // bruker CONFLATED som er en channel med buffer på 1, hvor hver ny melding overskriver den forrige
    // i praksis vil dette bety at vi ikke blokkerer senderen av shutdown-signalet
    private val shutdownChannel = Channel<Boolean>(CONFLATED)

    init {
        rapid.register(this)
    }

    override fun onShutdownComplete(rapidsConnection: RapidsConnection) {
        runBlocking(Dispatchers.IO) {
            try {
                withTimeout(1.seconds) {
                    log.info("sender shutdownsignal på channel")
                    shutdownChannel.send(true)
                    // a channel can be closed to indicate that no more elements are coming
                    shutdownChannel.close()
                }
            } catch (e: Exception) {
                log.warn("fikk exception da vi sendte shutdown-signal på channel: ${e.message}", e)
            }
        }
    }

    /**
     * sender stop-signal til kafkarapid.
     * da vil kafka-rapid sørge for at konsumer-tråden får beskjed, og starter nedstenging.
     * når nedstengingen er fullført vil vi få et varsel på onShutdown().
     * da varsler vi prestop-hooken om at nedstenging er fullført.
     * prestop-hooken venter i opptil 30 sekunder på å motta dette signalet.
     */
    suspend fun handlePreStopRequest() {
        rapid.stop()
        // block the preStopHook call from returning until
        // ktor is ready to shut down, which means that the KafkaRapid has shutdown
        withContext(Dispatchers.IO) {
            val shutdownValue = withTimeoutOrNull(30.seconds) {
                shutdownChannel.receive()
            }
            if (shutdownValue == null) {
                log.info("fikk ikke shutdown-signal innen timeout")
            } else {
                log.info("mottok shutdownsignal på channel")
            }
        }
    }
}