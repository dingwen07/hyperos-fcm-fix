package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageManagerOutputTest {
    @Test
    fun parsesUidOnlyForTheExactPackage() {
        assertEquals(
            10130,
            PackageManagerOutput.packageUid(
                """
                package:com.google.android.gms.location.history uid:10131
                package:com.google.android.gms uid:10130
                """.trimIndent(),
                "com.google.android.gms",
            ),
        )
    }

    @Test
    fun rejectsMissingOrMalformedUid() {
        assertNull(PackageManagerOutput.packageUid("package:com.google.android.gms", "com.google.android.gms"))
        assertNull(
            PackageManagerOutput.packageUid(
                "package:com.google.android.gms uid:not-a-number",
                "com.google.android.gms",
            ),
        )
    }

    @Test
    fun returnsOnlySelectedStoppedPackagesInStableOrder() {
        val output = """
            package:com.example.two uid:10202 stopped=true
            package:com.example.running uid:10203 stopped=false
            package:com.example.one uid:10201 stopped=true
            package:com.example.unselected uid:10204 stopped=true
            package:com.example.one uid:10201 stopped=true
        """.trimIndent()

        assertEquals(
            listOf("com.example.one", "com.example.two"),
            PackageManagerOutput.selectedStoppedPackages(
                output,
                listOf("com.example.two", "com.example.one", "com.example.running"),
            ),
        )
    }

    @Test
    fun emptyPackageListProducesNoStoppedPackages() {
        assertEquals(
            emptyList<String>(),
            PackageManagerOutput.selectedStoppedPackages("", listOf("com.example.push")),
        )
    }
}
