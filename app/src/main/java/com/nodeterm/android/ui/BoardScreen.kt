package com.nodeterm.android.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeterm.android.core.model.CanvasNode
import com.nodeterm.android.core.model.NodeStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** One canvas unit maps to this many dp at zoom 1. */
private const val UNIT_DP = 1.0f
private const val PAD = 160f
private const val MIN_SCALE = 0.15f
private const val MAX_SCALE = 4.0f

private enum class BoardMode { KANBAN, CANVAS }

/**
 * Mobile board — two views over the host's active project, mirroring the desktop's
 * kanban↔canvas switch: a Trello-style kanban (columns, drag-to-move, card details) and the
 * P2 spatial canvas. Decked by default on the kanban board; toggle via the header.
 */
@Composable
fun BoardScreen(
    nodes: List<CanvasNode>,
    status: Map<String, NodeStatus>,
    previews: Map<String, String>,
    nodeNames: Map<String, String>,
    kanban: KanbanView?,
    onOpenNode: (NodeRow) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    var mode by rememberSaveable { mutableStateOf(BoardMode.KANBAN) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Column(Modifier.weight(1f)) {
                Text("Board", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val subtitle = listOfNotNull(
                    kanban?.activeProjectName,
                    if (nodes.isEmpty()) null else "${nodes.size} node${if (nodes.size == 1) "" else "s"}"
                ).joinToString(" · ")
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Board / Canvas toggle.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                listOf(BoardMode.KANBAN to "Board", BoardMode.CANVAS to "Canvas").forEach { (m, label) ->
                    val selected = mode == m
                    TextButton(
                        onClick = { mode = m },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }

        when (mode) {
            BoardMode.KANBAN -> {
                val board = kanban
                if (board == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No kanban board for this project yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    KanbanBoard(
                        board = board,
                        nodes = nodes,
                        status = status,
                        previews = previews,
                        nodeNames = nodeNames,
                        onOpenNode = onOpenNode,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
            BoardMode.CANVAS -> CanvasBoardScreen(
                nodes = nodes,
                status = status,
                previews = previews,
                onOpenNode = onOpenNode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}


/**
 * The P2 spatial canvas board (pan/zoom). Kept as-is; the [BoardScreen] dispatcher toggles
 * between this and the Trello-style [KanbanBoard].
 */
@Composable
private fun CanvasBoardScreen(
    nodes: List<CanvasNode>,
    status: Map<String, NodeStatus>,
    previews: Map<String, String>,
    onOpenNode: (NodeRow) -> Unit,
    modifier: Modifier = Modifier
) {
    // scale + pan offset are plain state so gestures can update them directly; fit/focus animate
    // them with animate() inside the effects below. Offsets stay in dp — the same unit space as
    // [contentW]/[contentH] and [canvasSize] — so fit + centring + pan compose without density
    // conversion bugs.
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // The board viewport size in dp (onSizeChanged reports px; converted via the current density).
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    // One-shot auto-fit: the initial fit request is armed once (first measurable content) and
    // never re-armed by refreshes/resizes, so the view stays where the user left it.
    var everFitted by remember { mutableStateOf(false) }
    // Auto-fit once on first content, then only on explicit Fit (a canvas refresh must not
    // yank the view out from under the user mid-interaction).
    var fitRequest by remember { mutableIntStateOf(0) }
    // Node currently focused by a double-tap; double-tapping it again returns to Fit.
    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }
    val density = LocalDensity.current
    // +/- buttons snap the zoom (no animation) and drop any active focus so a later viewport
    // resize won't silently re-centre on a stale node. Reset fitRequest too so an in-flight fit
    // animation is cancelled (key change restarts the effect, which early-returns and cancels).
    val zoomBy: (Float) -> Unit = { z ->
        focusTarget = null
        fitRequest = 0
        scale = z.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    val bounds = remember(nodes) { nodeBounds(nodes) }
    val contentW = max(1f, bounds.width + PAD * 2)
    val contentH = max(1f, bounds.height + PAD * 2)

    LaunchedEffect(nodes, canvasSize) {
        if (!everFitted && nodes.isNotEmpty() && canvasSize.width > 0f) {
            everFitted = true
            fitRequest = 1
        }
    }

    // Whole-board fit: scale so the full canvas fits the viewport, centred, with a short
    // animation (rather than the old instant jump).
    LaunchedEffect(canvasSize, fitRequest) {
        if (nodes.isEmpty() || canvasSize.width == 0f || fitRequest == 0) return@LaunchedEffect
        val sx = canvasSize.width / contentW
        val sy = canvasSize.height / contentH
        val s = min(max(min(sx, sy), MIN_SCALE), 1.2f)
        coroutineScope {
            launch { animateFloat(scale, s, 300) { scale = it } }
            launch { animateFloat(offsetX, 0f, 300) { offsetX = it } }
            launch { animateFloat(offsetY, 0f, 300) { offsetY = it } }
        }
        // One-shot: consume the request so a LATER viewport resize (rotation / multi-window)
        // does not silently re-fit over the user's manual zoom/pan — only an explicit Fit
        // button press (or a double-tap toggle) re-requests it.
        fitRequest = 0
    }

    // Node focus: a double-tap animates the view so the node's centre lands in the viewport
    // centre at a comfortable viewing scale. The target offset is (contentCentre − nodeCentre)
    // × scale, which composes with the content Box's own (viewport − content)/2 layout offset
    // because the graphicsLayer scales around the Box centre.
    LaunchedEffect(focusTarget, canvasSize, nodes) {
        val target = focusTarget ?: return@LaunchedEffect
        if (canvasSize.width == 0f) return@LaunchedEffect
        val node = nodes.find { it.id == target.nodeId }
        if (node == null) {
            focusTarget = null
            return@LaunchedEffect
        }
        val (nw, nh) = nodeRenderSize(node)
        val nx = (node.position.x.toFloat() - bounds.minX + PAD) * UNIT_DP + nw / 2f
        val ny = (node.position.y.toFloat() - bounds.minY + PAD) * UNIT_DP + nh / 2f
        // Comfortable viewing scale: the node spans ~45% of the smaller viewport dimension.
        val s = min(canvasSize.width / (nw * 2.2f), canvasSize.height / (nh * 2.2f))
            .coerceIn(MIN_SCALE, MAX_SCALE)
        coroutineScope {
            launch { animateFloat(scale, s, 320) { scale = it } }
            launch { animateFloat(offsetX, (contentW / 2f - nx) * s, 320) { offsetX = it } }
            launch { animateFloat(offsetY, (contentH / 2f - ny) * s, 320) { offsetY = it } }
        }
    }

    Column(modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .clipToBounds()
                .onSizeChanged { size ->
                    // The fit maths depends on the real viewport size; without this measure the
                    // canvas stays at scale 1 and the centring offset is computed against a
                    // zero-size viewport (nodes end up off-screen). onSizeChanged reports PIXELS,
                    // so convert to dp to keep canvasSize in the same unit space as the content.
                    with(density) {
                        canvasSize = Size(
                            size.width.toDp().value,
                            size.height.toDp().value
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Manual manipulation takes over from any running focus/fit animation
                        // (reset both request flags: the keyed effects restart and early-return,
                        // cancelling their animation children).
                        focusTarget = null
                        fitRequest = 0
                        // pan arrives in px; convert to dp so the offset composes with the dp-space fit.
                        with(density) {
                            offsetX += pan.x.toDp().value
                            offsetY += pan.y.toDp().value
                        }
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    }
                }
        ) {
            if (nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No canvas yet — refresh to pull the host's board.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    // graphicsLayer scales around the Box CENTRE, so the centred (scale==1)
                    // layout offset is (viewport - content)/2 and the centre stays put under zoom.
                    .offset(
                        x = (offsetX + (canvasSize.width - contentW) / 2f).dp,
                        y = (offsetY + (canvasSize.height - contentH) / 2f).dp
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(contentW.dp, contentH.dp)
            ) {
                for (node in nodes) {
                    val x = (node.position.x.toFloat() - bounds.minX + PAD) * UNIT_DP
                    val y = (node.position.y.toFloat() - bounds.minY + PAD) * UNIT_DP
                    val (w, h) = nodeRenderSize(node)
                    BoardNodeCard(
                        node = node,
                        nodeStatus = status[node.id] ?: NodeStatus.IDLE,
                        preview = previews[node.id].orEmpty(),
                        x = x.dp,
                        y = y.dp,
                        w = w.dp,
                        h = h.dp,
                        onClick = {
                            onOpenNode(
                                NodeRow(
                                    nodeId = node.id,
                                    title = node.title,
                                    kind = node.kind,
                                    agentId = node.agentId,
                                    cwd = node.cwd,
                                    projectName = ""
                                )
                            )
                        },
                        onDoubleTap = {
                            // Double-tap focuses the node; double-tapping the focused node again
                            // toggles back to the whole-board Fit view.
                            if (focusTarget?.nodeId == node.id) {
                                focusTarget = null
                                fitRequest++
                            } else {
                                focusTarget = FocusTarget(node.id)
                            }
                        }
                    )
                }
            }
        }

        // Zoom controls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            IconButton(onClick = { zoomBy(scale / 1.3f) }) {
                Text("−", fontSize = 20.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    focusTarget = null
                    fitRequest++
                },
                modifier = Modifier.height(36.dp)
            ) {
                Text("Fit", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { zoomBy(scale * 1.3f) }) {
                Text("+", fontSize = 18.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardNodeCard(
    node: CanvasNode,
    nodeStatus: NodeStatus,
    preview: String,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    w: androidx.compose.ui.unit.Dp,
    h: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onDoubleTap: () -> Unit
) {
    val bg = parseNodeColor(node.color)
    Column(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(w, h)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleTap
            )
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = node.title.ifBlank { node.id },
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            StatusBadge(nodeStatus)
        }
        Spacer(Modifier.height(4.dp))
        if (preview.isNotBlank()) {
            // Mini terminal preview: the node's most recent output snippet on a dark screen,
            // monospace and clipped — reads like a zoomed-out terminal. weight(fill=false) caps
            // it at its content height (≤4 lines) so tall cards don't grow a huge empty screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = preview,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        } else if (node.kind != "terminal") {
            Text(
                text = node.kind,
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1
            )
        }
    }
}

/** Rendered size of a node card in dp, matching the render loop's minimums. */
private fun nodeRenderSize(node: CanvasNode): Pair<Float, Float> {
    val w = max(120f, node.size.width.toFloat() * UNIT_DP)
    val h = max(64f, node.size.height.toFloat() * UNIT_DP)
    return w to h
}

private fun nodeBounds(nodes: List<CanvasNode>): Bounds {
    if (nodes.isEmpty()) return Bounds(0f, 0f, 1f, 1f)
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (n in nodes) {
        minX = min(minX, n.position.x.toFloat())
        minY = min(minY, n.position.y.toFloat())
        maxX = max(maxX, n.position.x.toFloat() + n.size.width.toFloat())
        maxY = max(maxY, n.position.y.toFloat() + n.size.height.toFloat())
    }
    return Bounds(minX, minY, maxX - minX, maxY - minY)
}

private data class Bounds(val minX: Float, val minY: Float, val width: Float, val height: Float)

/** A node the user double-tapped to focus on; double-tapping it again returns to Fit. */
private data class FocusTarget(val nodeId: String)

/**
 * Animate [from] → [to] over [durationMillis], reporting each frame to [onFrame].
 * Thin wrapper over the top-level [animate] (which requires an explicit initial velocity).
 */
private suspend fun animateFloat(
    from: Float,
    to: Float,
    durationMillis: Int,
    onFrame: (Float) -> Unit
) {
    animate(
        initialValue = from,
        targetValue = to,
        initialVelocity = 0f,
        animationSpec = tween(durationMillis)
    ) { value, _ -> onFrame(value) }
}

/** Parse the host's `#rrggbb` canvas colours; fall back to the theme primary. */
private fun parseNodeColor(hex: String): Color {
    val h = hex.removePrefix("#")
    if (h.length != 6) return Color(0xFF2A3B5C)
    val v = h.toLongOrNull(16) ?: return Color(0xFF2A3B5C)
    return Color(0xFF000000L or v)
}
