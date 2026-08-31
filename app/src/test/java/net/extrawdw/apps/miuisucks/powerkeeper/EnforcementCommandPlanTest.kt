package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementCommandPlanTest {
    @Test
    fun runBackgroundOperationNamesAreExplicitStringValues() {
        assertEquals("RUN_IN_BACKGROUND", EnforcementCommandPlan.RUN_IN_BACKGROUND)
        assertEquals("RUN_ANY_IN_BACKGROUND", EnforcementCommandPlan.RUN_ANY_IN_BACKGROUND)
    }

    @Test
    fun managedAutostartTargetsTheSelectedUserAndBothXiaomiOperations() {
        val commands = EnforcementCommandPlan.policyCommands(
            AppPolicy(
                packageName = "com.example.push",
                autostartManaged = true,
                autostartEnabled = true,
            ),
            userId = 10,
            includeAutostartSwitchOp = true,
        )

        assertEquals(
            listOf(
                appOp(10, "com.example.push", "10008", "allow"),
                appOp(10, "com.example.push", "10053", "allow"),
            ),
            commands,
        )
    }

    @Test
    fun unavailableAutostartSwitchOperationIsOmitted() {
        val commands = EnforcementCommandPlan.policyCommands(
            AppPolicy(
                packageName = "com.example.push",
                autostartManaged = true,
            ),
            userId = 999,
            includeAutostartSwitchOp = false,
        )

        assertEquals(listOf(appOp(999, "com.example.push", "10008", "ignore")), commands)
    }

    @Test
    fun restrictedDozeUsesBothNamedBackgroundOperations() {
        val commands = EnforcementCommandPlan.policyCommands(
            AppPolicy(
                packageName = "com.example.push",
                dozeManaged = true,
                dozePolicy = AppDozePolicy.RESTRICTED,
            ),
            userId = 0,
            includeAutostartSwitchOp = true,
        )

        assertEquals(
            listOf(
                appOp(0, "com.example.push", EnforcementCommandPlan.RUN_IN_BACKGROUND, "ignore"),
                appOp(0, "com.example.push", EnforcementCommandPlan.RUN_ANY_IN_BACKGROUND, "ignore"),
            ),
            commands,
        )
    }

    @Test
    fun dozeWhitelistMatchesTheSelectedPolicy() {
        val unrestricted = AppPolicy(
            packageName = "com.example.push",
            dozeManaged = true,
            dozePolicy = AppDozePolicy.UNRESTRICTED,
        )
        val optimized = unrestricted.copy(dozePolicy = AppDozePolicy.DEFAULT)

        assertEquals(
            SystemServiceCommands.deviceIdle("whitelist", "+com.example.push"),
            EnforcementCommandPlan.dozeWhitelistCommand(unrestricted),
        )
        assertEquals(
            SystemServiceCommands.deviceIdle("whitelist", "-com.example.push"),
            EnforcementCommandPlan.dozeWhitelistCommand(optimized),
        )
        assertEquals(null, EnforcementCommandPlan.dozeWhitelistCommand(unrestricted.copy(dozeManaged = false)))
    }

    @Test
    fun unmanagedPolicyDoesNotProduceCommands() {
        assertTrue(
            EnforcementCommandPlan.policyCommands(
                AppPolicy("com.example.push"),
                userId = 0,
                includeAutostartSwitchOp = true,
            ).isEmpty(),
        )
    }

    @Test
    fun selfProtectionUsesOwnerUserWithoutTouchingPowerKeeper() {
        val applicationId = "net.extrawdw.apps.miuisucks.powerkeeper"
        val commands = EnforcementCommandPlan.selfProtectionCommands(
            applicationId,
            includeAutostartSwitchOp = false,
        )

        assertTrue(commands.contains(appOp(0, applicationId, EnforcementCommandPlan.RUN_IN_BACKGROUND, "allow")))
        assertTrue(commands.contains(appOp(0, applicationId, "10008", "allow")))
        assertFalse(commands.any { "10053" in it.arguments })
        assertFalse(commands.any { "com.miui.powerkeeper" in it.arguments })
        assertTrue(
            commands.contains(
                SystemServiceCommands.activity("set-standby-bucket", "--user", "0", applicationId, "active"),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPackageNames() {
        EnforcementCommandPlan.policyCommands(
            AppPolicy("com.example;rm"),
            userId = 0,
            includeAutostartSwitchOp = true,
        )
    }

    private fun appOp(userId: Int, packageName: String, operation: String, mode: String) =
        SystemServiceCommands.appOps(
            "set",
            "--user",
            userId.toString(),
            packageName,
            operation,
            mode,
        )
}
