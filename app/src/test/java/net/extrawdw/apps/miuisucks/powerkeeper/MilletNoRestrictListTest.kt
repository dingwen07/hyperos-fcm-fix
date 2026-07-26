package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Test

class MilletNoRestrictListTest {
    @Test
    fun appendsGmsAfterEveryExistingEntry() {
        val existing = "com.android.vending, net.extrawdw.apps.notisync"

        assertEquals(
            listOf("com.android.vending", "net.extrawdw.apps.notisync", "com.google.android.gms"),
            MilletNoRestrictList.appendGms(existing),
        )
    }

    @Test
    fun leavesExistingGmsEntryInPlace() {
        val existing = "com.google.android.gms, com.android.vending"

        assertEquals(
            listOf("com.google.android.gms", "com.android.vending"),
            MilletNoRestrictList.appendGms(existing),
        )
    }

    @Test
    fun treatsMissingSettingAsAnEmptyList() {
        assertEquals(
            listOf("com.google.android.gms"),
            MilletNoRestrictList.appendGms("null"),
        )
    }

    @Test
    fun trimsAndDeduplicatesWithoutReordering() {
        val existing = " com.android.vending,com.android.vending, net.extrawdw.apps.notisync "

        assertEquals(
            "com.android.vending, net.extrawdw.apps.notisync, com.google.android.gms",
            MilletNoRestrictList.serialize(MilletNoRestrictList.appendGms(existing)),
        )
    }
}
