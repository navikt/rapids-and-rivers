package no.nav.helse.rapids_rivers

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Histogram

object Metrics {
    private val registry = CollectorRegistry.defaultRegistry

    val lagHistogram = Histogram.build()
        .name("rapids_rivers_lag_seconds")
        .help("Consumer lag mellom sending av melding og mottak av melding")
        .labelNames("rapid")
        .register(registry)

    val onPacketHistorgram = Histogram.build()
        .name("on_packet_seconds")
        .help("Hvor lang det tar å lese en gjenkjent melding i sekunder")
        .labelNames("rapid", "event_name")
        .register(registry)

    val onMessageCounter = Counter.build()
        .name("message_counter")
        .help("Hvor mange meldinger som er lest inn")
        .labelNames("rapid", "validated")
        .register(registry)

}
