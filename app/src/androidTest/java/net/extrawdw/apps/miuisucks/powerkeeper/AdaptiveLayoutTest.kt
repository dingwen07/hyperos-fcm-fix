package net.extrawdw.apps.miuisucks.powerkeeper

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
    private val apps = listOf(InstalledFcmApp("example.app", "Example app", null, true))
    private val policies = mapOf("example.app" to AppPolicy("example.app", appEnabled = true))
    private var safeTop = 0

    @Test
    fun mediumWindowShowsNarrowerAppListBesideSettings() {
        compose.setContent { Fixture() }
        compose.onNodeWithTag("app-row:example.app").performClick()
        compose.onNodeWithTag("app-detail-pane").assertIsDisplayed()
        compose.onNodeWithTag("app-row:example.app").assertIsSelected()
        val list = compose.onNodeWithTag("apps-list").fetchSemanticsNode().boundsInWindow
        val detail = compose.onNodeWithTag("app-detail-pane").fetchSemanticsNode().boundsInWindow
        assertTrue("app list is narrower than settings", list.width < detail.width)
        compose.onNodeWithTag("app-detail-back").performClick()
        compose.onNodeWithTag("app-detail-pane").assertDoesNotExist()
        compose.onNodeWithTag("app-row:example.app").assertIsDisplayed()
    }

    @Test
    fun resizingSwitchesBetweenPaneAndSafeSheetRetainingSelectionAndSearch() {
        compose.setContent { Fixture() }
        compose.onNodeWithTag("apps-search").performTextInput("Example")
        compose.onNodeWithTag("app-row:example.app").performClick()
        compose.onNodeWithTag("app-detail-pane").assertIsDisplayed()
        compose.runOnIdle { wide.value = false }
        val sheet = compose.onNodeWithTag("app-detail-sheet").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("expanded sheet clears the status bar", sheet.boundsInWindow.top >= safeTop)
        compose.runOnIdle { wide.value = true }
        compose.onNodeWithTag("app-detail-pane").assertIsDisplayed()
        compose.onNodeWithTag("apps-search").assertTextEquals("Example")
        compose.onNodeWithTag("app-row:example.app").assertIsSelected()
    }

    @Composable
    private fun Fixture() {
        var query by rememberSaveable { mutableStateOf("") }
        safeTop = WindowInsets.safeDrawing.getTop(LocalDensity.current)
        MaterialTheme {
            Box(Modifier.requiredWidth(if (wide.value) 520.dp else 480.dp).fillMaxHeight()) {
                AppManagementScreen(
                    apps = apps,
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
