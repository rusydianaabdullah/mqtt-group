package id.larus.mqttgroup

import id.larus.mqttgroup.data.repo.OutboxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxBackoffTest {

    @Test
    fun `doubles from one second`() {
        assertEquals(1_000L, OutboxRepository.backoffMillis(1))
        assertEquals(2_000L, OutboxRepository.backoffMillis(2))
        assertEquals(4_000L, OutboxRepository.backoffMillis(3))
        assertEquals(8_000L, OutboxRepository.backoffMillis(4))
    }

    @Test
    fun `caps at five minutes`() {
        // Uncapped doubling would have a phone that woke from a long doze sitting out an
        // hour-long backoff with a full outbox.
        assertEquals(5 * 60_000L, OutboxRepository.backoffMillis(10))
        assertEquals(5 * 60_000L, OutboxRepository.backoffMillis(OutboxRepository.MAX_RETRIES))
    }

    @Test
    fun `never overflows, however many retries a row accumulates`() {
        for (attempt in 1..100) {
            assertTrue("attempt $attempt", OutboxRepository.backoffMillis(attempt) in 1_000L..300_000L)
        }
    }
}
