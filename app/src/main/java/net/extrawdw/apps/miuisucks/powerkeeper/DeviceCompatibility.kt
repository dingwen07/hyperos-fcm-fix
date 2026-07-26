package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager

data class DeviceCompatibility(
    val supported: Boolean,
    val reason: String,
)

object XiaomiCompatibility {
    const val REQUIRED_SHARED_LIBRARY = "com.miui.system"

    fun check(context: Context): DeviceCompatibility {
        val packageManager = context.packageManager
        val ownerUser = context.getSystemService(UserManager::class.java).isSystemUser
        val manufacturerMatches = isXiaomiManufacturer(Build.MANUFACTURER)
        val libraryPresent = packageManager.systemSharedLibraryNames
            ?.contains(REQUIRED_SHARED_LIBRARY) == true
        val miuiSystemPresent = hasSystemPackage(packageManager, MIUI_SYSTEM_PACKAGE)
        val supported = ownerUser && manufacturerMatches && libraryPresent && miuiSystemPresent

        val reason = if (supported) {
            "Xiaomi/HyperOS framework detected"
        } else {
            buildList {
                if (!ownerUser) add("app must run as Android owner user 0")
                if (!manufacturerMatches) add("manufacturer is ${Build.MANUFACTURER}")
                if (!libraryPresent) add("$REQUIRED_SHARED_LIBRARY shared library is missing")
                if (!miuiSystemPresent) add("$MIUI_SYSTEM_PACKAGE system package is missing")
            }.joinToString(separator = "; ")
        }
        return DeviceCompatibility(supported, reason)
    }

    internal fun isXiaomiManufacturer(manufacturer: String): Boolean =
        manufacturer.equals("Xiaomi", ignoreCase = true)

    @Suppress("DEPRECATION")
    private fun hasSystemPackage(packageManager: PackageManager, packageName: String): Boolean {
        val applicationInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
                )
            } else {
                packageManager.getApplicationInfo(packageName, PackageManager.MATCH_SYSTEM_ONLY)
            }
        }.getOrNull() ?: return false
        return applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    private const val MIUI_SYSTEM_PACKAGE = "com.miui.system"
}
