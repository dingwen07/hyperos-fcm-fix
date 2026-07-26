package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUserSelectionTest {
    @Test
    fun parsesOwnerXSpaceAndNamedSecondaryUsers() {
        val output = """
            Users:
                UserInfo{0:Owner:c13} running
                UserInfo{10:Work: profile:30} running
                UserInfo{999:XSpace:800010} running
        """.trimIndent()

        assertEquals(
            listOf(
                DiscoveredAndroidUser(0, "Owner"),
                DiscoveredAndroidUser(10, "Work: profile"),
                DiscoveredAndroidUser(999, "XSpace"),
            ),
            AndroidUserSelections.parsePmListUsers(output),
        )
    }

    @Test
    fun defaultsOnlyOwnerAndXSpaceToEnabled() {
        val merged = AndroidUserSelections.merge(
            discovered = listOf(
                DiscoveredAndroidUser(0, "Owner"),
                DiscoveredAndroidUser(10, "Work"),
                DiscoveredAndroidUser(999, "XSpace"),
            ),
            persisted = emptyList(),
        )

        assertTrue(merged.first { it.userId == 0 }.enabled)
        assertFalse(merged.first { it.userId == 10 }.enabled)
        assertTrue(merged.first { it.userId == 999 }.enabled)
    }

    @Test
    fun refreshPreservesSelectionAndUpdatesName() {
        val merged = AndroidUserSelections.merge(
            discovered = listOf(DiscoveredAndroidUser(10, "Renamed work profile")),
            persisted = listOf(AndroidUserSelection(10, "Old name", enabled = true)),
        )

        assertEquals(
            listOf(AndroidUserSelection(10, "Renamed work profile", enabled = true)),
            merged,
        )
    }

    @Test
    fun persistenceRoundTripPreservesUnicodeNamesAndSelection() {
        val users = listOf(
            AndroidUserSelection(0, "机主", enabled = true),
            AndroidUserSelection(10, "Work | profile", enabled = false),
        )

        assertEquals(users, AndroidUserSelections.decode(AndroidUserSelections.encode(users)))
    }
}
