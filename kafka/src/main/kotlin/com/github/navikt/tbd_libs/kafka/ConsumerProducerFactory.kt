package com.github.navikt.tbd_libs.kafka

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.*

class ConsumerProducerFactory(private val config: Config) {
    fun createConsumer(groupId: String, properties: Properties = Properties(), withShutdownHook: Boolean = true): KafkaConsumer<String, String> {
        val deser = StringDeserializer()
        return KafkaConsumer(config.consumerConfig(groupId, properties), deser, deser).also {
            if (withShutdownHook) {
                Runtime.getRuntime().addShutdownHook(Thread {
                    it.wakeup()
                })
            }
        }
    }

    fun createProducer(properties: Properties = Properties(), withShutdownHook: Boolean = true): KafkaProducer<String, String> {
        val ser = StringSerializer()
        return KafkaProducer(config.producerConfig(properties), ser, ser).also {
            if (withShutdownHook) {
                Runtime.getRuntime().addShutdownHook(Thread {
                    it.close()
                })
            }
        }
    }

    fun adminClient(properties: Properties = Properties()): AdminClient = AdminClient.create(config.adminConfig(properties))
}
