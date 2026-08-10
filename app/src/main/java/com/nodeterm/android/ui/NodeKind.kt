package com.nodeterm.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One source of truth for how node kinds look, mirroring the desktop's visual language:
 * every node kind carries a distinct icon + accent colour (🖥 Terminal, 🤖 Agent,
 * 📝 Sticky note, 🗂 Group, ✏️ Editor, 🔀 Diff, 🌐 Web/Video). All surfaces (home list,
 * canvas board, kanban cards) read from here so a kind looks identical everywhere.
 */
data class NodeKindMeta(
    val kind: String,
    val label: String,
    val icon: ImageVector,
    val color: Color
)

object NodeKinds {

    /** Kinds that own a live terminal (pty/tmux) — they show output previews and open as terminals. */
    val TERMINAL_LIKE = setOf("terminal", "agent")

    /** Sticky-note kinds — rendered as a yellow note on the canvas, never opened as a terminal. */
    val NOTE_KINDS = setOf("note", "sticky", "sticky-note", "sticky_note")

    /** Group kinds — rendered as a labelled container frame on the canvas. */
    val GROUP_KINDS = setOf("group", "folder", "worktree")

    fun normalize(kind: String): String = kind.trim().lowercase()

    /**
     * Non-composable so non-UI code (command palette entry building) can look a kind up too.
     * Unknown kinds fall back to a fixed muted grey that reads on both light and dark surfaces.
     */
    fun meta(kind: String): NodeKindMeta = when (normalize(kind)) {
        "terminal" -> NodeKindMeta(kind, "TERMINAL", Icons.Outlined.Terminal, Color(0xFF4ADE80))
        "agent" -> NodeKindMeta(kind, "AGENT", Icons.Outlined.SmartToy, Color(0xFFBC8CFF))
        in NOTE_KINDS -> NodeKindMeta(kind, "NOTE", Icons.AutoMirrored.Outlined.StickyNote2, Color(0xFFE3B341))
        in GROUP_KINDS -> NodeKindMeta(kind, "GROUP", Icons.Outlined.FolderCopy, Color(0xFF58A6FF))
        "editor", "code" -> NodeKindMeta(kind, "EDITOR", Icons.Outlined.EditNote, Color(0xFFFFA657))
        "diff", "diffview" -> NodeKindMeta(kind, "DIFF", Icons.AutoMirrored.Outlined.CompareArrows, Color(0xFFFF7B72))
        "web", "website", "url", "browser" -> NodeKindMeta(kind, "WEB", Icons.Outlined.Language, Color(0xFF39C5CF))
        "video" -> NodeKindMeta(kind, "VIDEO", Icons.Outlined.OndemandVideo, Color(0xFFFF6B9D))
        else -> NodeKindMeta(kind, kind.uppercase(), Icons.Outlined.Widgets, Color(0xFF8B949E))
    }
}

/**
 * Parse the host's `#rrggbb` node/project colours. Returns [fallback] for anything malformed —
 * the desktop always serves a colour, but hand-edited workspace files must not crash the renderer.
 */
fun parseNodeColor(hex: String, fallback: Color): Color {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6) return fallback
    val v = h.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000L or v)
}
