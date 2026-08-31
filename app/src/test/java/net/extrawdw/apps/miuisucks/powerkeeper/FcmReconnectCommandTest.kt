package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Test

class FcmReconnectCommandTest {
    @Test
    fun targetsOwnerUserGooglePlayServices() {
        assertEquals(
            listOf(
                "/system/bin/am",
                "broadcast",
                "--user",
                "0",
                "-a",
                "com.google.android.intent.action.GCM_RECONNECT",
                "-p",
                "com.google.android.gms",
            ),
            FcmReconnectCommand.build("0"),
        )
    }
}
