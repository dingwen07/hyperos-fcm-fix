package net.extrawdw.apps.miuisucks.powerkeeper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmSocketProbeTest {
    @Test
    fun matchesEstablishedGmsSocketByRemoteFcmPort() {
        assertTrue(
            FcmSocketProbe.hasEstablishedFcmSocket(
                sequenceOf(socketLine(remotePortHex = "146C")),
                GMS_UID,
            ),
        )
    }

    @Test
    fun rejectsWrongUidStateAndLocalOnlyFcmPort() {
        assertFalse(
            FcmSocketProbe.hasEstablishedFcmSocket(
                sequenceOf(
                    socketLine(remotePortHex = "146D", uid = GMS_UID + 1),
                    socketLine(remotePortHex = "146E", state = "02"),
                    socketLine(localPortHex = "146C", remotePortHex = "01BB"),
                ),
                GMS_UID,
            ),
        )
    }

    @Test
    fun unavailableProcFileFailsOpen() {
        val readable = File.createTempFile("fcm-socket-probe", ".txt")
        val missing = File(readable.parentFile, "missing-${System.nanoTime()}")
        try {
            readable.writeText("sl local_address rem_address st\n")
            assertEquals(
                FcmSocketProbeResult.UNAVAILABLE,
                FcmSocketProbe.probe(GMS_UID, listOf(readable, missing)),
            )
        } finally {
            readable.delete()
        }
    }

    @Test
    fun matchWinsEvenWhenAnotherProcFileIsUnavailable() {
        val readable = File.createTempFile("fcm-socket-probe", ".txt")
        val missing = File(readable.parentFile, "missing-${System.nanoTime()}")
        try {
            readable.writeText(socketLine(remotePortHex = "146E"))
            assertEquals(
                FcmSocketProbeResult.MATCHED,
                FcmSocketProbe.probe(GMS_UID, listOf(missing, readable)),
            )
        } finally {
            readable.delete()
        }
    }

    private fun socketLine(
        localPortHex: String = "C001",
        remotePortHex: String,
        state: String = "01",
        uid: Int = GMS_UID,
    ): String =
        "0: 0100007F:$localPortHex 08080808:$remotePortHex $state " +
            "00000000:00000000 00:00000000 00000000 $uid 0 12345"

    private companion object {
        const val GMS_UID = 10130
    }
}
