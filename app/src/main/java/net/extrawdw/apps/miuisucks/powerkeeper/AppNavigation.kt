package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
internal enum class GuardTab : NavKey { HOME, APPS }

/** Material chooses a bar or rail from window size and fold posture. Nav3 owns Back and state. */
@Composable
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun GuardNavigation(content: @Composable (GuardTab) -> Unit) {
    val backStack = rememberNavBackStack(GuardTab.HOME)
    val currentContent = rememberUpdatedState(content)
    val selected = backStack.last()
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val window = LocalWindowInfo.current.containerSize
    val layoutType = if (
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(600) && window.width > window.height &&
        !adaptiveInfo.windowPosture.isTabletop
    ) NavigationSuiteType.NavigationRail
    else NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            GuardTab.entries.forEach { tab ->
                val labelRes = if (tab == GuardTab.HOME) R.string.home else R.string.apps_title
                item(
                    selected = selected == tab,
                    onClick = {
                        if (tab == GuardTab.HOME) {
                            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                        } else if (selected != tab) {
                            backStack.add(tab)
                        }
                    },
                    icon = { Icon(if (tab == GuardTab.HOME) Icons.Default.Home else Icons.Default.Apps, stringResource(labelRes)) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = { key -> NavEntry(key) { currentContent.value(key as GuardTab) } },
        )
    }
}
