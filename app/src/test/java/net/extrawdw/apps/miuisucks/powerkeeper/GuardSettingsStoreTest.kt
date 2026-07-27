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
            autostartManaged = true,
            autostartEnabled = false,
            dozeManaged = true,
            dozePolicy = AppDozePolicy.RESTRICTED,
            periodicEnforcement = false,
        )

        val encoded = GuardSettingsStore.encodeAppPolicy(policy)
        assertEquals(8, encoded.split('|').size)
        assertEquals(policy, GuardSettingsStore.decodeAppPolicy(encoded))
    }

    @Test
    fun hyperOsAutoUnrestrictedPackagesShareProtectedDefaults() {
        assertTrue(AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES.isNotEmpty())
        assertTrue("com.tencent.mm" in AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES)
        assertTrue("org.telegram.messenger" in AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES)
        AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES.forEach { packageName ->
            assertTrue(AppPolicyDefaults.forPackage(packageName).appEnabled)
            assertTrue(AppPolicyDefaults.forPackage(packageName).aurogonEnabled)
            assertTrue(AppPolicyDefaults.forPackage(packageName).autostartManaged)
            assertTrue(AppPolicyDefaults.forPackage(packageName).autostartEnabled)
            assertTrue(AppPolicyDefaults.forPackage(packageName).dozeManaged)
            assertEquals(AppDozePolicy.DEFAULT, AppPolicyDefaults.forPackage(packageName).dozePolicy)
            assertTrue(AppPolicyDefaults.forPackage(packageName).periodicEnforcement)
        }

        val other = AppPolicyDefaults.forPackage("com.example.push")
        assertFalse(other.appEnabled)
        assertFalse(other.aurogonEnabled)
        assertFalse(other.autostartManaged)
        assertFalse(other.autostartEnabled)
        assertFalse(other.dozeManaged)
        assertEquals(AppDozePolicy.DEFAULT, other.dozePolicy)
        assertFalse(other.periodicEnforcement)
    }

    @Test
    fun appEnabledStateIsIndependentOfDetailToggles() {
        assertFalse(
            AppPolicy(
                packageName = "com.example.push",
                appEnabled = false,
                aurogonEnabled = true,
                autostartManaged = true,
                autostartEnabled = true,
            ).appEnabled,
        )
        assertTrue(
            AppPolicy(
                packageName = "com.example.push",
                appEnabled = true,
                aurogonEnabled = false,
            ).appEnabled,
        )
    }

    @Test
    fun disablingAppRemembersAllDetailSettings() {
        val configured = AppPolicy(
            packageName = "com.example.push",
            appEnabled = true,
            aurogonEnabled = false,
            autostartManaged = true,
            autostartEnabled = false,
            dozeManaged = true,
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
        val enabled = AppPolicyDefaults.forPackage("com.example.push")
            .withAppEnabled(true, initializeDefaults = true)

        assertTrue(enabled.appEnabled)
        assertTrue(enabled.aurogonEnabled)
        assertTrue(enabled.autostartManaged)
        assertTrue(enabled.autostartEnabled)
        assertFalse(enabled.dozeManaged)
        assertEquals(AppDozePolicy.DEFAULT, enabled.dozePolicy)
    }

    @Test
    fun managementSwitchesPreserveSelectedValues() {
        val configured = AppPolicy(
            packageName = "com.example.push",
            autostartManaged = true,
            autostartEnabled = false,
            dozeManaged = true,
            dozePolicy = AppDozePolicy.UNRESTRICTED,
        )
        val unmanaged = configured.copy(autostartManaged = false, dozeManaged = false)

        assertFalse(unmanaged.autostartEnabled)
        assertEquals(AppDozePolicy.UNRESTRICTED, unmanaged.dozePolicy)
    }

    @Test
    fun appOffResetsBatteryOnlyWhenBatteryManagementWasEnabled() {
        val managed = AppPolicy(
            packageName = "com.example.push",
            autostartManaged = true,
            autostartEnabled = true,
            dozeManaged = true,
            dozePolicy = AppDozePolicy.UNRESTRICTED,
        ).appOffCleanupPolicy()

        assertEquals(
            AppPolicy(
                packageName = "com.example.push",
                appEnabled = true,
                dozeManaged = true,
                dozePolicy = AppDozePolicy.DEFAULT,
            ),
            managed,
        )
        assertEquals(null, AppPolicy("com.example.push").appOffCleanupPolicy())
    }

    @Test
    fun fullAndPeriodicPolicySelectionsAreGuardedByAppEnabledState() {
        val enabledPeriodic = AppPolicy(
            packageName = "com.example.enabled.periodic",
            appEnabled = true,
            aurogonEnabled = true,
            periodicEnforcement = true,
        )
        val enabledManualOnly = AppPolicy(
            packageName = "com.example.enabled.manual",
            appEnabled = true,
            aurogonEnabled = false,
            periodicEnforcement = false,
        )
        val disabledPeriodic = AppPolicy(
            packageName = "com.example.disabled.periodic",
            appEnabled = false,
            aurogonEnabled = true,
            autostartEnabled = true,
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
    }

    @Test
    fun previousSevenFieldPolicyIsNotDecoded() {
        assertEquals(
            null,
            GuardSettingsStore.decodeAppPolicy("com.example.push|1|1|1|default|default|1"),
        )
    }
}
