package net.extrawdw.apps.miuisucks.powerkeeper

/**
 * Supported delays for the daemon MILLET watchdog. The 2.5-second default retains a nominal
 * 2.5-second response margin before the shortest observed five-second delayed-freeze path.
 * The scheduled task has no wake lock and therefore does not wake a suspended device.
 */
object MilletPollingInterval {
    const val DEFAULT_MILLIS = 2_500L

    val OPTIONS_MILLIS = listOf(DEFAULT_MILLIS, 5_000L, 10_000L, 30_000L)

    fun isSupported(intervalMillis: Long): Boolean = intervalMillis in OPTIONS_MILLIS

    fun normalize(intervalMillis: Long): Long =
        intervalMillis.takeIf(::isSupported) ?: DEFAULT_MILLIS

    fun secondsLabel(intervalMillis: Long): String = when (normalize(intervalMillis)) {
        DEFAULT_MILLIS -> "2.5"
        else -> (intervalMillis / 1_000L).toString()
    }

    fun diagnosticLabel(intervalMillis: Long): String = "${secondsLabel(intervalMillis)}s"
}
