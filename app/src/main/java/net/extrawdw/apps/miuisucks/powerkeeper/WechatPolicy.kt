package net.extrawdw.apps.miuisucks.powerkeeper

enum class WechatPolicy(
    val code: Int,
    val persistedValue: String,
    val title: String,
    val description: String,
) {
    DISABLED(
        code = 0,
        persistedValue = "disabled",
        title = "Off",
        description = "Leave WeChat's battery settings unchanged.",
    ),
    OPTIMIZED(
        code = 1,
        persistedValue = "optimized",
        title = "Optimized",
        description = "Allow background activity without using Unrestricted mode.",
    ),
    RESTRICTED(
        code = 2,
        persistedValue = "restricted",
        title = "Restricted",
        description = "Limit background activity to save more battery. Notifications may be delayed.",
    );

    companion object {
        fun fromCode(code: Int): WechatPolicy = entries.firstOrNull { it.code == code } ?: OPTIMIZED

        fun fromPersistedValue(value: String?): WechatPolicy =
            entries.firstOrNull { it.persistedValue == value } ?: OPTIMIZED
    }
}
