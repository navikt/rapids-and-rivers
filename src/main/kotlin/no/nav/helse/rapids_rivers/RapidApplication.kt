package no.nav.helse.rapids_rivers

import io.ktor.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.prometheus.client.CollectorRegistry
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.net.InetAddress
import java.time.LocalDateTime
import java.util.*

class RapidApplication internal constructor(
    private val ktor: ApplicationEngine,
    private val rapid: RapidsConnection,
    private val appName: String? = null,
    private val instanceId: String
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
        listeners.forEach { it.onMessage(message, context) }
    }

    override fun start() {
        ktor.start(wait = false)
        rapid.start()
    }

    override fun stop() {
        rapid.stop()
        ktor.stop(1000, 1000)
    }

    override fun publish(message: String) {
        rapid.publish(message)
    }

    override fun publish(key: String, message: String) {
        rapid.publish(key, message)
    }

    private fun shutdownHook() {
        log.info("received shutdown signal, stopping app")
        stop()
    }

    override fun onStartup(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_up")
        statusListeners.forEach { it.onStartup(this) }
    }

    override fun onReady(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_ready")
        statusListeners.forEach { it.onReady(this) }
    }

    override fun onNotReady(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_not_ready")
        statusListeners.forEach { it.onReady(this) }
    }

    override fun onShutdown(rapidsConnection: RapidsConnection) {
        publishApplicationEvent(rapidsConnection, "application_down")
        statusListeners.forEach {
            try {
                it.onShutdown(this)
            } catch (err: Exception) {
                log.error("A shutdown callback threw an exception: ${err.message}", err)
            }
        }
    }

    private fun publishApplicationEvent(rapidsConnection: RapidsConnection, event: String) {
        applicationEvent(event)?.also {
            log.info("publishing $event event for app_name=$appName, instance_id=$instanceId")
            try {
                rapidsConnection.publish(it)
            } catch (err: Exception) {
            }
        }
    }

    private fun applicationEvent(event: String): String? {
        if (appName == null) return null
        val packet = JsonMessage("{}", MessageProblems("{}"))
        packet["@event_name"] = event
        packet["@opprettet"] = LocalDateTime.now()
        packet["app_name"] = appName
        packet["instance_id"] = instanceId
        return packet.toJson()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RapidApplication::class.java)

        fun create(env: Map<String, String>, configure: (ApplicationEngine, KafkaRapid) -> Unit = { _, _ -> }) =
            Builder(RapidApplicationConfig.fromEnv(env)).build(configure)
    }

    class Builder(private val config: RapidApplicationConfig) {

        init {
            Thread.currentThread().setUncaughtExceptionHandler(::uncaughtExceptionHandler)
        }

        private val rapid = KafkaRapid.create(config.kafkaConfig, config.rapidTopic, config.extraTopics)

        private val ktor = KtorBuilder()
            .log(log)
            .port(config.httpPort)
            .liveness(rapid::isRunning)
            .readiness(rapid::isReady)

        fun withCollectorRegistry(registry: CollectorRegistry = CollectorRegistry.defaultRegistry) = apply {
            ktor.withCollectorRegistry(registry)
        }

        fun withKtorModule(module: Application.() -> Unit) = apply {
            ktor.module(module)
        }

        fun build(configure: (ApplicationEngine, KafkaRapid) -> Unit = { _, _ -> }): RapidsConnection {
            val app = ktor.build()
            configure(app, rapid)
            return RapidApplication(app, rapid, config.appName, config.instanceId)
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
                    kafkaConfig = KafkaConfig(
                        isKafkaCloud = env["IS_KAFKA_CLOUD"]?.let { "true" == it.toLowerCase() },
                        bootstrapServers = env.getValue("KAFKA_BOOTSTRAP_SERVERS"),
                        consumerGroupId = env.getValue("KAFKA_CONSUMER_GROUP_ID"),
                        clientId = instanceId,
                        username = "/var/run/secrets/nais.io/service_user/username".readFile(),
                        password = "/var/run/secrets/nais.io/service_user/password".readFile(),
                        truststore = env["NAV_TRUSTSTORE_PATH"],
                        truststorePassword = env["NAV_TRUSTSTORE_PASSWORD"],
                        sslTruststoreLocationEnvKey = env["KAFKA_TRUSTSTORE_PATH"],
                        sslTruststorePasswordEnvKey = env["KAFKA_TRUSTSTORE_PASSWORD"],
                        sslKeystoreLocationEnvKey = env["KAFKA_KEYSTORE_PATH"],
                        sslKeystorePasswordEnvKey = env["KAFKA_KEYSTORE_PASSWORD"],
                        autoOffsetResetConfig = env["KAFKA_RESET_POLICY"],
                        autoCommit = env["KAFKA_AUTO_COMMIT"]?.let { "true" == it.toLowerCase() },
                        maxIntervalMs = env["KAFKA_MAX_POLL_INTERVAL_MS"]?.toInt(),
                        maxRecords = env["KAFKA_MAX_RECORDS"]?.toInt()
                    ),
                    httpPort = env["HTTP_PORT"]?.toInt() ?: 8080
                )
            }

            private fun generateInstanceId(env: Map<String, String>): String {
                if (env.containsKey("NAIS_APP_NAME")) return InetAddress.getLocalHost().hostName
                return UUID.randomUUID().toString()
            }

            private fun generateAppName(env: Map<String, String>): String? {
                val appName =
                    env["NAIS_APP_NAME"] ?: return log.info("not generating app name because NAIS_APP_NAME not set")
                        .let { null }
                val namespace =
                    env["NAIS_NAMESPACE"] ?: return log.info("not generating app name because NAIS_NAMESPACE not set")
                        .let { null }
                val cluster = env["NAIS_CLUSTER_NAME"]
                    ?: return log.info("not generating app name because NAIS_CLUSTER_NAME not set").let { null }
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
