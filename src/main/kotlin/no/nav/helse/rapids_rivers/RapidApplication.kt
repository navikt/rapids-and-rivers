package no.nav.helse.rapids_rivers

import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.KafkaRapid
import com.github.navikt.tbd_libs.rapids_and_rivers.createDefaultKafkaRapidFromEnv
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.ktor.server.application.Application
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.net.InetAddress
import java.util.UUID
import org.slf4j.LoggerFactory

class RapidApplication internal constructor(
    private val ktor: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val rapid: RapidsConnection,
    private val appName: String? = null,
    private val instanceId: String,
    private val onKtorStartup: () -> Unit = {},
    private val onKtorShutdown: () -> Unit = {}
) : RapidsConnection(), RapidsConnection.MessageListener, RapidsConnection.StatusListener {

    init {
        Runtime.getRuntime().addShutdownHook(Thread(::shutdownHook))
        rapid.register(this as MessageListener)
        rapid.register(this as StatusListener)

        if (appName != null) {
            PingPong(rapid, appName, instanceId)
        } else {
            log.info("not responding to pings; no app name set.")
        }
    }

    override fun onMessage(message: String, context: MessageContext, metrics: MeterRegistry) {
        notifyMessage(message, context, metrics)
    }

    override fun start() {
        ktor.start(wait = false)
        try {
            onKtorStartup()
            rapid.start()
        } finally {
            onKtorShutdown()
            val gracePeriod = 5000L
            val forcefulShutdownTimeout = 30000L
            log.info("shutting down ktor, waiting $gracePeriod ms for workers to exit. Forcing shutdown after $forcefulShutdownTimeout ms")
            ktor.stop(gracePeriod, forcefulShutdownTimeout)
            log.info("ktor shutdown complete: end of life. goodbye.")
        }
    }

    override fun stop() {
        rapid.stop()
    }

    override fun publish(message: String) {
        rapid.publish(message)
    }

    override fun publish(key: String, message: String) {
        rapid.publish(key, message)
    }

    override fun rapidName(): String {
        return rapid.rapidName()
    }

    private fun shutdownHook() {
        log.info("received shutdown signal, stopping app")
        stop()
    }

    override fun onStartup(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_up")
        notifyStartup()
    }

    override fun onReady(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_ready")
        notifyReady()
    }

    override fun onNotReady(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_not_ready")
        notifyNotReady()
    }

    override fun onShutdownSignal(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_stop")
        notifyShutdownSignal()
    }

    override fun onShutdown(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_down")
        notifyShutdown()
    }

    private fun publishApplicationEvent(rapidsConnection: RapidsConnection, event: String) {
        applicationEvent(event)?.also {
            log.info("publishing $event event for app_name=$appName, instance_id=$instanceId")
            try {
                rapidsConnection.publish(it)
            } catch (err: Exception) { log.info("failed to publish event: {}", err.message, err) }
        }
    }

    private fun applicationEvent(event: String): String? {
        if (appName == null) return null
        val packet = JsonMessage.newMessage(event, mapOf(
            "app_name" to appName,
            "instance_id" to instanceId
        ))
        return packet.toJson()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RapidApplication::class.java)

        fun create(
            env: Map<String, String>,
            consumerProducerFactory: ConsumerProducerFactory = ConsumerProducerFactory(AivenConfig.default),
            configure: (EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>, KafkaRapid) -> Unit = { _, _ -> }
        ): RapidsConnection {
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, PrometheusRegistry.defaultRegistry, Clock.SYSTEM)

            val kafkaRapid = createDefaultKafkaRapidFromEnv(
                factory = consumerProducerFactory,
                meterRegistry = meterRegistry,
                env = env
            )
            return Builder(
                appName = env["RAPID_APP_NAME"] ?: generateAppName(env),
                instanceId = generateInstanceId(env),
                rapid = kafkaRapid,
                meterRegistry = meterRegistry
            ).build(configure)
        }

        private fun generateInstanceId(env: Map<String, String>): String {
            if (env.containsKey("NAIS_APP_NAME")) return InetAddress.getLocalHost().hostName
            return UUID.randomUUID().toString()
        }

        private fun generateAppName(env: Map<String, String>): String? {
            val appName = env["NAIS_APP_NAME"] ?: return log.info("not generating app name because NAIS_APP_NAME not set").let { null }
            val namespace = env["NAIS_NAMESPACE"] ?: return log.info("not generating app name because NAIS_NAMESPACE not set").let { null }
            val cluster = env["NAIS_CLUSTER_NAME"] ?: return log.info("not generating app name because NAIS_CLUSTER_NAME not set").let { null }
            return "$appName-$cluster-$namespace"
        }
    }

    class Builder(
        private val appName: String?,
        private val instanceId: String,
        private val rapid: KafkaRapid,
        private val meterRegistry: PrometheusMeterRegistry
    ) {

        init {
            Thread.currentThread().setUncaughtExceptionHandler(::uncaughtExceptionHandler)
        }

        private var httpPort = 8080
        private var ktor: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
        private val modules = mutableListOf<Application.() -> Unit>()


        private var isAliveEndpoint = "/isalive"
        private var isReadyEndpoint = "/isready"
        private var metricsEndpoint = "/metrics"
        private var preStopHookEndpoint = "/stop"

        fun withHttpPort(httpPort: Int) = apply {
            this.httpPort = httpPort
        }

        fun withKtor(ktor: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>) = apply {
            this.ktor = ktor
        }

        fun withKtorModule(module: Application.() -> Unit) = apply {
            this.modules.add(module)
        }

        fun withIsAliveEndpoint(isAliveEndpoint: String) = apply {
            this.isAliveEndpoint = isAliveEndpoint
        }

        fun withIsReadyEndpoint(isReadyEndpoint: String) = apply {
            this.isReadyEndpoint = isReadyEndpoint
        }

        fun withMetricsEndpoint(metricsEndpoint: String) = apply {
            this.metricsEndpoint = metricsEndpoint
        }

        fun withPreStopHookEndpoint(preStopHookEndpoint: String) = apply {
            this.preStopHookEndpoint = preStopHookEndpoint
        }

        fun build(configure: (EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>, KafkaRapid) -> Unit = { _, _ -> }, cioConfiguration: CIOApplicationEngine.Configuration.() -> Unit = { } ): RapidsConnection {
            val app = ktor ?: defaultKtorApp(cioConfiguration)
            configure(app, rapid)
            return RapidApplication(app, rapid, appName, instanceId)
        }

        private fun defaultKtorApp(cioConfiguration: CIOApplicationEngine.Configuration.() -> Unit): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
            val stopHook = PreStopHook(rapid)
            return defaultNaisApplication(
                port = httpPort,
                collectorRegistry = meterRegistry,
                isAliveCheck = rapid::isRunning,
                isReadyCheck = rapid::isReady,
                preStopHook = stopHook::handlePreStopRequest,
                extraModules = modules,
                cioConfiguration = cioConfiguration,
                metricsEndpoint = metricsEndpoint,
                isAliveEndpoint = isAliveEndpoint,
                isReadyEndpoint = isReadyEndpoint,
                preStopHookEndpoint = preStopHookEndpoint
            )
        }

        private fun uncaughtExceptionHandler(thread: Thread, err: Throwable) {
            log.error("Uncaught exception in thread ${thread.name}: ${err.message}", err)
        }
    }
}
