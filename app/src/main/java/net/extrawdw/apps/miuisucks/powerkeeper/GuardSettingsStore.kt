package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import androidx.core.content.edit

data class GuardSettings(
    val appPolicies: Map<String, AppPolicy>,
    val intervalMinutes: Long,
    val androidUsers: List<AndroidUserSelection>,
) {
    fun policyFor(packageName: String): AppPolicy =
        appPolicies[packageName] ?: AppPolicyDefaults.forPackage(packageName)

    val aurogonEnabledPackages: List<String>
        get() = appPolicies.values
            .filter { it.appEnabled && it.aurogonEnabled }
            .map(AppPolicy::packageName)
            .sorted()

    val aurogonManagedPackages: List<String>
        get() = appPolicies.values
            .filter(AppPolicy::aurogonManaged)
            .map(AppPolicy::packageName)
            .sorted()

    val enabledAppPolicies: List<AppPolicy>
        get() = appPolicies.values
            .filter(AppPolicy::appEnabled)
            .sortedBy(AppPolicy::packageName)

    val periodicallyEnforcedAppPolicies: List<AppPolicy>
        get() = enabledAppPolicies.filter(AppPolicy::periodicEnforcement)
}

data class LastRun(
    val timestampMillis: Long,
    val succeeded: Boolean,
    val report: String,
)

class GuardSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): GuardSettings = GuardSettings(
        appPolicies = loadAppPolicies(),
        intervalMinutes = normalizeIntervalMinutes(
            preferences.getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES),
        ),
        androidUsers = loadAndroidUsers(),
    )

    fun loadAppPolicies(): Map<String, AppPolicy> {
        val policies = preferences.getStringSet(KEY_APP_POLICIES, emptySet())
            .orEmpty()
            .mapNotNull(::decodeAppPolicy)
            .associateBy(AppPolicy::packageName)
            .toMutableMap()
        AppPolicyDefaults.initialPolicies().forEach { (packageName, policy) ->
            policies.putIfAbsent(packageName, policy)
        }
        return policies
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        updateAppPolicy(packageName) { it.withAppEnabled(enabled) }
    }

    fun setAurogonEnabled(packageName: String, enabled: Boolean) {
        updateAppPolicy(packageName) {
            it.copy(aurogonEnabled = enabled, aurogonManaged = true)
        }
    }

    fun setAutostartEnabled(packageName: String, enabled: Boolean) {
        updateAppPolicy(packageName) {
            it.copy(autostartEnabled = enabled, autostartManaged = true)
        }
    }

    fun setDozePolicy(packageName: String, policy: AppDozePolicy) {
        updateAppPolicy(packageName) { it.copy(dozePolicy = policy) }
    }

    fun setPeriodicEnforcement(packageName: String, enabled: Boolean) {
        updateAppPolicy(packageName) { it.copy(periodicEnforcement = enabled) }
    }

    fun setIntervalMinutes(minutes: Long) {
        preferences.edit {
            putLong(KEY_INTERVAL_MINUTES, normalizeIntervalMinutes(minutes))
        }
    }

    fun loadAndroidUsers(): List<AndroidUserSelection> = AndroidUserSelections.decode(
        preferences.getString(KEY_ANDROID_USERS, null),
    )

    fun loadEnabledAndroidUserIds(): List<Int> {
        val users = loadAndroidUsers()
        return if (users.isEmpty()) {
            AndroidUserSelections.DEFAULT_ENABLED_USER_IDS.sorted()
        } else {
            users.filter(AndroidUserSelection::enabled).map(AndroidUserSelection::userId)
        }
    }

    fun mergeAndSaveAndroidUsers(discovered: List<DiscoveredAndroidUser>): List<AndroidUserSelection> {
        val merged = AndroidUserSelections.merge(discovered, loadAndroidUsers())
        saveAndroidUsers(merged)
        return merged
    }

    fun setAndroidUserEnabled(userId: Int, enabled: Boolean) {
        val updated = loadAndroidUsers().map { user ->
            if (user.userId == userId) user.copy(enabled = enabled) else user
        }
        saveAndroidUsers(updated)
    }

    private fun saveAndroidUsers(users: List<AndroidUserSelection>) {
        preferences.edit { putString(KEY_ANDROID_USERS, AndroidUserSelections.encode(users)) }
    }

    private fun updateAppPolicy(packageName: String, transform: (AppPolicy) -> AppPolicy) {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
        val policies = loadAppPolicies().toMutableMap()
        val current = policies[packageName] ?: AppPolicyDefaults.forPackage(packageName)
        policies[packageName] = transform(current).copy(packageName = packageName)
        saveAppPolicies(policies.values)
    }

    private fun saveAppPolicies(policies: Collection<AppPolicy>) {
        preferences.edit {
            putStringSet(
                KEY_APP_POLICIES,
                policies
                    .filter { PACKAGE_NAME.matches(it.packageName) }
                    .mapTo(linkedSetOf(), ::encodeAppPolicy),
            )
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
        const val DISABLED_INTERVAL_MINUTES = 0L
        const val MINIMUM_INTERVAL_MINUTES = 15L
        const val DEFAULT_INTERVAL_MINUTES = 60L

        fun isPeriodicEnforcementEnabled(intervalMinutes: Long): Boolean =
            intervalMinutes > DISABLED_INTERVAL_MINUTES

        internal fun normalizeIntervalMinutes(minutes: Long): Long =
            if (minutes == DISABLED_INTERVAL_MINUTES) {
                DISABLED_INTERVAL_MINUTES
            } else {
                minutes.coerceAtLeast(MINIMUM_INTERVAL_MINUTES)
            }

        private const val PREFERENCES_NAME = "guard_settings"
        private const val KEY_APP_POLICIES = "app_policies_v2"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_ANDROID_USERS = "android_users"
        private const val KEY_LAST_RUN_TIMESTAMP = "last_run_timestamp"
        private const val KEY_LAST_RUN_SUCCEEDED = "last_run_succeeded"
        private const val KEY_LAST_RUN_REPORT = "last_run_report"
        private const val MAX_STORED_REPORT_LENGTH = 24_000

        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

        internal fun encodeAppPolicy(policy: AppPolicy): String = listOf(
            policy.packageName,
            if (policy.appEnabled) "1" else "0",
            if (policy.aurogonEnabled) "1" else "0",
            if (policy.aurogonManaged) "1" else "0",
            if (policy.autostartEnabled) "1" else "0",
            if (policy.autostartManaged) "1" else "0",
            policy.dozePolicy.persistedValue,
            if (policy.periodicEnforcement) "1" else "0",
        ).joinToString("|")

        internal fun decodeAppPolicy(encoded: String): AppPolicy? {
            val fields = encoded.split('|')
            if (fields.size != 8 || !PACKAGE_NAME.matches(fields[0])) return null
            val appEnabled = fields[1]
            val aurogonEnabled = fields[2]
            val aurogonManaged = fields[3]
            val autostartEnabled = fields[4]
            val autostartManaged = fields[5]
            val periodic = fields[7]
            if (listOf(appEnabled, aurogonEnabled, aurogonManaged, autostartEnabled, autostartManaged, periodic).any { it !in setOf("0", "1") }) return null
            return AppPolicy(
                packageName = fields[0],
                appEnabled = appEnabled == "1",
                aurogonEnabled = aurogonEnabled == "1",
                aurogonManaged = aurogonManaged == "1",
                autostartEnabled = autostartEnabled == "1",
                autostartManaged = autostartManaged == "1",
                dozePolicy = AppDozePolicy.fromPersistedValue(fields[6]),
                periodicEnforcement = periodic == "1",
            )
        }
    }
}
