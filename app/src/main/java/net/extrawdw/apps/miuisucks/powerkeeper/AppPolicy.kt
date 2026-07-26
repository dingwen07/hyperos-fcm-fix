package net.extrawdw.apps.miuisucks.powerkeeper

enum class AppDozePolicy(
    val code: Int,
    val persistedValue: String,
    val titleRes: Int,
    val descriptionRes: Int,
) {
    OFF(
        code = 0,
        persistedValue = "off",
        titleRes = R.string.app_doze_off,
        descriptionRes = R.string.app_doze_off_description,
    ),
    UNRESTRICTED(
        code = 1,
        persistedValue = "unrestricted",
        titleRes = R.string.app_doze_unrestricted,
        descriptionRes = R.string.app_doze_unrestricted_description,
    ),
    DEFAULT(
        code = 2,
        persistedValue = "default",
        titleRes = R.string.app_doze_default,
        descriptionRes = R.string.app_doze_default_description,
    ),
    RESTRICTED(
        code = 3,
        persistedValue = "restricted",
        titleRes = R.string.app_doze_restricted,
        descriptionRes = R.string.app_doze_restricted_description,
    );

    companion object {
        fun fromCode(code: Int): AppDozePolicy = entries.firstOrNull { it.code == code } ?: OFF

        fun fromPersistedValue(value: String?): AppDozePolicy =
            entries.firstOrNull { it.persistedValue == value } ?: OFF
    }
}

data class AppPolicy(
    val packageName: String,
    val aurogonEnabled: Boolean = false,
    val aurogonManaged: Boolean = false,
    val autostartEnabled: Boolean = false,
    val autostartManaged: Boolean = false,
    val dozePolicy: AppDozePolicy = AppDozePolicy.OFF,
    val periodicEnforcement: Boolean = false,
) {
    val fcmProtectionEnabled: Boolean
        get() = aurogonEnabled
}

object AppPolicyDefaults {
    val HYPEROS_AUTO_UNRESTRICTED_PACKAGES: Set<String> = setOf(
        "com.tencent.mm",
    )

    fun initialPolicies(): Map<String, AppPolicy> =
        HYPEROS_AUTO_UNRESTRICTED_PACKAGES.associateWith(::forPackage)

    fun forPackage(packageName: String): AppPolicy =
        if (packageName in HYPEROS_AUTO_UNRESTRICTED_PACKAGES) {
            AppPolicy(
                packageName = packageName,
                aurogonEnabled = true,
                aurogonManaged = true,
                autostartEnabled = true,
                autostartManaged = true,
                dozePolicy = AppDozePolicy.OFF,
                periodicEnforcement = true,
            )
        } else {
            AppPolicy(packageName, dozePolicy = AppDozePolicy.UNRESTRICTED)
        }
}
