package com.nodeterm.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.nodeterm.android.core.vt.StyleResolver
import com.nodeterm.android.core.vt.VtScreen
import kotlin.math.max

/**
 * P2 full terminal renderer: draws the [VtScreen] cell grid on a Compose Canvas with per-run
 * colours/styles (bold/italic/underline/strike/inverse/dim), a block cursor, and a monospace
 * grid sized to the view. The screen mutates in place, so [generation] is bumped by the
 * ViewModel on every data arrival to force a redraw.
 *
 * Scrollback is NOT rendered locally for tmux-backed nodes — the phone scrolls the host's tmux
 * history via `pty.scroll` and the repainted screen streams back (see NodeDetailScreen).
 */
@Composable
fun TerminalRenderer(
    screen: VtScreen,
    generation: Int,
    defaultFg: Color = Color(0xFFD8DEE9),
    defaultBg: Color = Color(0xFF0D1117),
    onResize: (cols: Int, rows: Int) -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
    val refLayout = remember(textMeasurer, textStyle) {
        textMeasurer.measure(AnnotatedString("W"), textStyle)
    }
    val cellW = max(1, refLayout.size.width)
    val cellH = max(1, refLayout.size.height)
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(size, cellW, cellH) {
        if (size.width > 0 && size.height > 0) {
            onResize(max(1, size.width / cellW), max(1, size.height / cellH))
        }
    }

    key(generation) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
        ) {
            drawTerminal(
                screen = screen,
                cellW = cellW,
                cellH = cellH,
                defaultFg = defaultFg,
                defaultBg = defaultBg,
                textMeasurer = textMeasurer,
                textStyle = textStyle
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTerminal(
    screen: VtScreen,
    cellW: Int,
    cellH: Int,
    defaultFg: Color,
    defaultBg: Color,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle
) {
    val defaultBgArgb = defaultBg.toArgb()
    val defaultFgArgb = defaultFg.toArgb()
    drawRect(defaultBg)

    val cells = screen.cells()
    val cols = screen.cols
    val rows = screen.rows

    for (r in 0 until rows) {
        val base = r * cols
        var c = 0
        while (c < cols) {
            val st = cells[base + c].style
            var end = c + 1
            while (end < cols && cells[base + end].style == st) end++

            val bgArgb = StyleResolver.bgArgb(st, defaultFgArgb, defaultBgArgb)
            if (bgArgb != defaultBgArgb) {
                drawRect(
                    color = Color(bgArgb),
                    topLeft = Offset((c * cellW).toFloat(), (r * cellH).toFloat()),
                    size = Size((end - c) * cellW.toFloat(), cellH.toFloat())
                )
            }

            val sb = StringBuilder(end - c)
            for (i in c until end) {
                val ch = cells[base + i].ch
                sb.append(if (ch == '\u0000') ' ' else ch)
            }
            if (sb.isNotBlank()) {
                var fg = Color(StyleResolver.fgArgb(st, defaultFgArgb, defaultBgArgb))
                if (st.dim) fg = blendToward(fg, Color(bgArgb), 0.5f)
                val runStyle = textStyle.merge(
                    TextStyle(
                        color = fg,
                        fontWeight = if (st.bold) FontWeight.Bold else null,
                        fontStyle = if (st.italic) FontStyle.Italic else null
                    )
                )
                val layout = textMeasurer.measure(AnnotatedString(sb.toString()), runStyle)
                val topLeft = Offset((c * cellW).toFloat(), (r * cellH).toFloat())
                drawText(layout, topLeft = topLeft)
                if (st.underline) {
                    val y = (r + 1) * cellH - 1.5f
                    drawLine(
                        color = fg,
                        start = Offset((c * cellW).toFloat(), y),
                        end = Offset((end * cellW).toFloat(), y),
                        strokeWidth = 1f
                    )
                }
                if (st.strike) {
                    val y = r * cellH + cellH * 0.55f
                    drawLine(
                        color = fg,
                        start = Offset((c * cellW).toFloat(), y),
                        end = Offset((end * cellW).toFloat(), y),
                        strokeWidth = 1f
                    )
                }
            }
            c = end
        }
    }

    // Block cursor.
    // Block cursor: a translucent overlay so the glyph underneath stays readable.
    if (screen.cursorVisible) {
        val cr = screen.cursorRow
        val cc = screen.cursorCol
        if (cr in 0 until rows && cc in 0 until cols) {
            drawRect(
                color = defaultFg.copy(alpha = 0.4f),
                topLeft = Offset((cc * cellW).toFloat(), (cr * cellH).toFloat()),
                size = Size(cellW.toFloat(), cellH.toFloat())
            )
        }
    }
}

private fun blendToward(color: Color, target: Color, amount: Float): Color = Color(
    red = color.red + (target.red - color.red) * amount,
    green = color.green + (target.green - color.green) * amount,
    blue = color.blue + (target.blue - color.blue) * amount,
    alpha = color.alpha
)
