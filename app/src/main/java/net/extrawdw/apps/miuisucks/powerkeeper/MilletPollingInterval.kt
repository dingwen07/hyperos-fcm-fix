package net.extrawdw.apps.miuisucks.powerkeeper

/** Supported delays for the combined MILLET and FCM watchdog. */
object MilletPollingInterval {
    const val DEFAULT_MILLIS = 30_000L

    val OPTIONS_MILLIS = listOf(DEFAULT_MILLIS, 60_000L, 120_000L)

    fun isSupported(intervalMillis: Long): Boolean = intervalMillis in OPTIONS_MILLIS

    fun normalize(intervalMillis: Long): Long =
        intervalMillis.takeIf(::isSupported) ?: DEFAULT_MILLIS

    fun secondsLabel(intervalMillis: Long): String =
        (normalize(intervalMillis) / 1_000L).toString()

    fun diagnosticLabel(intervalMillis: Long): String = "${secondsLabel(intervalMillis)}s"
}
