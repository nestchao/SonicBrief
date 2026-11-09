package dev.sonicbrief.app.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesHeadingsWithLevels() {
        val blocks = parseMarkdown("## Overview\n### Details\n# Title")
        assertEquals(
            listOf(
                MarkdownBlock.Heading(2, "Overview"),
                MarkdownBlock.Heading(3, "Details"),
                MarkdownBlock.Heading(1, "Title"),
            ),
            blocks,
        )
    }

    @Test
    fun joinsWrappedLinesIntoOneParagraph() {
        val blocks = parseMarkdown("First line\nsecond line\n\nNext paragraph")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("First line second line"),
                MarkdownBlock.Paragraph("Next paragraph"),
            ),
            blocks,
        )
    }

    @Test
    fun parsesBulletsAndNesting() {
        val blocks = parseMarkdown("- Top item\n  - Nested item\n* Star bullet")
        assertEquals(
            listOf(
                MarkdownBlock.Bullet("Top item", 0),
                MarkdownBlock.Bullet("Nested item", 1),
                MarkdownBlock.Bullet("Star bullet", 0),
            ),
            blocks,
        )
    }

    @Test
    fun parsesOrderedListsKeepingMarker() {
        val blocks = parseMarkdown("1. First\n2) Second")
        assertEquals(
            listOf(
                MarkdownBlock.OrderedItem("1.", "First", 0),
                MarkdownBlock.OrderedItem("2.", "Second", 0),
            ),
            blocks,
        )
    }

    @Test
    fun keepsBoldMarkersOutOfBlockText() {
        val blocks = parseMarkdown("**舆论争议**：论点")
        assertEquals(listOf(MarkdownBlock.Paragraph("**舆论争议**：论点")), blocks)
        val spans = parseInline("**舆论争议**：论点")
        assertEquals("舆论争议", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals("：论点", spans[1].text)
    }

    @Test
    fun parsesInlineEmphasisCodeAndLinks() {
        val spans = parseInline("a **b** c *d* `e` [f](https://x.test)")
        assertEquals(listOf("a ", "b", " c ", "d", " ", "e", " ", "f"), spans.map { it.text })
        assertTrue(spans[1].bold)
        assertTrue(spans[3].italic)
        assertTrue(spans[5].code)
        assertEquals("https://x.test", spans[7].link)
    }

    @Test
    fun supportsNestedEmphasis() {
        val spans = parseInline("**bold *and italic***")
        assertEquals("bold ", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals("and italic", spans[1].text)
        assertTrue(spans[1].bold)
        assertTrue(spans[1].italic)
    }

    @Test
    fun recognisesTimestampCitations() {
        val spans = parseInline("观点一 [13.6s, 19.3s] 观点二 [148.4s, 154.9s]")
        val citations = spans.filter { it.citation }
        assertEquals(listOf("[13.6s,\u00A019.3s]", "[148.4s,\u00A0154.9s]"), citations.map { it.text })
    }

    @Test
    fun citationDoesNotBreakAcrossLines() {
        val citation = parseInline("[13.6s, 19.3s]").single()
        assertTrue(citation.citation)
        assertTrue(!citation.text.contains(' '))
    }

    @Test
    fun citationIsNotMistakenForLink() {
        val spans = parseInline("[23.2s, 28.8s]")
        assertEquals(1, spans.size)
        assertTrue(spans[0].citation)
        assertEquals(null, spans[0].link)
    }

    @Test
    fun parsesFencedCodeAndRules() {
        val blocks = parseMarkdown("```kotlin\nval x = 1\n```\n\n---")
        assertEquals(
            listOf(
                MarkdownBlock.CodeBlock("val x = 1", "kotlin"),
                MarkdownBlock.Rule,
            ),
            blocks,
        )
    }

    @Test
    fun parsesQuotes() {
        val blocks = parseMarkdown("> quoted line")
        assertEquals(listOf(MarkdownBlock.Quote("quoted line")), blocks)
    }

    @Test
    fun parsesPipeTables() {
        val blocks = parseMarkdown("| A | B |\n| --- | --- |\n| 1 | 2 |")
        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("A", "B"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun plainTextSurvivesUnchanged() {
        val blocks = parseMarkdown("Just a sentence about nothing.")
        assertEquals(listOf(MarkdownBlock.Paragraph("Just a sentence about nothing.")), blocks)
    }
}
