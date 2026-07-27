package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AurogonConfigTest {
    private val manager = "net.extrawdw.apps.miuisucks.powerkeeper"
    private val target = "com.example.push"

    @Test
    fun emptyValueGetsNarrowManagedRule() {
        val merged = AurogonConfig.merge(null, listOf(target), manager)

        assertTrue(merged.startsWith("broadcastctrl:true#$manager/"))
        assertTrue(merged.contains("#$target/${AurogonConfig.FCM_RECEIVE_ACTION}"))
        assertTrue(AurogonConfig.rulesEffective(merged, listOf(target)))
    }

    @Test
    fun unknownCloudSectionsArePreserved() {
        val original = "extrememode:true;doubleapp:false"
        val merged = AurogonConfig.merge(original, listOf(target), manager)

        assertTrue(merged.startsWith("$original;"))
        assertEquals(merged, AurogonConfig.merge(merged, listOf(target), manager))
    }

    @Test
    fun disablingPackageRemovesManagedOverlay() {
        val enabled = AurogonConfig.merge(null, listOf(target), manager)
        val disabled = AurogonConfig.merge(enabled, emptyList(), manager)

        assertFalse(disabled.contains(target))
        assertFalse(AurogonConfig.rulesEffective(disabled, listOf(target)))
    }

    @Test
    fun existingFalseFlagIsNeverTightened() {
        val original = "extrememode:true;broadcastctrl:false#com.other/ALL"
        val merged = AurogonConfig.merge(original, listOf(target), manager)

        assertEquals(original, merged)
        assertTrue(AurogonConfig.rulesEffective(merged, listOf(target)))
    }

    @Test
    fun existingPackageActionIsRetainedWhenFcmIsAdded() {
        val original = "broadcastctrl:true#$target/android.intent.action.BOOT_COMPLETED"
        val merged = AurogonConfig.merge(original, listOf(target), manager)

        assertTrue(merged.contains("$target/android.intent.action.BOOT_COMPLETED,${AurogonConfig.FCM_RECEIVE_ACTION}"))
    }

    @Test
    fun cloudAllMappingNeedsNoManagedOverride() {
        val original = "broadcastctrl:true#$target/ALL"
        val merged = AurogonConfig.merge(original, listOf(target), manager)

        assertEquals(original, merged)
    }

    @Test
    fun partialActionNameDoesNotCountAsFcmRule() {
        val original = "broadcastctrl:true#$target/${AurogonConfig.FCM_RECEIVE_ACTION}.FAKE"
        val merged = AurogonConfig.merge(original, listOf(target), manager)

        assertTrue(merged.contains("${AurogonConfig.FCM_RECEIVE_ACTION}.FAKE,${AurogonConfig.FCM_RECEIVE_ACTION}"))
        assertTrue(AurogonConfig.rulesEffective(merged, listOf(target)))
    }
}
