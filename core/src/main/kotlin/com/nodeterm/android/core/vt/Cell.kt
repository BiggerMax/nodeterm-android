package com.nodeterm.android.core.vt

/**
 * Terminal color: theme default, palette index (0..255), or truecolor RGB.
 * Mirrors the color model the reference host emits (SGR 30-37/40-47/90-97/100-107,
 * 38;5;n / 48;5;n 256-colour, 38;2;r;g;b / 48;2;r;g;b truecolor).
 */
sealed interface ColorSpec {
    data object Default : ColorSpec
    data class Index(val n: Int) : ColorSpec
    data class Rgb(val r: Int, val g: Int, val b: Int) : ColorSpec
}

/** Cell style attributes as a small immutable value; flag bits keep it cheap to copy. */
data class Style(
    val fg: ColorSpec = ColorSpec.Default,
    val bg: ColorSpec = ColorSpec.Default,
    val flags: Int = 0,
) {
    val bold: Boolean get() = flags and BOLD != 0
    val dim: Boolean get() = flags and DIM != 0
    val italic: Boolean get() = flags and ITALIC != 0
    val underline: Boolean get() = flags and UNDERLINE != 0
    val blink: Boolean get() = flags and BLINK != 0
    val inverse: Boolean get() = flags and INVERSE != 0
    val strike: Boolean get() = flags and STRIKE != 0

    companion object {
        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val BLINK = 16
        const val INVERSE = 32
        const val STRIKE = 64

        val DEFAULT = Style()
    }
}

/** A single screen cell. Mutable so erase/scroll operations can rewrite in place. */
class Cell(var ch: Char = ' ', var style: Style = Style.DEFAULT)

/** The classic xterm 256-colour palette (16 base + 6×6×6 cube + 24-step greyscale). */
object TerminalPalette {

    val BASE = intArrayOf(
        0x000000, 0xcd0000, 0x00cd00, 0xcdcd00,
        0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
        0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00,
        0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
    )

    fun index(n: Int): Int = when {
        n < 16 -> BASE[n and 15]
        n < 232 -> {
            val c = n - 16
            val r = c / 36
            val g = (c % 36) / 6
            val b = c % 6
            fun step(v: Int) = if (v == 0) 0 else 55 + v * 40
            (step(r) shl 16) or (step(g) shl 8) or step(b)
        }
        else -> {
            val v = 8 + (n - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
    }
}

/**
 * Resolve a cell's actual foreground/background ARGB given the theme defaults.
 * Applies the inverse attribute (SGR 7) here so the renderer stays dumb.
 */
object StyleResolver {

    fun fgArgb(style: Style, defaultFg: Int, defaultBg: Int): Int {
        var spec = style.fg
        if (style.inverse) spec = if (style.bg is ColorSpec.Default) ColorSpec.Default else style.bg
        return when (spec) {
            is ColorSpec.Default -> if (style.inverse) defaultBg else defaultFg
            // The palette returns 0xRRGGBB (no alpha); OR in full opacity so the color is never
            // resolved to a fully-transparent ARGB (Color(0x00RRGGBB) would paint nothing).
            is ColorSpec.Index -> (0xff shl 24) or TerminalPalette.index(spec.n)
            is ColorSpec.Rgb -> (0xff shl 24) or (spec.r shl 16) or (spec.g shl 8) or spec.b
        }
    }

    fun bgArgb(style: Style, defaultFg: Int, defaultBg: Int): Int {
        var spec = style.bg
        if (style.inverse) spec = if (style.fg is ColorSpec.Default) ColorSpec.Default else style.fg
        return when (spec) {
            is ColorSpec.Default -> if (style.inverse) defaultFg else defaultBg
            is ColorSpec.Index -> (0xff shl 24) or TerminalPalette.index(spec.n)
            is ColorSpec.Rgb -> (0xff shl 24) or (spec.r shl 16) or (spec.g shl 8) or spec.b
        }
    }
}
