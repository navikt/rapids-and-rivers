package no.nav.helse.rapids_rivers

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class RapidApplication internal constructor(
    private val ktor: ApplicationEngine,
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

    override fun onMessage(message: String, context: MessageContext) {
        notifyMessage(message, context)
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

        fun create(env: Map<String, String>, configure: (ApplicationEngine, KafkaRapid) -> Unit = {_, _ -> }) = Builder(RapidApplicationConfig.fromEnv(env)).build(configure, CIO)
    }

    class Builder(private val config: RapidApplicationConfig) {

        init {
            Thread.currentThread().setUncaughtExceptionHandler(::uncaughtExceptionHandler)
        }

        private val isKtorRunning = AtomicBoolean(false)
        private val rapid = KafkaRapid.create(config.kafkaConfig, config.rapidTopic, config.extraTopics)

        private val ktor = KtorBuilder()
            .log(log)
            .port(config.httpPort)
            .preStopHook(this::handlePreStopRequest)
            .liveness(rapid::isRunning)
            .readiness(rapid::isReady)
            .metrics(rapid.getMetrics())

        fun withCollectorRegistry(registry: CollectorRegistry = CollectorRegistry.defaultRegistry) = apply {
            ktor.withCollectorRegistry(registry)
        }

        fun withKtorModule(module: Application.() -> Unit) = apply {
            ktor.module(module)
        }

        fun build(configure: (ApplicationEngine, KafkaRapid) -> Unit = { _, _ -> }): RapidsConnection {
            return build(configure, CIO)
        }

        fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> build(configure: (ApplicationEngine, KafkaRapid) -> Unit = { _, _ -> }, factory: ApplicationEngineFactory<TEngine, TConfiguration>): RapidsConnection {
            val app = ktor.build(factory)
            configure(app, rapid)
            return RapidApplication(app, rapid, config.appName, config.instanceId, this::onKtorStartup, this::onKtorShutdown)
        }

        private suspend fun handlePreStopRequest() {
            rapid.stop()
            // block the preStopHook call from returning until
            // ktor is ready to shutdown, which means that the KafkaRapid has shutdown
            while (isKtorRunning.get()) {
                delay(1000)
            }
        }

        private fun onKtorStartup() {
            isKtorRunning.set(true)
        }

        private fun onKtorShutdown() {
            isKtorRunning.set(false)
        }

        private fun uncaughtExceptionHandler(thread: Thread, err: Throwable) {
            log.error("Uncaught exception in thread ${thread.name}: ${err.message}", err)
        }
    }

    class RapidApplicationConfig(
        internal val appName: String?,
        internal val instanceId: String,
        internal val rapidTopic: String,
        internal val extraTopics: List<String> = emptyList(),
        internal val kafkaConfig: KafkaConfig,
        internal val httpPort: Int = 8080
    ) {
        companion object {
            fun fromEnv(env: Map<String, String>) = generateInstanceId(env).let { instanceId ->
                RapidApplicationConfig(
                    appName = env["RAPID_APP_NAME"] ?: generateAppName(env) ?: log.info("app name not configured")
                        .let { null },
                    instanceId = instanceId,
                    rapidTopic = env.getValue("KAFKA_RAPID_TOPIC"),
                    extraTopics = env["KAFKA_EXTRA_TOPIC"]?.split(',')?.map(String::trim) ?: emptyList(),
                    kafkaConfig = kafkaConfig(env, instanceId),
                    httpPort = env["HTTP_PORT"]?.toInt() ?: 8080
                )
            }

            private fun kafkaConfig(env: Map<String, String>, instanceId: String): KafkaConfig {
                val preferOnPrem = env["KAFKA_PREFER_ON_PREM"]?.let { it.lowercase() == "true"} ?: false
                if (preferOnPrem || !gcpConfigAvailable(env)) return onPremConfig(env, instanceId)
                return gcpConfig(env, instanceId)
            }

            private fun gcpConfigAvailable(env: Map<String, String>) =
                env.containsKey("KAFKA_BROKERS") && env.containsKey("KAFKA_CREDSTORE_PASSWORD")

            private fun gcpConfig(env: Map<String, String>, instanceId: String) =
                KafkaConfig(
                    bootstrapServers = env.getValue("KAFKA_BROKERS"),
                    consumerGroupId = env.getValue("KAFKA_CONSUMER_GROUP_ID"),
                    clientId = instanceId,
                    username = null,
                    password = null,
                    truststore = env["KAFKA_TRUSTSTORE_PATH"],
                    truststorePassword = env.getValue("KAFKA_CREDSTORE_PASSWORD"),
                    keystoreLocation = env.getValue("KAFKA_KEYSTORE_PATH"),
                    keystorePassword = env.getValue("KAFKA_CREDSTORE_PASSWORD"),
                    autoOffsetResetConfig = env["KAFKA_RESET_POLICY"],
                    autoCommit = env["KAFKA_AUTO_COMMIT"]?.let { "true" == it.lowercase() },
                    maxIntervalMs = env["KAFKA_MAX_POLL_INTERVAL_MS"]?.toInt(),
                    maxRecords = env["KAFKA_MAX_RECORDS"]?.toInt()
                )


            private fun onPremConfig(env: Map<String, String>, instanceId: String) =
                KafkaConfig(
                    bootstrapServers = env.getValue("KAFKA_BOOTSTRAP_SERVERS"),
                    consumerGroupId = env.getValue("KAFKA_CONSUMER_GROUP_ID"),
                    clientId = instanceId,
                    username = "/var/run/secrets/nais.io/service_user/username".readFile(),
                    password = "/var/run/secrets/nais.io/service_user/password".readFile(),
                    truststore = env["NAV_TRUSTSTORE_PATH"],
                    truststorePassword = env["NAV_TRUSTSTORE_PASSWORD"],
                    keystoreLocation = null,
                    keystorePassword = null,
                    autoOffsetResetConfig = env["KAFKA_RESET_POLICY"],
                    autoCommit = env["KAFKA_AUTO_COMMIT"]?.let { "true" == it.lowercase() },
                    maxIntervalMs = env["KAFKA_MAX_POLL_INTERVAL_MS"]?.toInt(),
                    maxRecords = env["KAFKA_MAX_RECORDS"]?.toInt()
                )

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
    }
}

private fun String.readFile() =
    try {
        File(this).readText(Charsets.UTF_8)
    } catch (err: FileNotFoundException) {
        null
    }
