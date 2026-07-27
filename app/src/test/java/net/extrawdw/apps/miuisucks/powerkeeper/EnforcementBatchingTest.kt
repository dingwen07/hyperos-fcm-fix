package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementBatchingTest {
    @Test
    fun twoHundredFiftySixEnabledPoliciesAreSplitIntoBoundedBatches() {
        val policies = (255 downTo 0).map { index ->
            AppPolicy(
                packageName = "com.example.push%03d".format(index),
                appEnabled = true,
                autostartEnabled = true,
                autostartManaged = true,
            )
        }

        val batches = EnforcementBatching.split(policies)

        assertEquals(16, batches.size)
        assertTrue(batches.all { it.size <= EnforcementBatching.MAX_POLICIES_PER_BATCH })
        assertEquals(policies.map(AppPolicy::packageName).sorted(), batches.flatten().map(AppPolicy::packageName))
        batches.forEachIndexed { index, batch ->
            val installed = batch.map(AppPolicy::packageName).toSet()
            val script = EnforcementScript.build(
                policies = batch,
                applicationId = "net.extrawdw.apps.miuisucks.powerkeeper",
                targetUsers = listOf(0, 999),
                includeSelfProtection = index == 0,
                includeWriteSettings = index == batches.lastIndex,
                installedPackagesByUser = mapOf(0 to installed, 999 to installed),
                progressStartIndex = index * EnforcementBatching.MAX_POLICIES_PER_BATCH,
            )
            assertTrue("Batch script was unexpectedly large: ${script.length}", script.length < 32_000)
        }
    }

    @Test
    fun disabledPoliciesAreNotBatched() {
        val enabled = AppPolicy("com.example.enabled", appEnabled = true)
        val disabled = AppPolicy("com.example.disabled", appEnabled = false)

        assertEquals(listOf(listOf(enabled)), EnforcementBatching.split(listOf(disabled, enabled)))
    }
}
