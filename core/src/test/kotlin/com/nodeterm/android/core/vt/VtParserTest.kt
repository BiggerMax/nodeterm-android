package com.nodeterm.android.core.vt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dump a visible line as text (placeholder cells render as spaces). */
private fun lineText(screen: VtScreen, r: Int): String {
    val sb = StringBuilder()
    for (c in 0 until screen.cols) {
        val ch = screen.cellAt(r, c).ch
        sb.append(if (ch == '\u0000') ' ' else ch)
    }
    return sb.toString()
}

private fun cellStyle(screen: VtScreen, r: Int, c: Int): Style = screen.cellAt(r, c).style

class VtParserTest {

    private fun parser(cols: Int = 80, rows: Int = 24): Pair<VtParser, VtScreen> {
        val screen = VtScreen(cols, rows)
        return VtParser(screen) to screen
    }

    @Test
    fun plainTextWritesAtCursor() {
        val (p, s) = parser()
        p.feed("hello")
        assertEquals("hello", lineText(s, 0).substring(0, 5))
        assertEquals(5, s.cursorCol)
    }

    @Test
    fun carriageReturnMovesToColumnZero() {
        val (p, s) = parser()
        p.feed("abcdef")
        p.feed("\rXY")
        assertEquals("XYcdef", lineText(s, 0).substring(0, 6))
    }

    @Test
    fun lineFeedMovesDownWithoutColumnReset() {
        val (p, s) = parser()
        p.feed("a\nb")
        assertEquals('a', s.cellAt(0, 0).ch)
        assertEquals('b', s.cellAt(1, 1).ch) // LF alone doesn't CR (CR is separate)
    }

    @Test
    fun autowrapMovesToNextLineAtMargin() {
        val (p, s) = parser(cols = 10, rows = 5)
        p.feed("abcdefghij") // exactly 10
        assertEquals(9, s.cursorCol)
        p.feed("k") // wraps
        assertEquals(1, s.cursorRow)
        assertEquals(1, s.cursorCol) // after writing k at col 0
        assertEquals('k', s.cellAt(1, 0).ch)
    }

    @Test
    fun autowrapOffOverwritesLastCell() {
        val (p, s) = parser(cols = 5, rows = 3)
        p.feed("\u001b[?7l")
        p.feed("abcde")
        p.feed("X")
        assertEquals(0, s.cursorRow)
        assertEquals(4, s.cursorCol)
        assertEquals('X', s.cellAt(0, 4).ch)
    }

    @Test
    fun lineFeedAtBottomScrollsAndRecordsTranscript() {
        val (p, s) = parser(cols = 5, rows = 3)
        p.feed("11111\r\n22222\r\n33333\r\n44444") // pty lines end with \r\n
        // 4 lines through a 3-row screen → the first scrolled off.
        assertEquals(1, s.transcriptSize)
        assertEquals("22222", lineText(s, 0))
        assertEquals("33333", lineText(s, 1))
        assertEquals("44444", lineText(s, 2))
    }

    @Test
    fun sgrColorsAndReset() {
        val (p, s) = parser()
        p.feed("\u001b[31mred")
        assertEquals(ColorSpec.Index(1), cellStyle(s, 0, 0).fg)
        assertEquals(ColorSpec.Index(1), cellStyle(s, 0, 2).fg)
        p.feed("\u001b[0mplain")
        assertEquals(ColorSpec.Default, cellStyle(s, 0, 3).fg)
        assertEquals(ColorSpec.Default, cellStyle(s, 0, 4).fg)
    }

    @Test
    fun sgrBoldUnderlineInverseFlags() {
        val (p, s) = parser()
        p.feed("\u001b[1;4;7mX")
        val st = cellStyle(s, 0, 0)
        assertTrue(st.bold)
        assertTrue(st.underline)
        assertTrue(st.inverse)
        p.feed("\u001b[22;24;27mY")
        val st2 = cellStyle(s, 0, 1)
        assertFalse(st2.bold)
        assertFalse(st2.underline)
        assertFalse(st2.inverse)
    }

    @Test
    fun sgrBackgroundAndBright() {
        val (p, s) = parser()
        p.feed("\u001b[42m\u001b[97mX")
        val st = cellStyle(s, 0, 0)
        assertEquals(ColorSpec.Index(2), st.bg)
        assertEquals(ColorSpec.Index(97 - 90 + 8), st.fg)
    }

    @Test
    fun sgr256Color() {
        val (p, s) = parser()
        p.feed("\u001b[38;5;196mX")
        assertEquals(ColorSpec.Index(196), cellStyle(s, 0, 0).fg)
        p.feed("\u001b[48;5;21mY")
        assertEquals(ColorSpec.Index(21), cellStyle(s, 0, 1).bg)
    }

    @Test
    fun sgrTruecolor() {
        val (p, s) = parser()
        p.feed("\u001b[38;2;1;2;3mX")
        assertEquals(ColorSpec.Rgb(1, 2, 3), cellStyle(s, 0, 0).fg)
    }

    @Test
    fun cursorAddressingAndMovement() {
        val (p, s) = parser(rows = 10)
        p.feed("\u001b[5;7H") // CUP row5 col7 (1-based)
        assertEquals(4, s.cursorRow)
        assertEquals(6, s.cursorCol)
        p.feed("\u001b[2A") // CUU 2
        assertEquals(2, s.cursorRow)
        assertEquals(6, s.cursorCol)
        p.feed("\u001b[3B") // CUD 3
        assertEquals(5, s.cursorRow)
        p.feed("\u001b[2C") // CUF 2
        assertEquals(8, s.cursorCol)
        p.feed("\u001b[3D") // CUB 3
        assertEquals(5, s.cursorCol)
        p.feed("X")
        assertEquals('X', s.cellAt(5, 5).ch)
    }

    @Test
    fun eraseDisplayAndHome() {
        val (p, s) = parser(cols = 5, rows = 3)
        p.feed("abcde\nfghij\nklmno")
        p.feed("\u001b[2J\u001b[H")
        assertEquals(' ', s.cellAt(0, 0).ch)
        assertEquals(' ', s.cellAt(2, 4).ch)
        assertEquals(0, s.cursorRow)
        assertEquals(0, s.cursorCol)
    }

    @Test
    fun eraseLineModes() {
        val (p, s) = parser(cols = 6, rows = 3)
        p.feed("abcdef")
        p.feed("\u001b[4G\u001b[0K") // CHA 4 + EL 0 → clears cursor→end
        assertEquals("abc", lineText(s, 0).substring(0, 3))
        assertEquals(' ', s.cellAt(0, 3).ch)
        assertEquals(' ', s.cellAt(0, 5).ch)
        p.feed("\u001b[2G\u001b[1K") // CHA 2 + EL 1 → clears start→cursor
        assertEquals(' ', s.cellAt(0, 0).ch)
        assertEquals(' ', s.cellAt(0, 1).ch)
        assertEquals('c', s.cellAt(0, 2).ch)
        p.feed("\u001b[1G\u001b[2K") // EL 2 → whole line
        assertTrue(lineText(s, 0).isBlank())
    }

    @Test
    fun eraseCharsEch() {
        val (p, s) = parser(cols = 6, rows = 2)
        p.feed("abcdef")
        p.feed("\u001b[2G\u001b[3X") // CHA 2 + ECH 3 → clears cells 1..3
        assertEquals('a', s.cellAt(0, 0).ch)
        assertEquals(' ', s.cellAt(0, 1).ch)
        assertEquals(' ', s.cellAt(0, 3).ch)
        assertEquals('e', s.cellAt(0, 4).ch)
    }

    @Test
    fun scrollRegionLocksLines() {
        val (p, s) = parser(cols = 5, rows = 5)
        p.feed("aaaaa\r\nbbbbb\r\nccccc\r\nddddd\r\neeeee")
        p.feed("\u001b[2;4r\u001b[4;1H") // region rows 2..4, cursor row 3 (0-based)
        p.feed("\n\n\n")
        // Rows above and below the region are locked; the region scrolls with blanks at its bottom.
        assertEquals("aaaaa", lineText(s, 0))
        assertEquals("eeeee", lineText(s, 4))
        assertTrue(lineText(s, 3).isBlank())
        p.feed("\u001b[r") // reset region
    }

    @Test
    fun insertAndDeleteLines() {
        val (p, s) = parser(cols = 5, rows = 5)
        p.feed("aaaaa\r\nbbbbb\r\nccccc\r\nddddd\r\neeeee")
        p.feed("\u001b[3;1H\u001b[1L") // row 3, insert 1 line
        assertEquals("aaaaa", lineText(s, 0))
        assertEquals("bbbbb", lineText(s, 1))
        assertTrue(lineText(s, 2).isBlank())
        assertEquals("ccccc", lineText(s, 3))
        p.feed("\u001b[3;1H\u001b[1M") // delete the blank line again
        assertEquals("ccccc", lineText(s, 2))
        assertEquals("ddddd", lineText(s, 3))
    }

    @Test
    fun insertLinesAtTopDoesNotUnderflow() {
        // tmux scroll repaints send CSI L (insert lines) with the cursor near the TOP of the
        // buffer; the copy loop must never read rows above row 0 (previously threw
        // ArrayIndexOutOfBoundsException in VtScreen.copyRow and killed the stream).
        val (p, s) = parser(cols = 5, rows = 5)
        p.feed("aaaaa\r\nbbbbb\r\nccccc\r\nddddd\r\neeeee")
        p.feed("\u001b[1;1H\u001b[1L") // insert 1 line at the top row
        assertTrue(lineText(s, 0).isBlank())
        assertEquals("aaaaa", lineText(s, 1))
        assertEquals("bbbbb", lineText(s, 2))
        assertEquals("ccccc", lineText(s, 3))
        assertEquals("ddddd", lineText(s, 4))

        // Overflow-style counts clamp to the region instead of underflowing.
        p.feed("\u001b[1;1H\u001b[9999L")
        assertTrue(lineText(s, 0).isBlank())
        assertTrue(lineText(s, 2).isBlank())
        assertTrue(lineText(s, 4).isBlank())
    }

    @Test
    fun insertAndDeleteChars() {
        val (p, s) = parser(cols = 6, rows = 2)
        p.feed("abcdef")
        p.feed("\u001b[2G\u001b[2@") // CHA 2, insert 2 blanks
        assertEquals("a  bc", lineText(s, 0).substring(0, 5))
        p.feed("\u001b[2G\u001b[2P") // delete 2 blanks, shifting left
        assertEquals("abcd", lineText(s, 0).substring(0, 4))
        assertEquals(' ', s.cellAt(0, 4).ch)
    }

    @Test
    fun scrollUpAndDown() {
        val (p, s) = parser(cols = 4, rows = 4)
        p.feed("aaaa\r\nbbbb\r\ncccc\r\ndddd")
        p.feed("\u001b[1S") // SU 1
        assertEquals("bbbb", lineText(s, 0))
        assertEquals("dddd", lineText(s, 2))
        assertTrue(lineText(s, 3).isBlank())
        p.feed("\u001b[1T") // SD 1
        assertEquals("", lineText(s, 0).trim())
        assertEquals("bbbb", lineText(s, 1))
    }

    @Test
    fun reverseIndexScrollsAtTop() {
        val (p, s) = parser(cols = 3, rows = 3)
        p.feed("aaa\r\nbbb")
        p.feed("\u001b[1;1H") // home
        p.feed("\u001bM") // RI → scroll down
        assertTrue(lineText(s, 0).isBlank())
        assertEquals("aaa", lineText(s, 1))
        assertEquals(0, s.cursorRow) // cursor stays at the top of the region
    }

    @Test
    fun alternateScreenSwapsBuffers() {
        val (p, s) = parser(cols = 5, rows = 3)
        p.feed("HELLO")
        p.feed("\u001b[?1049h")
        assertTrue(s.inAlternateScreen)
        assertEquals("", lineText(s, 0).trim())
        p.feed("WORLD")
        assertEquals("WORLD", lineText(s, 0).substring(0, 5))
        p.feed("\u001b[?1049l")
        assertFalse(s.inAlternateScreen)
        assertEquals("HELLO", lineText(s, 0).substring(0, 5))
    }

    @Test
    fun repeatLastPrintedChar() {
        val (p, s) = parser(cols = 10, rows = 2)
        p.feed("A\u001b[4b")
        assertEquals("AAAAA", lineText(s, 0).substring(0, 5))
    }

    @Test
    fun risFullReset() {
        val (p, s) = parser(cols = 5, rows = 3)
        p.feed("\u001b[31mred\nblue")
        p.feed("\u001bc") // RIS
        assertEquals(ColorSpec.Default, cellStyle(s, 0, 0).fg)
        assertEquals(' ', s.cellAt(0, 0).ch)
        assertEquals(0, s.cursorRow)
        assertEquals(0, s.cursorCol)
    }

    @Test
    fun oscSetsTitle() {
        val (p, s) = parser()
        p.feed("\u001b]0;my node title\u0007")
        assertEquals("my node title", s.title)
        p.feed("\u001b]2;other\u001b\\")
        assertEquals("other", s.title)
    }

    @Test
    fun utf8MultiByteAndWideChars() {
        val (p, s) = parser(cols = 12, rows = 2)
        p.feed("héllo")
        assertEquals("héllo", lineText(s, 0).substring(0, 5))
        p.feed("\r中") // CJK wide char → 2 cells
        assertEquals('中', s.cellAt(0, 0).ch)
        assertEquals('\u0000', s.cellAt(0, 1).ch)
        assertEquals(2, s.cursorCol)
    }

    @Test
    fun fragmentedSequencesAcrossFeedCalls() {
        val (p, s) = parser(rows = 10)
        val full = "\u001b[5;7H\u001b[31mred".toByteArray(Charsets.UTF_8)
        // Feed one byte at a time — the machine must reassemble.
        for (b in full) p.feed(byteArrayOf(b))
        assertEquals(4, s.cursorRow)
        assertEquals(9, s.cursorCol) // after writing red at cols 6..8
        assertEquals(ColorSpec.Index(1), cellStyle(s, 4, 6).fg)
        assertEquals("red", lineText(s, 4).substring(6, 9))
    }

    @Test
    fun utf8SplitAcrossFrames() {
        val (p, s) = parser(cols = 10, rows = 2)
        val bytes = "é".toByteArray(Charsets.UTF_8) // 2 bytes
        p.feed(byteArrayOf(bytes[0]))
        p.feed(byteArrayOf(bytes[1]))
        assertEquals('é', s.cellAt(0, 0).ch)
    }

    @Test
    fun unknownSequencesAreIgnoredSafely() {
        val (p, s) = parser(cols = 12, rows = 4)
        p.feed("\u001b[?1000h\u001b[>4;2m\u001b]8;;http://x\u0007\u001bPq#0;2;0;0;0;1;0;0\u001b\\\u001b[2;1;2;1t")
        p.feed("still alive")
        assertEquals("still alive", lineText(s, 0).substring(0, 11))
    }

    @Test
    fun tabStopsEveryEight() {
        val (p, s) = parser(cols = 20, rows = 2)
        p.feed("a\tb")
        assertEquals('a', s.cellAt(0, 0).ch)
        assertEquals('b', s.cellAt(0, 8).ch) // next tab stop after col 1 is col 8
        assertEquals(9, s.cursorCol)
    }

    @Test
    fun resizePreservesTopLeftContent() {
        val (p, s) = parser(cols = 5, rows = 3)
        p.feed("abcde\r\nfghij")
        s.resize(3, 4)
        assertEquals(3, s.cols)
        assertEquals(4, s.rows)
        assertEquals("abc", lineText(s, 0))
        assertEquals("fgh", lineText(s, 1))
    }

    @Test
    fun saveRestoreCursorAndStyle() {
        val (p, s) = parser(rows = 8)
        p.feed("\u001b[31m\u001b[4;3H")
        p.feed("\u001b7")
        p.feed("\u001b[32m\u001b[1;1HX")
        p.feed("\u001b8")
        assertEquals(3, s.cursorRow)
        assertEquals(2, s.cursorCol)
        assertEquals(ColorSpec.Index(1), s.style.fg)
    }

    @Test
    fun originModeOffsetsCursor() {
        val (p, s) = parser(rows = 6)
        p.feed("\u001b[2;4r\u001b[?6h")
        p.feed("\u001b[2;1H") // row 2 of the region (1-based) → absolute row = scrollTop + 1
        assertEquals(2, s.cursorRow)
        assertEquals(0, s.cursorCol)
        p.feed("X")
        assertEquals('X', s.cellAt(2, 0).ch)
    }

    @Test
    fun scrollbackIsCapped() {
        val screen = VtScreen(80, 24, scrollbackLimit = 5)
        val p = VtParser(screen)
        repeat(60) { p.feed("line$it\n") }
        assertTrue(screen.transcriptSize <= 6)
    }

    @Test
    fun snapshotStylePaintIsRepresentable() {
        // A realistic tmux capture-pane -e paint: hide cursor, clear, home, then painted lines.
        val (p, s) = parser(cols = 10, rows = 4)
        p.feed("\u001b[?25l\u001b[2J\u001b[H")
        p.feed("\u001b[1;32mgreen text\u001b[0m")
        p.feed("\r\n\u001b[31mred here")
        assertFalse(s.cursorVisible)
        assertEquals(ColorSpec.Index(2), cellStyle(s, 0, 0).fg)
        assertEquals("green text", lineText(s, 0).substring(0, 10))
        assertEquals(ColorSpec.Index(1), cellStyle(s, 1, 0).fg)
        assertEquals("red here", lineText(s, 1).substring(0, 8))
    }
}
