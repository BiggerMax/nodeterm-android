package com.nodeterm.android.core.vt

import kotlin.math.max
import kotlin.math.min

/**
 * The terminal screen model driven by [VtParser]: a character grid with per-cell styles, a
 * cursor, a scroll region, a scrolled-off transcript (scrollback), and a separate alternate
 * screen buffer (the state tmux panes live in while running full-screen programs).
 *
 * Pure JVM, no Android dependencies — unit-tested in :core against reference ANSI behaviour.
 */
class VtScreen(
    var cols: Int = 80,
    var rows: Int = 24,
    val scrollbackLimit: Int = 2000,
) {
    /** One renderable buffer (primary or alternate). */
    private class Buffer {
        var cols = 0
        var rows = 0
        lateinit var cells: Array<Cell>

        var cursorRow = 0
        var cursorCol = 0
        var savedRow = 0
        var savedCol = 0
        var savedStyle = Style.DEFAULT
        var wrapPending = false

        var scrollTop = 0
        var scrollBottom = 0

        /** Rows scrolled off the top of the primary buffer (capped). */
        val transcript = ArrayDeque<Array<Cell>>()
        var transcriptChars = 0L

        fun alloc(c: Int, r: Int) {
            cols = c
            rows = r
            cells = Array(c * r) { Cell() }
            scrollTop = 0
            scrollBottom = r - 1
        }
    }

    private var primary = Buffer()
    private var alt: Buffer? = null
    private var cur = primary

    /** 1049 entered with a saved cursor that must be restored on exit. */
    private var altRestoreCursor = false

    /** SGR attributes applied to subsequently written characters. */
    var style = Style.DEFAULT

    /** DEC private modes. */
    var cursorVisible = true
    var autowrap = true
    var originMode = false
    var mouseReporting = false

    /** OSC 0/1/2 window title, when the peer sends one. */
    var title = ""

    init {
        primary.alloc(cols, rows)
    }

    val inAlternateScreen: Boolean get() = cur !== primary
    val scrollRegionTop: Int get() = cur.scrollTop
    val scrollRegionBottom: Int get() = cur.scrollBottom
    val cursorRow: Int get() = cur.cursorRow
    val cursorCol: Int get() = cur.cursorCol
    val transcriptSize: Int get() = cur.transcript.size

    // ------------------------------------------------------------------ renderer access

    /** The flat cell grid (row-major, [cols] per row) for the renderer. */
    fun cells(): Array<Cell> = cur.cells

    fun cellAt(r: Int, c: Int): Cell = cur.cells[r * cur.cols + c]

    /** Scrolled-off rows, oldest first. Each row is [cols] cells. */
    fun transcriptLines(): List<Array<Cell>> = cur.transcript.toList()

    // ------------------------------------------------------------------ basic operations

    private fun indexOf(r: Int, c: Int) = r * cur.cols + c

    fun clear() {
        for (i in cur.cells.indices) {
            cur.cells[i].ch = ' '
            cur.cells[i].style = style
        }
        cur.wrapPending = false
    }

    /** Clear the screen for a host snapshot paint: home the cursor and drop the transcript. */
    fun prepareSnapshot() {
        cur.cursorRow = 0
        cur.cursorCol = 0
        cur.wrapPending = false
        cur.transcript.clear()
        cur.transcriptChars = 0
        clear()
    }

    fun reset() {
        style = Style.DEFAULT
        cursorVisible = true
        autowrap = true
        originMode = false
        mouseReporting = false
        title = ""
        exitAlternate()
        cur.cursorRow = 0
        cur.cursorCol = 0
        cur.savedRow = 0
        cur.savedCol = 0
        cur.savedStyle = Style.DEFAULT
        cur.scrollTop = 0
        cur.scrollBottom = cur.rows - 1
        cur.transcript.clear()
        cur.transcriptChars = 0
        clear()
    }

    // ------------------------------------------------------------------ cursor / writing

    /** Move to absolute row/col (0-based), honouring origin mode for DECOM. */
    fun moveTo(row: Int, col: Int) {
        val r = if (originMode) cur.scrollTop + max(0, min(row, cur.scrollBottom - cur.scrollTop)) else max(0, min(row, cur.rows - 1))
        cur.cursorRow = r
        cur.cursorCol = max(0, min(col, cur.cols - 1))
        cur.wrapPending = false
    }

    fun moveUp(n: Int) {
        cur.cursorRow = max(cur.scrollTop, cur.cursorRow - n)
        cur.wrapPending = false
    }

    fun moveDown(n: Int) {
        cur.cursorRow = min(cur.scrollBottom, cur.cursorRow + n)
        cur.wrapPending = false
    }

    fun moveRight(n: Int) {
        cur.cursorCol = min(cur.cols - 1, cur.cursorCol + n)
        cur.wrapPending = false
    }

    fun moveLeft(n: Int) {
        cur.cursorCol = max(0, cur.cursorCol - n)
        cur.wrapPending = false
    }

    fun carriageReturn() {
        cur.cursorCol = 0
        cur.wrapPending = false
    }

    /** Line feed (LF / VT / FF): move down one, scrolling the region at the bottom. */
    fun lineFeed() {
        if (cur.cursorRow == cur.scrollBottom) {
            scrollUp(1)
        } else {
            cur.cursorRow = min(cur.rows - 1, cur.cursorRow + 1)
        }
        cur.wrapPending = false
    }

    /** Reverse index (RI): move up one, scrolling down at the top. */
    fun reverseIndex() {
        if (cur.cursorRow == cur.scrollTop) {
            scrollDown(1)
        } else {
            cur.cursorRow = max(0, cur.cursorRow - 1)
        }
        cur.wrapPending = false
    }

    fun backspace() {
        if (cur.cursorCol > 0) cur.cursorCol--
        cur.wrapPending = false
    }

    fun tab() {
        val next = (cur.cursorCol / 8 + 1) * 8
        cur.cursorCol = min(cur.cols - 1, next)
        cur.wrapPending = false
    }

    /** Write one printable character at the cursor with autowrap + wide-char handling. */
    fun putChar(c: Char) {
        // Wide chars (CJK/emoji) occupy two cells; combining marks are dropped.
        val width = charWidth(c)
        if (width == 0) return

        if (cur.wrapPending && autowrap) {
            if (cur.cursorRow == cur.scrollBottom) scrollUp(1) else cur.cursorRow++
            cur.cursorCol = 0
            cur.wrapPending = false
        }

        val col = cur.cursorCol
        if (width == 2 && col + 1 >= cur.cols) {
            // A wide char that would straddle the margin: wrap first, then place.
            if (cur.cursorRow == cur.scrollBottom) scrollUp(1) else cur.cursorRow++
            cur.cursorCol = 0
            cur.wrapPending = false
        }

        val cc = cur.cursorCol
        cur.cells[indexOf(cur.cursorRow, cc)].ch = c
        cur.cells[indexOf(cur.cursorRow, cc)].style = style
        if (width == 2) {
            if (cc + 1 < cur.cols) {
                cur.cells[indexOf(cur.cursorRow, cc + 1)].ch = '\u0000'
                cur.cells[indexOf(cur.cursorRow, cc + 1)].style = style
                cur.cursorCol = cc + 2
            } else {
                cur.cursorCol = cc + 1
            }
        } else {
            cur.cursorCol = cc + 1
        }
        if (cur.cursorCol >= cur.cols) {
            cur.cursorCol = cur.cols - 1
            cur.wrapPending = true
        }
    }

    fun saveCursor() {
        cur.savedRow = cur.cursorRow
        cur.savedCol = cur.cursorCol
        cur.savedStyle = style
        cur.wrapPending = false
    }

    fun restoreCursor() {
        cur.cursorRow = min(cur.savedRow, cur.rows - 1)
        cur.cursorCol = min(cur.savedCol, cur.cols - 1)
        style = cur.savedStyle
        cur.wrapPending = false
    }

    // ------------------------------------------------------------------ erase

    fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // cursor -> end of screen
                eraseRange(cur.cursorRow, cur.cursorCol, cur.rows - 1, cur.cols - 1)
            }
            1 -> { // start of screen -> cursor
                eraseRange(0, 0, cur.cursorRow, cur.cursorCol)
            }
            2 -> { // whole screen
                eraseRange(0, 0, cur.rows - 1, cur.cols - 1)
            }
            3 -> { // clear scrollback
                cur.transcript.clear()
                cur.transcriptChars = 0
            }
        }
    }

    fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseRange(cur.cursorRow, cur.cursorCol, cur.cursorRow, cur.cols - 1)
            1 -> eraseRange(cur.cursorRow, 0, cur.cursorRow, cur.cursorCol)
            2 -> eraseRange(cur.cursorRow, 0, cur.cursorRow, cur.cols - 1)
        }
    }

    fun eraseChars(n: Int) {
        val end = min(cur.cols - 1, cur.cursorCol + n - 1)
        eraseRange(cur.cursorRow, cur.cursorCol, cur.cursorRow, end)
    }

    private fun eraseRange(r1: Int, c1: Int, r2: Int, c2: Int) {
        for (r in r1..r2) {
            for (c in c1..c2) {
                val i = indexOf(r, c)
                cur.cells[i].ch = ' '
                cur.cells[i].style = style
            }
        }
    }

    // ------------------------------------------------------------------ insert / delete / scroll

    fun insertLines(n: Int) {
        val count = min(n, cur.scrollBottom - cur.cursorRow + 1)
        if (count <= 0) return
        // Rows at/below the cursor shift DOWN by [count], so only r >= cursorRow + count has a
        // valid source row (r - count). Copying r in [cursorRow, cursorRow + count) would read
        // row r - count < cursorRow — negative for a cursor near the top, which blew up the
        // array index and killed the stream (tmux scroll repaints send CSI L with the cursor at
        // row 0). Those top [count] rows are the freshly blanked ones, cleared below.
        for (r in cur.scrollBottom downTo cur.cursorRow + count) {
            copyRow(r - count, r)
        }
        clearRows(cur.cursorRow, count)
    }

    fun deleteLines(n: Int) {
        val count = min(n, cur.scrollBottom - cur.cursorRow + 1)
        if (count <= 0) return
        for (r in cur.cursorRow..cur.scrollBottom - count) {
            copyRow(r + count, r)
        }
        clearRows(cur.scrollBottom - count + 1, count)
    }

    fun insertChars(n: Int) {
        val count = min(n, cur.cols - cur.cursorCol)
        if (count <= 0) return
        for (c in cur.cols - 1 downTo cur.cursorCol + count) {
            copyColChar(cur.cursorRow, c - count, c)
        }
        clearRange(cur.cursorRow, cur.cursorCol, cur.cursorRow, cur.cursorCol + count - 1)
    }

    fun deleteChars(n: Int) {
        val count = min(n, cur.cols - cur.cursorCol)
        if (count <= 0) return
        for (c in cur.cursorCol..cur.cols - 1 - count) {
            copyColChar(cur.cursorRow, c + count, c)
        }
        clearRange(cur.cursorRow, cur.cols - count, cur.cursorRow, cur.cols - 1)
    }

    /** Scroll the region up n rows; rows leaving the top of a full-screen region enter the transcript. */
    fun scrollUp(n: Int) {
        val count = min(n, cur.scrollBottom - cur.scrollTop + 1)
        if (count <= 0) return
        if (cur.scrollTop == 0 && cur.scrollBottom == cur.rows - 1) {
            // Full-screen scroll: push the scrolled-off rows into the transcript.
            for (k in 0 until count) {
                val row = Array(cur.cols) { Cell() }
                for (c in 0 until cur.cols) {
                    val src = cur.cells[indexOf(k, c)]
                    row[c].ch = src.ch
                    row[c].style = src.style
                }
                cur.transcript.addLast(row)
                cur.transcriptChars += cur.cols
            }
            while (cur.transcriptChars > scrollbackLimit.toLong() * cur.cols && cur.transcript.size > 1) {
                cur.transcript.removeFirst()
                cur.transcriptChars -= cur.cols
            }
        }
        for (r in cur.scrollTop..cur.scrollBottom - count) {
            copyRow(r + count, r)
        }
        clearRows(cur.scrollBottom - count + 1, count)
    }

    /** Scroll the region down n rows (blank rows enter at the top). */
    fun scrollDown(n: Int) {
        val count = min(n, cur.scrollBottom - cur.scrollTop + 1)
        if (count <= 0) return
        for (r in cur.scrollBottom downTo cur.scrollTop + count) {
            copyRow(r - count, r)
        }
        clearRows(cur.scrollTop, count)
    }

    private fun copyRow(from: Int, to: Int) {
        val f = from * cur.cols
        val t = to * cur.cols
        for (c in 0 until cur.cols) {
            val src = cur.cells[f + c]
            val dst = cur.cells[t + c]
            dst.ch = src.ch
            dst.style = src.style
        }
    }

    private fun copyColChar(row: Int, from: Int, to: Int) {
        val src = cur.cells[indexOf(row, from)]
        val dst = cur.cells[indexOf(row, to)]
        dst.ch = src.ch
        dst.style = src.style
    }

    private fun clearRows(start: Int, count: Int) {
        val end = min(cur.rows, start + count)
        for (r in start until end) {
            for (c in 0 until cur.cols) {
                val i = indexOf(r, c)
                cur.cells[i].ch = ' '
                cur.cells[i].style = style
            }
        }
    }

    private fun clearRange(r1: Int, c1: Int, r2: Int, c2: Int) {
        eraseRange(r1, c1, r2, c2)
    }

    // ------------------------------------------------------------------ scroll region / alt screen

    /** DECSTBM: set the scrolling region (1-based params; 0/none means full screen). */
    fun setScrollRegion(top: Int, bottom: Int) {
        val t = max(0, min(top, cur.rows - 1))
        val b = if (bottom < 0) cur.rows - 1 else min(bottom, cur.rows - 1)
        if (b <= t) {
            cur.scrollTop = 0
            cur.scrollBottom = cur.rows - 1
        } else {
            cur.scrollTop = t
            cur.scrollBottom = b
        }
        moveTo(0, 0)
    }

    /** 47/1047/1049: switch to the alternate buffer (1049 also saves cursor + clears). */
    fun enterAlternate(saveCursor: Boolean) {
        if (inAlternateScreen) return
        if (saveCursor) {
            saveCursor()
            altRestoreCursor = true
        }
        if (alt == null) alt = Buffer().also { it.alloc(cols, rows) }
        cur = alt!!
        cur.cursorRow = 0
        cur.cursorCol = 0
        cur.scrollTop = 0
        cur.scrollBottom = cur.rows - 1
        clear()
    }

    fun exitAlternate() {
        if (!inAlternateScreen) return
        val restore = altRestoreCursor
        altRestoreCursor = false
        cur = primary
        cur.wrapPending = false
        if (restore) restoreCursor()
    }

    // ------------------------------------------------------------------ resize

    fun resize(newCols: Int, newRows: Int) {
        val c = max(1, newCols)
        val r = max(1, newRows)
        if (c == cur.cols && r == cur.rows) return
        resizeBuffer(primary, c, r)
        alt?.let { resizeBuffer(it, c, r) }
        cols = cur.cols
        rows = cur.rows
    }

    private fun resizeBuffer(b: Buffer, newCols: Int, newRows: Int) {
        val oldCells = b.cells
        val oldCols = b.cols
        val oldRows = b.rows
        val fresh = Array(newCols * newRows) { Cell() }
        for (r in 0 until min(oldRows, newRows)) {
            for (c in 0 until min(oldCols, newCols)) {
                val src = oldCells[r * oldCols + c]
                val dst = fresh[r * newCols + c]
                dst.ch = src.ch
                dst.style = src.style
            }
        }
        b.cells = fresh
        b.cols = newCols
        b.rows = newRows
        b.cursorRow = min(b.cursorRow, newRows - 1)
        b.cursorCol = min(b.cursorCol, newCols - 1)
        b.savedRow = min(b.savedRow, newRows - 1)
        b.savedCol = min(b.savedCol, newCols - 1)
        b.scrollTop = 0
        b.scrollBottom = newRows - 1
    }

    // ------------------------------------------------------------------ wide-char detection

    companion object {
        /** Column width of a UTF-16 code unit: 0 (combining), 1, or 2 (wide). */
        fun charWidth(c: Char): Int {
            val code = c.code
            if (c.isHighSurrogate()) return 2 // surrogate pair head (emoji)
            if (code in 0x0300..0x036F || code in 0x20D0..0x20FF) return 0
            return when {
                code in 0x1100..0x115F -> 2 // Hangul jamo
                code in 0x2E80..0xA4CF -> 2 // CJK radicals..Yi
                code in 0xAC00..0xD7A3 -> 2 // Hangul syllables
                code in 0xF900..0xFAFF -> 2 // CJK compat ideographs
                code in 0xFE30..0xFE4F -> 2 // CJK compat forms
                code in 0xFF00..0xFF60 -> 2 // fullwidth forms
                code in 0xFFE0..0xFFE6 -> 2 // fullwidth signs
                code in 0x20000..0x2FFFD -> 2 // CJK extension B
                code in 0x1F300..0x1FAFF -> 2 // emoji / symbols
                else -> 1
            }
        }
    }
}
