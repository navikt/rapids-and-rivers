package no.nav.helse.rapids_rivers

import io.ktor.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.CollectorRegistry
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class RapidApplication internal constructor(
    private val ktor: ApplicationEngine,
    private val rapid: RapidsConnection
) : RapidsConnection(), RapidsConnection.MessageListener {

    init {
        Runtime.getRuntime().addShutdownHook(Thread(::shutdownHook))
        rapid.register(this)
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
        ktor.stop(1, 1, TimeUnit.SECONDS)
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

    companion object {
        private val log = LoggerFactory.getLogger(RapidApplication::class.java)

        fun create(env: Map<String, String>, configure: (ApplicationEngine, KafkaRapid) -> Unit = {_, _ -> }) = Builder(RapidApplicationConfig.fromEnv(env)).build(configure)
    }

    class Builder(config: RapidApplicationConfig) {

        init {
            Thread.currentThread().setUncaughtExceptionHandler(::uncaughtExceptionHandler)
        }

        private val rapid = KafkaRapid.create(config.kafkaConfig, config.rapidTopic, config.extraTopics)

        private val ktor = KtorBuilder()
            .log(log)
            .port(config.httpPort)
            .liveness(rapid::isRunning)
            .readiness(rapid::isRunning)
            .metrics(CollectorRegistry.defaultRegistry)

        fun withKtorModule(module: Application.() -> Unit) = apply {
            ktor.module(module)
        }

        fun build(configure: (ApplicationEngine, KafkaRapid) -> Unit = {_, _ -> }): RapidsConnection {
            val app = ktor.build()
            configure(app, rapid)
            return RapidApplication(app, rapid)
        }

        private fun uncaughtExceptionHandler(thread: Thread, err: Throwable) {
            log.error("Uncaught exception in thread ${thread.name}: ${err.message}", err)
        }
    }

    class RapidApplicationConfig(
        internal val rapidTopic: String,
        internal val extraTopics: List<String> = emptyList(),
        internal val kafkaConfig: KafkaConfig,
        internal val httpPort: Int = 8080
    ) {
        companion object {
            fun fromEnv(env: Map<String, String>) =
                RapidApplicationConfig(
                    rapidTopic = env.getValue("KAFKA_RAPID_TOPIC"),
                    extraTopics = env["KAFKA_EXTRA_TOPIC"]?.split(',')?.map(String::trim) ?: emptyList(),
                    kafkaConfig = KafkaConfig(
                        bootstrapServers = env.getValue("KAFKA_BOOTSTRAP_SERVERS"),
                        consumerGroupId = env.getValue("KAFKA_CONSUMER_GROUP_ID"),
                        username = "/var/run/secrets/nais.io/service_user/username".readFile(),
                        password = "/var/run/secrets/nais.io/service_user/password".readFile(),
                        truststore = env["NAV_TRUSTSTORE_PATH"],
                        truststorePassword = env["NAV_TRUSTSTORE_PASSWORD"],
                        autoOffsetResetConfig = env["KAFKA_RESET_POLICY"]
                    ),
                    httpPort = env["HTTP_PORT"]?.toInt() ?: 8080
                )
        }
    }
}

private fun String.readFile() =
    try {
        File(this).readText(Charsets.UTF_8)
    } catch (err: FileNotFoundException) {
        null
    }
