package com.github.navikt.tbd_libs.rapids_and_rivers.test_support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.rapids_and_rivers_api.FailedMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.KeyMessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.rapids_and_rivers_api.SentMessage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class TestRapid(private val meterRegistry: MeterRegistry = SimpleMeterRegistry()) :
    RapidsConnection() {
    private companion object {
        private val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    private val messages = mutableListOf<Pair<String?, String>>()
    val inspektør get() = RapidInspector(messages.toList())

    fun reset() {
        messages.clear()
    }

    fun sendTestMessage(message: String) {
        notifyMessage(message, this, MessageMetadata("test.message", -1, -1, null, emptyMap()), meterRegistry)
    }

    fun sendTestMessage(message: String, key: String) {
        notifyMessage(message, KeyMessageContext(this, key), MessageMetadata("test.message", -1, -1, key, emptyMap()), meterRegistry)
    }

    override fun publish(message: String) {
        messages.add(null to message)
    }

    override fun publish(key: String, message: String) {
        messages.add(key to message)
    }

    override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> {
        this.messages.addAll(messages.map { it.key to it.body })
        return messages.mapIndexed { index, it ->
            SentMessage(
                index = index,
                message = it,
                partition = 0,
                offset = 0L
            )
        } to emptyList()
    }

    override fun rapidName(): String {
        return "testRapid"
    }

    override fun start() {}
    override fun stop() {}

    class RapidInspector(private val messages: List<Pair<String?, String>>) {
        private val jsonMessages = mutableMapOf<Int, JsonNode>()
        val size get() = messages.size

        fun key(index: Int) = messages[index].first
        fun message(index: Int) = jsonMessages.getOrPut(index) { objectMapper.readTree(messages[index].second) }
        fun field(index: Int, field: String) =
            requireNotNull(message(index).path(field).takeUnless { it.isMissingNode || it.isNull }) {
                "Message does not contain field '$field'"
            }
    }
}
