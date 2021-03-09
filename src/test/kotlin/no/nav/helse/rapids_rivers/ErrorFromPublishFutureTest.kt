package no.nav.helse.rapids_rivers


import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.*
import java.lang.RuntimeException

internal class ErrorFromPublishFutureTest {
    lateinit var mockProducer: MockProducer<String, String>
    lateinit var mockConsumer: Consumer<String, String>
    lateinit var rapid: KafkaRapid

    @BeforeEach
    fun start(){
        mockProducer = MockProducer(false, StringSerializer(), StringSerializer());
        mockConsumer = MockConsumer(OffsetResetStrategy.LATEST);
        rapid = KafkaRapid(
            consumer = mockConsumer,
            producer = mockProducer,
            autoCommit = false,
            rapidTopic = "rapid",
            extraTopics = emptyList()
        )

        GlobalScope.launch {
            rapid.start()
        }
    }

    @AfterEach
    fun stop(){
        rapid.stop()
    }

    @Test
    fun `ignore errors on publish when not publishing synchronously`() {
        rapid.publish("arbitrary message")
        mockProducer.errorNext(TestException())
    }


    @Test
    fun `raise errors on publish when not publishing synchronously`() {
        rapid.syncronizedPublish()
        GlobalScope.launch {
            delay(100L)
            mockProducer.errorNext(TestException())
        }
        assertThrows<TestException> { rapid.publish("arbitrary message") }
    }

    class TestException: RuntimeException()
}
