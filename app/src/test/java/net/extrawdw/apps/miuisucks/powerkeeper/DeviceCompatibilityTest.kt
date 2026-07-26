package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatibilityTest {
    @Test
    fun acceptsXiaomiManufacturerWithoutCaseSensitivity() {
        assertTrue(XiaomiCompatibility.isXiaomiManufacturer("Xiaomi"))
        assertTrue(XiaomiCompatibility.isXiaomiManufacturer("xiaomi"))
    }

    @Test
    fun rejectsOtherManufacturers() {
        assertFalse(XiaomiCompatibility.isXiaomiManufacturer("Google"))
        assertFalse(XiaomiCompatibility.isXiaomiManufacturer("Samsung"))
        assertFalse(XiaomiCompatibility.isXiaomiManufacturer(""))
    }
}
