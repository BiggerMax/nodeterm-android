package com.nodeterm.android.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownTest {

    @Test
    fun detectsMarkdownFilenames() {
        assertTrue(Markdown.isMarkdown("README.md"))
        assertTrue(Markdown.isMarkdown("docs/guide.MARKDOWN"))
        assertTrue(Markdown.isMarkdown("notes.mdown"))
        assertTrue(Markdown.isMarkdown("readme"))
        assertFalse(Markdown.isMarkdown("MainActivity.kt"))
        assertFalse(Markdown.isMarkdown("notes.txt"))
        assertFalse(Markdown.isMarkdown("md"))
    }

    @Test
    fun headingLevels() {
        val lines = Markdown.render("# Title\n## Sub\n### Sub3\n")
        assertEquals(MdKind.HEADING, lines[0].kind)
        assertEquals(1, lines[0].level)
        assertEquals("Title", lines[0].segments.single().text)
        assertEquals(2, lines[1].level)
        assertEquals("Sub", lines[1].segments.single().text)
        assertEquals(3, lines[2].level)
        assertEquals("Sub3", lines[2].segments.single().text)
    }

    @Test
    fun inlineBoldItalicCodeAndLinks() {
        val lines = Markdown.render("Use **bold**, *italic*, `code` and [a link](https://x.dev).")
        val segs = lines.single().segments
        assertTrue(segs.any { it.text == "bold" && it.bold && !it.italic && !it.code })
        assertTrue(segs.any { it.text == "italic" && it.italic && !it.bold })
        assertTrue(segs.any { it.text == "code" && it.code })
        assertTrue(segs.any { it.text == "a link" && it.link })
        // Ordinary words carry no flags.
        assertTrue(segs.any { it.text == "Use " && !it.bold && !it.italic && !it.code && !it.link })
    }

    @Test
    fun unmatchedMarkersStayLiteral() {
        val segs = Markdown.render("3 * 4 = 12").single().segments
        assertEquals("3 * 4 = 12", segs.joinToString("") { it.text })
    }

    @Test
    fun boldNeedsMatchedPair() {
        // Unmatched "**" stays literal.
        assertEquals("**x", Markdown.render("**x").single().segments.joinToString("") { it.text })
        // Matched pair toggles bold on the inner text only.
        val bold = Markdown.render("**b**").single().segments
        assertTrue(bold.any { it.text == "b" && it.bold })
        // Two pairs on one line each close independently.
        val two = Markdown.render("**a** and **b**").single().segments
        assertTrue(two.any { it.text == "a" && it.bold })
        assertTrue(two.any { it.text == "b" && it.bold })
        assertTrue(two.any { it.text == " and " && !it.bold })
    }

    @Test
    fun fencedCodeBlockCollectsRawLines() {
        val lines = Markdown.render("```kotlin\nval x = 1\n```\nAfter")
        assertEquals(MdKind.CODE, lines[0].kind)
        assertEquals("```kotlin", lines[0].raw)
        assertEquals(MdKind.CODE, lines[1].kind)
        assertEquals("val x = 1", lines[1].raw)
        assertEquals(MdKind.CODE, lines[2].kind)
        assertEquals("```", lines[2].raw)
        assertEquals(MdKind.PARAGRAPH, lines[3].kind)
        assertEquals("After", lines[3].segments.single().text)
    }

    @Test
    fun listsQuotesAndRules() {
        val lines = Markdown.render("- one\n* two\n1. three\n> quoted\n---")
        assertEquals(MdKind.LIST_ITEM, lines[0].kind)
        assertEquals("one", lines[0].segments.single().text)
        assertEquals(MdKind.LIST_ITEM, lines[1].kind)
        assertEquals("two", lines[1].segments.single().text)
        assertEquals(MdKind.LIST_ITEM, lines[2].kind)
        assertEquals("three", lines[2].segments.single().text)
        assertEquals(MdKind.QUOTE, lines[3].kind)
        assertEquals("quoted", lines[3].segments.single().text)
        assertEquals(MdKind.RULE, lines[4].kind)
    }

    @Test
    fun blankLinesBecomeEmpty() {
        val lines = Markdown.render("a\n\nb")
        assertEquals(MdKind.PARAGRAPH, lines[0].kind)
        assertEquals(MdKind.EMPTY, lines[1].kind)
        assertEquals(MdKind.PARAGRAPH, lines[2].kind)
    }

    @Test
    fun emptyAndWeirdInputsNeverThrow() {
        // "".split("\n") yields one empty string — so an empty doc is a single EMPTY line.
        assertTrue(Markdown.render("").all { it.kind == MdKind.EMPTY })
        assertTrue(Markdown.render("\n\n").all { it.kind == MdKind.EMPTY })
        val lines = Markdown.render("```\nnever closed")
        assertTrue(lines.all { it.kind == MdKind.CODE })
        assertEquals(6, Markdown.render("###### Deep").single().level)
        assertEquals(MdKind.PARAGRAPH, Markdown.render("####### Deep").single().kind)
    }
}
