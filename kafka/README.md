Kafka
================

Noen ting er bare greit å få med på kjøpet!

## Komme i gang

```kotlin
private val config = AivenConfig.default
private val factory = ConsumerProducerFactory(config)

private val groupId = "min-consumergruppe"
private val defaultConsumerProperties = Properties().apply {
    this[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
}

private val running = AtomicBoolean(false)
private val consumer = factory.createConsumer(groupId, defaultConsumerProperties)
        
fun main() {
    factory.createProducer().use { producer ->
        producer.send(ProducerRecord("min_topic", "nøkkelen", "meldingsinnholdet"))
    }

    consumer.use {
        consumer.subscribe(listOf("min_topic"))

        try {
            consumer.poll(running::get) { records -> 
                records.forEach { record ->
                    /* gjør noe med meldingen */
                }
            }
        } catch (err: WakeupException) {
            log.info("Exiting consumer after ${if (!running.get()) "receiving shutdown signal" else "being interrupted by someone" }")
        }
    }
}

// kalles f.eks. fra en annen tråd
fun stop() {
    if (!running.getAndSet(false)) return log.info("Already in process of shutting down")
    log.info("Received shutdown signal. Waiting 10 seconds for app to shutdown gracefully")
    consumer.wakeup()
}
```
