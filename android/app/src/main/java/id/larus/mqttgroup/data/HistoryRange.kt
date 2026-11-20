package id.larus.mqttgroup.data

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Which slice of the past the map is drawing.
 *
 * Resolved to milliseconds only when it is asked for, never stored as one. "Today" written down
 * at 23:59 and read back at 00:01 has to mean the new day, and a range fixed at the moment a
 * chip was tapped would quietly go stale in the pocket — the map would still say "Today" while
 * showing yesterday.
 *
 * The presets are the spans people actually ask for out loud. [Custom] is the escape hatch, and
 * carries whole days rather than instants because that is what a date picker can express.
 */
sealed interface HistoryRange {

    /** The label shown on the range chip. */
    val label: String

    /**
     * Start and end in epoch milliseconds, half-open: `since <= t < until`.
     *
     * Ranges that run up to the present end at [Long.MAX_VALUE] rather than at `now`. Two
     * reasons: a fix stamped in the same millisecond the window was resolved would otherwise
     * fall outside it and blink into the path a moment later, and a phone whose clock runs a few
     * seconds fast would have its newest position quietly excluded from "today" — a real fix,
     * dropped for a reason nobody can see.
     */
    fun window(now: Long = System.currentTimeMillis()): LongRange

    data object LastHour : HistoryRange {
        override val label = "Last hour"
        override fun window(now: Long) = (now - TimeUnit.HOURS.toMillis(1))..Long.MAX_VALUE
    }

    data object Today : HistoryRange {
        override val label = "Today"
        override fun window(now: Long) = startOfDay(now)..Long.MAX_VALUE
    }

    data object Yesterday : HistoryRange {
        override val label = "Yesterday"
        override fun window(now: Long): LongRange {
            val today = startOfDay(now)
            return (today - DAY_MS)..today
        }
    }

    data object Last24Hours : HistoryRange {
        override val label = "Last 24 hours"
        override fun window(now: Long) = (now - DAY_MS)..Long.MAX_VALUE
    }

    data object Last7Days : HistoryRange {
        override val label = "Last 7 days"
        override fun window(now: Long) = (startOfDay(now) - 6 * DAY_MS)..Long.MAX_VALUE
    }

    /**
     * A span the user picked by date. [firstDay] and [lastDay] are any instant inside the first
     * and last day they chose; both are widened here to cover those days whole.
     *
     * A date picker returns UTC midnight for a chosen day, which in this phone's timezone can be
     * the afternoon before or the small hours after — so the instants are normalised through the
     * local calendar rather than trusted as they arrive. Getting this wrong shows someone an
     * empty map for a day they know they travelled on.
     */
    data class Custom(val firstDay: Long, val lastDay: Long) : HistoryRange {
        // A getter, not an initialised property: formatting is an Android call, and doing it
        // when the range is constructed both drags the platform into a plain data class and puts
        // it out of reach of a JVM unit test that only wants to check the arithmetic.
        override val label: String
            get() = if (startOfDay(firstDay) == startOfDay(lastDay)) {
                formatDay(firstDay)
            } else {
                "${formatDay(firstDay)} – ${formatDay(lastDay)}"
            }

        override fun window(now: Long) = startOfDay(firstDay)..(startOfDay(lastDay) + DAY_MS)
    }

    companion object {
        /** Offered on the range menu, in the order they appear there. */
        val PRESETS = listOf(LastHour, Today, Yesterday, Last24Hours, Last7Days)

        val DEFAULT: HistoryRange = Last24Hours

        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Local midnight at the start of the day containing [at]. */
        fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        private fun formatDay(at: Long): String =
            android.text.format.DateFormat.format("d MMM", at).toString()
    }
}
