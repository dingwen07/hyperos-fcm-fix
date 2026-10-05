package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppManagementScreen(
    apps: List<InstalledFcmApp>?,
    policies: Map<String, AppPolicy>,
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    showSearchField: Boolean = true,
    directive: PaneScaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2()),
    onAppEnabledChanged: (String, Boolean) -> Unit,
    onAurogonChanged: (String, Boolean) -> Unit,
    onAutoUnstopChanged: (String, Boolean) -> Unit,
    onAutostartManagedChanged: (String, Boolean) -> Unit,
    onAutostartChanged: (String, Boolean) -> Unit,
    onDozeManagedChanged: (String, Boolean) -> Unit,
    onDozeChanged: (String, AppDozePolicy) -> Unit,
) {
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val policyScroll = key(selectedPackage) { rememberScrollState() }
    val configFor = apps?.firstOrNull { it.packageName == selectedPackage }
        ?.takeIf { policies.policyFor(it.packageName).appEnabled }
    // Loading during recreation must not discard the restored selection.
    LaunchedEffect(apps, selectedPackage, configFor) {
        if (apps != null && selectedPackage != null && configFor == null) selectedPackage = null
    }
    val locale = LocalConfiguration.current.locales[0]
    val normalizedQuery = query.trim().lowercase(locale)
    val matching = apps.orEmpty().filter { app ->
        normalizedQuery.isEmpty() ||
            app.label.lowercase(locale).contains(normalizedQuery) ||
            app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
    }
    val sorted = matching.sortedWith(
        compareBy<InstalledFcmApp> { it.label.lowercase(locale) }
            .thenBy(InstalledFcmApp::packageName),
    )
    val enabled = sorted.filter { policies.policyFor(it.packageName).appEnabled }
    val disabled = sorted.filterNot { policies.policyFor(it.packageName).appEnabled }

    AdaptiveDetailLayout(
        selectedKey = selectedPackage,
        onDismiss = { selectedPackage = null },
        title = configFor?.label.orEmpty(),
        modifier = modifier,
        directive = directive,
        detail = { isPane ->
            configFor?.let { app ->
                AppPolicyContent(
                    app = app,
                    policy = policies.policyFor(app.packageName),
                    scrollState = policyScroll,
                    showTitle = !isPane,
                    onAurogonChanged = onAurogonChanged,
                    onAutoUnstopChanged = onAutoUnstopChanged,
                    onAutostartManagedChanged = onAutostartManagedChanged,
                    onAutostartChanged = onAutostartChanged,
                    onDozeManagedChanged = onDozeManagedChanged,
                    onDozeChanged = onDozeChanged,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (showSearchField) AppSearchField(
                query = query,
                onQueryChanged = onQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                else -> LazyColumn(Modifier.fillMaxSize().testTag("apps-list")) {
                    if (enabled.isNotEmpty()) {
                        stickyHeader { AppSectionHeader(stringResource(R.string.apps_section_enabled), enabled.size) }
                        items(enabled, key = InstalledFcmApp::packageName) { app ->
                            AppPolicyRow(
                                app, policies.policyFor(app.packageName), app.packageName == selectedPackage,
                                onAppEnabledChanged,
                            ) { selectedPackage = app.packageName }
                        }
                    }
                    if (disabled.isNotEmpty()) {
                        stickyHeader { AppSectionHeader(stringResource(R.string.apps_section_all), disabled.size) }
                        items(disabled, key = InstalledFcmApp::packageName) { app ->
                            AppPolicyRow(
                                app, policies.policyFor(app.packageName), app.packageName == selectedPackage,
                                onAppEnabledChanged,
                            ) { selectedPackage = app.packageName }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPolicyRow(
    app: InstalledFcmApp,
    policy: AppPolicy,
    isSelected: Boolean,
    onAppEnabledChanged: (String, Boolean) -> Unit,
    onOpenConfig: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .testTag("app-row:${app.packageName}")
            .semantics { selected = isSelected }
            .clickable(enabled = policy.appEnabled, onClick = onOpenConfig),
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
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
                modifier = Modifier.testTag("app-enabled:${app.packageName}"),
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
private fun AppPolicyContent(
    app: InstalledFcmApp,
    policy: AppPolicy,
    scrollState: ScrollState,
    showTitle: Boolean,
    onAurogonChanged: (String, Boolean) -> Unit,
    onAutoUnstopChanged: (String, Boolean) -> Unit,
    onAutostartManagedChanged: (String, Boolean) -> Unit,
    onAutostartChanged: (String, Boolean) -> Unit,
    onDozeManagedChanged: (String, Boolean) -> Unit,
    onDozeChanged: (String, AppDozePolicy) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTitle) Text(app.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 360.dp * LocalDensity.current.fontScale) {
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        batteryPolicies.forEach { batteryPolicy ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                    selected = policy.dozePolicy == batteryPolicy,
                                    role = Role.RadioButton,
                                    onClick = { onDozeChanged(app.packageName, batteryPolicy) },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = policy.dozePolicy == batteryPolicy, onClick = null)
                                Text(stringResource(batteryPolicy.titleRes), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                } else {
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
