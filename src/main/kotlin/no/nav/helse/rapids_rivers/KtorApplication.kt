package no.nav.helse.rapids_rivers

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.*
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

data class NaisEndpoints(
    val isaliveEndpoint: String,
    val isreadyEndpoint: String,
    val metricsEndpoint: String,
    val preStopEndpoint: String
) {
    companion object {
        val Default = NaisEndpoints(
            isaliveEndpoint = "/isalive",
            isreadyEndpoint = "/isready",
            metricsEndpoint = "/metrics",
            preStopEndpoint = "/stop"
        )
    }
}

fun ktorApplication(
    naisEndpoints: NaisEndpoints,
    meterRegistry: PrometheusMeterRegistry,
    preStopHook: suspend () -> Unit,
    aliveCheck: () -> Boolean,
    readyCheck: () -> Boolean,
    cioConfiguration: CIOApplicationEngine.Configuration.() -> Unit,
    port: Int,
    modules: List<Application.() -> Unit>,
    applicationLogger: Logger = LoggerFactory.getLogger("no.nav.helse.rapids_rivers.ktorApplication"),
    developmentMode: Boolean = false,
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    val config = serverConfig(
        environment = applicationEnvironment {
            log = applicationLogger
        }
    ) {
        this.developmentMode = developmentMode
        module {
            monitor.subscribe(ApplicationStarting) { it.log.info("Application starting …") }
            monitor.subscribe(ApplicationStarted) { it.log.info("Application started …") }
            monitor.subscribe(ServerReady) { it.log.info("Application ready …") }
            monitor.subscribe(ApplicationStopPreparing) { it.log.info("Application preparing to stop …") }
            monitor.subscribe(ApplicationStopping) { it.log.info("Application stopping …") }
            monitor.subscribe(ApplicationStopped) { it.log.info("Application stopped …") }

            applicationModule(meterRegistry)
            modules.forEach { it() }
            naisRoutings(naisEndpoints, meterRegistry, preStopHook, aliveCheck, readyCheck)
        }
    }
    val defaultConfig = CIOApplicationEngine.Configuration().apply {
        connector {
            this.port = port
        }
    }
    val cioConfig = defaultConfig.apply(cioConfiguration)
    return EmbeddedServer(config, CIO) {
        takeFrom(cioConfig)
    }
}

private fun Application.applicationModule(meterRegistry: MeterRegistry) {
    install(MicrometerMetrics) {
        registry = meterRegistry
        val defaultBinders = listOf(
            ClassLoaderMetrics(),
            JvmInfoMetrics(),
            JvmMemoryMetrics(),
            JvmThreadMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics()
        )
        meterBinders = defaultBinders + buildList {
            try {
                Class.forName("ch.qos.logback.classic.LoggerContext")
                add(LogbackMetrics())
            } catch (_: ClassNotFoundException) {}
        }
    }
}

private fun Application.naisRoutings(
    naisEndpoints: NaisEndpoints,
    meterRegistry: PrometheusMeterRegistry,
    preStopHook: suspend () -> Unit,
    aliveCheck: () -> Boolean,
    readyCheck: () -> Boolean
) {
    val readyToggle = AtomicBoolean(false)
    monitor.subscribe(ApplicationStarted) {
        readyToggle.set(true)
    }
    monitor.subscribe(ApplicationStopPreparing) {
        readyToggle.set(false)
    }

    routing {
        /*
            https://doc.nais.io/workloads/explanations/good-practices/?h=graceful#handles-termination-gracefully

            termination lifecycle:
                1. Application (pod) gets status TERMINATING, and grace period starts (default 30s)
                    (simultaneous with 1) If the pod has a preStop hook defined, this is invoked
                    (simultaneous with 1) The pod is removed from the list of endpoints i.e. taken out of load balancing
                    (simultaneous with 1, but after preStop if defined) Container receives SIGTERM, and should prepare for shutdown
                2. Grace period ends, and container receives SIGKILL
                3. Pod disappears from the API, and is no longer visible for the client.
         */
        get(naisEndpoints.preStopEndpoint) {
            application.log.info("Received shutdown signal via preStopHookPath, calling actual stop hook")
            application.monitor.raise(ApplicationStopPreparing, environment)

            /**
             *  fra doccen:
             *  Be aware that even after your preStop-hook has been triggered,
             *  your application might still receive new connections for a few seconds.
             *  This is because step 3 above can take a few seconds to complete.
             *  Your application should handle those connections before exiting.
             */
            preStopHook()

            application.log.info("Stop hook returned. Responding to preStopHook request with 200 OK")
            call.respond(HttpStatusCode.OK)
        }
        get(naisEndpoints.isaliveEndpoint) {
            if (!aliveCheck()) return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.respondText("ALIVE", ContentType.Text.Plain)
        }
        get(naisEndpoints.isreadyEndpoint) {
            if (!readyToggle.get() || !readyCheck()) return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.respondText("READY", ContentType.Text.Plain)
        }

        get(naisEndpoints.metricsEndpoint) {
            call.respond(meterRegistry.scrape())
        }
    }
}