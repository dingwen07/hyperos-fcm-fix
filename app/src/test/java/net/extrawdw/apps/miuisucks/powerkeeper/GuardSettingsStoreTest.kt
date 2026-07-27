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
            appEnabled = true,
            aurogonEnabled = true,
            aurogonManaged = true,
            autostartEnabled = false,
            autostartManaged = true,
            dozePolicy = AppDozePolicy.RESTRICTED,
            selectedDozePolicy = AppDozePolicy.RESTRICTED,
            periodicEnforcement = false,
        )

        assertEquals(policy, GuardSettingsStore.decodeAppPolicy(GuardSettingsStore.encodeAppPolicy(policy)))
    }

    @Test
    fun hyperOsAutoUnrestrictedPackagesShareProtectedDefaults() {
        assertTrue(AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES.isNotEmpty())
        assertTrue("com.tencent.mm" in AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES)
        assertTrue("org.telegram.messenger" in AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES)
        AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES.forEach { packageName ->
            assertTrue(AppPolicyDefaults.forPackage(packageName).appEnabled)
            assertTrue(AppPolicyDefaults.forPackage(packageName).aurogonEnabled)
            assertTrue(AppPolicyDefaults.forPackage(packageName).autostartEnabled)
            assertEquals(AppDozePolicy.DEFAULT, AppPolicyDefaults.forPackage(packageName).dozePolicy)
            assertEquals(AppDozePolicy.DEFAULT, AppPolicyDefaults.forPackage(packageName).selectedDozePolicy)
            assertTrue(AppPolicyDefaults.forPackage(packageName).periodicEnforcement)
        }

        val other = AppPolicyDefaults.forPackage("com.example.push")
        assertFalse(other.appEnabled)
        assertFalse(other.aurogonEnabled)
        assertFalse(other.autostartEnabled)
        assertEquals(AppDozePolicy.OFF, other.dozePolicy)
        assertEquals(AppDozePolicy.DEFAULT, other.selectedDozePolicy)
        assertFalse(other.periodicEnforcement)
    }

    @Test
    fun appEnabledStateIsIndependentOfDetailToggles() {
        assertFalse(
            AppPolicy(
                packageName = "com.example.push",
                appEnabled = false,
                aurogonEnabled = true,
                autostartEnabled = true,
                autostartManaged = true,
            ).appEnabled,
        )
        assertTrue(
            AppPolicy(
                packageName = "com.example.push",
                appEnabled = true,
                aurogonEnabled = false,
                aurogonManaged = true,
            ).appEnabled,
        )
    }

    @Test
    fun disablingAppRemembersAllDetailSettings() {
        val configured = AppPolicy(
            packageName = "com.example.push",
            appEnabled = true,
            aurogonEnabled = false,
            aurogonManaged = true,
            autostartEnabled = false,
            autostartManaged = true,
            dozePolicy = AppDozePolicy.UNRESTRICTED,
            periodicEnforcement = true,
        )
        val disabled = configured.withAppEnabled(false)

        assertFalse(disabled.appEnabled)
        assertEquals(configured.copy(appEnabled = false), disabled)
        assertEquals(configured, disabled.withAppEnabled(true))
    }

    @Test
    fun firstEnableTurnsOnAurogonAndAutostartDefaults() {
        val enabled = AppPolicyDefaults.forPackage("com.example.push").withAppEnabled(true)

        assertTrue(enabled.appEnabled)
        assertTrue(enabled.aurogonEnabled)
        assertTrue(enabled.aurogonManaged)
        assertTrue(enabled.autostartEnabled)
        assertTrue(enabled.autostartManaged)
        assertEquals(AppDozePolicy.OFF, enabled.dozePolicy)
        assertEquals(AppDozePolicy.DEFAULT, enabled.selectedDozePolicy)
    }

    @Test
    fun doNotChangePreservesSelectionAndRestoringManagementUsesIt() {
        val unrestricted = AppPolicy("com.example.push").withDozePolicy(AppDozePolicy.UNRESTRICTED)
        val unchanged = unrestricted.withDozePolicy(AppDozePolicy.OFF)

        assertEquals(AppDozePolicy.OFF, unchanged.dozePolicy)
        assertEquals(AppDozePolicy.UNRESTRICTED, unchanged.selectedDozePolicy)
        assertEquals(
            unrestricted,
            unchanged.withDozePolicy(unchanged.selectedDozePolicy),
        )
    }

    @Test
    fun fullAndPeriodicPolicySelectionsAreGuardedByAppEnabledState() {
        val enabledPeriodic = AppPolicy(
            packageName = "com.example.enabled.periodic",
            appEnabled = true,
            aurogonEnabled = true,
            aurogonManaged = true,
            periodicEnforcement = true,
        )
        val enabledManualOnly = AppPolicy(
            packageName = "com.example.enabled.manual",
            appEnabled = true,
            aurogonEnabled = false,
            aurogonManaged = true,
            periodicEnforcement = false,
        )
        val disabledPeriodic = AppPolicy(
            packageName = "com.example.disabled.periodic",
            appEnabled = false,
            aurogonEnabled = true,
            aurogonManaged = true,
            autostartEnabled = true,
            autostartManaged = true,
            dozePolicy = AppDozePolicy.UNRESTRICTED,
            periodicEnforcement = true,
        )
        val settings = GuardSettings(
            appPolicies = listOf(enabledPeriodic, enabledManualOnly, disabledPeriodic)
                .associateBy(AppPolicy::packageName),
            intervalMinutes = GuardSettingsStore.MINIMUM_INTERVAL_MINUTES,
            androidUsers = emptyList(),
        )

        assertEquals(
            listOf(enabledManualOnly, enabledPeriodic),
            settings.enabledAppPolicies,
        )
        assertEquals(listOf(enabledPeriodic), settings.periodicallyEnforcedAppPolicies)
        assertEquals(listOf(enabledPeriodic.packageName), settings.aurogonEnabledPackages)
        assertEquals(
            listOf(disabledPeriodic, enabledManualOnly, enabledPeriodic).map(AppPolicy::packageName).sorted(),
            settings.aurogonManagedPackages,
        )
    }

    @Test
    fun previousEightFieldPolicyIsNotDecoded() {
        assertEquals(null, GuardSettingsStore.decodeAppPolicy("com.example.push|1|1|1|1|1|default|1"))
    }
}
