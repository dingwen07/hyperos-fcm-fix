package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagementScreen(
    apps: List<InstalledFcmApp>?,
    policies: Map<String, AppPolicy>,
    modifier: Modifier = Modifier,
    onAppEnabledChanged: (String, Boolean) -> Unit,
    onAurogonChanged: (String, Boolean) -> Unit,
    onAutoUnstopChanged: (String, Boolean) -> Unit,
    onAutostartManagedChanged: (String, Boolean) -> Unit,
    onAutostartChanged: (String, Boolean) -> Unit,
    onPeriodicChanged: (String, Boolean) -> Unit,
    onDozeManagedChanged: (String, Boolean) -> Unit,
    onDozeChanged: (String, AppDozePolicy) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var configFor by remember { mutableStateOf<InstalledFcmApp?>(null) }
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    val matching = apps.orEmpty().filter { app ->
        normalizedQuery.isEmpty() ||
            app.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
            app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
    }
    val sorted = matching.sortedWith(
        compareBy<InstalledFcmApp> { it.label.lowercase(Locale.getDefault()) }
            .thenBy(InstalledFcmApp::packageName),
    )
    val enabled = sorted.filter { policies.policyFor(it.packageName).appEnabled }
    val disabled = sorted.filterNot { policies.policyFor(it.packageName).appEnabled }

    Column(modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.apps_search_hint)) },
                singleLine = true,
            )
            when {
                apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                matching.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) stringResource(R.string.apps_empty) else stringResource(R.string.apps_no_match, query),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (enabled.isNotEmpty()) {
                        stickyHeader { AppSectionHeader(stringResource(R.string.apps_section_enabled), enabled.size) }
                        items(enabled, key = { "enabled:${it.packageName}" }) { app ->
                            AppPolicyRow(app, policies.policyFor(app.packageName), onAppEnabledChanged) { configFor = app }
                        }
                    }
                    if (disabled.isNotEmpty()) {
                        stickyHeader { AppSectionHeader(stringResource(R.string.apps_section_all), disabled.size) }
                        items(disabled, key = { "all:${it.packageName}" }) { app ->
                            AppPolicyRow(app, policies.policyFor(app.packageName), onAppEnabledChanged) { configFor = app }
                        }
                    }
                }
            }
    }

    configFor?.let { app ->
        AppPolicySheet(
            app = app,
            policy = policies.policyFor(app.packageName),
            onDismiss = { configFor = null },
            onAurogonChanged = onAurogonChanged,
            onAutoUnstopChanged = onAutoUnstopChanged,
            onAutostartManagedChanged = onAutostartManagedChanged,
            onAutostartChanged = onAutostartChanged,
            onPeriodicChanged = onPeriodicChanged,
            onDozeManagedChanged = onDozeManagedChanged,
            onDozeChanged = onDozeChanged,
        )
    }
}

@Composable
private fun AppPolicyRow(
    app: InstalledFcmApp,
    policy: AppPolicy,
    onAppEnabledChanged: (String, Boolean) -> Unit,
    onOpenConfig: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(
            enabled = policy.appEnabled,
            onClick = onOpenConfig,
        ),
        leadingContent = { AppIcon(app.icon) },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (app.installed) app.packageName else stringResource(R.string.app_not_installed, app.packageName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Switch(
                checked = policy.appEnabled,
                onCheckedChange = { onAppEnabledChanged(app.packageName, it) },
            )
        },
    )
}

@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(CircleShape),
        )
    } else {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun AppSectionHeader(title: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPolicySheet(
    app: InstalledFcmApp,
    policy: AppPolicy,
    onDismiss: () -> Unit,
    onAurogonChanged: (String, Boolean) -> Unit,
    onAutoUnstopChanged: (String, Boolean) -> Unit,
    onAutostartManagedChanged: (String, Boolean) -> Unit,
    onAutostartChanged: (String, Boolean) -> Unit,
    onPeriodicChanged: (String, Boolean) -> Unit,
    onDozeManagedChanged: (String, Boolean) -> Unit,
    onDozeChanged: (String, AppDozePolicy) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(app.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            PolicySwitchRow(
                title = stringResource(R.string.aurogon_protection),
                description = stringResource(R.string.aurogon_protection_description),
                checked = policy.aurogonEnabled,
                onCheckedChange = { onAurogonChanged(app.packageName, it) },
            )
            PolicySwitchRow(
                title = stringResource(R.string.auto_unstop),
                description = stringResource(R.string.auto_unstop_description),
                checked = policy.autoUnstopEnabled,
                onCheckedChange = { onAutoUnstopChanged(app.packageName, it) },
            )
            PolicySwitchRow(
                title = stringResource(R.string.miui_autostart),
                description = stringResource(R.string.miui_autostart_description),
                checked = policy.autostartManaged,
                onCheckedChange = { onAutostartManagedChanged(app.packageName, it) },
            )
            if (policy.autostartManaged) {
                val autostartValues = listOf(true, false)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    autostartValues.forEachIndexed { index, enabled ->
                        SegmentedButton(
                            selected = policy.autostartEnabled == enabled,
                            onClick = { onAutostartChanged(app.packageName, enabled) },
                            shape = SegmentedButtonDefaults.itemShape(index, autostartValues.size),
                        ) {
                            Text(
                                stringResource(
                                    if (enabled) R.string.policy_enabled else R.string.policy_disabled,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            PolicySwitchRow(
                title = stringResource(R.string.periodic_enforcement),
                description = stringResource(R.string.periodic_enforcement_description),
                checked = policy.periodicEnforcement,
                onCheckedChange = { onPeriodicChanged(app.packageName, it) },
            )

            HorizontalDivider()
            PolicySwitchRow(
                title = stringResource(R.string.aosp_doze_policy),
                description = stringResource(R.string.aosp_doze_policy_description),
                checked = policy.dozeManaged,
                onCheckedChange = { onDozeManagedChanged(app.packageName, it) },
            )
            if (policy.dozeManaged) {
                val batteryPolicies = listOf(
                    AppDozePolicy.UNRESTRICTED,
                    AppDozePolicy.DEFAULT,
                    AppDozePolicy.RESTRICTED,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    batteryPolicies.forEachIndexed { index, batteryPolicy ->
                        SegmentedButton(
                            selected = policy.dozePolicy == batteryPolicy,
                            onClick = { onDozeChanged(app.packageName, batteryPolicy) },
                            shape = SegmentedButtonDefaults.itemShape(index, batteryPolicies.size),
                        ) {
                            Text(stringResource(batteryPolicy.titleRes), maxLines = 1)
                        }
                    }
                }
                Text(
                    stringResource(policy.dozePolicy.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PolicySwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun Map<String, AppPolicy>.policyFor(packageName: String): AppPolicy =
    this[packageName] ?: AppPolicyDefaults.forPackage(packageName)
