package com.github.navikt.tbd_libs.rapids_and_rivers_api

import java.util.*

fun interface RandomIdGenerator {
    companion object {
        val Default = RandomIdGenerator { UUID.randomUUID().toString() }
    }
    fun generateId(): String
}