package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementScriptTest {
    private val applicationId = "net.extrawdw.apps.miuisucks.powerkeeper"
    private val autoUnrestrictedPackage = AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES.first()
    private val autoUnrestrictedPolicy = AppPolicyDefaults.forPackage(autoUnrestrictedPackage)

    @Test
    fun scriptTargetsOnlyOwnerAndXSpace() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId)

        assertTrue(script.contains("for target_user in 0 999"))
        assertFalse(script.contains("--user 16"))
        assertFalse(script.contains("--user all"))
        assertFalse(script.contains("--uid"))
        assertFalse(script.contains("setUidMode"))
    }

    @Test
    fun customUserSelectionReplacesDefaultUsers() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId, targetUsers = listOf(10))

        assertTrue(script.contains("for target_user in 10"))
        assertFalse(script.contains("for target_user in 0 999"))
    }

    @Test
    fun emptyUserSelectionDoesNotMutateConfiguredApps() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId, targetUsers = emptyList())

        assertTrue(script.contains("No Android users selected"))
        assertFalse(script.contains("'$autoUnrestrictedPackage' RUN_ANY_IN_BACKGROUND"))
        assertFalse(script.contains("'$autoUnrestrictedPackage' '10008'"))
    }

    @Test
    fun optimizedBatteryPolicyAllowsBackgroundButRemovesUnrestrictedAllowlist() {
        val policy = AppPolicy("com.example.push", dozePolicy = AppDozePolicy.DEFAULT)
        val script = EnforcementScript.build(listOf(policy), applicationId)

        assertTrue(script.contains("'com.example.push' RUN_ANY_IN_BACKGROUND 'allow'"))
        assertTrue(script.contains("whitelist '-com.example.push'"))
    }

    @Test
    fun unrestrictedBatteryPolicyAddsUnrestrictedAllowlist() {
        val policy = AppPolicy("com.example.push", dozePolicy = AppDozePolicy.UNRESTRICTED)
        val script = EnforcementScript.build(listOf(policy), applicationId)

        assertTrue(script.contains("'com.example.push' RUN_ANY_IN_BACKGROUND 'allow'"))
        assertTrue(script.contains("whitelist '+com.example.push'"))
    }

    @Test
    fun configuredAutostartWritesBothXiaomiAppOps() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId)

        assertTrue(script.contains("'$autoUnrestrictedPackage' '10008' 'allow'"))
        assertTrue(script.contains("'$autoUnrestrictedPackage' '10053' 'allow'"))
    }

    @Test
    fun disablingManagedAutostartWritesIgnore() {
        val policy = AppPolicy("com.example.push", autostartManaged = true)
        val script = EnforcementScript.build(listOf(policy), applicationId)

        assertTrue(script.contains("'com.example.push' '10008' 'ignore'"))
        assertTrue(script.contains("'com.example.push' '10053' 'ignore'"))
    }

    @Test
    fun selfProtectionEnablesBothXiaomiAutostartAppOpsForOwner() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId)

        assertTrue(script.contains("cmd appops set --user 0 '$applicationId' '10008' allow"))
        assertTrue(script.contains("cmd appops set --user 0 '$applicationId' '10053' allow"))
        assertFalse(script.contains("cmd appops set --user 999 '$applicationId' '10008' allow"))
    }

    @Test
    fun restrictedPolicyIgnoresArbitraryAppBackgroundOps() {
        val policy = AppPolicy("com.example.push", dozePolicy = AppDozePolicy.RESTRICTED)
        val script = EnforcementScript.build(listOf(policy), applicationId)

        assertTrue(script.contains("'com.example.push' RUN_IN_BACKGROUND 'ignore'"))
        assertTrue(script.contains("'com.example.push' RUN_ANY_IN_BACKGROUND 'ignore'"))
    }

    @Test
    fun offUnmanagedPolicyDoesNotMutateApp() {
        val script = EnforcementScript.build(listOf(AppPolicy("com.example.push")), applicationId)

        assertTrue(script.contains("No per-app Autostart or AOSP battery policies selected"))
        assertFalse(script.contains("'com.example.push' RUN_ANY_IN_BACKGROUND"))
        assertFalse(script.contains("'com.example.push' '10008'"))
    }

    @Test
    fun unresolvedPoliciesQueryInstalledPackagesOnlyOncePerUser() {
        val policies = listOf(
            AppPolicy("com.example.one", autostartEnabled = true, autostartManaged = true),
            AppPolicy("com.example.two", autostartEnabled = true, autostartManaged = true),
        )
        val script = EnforcementScript.build(policies, applicationId, targetUsers = listOf(0, 999))

        assertEquals(1, script.windowed("pm list packages --user '0'".length).count { it == "pm list packages --user '0'" })
        assertEquals(1, script.windowed("pm list packages --user '999'".length).count { it == "pm list packages --user '999'" })
        assertFalse(script.contains("grep -Fxq"))
        assertFalse(script.contains("pm list packages --user \"\$1\" \"\$2\""))
    }

    @Test
    fun resolvedBatchDoesNotQueryPackagesAndTargetsOnlyInstalledUsers() {
        val policy = AppPolicy(
            "com.example.push",
            autostartEnabled = true,
            autostartManaged = true,
            dozePolicy = AppDozePolicy.DEFAULT,
        )
        val script = EnforcementScript.build(
            policies = listOf(policy),
            applicationId = applicationId,
            targetUsers = listOf(0, 999),
            installedPackagesByUser = mapOf(
                0 to setOf(policy.packageName),
                999 to emptySet(),
            ),
        )

        assertFalse(script.contains("pm list packages"))
        assertTrue(script.contains("--user \"0\" '${policy.packageName}' '10008' 'allow'"))
        assertFalse(script.contains("--user \"999\" '${policy.packageName}' '10008'"))
        assertTrue(script.contains("${policy.packageName} user 999: skipped (not installed)"))
    }

    @Test
    fun intermediateBatchOmitsSelfProtectionAndWriteSettings() {
        val script = EnforcementScript.build(
            policies = listOf(autoUnrestrictedPolicy),
            applicationId = applicationId,
            includeSelfProtection = false,
            includeWriteSettings = false,
            installedPackagesByUser = mapOf(0 to setOf(autoUnrestrictedPackage)),
        )

        assertFalse(script.contains("Self-protection"))
        assertFalse(script.contains("cmd appops write-settings"))
    }

    @Test
    fun resolvedBatchEmitsPerAppProgressIncludingUnmanagedApps() {
        val policies = listOf(
            AppPolicy("com.example.managed", autostartEnabled = true, autostartManaged = true),
            AppPolicy("com.example.unmanaged"),
        )
        val script = EnforcementScript.build(
            policies = policies,
            applicationId = applicationId,
            targetUsers = listOf(0),
            installedPackagesByUser = mapOf(0 to policies.map(AppPolicy::packageName).toSet()),
            progressStartIndex = 40,
        )

        assertTrue(script.contains("${EnforcementScript.PROGRESS_PREFIX}41"))
        assertTrue(script.contains("${EnforcementScript.PROGRESS_PREFIX}42"))
        assertTrue(script.contains("com.example.unmanaged: no per-app policy selected"))

        val process = ProcessBuilder("/bin/sh", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(error, 0, process.waitFor())
    }

    @Test
    fun scriptDoesNotAlterPowerKeeper() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId)

        assertFalse(script.contains("com.miui.powerkeeper"))
        assertFalse(script.contains("force-stop --user"))
    }

    @Test
    fun targetedPolicyDoesNotApplyManagerSelfProtection() {
        val policy = AppPolicy("com.example.push", autostartEnabled = true, autostartManaged = true)
        val script = EnforcementScript.build(
            listOf(policy),
            applicationId,
            includeSelfProtection = false,
        )

        assertTrue(script.contains("'com.example.push' '10008' 'allow'"))
        assertFalse(script.contains("'$applicationId' '10008' allow"))
        assertFalse(script.contains("whitelist '+$applicationId'"))
    }

    @Test
    fun generatedScriptHasValidShellSyntax() {
        val policies = listOf(
            autoUnrestrictedPolicy,
            AppPolicy("com.example.default", dozePolicy = AppDozePolicy.DEFAULT),
            AppPolicy(
                "com.example.restricted",
                autostartManaged = true,
                dozePolicy = AppDozePolicy.RESTRICTED,
            ),
        )
        val process = ProcessBuilder("/bin/sh", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(EnforcementScript.build(policies, applicationId)) }
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(error, 0, process.waitFor())
    }
}
