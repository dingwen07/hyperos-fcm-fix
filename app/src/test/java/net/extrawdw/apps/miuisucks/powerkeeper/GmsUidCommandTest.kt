package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmsUidCommandTest {
    @Test
    fun targetsOwnerUserGooglePlayServices() {
        assertEquals(
            listOf(
                "/system/bin/cmd",
                "package",
                "list",
                "packages",
                "--user",
                "0",
                "-U",
                "com.google.android.gms",
            ),
            GmsUidCommand.build("0"),
        )
    }

    @Test
    fun parsesOnlyTheExactGooglePlayServicesPackage() {
        assertEquals(
            10130,
            GmsUidCommand.parse(
                """
                package:com.google.android.gms.location.history uid:10131
                package:com.google.android.gms uid:10130
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun rejectsMissingOrMalformedUid() {
        assertNull(GmsUidCommand.parse("package:com.google.android.gms"))
        assertNull(GmsUidCommand.parse("package:com.google.android.gms uid:not-a-number"))
    }
}
