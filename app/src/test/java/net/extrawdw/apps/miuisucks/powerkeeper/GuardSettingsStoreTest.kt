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
    fun fcmReconnectDefaultsEnabled() {
        assertTrue(GuardSettingsStore.DEFAULT_FCM_RECONNECT_ENABLED)
    }

    @Test
    fun appPolicyCodecPreservesIndependentControls() {
        val policy = AppPolicy(
            packageName = "com.example.push",
            appEnabled = true,
            aurogonEnabled = true,
            autoUnstopEnabled = true,
            autostartManaged = true,
            autostartEnabled = false,
            dozeManaged = true,
            dozePolicy = AppDozePolicy.RESTRICTED,
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
            assertTrue(AppPolicyDefaults.forPackage(packageName).autoUnstopEnabled)
            assertTrue(AppPolicyDefaults.forPackage(packageName).autostartManaged)
            assertTrue(AppPolicyDefaults.forPackage(packageName).autostartEnabled)
            assertTrue(AppPolicyDefaults.forPackage(packageName).dozeManaged)
            assertEquals(AppDozePolicy.DEFAULT, AppPolicyDefaults.forPackage(packageName).dozePolicy)
        }

        val other = AppPolicyDefaults.forPackage("com.example.push")
        assertFalse(other.appEnabled)
        assertFalse(other.aurogonEnabled)
        assertFalse(other.autoUnstopEnabled)
        assertFalse(other.autostartManaged)
        assertFalse(other.autostartEnabled)
        assertFalse(other.dozeManaged)
        assertEquals(AppDozePolicy.DEFAULT, other.dozePolicy)
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
        assertTrue(enabled.autoUnstopEnabled)
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
    fun enforcementSelectionsAreGuardedByAppEnabledState() {
        val enabledAurogon = AppPolicy(
            packageName = "com.example.enabled.aurogon",
            appEnabled = true,
            aurogonEnabled = true,
            autoUnstopEnabled = true,
        )
        val enabledUnstop = AppPolicy(
            packageName = "com.example.enabled.unstop",
            appEnabled = true,
            aurogonEnabled = false,
            autoUnstopEnabled = true,
        )
        val disabled = AppPolicy(
            packageName = "com.example.disabled",
            appEnabled = false,
            aurogonEnabled = true,
            autoUnstopEnabled = true,
            autostartEnabled = true,
            dozePolicy = AppDozePolicy.UNRESTRICTED,
        )
        val settings = GuardSettings(
            appPolicies = listOf(enabledAurogon, enabledUnstop, disabled)
                .associateBy(AppPolicy::packageName),
            intervalMinutes = GuardSettingsStore.MINIMUM_INTERVAL_MINUTES,
            milletPollingIntervalMillis = MilletPollingInterval.DEFAULT_MILLIS,
            fcmReconnectEnabled = GuardSettingsStore.DEFAULT_FCM_RECONNECT_ENABLED,
            androidUsers = emptyList(),
        )

        assertEquals(
            listOf(enabledAurogon, enabledUnstop),
            settings.enabledAppPolicies,
        )
        assertEquals(listOf(enabledAurogon.packageName), settings.aurogonEnabledPackages)
        assertEquals(
            listOf(enabledAurogon.packageName, enabledUnstop.packageName),
            settings.autoUnstopPackages,
        )
    }

    @Test
    fun previousNineFieldPolicyIgnoresRemovedPeriodicFlag() {
        val decoded = GuardSettingsStore.decodeLegacyAppPolicy(
            "com.example.push|1|1|1|1|0|1|restricted|0",
        )

        assertEquals(
            AppPolicy(
                packageName = "com.example.push",
                appEnabled = true,
                aurogonEnabled = true,
                autoUnstopEnabled = true,
                autostartManaged = true,
                autostartEnabled = false,
                dozeManaged = true,
                dozePolicy = AppDozePolicy.RESTRICTED,
            ),
            decoded,
        )
    }

    @Test
    fun releasedEightFieldPolicyDefaultsAutoUnstopToAppEnabled() {
        val decoded = GuardSettingsStore.decodeLegacyAppPolicy("com.example.push|1|1|1|0|1|default|1")

        assertEquals(true, decoded?.appEnabled)
        assertEquals(true, decoded?.aurogonEnabled)
        assertEquals(true, decoded?.autoUnstopEnabled)
        assertEquals(true, decoded?.autostartManaged)
        assertEquals(false, decoded?.autostartEnabled)
        assertEquals(true, decoded?.dozeManaged)
        assertEquals(AppDozePolicy.DEFAULT, decoded?.dozePolicy)
    }

    @Test
    fun malformedPolicyIsNotDecoded() {
        assertEquals(
            null,
            GuardSettingsStore.decodeLegacyAppPolicy("com.example.push|1|1|1|default|default|1"),
        )
    }
}
