package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MilletPollingIntervalTest {
    @Test
    fun exposesOnlySupportedSelectorOptions() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L), MilletPollingInterval.OPTIONS_MILLIS)
        MilletPollingInterval.OPTIONS_MILLIS.forEach { intervalMillis ->
            assertTrue(MilletPollingInterval.isSupported(intervalMillis))
            assertEquals(intervalMillis, MilletPollingInterval.normalize(intervalMillis))
        }
    }

    @Test
    fun unsupportedPersistedValueFallsBackToThirtySeconds() {
        assertFalse(MilletPollingInterval.isSupported(1_000L))
        assertEquals(MilletPollingInterval.DEFAULT_MILLIS, MilletPollingInterval.normalize(1_000L))
    }

    @Test
    fun formatsHumanReadableLabels() {
        assertEquals("30s", MilletPollingInterval.diagnosticLabel(30_000L))
        assertEquals("120s", MilletPollingInterval.diagnosticLabel(120_000L))
    }
}
