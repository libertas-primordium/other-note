package com.libertasprimordium.othernote.util

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class BlockQuote(val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String) : MarkdownBlock()
}

sealed class MarkdownSpan {
    data class Text(val text: String) : MarkdownSpan()
    data class Bold(val text: String) : MarkdownSpan()
    data class Italic(val text: String) : MarkdownSpan()
    data class Strike(val text: String) : MarkdownSpan()
    data class Code(val text: String) : MarkdownSpan()
}

data class NoteCardPreview(
    val title: String,
    val snippet: String,
)

fun markdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val quote = mutableListOf<String>()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    fun flushQuote() {
        if (quote.isNotEmpty()) {
            blocks += MarkdownBlock.BlockQuote(quote.joinToString("\n").trim())
            quote.clear()
        }
    }

    fun flushTextBlocks() {
        flushParagraph()
        flushQuote()
    }

    markdown.lines().forEach { line ->
        when {
            line.trim().startsWith("```") && !inCode -> {
                flushTextBlocks()
                inCode = true
            }
            line.trim().startsWith("```") && inCode -> {
                blocks += MarkdownBlock.CodeBlock(code.toString().trimEnd())
                code.clear()
                inCode = false
            }
            inCode -> code.appendLine(line)
            line.isBlank() -> flushTextBlocks()
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                if (line.getOrNull(level) == ' ') {
                    flushTextBlocks()
                    blocks += MarkdownBlock.Heading(level, line.drop(level + 1).trim())
                } else {
                    flushQuote()
                    paragraph += line
                }
            }
            line.startsWith(">") -> {
                flushParagraph()
                quote += line.drop(1).removePrefix(" ").trimEnd()
            }
            else -> {
                flushQuote()
                paragraph += line
            }
        }
    }
    if (inCode) blocks += MarkdownBlock.CodeBlock(code.toString().trimEnd())
    flushTextBlocks()
    return blocks
}

fun markdownSpans(markdown: String): List<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    var index = 0

    fun appendText(text: String) {
        if (text.isNotEmpty()) spans += MarkdownSpan.Text(text)
    }

    while (index < markdown.length) {
        val marker = when {
            markdown.startsWith("`", index) -> "`"
            markdown.startsWith("**", index) -> "**"
            markdown.startsWith("~~", index) -> "~~"
            markdown.startsWith("~", index) -> "~"
            markdown.startsWith("*", index) -> "*"
            else -> null
        }
        if (marker == null) {
            val next = listOf(
                markdown.indexOf("`", index),
                markdown.indexOf("**", index),
                markdown.indexOf("~~", index),
                markdown.indexOf("~", index),
                markdown.indexOf("*", index),
            ).filter { it >= 0 }.minOrNull() ?: markdown.length
            appendText(markdown.substring(index, next))
            index = next
            continue
        }

        val close = markdown.indexOf(marker, index + marker.length)
        if (close < 0) {
            appendText(marker)
            index += marker.length
            continue
        }

        val content = markdown.substring(index + marker.length, close)
        if (content.isEmpty()) {
            appendText(marker + marker)
        } else {
            spans += when (marker) {
                "`" -> MarkdownSpan.Code(content)
                "**" -> MarkdownSpan.Bold(content)
                "*" -> MarkdownSpan.Italic(content)
                "~", "~~" -> MarkdownSpan.Strike(content)
                else -> MarkdownSpan.Text(marker + content + marker)
            }
        }
        index = close + marker.length
    }
    return spans
}

fun truncateMarkdown(markdown: String, maxChars: Int = 160): String {
    val plain = markdown
        .replace(Regex("```[\\s\\S]*?```"), "[code]")
        .replace(Regex("""[#*_`~>\[\]()]"""), "")
        .lines()
        .joinToString(" ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (plain.length <= maxChars) plain else plain.take(maxChars).trimEnd() + "..."
}

fun noteCardPreview(
    markdown: String,
    maxTitleChars: Int = 80,
    maxSnippetChars: Int = 140,
): NoteCardPreview {
    val lines = markdown.lines()
    val firstContentIndex = lines.indexOfFirst { it.isNotBlank() }
    if (firstContentIndex < 0) return NoteCardPreview(title = "Untitled note", snippet = "")

    val firstLine = lines[firstContentIndex].trim()
    if (firstLine.startsWith("```")) {
        val codeLines = lines.drop(firstContentIndex + 1)
            .takeWhile { !it.trim().startsWith("```") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return NoteCardPreview(
            title = "Code block",
            snippet = codeLines.joinToString(" ").compactPreviewText(maxSnippetChars),
        )
    }

    val title = firstLine.toNoteCardPreviewText().ifBlank { "Untitled note" }.compactPreviewText(maxTitleChars)
    val snippet = lines.drop(firstContentIndex + 1)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("```") }
        .map { it.toNoteCardPreviewText() }
        .filter { it.isNotBlank() }
        .take(3)
        .joinToString(" ")
        .compactPreviewText(maxSnippetChars)
    return NoteCardPreview(title = title, snippet = snippet)
}

private fun String.toNoteCardPreviewText(): String =
    trim()
        .removePrefix("> ")
        .removePrefix(">")
        .replace(Regex("""^#{1,6}\s+"""), "")
        .replace(Regex("""^[-*]\s+"""), "")
        .replace(Regex("""^\d+\.\s+"""), "")
        .replace(Regex("```.*$"), "")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        .replace(Regex("""\*([^*]+)\*"""), "$1")
        .replace(Regex("""~~([^~]+)~~"""), "$1")
        .replace(Regex("""~([^~]+)~"""), "$1")
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun String.compactPreviewText(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."
