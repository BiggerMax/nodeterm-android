package com.nodeterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeterm.android.core.model.NodeStatus

@Composable
fun StatusBadge(status: NodeStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        NodeStatus.NEEDS_YOU -> MaterialTheme.colorScheme.error to Color.White
        NodeStatus.WORKING -> Color(0xFF16371F) to Color(0xFF4ADE80)
        NodeStatus.DONE -> Color(0xFF1C2A3A) to Color(0xFF79C0FF)
        NodeStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
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

@Composable
fun KindTag(kind: String, modifier: Modifier = Modifier) {
    if (kind == "terminal") return
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = kind.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp
        )
    }
}
