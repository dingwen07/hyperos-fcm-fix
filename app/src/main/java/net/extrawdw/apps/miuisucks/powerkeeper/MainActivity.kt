package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

data class GuardUiState(
    val settings: GuardSettings = GuardSettings(
        wechatPolicy = WechatPolicy.OPTIMIZED,
        intervalMinutes = GuardSettingsStore.DEFAULT_INTERVAL_MINUTES,
        androidUsers = emptyList(),
    ),
    val shizuku: ShizukuStatus = ShizukuStatus(),
    val lastRun: LastRun? = null,
    val applying: Boolean = false,
    val milletNoRestrictValue: String? = null,
    val checkingMilletValue: Boolean = false,
    val refreshingAndroidUsers: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: GuardSettingsStore
    private var uiState by mutableStateOf(GuardUiState())
    private var shizukuListenersRegistered = false
    private var applyAfterAndroidUserRefresh = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshState()
        if (uiState.shizuku.granted) applyNow()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refreshState(getString(R.string.shizuku_stopped_message), messageIsError = true)
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
        if (granted) applyNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val compatibility = XiaomiCompatibility.check(applicationContext)
        if (!compatibility.supported) {
            AppLog.e("Compatibility", compatibility.reason)
            setContent {
                MIUIPowerKeeperFixTheme {
                    UnsupportedDeviceScreen()
                }
            }
            return
        }

        settingsStore = GuardSettingsStore(applicationContext)
        EnforcementScheduler.schedule(applicationContext, settingsStore.loadSettings().intervalMinutes)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        shizukuListenersRegistered = true
        refreshState()

        setContent {
            MIUIPowerKeeperFixTheme {
                GuardApp(
                    state = uiState,
                    onRequestShizuku = ::requestShizukuAccess,
                    onOpenShizuku = ::openShizuku,
                    onApplyNow = ::applyNow,
                    onRefreshAndroidUsers = { refreshAndroidUsers() },
                    onAndroidUserEnabledChanged = ::setAndroidUserEnabled,
                    onCheckMilletValue = ::checkMilletValue,
                    onPolicySelected = ::setWechatPolicy,
                    onIntervalSelected = ::setInterval,
                    onLogsCleared = ::flushServiceLogs,
                )
            }
        }
        maybeApplyStaleSettings()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsStore.isInitialized) {
            refreshState()
            if (uiState.shizuku.granted) {
                if (uiState.settings.androidUsers.isEmpty()) refreshAndroidUsers()
                if (!uiState.applying) checkMilletValue()
            }
        }
    }

    override fun onDestroy() {
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
        val available = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        val granted = available && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val connectionState = when {
            !available -> ShizukuConnectionState.NOT_RUNNING
            !granted -> ShizukuConnectionState.PERMISSION_REQUIRED
            else -> ShizukuConnectionState.CONNECTED
        }

        uiState = uiState.copy(
            settings = settingsStore.loadSettings(),
            shizuku = ShizukuStatus(available, granted, connectionState),
            lastRun = settingsStore.loadLastRun(),
            message = message,
            messageIsError = messageIsError,
        )
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

    private fun setWechatPolicy(policy: WechatPolicy) {
        if (policy == uiState.settings.wechatPolicy) return
        settingsStore.setWechatPolicy(policy)
        refreshState(
            getString(R.string.wechat_policy_changed_message, getString(policy.titleRes)),
            messageIsError = false,
        )
        if (uiState.shizuku.granted) applyNow()
    }

    private fun setInterval(minutes: Long) {
        settingsStore.setIntervalMinutes(minutes)
        EnforcementScheduler.schedule(applicationContext, minutes)
        refreshState(
            getString(R.string.frequency_changed_message, formatInterval(minutes)),
            messageIsError = false,
        )
    }

    private fun maybeApplyStaleSettings() {
        if (!uiState.shizuku.granted || uiState.applying) return
        val lastRun = settingsStore.loadLastRun()
        val intervalMillis = settingsStore.loadSettings().intervalMinutes * 60_000L
        if (lastRun == null || System.currentTimeMillis() - lastRun.timestampMillis >= intervalMillis) {
            applyNow()
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
            message = getString(R.string.applying_settings_message),
            messageIsError = false,
        )
        lifecycleScope.launch {
            runCatching {
                PrivilegedServiceClient.enforce(settings.wechatPolicy, targetUserIds, TRIGGER_UI_APPLY)
            }
                .onSuccess { report ->
                    val succeeded = !report.contains("FAILED:") && !report.contains("exit_code=")
                    settingsStore.saveLastRun(succeeded, report)
                    uiState = uiState.copy(
                        applying = false,
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
        if (uiState.shizuku.granted) applyNow()
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
    onPolicySelected: (WechatPolicy) -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onLogsCleared: () -> Unit,
) {
    var showingLogs by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
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
            item {
                ProtectionCard(
                    state = state,
                    onApplyNow = onApplyNow,
                )
            }
            item {
                AndroidUsersCard(
                    state = state,
                    onRefreshUsers = onRefreshAndroidUsers,
                    onUserEnabledChanged = onAndroidUserEnabledChanged,
                )
            }
            item {
                MilletNoRestrictCard(
                    state = state,
                    onCheckValue = onCheckMilletValue,
                )
            }
            item {
                WechatPolicyCard(
                    selectedPolicy = state.settings.wechatPolicy,
                    onPolicySelected = onPolicySelected,
                )
            }
            item {
                IntervalCard(
                    selectedMinutes = state.settings.intervalMinutes,
                    onIntervalSelected = onIntervalSelected,
                )
            }
            state.lastRun?.let { lastRun ->
                item { LastRunCard(lastRun) }
            }
            item {
                LogsCard(
                    onOpenLogs = {
                        showingLogs = true
                    },
                )
            }
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
    val enabledUserCount = if (state.settings.androidUsers.isEmpty()) {
        AndroidUserSelections.DEFAULT_ENABLED_USER_IDS.size
    } else {
        state.settings.androidUsers.count(AndroidUserSelection::enabled)
    }
    val wechatStatus = if (state.settings.wechatPolicy == WechatPolicy.DISABLED) {
        stringResource(R.string.wechat_policy_off_status)
    } else {
        pluralStringResource(
            R.plurals.wechat_policy_active_status,
            enabledUserCount,
            stringResource(state.settings.wechatPolicy.titleRes),
            enabledUserCount,
        )
    }
    val statusText = stringResource(R.string.protection_summary, wechatStatus)

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
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.applying),
                        modifier = Modifier.padding(start = 10.dp),
                    )
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
private fun WechatPolicyCard(
    selectedPolicy: WechatPolicy,
    onPolicySelected: (WechatPolicy) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.wechat_battery_policy),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.wechat_battery_policy_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            WechatPolicy.entries.forEachIndexed { index, policy ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedPolicy == policy,
                            onClick = { onPolicySelected(policy) },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedPolicy == policy,
                        onClick = null,
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(policy.titleRes), fontWeight = FontWeight.Medium)
                            if (policy == WechatPolicy.OPTIMIZED) {
                                Text(
                                    stringResource(R.string.recommended),
                                    modifier = Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            stringResource(policy.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IntervalCard(
    selectedMinutes: Long,
    onIntervalSelected: (Long) -> Unit,
) {
    val options = remember { listOf(15L, 60L, 180L, 360L) }
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
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
    60L -> stringResource(R.string.interval_one_hour)
    in 0L..59L -> stringResource(R.string.interval_minutes, minutes)
    else -> pluralStringResource(
        R.plurals.interval_hours,
        (minutes / 60L).toInt(),
        minutes / 60L,
    )
}
