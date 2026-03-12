package com.github.navikt.tbd_libs.kafka

import com.github.navikt.tbd_libs.test_support.KafkaContainers
import com.github.navikt.tbd_libs.test_support.kafkaTest
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

class KafkaTest {
    private companion object {
        private val kafkaContainer = KafkaContainers.container("tbd-libs-kafka")
    }

    @Test
    fun factory() {
        kafkaTest(kafkaContainer) {
            val key = "key"
            val value = "message"

            val recordMetaFuture = send(key, value)

            pollRecords().also { records ->
                assertEquals(1, records.count())
                val record = records.single()
                assertEquals(key, record.key())
                assertEquals(value, record.value())
            }

            val partitions = adminClient.getPartitions(topicnavn)
            val recordMeta = recordMetaFuture.resultNow()
            val expectedOffsets = partitions.associateWith {
                if (it.partition() == recordMeta.partition()) recordMeta.offset() + 1 else 0
            }
            val actualOffsets = adminClient.findOffsets(partitions, OffsetSpec.latest())
            assertEquals(expectedOffsets, actualOffsets)
        }
    }

    @Test
    fun polling() {
        kafkaTest(kafkaContainer) {
            val key = "key"
            val value = "message"

            send(key, value)

            val expectedPollCount = 10
            val latch = CountDownLatch(expectedPollCount + 1)
            val until = {
                latch.countDown()
                latch.count > 0
            }

            val pollResults = mutableListOf<ConsumerRecords<*, *>>()
            consumer.poll(until) { records ->
                pollResults.add(records)
            }

            assertEquals(expectedPollCount, pollResults.size)
            assertEquals(1, pollResults.sumOf { it.count() })
        }
    }
}