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

data class ShizukuStatus(
    val available: Boolean = false,
    val granted: Boolean = false,
    val summary: String = "Checking Shizuku…",
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
        refreshState("Shizuku stopped. Automatic checks are paused.", messageIsError = true)
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        refreshState(
            message = if (granted) {
                "Shizuku access granted. Applying your settings…"
            } else {
                "Shizuku access was denied."
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
            setContent {
                MIUIPowerKeeperFixTheme {
                    UnsupportedDeviceScreen(compatibility.reason)
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
        val summary = when {
            !available -> "Not running"
            !granted -> "Permission required"
            else -> "Connected"
        }

        uiState = uiState.copy(
            settings = settingsStore.loadSettings(),
            shizuku = ShizukuStatus(available, granted, summary),
            lastRun = settingsStore.loadLastRun(),
            message = message,
            messageIsError = messageIsError,
        )
    }

    private fun requestShizukuAccess() {
        if (!uiState.shizuku.available) {
            openShizuku()
            uiState = uiState.copy(message = "Start Shizuku, then return here.", messageIsError = false)
            return
        }
        if (uiState.shizuku.granted) {
            uiState = uiState.copy(message = "Shizuku access is already granted.", messageIsError = false)
            return
        }
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                openShizuku()
                uiState = uiState.copy(
                    message = "Authorization was denied previously. Re-enable this app in Shizuku.",
                    messageIsError = true,
                )
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }
        }.onFailure {
            uiState = uiState.copy(
                message = "Could not request Shizuku access: ${it.message}",
                messageIsError = true,
            )
        }
    }

    private fun openShizuku() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            uiState = uiState.copy(message = "Shizuku is not installed.", messageIsError = true)
        }
    }

    private fun setWechatPolicy(policy: WechatPolicy) {
        if (policy == uiState.settings.wechatPolicy) return
        settingsStore.setWechatPolicy(policy)
        refreshState("WeChat policy changed to ${policy.title}.", messageIsError = false)
        if (uiState.shizuku.granted) applyNow()
    }

    private fun setInterval(minutes: Long) {
        settingsStore.setIntervalMinutes(minutes)
        EnforcementScheduler.schedule(applicationContext, minutes)
        refreshState("Check frequency changed to ${formatInterval(minutes)}.", messageIsError = false)
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
        uiState = uiState.copy(applying = true, message = "Applying settings…", messageIsError = false)
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
                        message = if (succeeded) "Settings applied." else "Some settings could not be applied. View the details below.",
                        messageIsError = !succeeded,
                    )
                    checkMilletValue()
                }
                .onFailure { error ->
                    val report = "Enforcement failed: ${error.message ?: error.javaClass.simpleName}"
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
                require(discovered.isNotEmpty()) { "No Android users were returned by PackageManager." }
                settingsStore.mergeAndSaveAndroidUsers(discovered)
            }.onSuccess { users ->
                refreshState(
                    message = "Found ${users.size} Android ${if (users.size == 1) "user" else "users"}.",
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
                    message = "Could not refresh Android users: ${error.message}",
                    messageIsError = true,
                )
            }
        }
    }

    private fun setAndroidUserEnabled(userId: Int, enabled: Boolean) {
        settingsStore.setAndroidUserEnabled(userId, enabled)
        val user = settingsStore.loadAndroidUsers().firstOrNull { it.userId == userId }
        refreshState(
            message = "${user?.name ?: "User $userId"} ${if (enabled) "enabled" else "disabled"} for WeChat.",
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
                        message = if (failed) value else uiState.message,
                        messageIsError = if (failed) true else uiState.messageIsError,
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        checkingMilletValue = false,
                        message = "Could not read ${MilletNoRestrictList.SETTING_NAME}: ${error.message}",
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
                            "WeChat policy + background FCM",
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
private fun UnsupportedDeviceScreen(reason: String) {
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
                "Unsupported device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "This app is designed for Xiaomi devices running MIUI or HyperOS.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
            Text("Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(state.shizuku.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Shizuku lets this app manage HyperOS battery settings without root.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!state.shizuku.granted) {
                    Button(onClick = onRequestShizuku) {
                        Text(if (state.shizuku.available) "Grant access" else "Open Shizuku")
                    }
                }
                if (state.shizuku.available) {
                    OutlinedButton(onClick = onOpenShizuku) { Text("Manage") }
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
        "WeChat policy changes are off."
    } else {
        "WeChat ${state.settings.wechatPolicy.title.lowercase()} is active for $enabledUserCount selected ${if (enabledUserCount == 1) "user" else "users"}."
    }
    val statusText = "PowerKeeper FCM protection promptly restores Google Play services to HyperOS's no-restrictions list. $wechatStatus"

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Automatic protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                        text = "Applying…",
                        modifier = Modifier.padding(start = 10.dp),
                    )
                } else {
                    Text("Apply now")
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
                Text("Android users", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Select the Android users where the WeChat policy is enforced. User IDs, names, and selections are saved locally. Users 0 and 999 default to enabled.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.settings.androidUsers.isEmpty()) {
                Text(
                    "No saved user list. It will be queried once Shizuku is available.",
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
                                "User ${user.userId}",
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
                Text(if (state.refreshingAndroidUsers) "Refreshing…" else "Refresh user list")
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
    val gmsPresent = rawValue != null && !readFailed &&
        MilletNoRestrictList.GMS_PACKAGE in MilletNoRestrictList.parse(rawValue)
    val status = when {
        rawValue == null -> "Not checked yet"
        readFailed -> "Could not read the setting"
        gmsPresent -> "Google Play services is present"
        else -> "Google Play services is missing"
    }
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
                rawValue ?: "Use Shizuku to read the current owner-user value.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCheckValue,
                enabled = state.shizuku.granted && !state.checkingMilletValue,
            ) {
                Text(if (state.checkingMilletValue) "Checking…" else "Check current value")
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
                Text("WeChat battery policy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose how WeChat can run in the background for the enabled Android users above.",
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
                            Text(policy.title, fontWeight = FontWeight.Medium)
                            if (policy == WechatPolicy.OPTIMIZED) {
                                Text(
                                    "  Recommended",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            policy.description,
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
            Text("Check frequency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                "How often the selected WeChat policy is reapplied. FCM uses its own two-second Shizuku monitor and a fixed 15-minute recovery job.",
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
            Text("Last check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (lastRun.succeeded) "Successful · $formattedTime" else "Needs attention · $formattedTime",
                color = if (lastRun.succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "View details")
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
            Text("Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Review WorkManager runs, Shizuku connections, privileged commands, and FCM repairs.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenLogs,
            ) {
                Text("Open log viewer")
            }
        }
    }
}

private fun formatInterval(minutes: Long): String = when (minutes) {
    15L -> "15 min"
    60L -> "1 hour"
    else -> "${minutes / 60} hours"
}
