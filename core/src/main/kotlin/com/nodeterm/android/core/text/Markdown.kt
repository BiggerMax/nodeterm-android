package com.nodeterm.android.core.text

/**
 * A tiny, dependency-free markdown renderer for the read-only file viewer — the mobile mirror of
 * the desktop's `⌘M` markdown view. It only needs to look good for README-style documents, so it
 * deliberately covers the common blocks (headings, paragraphs, lists, quotes, fenced code,
 * rules) and the most-used inline emphasis (bold / italic / code spans / links) — no tables,
 * no nested lists, no HTML.
 *
 * The renderer is UI-agnostic: it returns pure data ([MdLine] / [MdSegment]) and lets the
 * Compose layer decide fonts, colours and spacing. Kept in :core so it stays unit-testable
 * with a plain JDK, like the syntax highlighter next to it.
 */

/** Block-level kind for one rendered markdown line. */
enum class MdKind {
    /** `#` … `######` — [MdLine.level] carries 1..6. */
    HEADING,
    PARAGRAPH,
    /** `> quoted` */
    QUOTE,
    /** `- item`, `* item`, `1. item` */
    LIST_ITEM,
    /** Inside a ``` fenced block — the raw text lives in [MdLine.raw]. */
    CODE,
    /** `---` / `***` / `___` */
    RULE,
    /** Blank line — a vertical spacer. */
    EMPTY
}

/** One inline segment with its emphasis flags (bold / italic / code span / link). */
data class MdSegment(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false
)

/** A single rendered line of markdown. */
data class MdLine(
    val kind: MdKind,
    val level: Int = 0,
    val segments: List<MdSegment> = emptyList(),
    /** Raw line text (code blocks, where inline parsing does not apply). */
    val raw: String = ""
)

object Markdown {

    /** Whether a filename should be rendered as markdown (⌘M view). */
    fun isMarkdown(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".md") ||
            n.endsWith(".markdown") ||
            n.endsWith(".mdown") ||
            n == "readme"
    }

    /** Render [text] into block/segment data for the Compose layer. Never throws. */
    fun render(text: String): List<MdLine> {
        val src = text.split("\n")
        val out = mutableListOf<MdLine>()
        var i = 0
        while (i < src.size) {
            val trimmed = src[i].trim()

            // Fenced code block: ``` (or ~~~) opens, the matching fence closes.
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = trimmed.first()
                out += MdLine(MdKind.CODE, raw = trimmed)
                i++
                while (i < src.size) {
                    val t = src[i].trim()
                    out += MdLine(MdKind.CODE, raw = src[i])
                    i++
                    if (t.startsWith("```") || t.startsWith("~~~")) {
                        if (t.first() == fence) break
                    }
                }
                continue
            }

            // ATX headings: 1-6 #s followed by space + text.
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                out += MdLine(
                    MdKind.HEADING,
                    level = heading.groupValues[1].length,
                    segments = parseInline(heading.groupValues[2])
                )
                i++
                continue
            }

            // Horizontal rule.
            if (Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$").matches(trimmed)) {
                out += MdLine(MdKind.RULE)
                i++
                continue
            }

            // Blank line.
            if (trimmed.isEmpty()) {
                out += MdLine(MdKind.EMPTY)
                i++
                continue
            }

            // Blockquote.
            if (trimmed.startsWith(">")) {
                out += MdLine(MdKind.QUOTE, segments = parseInline(trimmed.removePrefix(">").trim()))
                i++
                continue
            }

            // List items (unordered and ordered).
            val ul = Regex("^[-*+]\\s+(.*)$").find(trimmed)
            if (ul != null) {
                out += MdLine(MdKind.LIST_ITEM, segments = parseInline(ul.groupValues[1]))
                i++
                continue
            }
            val ol = Regex("^\\d+[.)]\\s+(.*)$").find(trimmed)
            if (ol != null) {
                out += MdLine(MdKind.LIST_ITEM, segments = parseInline(ol.groupValues[1]))
                i++
                continue
            }

            out += MdLine(MdKind.PARAGRAPH, segments = parseInline(trimmed))
            i++
        }
        return out
    }

    /**
     * Scan one line for inline emphasis. Handles `` `code` ``, `**bold**`, `*italic*` / `_italic_`,
     * and `[text](url)` links, in any order. Segments are emitted with the flags active at the
     * time the text was buffered — a segment boundary is created whenever a flag flips.
     */
    private fun parseInline(src: String): List<MdSegment> {
        val out = mutableListOf<MdSegment>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        // Position of a pending single-char closing marker (* or _) already promised on the line.
        var pendingClose = -1
        // Position of a pending double-char closing marker (** / __) — the START of the pair.
        var pendingBoldClose = -1

        fun flush() {
            if (buf.isNotEmpty()) {
                out += MdSegment(buf.toString(), bold = bold, italic = italic)
                buf.clear()
            }
        }

        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                // Code span: backtick … backtick.
                c == '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out += MdSegment(src.substring(i + 1, end), code = true)
                        i = end + 1
                    } else {
                        buf.append(c)
                        i++
                    }
                }
                // Link: [text](url).
                c == '[' -> {
                    val close = src.indexOf(']', i)
                    if (close > i && close + 1 < n && src[close + 1] == '(') {
                        val parenEnd = src.indexOf(')', close + 2)
                        if (parenEnd > close) {
                            flush()
                            out += MdSegment(
                                src.substring(i + 1, close),
                                bold = bold,
                                italic = italic,
                                link = true
                            )
                            i = parenEnd + 1
                        } else {
                            buf.append(c)
                            i++
                        }
                    } else {
                        buf.append(c)
                        i++
                    }
                }
                // Bold / italic markers only take effect when a matching closer exists later on
                // the line — an unmatched mark ("3 * 4 = 12" or "**x") stays literal text.
                c == '*' || c == '_' -> {
                    when {
                        i == pendingBoldClose -> {
                            flush()
                            bold = !bold
                            pendingBoldClose = -1
                            i += 2
                        }
                        i == pendingClose -> {
                            flush()
                            italic = !italic
                            pendingClose = -1
                            i++
                        }
                        i + 1 < n && src[i + 1] == c -> {
                            // Candidate bold pair — only opens if a matching **/__ pair follows.
                            val close = findDouble(src, c, i + 2)
                            if (close > i) {
                                flush()
                                bold = !bold
                                pendingBoldClose = close
                                i += 2
                            } else {
                                buf.append(c)
                                i++
                            }
                        }
                        else -> {
                            // Candidate italic mark — only opens if a later single marker exists.
                            val close = src.indexOf(c, i + 1)
                            if (close > i && (pendingClose == -1 || close != pendingClose)) {
                                flush()
                                italic = !italic
                                pendingClose = close
                                i++
                            } else {
                                buf.append(c)
                                i++
                            }
                        }
                    }
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** First position ≥ [from] where [c] starts a double marker ("**" / "__"), or -1. */
    private fun findDouble(src: String, c: Char, from: Int): Int {
        var j = from
        while (j < src.length - 1) {
            if (src[j] == c && src[j + 1] == c) return j
            j++
        }
        return -1
    }
}
