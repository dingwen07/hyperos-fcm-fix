package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import androidx.core.content.edit

data class GuardSettings(
    val wechatPolicy: WechatPolicy,
    val intervalMinutes: Long,
)

data class LastRun(
    val timestampMillis: Long,
    val succeeded: Boolean,
    val report: String,
)

class GuardSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): GuardSettings = GuardSettings(
        wechatPolicy = WechatPolicy.fromPersistedValue(
            preferences.getString(KEY_WECHAT_POLICY, WechatPolicy.OPTIMIZED.persistedValue),
        ),
        intervalMinutes = preferences.getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
            .coerceAtLeast(MINIMUM_INTERVAL_MINUTES),
    )

    fun setWechatPolicy(policy: WechatPolicy) {
        preferences.edit { putString(KEY_WECHAT_POLICY, policy.persistedValue) }
    }

    fun setIntervalMinutes(minutes: Long) {
        preferences.edit {
            putLong(KEY_INTERVAL_MINUTES, minutes.coerceAtLeast(MINIMUM_INTERVAL_MINUTES))
        }
    }

    fun loadLastRun(): LastRun? {
        val timestamp = preferences.getLong(KEY_LAST_RUN_TIMESTAMP, 0L)
        if (timestamp == 0L) return null
        return LastRun(
            timestampMillis = timestamp,
            succeeded = preferences.getBoolean(KEY_LAST_RUN_SUCCEEDED, false),
            report = preferences.getString(KEY_LAST_RUN_REPORT, "").orEmpty(),
        )
    }

    fun saveLastRun(succeeded: Boolean, report: String) {
        preferences.edit {
            putLong(KEY_LAST_RUN_TIMESTAMP, System.currentTimeMillis())
            putBoolean(KEY_LAST_RUN_SUCCEEDED, succeeded)
            putString(KEY_LAST_RUN_REPORT, report.take(MAX_STORED_REPORT_LENGTH))
        }
    }

    companion object {
        const val MINIMUM_INTERVAL_MINUTES = 15L
        const val DEFAULT_INTERVAL_MINUTES = 60L

        private const val PREFERENCES_NAME = "guard_settings"
        private const val KEY_WECHAT_POLICY = "wechat_policy"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_LAST_RUN_TIMESTAMP = "last_run_timestamp"
        private const val KEY_LAST_RUN_SUCCEEDED = "last_run_succeeded"
        private const val KEY_LAST_RUN_REPORT = "last_run_report"
        private const val MAX_STORED_REPORT_LENGTH = 24_000
    }
}
