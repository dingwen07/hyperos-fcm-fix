package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import androidx.core.content.edit

data class GuardSettings(
    val appPolicies: Map<String, AppPolicy>,
    val intervalMinutes: Long,
    val milletPollingIntervalMillis: Long,
    val fcmReconnectEnabled: Boolean,
    val androidUsers: List<AndroidUserSelection>,
) {
    fun policyFor(packageName: String): AppPolicy =
        appPolicies[packageName] ?: AppPolicyDefaults.forPackage(packageName)

    val aurogonEnabledPackages: List<String>
        get() = appPolicies.values
            .filter { it.appEnabled && it.aurogonEnabled }
            .map(AppPolicy::packageName)
            .sorted()

    val autoUnstopPackages: List<String>
        get() = appPolicies.values
            .filter { it.appEnabled && it.autoUnstopEnabled }
            .map(AppPolicy::packageName)
            .sorted()

    val enabledAppPolicies: List<AppPolicy>
        get() = appPolicies.values
            .filter(AppPolicy::appEnabled)
            .sortedBy(AppPolicy::packageName)
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
        milletPollingIntervalMillis = MilletPollingInterval.normalize(
            preferences.getLong(
                KEY_MILLET_POLLING_INTERVAL_MILLIS,
                MilletPollingInterval.DEFAULT_MILLIS,
            ),
        ),
        fcmReconnectEnabled = preferences.getBoolean(
            KEY_FCM_RECONNECT_ENABLED,
            DEFAULT_FCM_RECONNECT_ENABLED,
        ),
        androidUsers = loadAndroidUsers(),
    )

    fun loadAppPolicies(): Map<String, AppPolicy> {
        migrateAppPoliciesIfNeeded()
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

    private fun migrateAppPoliciesIfNeeded() {
        if (preferences.getInt(KEY_APP_POLICY_SCHEMA_VERSION, 0) >= APP_POLICY_SCHEMA_VERSION) return

        val migratedPolicies = preferences.getStringSet(KEY_APP_POLICIES, emptySet())
            .orEmpty()
            .mapNotNull(::decodeLegacyAppPolicy)
            .mapTo(linkedSetOf(), ::encodeAppPolicy)
        preferences.edit {
            putStringSet(KEY_APP_POLICIES, migratedPolicies)
            putInt(KEY_APP_POLICY_SCHEMA_VERSION, APP_POLICY_SCHEMA_VERSION)
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        updateAppPolicy(packageName) { current ->
            val policy = current ?: AppPolicyDefaults.forPackage(packageName)
            policy.withAppEnabled(enabled, initializeDefaults = enabled && current == null)
        }
    }

    fun setAurogonEnabled(packageName: String, enabled: Boolean) {
        updateAppPolicy(packageName) { current ->
            (current ?: AppPolicyDefaults.forPackage(packageName)).copy(aurogonEnabled = enabled)
        }
    }

    fun setAutoUnstopEnabled(packageName: String, enabled: Boolean) {
        updateAppPolicy(packageName) { current ->
            (current ?: AppPolicyDefaults.forPackage(packageName)).copy(autoUnstopEnabled = enabled)
        }
    }

    fun setAutostartEnabled(packageName: String, enabled: Boolean) {
        updateAppPolicy(packageName) { current ->
            (current ?: AppPolicyDefaults.forPackage(packageName)).copy(autostartEnabled = enabled)
        }
    }

    fun setAutostartManaged(packageName: String, managed: Boolean) {
        updateAppPolicy(packageName) { current ->
            (current ?: AppPolicyDefaults.forPackage(packageName)).copy(autostartManaged = managed)
        }
    }

    fun setDozePolicy(packageName: String, policy: AppDozePolicy) {
        updateAppPolicy(packageName) { current ->
            (current ?: AppPolicyDefaults.forPackage(packageName)).copy(dozePolicy = policy)
        }
    }

    fun setDozeManaged(packageName: String, managed: Boolean) {
        updateAppPolicy(packageName) { current ->
            (current ?: AppPolicyDefaults.forPackage(packageName)).copy(dozeManaged = managed)
        }
    }

    fun setIntervalMinutes(minutes: Long) {
        preferences.edit {
            putLong(KEY_INTERVAL_MINUTES, normalizeIntervalMinutes(minutes))
        }
    }

    fun setMilletPollingIntervalMillis(intervalMillis: Long) {
        preferences.edit {
            putLong(
                KEY_MILLET_POLLING_INTERVAL_MILLIS,
                MilletPollingInterval.normalize(intervalMillis),
            )
        }
    }

    fun setFcmReconnectEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_FCM_RECONNECT_ENABLED, enabled) }
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

    private fun updateAppPolicy(packageName: String, transform: (AppPolicy?) -> AppPolicy) {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
        val policies = loadAppPolicies().toMutableMap()
        policies[packageName] = transform(policies[packageName]).copy(packageName = packageName)
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
        const val DEFAULT_FCM_RECONNECT_ENABLED = true

        fun isPeriodicEnforcementEnabled(intervalMinutes: Long): Boolean =
            intervalMinutes > DISABLED_INTERVAL_MINUTES

        internal fun normalizeIntervalMinutes(minutes: Long): Long =
            if (minutes == DISABLED_INTERVAL_MINUTES) {
                DISABLED_INTERVAL_MINUTES
            } else {
                minutes.coerceAtLeast(MINIMUM_INTERVAL_MINUTES)
            }

        private const val PREFERENCES_NAME = "guard_settings"
        private const val KEY_APP_POLICIES = "app_policies"
        private const val KEY_APP_POLICY_SCHEMA_VERSION = "app_policy_schema_version"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_MILLET_POLLING_INTERVAL_MILLIS = "millet_polling_interval_millis"
        private const val KEY_FCM_RECONNECT_ENABLED = "fcm_reconnect_enabled"
        private const val KEY_ANDROID_USERS = "android_users"
        private const val KEY_LAST_RUN_TIMESTAMP = "last_run_timestamp"
        private const val KEY_LAST_RUN_SUCCEEDED = "last_run_succeeded"
        private const val KEY_LAST_RUN_REPORT = "last_run_report"
        private const val MAX_STORED_REPORT_LENGTH = 24_000
        private const val APP_POLICY_SCHEMA_VERSION = 1

        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

        internal fun encodeAppPolicy(policy: AppPolicy): String = listOf(
            policy.packageName,
            if (policy.appEnabled) "1" else "0",
            if (policy.aurogonEnabled) "1" else "0",
            if (policy.autoUnstopEnabled) "1" else "0",
            if (policy.autostartManaged) "1" else "0",
            if (policy.autostartEnabled) "1" else "0",
            if (policy.dozeManaged) "1" else "0",
            policy.dozePolicy.persistedValue,
        ).joinToString("|")

        internal fun decodeAppPolicy(encoded: String): AppPolicy? {
            val fields = encoded.split('|')
            if (fields.size != 8 || !PACKAGE_NAME.matches(fields[0])) return null
            val appEnabled = fields[1]
            val aurogonEnabled = fields[2]
            val autoUnstopEnabled = fields[3]
            val autostartManaged = fields[4]
            val autostartEnabled = fields[5]
            val dozeManaged = fields[6]
            val dozePolicy = fields[7]
            if (listOf(appEnabled, aurogonEnabled, autoUnstopEnabled, autostartManaged, autostartEnabled, dozeManaged).any { it !in setOf("0", "1") }) return null
            return AppPolicy(
                packageName = fields[0],
                appEnabled = appEnabled == "1",
                aurogonEnabled = aurogonEnabled == "1",
                autoUnstopEnabled = autoUnstopEnabled == "1",
                autostartManaged = autostartManaged == "1",
                autostartEnabled = autostartEnabled == "1",
                dozeManaged = dozeManaged == "1",
                dozePolicy = AppDozePolicy.fromPersistedValue(dozePolicy),
            )
        }

        internal fun decodeLegacyAppPolicy(encoded: String): AppPolicy? {
            val fields = encoded.split('|')
            if ((fields.size != 8 && fields.size != 9) || !PACKAGE_NAME.matches(fields[0])) return null
            val hasAutoUnstopField = fields.size == 9
            val appEnabled = fields[1]
            val aurogonEnabled = fields[2]
            val autoUnstopEnabled = if (hasAutoUnstopField) fields[3] else appEnabled
            val autostartManaged = fields[if (hasAutoUnstopField) 4 else 3]
            val autostartEnabled = fields[if (hasAutoUnstopField) 5 else 4]
            val dozeManaged = fields[if (hasAutoUnstopField) 6 else 5]
            val dozePolicy = fields[if (hasAutoUnstopField) 7 else 6]
            val periodic = fields[if (hasAutoUnstopField) 8 else 7]
            if (listOf(appEnabled, aurogonEnabled, autoUnstopEnabled, autostartManaged, autostartEnabled, dozeManaged, periodic).any { it !in setOf("0", "1") }) return null
            return AppPolicy(
                packageName = fields[0],
                appEnabled = appEnabled == "1",
                aurogonEnabled = aurogonEnabled == "1",
                autoUnstopEnabled = autoUnstopEnabled == "1",
                autostartManaged = autostartManaged == "1",
                autostartEnabled = autostartEnabled == "1",
                dozeManaged = dozeManaged == "1",
                dozePolicy = AppDozePolicy.fromPersistedValue(dozePolicy),
            )
        }
    }
}
