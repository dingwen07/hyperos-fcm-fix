package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.ui.NavDisplay
import kotlin.math.roundToInt

/**
 * Matches NotiSync's Apps presentation: a supporting pane when it fits, otherwise a modal sheet.
 * Both scenes use the same Nav3 entries, preserving search, scroll and detail state across folds.
 * The caller owns outer system insets; Material's directive keeps panes clear of separating hinges.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun AdaptiveDetailLayout(
    selectedKey: String?,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    directive: PaneScaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2()),
    detail: @Composable (isPane: Boolean) -> Unit,
    list: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 1280.dp).fillMaxSize()) {
            // Material's dense directive permits two panes from 600 dp. Check the remaining
            // content width as well, since the rail and system insets consume part of the window.
            val minimumPaneWidth = 240.dp
            val wide = directive.maxHorizontalPartitions > 1 &&
                maxWidth >= minimumPaneWidth * 2 + directive.horizontalPartitionSpacerSize
            val currentWide = rememberUpdatedState(wide)
            val currentDetail = rememberUpdatedState(detail)
            val currentList = rememberUpdatedState(list)
            val currentTitle = rememberUpdatedState(title)
            val currentDismiss = rememberUpdatedState(onDismiss)
            val paneState = remember { SupportingPaneMotionState() }
            // Keep the app list compact, leaving most of the split for policy descriptions.
            // Include half the gutter so the list itself remains at least 240 dp wide.
            val defaultMainWidth = (maxWidth * 0.35f)
                .coerceIn(minimumPaneWidth + 8.dp, 320.dp + 8.dp)
                .coerceAtMost(maxWidth / 2)
            val splitFraction = if (maxWidth > 0.dp) defaultMainWidth / maxWidth else 0.5f
            val currentSplitFraction = rememberUpdatedState(splitFraction)
            val anchors = remember(splitFraction) {
                listOf(
                    PaneExpansionAnchor.Proportion(0f),
                    PaneExpansionAnchor.Proportion(splitFraction),
                    PaneExpansionAnchor.Proportion(1f),
                )
            }
            val currentAnchors = rememberUpdatedState(anchors)
            val interactionSource = remember { MutableInteractionSource() }
            val dragging = interactionSource.collectIsDraggedAsState()
            val expansionState = rememberPaneExpansionState(
                anchors = anchors,
                anchoringAnimationSpec = tween(240, easing = FastOutSlowInEasing),
            )
            val canExpand = directive.excludedBounds.isEmpty()
            val currentCanExpand = rememberUpdatedState(canExpand)
            val paneOpen = wide && selectedKey != null
            val currentPaneOpen = rememberUpdatedState(paneOpen)
            val windowWidthPx = rememberUpdatedState(constraints.maxWidth)
            val spacerPx = rememberUpdatedState(with(LocalDensity.current) { 16.dp.roundToPx() })
            val coveringEnabled = {
                currentCanExpand.value && currentPaneOpen.value && !paneState.opening && !paneState.closing
            }
            val anchoredWidth: (Boolean) -> Int? = { main ->
                when (expansionState.currentAnchor) {
                    currentAnchors.value.first() -> if (main) 0 else windowWidthPx.value
                    currentAnchors.value[1] -> {
                        val divider = (windowWidthPx.value * currentSplitFraction.value).roundToInt()
                        (if (main) divider else windowWidthPx.value - divider) - spacerPx.value / 2
                    }
                    currentAnchors.value.last() -> if (main) windowWidthPx.value else 0
                    else -> null
                }
            }
            LaunchedEffect(paneOpen) {
                if (paneOpen) paneState.open() else paneState.reset()
            }
            var paneWidth by remember(maxWidth) { mutableStateOf(maxWidth - defaultMainWidth) }
            val currentPaneWidth = rememberUpdatedState(paneWidth)
            val prepareClose = rememberUpdatedState<(Dp) -> Unit>({ width ->
                paneWidth = (width + 16.dp).coerceAtMost(maxWidth)
            })
            val mainWidthPx = with(LocalDensity.current) {
                (maxWidth - paneWidth * paneState.progress.value).roundToPx()
            }
            // Only progress changes drive expansion. Updating it on every recomposition would
            // overwrite the native divider position, including its fully hidden list anchor.
            LaunchedEffect(mainWidthPx, canExpand) {
                if (canExpand) expansionState.setFirstPaneWidth(mainWidthPx)
            }
            LaunchedEffect(expansionState.currentAnchor, paneOpen) {
                if (canExpand && paneOpen && expansionState.currentAnchor == anchors.last()) {
                    expansionState.animateTo(anchors.last())
                    currentDismiss.value()
                }
            }
            val paneStrategy = rememberSupportingPaneSceneStrategy<String>(
                shouldHandleSinglePaneLayout = true,
                directive = PaneScaffoldDirective(
                    maxHorizontalPartitions = directive.maxHorizontalPartitions,
                    horizontalPartitionSpacerSize = 16.dp,
                    maxVerticalPartitions = directive.maxVerticalPartitions,
                    verticalPartitionSpacerSize = directive.verticalPartitionSpacerSize,
                    defaultPanePreferredWidth = minimumPaneWidth,
                    defaultPanePreferredHeight = directive.defaultPanePreferredHeight,
                    excludedBounds = directive.excludedBounds,
                    shouldAutoFocusCurrentDestination = false,
                ),
                // User-controlled expansion bypasses Material's hinge partitioning. Keep a
                // separating hinge fixed; let Material place one pane on each usable display.
                paneExpansionState = expansionState.takeIf { canExpand },
                paneExpansionDragHandle = if (canExpand) ({ state ->
                    VerticalDragHandle(
                        modifier = Modifier.testTag("app-detail-divider").paneExpansionDraggable(
                            state, LocalMinimumInteractiveComponentSize.current, interactionSource,
                        ),
                        interactionSource = interactionSource,
                    )
                }) else null,
            )
            val paneMotion = if (canExpand) remember {
                SupportingPaneSceneStrategy.paneAnimation(
                    enterTransition = EnterTransition.None,
                    exitTransition = ExitTransition.None,
                    boundsAnimationSpec = snap(),
                )
            } else emptyMap()
            val sheetStrategy = remember {
                SceneStrategy<String> { entries ->
                    if (entries.size > 1) DetailSheetScene(entries.last(), entries.dropLast(1)) else null
                }
            }
            NavDisplay(
                backStack = listOf("list") + listOfNotNull(selectedKey?.let { "detail:$it" }),
                sceneStrategies = if (wide) listOf(paneStrategy, sheetStrategy) else listOf(sheetStrategy, paneStrategy),
                onBack = onDismiss,
                entryProvider = { key ->
                    if (key == "list") {
                        NavEntry(key, metadata = SupportingPaneSceneStrategy.mainPane() + paneMotion) {
                            CoveringPaneContent(
                                windowWidthPx.value, coveringEnabled, { dragging.value }, { anchoredWidth(true) },
                                restoreSplitWidth = true,
                            ) { currentList.value() }
                        }
                    } else {
                        NavEntry(key, metadata = SupportingPaneSceneStrategy.supportingPane() + paneMotion) {
                            if (currentWide.value) {
                                CoveringPaneContent(
                                    windowWidthPx.value, coveringEnabled, { dragging.value }, { anchoredWidth(false) },
                                ) {
                                    SlidingSupportingPane(
                                        paneState, currentPaneWidth.value, { currentDismiss.value() },
                                        enabled = currentCanExpand.value,
                                        resizeToPane = !paneState.opening && !paneState.closing,
                                        onCloseStart = { prepareClose.value(it) },
                                    ) { close ->
                                        Scaffold(
                                            modifier = Modifier.fillMaxSize()
                                                .clip(MaterialTheme.shapes.extraLarge)
                                                .testTag("app-detail-pane"),
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                            contentWindowInsets = WindowInsets(0),
                                            topBar = {
                                                TopAppBar(
                                                    title = { Text(currentTitle.value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                    windowInsets = WindowInsets(0),
                                                    colors = TopAppBarDefaults.topAppBarColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    ),
                                                    navigationIcon = {
                                                        IconButton(onClick = close, modifier = Modifier.testTag("app-detail-back")) {
                                                            Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back))
                                                        }
                                                    },
                                                )
                                            },
                                        ) { padding ->
                                            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                                currentDetail.value(true)
                                            }
                                        }
                                    }
                                }
                            } else {
                                ModalBottomSheet(
                                    onDismissRequest = { currentDismiss.value() },
                                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                                    // A margin on the sheet surface caps expansion below system UI,
                                    // including the drag handle. Content insets alone only pad its body.
                                    modifier = Modifier
                                        .padding(top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding())
                                        .testTag("app-detail-sheet"),
                                    contentWindowInsets = {
                                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                                    },
                                ) { currentDetail.value(false) }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** The sheet owns its window and Back gesture, while its source list remains composed. */
private data class DetailSheetScene(
    val entry: NavEntry<String>,
    override val overlaidEntries: List<NavEntry<String>>,
) : OverlayScene<String> {
    override val key: Any = entry.contentKey
    override val entries = listOf(entry)
    override val previousEntries = overlaidEntries
    override val content: @Composable () -> Unit = { entry.Content() }
}
