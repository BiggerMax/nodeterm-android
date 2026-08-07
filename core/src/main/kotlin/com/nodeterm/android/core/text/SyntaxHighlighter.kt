package com.nodeterm.android.core.text

/**
 * Lightweight per-line syntax tokenizer for the read-only file viewer.
 *
 * Deliberately line-based (the viewer renders one line per Text item): single-line comments,
 * strings and numbers are fully handled; multi-line block comments / triple-quoted strings are
 * colored only while they start AND end on the same line (rare in practice — acceptable for a
 * read-only mobile viewer; the fallback is plain text, never wrong content).
 */
enum class HighlightKind { PLAIN, COMMENT, STRING, NUMBER, KEYWORD, TYPE }

data class HighlightToken(val start: Int, val length: Int, val kind: HighlightKind)

enum class CodeLang {
    KOTLIN, JAVA, TS, PYTHON, GO, RUST, SHELL, SWIFT, C, JSON, YAML, SQL, MARKDOWN
}

object SyntaxHighlighter {

    /** Language for a file name (by extension), or null → plain rendering. */
    fun detectLanguage(fileName: String): CodeLang? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> CodeLang.KOTLIN
            "java" -> CodeLang.JAVA
            "ts", "mts", "cts", "tsx", "js", "mjs", "cjs", "jsx" -> CodeLang.TS
            "py" -> CodeLang.PYTHON
            "go" -> CodeLang.GO
            "rs" -> CodeLang.RUST
            "sh", "bash", "zsh", "fish" -> CodeLang.SHELL
            "swift" -> CodeLang.SWIFT
            "c", "h", "cpp", "hpp", "cc", "cxx" -> CodeLang.C
            "json", "jsonc" -> CodeLang.JSON
            "yml", "yaml" -> CodeLang.YAML
            "sql" -> CodeLang.SQL
            "md", "markdown" -> CodeLang.MARKDOWN
            else -> null
        }
    }

    /** Tokenize one line. Returns empty for MARKDOWN-unrelated plain lines and unknown languages. */
    fun highlight(line: String, lang: CodeLang): List<HighlightToken> {
        if (line.isEmpty()) return emptyList()
        return when (lang) {
            CodeLang.MARKDOWN -> highlightMarkdown(line)
            else -> highlightCode(line, lang)
        }
    }

    // ---- code lines ---------------------------------------------------------------------

    private fun highlightCode(line: String, lang: CodeLang): List<HighlightToken> {
        val tokens = mutableListOf<HighlightToken>()
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]

            // Line comment — consumes the rest of the line.
            if (isLineCommentStart(line, i, lang)) {
                tokens += HighlightToken(i, n - i, HighlightKind.COMMENT)
                break
            }
            // Block comment fully contained in this line.
            if (line.startsWith("/*", i)) {
                val end = line.indexOf("*/", i + 2)
                if (end >= 0) {
                    tokens += HighlightToken(i, end + 2 - i, HighlightKind.COMMENT)
                    i = end + 2
                    continue
                }
            }
            // Triple-quoted blocks (python/kotlin) → plain: they span lines, so skip 3 chars.
            if (line.startsWith("\"\"\"", i) || line.startsWith("'''", i)) {
                i += 3
                continue
            }
            // String literal (same line, with escapes).
            if (isStringQuote(c, lang)) {
                val len = consumeString(line, i, lang)
                tokens += HighlightToken(i, len, HighlightKind.STRING)
                i += len
                continue
            }
            // Number.
            if (isDigitStart(line, i)) {
                val m = NUMBER_RE.find(line, i)
                if (m != null && m.range.first == i) {
                    tokens += HighlightToken(i, m.value.length, HighlightKind.NUMBER)
                    i = m.range.last + 1
                    continue
                }
            }
            // Identifier → keyword / type heuristic.
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                val kind = when {
                    word in keywords(lang) -> HighlightKind.KEYWORD
                    isTypeWord(word, lang) -> HighlightKind.TYPE
                    else -> HighlightKind.PLAIN
                }
                if (kind != HighlightKind.PLAIN) tokens += HighlightToken(i, j - i, kind)
                i = j
                continue
            }
            i++
        }
        return tokens
    }

    private fun isLineCommentStart(line: String, i: Int, lang: CodeLang): Boolean = when (lang) {
        CodeLang.PYTHON, CodeLang.SHELL, CodeLang.YAML -> line[i] == '#'
        CodeLang.SQL -> line.startsWith("--", i)
        else -> line.startsWith("//", i)
    }

    private fun isStringQuote(c: Char, lang: CodeLang): Boolean = when (lang) {
        // TS template literals and shell backticks are strings too.
        CodeLang.TS, CodeLang.SHELL -> c == '"' || c == '\'' || c == '`'
        else -> c == '"' || c == '\''
    }

    /** Consume a same-line string starting at i; returns length (may run to EOL if unterminated). */
    private fun consumeString(line: String, i: Int, lang: CodeLang): Int {
        val quote = line[i]
        var j = i + 1
        val n = line.length
        while (j < n) {
            val c = line[j]
            if (c == '\\') { j += 2; continue } // skip escaped char
            if (c == quote) return j + 1 - i
            j++
        }
        return n - i
    }

    private val NUMBER_RE = Regex("""0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*(\.\d+)?([eE][+-]?\d+)?[fFlLdDuU]?""")

    /** True when a NUMBER_RE match can start here (regex begins with a digit / 0x / 0b). */
    private fun isDigitStart(line: String, i: Int): Boolean = line[i].isDigit()

    /** Capitalized identifiers read as types in the statically-typed family. */
    private fun isTypeWord(word: String, lang: CodeLang): Boolean = when (lang) {
        CodeLang.KOTLIN, CodeLang.JAVA, CodeLang.TS, CodeLang.GO, CodeLang.RUST, CodeLang.SWIFT, CodeLang.C ->
            word.length > 1 && word[0].isUpperCase() && word.all { it.isLetterOrDigit() || it == '_' }
        else -> false
    }

    // ---- markdown -----------------------------------------------------------------------

    private fun highlightMarkdown(line: String): List<HighlightToken> {
        val tokens = mutableListOf<HighlightToken>()
        // ATX header.
        val headMatch = Regex("^#{1,6} ").find(line)
        if (headMatch != null) {
            tokens += HighlightToken(0, headMatch.value.length + 1, HighlightKind.KEYWORD)
            tokens += HighlightToken(headMatch.value.length + 1, line.length - headMatch.value.length - 1, HighlightKind.PLAIN)
            return tokens
        }
        // Inline code spans + bold.
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '`') {
                val end = line.indexOf('`', i + 1)
                if (end > i) {
                    tokens += HighlightToken(i, end + 1 - i, HighlightKind.STRING)
                    i = end + 1
                    continue
                }
            }
            if (c == '*' && i + 1 < line.length && line[i + 1] == '*') {
                val end = line.indexOf("**", i + 2)
                if (end > i) {
                    tokens += HighlightToken(i, end + 2 - i, HighlightKind.KEYWORD)
                    i = end + 2
                    continue
                }
            }
            i++
        }
        return tokens
    }

    // ---- keyword tables ------------------------------------------------------------------

    private val KOTLIN_KEYWORDS = setOf(
        "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return", "class",
        "object", "interface", "data", "sealed", "enum", "import", "package", "private", "public",
        "protected", "internal", "override", "open", "abstract", "final", "companion", "init",
        "constructor", "this", "super", "null", "true", "false", "is", "in", "as", "try", "catch",
        "finally", "throw", "break", "continue", "suspend", "inline", "lateinit", "by",
        "typealias", "operator", "get", "set", "where", "expect", "actual", "out", "reified",
        "noinline", "crossinline", "vararg", "const", "external", "annotation", "infix", "tailrec"
    )

    private val JAVA_KEYWORDS = setOf(
        "public", "private", "protected", "class", "interface", "enum", "extends", "implements",
        "new", "return", "if", "else", "for", "while", "do", "break", "continue", "switch", "case",
        "default", "try", "catch", "finally", "throw", "throws", "static", "final", "abstract",
        "void", "int", "long", "double", "float", "boolean", "byte", "short", "char", "String",
        "List", "Map", "Set", "null", "true", "false", "this", "super", "import", "package",
        "synchronized", "instanceof", "volatile", "transient", "native", "record", "var"
    )

    private val TS_KEYWORDS = setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch",
        "case", "break", "continue", "new", "class", "interface", "type", "enum", "extends",
        "implements", "export", "import", "from", "default", "async", "await", "try", "catch",
        "finally", "throw", "null", "undefined", "true", "false", "this", "super", "of", "in",
        "instanceof", "typeof", "void", "readonly", "public", "private", "protected", "static",
        "get", "set", "as", "keyof", "never", "unknown", "any", "yield", "delete"
    )

    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as",
        "try", "except", "finally", "with", "lambda", "yield", "raise", "pass", "break", "continue",
        "global", "nonlocal", "del", "assert", "True", "False", "None", "and", "or", "not", "in",
        "is", "async", "await", "match", "case", "self"
    )

    private val GO_KEYWORDS = setOf(
        "package", "import", "func", "var", "const", "type", "struct", "interface", "map", "chan",
        "go", "defer", "return", "if", "else", "for", "range", "break", "continue", "switch",
        "case", "default", "select", "fallthrough", "goto", "nil", "true", "false"
    )

    private val RUST_KEYWORDS = setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "mod", "use",
        "pub", "crate", "super", "self", "return", "if", "else", "match", "for", "while", "loop",
        "break", "continue", "move", "ref", "type", "where", "as", "in", "async", "await", "dyn",
        "unsafe", "extern", "true", "false", "Some", "None", "Ok", "Err"
    )

    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "exit", "local", "export", "source", "echo", "cd", "set", "unset",
        "readonly", "shift", "in"
    )

    private val SWIFT_KEYWORDS = setOf(
        "func", "var", "let", "class", "struct", "enum", "protocol", "extension", "import",
        "return", "if", "else", "guard", "for", "while", "repeat", "switch", "case", "default",
        "break", "continue", "throw", "throws", "try", "catch", "defer", "init", "deinit", "self",
        "super", "nil", "true", "false", "public", "private", "internal", "fileprivate", "open",
        "static", "final", "override", "inout", "async", "await", "actor", "where", "some", "any"
    )

    private val C_KEYWORDS = setOf(
        "int", "void", "char", "long", "short", "unsigned", "signed", "float", "double", "bool",
        "struct", "union", "enum", "class", "public", "private", "protected", "static", "const",
        "volatile", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "new", "delete", "nullptr", "NULL", "true", "false", "typedef", "namespace",
        "using", "template", "typename", "virtual", "override", "final", "try", "catch", "throw",
        "this", "#include", "#define", "#ifdef", "#ifndef", "#endif"
    )

    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "index", "view", "alter", "drop", "join", "left", "right", "inner", "outer",
        "full", "on", "group", "by", "order", "having", "limit", "offset", "distinct", "as", "and",
        "or", "not", "in", "is", "null", "like", "between", "union", "all", "case", "when", "then",
        "else", "end", "primary", "key", "foreign", "references", "default", "unique", "constraint",
        "begin", "commit", "rollback", "transaction"
    )

    private val JSON_KEYWORDS = setOf("true", "false", "null")
    private val YAML_KEYWORDS = setOf("true", "false", "null", "yes", "no", "on", "off")

    private fun keywords(lang: CodeLang): Set<String> = when (lang) {
        CodeLang.KOTLIN -> KOTLIN_KEYWORDS
        CodeLang.JAVA -> JAVA_KEYWORDS
        CodeLang.TS -> TS_KEYWORDS
        CodeLang.PYTHON -> PYTHON_KEYWORDS
        CodeLang.GO -> GO_KEYWORDS
        CodeLang.RUST -> RUST_KEYWORDS
        CodeLang.SHELL -> SHELL_KEYWORDS
        CodeLang.SWIFT -> SWIFT_KEYWORDS
        CodeLang.C -> C_KEYWORDS
        CodeLang.SQL -> SQL_KEYWORDS
        CodeLang.JSON -> JSON_KEYWORDS
        CodeLang.YAML -> YAML_KEYWORDS
        CodeLang.MARKDOWN -> emptySet()
    }
}
