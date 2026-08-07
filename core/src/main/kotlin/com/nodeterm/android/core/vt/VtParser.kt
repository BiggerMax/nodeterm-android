package com.nodeterm.android.core.vt

/**
 * A pragmatic xterm/VT escape-sequence state machine feeding a [VtScreen].
 *
 * Covers the sequences real terminal output (including `tmux capture-pane -e` snapshots and live
 * tmux client output) emits: SGR colours (16 / 256 / truecolor), bold/dim/italic/underline/inverse/
 * blink/strike, cursor addressing and movement, erase (ED/EL/ECH), insert/delete line/char,
 * scroll (SU/SD), scroll regions, REP, save/restore cursor, alternate screen (47/1047/1049),
 * IND/RI/NEL, tabs, autowrap, origin mode, and OSC window titles. Unsupported sequences are
 * consumed and ignored (they never corrupt the screen).
 *
 * The machine holds all state between [feed] calls, so arbitrary byte-stream fragmentation
 * (frames split mid-sequence, UTF-8 split across frames) is handled correctly.
 */
class VtParser(val screen: VtScreen) {

    private enum class State { GROUND, ESCAPE, CSI, OSC, DCS, CHARSET }

    private var state = State.GROUND

    // CSI state
    private var privateMarker = 0
    private var intermediate = 0
    private val params = mutableListOf<Int>()
    private var paramBuf = StringBuilder()

    // OSC / DCS shared "ESC \ is the terminator" handling
    private var escPending = false
    private val osc = StringBuilder()

    // UTF-8 decoder
    private var utf8Need = 0
    private var utf8Len = 0
    private var utf8Cp = 0

    private var lastPrinted = ' '

    // ---------------------------------------------------------------- public API

    /** Feed raw bytes (PTY output / snapshot chunks). State persists across calls. */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = data[i].toInt() and 0xff
            i++
            when (state) {
                State.GROUND -> i = ground(b, i, end)
                State.ESCAPE -> escape(b)
                State.CSI -> csi(b)
                State.OSC -> oscChar(b)
                State.DCS -> dcsChar(b)
                State.CHARSET -> state = State.GROUND
            }
        }
    }

    /** Convenience for tests: feed a plain string (encoded UTF-8). */
    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- GROUND

    private fun ground(b: Int, i: Int, end: Int): Int {
        // Any non-continuation byte aborts a torn multi-byte sequence (xterm discards it).
        if (b < 0x80) utf8Need = 0
        if (b < 0x20) {
            when (b) {
                0x08 -> screen.backspace()
                0x09 -> screen.tab()
                0x0A, 0x0B, 0x0C -> screen.lineFeed()
                0x0D -> screen.carriageReturn()
                0x1B -> state = State.ESCAPE
            }
            return i
        }
        if (b == 0x7F) return i // DEL: ignore
        if (b < 0x80) {
            val ch = b.toChar()
            screen.putChar(ch)
            lastPrinted = ch
            return i
        }
        // UTF-8 sequence
        if (utf8Need == 0) {
            utf8Cp = when {
                b and 0xE0 == 0xC0 -> { utf8Need = 2; b and 0x1F }
                b and 0xF0 == 0xE0 -> { utf8Need = 3; b and 0x0F }
                b and 0xF8 == 0xF0 -> { utf8Need = 4; b and 0x07 }
                else -> { utf8Need = 1; b } // stray continuation: keep as-is
            }
            utf8Len = 1
        } else {
            utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
            utf8Len++
            if (utf8Len >= utf8Need) {
                utf8Need = 0
                emitCodePoint(utf8Cp)
            }
        }
        return i
    }

    private fun emitCodePoint(cp: Int) {
        if (cp <= 0xFFFF) {
            val ch = cp.toChar()
            screen.putChar(ch)
            lastPrinted = ch
        } else {
            // Astral plane: reserve two cells via the high surrogate; skip the low half.
            val hi = Character.toChars(cp)[0]
            screen.putChar(hi)
            lastPrinted = hi
        }
    }

    // ---------------------------------------------------------------- ESCAPE

    private fun escape(b: Int) {
        when (b) {
            '['.code -> {
                state = State.CSI
                params.clear()
                paramBuf = StringBuilder()
                privateMarker = 0
                intermediate = 0
            }
            ']'.code -> {
                state = State.OSC
                osc.setLength(0)
                escPending = false
            }
            'P'.code -> {
                state = State.DCS
                escPending = false
            }
            '7'.code -> screen.saveCursor()
            '8'.code -> screen.restoreCursor()
            'D'.code -> screen.lineFeed() // IND
            'M'.code -> screen.reverseIndex() // RI
            'E'.code -> { // NEL
                screen.carriageReturn()
                screen.lineFeed()
            }
            'c'.code -> screen.reset() // RIS
            '('.code, ')'.code, '*'.code, '+'.code -> state = State.CHARSET
            else -> state = State.GROUND // '=' '>' keypad, 'H'/'[' etc: ignore
        }
    }

    // ---------------------------------------------------------------- CSI

    private fun csi(b: Int) {
        when {
            b in 0x30..0x39 -> paramBuf.append(b.toChar())
            b == ';'.code -> pushParam()
            (b == '?'.code || b == '>'.code || b == '='.code || b == '<'.code) && privateMarker == 0 ->
                privateMarker = b
            b in 0x20..0x2F -> intermediate = b
            b in 0x40..0x7E -> {
                pushParam()
                dispatch(b)
                state = State.GROUND
            }
            else -> state = State.GROUND // malformed: abort
        }
    }

    private fun pushParam() {
        params.add(paramBuf.toString().toIntOrNull() ?: 0)
        paramBuf = StringBuilder()
    }

    /** Parameter with count semantics: missing/0 → [dflt] (1 for cursor motion, etc.). */
    private fun p(i: Int, dflt: Int): Int {
        val v = params.getOrElse(i) { dflt }
        return if (v <= 0) dflt else v
    }

    /** Raw parameter: missing → 0 (for mode-like finals ED/EL/SGR). */
    private fun p0(i: Int): Int = params.getOrElse(i) { 0 }

    private fun dispatch(final: Int) {
        if (privateMarker == '?'.code) {
            decPrivate(final)
            return
        }
        when (final.toChar()) {
            'A' -> screen.moveUp(p(0, 1))
            'B' -> screen.moveDown(p(0, 1))
            'C' -> screen.moveRight(p(0, 1))
            'D' -> screen.moveLeft(p(0, 1))
            'E' -> { // CNL: down + column 1
                screen.moveDown(p(0, 1))
                screen.carriageReturn()
            }
            'F' -> { // CPL: up + column 1
                screen.moveUp(p(0, 1))
                screen.carriageReturn()
            }
            'G' -> screen.moveTo(screen.cursorRow, p(0, 1) - 1) // CHA
            'H', 'f' -> screen.moveTo(p(0, 1) - 1, p(1, 1) - 1) // CUP / HVP
            'd' -> screen.moveTo(p(0, 1) - 1, screen.cursorCol) // VPA
            'e' -> screen.moveDown(p(0, 1)) // VPR
            'a' -> screen.moveRight(p(0, 1)) // HPR
            'I' -> repeat(p(0, 1)) { screen.tab() } // CHT
            'Z' -> repeat(p(0, 1)) { screen.backspace() } // CBT (crude but harmless)
            'J' -> screen.eraseDisplay(p0(0))
            'K' -> screen.eraseLine(p0(0))
            'X' -> screen.eraseChars(p(0, 1))
            'L' -> screen.insertLines(p(0, 1))
            'M' -> screen.deleteLines(p(0, 1))
            '@' -> screen.insertChars(p(0, 1))
            'P' -> screen.deleteChars(p(0, 1))
            'S' -> screen.scrollUp(p(0, 1))
            'T' -> screen.scrollDown(p(0, 1))
            'r' -> screen.setScrollRegion(p(0, 1) - 1, p(1, 1) - 1)
            's' -> screen.saveCursor()
            'u' -> screen.restoreCursor()
            'm' -> sgr()
            'b' -> repeat(p(0, 1)) { screen.putChar(lastPrinted) } // REP
            else -> {} // DA, DSR, DECSCUSR, window ops, insert-mode, … : ignore
        }
    }

    private fun decPrivate(final: Int) {
        val set = final == 'h'.code
        if (final != 'h'.code && final != 'l'.code) return
        when (p0(0)) {
            1 -> {} // DECCKM cursor keys: informational
            6 -> screen.originMode = set
            7 -> screen.autowrap = set
            25 -> screen.cursorVisible = set
            47 -> if (set) screen.enterAlternate(false) else screen.exitAlternate()
            1047 -> if (set) screen.enterAlternate(false) else screen.exitAlternate()
            1049 -> if (set) screen.enterAlternate(true) else screen.exitAlternate()
            1000, 1002, 1003 -> screen.mouseReporting = set
            2004 -> {} // bracketed paste: informational
        }
    }

    private fun sgr() {
        var i = 0
        while (i < params.size) {
            val code = params[i]
            when (code) {
                0 -> screen.style = Style.DEFAULT
                1 -> screen.style = flag(screen.style, Style.BOLD, true)
                2 -> screen.style = flag(screen.style, Style.DIM, true)
                3 -> screen.style = flag(screen.style, Style.ITALIC, true)
                4 -> screen.style = flag(screen.style, Style.UNDERLINE, true)
                5, 6 -> screen.style = flag(screen.style, Style.BLINK, true)
                7 -> screen.style = flag(screen.style, Style.INVERSE, true)
                8 -> {} // concealed: informational
                9 -> screen.style = flag(screen.style, Style.STRIKE, true)
                21 -> screen.style = flag(screen.style, Style.UNDERLINE, true) // double underline
                22 -> screen.style = flag(screen.style, Style.BOLD or Style.DIM, false)
                23 -> screen.style = flag(screen.style, Style.ITALIC, false)
                24 -> screen.style = flag(screen.style, Style.UNDERLINE, false)
                25 -> screen.style = flag(screen.style, Style.BLINK, false)
                27 -> screen.style = flag(screen.style, Style.INVERSE, false)
                29 -> screen.style = flag(screen.style, Style.STRIKE, false)
                in 30..37 -> screen.style = screen.style.copy(fg = ColorSpec.Index(code - 30))
                38 -> i += extendedColor(true, i + 1)
                39 -> screen.style = screen.style.copy(fg = ColorSpec.Default)
                in 40..47 -> screen.style = screen.style.copy(bg = ColorSpec.Index(code - 40))
                48 -> i += extendedColor(false, i + 1)
                49 -> screen.style = screen.style.copy(bg = ColorSpec.Default)
                in 90..97 -> screen.style = screen.style.copy(fg = ColorSpec.Index(8 + code - 90))
                in 100..107 -> screen.style = screen.style.copy(bg = ColorSpec.Index(8 + code - 100))
            }
            i++
        }
    }

    /** Handles `38;5;n` / `38;2;r;g;b` (and 48…); returns the number of extra params consumed. */
    private fun extendedColor(foreground: Boolean, start: Int): Int {
        val mode = params.getOrElse(start) { 0 }
        val apply = { spec: ColorSpec ->
            screen.style = if (foreground) screen.style.copy(fg = spec) else screen.style.copy(bg = spec)
        }
        return when (mode) {
            5 -> {
                apply(ColorSpec.Index(params.getOrElse(start + 1) { 0 } and 255))
                2
            }
            2 -> {
                apply(
                    ColorSpec.Rgb(
                        params.getOrElse(start + 1) { 0 } and 255,
                        params.getOrElse(start + 2) { 0 } and 255,
                        params.getOrElse(start + 3) { 0 } and 255
                    )
                )
                4
            }
            else -> 0
        }
    }

    private fun flag(s: Style, bits: Int, on: Boolean): Style =
        if (on) s.copy(flags = s.flags or bits) else s.copy(flags = s.flags and bits.inv())

    // ---------------------------------------------------------------- OSC / DCS

    private fun oscChar(b: Int) {
        if (escPending) {
            escPending = false
            if (b == '\\'.code) finishOsc() else osc.append(0x1b.toChar()).append(b.toChar())
            return
        }
        when (b) {
            0x07 -> finishOsc()
            0x1B -> escPending = true
            else -> osc.append(b.toChar())
        }
    }

    private fun finishOsc() {
        val text = osc.toString()
        val sep = text.indexOf(';')
        if (sep > 0) {
            val kind = text.substring(0, sep)
            val value = text.substring(sep + 1)
            if (kind == "0" || kind == "1" || kind == "2") screen.title = value
            // kind "8" (hyperlinks) and everything else: ignored
        }
        state = State.GROUND
    }

    private fun dcsChar(b: Int) {
        if (escPending) {
            escPending = false
            if (b == '\\'.code) state = State.GROUND
            return
        }
        if (b == 0x1B) escPending = true
    }
}
