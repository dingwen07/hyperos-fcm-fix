package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Shared by pairing and selected details: one progress drives main width and pane position. */
@Stable
internal class SupportingPaneMotionState {
    val progress = Animatable(0f)
    var opening by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set

    suspend fun open() {
        closing = false
        opening = true
        try {
            progress.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        } finally {
            opening = false
        }
    }

    suspend fun reset() {
        progress.snapTo(0f)
        opening = false
        closing = false
    }

    suspend fun close(onClose: () -> Unit) {
        if (closing) return
        closing = true
        progress.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        onClose()
    }
}

/**
 * During divider motion covered content keeps its measured width, including while being uncovered.
 * It expands only once the exposed area exceeds that width: details cover the list moving left,
 * or slide off moving right. Fully hiding a pane must not discard the layout we will reveal again.
 * The list can instead reflow back to its resting split width before being covered again.
 * A settled split and a resized window lay out normally again. Keep these measurements out of
 * snapshot state so dragging only invalidates layout.
 */
private class CoveringPaneMeasurements {
    var contentWidth = -1
    var splitWidth = -1
}

@Composable
internal fun CoveringPaneContent(
    windowWidthPx: Int,
    enabled: () -> Boolean,
    dragging: () -> Boolean,
    anchoredWidth: () -> Int?,
    restoreSplitWidth: Boolean = false,
    content: @Composable () -> Unit,
) {
    val measurements = remember(windowWidthPx) { CoveringPaneMeasurements() }
    Layout(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        content = { Box(Modifier.fillMaxSize()) { content() } },
    ) { measurables, constraints ->
        val viewportWidth = constraints.maxWidth
        val covering = enabled()
        val settled = viewportWidth > 0 && !dragging() && viewportWidth == anchoredWidth()
        val splitWidth = when {
            !covering -> -1
            measurements.splitWidth < 0 || settled -> viewportWidth
            else -> measurements.splitWidth
        }
        val contentWidth = when {
            !covering || measurements.contentWidth < 0 -> viewportWidth
            settled -> viewportWidth
            restoreSplitWidth && splitWidth > 0 -> viewportWidth.coerceAtLeast(splitWidth)
            viewportWidth > measurements.contentWidth -> viewportWidth
            else -> measurements.contentWidth
        }
        // Lookahead can measure a destination before the visible pane reaches it.
        if (!isLookingAhead) {
            measurements.contentWidth = contentWidth
            measurements.splitWidth = splitWidth
        }
        val placeable = measurables.single().measure(Constraints.fixed(contentWidth, constraints.maxHeight))
        layout(viewportWidth, constraints.maxHeight) { placeable.place(0, 0) }
    }
}

/** Keeps content at its final width while the supporting region slides in/out, including Back. */
@Composable
internal fun SlidingSupportingPane(
    state: SupportingPaneMotionState,
    width: Dp,
    onClose: () -> Unit,
    enabled: Boolean = true,
    resizeToPane: Boolean = false,
    onCloseStart: (Dp) -> Unit = {},
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var measuredWidth by remember { mutableIntStateOf(0) }
    val prepareClose = { onCloseStart(with(density) { measuredWidth.toDp() }) }
    val close = {
        if (!state.closing) {
            prepareClose()
            scope.launch { state.close(onClose) }
        }
        Unit
    }
    BackHandler(enabled = enabled && (state.opening || state.closing)) {
        if (!state.closing) close()
    }
    PredictiveBackHandler(enabled = enabled && !state.opening && !state.closing) { events ->
        try {
            prepareClose()
            events.collect { event -> state.progress.snapTo(1f - event.progress.coerceIn(0f, 1f)) }
            state.close(onClose)
        } catch (cancelled: CancellationException) {
            scope.launch { state.progress.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
            throw cancelled
        }
    }
    Layout(
        modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged { measuredWidth = it.width },
        content = {
            Surface(
                Modifier.fillMaxSize(),
                color = if (enabled) MaterialTheme.colorScheme.background else Color.Transparent,
            ) { content(close) }
        },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(
            Constraints.fixed(
                if (enabled && !resizeToPane) width.roundToPx() else constraints.maxWidth,
                constraints.maxHeight,
            ),
        )
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
    }
}
