package com.github.navikt.tbd_libs.rapids_and_rivers_api

data class MessageMetadata(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val headers: Map<String, ByteArray>
)