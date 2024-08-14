package no.nav.helse.rapids_rivers

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Metrics.addRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import org.slf4j.LoggerFactory

private const val defaultIsAliveEndpoint = "/isalive"
private const val defaultIsReadyEndpoint = "/isready"
private const val defaultMetricsEndpoint = "/metrics"
private const val defaultPreStopHookEndpoint = "/stop"

fun defaultNaisApplication(
    port: Int = 8080,
    extraMetrics: List<MeterBinder> = emptyList(),
    collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry,
    metricsEndpoint: String = defaultMetricsEndpoint,
    isAliveEndpoint: String = defaultIsAliveEndpoint,
    isReadyEndpoint: String = defaultIsReadyEndpoint,
    preStopHookEndpoint: String = defaultPreStopHookEndpoint,
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
    module(metricsEndpoint(metricsEndpoint, extraMetrics, collectorRegistry))
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
private fun metricsEndpoint(endpoint: String, metrics: List<MeterBinder>, collectorRegistry: CollectorRegistry) = fun Application.() {
    install(MicrometerMetrics) {
        registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, collectorRegistry, Clock.SYSTEM)
        meterBinders = meterBinders + metrics
        addRegistry(registry)
    }
    routing {
        get(endpoint) {
            val names = call.request.queryParameters.getAll("name[]")?.toSet() ?: emptySet()
            call.respondTextWriter(ContentType.parse(TextFormat.CONTENT_TYPE_004)) {
                TextFormat.write004(this, collectorRegistry.filteredMetricFamilySamples(names))
            }
        }
    }
}