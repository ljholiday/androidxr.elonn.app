package com.elonn.androidxr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.elonn.androidxr.core.PanelGeometry
import com.elonn.androidxr.core.PanelStore
import kotlin.math.roundToInt

/** window.md's tap-vs-drag threshold, same value xreal.elonn.app's CarryPanelController uses. */
private val TapDragThresholdDp = 14.dp
private val MinPanelWidth = 200.dp
private val MinPanelHeight = 64.dp

/**
 * A window, per dev.elonn.local's terminology/window.md: a runtime's
 * movable, resizable, focusable (collapsible) rendering of an Object on
 * Carry. Every Object placed on Carry gets identical chrome -- no object is
 * special-cased with fewer controls -- with one earned exception: the single
 * shared Entry/Results window passes `closable = false`, since Entry must
 * always exist (layout.md). Mirrors xreal.elonn.app's CarryPanelController /
 * CarryPanelResizeHandle and web.elonn.local's floatingPanel(): the header
 * both drags the window and (on a plain tap, not a drag) toggles collapse; a
 * separate corner handle resizes; geometry persists per panelId via
 * PanelStore, the same way CarryPanelStore/localStorage do for the reference
 * runtimes.
 */
@Composable
fun FloatingWindow(
    panelId: String,
    store: PanelStore,
    boundsPx: IntSize,
    defaultX: Dp,
    defaultY: Dp,
    defaultWidth: Dp,
    defaultHeight: Dp,
    closable: Boolean,
    onClosed: (() -> Unit)? = null,
    // A distinct value here (e.g. a counter bumped once per completed search) forces this window
    // open even if the member had it collapsed -- so a fresh result set (or its error) is never
    // left hidden. null means "no forced expand," the default for every window that doesn't need
    // this (only EntryResultsWindow does).
    expandOnChangeOf: Any? = null,
    header: @Composable RowScope.(collapsed: Boolean, toggleCollapsed: () -> Unit) -> Unit,
    // Responsible for its own vertical scrolling when its content can exceed
    // the window's height -- either Modifier.verticalScroll (plain stacked
    // content) or its own LazyColumn (a list); never both nested together.
    // Without this, a drag over content taller than the window isn't
    // consumed here and falls through to whatever window sits behind it --
    // caught live on-device with the account Dashboard's form stack
    // scrolling the Results pane underneath it instead of itself.
    body: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val saved = remember(panelId) { store.load(panelId) }
    var xPx by remember(panelId) { mutableStateOf(saved?.x ?: with(density) { defaultX.toPx() }) }
    var yPx by remember(panelId) { mutableStateOf(saved?.y ?: with(density) { defaultY.toPx() }) }
    var widthPx by remember(panelId) { mutableStateOf(saved?.width?.takeIf { it > 0f } ?: with(density) { defaultWidth.toPx() }) }
    var heightPx by remember(panelId) { mutableStateOf(saved?.height?.takeIf { it > 0f } ?: with(density) { defaultHeight.toPx() }) }
    var collapsed by remember(panelId) { mutableStateOf(saved?.collapsed ?: false) }
    // A brand-new window (no saved geometry) opens on top of whatever
    // already exists, same as a freshly-opened window would in any windowed
    // system; store.raise() hands out a value strictly above every window
    // raised so far, persisted, so stacking order survives a restart too.
    var z by remember(panelId) { mutableStateOf(saved?.z ?: store.raise(panelId)) }

    fun persist() {
        store.save(panelId, PanelGeometry(xPx, yPx, widthPx, heightPx, collapsed, z))
    }

    fun toggleCollapsed() {
        collapsed = !collapsed
        persist()
    }

    LaunchedEffect(expandOnChangeOf) {
        if (expandOnChangeOf != null && collapsed) {
            collapsed = false
            persist()
        }
    }

    fun raise() {
        val next = store.raise(panelId)
        if (next > z) {
            z = next
            persist()
        }
    }

    val minWidthPx = with(density) { MinPanelWidth.toPx() }
    val minHeightPx = with(density) { MinPanelHeight.toPx() }
    val tapDragThresholdPx = with(density) { TapDragThresholdDp.toPx() }

    Column(
        modifier = Modifier
            .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
            .zIndex(z)
            .width(with(density) { widthPx.toDp() })
            // Initial pass, and never consumes: this only observes that a
            // gesture is starting somewhere in the window, before the header
            // drag/collapse, resize handle, or body scroll (below) get to
            // decide what the gesture means -- so raising to front never
            // steals the gesture itself from whichever control handles it.
            .pointerInput(panelId) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                    raise()
                }
            }
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
            .border(ElonnBorderWidth, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
    ) {
        // The header both drags the window (like any title bar) and, on a
        // plain tap -- not a drag -- toggles collapse. requireUnconsumed on
        // the initial down means a child control that already consumed it
        // (Entry's own text field, the Close button) never starts a drag.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(panelId, boundsPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        var dragging = false
                        var travelled = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            travelled += delta
                            if (!dragging && travelled.getDistance() > tapDragThresholdPx) dragging = true
                            if (dragging) {
                                val maxX = (boundsPx.width - widthPx).coerceAtLeast(0f)
                                val maxY = (boundsPx.height - minHeightPx).coerceAtLeast(0f)
                                xPx = (xPx + delta.x).coerceIn(0f, maxX)
                                yPx = (yPx + delta.y).coerceIn(0f, maxY)
                                change.consume()
                            }
                        }
                        if (dragging) persist() else toggleCollapsed()
                    }
                }
                .padding(horizontal = ElonnSpacing.sm, vertical = ElonnSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            header(collapsed, ::toggleCollapsed)
            if (closable) {
                TextButton(onClick = {
                    store.forget(panelId)
                    onClosed?.invoke()
                }) { Text("Close") }
            }
        }

        if (!collapsed) {
            // This Column itself deliberately does NOT own scrolling: some
            // bodies (EntryResultsWindow's Findings list) are already a
            // LazyColumn, and a LazyColumn nested inside a
            // Modifier.verticalScroll parent crashes (unbounded height).
            // Each body is responsible for making its own content scroll --
            // see the doc on FloatingWindow's `body` parameter below.
            Column(
                modifier = Modifier
                    .width(with(density) { widthPx.toDp() })
                    .height(with(density) { heightPx.toDp() })
                    .padding(horizontal = ElonnSpacing.sm, vertical = ElonnSpacing.xs),
            ) {
                body()
            }

            // Separate from the header's gesture so a drag started here never
            // also triggers the header's drag/tap handling (CarryPanelResizeHandle).
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .size(28.dp)
                    .padding(ElonnSpacing.xs)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .border(ElonnBorderWidth, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp))
                    .pointerInput(panelId) {
                        detectResizeDrag(
                            onDragEnd = { persist() },
                        ) { dragAmount ->
                            // Clamped against the window's own left/top (xPx/yPx), not the
                            // screen's full width/height, so growing a window that's already
                            // offset from the origin can't push its right/bottom edge past
                            // the screen -- caught live on-device: a resize past this bound
                            // spilled the panel off the right edge of the screen.
                            widthPx = (widthPx + dragAmount.x).coerceAtLeast(minWidthPx).coerceAtMost((boundsPx.width - xPx).coerceAtLeast(minWidthPx))
                            heightPx = (heightPx + dragAmount.y).coerceAtLeast(minHeightPx).coerceAtMost((boundsPx.height - yPx).coerceAtLeast(minHeightPx))
                        }
                    },
            )
        }
    }
}

/**
 * A plain drag detector for the resize handle: every down-then-move is a
 * resize from the first pixel, unlike the header's gesture there is no
 * tap/collapse ambiguity to resolve here.
 */
private suspend fun PointerInputScope.detectResizeDrag(
    onDragEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            if (delta != Offset.Zero) {
                change.consume()
                onDrag(delta)
            }
        }
        onDragEnd()
    }
}
