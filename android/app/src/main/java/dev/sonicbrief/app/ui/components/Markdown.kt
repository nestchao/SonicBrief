package dev.sonicbrief.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.sonicbrief.app.ui.result.MarkdownBlock
import dev.sonicbrief.app.ui.result.MdSpan
import dev.sonicbrief.app.ui.result.parseInline
import dev.sonicbrief.app.ui.result.parseMarkdown

/**
 * Renders a summarizer's Markdown with a real typographic hierarchy instead of dumping
 * raw `##` and `**` markers into a paragraph.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val spacing = when {
                index == 0 -> 0.dp
                block is MarkdownBlock.Heading -> 14.dp
                blocks[index - 1] is MarkdownBlock.Heading -> 8.dp
                block is MarkdownBlock.Rule -> 12.dp
                else -> 10.dp
            }
            if (spacing > 0.dp) Spacer(Modifier.height(spacing))
            when (block) {
                is MarkdownBlock.Heading -> HeadingBlock(block)
                is MarkdownBlock.Paragraph -> Text(
                    text = inline(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                )
                is MarkdownBlock.Bullet -> BulletBlock(text = block.text, level = block.level, marker = "•")
                is MarkdownBlock.OrderedItem -> BulletBlock(text = block.text, level = block.level, marker = block.marker)
                is MarkdownBlock.Quote -> QuoteBlock(block.text)
                is MarkdownBlock.CodeBlock -> CodeBlock(block.text)
                MarkdownBlock.Rule -> SoftDivider()
                is MarkdownBlock.Table -> TableBlock(block)
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading) {
    val base = MaterialTheme.typography
    val style: TextStyle = when (block.level) {
        1 -> base.headlineSmall.copy(fontWeight = FontWeight.Bold)
        2 -> base.titleLarge.copy(fontWeight = FontWeight.Bold)
        3 -> base.titleMedium.copy(fontWeight = FontWeight.Bold)
        else -> base.titleSmall.copy(fontWeight = FontWeight.Bold)
    }
    Text(
        text = inline(block.text),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun BulletBlock(text: String, level: Int, marker: String) {
    val style = MaterialTheme.typography.bodyLarge
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (level * 14).dp),
    ) {
        Text(
            text = marker,
            style = style,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(if (marker.length > 2) 28.dp else 16.dp),
        )
        Text(
            text = inline(text),
            style = style,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = inline(text),
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CodeBlock(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TableBlock(block: MarkdownBlock.Table) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            block.headers.forEach { cell ->
                Text(
                    text = inline(cell),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
            }
        }
        SoftDivider()
        block.rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(
                        text = inline(cell),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            }
        }
    }
}

/** Builds the annotated string for one block of inline content. */
@Composable
private fun inline(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurface
    val citationBackground = MaterialTheme.colorScheme.surfaceVariant
    val citationColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeBackground, citationBackground, linkColor) {
        buildAnnotatedString {
            parseInline(text).forEach { span -> withStyle(span.styleCode(codeBackground, codeColor, citationBackground, citationColor, linkColor)) { append(span.text) } }
        }
    }
}

private fun MdSpan.styleCode(
    codeBackground: Color,
    codeColor: Color,
    citationBackground: Color,
    citationColor: Color,
    linkColor: Color,
): SpanStyle = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    fontFamily = if (code) FontFamily.Monospace else null,
    background = when {
        code -> codeBackground
        citation -> citationBackground
        else -> Color.Unspecified
    },
    color = when {
        code -> codeColor
        citation -> citationColor
        link != null -> linkColor
        else -> Color.Unspecified
    },
    fontSize = if (citation) 0.85.em else androidx.compose.ui.unit.TextUnit.Unspecified,
    textDecoration = if (link != null) TextDecoration.Underline else null,
)

/** Selectable Markdown block, used where the user may want to copy part of the summary. */
@Composable
fun SelectableMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    SelectionContainer(modifier) {
        MarkdownText(markdown)
    }
}
