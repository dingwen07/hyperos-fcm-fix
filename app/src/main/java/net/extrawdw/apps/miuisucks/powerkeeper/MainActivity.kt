package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.miuisucks.powerkeeper.ui.theme.MIUIPowerKeeperFixTheme
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date

enum class ShizukuConnectionState {
    CHECKING,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    CONNECTED,
}

data class ShizukuStatus(
    val available: Boolean = false,
    val granted: Boolean = false,
    val connectionState: ShizukuConnectionState = ShizukuConnectionState.CHECKING,
)

data class ApplyProgress(
    val completedApps: Int,
    val totalApps: Int,
) {
    val fraction: Float
        get() = if (totalApps == 0) 1f else completedApps.toFloat() / totalApps
}

data class GuardUiState(
    val settings: GuardSettings = GuardSettings(
        appPolicies = AppPolicyDefaults.initialPolicies(),
        intervalMinutes = GuardSettingsStore.DEFAULT_INTERVAL_MINUTES,
        androidUsers = emptyList(),
    ),
    val installedApps: List<InstalledFcmApp>? = null,
    val shizuku: ShizukuStatus = ShizukuStatus(),
    val lastRun: LastRun? = null,
    val applying: Boolean = false,
    val applyProgress: ApplyProgress? = null,
    val milletNoRestrictValue: String? = null,
    val checkingMilletValue: Boolean = false,
    val refreshingAndroidUsers: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

private enum class GuardTab { HOME, APPS }

private data class StartupData(
    val compatibility: DeviceCompatibility,
    val settingsStore: GuardSettingsStore? = null,
    val settings: GuardSettings? = null,
    val lastRun: LastRun? = null,
)

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: GuardSettingsStore
    private var uiState by mutableStateOf(GuardUiState())
    private var compatibility by mutableStateOf<DeviceCompatibility?>(null)
    private var shizukuListenersRegistered = false
    private var shizukuRefreshGeneration = 0
    private var applyAfterAndroidUserRefresh = false
    private var loadingInstalledApps = false
    private var stalePeriodicRunning = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshState()
        refreshShizukuStatus { status ->
            if (status.granted) maybeApplyStaleSettings()
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuRefreshGeneration++
        refreshState(getString(R.string.shizuku_stopped_message), messageIsError = true)
        uiState = uiState.copy(
            shizuku = ShizukuStatus(
                available = false,
                granted = false,
                connectionState = ShizukuConnectionState.NOT_RUNNING,
            ),
        )
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        refreshState(
            message = if (granted) {
                getString(R.string.shizuku_access_granted_message)
            } else {
                getString(R.string.shizuku_access_denied_message)
            },
            messageIsError = !granted,
        )
        refreshShizukuStatus { status ->
            if (status.granted) maybeApplyStaleSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MIUIPowerKeeperFixTheme {
                val checkedCompatibility = compatibility
                when {
                    checkedCompatibility == null -> StartupCheckScreen()
                    !checkedCompatibility.supported -> UnsupportedDeviceScreen()
                    else -> GuardApp(
                        state = uiState,
                        onRequestShizuku = ::requestShizukuAccess,
                        onOpenShizuku = ::openShizuku,
                        onApplyNow = ::applyNow,
                        onRefreshAndroidUsers = { refreshAndroidUsers() },
                        onAndroidUserEnabledChanged = ::setAndroidUserEnabled,
                        onCheckMilletValue = ::checkMilletValue,
                        onAppEnabledChanged = ::setAppEnabled,
                        onAurogonChanged = ::setAurogonEnabled,
                        onAutostartManagedChanged = ::setAutostartManaged,
                        onAutostartChanged = ::setAutostartEnabled,
                        onPeriodicChanged = ::setPeriodicEnforcement,
                        onDozeManagedChanged = ::setDozeManaged,
                        onDozeChanged = ::setDozePolicy,
                        onIntervalSelected = ::setInterval,
                        onLogsCleared = ::flushServiceLogs,
                    )
                }
            }
        }

        lifecycleScope.launch {
            val startup = withContext(Dispatchers.IO) {
                val checkedCompatibility = XiaomiCompatibility.check(applicationContext)
                if (!checkedCompatibility.supported) {
                    StartupData(checkedCompatibility)
                } else {
                    val store = GuardSettingsStore(applicationContext)
                    val settings = store.loadSettings()
                    EnforcementScheduler.schedule(applicationContext, settings.intervalMinutes)
                    StartupData(
                        compatibility = checkedCompatibility,
                        settingsStore = store,
                        settings = settings,
                        lastRun = store.loadLastRun(),
                    )
                }
            }

            if (!startup.compatibility.supported) {
                AppLog.e("Compatibility", startup.compatibility.reason)
                compatibility = startup.compatibility
                return@launch
            }

            settingsStore = requireNotNull(startup.settingsStore)
            uiState = uiState.copy(
                settings = requireNotNull(startup.settings),
                lastRun = startup.lastRun,
            )
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            shizukuListenersRegistered = true
            compatibility = startup.compatibility
            loadInstalledApps()
            refreshShizukuStatus { status ->
                if (status.granted) {
                    if (uiState.settings.androidUsers.isEmpty()) refreshAndroidUsers()
                    checkMilletValue()
                    maybeApplyStaleSettings()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settingsStore.isInitialized) {
            refreshState()
            loadInstalledApps()
            refreshShizukuStatus { status ->
                if (status.granted) {
                    if (uiState.settings.androidUsers.isEmpty()) refreshAndroidUsers()
                    if (!uiState.applying) checkMilletValue()
                }
            }
        }
    }

    override fun onDestroy() {
        shizukuRefreshGeneration++
        if (shizukuListenersRegistered) {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        }
        super.onDestroy()
    }

    private fun refreshState(
        message: String? = uiState.message,
        messageIsError: Boolean = uiState.messageIsError,
    ) {
        uiState = uiState.copy(
            settings = settingsStore.loadSettings(),
            lastRun = settingsStore.loadLastRun(),
            message = message,
            messageIsError = messageIsError,
        )
    }

    private fun refreshShizukuStatus(onRefreshed: (ShizukuStatus) -> Unit = {}) {
        val generation = ++shizukuRefreshGeneration
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                val available = runCatching {
                    Shizuku.pingBinder() && !Shizuku.isPreV11()
                }.getOrDefault(false)
                val granted = available && runCatching {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                ShizukuStatus(
                    available = available,
                    granted = granted,
                    connectionState = when {
                        !available -> ShizukuConnectionState.NOT_RUNNING
                        !granted -> ShizukuConnectionState.PERMISSION_REQUIRED
                        else -> ShizukuConnectionState.CONNECTED
                    },
                )
            }
            if (generation != shizukuRefreshGeneration) return@launch
            uiState = uiState.copy(shizuku = status)
            onRefreshed(status)
        }
    }

    private fun requestShizukuAccess() {
        if (!uiState.shizuku.available) {
            openShizuku()
            uiState = uiState.copy(message = getString(R.string.start_shizuku_message), messageIsError = false)
            return
        }
        if (uiState.shizuku.granted) {
            uiState = uiState.copy(
                message = getString(R.string.shizuku_access_already_granted_message),
                messageIsError = false,
            )
            return
        }
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                openShizuku()
                uiState = uiState.copy(
                    message = getString(R.string.shizuku_access_denied_previously_message),
                    messageIsError = true,
                )
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }
        }.onFailure {
            uiState = uiState.copy(
                message = getString(
                    R.string.shizuku_request_failed_message,
                    it.message ?: getString(R.string.unknown_error),
                ),
                messageIsError = true,
            )
        }
    }

    private fun openShizuku() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            uiState = uiState.copy(message = getString(R.string.shizuku_not_installed_message), messageIsError = true)
        }
    }

    private fun setAppEnabled(packageName: String, enabled: Boolean) {
        settingsStore.setAppEnabled(packageName, enabled)
        val settings = refreshAppSetting(packageName, willApply = true)
        if (!uiState.shizuku.granted) return
        val policy = settings.policyFor(packageName)
        runAppSettingAction(packageName, if (enabled) "app-on" else "app-off") {
            buildString {
                appendLine(
                    PrivilegedServiceClient.reconcileAurogon(
                        settings.aurogonEnabledPackages,
                        TRIGGER_UI_APP_CHANGE,
                    ),
                )
                val targetedPolicy = when {
                    enabled && (policy.autostartManaged || policy.dozeManaged) -> policy
                    !enabled -> policy.appOffCleanupPolicy()
                    else -> null
                }
                if (targetedPolicy != null) {
                    append(
                        PrivilegedServiceClient.applyAppPolicy(
                            policy = targetedPolicy,
                            targetUserIds = settingsStore.loadEnabledAndroidUserIds(),
                            trigger = TRIGGER_UI_APP_CHANGE,
                        ),
                    )
                }
            }
        }
    }

    private fun setAurogonEnabled(packageName: String, enabled: Boolean) {
        settingsStore.setAurogonEnabled(packageName, enabled)
        val settings = refreshAppSetting(packageName, willApply = true)
        if (!uiState.shizuku.granted) return
        runAppSettingAction(packageName, "aurogon") {
            PrivilegedServiceClient.reconcileAurogon(
                settings.aurogonEnabledPackages,
                TRIGGER_UI_APP_CHANGE,
            )
        }
    }

    private fun setAutostartManaged(packageName: String, managed: Boolean) {
        settingsStore.setAutostartManaged(packageName, managed)
        val policy = refreshAppSetting(packageName, willApply = managed).policyFor(packageName)
        if (!uiState.shizuku.granted || !policy.appEnabled || !managed) return
        runAppSettingAction(packageName, "autostart-management") {
            PrivilegedServiceClient.applyAppPolicy(
                policy = AppPolicy(
                    packageName = packageName,
                    appEnabled = true,
                    autostartManaged = true,
                    autostartEnabled = policy.autostartEnabled,
                ),
                targetUserIds = settingsStore.loadEnabledAndroidUserIds(),
                trigger = TRIGGER_UI_APP_CHANGE,
            )
        }
    }

    private fun setAutostartEnabled(packageName: String, enabled: Boolean) {
        settingsStore.setAutostartEnabled(packageName, enabled)
        val policy = refreshAppSetting(packageName, willApply = true).policyFor(packageName)
        if (!uiState.shizuku.granted || !policy.appEnabled || !policy.autostartManaged) return
        runAppSettingAction(packageName, "autostart") {
            PrivilegedServiceClient.applyAppPolicy(
                policy = AppPolicy(
                    packageName = packageName,
                    appEnabled = true,
                    autostartManaged = true,
                    autostartEnabled = enabled,
                ),
                targetUserIds = settingsStore.loadEnabledAndroidUserIds(),
                trigger = TRIGGER_UI_APP_CHANGE,
            )
        }
    }

    private fun setPeriodicEnforcement(packageName: String, enabled: Boolean) {
        settingsStore.setPeriodicEnforcement(packageName, enabled)
        refreshAppSetting(packageName, willApply = false)
    }

    private fun setDozeManaged(packageName: String, managed: Boolean) {
        settingsStore.setDozeManaged(packageName, managed)
        val policy = refreshAppSetting(packageName, willApply = managed).policyFor(packageName)
        if (!uiState.shizuku.granted || !policy.appEnabled || !managed) return
        runAppSettingAction(packageName, "battery-management") {
            PrivilegedServiceClient.applyAppPolicy(
                policy = AppPolicy(
                    packageName = packageName,
                    appEnabled = true,
                    dozeManaged = true,
                    dozePolicy = policy.dozePolicy,
                ),
                targetUserIds = settingsStore.loadEnabledAndroidUserIds(),
                trigger = TRIGGER_UI_APP_CHANGE,
            )
        }
    }

    private fun setDozePolicy(packageName: String, policy: AppDozePolicy) {
        settingsStore.setDozePolicy(packageName, policy)
        val appPolicy = refreshAppSetting(packageName, willApply = true).policyFor(packageName)
        if (!uiState.shizuku.granted || !appPolicy.appEnabled || !appPolicy.dozeManaged) return
        runAppSettingAction(packageName, "battery") {
            PrivilegedServiceClient.applyAppPolicy(
                policy = AppPolicy(
                    packageName = packageName,
                    appEnabled = true,
                    dozeManaged = true,
                    dozePolicy = policy,
                ),
                targetUserIds = settingsStore.loadEnabledAndroidUserIds(),
                trigger = TRIGGER_UI_APP_CHANGE,
            )
        }
    }

    private fun refreshAppSetting(packageName: String, willApply: Boolean): GuardSettings {
        val label = uiState.installedApps?.firstOrNull { it.packageName == packageName }?.label ?: packageName
        refreshState(
            getString(
                if (willApply && uiState.shizuku.granted) {
                    R.string.app_setting_changed_message
                } else {
                    R.string.app_setting_saved_message
                },
                label,
            ),
            messageIsError = false,
        )
        return uiState.settings
    }

    private fun runAppSettingAction(
        packageName: String,
        actionName: String,
        action: suspend () -> String,
    ) {
        val label = uiState.installedApps?.firstOrNull { it.packageName == packageName }?.label ?: packageName
        lifecycleScope.launch {
            runCatching { action() }
                .onSuccess { report ->
                    val succeeded = !report.contains("FAILED:") && !report.contains("exit_code=")
                    uiState = uiState.copy(
                        message = getString(
                            if (succeeded) R.string.app_setting_applied_message else R.string.settings_partially_applied_message,
                            label,
                        ),
                        messageIsError = !succeeded,
                    )
                }
                .onFailure { error ->
                    AppLog.e("UI/AppPolicy", "action=$actionName package=$packageName failed", error)
                    uiState = uiState.copy(
                        message = getString(
                            R.string.app_setting_failed_message,
                            label,
                            error.message ?: error.javaClass.simpleName,
                        ),
                        messageIsError = true,
                    )
                }
        }
    }

    private fun loadInstalledApps() {
        if (loadingInstalledApps) return
        loadingInstalledApps = true
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                InstalledFcmApps.load(applicationContext, settingsStore.loadAppPolicies().keys)
            }
            uiState = uiState.copy(installedApps = apps)
            loadingInstalledApps = false
        }
    }

    private fun setInterval(minutes: Long) {
        settingsStore.setIntervalMinutes(minutes)
        lifecycleScope.launch(Dispatchers.IO) {
            EnforcementScheduler.schedule(applicationContext, minutes)
        }
        refreshState(
            getString(R.string.frequency_changed_message, formatInterval(minutes)),
            messageIsError = false,
        )
    }

    private fun maybeApplyStaleSettings() {
        if (!uiState.shizuku.granted || uiState.applying) return
        if (!GuardSettingsStore.isPeriodicEnforcementEnabled(uiState.settings.intervalMinutes)) return
        val lastRun = settingsStore.loadLastRun()
        val intervalMillis = settingsStore.loadSettings().intervalMinutes * 60_000L
        if (lastRun == null || System.currentTimeMillis() - lastRun.timestampMillis >= intervalMillis) {
            applyPeriodicPolicies()
        }
    }

    private fun applyPeriodicPolicies() {
        if (stalePeriodicRunning) return
        stalePeriodicRunning = true
        val settings = settingsStore.loadSettings()
        lifecycleScope.launch {
            runCatching {
                PrivilegedServiceClient.enforce(
                    settings.aurogonEnabledPackages,
                    settings.periodicallyEnforcedAppPolicies,
                    settingsStore.loadEnabledAndroidUserIds(),
                    TRIGGER_UI_STALE_PERIODIC,
                )
            }.onSuccess { report ->
                stalePeriodicRunning = false
                val succeeded = !report.contains("FAILED:") && !report.contains("exit_code=")
                settingsStore.saveLastRun(succeeded, report)
                uiState = uiState.copy(lastRun = settingsStore.loadLastRun())
            }.onFailure { error ->
                stalePeriodicRunning = false
                AppLog.e("UI/Periodic", "stale periodic enforcement failed", error)
                settingsStore.saveLastRun(
                    false,
                    getString(R.string.settings_failed_message, error.message ?: error.javaClass.simpleName),
                )
                uiState = uiState.copy(lastRun = settingsStore.loadLastRun())
            }
        }
    }

    private fun applyNow() {
        if (!uiState.shizuku.granted) {
            requestShizukuAccess()
            return
        }
        if (uiState.applying) return

        val settings = settingsStore.loadSettings()
        if (settings.androidUsers.isEmpty()) {
            refreshAndroidUsers(applyAfterRefresh = true)
            return
        }

        val targetUserIds = settings.androidUsers
            .filter(AndroidUserSelection::enabled)
            .map(AndroidUserSelection::userId)
        uiState = uiState.copy(
            applying = true,
            applyProgress = ApplyProgress(0, settings.enabledAppPolicies.size),
            message = getString(R.string.applying_settings_message),
            messageIsError = false,
        )
        lifecycleScope.launch {
            runCatching {
                PrivilegedServiceClient.enforce(
                    settings.aurogonEnabledPackages,
                    settings.enabledAppPolicies,
                    targetUserIds,
                    TRIGGER_UI_APPLY,
                    onProgress = { completed, total ->
                        uiState = uiState.copy(applyProgress = ApplyProgress(completed, total))
                    },
                )
            }
                .onSuccess { report ->
                    val succeeded = !report.contains("FAILED:") && !report.contains("exit_code=")
                    settingsStore.saveLastRun(succeeded, report)
                    uiState = uiState.copy(
                        applying = false,
                        applyProgress = null,
                        lastRun = settingsStore.loadLastRun(),
                        message = getString(
                            if (succeeded) {
                                R.string.settings_applied_message
                            } else {
                                R.string.settings_partially_applied_message
                            },
                        ),
                        messageIsError = !succeeded,
                    )
                    checkMilletValue()
                }
                .onFailure { error ->
                    val report = getString(
                        R.string.settings_failed_message,
                        error.message ?: error.javaClass.simpleName,
                    )
                    settingsStore.saveLastRun(false, report)
                    uiState = uiState.copy(
                        applying = false,
                        applyProgress = null,
                        lastRun = settingsStore.loadLastRun(),
                        message = report,
                        messageIsError = true,
                    )
                }
        }
    }

    private fun refreshAndroidUsers(applyAfterRefresh: Boolean = false) {
        if (applyAfterRefresh) applyAfterAndroidUserRefresh = true
        if (!uiState.shizuku.granted || uiState.refreshingAndroidUsers) return

        uiState = uiState.copy(refreshingAndroidUsers = true)
        lifecycleScope.launch {
            runCatching {
                val output = PrivilegedServiceClient.listAndroidUsers(TRIGGER_UI_USERS)
                require(!output.startsWith("FAILED:")) { output }
                val discovered = AndroidUserSelections.parsePmListUsers(output)
                require(discovered.isNotEmpty()) { getString(R.string.no_android_profiles_error) }
                settingsStore.mergeAndSaveAndroidUsers(discovered)
            }.onSuccess { users ->
                refreshState(
                    message = resources.getQuantityString(
                        R.plurals.android_profiles_found_message,
                        users.size,
                        users.size,
                    ),
                    messageIsError = false,
                )
                uiState = uiState.copy(refreshingAndroidUsers = false)
                val shouldApply = applyAfterAndroidUserRefresh
                applyAfterAndroidUserRefresh = false
                if (shouldApply) applyNow()
            }.onFailure { error ->
                applyAfterAndroidUserRefresh = false
                uiState = uiState.copy(
                    refreshingAndroidUsers = false,
                    message = getString(
                        R.string.android_profiles_refresh_failed_message,
                        error.message ?: getString(R.string.unknown_error),
                    ),
                    messageIsError = true,
                )
            }
        }
    }

    private fun setAndroidUserEnabled(userId: Int, enabled: Boolean) {
        settingsStore.setAndroidUserEnabled(userId, enabled)
        val user = settingsStore.loadAndroidUsers().firstOrNull { it.userId == userId }
        val displayName = user?.name ?: getString(R.string.android_user_fallback, userId)
        refreshState(
            message = getString(
                if (enabled) R.string.android_profile_enabled_message else R.string.android_profile_disabled_message,
                displayName,
            ),
            messageIsError = false,
        )
    }

    private fun checkMilletValue() {
        if (!uiState.shizuku.granted || uiState.checkingMilletValue) return

        uiState = uiState.copy(checkingMilletValue = true)
        lifecycleScope.launch {
            runCatching { PrivilegedServiceClient.getMilletNoRestrictValue(TRIGGER_UI_MILLET) }
                .onSuccess { value ->
                    val failed = value.startsWith("FAILED:")
                    uiState = uiState.copy(
                        milletNoRestrictValue = value,
                        checkingMilletValue = false,
                        message = if (failed) {
                            getString(R.string.protection_status_failed_message, value.removePrefix("FAILED:").trim())
                        } else {
                            uiState.message
                        },
                        messageIsError = if (failed) true else uiState.messageIsError,
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        checkingMilletValue = false,
                        message = getString(
                            R.string.protection_status_failed_message,
                            error.message ?: getString(R.string.unknown_error),
                        ),
                        messageIsError = true,
                    )
                }
        }
    }

    private fun flushServiceLogs() {
        if (!uiState.shizuku.granted) return
        lifecycleScope.launch {
            runCatching { PrivilegedServiceClient.flushServiceLogs(TRIGGER_UI_LOGS) }
        }
    }

    private fun formatInterval(minutes: Long): String = when (minutes) {
        GuardSettingsStore.DISABLED_INTERVAL_MINUTES -> getString(R.string.off)
        60L -> getString(R.string.interval_one_hour)
        in 0L..59L -> getString(R.string.interval_minutes, minutes)
        else -> resources.getQuantityString(
            R.plurals.interval_hours,
            (minutes / 60L).toInt(),
            minutes / 60L,
        )
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 42
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val TRIGGER_UI_APPLY = "ui:apply"
        private const val TRIGGER_UI_USERS = "ui:user-list"
        private const val TRIGGER_UI_MILLET = "ui:millet-check"
        private const val TRIGGER_UI_LOGS = "ui:logs-cleared"
        private const val TRIGGER_UI_APP_CHANGE = "ui:app-change"
        private const val TRIGGER_UI_STALE_PERIODIC = "ui:stale-periodic"
    }
}

@Composable
private fun StartupCheckScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.checking_device_compatibility),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuardApp(
    state: GuardUiState,
    onRequestShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onApplyNow: () -> Unit,
    onRefreshAndroidUsers: () -> Unit,
    onAndroidUserEnabledChanged: (Int, Boolean) -> Unit,
    onCheckMilletValue: () -> Unit,
    onAppEnabledChanged: (String, Boolean) -> Unit,
    onAurogonChanged: (String, Boolean) -> Unit,
    onAutostartManagedChanged: (String, Boolean) -> Unit,
    onAutostartChanged: (String, Boolean) -> Unit,
    onPeriodicChanged: (String, Boolean) -> Unit,
    onDozeManagedChanged: (String, Boolean) -> Unit,
    onDozeChanged: (String, AppDozePolicy) -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onLogsCleared: () -> Unit,
) {
    var showingLogs by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(GuardTab.HOME) }
    BackHandler(enabled = selectedTab == GuardTab.APPS) { selectedTab = GuardTab.HOME }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectedTab == GuardTab.HOME) {
                        Column {
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.app_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(stringResource(R.string.apps_title), fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == GuardTab.HOME,
                    onClick = { selectedTab = GuardTab.HOME },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = stringResource(R.string.home),
                        )
                    },
                    label = { Text(stringResource(R.string.home)) },
                )
                NavigationBarItem(
                    selected = selectedTab == GuardTab.APPS,
                    onClick = { selectedTab = GuardTab.APPS },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Apps,
                            contentDescription = stringResource(R.string.apps_title),
                        )
                    },
                    label = { Text(stringResource(R.string.apps_title)) },
                )
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            GuardTab.HOME -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    end = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { ShizukuCard(state, onRequestShizuku, onOpenShizuku) }
                item { ProtectionCard(state = state, onApplyNow = onApplyNow) }
                item {
                    AndroidUsersCard(
                        state = state,
                        onRefreshUsers = onRefreshAndroidUsers,
                        onUserEnabledChanged = onAndroidUserEnabledChanged,
                    )
                }
                item { MilletNoRestrictCard(state = state, onCheckValue = onCheckMilletValue) }
                item {
                    IntervalCard(
                        selectedMinutes = state.settings.intervalMinutes,
                        onIntervalSelected = onIntervalSelected,
                    )
                }
                state.lastRun?.let { lastRun -> item { LastRunCard(lastRun) } }
                item { LogsCard(onOpenLogs = { showingLogs = true }) }
            }

            GuardTab.APPS -> AppManagementScreen(
                apps = state.installedApps,
                policies = state.settings.appPolicies,
                modifier = Modifier.padding(innerPadding),
                onAppEnabledChanged = onAppEnabledChanged,
                onAurogonChanged = onAurogonChanged,
                onAutostartManagedChanged = onAutostartManagedChanged,
                onAutostartChanged = onAutostartChanged,
                onPeriodicChanged = onPeriodicChanged,
                onDozeManagedChanged = onDozeManagedChanged,
                onDozeChanged = onDozeChanged,
            )
        }
    }
    if (showingLogs) {
        LogViewerScreen(
            onDismiss = {
                showingLogs = false
            },
            onLogsCleared = onLogsCleared,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnsupportedDeviceScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.unsupported_device),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.unsupported_device_description),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ShizukuCard(
    state: GuardUiState,
    onRequestShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val statusText = stringResource(
        when (state.shizuku.connectionState) {
            ShizukuConnectionState.CHECKING -> R.string.shizuku_checking
            ShizukuConnectionState.NOT_RUNNING -> R.string.shizuku_not_running
            ShizukuConnectionState.PERMISSION_REQUIRED -> R.string.shizuku_permission_required
            ShizukuConnectionState.CONNECTED -> R.string.shizuku_connected
        },
    )
    val containerColor = if (state.shizuku.granted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.shizuku), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.shizuku_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!state.shizuku.granted) {
                    Button(onClick = onRequestShizuku) {
                        Text(
                            stringResource(
                                if (state.shizuku.available) R.string.grant_access else R.string.open_shizuku,
                            ),
                        )
                    }
                }
                if (state.shizuku.available) {
                    OutlinedButton(onClick = onOpenShizuku) { Text(stringResource(R.string.manage)) }
                }
            }
        }
    }
}

@Composable
private fun ProtectionCard(state: GuardUiState, onApplyNow: () -> Unit) {
    val policies = state.settings.enabledAppPolicies
    val statusText = stringResource(
        R.string.protection_summary,
        state.settings.aurogonEnabledPackages.size,
        policies.count { it.autostartManaged && it.autostartEnabled },
        state.settings.periodicallyEnforcedAppPolicies.size,
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.automatic_protection),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
            )
            state.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onApplyNow,
                enabled = state.shizuku.granted && !state.applying,
            ) {
                if (state.applying) {
                    val progress = state.applyProgress ?: ApplyProgress(0, 0)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.applying_progress, progress.completedApps, progress.totalApps))
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = LocalContentColor.current,
                        )
                    }
                } else {
                    Text(stringResource(R.string.apply_now))
                }
            }
        }
    }
}

@Composable
private fun AndroidUsersCard(
    state: GuardUiState,
    onRefreshUsers: () -> Unit,
    onUserEnabledChanged: (Int, Boolean) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.android_profiles),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.android_profiles_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.settings.androidUsers.isEmpty()) {
                Text(
                    stringResource(R.string.android_profiles_empty),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.settings.androidUsers.forEachIndexed { index, user ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user.name, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.android_user_id, user.userId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = user.enabled,
                            enabled = !state.applying && !state.refreshingAndroidUsers,
                            onCheckedChange = { enabled ->
                                onUserEnabledChanged(user.userId, enabled)
                            },
                        )
                    }
                }
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                onClick = onRefreshUsers,
                enabled = state.shizuku.granted && !state.refreshingAndroidUsers && !state.applying,
            ) {
                Text(
                    stringResource(
                        if (state.refreshingAndroidUsers) R.string.refreshing else R.string.refresh_profiles,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MilletNoRestrictCard(
    state: GuardUiState,
    onCheckValue: () -> Unit,
) {
    val rawValue = state.milletNoRestrictValue
    val readFailed = rawValue?.startsWith("FAILED:") == true
    val packages = if (rawValue != null && !readFailed) {
        MilletNoRestrictList.parse(rawValue)
    } else {
        emptyList()
    }
    val gmsPresent = MilletNoRestrictList.GMS_PACKAGE in packages
    val status = stringResource(
        when {
            rawValue == null -> R.string.gms_status_not_checked
            readFailed -> R.string.gms_status_unavailable
            gmsPresent -> R.string.gms_status_protected
            else -> R.string.gms_status_needs_attention
        },
    )
    val statusColor = when {
        rawValue == null -> MaterialTheme.colorScheme.onSurfaceVariant
        readFailed || !gmsPresent -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                MilletNoRestrictList.SETTING_NAME,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(status, style = MaterialTheme.typography.bodyMedium, color = statusColor)
            Text(
                stringResource(R.string.millet_no_restrict_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rawValue != null && !readFailed) {
                if (packages.isEmpty()) {
                    Text(
                        stringResource(R.string.millet_no_restrict_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column {
                        packages.forEachIndexed { index, packageName ->
                            if (index > 0) HorizontalDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (packageName == MilletNoRestrictList.GMS_PACKAGE) {
                                    Text(
                                        stringResource(R.string.google_play_services),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Text(
                                    packageName,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = if (packageName == MilletNoRestrictList.GMS_PACKAGE) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCheckValue,
                enabled = state.shizuku.granted && !state.checkingMilletValue,
            ) {
                Text(
                    stringResource(
                        if (state.checkingMilletValue) R.string.checking else R.string.refresh_status,
                    ),
                )
            }
        }
    }
}

@Composable
private fun IntervalCard(
    selectedMinutes: Long,
    onIntervalSelected: (Long) -> Unit,
) {
    val options = remember {
        listOf(GuardSettingsStore.DISABLED_INTERVAL_MINUTES, 15L, 60L, 180L, 360L)
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.check_frequency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            options.chunked(2).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowOptions.forEach { minutes ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedMinutes == minutes,
                            onClick = { onIntervalSelected(minutes) },
                            label = { Text(formatInterval(minutes)) },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.check_frequency_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LastRunCard(lastRun: LastRun) {
    var expanded by rememberSaveable(lastRun.timestampMillis) { mutableStateOf(false) }
    val formattedTime = remember(lastRun.timestampMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            .format(Date(lastRun.timestampMillis))
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                stringResource(R.string.last_check),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    if (lastRun.succeeded) R.string.last_check_successful else R.string.last_check_attention,
                    formattedTime,
                ),
                color = if (lastRun.succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(if (expanded) R.string.hide_details else R.string.view_technical_details),
                )
            }
            if (expanded) {
                Text(
                    lastRun.report,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LogsCard(onOpenLogs: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.diagnostics),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.diagnostics_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenLogs,
            ) {
                Text(stringResource(R.string.open_diagnostics))
            }
        }
    }
}

@Composable
private fun formatInterval(minutes: Long): String = when (minutes) {
    GuardSettingsStore.DISABLED_INTERVAL_MINUTES -> stringResource(R.string.off)
    60L -> stringResource(R.string.interval_one_hour)
    in 0L..59L -> stringResource(R.string.interval_minutes, minutes)
    else -> pluralStringResource(
        R.plurals.interval_hours,
        (minutes / 60L).toInt(),
        minutes / 60L,
    )
}
