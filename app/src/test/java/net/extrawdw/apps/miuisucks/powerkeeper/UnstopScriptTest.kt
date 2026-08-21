package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnstopScriptTest {
    @Test
    fun checksOnlySelectedPackagesForSelectedUsers() {
        val script = UnstopScript.build(
            packageNames = listOf("com.example.two", "com.example.one", "com.example.one"),
            targetUsers = listOf(999, 0, 999),
        )

        assertTrue(script.contains("pm list packages --user '0' --show-stopped"))
        assertTrue(script.contains("pm list packages --user '999' --show-stopped"))
        assertTrue(script.contains("pm unstop --user '0' 'com.example.one'"))
        assertTrue(script.contains("pm unstop --user '999' 'com.example.two'"))
        assertFalse(script.contains("force-stop"))
        assertFalse(script.contains("com.example.three"))
    }

    @Test
    fun emptySelectionProducesSuccessfulNoOp() {
        val script = UnstopScript.build(emptyList(), listOf(0))

        assertTrue(script.contains("no packages or Android users selected"))
        assertFalse(script.contains("pm unstop"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPackageNames() {
        UnstopScript.build(listOf("com.example;rm"), listOf(0))
    }
}
