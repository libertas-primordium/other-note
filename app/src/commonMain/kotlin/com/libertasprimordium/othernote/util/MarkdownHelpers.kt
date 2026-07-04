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

private const val MaxMarkdownParseChars = 32_768
private const val MaxMarkdownSpans = 2_048
private const val MaxMarkdownLinkLabelChars = 2_048
private const val MaxUrlChars = 2_048
private const val MaxMarkdownLoopIterations = MaxMarkdownParseChars * 2

private val FencedCodeBlockRegex = Regex("```[\\s\\S]*?```")
private val MarkdownSyntaxCharsRegex = Regex("""[#*_`~>\[\]()]""")
private val WhitespaceRegex = Regex("""\s+""")
private val HeadingPrefixRegex = Regex("""^#{1,6}\s+""")
private val UnorderedListPrefixRegex = Regex("""^[-*]\s+""")
private val OrderedListPrefixRegex = Regex("""^\d+\.\s+""")
private val InlineCodeFencePrefixRegex = Regex("```.*$")
private val BoldAsteriskPreviewRegex = Regex("""\*\*([^*]+)\*\*""")
private val BoldUnderscorePreviewRegex = Regex("""__([^_]+)__""")
private val BoldItalicAsteriskPreviewRegex = Regex("""\*\*\*([^*]+)\*\*\*""")
private val ItalicAsteriskPreviewRegex = Regex("""\*([^*]+)\*""")
private val ItalicUnderscorePreviewRegex = Regex("""_([^_]+)_""")
private val DoubleStrikePreviewRegex = Regex("""~~([^~]+)~~""")
private val StrikePreviewRegex = Regex("""~([^~]+)~""")
private val InlineCodePreviewRegex = Regex("""`([^`]+)`""")

fun markdownBlocks(markdown: String): List<MarkdownBlock> {
    if (markdown.length > MaxMarkdownParseChars) {
        return listOf(MarkdownBlock.Paragraph(markdown))
    }
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
        val unorderedListItem = unorderedListItemText(contentLine)
        val orderedListItem = orderedListItemText(contentLine)
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
            isHorizontalRule(trimmed) -> {
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
            unorderedListItem != null || orderedListItem != null -> {
                flushParagraph()
                flushQuote()
                val ordered = orderedListItem != null
                if (listOrdered != null && listOrdered != ordered) flushList()
                listOrdered = ordered
                listItems += (orderedListItem ?: unorderedListItem).orEmpty().trimEnd()
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

private fun unorderedListItemText(line: String): String? {
    if (line.length < 3) return null
    val marker = line[0]
    return if ((marker == '-' || marker == '*') && line[1].isWhitespace()) {
        line.drop(2).trimStart()
    } else {
        null
    }
}

private fun orderedListItemText(line: String): String? {
    var index = 0
    while (index < line.length && line[index].isDigit()) index += 1
    if (index == 0 || line.getOrNull(index) != '.' || line.getOrNull(index + 1)?.isWhitespace() != true) {
        return null
    }
    return line.drop(index + 2).trimStart()
}

private fun isHorizontalRule(line: String): Boolean {
    if (line.length < 3) return false
    val marker = line[0]
    if (marker != '-' && marker != '*') return false
    return line.all { it == marker }
}

fun markdownSpans(markdown: String): List<MarkdownSpan> {
    if (markdown.length > MaxMarkdownParseChars) {
        return listOf(MarkdownSpan.Text(markdown))
    }
    val spans = mutableListOf<MarkdownSpan>()
    var index = 0
    var iterations = 0

    fun appendSpan(span: MarkdownSpan) {
        val last = spans.lastOrNull()
        if (last is MarkdownSpan.Text && span is MarkdownSpan.Text) {
            spans[spans.lastIndex] = MarkdownSpan.Text(last.text + span.text)
        } else if (spans.size < MaxMarkdownSpans) {
            spans += span
        } else {
            val fallbackText = span.visibleFallbackText()
            if (fallbackText.isEmpty()) return
            if (last is MarkdownSpan.Text) {
                spans[spans.lastIndex] = MarkdownSpan.Text(last.text + fallbackText)
            } else {
                spans += MarkdownSpan.Text(fallbackText)
            }
        }
    }

    fun appendPlainText(text: String) {
        if (text.isNotEmpty()) appendSpan(MarkdownSpan.Text(text))
    }

    fun appendText(text: String) {
        if (text.isNotEmpty()) linkifiedTextSpans(text).forEach(::appendSpan)
    }

    while (index < markdown.length) {
        iterations += 1
        if (iterations > MaxMarkdownLoopIterations || spans.size >= MaxMarkdownSpans) {
            appendPlainText(markdown.substring(index))
            break
        }
        parseEscapedMarkdownCharacter(markdown, index)?.let { parsed ->
            appendSpan(parsed.span)
            index = parsed.nextIndex
            continue
        }
        parseMarkdownImageSpan(markdown, index)?.let { parsed ->
            appendSpan(parsed.span)
            index = parsed.nextIndex
            continue
        }
        parseMarkdownLinkSpan(markdown, index)?.let { parsed ->
            appendSpan(parsed.span)
            index = parsed.nextIndex
            continue
        }
        if (isMalformedMarkdownImageStart(markdown, index) || isMalformedMarkdownLinkStart(markdown, index)) {
            appendPlainText(markdown.substring(index))
            break
        }
        parseBareUrlSpan(markdown, index)?.let { parsed ->
            appendSpan(parsed.span)
            if (parsed.trailingText.isNotEmpty()) appendSpan(MarkdownSpan.Text(parsed.trailingText))
            index = parsed.nextIndex
            continue
        }
        val marker = markdownMarkerAt(markdown, index)
        if (marker == null) {
            val next = nextInlineMarkdownSpecialIndex(markdown, index + 1)
            if (next <= index) {
                appendPlainText(markdown[index].toString())
                index += 1
            } else {
                appendText(markdown.substring(index, next))
                index = next
            }
            continue
        }

        if (!isValidOpeningMarkdownMarker(markdown, index, marker)) {
            appendPlainText(marker)
            index += marker.length
            continue
        }

        val close = findClosingMarkdownMarker(markdown, index + marker.length, marker)
        if (close < 0) {
            appendPlainText(markdown.substring(index))
            break
        }

        val content = markdown.substring(index + marker.length, close)
        if (content.isEmpty()) {
            appendText(marker + marker)
        } else {
            appendSpan(
                when (marker) {
                "`" -> MarkdownSpan.Code(content)
                "***", "___" -> MarkdownSpan.BoldItalic(content)
                "**", "__" -> MarkdownSpan.Bold(content)
                "*", "_" -> MarkdownSpan.Italic(content)
                "~", "~~" -> MarkdownSpan.Strike(content)
                else -> MarkdownSpan.Text(marker + content + marker)
                },
            )
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

private fun MarkdownSpan.visibleFallbackText(): String =
    when (this) {
        is MarkdownSpan.Text -> text
        is MarkdownSpan.Bold -> text
        is MarkdownSpan.Italic -> text
        is MarkdownSpan.BoldItalic -> text
        is MarkdownSpan.Strike -> text
        is MarkdownSpan.Code -> text
        is MarkdownSpan.Link -> label
        is MarkdownSpan.Image -> url
    }

private fun nextInlineMarkdownSpecialIndex(markdown: String, startIndex: Int): Int {
    var index = startIndex
    while (index < markdown.length) {
        when (markdown[index]) {
            '\\', '[', '`', '*', '_', '~' -> return index
            '!' -> if (markdown.getOrNull(index + 1) == '[') return index
            'h' -> if (markdown.startsWith("https://", index) || markdown.startsWith("http://", index)) return index
        }
        index += 1
    }
    return markdown.length
}

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
    val labelEnd = findSequenceWithin(markdown, "](", index + 2, index + 2 + MaxMarkdownLinkLabelChars) ?: return null
    val urlStart = labelEnd + 2
    val urlEnd = findCharWithin(markdown, ')', urlStart, urlStart + MaxUrlChars) ?: return null
    val raw = markdown.substring(index, urlEnd + 1)
    val alt = markdown.substring(index + 2, labelEnd)
    val url = markdown.substring(urlStart, urlEnd).trim()
    val span = if (isSupportedRemoteImageUrl(url)) {
        MarkdownSpan.Image(alt, url)
    } else {
        MarkdownSpan.Text(raw)
    }
    return ParsedMarkdownSpan(span, urlEnd + 1)
}

private fun isMalformedMarkdownImageStart(markdown: String, index: Int): Boolean =
    markdown.startsWith("![", index) &&
        findSequenceWithin(markdown, "](", index + 2, index + 2 + MaxMarkdownLinkLabelChars) != null

private fun parseMarkdownLinkSpan(markdown: String, index: Int): ParsedMarkdownSpan? {
    if (!markdown.startsWith("[", index)) return null
    val labelEnd = findSequenceWithin(markdown, "](", index + 1, index + 1 + MaxMarkdownLinkLabelChars) ?: return null
    val urlStart = labelEnd + 2
    val urlEnd = findCharWithin(markdown, ')', urlStart, urlStart + MaxUrlChars) ?: return null
    val raw = markdown.substring(index, urlEnd + 1)
    val label = markdown.substring(index + 1, labelEnd)
    val url = markdown.substring(urlStart, urlEnd).trim()
    val span = if (isSafeHttpUrl(url)) {
        MarkdownSpan.Link(label.ifBlank { url }, url)
    } else {
        MarkdownSpan.Text(raw)
    }
    return ParsedMarkdownSpan(span, urlEnd + 1)
}

private fun isMalformedMarkdownLinkStart(markdown: String, index: Int): Boolean =
    markdown.startsWith("[", index) &&
        findSequenceWithin(markdown, "](", index + 1, index + 1 + MaxMarkdownLinkLabelChars) != null

private fun parseBareUrlSpan(markdown: String, index: Int): ParsedBareUrlSpan? {
    if (!markdown.startsWith("https://", index) && !markdown.startsWith("http://", index)) return null
    val scanLimit = minOf(markdown.length, index + MaxUrlChars)
    var end = index
    while (end < scanLimit && !markdown[end].isBareUrlTerminator()) {
        end += 1
    }
    if (end == index) return null
    if (end == scanLimit && end < markdown.length && !markdown[end].isBareUrlTerminator()) {
        return ParsedBareUrlSpan(
            span = MarkdownSpan.Text(markdown.substring(index, end)),
            trailingText = "",
            nextIndex = end,
        )
    }
    val raw = markdown.substring(index, end)
    val url = trimBareUrlTrailingPunctuation(raw)
    if (url.isEmpty()) {
        return ParsedBareUrlSpan(MarkdownSpan.Text(raw), trailingText = "", nextIndex = end)
    }
    val trailing = raw.drop(url.length)
    val span = if (isSupportedRemoteImageUrl(url)) {
        MarkdownSpan.Image("", url)
    } else if (isSafeHttpUrl(url)) {
        MarkdownSpan.Link(url, url)
    } else {
        MarkdownSpan.Text(url)
    }
    return ParsedBareUrlSpan(span, trailing, end)
}

private fun linkifiedTextSpans(text: String): List<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    var index = 0
    while (index < text.length && spans.size < MaxMarkdownSpans) {
        val nextUrl = nextBareUrlStart(text, index)
        if (nextUrl < 0) {
            spans += MarkdownSpan.Text(text.substring(index))
            index = text.length
        } else {
            if (nextUrl > index) spans += MarkdownSpan.Text(text.substring(index, nextUrl))
            val parsed = parseBareUrlSpan(text, nextUrl)
            if (parsed == null || parsed.nextIndex <= nextUrl) {
                spans += MarkdownSpan.Text(text[nextUrl].toString())
                index = nextUrl + 1
            } else {
                spans += parsed.span
                if (parsed.trailingText.isNotEmpty()) spans += MarkdownSpan.Text(parsed.trailingText)
                index = parsed.nextIndex
            }
        }
    }
    if (index < text.length) {
        spans += MarkdownSpan.Text(text.substring(index))
    }
    return spans
}

private fun findSequenceWithin(text: String, sequence: String, startIndex: Int, rawEndExclusive: Int): Int? {
    val endExclusive = minOf(text.length, rawEndExclusive)
    var index = startIndex
    while (index + sequence.length <= endExclusive) {
        if (text.startsWith(sequence, index)) return index
        index += 1
    }
    return null
}

private fun findCharWithin(text: String, target: Char, startIndex: Int, rawEndExclusive: Int): Int? {
    val endExclusive = minOf(text.length, rawEndExclusive)
    var index = startIndex
    while (index < endExclusive) {
        if (text[index] == target) return index
        index += 1
    }
    return null
}

private fun nextBareUrlStart(text: String, startIndex: Int): Int {
    var index = startIndex
    while (index < text.length) {
        if (text[index] == 'h' && (text.startsWith("https://", index) || text.startsWith("http://", index))) {
            return index
        }
        index += 1
    }
    return -1
}

private fun Char.isBareUrlTerminator(): Boolean =
    isWhitespace() || isISOControl() || this == '<' || this == '>' || this == '"'

private fun trimBareUrlTrailingPunctuation(raw: String): String {
    var end = raw.length
    var openParen = 0
    var closeParen = 0
    var openBracket = 0
    var closeBracket = 0
    var openBrace = 0
    var closeBrace = 0
    raw.forEach { char ->
        when (char) {
            '(' -> openParen += 1
            ')' -> closeParen += 1
            '[' -> openBracket += 1
            ']' -> closeBracket += 1
            '{' -> openBrace += 1
            '}' -> closeBrace += 1
        }
    }
    while (end > 0) {
        val char = raw[end - 1]
        val shouldTrim = when (char) {
            '.', ',', '!', '?', ';', ':' -> true
            ')' -> closeParen > openParen
            ']' -> closeBracket > openBracket
            '}' -> closeBrace > openBrace
            else -> false
        }
        if (!shouldTrim) break
        when (char) {
            ')' -> closeParen -= 1
            ']' -> closeBracket -= 1
            '}' -> closeBrace -= 1
        }
        end -= 1
    }
    return raw.take(end)
}

fun truncateMarkdown(markdown: String, maxChars: Int = 160): String {
    val plain = markdown
        .replace(FencedCodeBlockRegex, "[code]")
        .replace(MarkdownSyntaxCharsRegex, "")
        .lines()
        .joinToString(" ") { it.trim() }
        .replace(WhitespaceRegex, " ")
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
        .replace(HeadingPrefixRegex, "")
        .replace(UnorderedListPrefixRegex, "")
        .replace(OrderedListPrefixRegex, "")
        .replace(InlineCodeFencePrefixRegex, "")
        .replace(BoldAsteriskPreviewRegex, "$1")
        .replace(BoldUnderscorePreviewRegex, "$1")
        .replace(BoldItalicAsteriskPreviewRegex, "$1")
        .replace(ItalicAsteriskPreviewRegex, "$1")
        .replace(ItalicUnderscorePreviewRegex, "$1")
        .replace(DoubleStrikePreviewRegex, "$1")
        .replace(StrikePreviewRegex, "$1")
        .replace(InlineCodePreviewRegex, "$1")
        .replace("""\*""", "*")
        .replace("""\[""", "[")
        .replace("""\]""", "]")
        .replace(WhitespaceRegex, " ")
        .trim()

private fun String.compactPreviewText(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."
