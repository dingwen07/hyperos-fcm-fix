package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Basic Apps adaptation, using the real Material/Nav3 layouts in a tablet test host. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val wide = mutableStateOf(true)
    private val appsLoaded = mutableStateOf(true)
    private var restoredWide = true
    private val apps = listOf(InstalledFcmApp("example.app", "Example app", null, true))
    private val policies = mapOf("example.app" to AppPolicy("example.app", appEnabled = true))
    private var safeTop = 0
    private var windowBottom = 0

    @Test
    fun railShowsNarrowerAppListAndSettingsBehindNavigationBar() {
        var windowBottom = 0
        compose.setContent {
            val activity = LocalActivity.current as ComponentActivity
            DisposableEffect(activity) {
                activity.enableEdgeToEdge()
                activity.window.isNavigationBarContrastEnforced = false
                onDispose {}
            }
            val height = LocalWindowInfo.current.containerSize.height
            SideEffect { windowBottom = height }
            AppFixture()
        }
        compose.onNodeWithText("Apps").performClick()
        compose.onNodeWithTag("app-row:example.app").performClick()
        compose.onNodeWithTag("app-detail-pane").assertIsDisplayed()
        compose.onNodeWithTag("app-row:example.app").assertIsSelected()
        val list = compose.onNodeWithTag("apps-list").fetchSemanticsNode().boundsInWindow
        val detail = compose.onNodeWithTag("app-detail-pane").fetchSemanticsNode().boundsInWindow
        assertTrue("app list is narrower than settings", list.width < detail.width)
        assertTrue("list reaches behind navigation bar", kotlin.math.abs(list.bottom - windowBottom) < 2f)
        assertTrue("settings surface reaches behind navigation bar", kotlin.math.abs(detail.bottom - windowBottom) < 2f)
        compose.onNodeWithTag("app-detail-back").performClick()
        compose.onNodeWithTag("app-detail-pane").assertDoesNotExist()
        compose.onNodeWithTag("app-row:example.app").assertIsDisplayed()
    }

    @Composable
    private fun AppFixture() {
        MaterialTheme {
            GuardApp(
                state = GuardUiState(installedApps = apps, settings = GuardUiState().settings.copy(appPolicies = policies)),
                onRequestShizuku = {}, onOpenShizuku = {}, onApplyNow = {},
                onRefreshAndroidUsers = {}, onAndroidUserEnabledChanged = { _, _ -> },
                onRefreshProtectionStatus = {}, onMilletPollingIntervalSelected = {},
                onFcmReconnectEnabledChanged = {}, onNighttimeFcmProtectionEnabledChanged = {},
                onOpenFcmDiagnostics = {}, onAppEnabledChanged = { _, _ -> },
                onAurogonChanged = { _, _ -> }, onAutoUnstopChanged = { _, _ -> },
                onAutostartManagedChanged = { _, _ -> }, onAutostartChanged = { _, _ -> },
                onDozeManagedChanged = { _, _ -> }, onDozeChanged = { _, _ -> },
                onIntervalSelected = {}, onLogsCleared = {},
            )
        }
    }

    @Test
    fun repeatedResizingAndRecreationKeepSheetExpandedRetainingSelectionAndSearch() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Fixture() }
        compose.onNodeWithTag("apps-search").performTextInput("Example")
        compose.onNodeWithTag("app-row:example.app").performClick()
        compose.onNodeWithTag("app-detail-pane").assertIsDisplayed()
        repeat(3) {
            // Activity recreation restores selection before the installed-app scan finishes.
            compose.runOnIdle { restoredWide = false }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("app-detail-sheet").assertDoesNotExist()
            compose.runOnIdle { appsLoaded.value = true }
            val sheet = compose.onNodeWithTag("app-detail-sheet").assertIsDisplayed().fetchSemanticsNode()
            assertTrue("expanded sheet clears the status bar", sheet.boundsInWindow.top >= safeTop)
            assertTrue("sheet expands after every return to portrait", sheet.boundsInWindow.top < windowBottom / 2)
            compose.runOnIdle { restoredWide = true }
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { appsLoaded.value = true }
            compose.onNodeWithTag("app-detail-pane").assertIsDisplayed()
            compose.onNodeWithTag("apps-search").assertTextEquals("Example")
            compose.onNodeWithTag("app-row:example.app").assertIsSelected()
        }
    }

    @Composable
    private fun Fixture() {
        DisposableEffect(Unit) {
            onDispose {
                wide.value = restoredWide
                appsLoaded.value = false
            }
        }
        var query by rememberSaveable { mutableStateOf("") }
        safeTop = WindowInsets.safeDrawing.getTop(LocalDensity.current)
        windowBottom = LocalWindowInfo.current.containerSize.height
        MaterialTheme {
            Box(Modifier.requiredWidth(if (wide.value) 520.dp else 480.dp).fillMaxHeight()) {
                AppManagementScreen(
                    apps = apps.takeIf { appsLoaded.value },
                    policies = policies,
                    query = query,
                    onQueryChanged = { query = it },
                    directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2()),
                    onAppEnabledChanged = { _, _ -> },
                    onAurogonChanged = { _, _ -> },
                    onAutoUnstopChanged = { _, _ -> },
                    onAutostartManagedChanged = { _, _ -> },
                    onAutostartChanged = { _, _ -> },
                    onDozeManagedChanged = { _, _ -> },
                    onDozeChanged = { _, _ -> },
                )
            }
        }
    }
}
