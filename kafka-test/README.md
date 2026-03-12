Kafka test
==========

Tilbyr et overbygg for å parallelisere kafka-tester (E2E-tester?) 
uten at testene går i beina på hverandre.

Det funkerer slik at det opprettes én kafka-container
for hver unike appnavn, og hver kafka-container oppretter
`n` antall topics som kan gjenbrukes.

En test starter med å "ta" en topic slik at ingen
andre tester kan bruke den samme.
Testen avsluttes med at topicen gis tilbake slik at andre tester kan bruke den.

## Eksempel

```kotlin
class KafkaE2ETest {
    private companion object {
        private val kafkaContainer = KafkaContainers.container("a-unique-container-name")
    }

    @Test
    fun `custom serde`() = kafkaTest(kafkaContainer) {
        @Language("JSON")
        val message = objectMapper.readTree("""{ "name":  "Bar" }""")
        val jacksonSerde = JacksonSerde()
        val stringSerde = StringSerde()
        
        send(message, jacksonSerde.serializer())
        pollRecords(stringSerde.deserializer(), jacksonSerde.deserializer()).also {
            assertEquals(1, it.size)
            assertEquals(message, it.single().value())
        }
    }

    private class JacksonSerde(private val objectMapper: ObjectMapper = jacksonObjectMapper()) : Serde<JsonNode> {
        override fun serializer() = object : Serializer<JsonNode> {
            override fun serialize(topic: String, data: JsonNode) =
                objectMapper.writeValueAsBytes(data)
        }

        override fun deserializer() = object : Deserializer<JsonNode> {
            override fun deserialize(topic: String, data: ByteArray) =
                objectMapper.readTree(data)
        }
    }
}

```