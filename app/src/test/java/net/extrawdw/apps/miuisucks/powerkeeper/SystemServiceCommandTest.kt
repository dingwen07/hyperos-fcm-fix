package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemServiceCommandTest {
    @Test
    fun commandBuildersTargetTheUnderlyingBinderServices() {
        assertEquals(
            SystemServiceCommand("settings", listOf("get", "global", "example")),
            SystemServiceCommands.settings("get", "global", "example"),
        )
        assertEquals(
            SystemServiceCommand("package", listOf("unstop", "--user", "0", "com.example.push")),
            SystemServiceCommands.packageManager("unstop", "--user", "0", "com.example.push"),
        )
        assertEquals(
            SystemServiceCommand("activity", listOf("broadcast", "--user", "0")),
            SystemServiceCommands.activity("broadcast", "--user", "0"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNulInArguments() {
        SystemServiceCommand("package", listOf("bad\u0000argument"))
    }
}
