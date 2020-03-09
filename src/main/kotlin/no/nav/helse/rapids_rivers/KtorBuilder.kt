package no.nav.helse.rapids_rivers

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.response.respondText
import io.ktor.response.respondTextWriter
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineEnvironmentBuilder
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.kafka.KafkaConsumerMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import org.slf4j.Logger

class KtorBuilder {

    private val builder = ApplicationEngineEnvironmentBuilder()

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

    @KtorExperimentalAPI
    fun build(): ApplicationEngine = embeddedServer(Netty, builder.build {  })

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

    fun metrics(collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry) = apply {
        builder.module {
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
                    LogbackMetrics(),
                    KafkaConsumerMetrics()
                )
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
    }
}
