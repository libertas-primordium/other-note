package com.libertasprimordium.othernote.util

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String) : MarkdownBlock()
}

fun markdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    markdown.lines().forEach { line ->
        when {
            line.trim() == "```" && !inCode -> {
                flushParagraph()
                inCode = true
            }
            line.trim() == "```" && inCode -> {
                blocks += MarkdownBlock.CodeBlock(code.toString().trimEnd())
                code.clear()
                inCode = false
            }
            inCode -> code.appendLine(line)
            line.isBlank() -> flushParagraph()
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                if (line.getOrNull(level) == ' ') {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(level, line.drop(level + 1).trim())
                } else {
                    paragraph += line
                }
            }
            else -> paragraph += line
        }
    }
    if (inCode) blocks += MarkdownBlock.CodeBlock(code.toString().trimEnd())
    flushParagraph()
    return blocks
}

fun truncateMarkdown(markdown: String, maxChars: Int = 160): String {
    val plain = markdown
        .replace(Regex("```[\\s\\S]*?```"), "[code]")
        .replace(Regex("""[#*_`>\[\]()]"""), "")
        .lines()
        .joinToString(" ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (plain.length <= maxChars) plain else plain.take(maxChars).trimEnd() + "..."
}
