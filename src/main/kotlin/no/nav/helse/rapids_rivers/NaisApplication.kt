package no.nav.helse.rapids_rivers

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respond
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
) = embeddedServer(
    factory = CIO,
    configure = {
        apply(cioConfiguration)
        connectors.add(EngineConnectorBuilder().apply {
            this.port = port
        })
        LoggerFactory.getLogger(this::class.java).info("CIO-configuration: parallelism=$parallelism,connectionGroupSize=$connectionGroupSize,workerGroupSize=$workerGroupSize,callGroupSize=$callGroupSize")
    },
    rootConfig = serverConfig(environment = applicationEnvironment {
        log = LoggerFactory.getLogger(this::class.java)

    }) {
        module(metricsEndpoint(metricsEndpoint, collectorRegistry))
        module(healthEndpoint(isAliveEndpoint, isAliveCheck))
        module(healthEndpoint(isReadyEndpoint, isReadyCheck))
        module(preStookHookEndpoint(preStopHookEndpoint, preStopHook))
        extraModules.forEach { module(it) }
    },
)


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