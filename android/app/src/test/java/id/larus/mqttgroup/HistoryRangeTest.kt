package id.larus.mqttgroup

import id.larus.mqttgroup.data.HistoryRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Calendar
import org.junit.Test

/**
 * The date arithmetic behind the map's range picker.
 *
 * Worth testing because every mistake here looks the same from the outside — an empty map for a
 * day the user knows they travelled on — and none of them look like a bug in a date calculation.
 * Labels are not covered: they format through Android and this is a plain JVM test.
 */
class HistoryRangeTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    @Test
    fun `today starts at local midnight and does not end`() {
        val now = at(2026, 9, 9, 14, 30)
        val window = HistoryRange.Today.window(now)

        assertEquals(at(2026, 9, 9, 0), window.first)
        assertEquals(Long.MAX_VALUE, window.last)
    }

    @Test
    fun `today still means today one minute before midnight`() {
        val now = at(2026, 9, 9, 23, 59)
        assertEquals(at(2026, 9, 9, 0), HistoryRange.Today.window(now).first)
    }

    @Test
    fun `yesterday is the whole day before, and stops at today`() {
        val now = at(2026, 9, 9, 14, 30)
        val window = HistoryRange.Yesterday.window(now)

        assertEquals(at(2026, 9, 8, 0), window.first)
        assertEquals(at(2026, 9, 9, 0), window.last)
    }

    @Test
    fun `last 7 days covers seven whole days including today`() {
        val now = at(2026, 9, 9, 14, 30)
        assertEquals(at(2026, 9, 3, 0), HistoryRange.Last7Days.window(now).first)
    }

    @Test
    fun `a custom range covers both chosen days whole`() {
        // Afternoon instants, as a date picker's UTC midnight can land in this timezone.
        val window = HistoryRange.Custom(
            firstDay = at(2026, 9, 3, 17),
            lastDay = at(2026, 9, 5, 9),
        ).window()

        assertEquals(at(2026, 9, 3, 0), window.first)
        // Half-open: the end is the midnight *after* the last chosen day, so a fix at 23:59 on
        // the 5th is inside and one at 00:00 on the 6th is not.
        assertEquals(at(2026, 9, 6, 0), window.last)
        assertTrue(at(2026, 9, 5, 23, 59) < window.last)
        assertTrue(at(2026, 9, 6, 0) >= window.last)
    }

    @Test
    fun `a custom range of one day is that day, not an empty window`() {
        val day = at(2026, 9, 3, 17)
        val window = HistoryRange.Custom(firstDay = day, lastDay = day).window()

        assertEquals(at(2026, 9, 3, 0), window.first)
        assertEquals(at(2026, 9, 4, 0), window.last)
        assertTrue(day in window.first until window.last)
    }

    @Test
    fun `relative ranges run to the end of time, so a fix stamped now is inside`() {
        val now = at(2026, 9, 9, 14, 30)
        listOf(HistoryRange.LastHour, HistoryRange.Last24Hours, HistoryRange.Last7Days)
            .forEach { range ->
                val window = range.window(now)
                assertTrue("${range::class.simpleName} excludes now", now in window.first until window.last)
            }
    }
}
