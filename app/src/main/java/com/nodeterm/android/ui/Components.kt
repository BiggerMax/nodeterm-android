package com.nodeterm.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeterm.android.core.model.NodeStatus

/**
 * Live status badge. WORKING (RUNNING) and NEEDS YOU pulse exactly like the desktop's hook-driven
 * badges — the two "actionable" states breathe (soft glow) so they catch the eye across the list;
 * DONE / IDLE stay static. The pulse is applied via graphicsLayer (alpha + slight scale), so it
 * never reflows the layout around it.
 */
@Composable
fun StatusBadge(status: NodeStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        NodeStatus.NEEDS_YOU -> MaterialTheme.colorScheme.error to Color.White
        NodeStatus.WORKING -> Color(0xFF16371F) to Color(0xFF4ADE80)
        NodeStatus.DONE -> Color(0xFF1C2A3A) to Color(0xFF79C0FF)
        NodeStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Only the actionable badges breathe; IDLE/DONE render a steady pill.
    val pulseAlpha: Float
    val pulseScale: Float
    if (status == NodeStatus.WORKING || status == NodeStatus.NEEDS_YOU) {
        val transition = rememberInfiniteTransition(label = "badgePulse")
        val alpha by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "badgeAlpha"
        )
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "badgeScale"
        )
        pulseAlpha = alpha
        pulseScale = scale
    } else {
        pulseAlpha = 1f
        pulseScale = 1f
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                this.alpha = pulseAlpha
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = status.badge,
            color = fg,
            fontSize = 11.sp,
            letterSpacing = 0.4.sp
        )
    }
}

/** A tiny pulsing connection dot — the home header's "live" indicator (green while connected). */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, steady: Boolean = false) {
    if (steady) {
        Box(
            modifier = modifier
                .size(7.dp)
                .background(color, RoundedCornerShape(50))
        )
    } else {
        val transition = rememberInfiniteTransition(label = "dotPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
        Box(
            modifier = modifier
                .size(7.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(color, RoundedCornerShape(50))
        )
    }
}

/**
 * A node-kind chip: the desktop's kind glyph (🖥 🤖 📝 🗂 ✏️ 🔀 🌐) rendered as a native icon in the
 * kind's accent colour. Terminal nodes stay icon-free here (their icon lives next to the title);
 * every other kind gets a tinted chip so kinds are scannable at a glance.
 */
@Composable
fun KindTag(kind: String, modifier: Modifier = Modifier) {
    if (NodeKinds.TERMINAL_LIKE.contains(NodeKinds.normalize(kind))) return
    val meta = NodeKinds.meta(kind)
    Box(
        modifier = modifier
            .background(meta.color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                imageVector = meta.icon,
                contentDescription = meta.label,
                tint = meta.color,
                modifier = Modifier.size(11.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = meta.label,
                color = meta.color,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        }
    }
}

/** Leading kind icon for node rows/cards — shown for every kind (terminal included). */
@Composable
fun KindIcon(kind: String, modifier: Modifier = Modifier) {
    val meta = NodeKinds.meta(kind)
    androidx.compose.material3.Icon(
        imageVector = meta.icon,
        contentDescription = meta.label,
        tint = meta.color,
        modifier = modifier.size(16.dp)
    )
}

/**
 * The desktop's per-node context-window meter: a labelled thin bar that fills with the agent's
 * context usage, green while comfortable (<60%), amber filling up (<85%), red near the limit.
 * [showPercent] renders the "Context …%" caption above the bar; a compact bar-only variant is
 * used on small surfaces (canvas cards).
 */
@Composable
fun ContextMeter(
    percent: Int,
    modifier: Modifier = Modifier,
    barHeight: Dp = 4.dp,
    showPercent: Boolean = true,
    // Track colour override for surfaces where surfaceVariant has no contrast (coloured cards).
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Column(modifier) {
        if (showPercent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Context",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$percent%",
                    fontSize = 10.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = contextColor(percent)
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2f))
                .background(trackColor)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .fillMaxHeight()
                    .background(contextColor(percent))
            )
        }
    }
}

/** Context-window fill colour: green while comfortable, amber filling up, red near the limit. */
fun contextColor(percent: Int): Color = when {
    percent >= 85 -> Color(0xFFFF7B72)
    percent >= 60 -> Color(0xFFE3B341)
    else -> Color(0xFF4ADE80)
}
