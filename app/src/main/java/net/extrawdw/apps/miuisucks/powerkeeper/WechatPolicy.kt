package net.extrawdw.apps.miuisucks.powerkeeper

enum class WechatPolicy(
    val code: Int,
    val persistedValue: String,
    val titleRes: Int,
    val descriptionRes: Int,
) {
    DISABLED(
        code = 0,
        persistedValue = "disabled",
        titleRes = R.string.wechat_policy_off,
        descriptionRes = R.string.wechat_policy_off_description,
    ),
    OPTIMIZED(
        code = 1,
        persistedValue = "optimized",
        titleRes = R.string.wechat_policy_optimized,
        descriptionRes = R.string.wechat_policy_optimized_description,
    ),
    RESTRICTED(
        code = 2,
        persistedValue = "restricted",
        titleRes = R.string.wechat_policy_restricted,
        descriptionRes = R.string.wechat_policy_restricted_description,
    );

    companion object {
        fun fromCode(code: Int): WechatPolicy = entries.firstOrNull { it.code == code } ?: OPTIMIZED

        fun fromPersistedValue(value: String?): WechatPolicy =
            entries.firstOrNull { it.persistedValue == value } ?: OPTIMIZED
    }
}
