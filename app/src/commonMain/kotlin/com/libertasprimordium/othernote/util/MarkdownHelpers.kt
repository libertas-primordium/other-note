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
    data class Link(val label: String, val url: String) : MarkdownSpan()
    data class Image(val alt: String, val url: String) : MarkdownSpan()
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
        if (text.isNotEmpty()) spans += linkifiedTextSpans(text)
    }

    while (index < markdown.length) {
        parseMarkdownImageSpan(markdown, index)?.let { parsed ->
            spans += parsed.span
            index = parsed.nextIndex
            continue
        }
        parseMarkdownLinkSpan(markdown, index)?.let { parsed ->
            spans += parsed.span
            index = parsed.nextIndex
            continue
        }
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
                markdown.indexOf("![", index),
                markdown.indexOf("[", index),
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

private data class ParsedMarkdownSpan(
    val span: MarkdownSpan,
    val nextIndex: Int,
)

private fun parseMarkdownImageSpan(markdown: String, index: Int): ParsedMarkdownSpan? {
    if (!markdown.startsWith("![", index)) return null
    val labelEnd = markdown.indexOf("](", index + 2)
    if (labelEnd < 0) return null
    val urlEnd = markdown.indexOf(")", labelEnd + 2)
    if (urlEnd < 0) return null
    val raw = markdown.substring(index, urlEnd + 1)
    val alt = markdown.substring(index + 2, labelEnd)
    val url = markdown.substring(labelEnd + 2, urlEnd).trim()
    val span = if (isSupportedRemoteImageUrl(url)) {
        MarkdownSpan.Image(alt, url)
    } else {
        MarkdownSpan.Text(raw)
    }
    return ParsedMarkdownSpan(span, urlEnd + 1)
}

private fun parseMarkdownLinkSpan(markdown: String, index: Int): ParsedMarkdownSpan? {
    if (!markdown.startsWith("[", index)) return null
    val labelEnd = markdown.indexOf("](", index + 1)
    if (labelEnd < 0) return null
    val urlEnd = markdown.indexOf(")", labelEnd + 2)
    if (urlEnd < 0) return null
    val raw = markdown.substring(index, urlEnd + 1)
    val label = markdown.substring(index + 1, labelEnd)
    val url = markdown.substring(labelEnd + 2, urlEnd).trim()
    val span = if (isSafeHttpUrl(url)) {
        MarkdownSpan.Link(label.ifBlank { url }, url)
    } else {
        MarkdownSpan.Text(raw)
    }
    return ParsedMarkdownSpan(span, urlEnd + 1)
}

private fun linkifiedTextSpans(text: String): List<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    var lastIndex = 0
    Regex("""https?://[^\s<>()"]+""").findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            spans += MarkdownSpan.Text(text.substring(lastIndex, match.range.first))
        }
        val raw = match.value
        val url = raw.trimEnd('.', ',', '!', '?', ';', ':', ')', ']')
        val trailing = raw.drop(url.length)
        if (isSupportedRemoteImageUrl(url)) {
            spans += MarkdownSpan.Image("", url)
        } else if (isSafeHttpUrl(url)) {
            spans += MarkdownSpan.Link(url, url)
        } else {
            spans += MarkdownSpan.Text(url)
        }
        if (trailing.isNotEmpty()) spans += MarkdownSpan.Text(trailing)
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        spans += MarkdownSpan.Text(text.substring(lastIndex))
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
