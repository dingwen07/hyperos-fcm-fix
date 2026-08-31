package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmReconnectPolicyTest {
    @Test
    fun mandatoryReconnectIsDueAtTenMinuteBoundary() {
        val nowElapsed = 123_456L
        val nextElapsed = FcmReconnectPolicy.nextMandatoryReconnectElapsed(nowElapsed)

        assertEquals(nowElapsed + 10L * 60_000L, nextElapsed)
        assertFalse(FcmReconnectPolicy.isMandatoryReconnectDue(nextElapsed - 1L, nextElapsed))
        assertTrue(FcmReconnectPolicy.isMandatoryReconnectDue(nextElapsed, nextElapsed))
    }
}
