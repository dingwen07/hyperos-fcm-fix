package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardSettingsStoreTest {
    @Test
    fun offIntervalRemainsDisabled() {
        assertEquals(
            GuardSettingsStore.DISABLED_INTERVAL_MINUTES,
            GuardSettingsStore.normalizeIntervalMinutes(GuardSettingsStore.DISABLED_INTERVAL_MINUTES),
        )
        assertFalse(
            GuardSettingsStore.isPeriodicEnforcementEnabled(GuardSettingsStore.DISABLED_INTERVAL_MINUTES),
        )
    }

    @Test
    fun enabledIntervalHonorsWorkManagerMinimum() {
        assertEquals(
            GuardSettingsStore.MINIMUM_INTERVAL_MINUTES,
            GuardSettingsStore.normalizeIntervalMinutes(1L),
        )
        assertTrue(
            GuardSettingsStore.isPeriodicEnforcementEnabled(GuardSettingsStore.MINIMUM_INTERVAL_MINUTES),
        )
    }

    @Test
    fun appPolicyCodecPreservesIndependentControls() {
        val policy = AppPolicy(
            packageName = "com.example.push",
            aurogonEnabled = true,
            aurogonManaged = true,
            autostartEnabled = false,
            autostartManaged = true,
            dozePolicy = AppDozePolicy.RESTRICTED,
            periodicEnforcement = false,
        )

        assertEquals(policy, GuardSettingsStore.decodeAppPolicy(GuardSettingsStore.encodeAppPolicy(policy)))
    }

    @Test
    fun hyperOsAutoUnrestrictedPackagesShareProtectedDefaults() {
        assertTrue(AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES.isNotEmpty())
        AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES.forEach { packageName ->
            assertTrue(AppPolicyDefaults.forPackage(packageName).aurogonEnabled)
            assertTrue(AppPolicyDefaults.forPackage(packageName).autostartEnabled)
            assertEquals(AppDozePolicy.OFF, AppPolicyDefaults.forPackage(packageName).dozePolicy)
            assertTrue(AppPolicyDefaults.forPackage(packageName).periodicEnforcement)
        }

        val other = AppPolicyDefaults.forPackage("com.example.push")
        assertFalse(other.aurogonEnabled)
        assertFalse(other.autostartEnabled)
        assertEquals(AppDozePolicy.UNRESTRICTED, other.dozePolicy)
        assertFalse(other.periodicEnforcement)
    }

    @Test
    fun appMasterStateTracksAurogonRatherThanAdvancedAutostartToggle() {
        assertFalse(
            AppPolicy(
                packageName = "com.example.push",
                autostartEnabled = true,
                autostartManaged = true,
            ).fcmProtectionEnabled,
        )
        assertTrue(
            AppPolicy(
                packageName = "com.example.push",
                aurogonEnabled = true,
                aurogonManaged = true,
            ).fcmProtectionEnabled,
        )
    }

    @Test
    fun legacyVariableLengthPolicyIsNotDecoded() {
        assertEquals(null, GuardSettingsStore.decodeAppPolicy("com.example.push|1|1|default"))
    }
}
