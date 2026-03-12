package com.github.navikt.tbd_libs.test_support

import java.util.concurrent.ConcurrentHashMap

object KafkaContainers {
    private val instances = ConcurrentHashMap<String, KafkaContainer>()

    // gjenbruker containers med samme navn for å unngå
    // å spinne opp mange containers
    fun container(appnavn: String): KafkaContainer {
        return instances.getOrPut(appnavn) { KafkaContainer(appnavn) }
    }
}