package com.libertasprimordium.othernote.util

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class BlockQuote(val text: String) : MarkdownBlock()
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
    data class CodeBlock(val code: String) : MarkdownBlock()
}

sealed class MarkdownSpan {
    data class Text(val text: String) : MarkdownSpan()
    data class Bold(val text: String) : MarkdownSpan()
    data class Italic(val text: String) : MarkdownSpan()
    data class BoldItalic(val text: String) : MarkdownSpan()
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
    val listItems = mutableListOf<String>()
    var listOrdered: Boolean? = null
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

    fun flushList() {
        if (listItems.isNotEmpty()) {
            blocks += MarkdownBlock.ListBlock(ordered = listOrdered == true, items = listItems.toList())
            listItems.clear()
            listOrdered = null
        }
    }

    fun flushTextBlocks() {
        flushParagraph()
        flushQuote()
        flushList()
    }

    markdown.lines().forEach { line ->
        val trimmed = line.trim()
        val contentLine = line.trimStart()
        val unorderedListMatch = Regex("""^[-*]\s+(.+)$""").matchEntire(contentLine)
        val orderedListMatch = Regex("""^\d+\.\s+(.+)$""").matchEntire(contentLine)
        when {
            trimmed.startsWith("```") && !inCode -> {
                flushTextBlocks()
                inCode = true
            }
            trimmed.startsWith("```") && inCode -> {
                blocks += MarkdownBlock.CodeBlock(code.toString().trimEnd())
                code.clear()
                inCode = false
            }
            inCode -> code.appendLine(line)
            line.isBlank() -> flushTextBlocks()
            trimmed.matches(Regex("""(-{3,}|\*{3,})""")) -> {
                flushTextBlocks()
                blocks += MarkdownBlock.HorizontalRule
            }
            contentLine.startsWith("#") -> {
                val level = contentLine.takeWhile { it == '#' }.length.coerceIn(1, 6)
                if (contentLine.getOrNull(level) == ' ') {
                    flushTextBlocks()
                    blocks += MarkdownBlock.Heading(level, contentLine.drop(level + 1).trim())
                } else {
                    flushList()
                    flushQuote()
                    paragraph += line
                }
            }
            contentLine.startsWith(">") -> {
                flushParagraph()
                flushList()
                quote += contentLine.drop(1).removePrefix(" ").trimEnd()
            }
            unorderedListMatch != null || orderedListMatch != null -> {
                flushParagraph()
                flushQuote()
                val ordered = orderedListMatch != null
                if (listOrdered != null && listOrdered != ordered) flushList()
                listOrdered = ordered
                listItems += (orderedListMatch ?: unorderedListMatch)?.groupValues?.get(1).orEmpty().trimEnd()
            }
            else -> {
                flushQuote()
                flushList()
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

    fun appendSpan(span: MarkdownSpan) {
        val last = spans.lastOrNull()
        if (last is MarkdownSpan.Text && span is MarkdownSpan.Text) {
            spans[spans.lastIndex] = MarkdownSpan.Text(last.text + span.text)
        } else {
            spans += span
        }
    }

    fun appendText(text: String) {
        if (text.isNotEmpty()) linkifiedTextSpans(text).forEach(::appendSpan)
    }

    while (index < markdown.length) {
        parseEscapedMarkdownCharacter(markdown, index)?.let { parsed ->
            appendSpan(parsed.span)
            index = parsed.nextIndex
            continue
        }
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
        parseBareUrlSpan(markdown, index)?.let { parsed ->
            appendSpan(parsed.span)
            if (parsed.trailingText.isNotEmpty()) appendSpan(MarkdownSpan.Text(parsed.trailingText))
            index = parsed.nextIndex
            continue
        }
        val marker = markdownMarkerAt(markdown, index)
        if (marker == null) {
            val next = listOf(
                markdown.indexOf("\\", index),
                markdown.indexOf("![", index),
                markdown.indexOf("[", index),
                markdown.indexOf("https://", index),
                markdown.indexOf("http://", index),
                markdown.indexOf("`", index),
                markdown.indexOf("___", index),
                markdown.indexOf("***", index),
                markdown.indexOf("**", index),
                markdown.indexOf("__", index),
                markdown.indexOf("~~", index),
                markdown.indexOf("~", index),
                markdown.indexOf("*", index),
                markdown.indexOf("_", index),
            ).filter { it >= 0 }.minOrNull() ?: markdown.length
            appendText(markdown.substring(index, next))
            index = next
            continue
        }

        if (!isValidOpeningMarkdownMarker(markdown, index, marker)) {
            appendText(marker)
            index += marker.length
            continue
        }

        val close = findClosingMarkdownMarker(markdown, index + marker.length, marker)
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
                "***", "___" -> MarkdownSpan.BoldItalic(content)
                "**", "__" -> MarkdownSpan.Bold(content)
                "*", "_" -> MarkdownSpan.Italic(content)
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

private data class ParsedBareUrlSpan(
    val span: MarkdownSpan,
    val trailingText: String,
    val nextIndex: Int,
)

private fun parseEscapedMarkdownCharacter(markdown: String, index: Int): ParsedMarkdownSpan? {
    if (!markdown.startsWith("\\", index)) return null
    val escaped = markdown.getOrNull(index + 1) ?: return ParsedMarkdownSpan(MarkdownSpan.Text("\\"), index + 1)
    return if (escaped in MarkdownEscapableCharacters) {
        val runLength = if (escaped in setOf('*', '_', '~')) {
            var count = 1
            while (count < 3 && markdown.getOrNull(index + 1 + count) == escaped) count += 1
            count
        } else {
            1
        }
        ParsedMarkdownSpan(MarkdownSpan.Text(escaped.toString().repeat(runLength)), index + 1 + runLength)
    } else {
        ParsedMarkdownSpan(MarkdownSpan.Text("\\"), index + 1)
    }
}

private val MarkdownEscapableCharacters = setOf('\\', '*', '_', '[', ']', '(', ')', '`', '~', '#', '>', '-', '!')

private fun markdownMarkerAt(markdown: String, index: Int): String? =
    when {
        markdown.startsWith("`", index) -> "`"
        markdown.startsWith("___", index) -> "___"
        markdown.startsWith("***", index) -> "***"
        markdown.startsWith("**", index) -> "**"
        markdown.startsWith("__", index) -> "__"
        markdown.startsWith("~~", index) -> "~~"
        markdown.startsWith("~", index) -> "~"
        markdown.startsWith("*", index) -> "*"
        markdown.startsWith("_", index) -> "_"
        else -> null
    }

private fun isValidOpeningMarkdownMarker(markdown: String, index: Int, marker: String): Boolean {
    val after = markdown.getOrNull(index + marker.length) ?: return false
    if (after.isWhitespace()) return false
    if (!marker.startsWith("_")) return true
    val before = markdown.getOrNull(index - 1)
    return !before.isMarkdownWordCharacter()
}

private fun findClosingMarkdownMarker(markdown: String, startIndex: Int, marker: String): Int {
    var searchIndex = startIndex
    while (searchIndex < markdown.length) {
        val close = markdown.indexOf(marker, searchIndex)
        if (close < 0) return -1
        if (isValidClosingMarkdownMarker(markdown, close, marker)) return close
        searchIndex = close + marker.length
    }
    return -1
}

private fun isValidClosingMarkdownMarker(markdown: String, close: Int, marker: String): Boolean {
    val before = markdown.getOrNull(close - 1) ?: return false
    if (before.isWhitespace()) return false
    if (!marker.startsWith("_")) return true
    val after = markdown.getOrNull(close + marker.length)
    return !after.isMarkdownWordCharacter()
}

private fun Char?.isMarkdownWordCharacter(): Boolean = this != null && (isLetterOrDigit() || this == '_')

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

private fun parseBareUrlSpan(markdown: String, index: Int): ParsedBareUrlSpan? {
    val match = Regex("""https?://[^\s<>()"]+""").find(markdown, index)
        ?.takeIf { it.range.first == index }
        ?: return null
    val raw = match.value
    val url = raw.trimEnd('.', ',', '!', '?', ';', ':', ')', ']')
    val trailing = raw.drop(url.length)
    val span = if (isSupportedRemoteImageUrl(url)) {
        MarkdownSpan.Image("", url)
    } else if (isSafeHttpUrl(url)) {
        MarkdownSpan.Link(url, url)
    } else {
        MarkdownSpan.Text(url)
    }
    return ParsedBareUrlSpan(span, trailing, match.range.last + 1)
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
        .replace(Regex("""__([^_]+)__"""), "$1")
        .replace(Regex("""\*\*\*([^*]+)\*\*\*"""), "$1")
        .replace(Regex("""\*([^*]+)\*"""), "$1")
        .replace(Regex("""_([^_]+)_"""), "$1")
        .replace(Regex("""~~([^~]+)~~"""), "$1")
        .replace(Regex("""~([^~]+)~"""), "$1")
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace("""\*""", "*")
        .replace("""\[""", "[")
        .replace("""\]""", "]")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun String.compactPreviewText(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."
