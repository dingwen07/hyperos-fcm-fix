package net.extrawdw.apps.miuisucks.powerkeeper

internal object FcmReconnectPolicy {
    const val MANDATORY_INTERVAL_MILLIS = 10L * 60_000L

    fun isMandatoryReconnectDue(nowElapsed: Long, nextMandatoryReconnectElapsed: Long): Boolean =
        nowElapsed >= nextMandatoryReconnectElapsed

    fun nextMandatoryReconnectElapsed(nowElapsed: Long): Long =
        nowElapsed + MANDATORY_INTERVAL_MILLIS
}
