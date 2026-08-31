package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MilletPollingIntervalTest {
    @Test
    fun exposesOnlySupportedSelectorOptions() {
        assertEquals(listOf(2_500L, 5_000L, 10_000L, 30_000L), MilletPollingInterval.OPTIONS_MILLIS)
        MilletPollingInterval.OPTIONS_MILLIS.forEach { intervalMillis ->
            assertTrue(MilletPollingInterval.isSupported(intervalMillis))
            assertEquals(intervalMillis, MilletPollingInterval.normalize(intervalMillis))
        }
    }

    @Test
    fun unsupportedPersistedValueFallsBackToTwoPointFiveSeconds() {
        assertFalse(MilletPollingInterval.isSupported(1_000L))
        assertEquals(MilletPollingInterval.DEFAULT_MILLIS, MilletPollingInterval.normalize(1_000L))
    }

    @Test
    fun formatsHumanReadableLabels() {
        assertEquals("2.5s", MilletPollingInterval.diagnosticLabel(2_500L))
        assertEquals("30s", MilletPollingInterval.diagnosticLabel(30_000L))
    }
}
