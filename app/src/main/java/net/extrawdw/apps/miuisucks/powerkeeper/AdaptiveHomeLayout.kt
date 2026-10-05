package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Separating hinges and the available content width determine whether Home has two columns. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun AdaptiveHomeLayout(
    modifier: Modifier = Modifier,
    directive: PaneScaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2()),
    supportingPane: @Composable () -> Unit,
    mainPane: @Composable (wide: Boolean) -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 1280.dp).fillMaxSize()) {
            val wide = directive.maxHorizontalPartitions > 1 &&
                maxWidth >= directive.defaultPanePreferredWidth * 2 + directive.horizontalPartitionSpacerSize
            SupportingPaneScaffold(
                directive = directive,
                value = ThreePaneScaffoldValue(
                    primary = PaneAdaptedValue.Expanded,
                    secondary = if (wide) PaneAdaptedValue.Expanded else PaneAdaptedValue.Hidden,
                    tertiary = PaneAdaptedValue.Hidden,
                ),
                mainPane = {
                    AnimatedPane {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { mainPane(wide) }
                        }
                    }
                },
                supportingPane = {
                    AnimatedPane(Modifier.testTag("home-supporting-pane")) { supportingPane() }
                },
            )
        }
    }
}
