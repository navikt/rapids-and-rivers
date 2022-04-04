package no.nav.helse.rapids_rivers

import no.nav.helse.rapids_rivers.testsupport.TestRapid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class PingPongTest {
    val rapid = TestRapid()
    val river = PingPong(rapid, "pingerino", "pongaroonie")

    @BeforeEach
    fun reset() {
        rapid.reset()
    }

    @Test
    fun `ignorer eldre pings`() {
        sendPing(LocalDateTime.now().minusHours(2))
        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `ignorer hyppige pings`() {
        repeat(10) { sendPing(LocalDateTime.now()) }
        assertEquals(1, rapid.inspektør.size)
    }

    @Test
    fun `svarer på nye pings`() {
        sendPing(LocalDateTime.now())
        assertEquals(1, rapid.inspektør.size)
    }

    fun sendPing(
        date: LocalDateTime = LocalDateTime.now()
    ) = rapid.sendTestMessage("""{"@event_name":"ping","@id":"${UUID.randomUUID()}","ping_time":"$date"}""")
}
