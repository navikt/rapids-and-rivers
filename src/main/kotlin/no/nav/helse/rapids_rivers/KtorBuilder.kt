package no.nav.helse.rapids_rivers

import io.ktor.application.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.response.*
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import kotlinx.coroutines.delay
import org.slf4j.Logger

class KtorBuilder {

    private val builder = ApplicationEngineEnvironmentBuilder()
    private var collectorRegistry = CollectorRegistry.defaultRegistry
    private val extraMeterBinders = mutableListOf<MeterBinder>()

    fun port(port: Int) = apply {
        builder.connector {
            this.port = port
        }
    }

    fun log(logger: Logger) = apply {
        builder.log = logger
    }

    fun module(module: Application.() -> Unit) = apply {
        builder.module(module)
    }

    fun build(): ApplicationEngine = embeddedServer(Netty, applicationEngineEnvironment {
        module {
            install(MicrometerMetrics) {
                registry = PrometheusMeterRegistry(
                    PrometheusConfig.DEFAULT,
                    collectorRegistry,
                    Clock.SYSTEM
                )
                meterBinders = listOf(
                    ClassLoaderMetrics(),
                    JvmMemoryMetrics(),
                    JvmGcMetrics(),
                    ProcessorMetrics(),
                    JvmThreadMetrics(),
                    LogbackMetrics()
                ) + extraMeterBinders
            }

            routing {
                get("/metrics") {
                    val names = call.request.queryParameters.getAll("name[]")?.toSet() ?: emptySet()

                    call.respondTextWriter(ContentType.parse(TextFormat.CONTENT_TYPE_004)) {
                        TextFormat.write004(this, collectorRegistry.filteredMetricFamilySamples(names))
                    }
                }
            }
        }

        this.connectors.addAll(builder.connectors)
        this.log = builder.log
        this.modules.addAll(builder.modules)
    })

    fun preStopHook(preStopHook: suspend () -> Unit) = apply {
        builder.module {
            routing {
                get("/stop") {
                    log.info("Received shutdown signal via preStopHookPath, calling actual stop hook")
                    preStopHook()
                    log.info("Stop hook returned, responding to preStopHook request with 200 OK")
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }

    fun liveness(isAliveCheck: () -> Boolean) = apply {
        builder.module {
            routing {
                get("/isalive") {
                    if (!isAliveCheck()) return@get call.respondText("NOT ALIVE", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                    call.respondText("ALIVE", ContentType.Text.Plain)
                }
            }
        }
    }

    fun readiness(isReadyCheck: () -> Boolean) = apply {
        builder.module {
            routing {
                get("/isready") {
                    if (!isReadyCheck()) return@get call.respondText("NOT READY", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                    call.respondText("READY", ContentType.Text.Plain)
                }
            }
        }
    }

    fun withCollectorRegistry(registry: CollectorRegistry) = apply {
        this.collectorRegistry = registry
    }

    fun metrics(metrics: List<MeterBinder>) = apply {
        extraMeterBinders.addAll(metrics)
    }
}
