package no.nav.helse.rapids_rivers

import java.util.*

interface Config {
    fun producerConfig(properties: Properties): Properties
    fun consumerConfig(groupId: String, properties: Properties): Properties
    fun adminConfig(properties: Properties): Properties
}
