package com.github.navikt.tbd_libs.test_support

import org.apache.kafka.clients.CommonClientConfigs
import org.testcontainers.DockerClientFactory
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

class KafkaContainer(private val appnavn: String) {
    private val instance by lazy {
        ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1")).apply {
            withCreateContainerCmdModifier { command -> command.withName(appnavn) }
            withReuse(true)
            withLabel("app-navn", appnavn)
            DockerClientFactory.lazyClient().apply {
                this
                    .listContainersCmd()
                    .exec()
                    .filter { it.labels["app-navn"] == appnavn }
                    .forEach {
                        killContainerCmd(it.id).exec()
                        removeContainerCmd(it.id).withForce(true).exec()
                    }
            }
            println("Starting kafka container")
            start()
        }
    }

    private val topicCount = AtomicInteger(0)

    val connectionProperties by lazy {
        Properties().apply {
            println("Bootstrap servers: ${instance.bootstrapServers}")
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, instance.bootstrapServers)
        }
    }

    fun nyTopic(): TestTopic {
        return nyeTopics(1).single()
    }

    fun nyeTopics(antall: Int): List<TestTopic> {
        return (0..<antall).map { TestTopic("test.topic.${topicCount.incrementAndGet()}", connectionProperties) }
    }


    fun droppTopic(testTopic: TestTopic) {
        droppTopics(listOf(testTopic))
    }

    @SuppressWarnings("unused")
    fun droppTopics(testTopics: List<TestTopic>) {
        /* todo: fremtidig api? */
    }
}

fun kafkaTest(kafkaContainer: KafkaContainer, testBlock: TestTopic.() -> Unit) {
    val testTopic = kafkaContainer.nyTopic()
    try {
        testBlock(testTopic)
    } finally {
        kafkaContainer.droppTopic(testTopic)
    }
}