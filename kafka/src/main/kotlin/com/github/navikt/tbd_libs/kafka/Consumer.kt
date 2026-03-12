package com.github.navikt.tbd_libs.kafka

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

fun AdminClient.getPartitions(topic: String) = this
    .describeTopics(listOf(topic))
    .allTopicNames()
    .get()
    .getValue(topic)
    .partitions()
    .map { TopicPartition(topic, it.partition()) }

fun AdminClient.findOffsets(partitions: Collection<TopicPartition>, spec: OffsetSpec): Map<TopicPartition, Long> = this
    .listOffsets(partitions.associateWith { spec })
    .all()
    .get()
    .mapValues { it.value.offset() }

/**
 * brukes for å finne offsets for et tidspunkt på alle partisjoner.
 * resultatet kan brukes på en consumer for å seek'e til de angitte offsetene
 */
fun AdminClient.findOffsetsForTime(topic: String, time: LocalDateTime, zoneId: ZoneId = ZoneId.systemDefault()): Map<TopicPartition, Long> {
    val timestamp = time.atZone(zoneId).toInstant().toEpochMilli()
    val partitions = getPartitions(topic)
    return findOffsets(partitions, OffsetSpec.forTimestamp(timestamp))
}

fun <K, V> KafkaConsumer<K, V>.poll(until: () -> Boolean = { true }, block: (ConsumerRecords<K, V>) -> Unit) {
    while (until()) {
        poll(Duration.ofMillis(500)).also(block)
    }
}