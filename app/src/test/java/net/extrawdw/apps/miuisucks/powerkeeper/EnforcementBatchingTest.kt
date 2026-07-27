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
                autostartManaged = true,
                autostartEnabled = true,
            )
        }

        val batches = EnforcementBatching.split(policies)

        assertEquals(16, batches.size)
        assertTrue(batches.all { it.size <= EnforcementBatching.MAX_POLICIES_PER_BATCH })
        assertEquals(
            policies.map(AppPolicy::packageName).sorted(),
            batches.flatten().map(AppPolicy::packageName),
        )
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
    fun policiesAreSortedBeforeBatching() {
        val second = AppPolicy("com.example.second", appEnabled = true)
        val first = AppPolicy("com.example.first", appEnabled = true)

        assertEquals(listOf(listOf(first, second)), EnforcementBatching.split(listOf(second, first)))
    }
}
