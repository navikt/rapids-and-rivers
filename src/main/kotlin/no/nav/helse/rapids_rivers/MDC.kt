package no.nav.helse.rapids_rivers

import org.slf4j.MDC
import java.io.Closeable

fun <R> withMDC(keyvalue: Pair<String, String>, block: () -> R): R {
    return MDC.putCloseable(keyvalue.first, keyvalue.second).use {
        block()
    }
}

fun <R> withMDC(context: Map<String, String>, block: () -> R): R {
    return CloseableMDCContext(context).use {
        block()
    }
}

private class CloseableMDCContext(newContext: Map<String, String>) : Closeable {
    private val originalContextMap = MDC.getCopyOfContextMap() ?: emptyMap()

    init {
        MDC.setContextMap(originalContextMap + newContext)
    }

    override fun close() {
        MDC.setContextMap(originalContextMap)
    }
}