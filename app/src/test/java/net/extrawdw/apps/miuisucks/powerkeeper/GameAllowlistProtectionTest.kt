package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAllowlistProtectionTest {
    private val gms = MilletNoRestrictList.GMS_PACKAGE

    @Test
    fun parserUsesExactUnderscoreDelimitedPackages() {
        assertFalse(GameAllowlist.containsGms("null"))
        assertFalse(GameAllowlist.containsGms("${gms}.extra"))
        assertFalse(GameAllowlist.containsGms(" $gms"))
        assertTrue(GameAllowlist.containsGms("com.example.game_${gms}_"))
        assertEquals("com.example.game_$gms", GameAllowlist.appendGms("com.example.game"))
        assertEquals("com.example.game", GameAllowlist.removeGms("${gms}_com.example.game_$gms"))
        assertEquals(" com.example.game _${gms}", GameAllowlist.appendGms(" com.example.game "))
    }

    @Test
    fun defaultOffHasNoSettingOrBroadcastOperations() {
        val fixture = Fixture()
        fixture.protection.configure(enabled = false, removeGmsOnDisable = false)
        fixture.protection.maintain()
        fixture.protection.prepareReconnect().getOrThrow()
        assertEquals(emptyList<String>(), fixture.events)
    }

    @Test
    fun repairPreservesOtherEntriesAndThawsBeforeReconnect() {
        val fixture = Fixture("com.example.game")
        val result = fixture.protection.configure(enabled = true, removeGmsOnDisable = false)
        assertFalse(result.failed)
        assertEquals("com.example.game_$gms", fixture.raw)
        assertEquals(listOf("read", "read", "write", "read", "thaw", "reconnect"), fixture.events)

        fixture.events.clear()
        fixture.protection.maintain()
        fixture.protection.configure(enabled = true, removeGmsOnDisable = false)
        assertEquals(listOf("read", "read"), fixture.events)
    }

    @Test
    fun oemClearIsRepairedOnNextPoll() {
        val fixture = Fixture()
        fixture.protection.configure(enabled = true, removeGmsOnDisable = false)
        fixture.raw = "com.example.othergame"
        fixture.events.clear()
        fixture.protection.maintain()
        assertEquals("com.example.othergame_$gms", fixture.raw)
        assertEquals(listOf("read", "read", "write", "read", "thaw", "reconnect"), fixture.events)
    }

    @Test
    fun newServiceSignalsObserverWithoutChangingExistingMembership() {
        val original = "com.example.game_${gms}_"
        val fixture = Fixture(original)
        fixture.protection.configure(enabled = true, removeGmsOnDisable = false)
        assertEquals(original.dropLast(1), fixture.raw)
        assertEquals(effectivePackages(original), effectivePackages(fixture.raw))
        fixture.protection.configure(enabled = false, removeGmsOnDisable = false)
        fixture.protection.configure(enabled = true, removeGmsOnDisable = false)
        assertEquals(original, fixture.raw)
    }

    @Test
    fun disablePreservesPreexistingGmsAndOtherPackages() {
        val fixture = Fixture("com.example.game_$gms")
        fixture.protection.configure(enabled = true, removeGmsOnDisable = false)
        val applied = fixture.raw
        fixture.events.clear()
        fixture.protection.configure(enabled = false, removeGmsOnDisable = false)
        fixture.protection.maintain()
        assertEquals(applied, fixture.raw)
        assertEquals(emptyList<String>(), fixture.events)
    }

    @Test
    fun disableRemovesOnlyAppAddedGmsFromCurrentList() {
        val fixture = Fixture("com.example.game")
        fixture.protection.configure(enabled = true, removeGmsOnDisable = false)
        fixture.raw = "com.example.newgame_${gms}_com.example.game"
        fixture.protection.configure(enabled = false, removeGmsOnDisable = true)
        assertEquals("com.example.newgame_com.example.game", fixture.raw)
    }

    @Test
    fun disableWritesEmptyValueInsteadOfDeletingLastEntryOrLeavingStaleCache() {
        for (raw in listOf(gms, "null")) {
            val fixture = Fixture(raw)
            assertFalse(fixture.protection.configure(enabled = false, removeGmsOnDisable = true).failed)
            assertEquals("", fixture.raw)
            assertEquals(listOf("read", "read", "write", "read"), fixture.events)
        }
    }

    @Test
    fun concurrentWriterDefersRepairAndRetriesCleanup() {
        val fixture = Fixture("com.example.game")
        var reads = 0
        fixture.beforeRead = { if (++reads == 2) fixture.raw = "com.example.newgame" }
        val result = fixture.protection.configure(enabled = true, removeGmsOnDisable = false)
        assertFalse(result.failed)
        assertFalse("write" in fixture.events)
        fixture.beforeRead = {}
        fixture.protection.maintain()
        assertEquals("com.example.newgame_$gms", fixture.raw)

        reads = 0
        fixture.beforeRead = { if (++reads == 2) fixture.raw = "com.example.anothergame_$gms" }
        assertTrue(fixture.protection.configure(enabled = false, removeGmsOnDisable = true).failed)
        fixture.beforeRead = {}
        assertFalse(fixture.protection.configure(enabled = false, removeGmsOnDisable = true).failed)
        assertEquals("com.example.anothergame", fixture.raw)
    }

    @Test
    fun failedWriteOrVerificationSendsNoRecoveryBroadcast() {
        val fixture = Fixture()
        fixture.writeResult = Result.failure(IllegalStateException("denied"))
        assertTrue(fixture.protection.configure(enabled = true, removeGmsOnDisable = false).failed)
        assertFalse("thaw" in fixture.events)
        fixture.writeResult = Result.success(Unit)
        fixture.afterWrite = { fixture.raw = "" }
        assertTrue(fixture.protection.maintain().failed)
        assertFalse("thaw" in fixture.events)
    }

    @Test
    fun unchangedStartupWriteCannotClaimObserverReload() {
        val original = "com.example.game_$gms"
        val fixture = Fixture(original)
        fixture.afterWrite = { fixture.raw = original }
        assertTrue(fixture.protection.configure(enabled = true, removeGmsOnDisable = false).failed)
        assertFalse("thaw" in fixture.events)
        fixture.afterWrite = {}
        assertFalse(fixture.protection.maintain().failed)
        assertEquals(original + "_", fixture.raw)
    }

    @Test
    fun failedThawRetriesRecoveryWithoutRewritingHealthyMembership() {
        val fixture = Fixture()
        fixture.thawResult = Result.failure(IllegalStateException("unavailable"))
        assertTrue(fixture.protection.configure(enabled = true, removeGmsOnDisable = false).failed)
        assertFalse("reconnect" in fixture.events)
        fixture.events.clear()
        fixture.thawResult = Result.success(Unit)
        assertFalse(fixture.protection.maintain().failed)
        assertEquals(listOf("read", "thaw", "reconnect"), fixture.events)
    }

    @Test
    fun failedReconnectRetriesAndExistingReconnectPathThawsWhenEnabled() {
        val fixture = Fixture()
        fixture.reconnectResult = Result.failure(IllegalStateException("unavailable"))
        assertTrue(fixture.protection.configure(enabled = true, removeGmsOnDisable = false).failed)
        fixture.reconnectResult = Result.success(Unit)
        fixture.events.clear()
        assertFalse(fixture.protection.maintain().failed)
        assertEquals(listOf("read", "thaw", "reconnect"), fixture.events)
        fixture.events.clear()
        fixture.protection.prepareReconnect().getOrThrow()
        assertEquals(listOf("thaw"), fixture.events)
    }

    private fun effectivePackages(raw: String) = raw.split('_').filter(String::isNotEmpty)

    private class Fixture(var raw: String = "null") {
        val events = mutableListOf<String>()
        var writeResult: Result<Unit> = Result.success(Unit)
        var thawResult: Result<Unit> = Result.success(Unit)
        var reconnectResult: Result<Unit> = Result.success(Unit)
        var beforeRead: () -> Unit = {}
        var afterWrite: () -> Unit = {}
        val protection = GameAllowlistProtection(
            readValue = {
                events += "read"
                beforeRead()
                Result.success(raw)
            },
            writeValue = {
                events += "write"
                if (writeResult.isSuccess) raw = it
                afterWrite()
                writeResult
            },
            thawGms = { events += "thaw"; thawResult },
            reconnectGms = { events += "reconnect"; reconnectResult },
        )
    }
}
