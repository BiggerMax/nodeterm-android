package com.nodeterm.android.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    private fun kinds(line: String, lang: CodeLang): List<Pair<String, HighlightKind>> =
        SyntaxHighlighter.highlight(line, lang)
            .sortedBy { it.start }
            .map { line.substring(it.start, it.start + it.length) to it.kind }

    // ---- language detection -----------------------------------------------------------

    @Test
    fun `detects language by extension`() {
        assertEquals(CodeLang.KOTLIN, SyntaxHighlighter.detectLanguage("Main.kt"))
        assertEquals(CodeLang.KOTLIN, SyntaxHighlighter.detectLanguage("build.gradle.kts"))
        assertEquals(CodeLang.JAVA, SyntaxHighlighter.detectLanguage("Foo.java"))
        assertEquals(CodeLang.TS, SyntaxHighlighter.detectLanguage("relay-socket.ts"))
        assertEquals(CodeLang.TS, SyntaxHighlighter.detectLanguage("app.jsx"))
        assertEquals(CodeLang.PYTHON, SyntaxHighlighter.detectLanguage("main.py"))
        assertEquals(CodeLang.GO, SyntaxHighlighter.detectLanguage("main.go"))
        assertEquals(CodeLang.RUST, SyntaxHighlighter.detectLanguage("lib.rs"))
        assertEquals(CodeLang.SHELL, SyntaxHighlighter.detectLanguage("setup.sh"))
        assertEquals(CodeLang.SWIFT, SyntaxHighlighter.detectLanguage("App.swift"))
        assertEquals(CodeLang.C, SyntaxHighlighter.detectLanguage("util.h"))
        assertEquals(CodeLang.JSON, SyntaxHighlighter.detectLanguage("package.json"))
        assertEquals(CodeLang.YAML, SyntaxHighlighter.detectLanguage("workflow.yml"))
        assertEquals(CodeLang.SQL, SyntaxHighlighter.detectLanguage("query.sql"))
        assertEquals(CodeLang.MARKDOWN, SyntaxHighlighter.detectLanguage("README.md"))
        assertNull(SyntaxHighlighter.detectLanguage("notes.txt"))
        assertNull(SyntaxHighlighter.detectLanguage("Makefile"))
    }

    // ---- kotlin ------------------------------------------------------------------------

    @Test
    fun `kotlin keywords and types`() {
        val line = "fun main() { val x: Int = 42 }"
        val k = kinds(line, CodeLang.KOTLIN)
        assertTrue("fun is keyword", k.contains("fun" to HighlightKind.KEYWORD))
        assertTrue("val is keyword", k.contains("val" to HighlightKind.KEYWORD))
        assertTrue("Int is type", k.contains("Int" to HighlightKind.TYPE))
        assertTrue("42 is number", k.contains("42" to HighlightKind.NUMBER))
    }

    @Test
    fun `kotlin string with escape`() {
        // The raw line contains literal `\"` and `\n` escapes; the tokenizer must capture the
        // whole literal as ONE string token.
        val line = """val s = "a \"quoted\" \n string""""
        val k = kinds(line, CodeLang.KOTLIN)
        assertEquals(1, k.count { it.second == HighlightKind.STRING })
        assertEquals(""""a \"quoted\" \n string"""", k.first { it.second == HighlightKind.STRING }.first)
    }

    @Test
    fun `kotlin line comment swallows the rest`() {
        val line = "val x = 1 // a comment with 2 and fun"
        val k = kinds(line, CodeLang.KOTLIN)
        assertTrue(k.any { it.second == HighlightKind.COMMENT && it.first.contains("comment") })
        assertTrue("nothing after comment is tokenized", k.last().second == HighlightKind.COMMENT)
    }

    @Test
    fun `block comment on one line`() {
        val line = "/* header */ val x = 1"
        val k = kinds(line, CodeLang.KOTLIN)
        assertTrue(k.any { it.second == HighlightKind.COMMENT && it.first == "/* header */" })
    }

    @Test
    fun `kotlin numbers hex float long`() {
        val line = "val a = 0xFF; val b = 3.14; val c = 100L"
        val k = kinds(line, CodeLang.KOTLIN)
        assertTrue(k.contains("0xFF" to HighlightKind.NUMBER))
        assertTrue(k.contains("3.14" to HighlightKind.NUMBER))
        assertTrue(k.contains("100L" to HighlightKind.NUMBER))
    }

    // ---- python / shell / sql comments --------------------------------------------------

    @Test
    fun `python hash comment and strings`() {
        val line = "def f(): # done\n    return 'x'".let { it.substringBefore('\n') }
        val k = kinds(line, CodeLang.PYTHON)
        assertTrue(k.contains("def" to HighlightKind.KEYWORD))
        assertTrue(k.any { it.second == HighlightKind.COMMENT })
    }

    @Test
    fun `sql double-dash comment`() {
        val line = "select * from t -- all rows"
        val k = kinds(line, CodeLang.SQL)
        assertTrue(k.contains("select" to HighlightKind.KEYWORD))
        assertTrue(k.contains("from" to HighlightKind.KEYWORD))
        assertTrue(k.any { it.second == HighlightKind.COMMENT })
    }

    @Test
    fun `shell comment and backtick string`() {
        val line = "echo `date` # print"
        val k = kinds(line, CodeLang.SHELL)
        assertTrue(k.contains("echo" to HighlightKind.KEYWORD))
        assertTrue(k.any { it.second == HighlightKind.STRING })
        assertTrue(k.any { it.second == HighlightKind.COMMENT })
    }

    // ---- markdown -----------------------------------------------------------------------

    @Test
    fun `markdown header and inline code`() {
        val header = kinds("# Title", CodeLang.MARKDOWN)
        assertTrue(header.isNotEmpty())
        val line = "run `gradle build` now"
        val k = kinds(line, CodeLang.MARKDOWN)
        assertTrue(k.any { it.second == HighlightKind.STRING })
    }

    // ---- edges --------------------------------------------------------------------------

    @Test
    fun `triple quoted strings are not colored`() {
        val line = "\"\"\"long text\"\"\""
        val k = kinds(line, CodeLang.PYTHON)
        assertTrue(k.isEmpty())
    }

    @Test
    fun `unknown language yields no tokens`() {
        assertNull(SyntaxHighlighter.detectLanguage("notes.txt"))
        // A plain markdown line has no special tokens at all.
        assertTrue(SyntaxHighlighter.highlight("plain text 123", CodeLang.MARKDOWN).isEmpty())
    }

    @Test
    fun `json keywords`() {
        val line = """{"a": true, "b": null}"""
        val k = kinds(line, CodeLang.JSON)
        assertTrue(k.contains("true" to HighlightKind.KEYWORD))
        assertTrue(k.contains("null" to HighlightKind.KEYWORD))
    }
}
