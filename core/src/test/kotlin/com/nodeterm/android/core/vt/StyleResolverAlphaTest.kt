package com.nodeterm.android.core.vt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: 256-colour (SGR 38;5;n / 48;5;n) styles must resolve to OPAQUE ARGB.
 * The classic xterm palette returns 0xRRGGBB with no alpha; if that is handed to
 * Compose's Color(int) as-is, the result is fully transparent and the text/background
 * is painted invisible (observed on-device: the freebuff agent TUI rendered blank).
 */
class StyleResolverAlphaTest {

    private val defaultFg = 0xFFD8DEE9.toInt()
    private val defaultBg = 0xFF0D1117.toInt()

    @Test
    fun `index colors resolve to fully opaque ARGB`() {
        val fg = StyleResolver.fgArgb(Style(fg = ColorSpec.Index(174)), defaultFg, defaultBg)
        val bg = StyleResolver.bgArgb(Style(bg = ColorSpec.Index(235)), defaultFg, defaultBg)
        assertEquals(0xff, (fg ushr 24) and 0xff, "fg alpha must be 0xff, was ${fg.toString(16)}")
        assertEquals(0xff, (bg ushr 24) and 0xff, "bg alpha must be 0xff, was ${bg.toString(16)}")
        assertTrue(fg != 0, "fg must not be zero")
    }

    @Test
    fun `parsed 256-colour text is not transparent when resolved`() {
        val screen = VtScreen(10, 2)
        val parser = VtParser(screen)
        parser.feed("\u001b[38;5;82mgreen\u001b[0m")
        val cell = screen.cellAt(0, 0)
        assertEquals('g', cell.ch)
        val argb = StyleResolver.fgArgb(cell.style, defaultFg, defaultBg)
        assertEquals(0xff, (argb ushr 24) and 0xff, "green text must be opaque")
    }

    @Test
    fun `inverse over index colours keeps opacity`() {
        val style = Style(fg = ColorSpec.Index(196), bg = ColorSpec.Default, flags = Style.INVERSE)
        val fg = StyleResolver.fgArgb(style, defaultFg, defaultBg)
        // inverse: fg uses the bg spec (Default) → default bg
        assertEquals(defaultBg, fg)
    }
}
