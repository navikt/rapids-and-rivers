package no.nav.helse.rapids_rivers

import com.github.navikt.tbd_libs.rapids_and_rivers.KafkaRapid
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PreStopHook(private val rapid: KafkaRapid) : RapidsConnection.StatusListener {
    private val shutdownLatch = CountDownLatch(1)

    init {
        rapid.register(this)
    }

    override fun onShutdown(rapidsConnection: RapidsConnection) {
        shutdownLatch.countDown()
    }

    suspend fun handlePreStopRequest() {
        rapid.stop()
        // block the preStopHook call from returning until
        // ktor is ready to shut down, which means that the KafkaRapid has shutdown
        withContext(Dispatchers.IO) {
            shutdownLatch.await(30, TimeUnit.SECONDS)
        }
    }
}