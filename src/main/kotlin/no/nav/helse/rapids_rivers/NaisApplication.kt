package no.nav.helse.rapids_rivers

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Metrics.addRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory

fun defaultNaisApplication(
    port: Int = 8080,
    collectorRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
    metricsEndpoint: String,
    isAliveEndpoint: String,
    isReadyEndpoint: String,
    preStopHookEndpoint: String,
    isAliveCheck: () -> Boolean = { true },
    isReadyCheck: () -> Boolean = { true },
    preStopHook: suspend () -> Unit = { },
    extraModules: List<Application.() -> Unit> = emptyList(),
    cioConfiguration: CIOApplicationEngine.Configuration.() -> Unit = { },
) = embeddedServer(CIO, applicationEngineEnvironment {
    log = LoggerFactory.getLogger(this::class.java)
    connectors.add(EngineConnectorBuilder().apply {
        this.port = port
    })
    module(metricsEndpoint(metricsEndpoint, collectorRegistry))
    module(healthEndpoint(isAliveEndpoint, isAliveCheck))
    module(healthEndpoint(isReadyEndpoint, isReadyCheck))
    module(preStookHookEndpoint(preStopHookEndpoint, preStopHook))
    modules.addAll(extraModules)
}) {
    apply(cioConfiguration)
    LoggerFactory.getLogger(this::class.java).info("CIO-configuration: parallelism=$parallelism,connectionGroupSize=$connectionGroupSize,workerGroupSize=$workerGroupSize,callGroupSize=$callGroupSize")
}
private fun healthEndpoint(endpoint: String, check: () -> Boolean) = fun Application.() {
    routing {
        get(endpoint) {
            if (!check()) return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(HttpStatusCode.OK)
        }
    }
}
private fun preStookHookEndpoint(endpoint: String, hook: suspend () -> Unit) = fun Application.() {
    routing {
        get(endpoint) {
            application.log.info("Received shutdown signal via preStopHookPath, calling actual stop hook")
            hook()
            application.log.info("Stop hook returned, responding to preStopHook request with 200 OK")
            call.respond(HttpStatusCode.OK)
        }
    }
}
private fun metricsEndpoint(endpoint: String, meterRegistry: PrometheusMeterRegistry) = fun Application.() {
    install(MicrometerMetrics) {
        registry = meterRegistry
        addRegistry(registry)
    }
    routing {
        get(endpoint) {
            call.respond(meterRegistry.scrape())
        }
    }
}