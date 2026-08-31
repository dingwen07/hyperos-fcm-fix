package net.extrawdw.apps.miuisucks.powerkeeper

import java.io.File

internal enum class FcmSocketProbeResult {
    MATCHED,
    NO_MATCH,
    UNAVAILABLE,
}

internal object FcmSocketProbe {
    private val PROC_NET_FILES = listOf(
        File("/proc/net/tcp"),
        File("/proc/net/tcp6"),
    )

    fun probe(gmsUid: Int): FcmSocketProbeResult = probe(gmsUid, PROC_NET_FILES)

    internal fun probe(gmsUid: Int, files: List<File>): FcmSocketProbeResult {
        var unavailable = false
        files.forEach { file ->
            val matched = runCatching {
                file.useLines { lines -> hasEstablishedFcmSocket(lines, gmsUid) }
            }.getOrElse {
                unavailable = true
                false
            }
            if (matched) return FcmSocketProbeResult.MATCHED
        }
        return if (unavailable) FcmSocketProbeResult.UNAVAILABLE else FcmSocketProbeResult.NO_MATCH
    }

    internal fun hasEstablishedFcmSocket(lines: Sequence<String>, gmsUid: Int): Boolean =
        lines.any { line ->
            val fields = line.trim().split(WHITESPACE)
            if (fields.size <= UID_FIELD_INDEX || fields[STATE_FIELD_INDEX] != TCP_ESTABLISHED) {
                return@any false
            }
            val remotePort = fields[REMOTE_ADDRESS_FIELD_INDEX]
                .substringAfterLast(':', missingDelimiterValue = "")
                .toIntOrNull(16)
            fields[UID_FIELD_INDEX].toIntOrNull() == gmsUid && remotePort in FCM_REMOTE_PORTS
        }

    private const val REMOTE_ADDRESS_FIELD_INDEX = 2
    private const val STATE_FIELD_INDEX = 3
    private const val UID_FIELD_INDEX = 7
    private const val TCP_ESTABLISHED = "01"
    private val FCM_REMOTE_PORTS = 5228..5230
    private val WHITESPACE = Regex("\\s+")
}
