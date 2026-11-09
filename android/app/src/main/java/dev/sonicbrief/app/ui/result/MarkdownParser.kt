package dev.sonicbrief.app.ui.result

/** Block-level Markdown produced by the summarizer, ready for typographic rendering. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String, val level: Int) : MarkdownBlock
    data class OrderedItem(val marker: String, val text: String, val level: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class CodeBlock(val text: String, val language: String?) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/** Inline run with the emphasis that applies to it. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val citation: Boolean = false,
)

private val HEADING = Regex("""^(#{1,6})\s+(.+)$""")
private val BULLET = Regex("""^(\s*)[-*•·]\s+(.+)$""")
private val ORDERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.+)$""")
private val RULE = Regex("""^([-*_]\s*){3,}$""")
private val TABLE_DIVIDER = Regex("""^\|?[\s:|-]*-[\s:|-]*\|?$""")
private val LINK = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
private val CITATION = Regex("""\[\s*\d+(?:\.\d+)?\s*s(?:\s*[,，、–—-]\s*\d+(?:\.\d+)?\s*s)*\s*]""")

/**
 * Parses the summarizer's Markdown into blocks. Deliberately small: it covers headings,
 * paragraphs, lists, quotes, fenced code, tables and rules, which is what summaries use.
 */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val raw = lines[index]
        val trimmed = raw.trim()

        if (trimmed.startsWith("```")) {
            flushParagraph()
            val language = trimmed.removePrefix("```").trim().ifBlank { null }
            val body = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                body += lines[index]
                index++
            }
            if (index < lines.size) index++
            blocks += MarkdownBlock.CodeBlock(body.joinToString("\n"), language)
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            index++
            continue
        }

        if (RULE.matches(trimmed) && !trimmed.startsWith("- ")) {
            flushParagraph()
            blocks += MarkdownBlock.Rule
            index++
            continue
        }

        HEADING.matchEntire(trimmed)?.let { match ->
            flushParagraph()
            blocks += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
            index++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
            index++
            continue
        }

        if (trimmed.startsWith("|") && index + 1 < lines.size && TABLE_DIVIDER.matches(lines[index + 1].trim())) {
            flushParagraph()
            val headers = splitTableRow(trimmed)
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && lines[index].trim().startsWith("|")) {
                rows += splitTableRow(lines[index].trim())
                index++
            }
            blocks += MarkdownBlock.Table(headers, rows)
            continue
        }

        BULLET.matchEntire(raw)?.let { match ->
            flushParagraph()
            blocks += MarkdownBlock.Bullet(match.groupValues[2].trim(), indentLevel(match.groupValues[1]))
            index++
            continue
        }

        ORDERED.matchEntire(raw)?.let { match ->
            flushParagraph()
            blocks += MarkdownBlock.OrderedItem(
                marker = match.groupValues[2] + ".",
                text = match.groupValues[3].trim(),
                level = indentLevel(match.groupValues[1]),
            )
            index++
            continue
        }

        paragraph += trimmed
        index++
    }
    flushParagraph()
    return blocks
}

private fun indentLevel(indent: String): Int = (indent.replace("\t", "  ").length / 2).coerceIn(0, 3)

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/**
 * Splits a line into styled runs. Emphasis is applied recursively so `**bold with *italic***`
 * keeps both attributes.
 */
fun parseInline(text: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            out += MdSpan(plain.toString())
            plain.setLength(0)
        }
    }

    while (index < text.length) {
        val link = LINK.matchAt(text, index)
        if (link != null) {
            flushPlain()
            out += MdSpan(link.groupValues[1], link = link.groupValues[2])
            index += link.value.length
            continue
        }

        val citation = CITATION.matchAt(text, index)
        if (citation != null) {
            flushPlain()
            // Non-breaking spaces keep a "13.6s, 19.3s" citation from splitting across lines.
            out += MdSpan(citation.value.replace(' ', '\u00A0'), citation = true)
            index += citation.value.length
            continue
        }

        if (text[index] == '`') {
            val end = text.indexOf('`', index + 1)
            if (end > index + 1) {
                flushPlain()
                out += MdSpan(text.substring(index + 1, end), code = true)
                index = end + 1
                continue
            }
        }

        if (text.startsWith("***", index)) {
            val end = text.indexOf("***", index + 3)
            if (end > index + 3) {
                flushPlain()
                out += parseInline(text.substring(index + 3, end)).map { it.copy(bold = true, italic = true) }
                index = end + 3
                continue
            }
        }

        if (text.startsWith("**", index) || text.startsWith("__", index)) {
            val marker = text.substring(index, index + 2)
            val end = findClosingMarker(text, index + 2, marker)
            if (end > index + 2) {
                flushPlain()
                out += parseInline(text.substring(index + 2, end)).map { it.copy(bold = true) }
                index = end + 2
                continue
            }
        }

        if (text[index] == '*' || text[index] == '_') {
            val marker = text[index].toString()
            val end = text.indexOf(marker, index + 1)
            if (end > index + 1) {
                flushPlain()
                out += parseInline(text.substring(index + 1, end)).map { it.copy(italic = true) }
                index = end + 1
                continue
            }
        }

        plain.append(text[index])
        index++
    }
    flushPlain()
    return out
}

/**
 * Finds the closing bold marker, skipping candidates that would leave an unbalanced `*` inside.
 * That keeps `**bold *and italic***` rendering as bold with an italic run rather than a stray
 * asterisk at the end.
 */
private fun findClosingMarker(text: String, from: Int, marker: String): Int {
    var search = from
    while (true) {
        val end = text.indexOf(marker, search)
        if (end < 0) return -1
        val inner = text.substring(from, end)
        if (inner.count { it == '*' } % 2 == 0) return end
        search = end + 1
    }
}
