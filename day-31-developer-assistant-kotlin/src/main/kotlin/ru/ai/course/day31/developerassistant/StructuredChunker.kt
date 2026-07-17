package ru.ai.course.day31.developerassistant

class StructuredChunker(private val maxTokens: Int) {
    init {
        require(maxTokens > 0) { "Chunk size must be positive." }
    }

    fun chunk(document: ProjectDocument): List<ChunkDraft> {
        val blocks = when (document.type.lowercase()) {
            "md", "markdown" -> mergeHeadingOnlyBlocks(markdownBlocks(document.text))
            "yaml", "yml" -> yamlBlocks(document.text)
            else -> listOf(SourceBlock("document", document.text))
        }
        return pack(document, blocks).mapIndexed { index, block ->
            val ordinal = index + 1
            val contentSha256 = TextTools.sha256(block.text)
            ChunkDraft(
                metadata = ChunkMetadata(
                    source = document.source,
                    section = block.section,
                    chunkId = "sha256:${TextTools.sha256("${document.source}\u0000${block.section}\u0000$ordinal\u0000${block.text}")}",
                    contentSha256 = contentSha256,
                    ordinal = ordinal,
                    approxTokens = TextTools.approxTokens(block.text),
                ),
                text = block.text,
            )
        }
    }

    private fun markdownBlocks(text: String): List<SourceBlock> =
        partitionAtStructure(text) { line ->
            markdownHeading.matchEntire(line.trimEnd('\r', '\n'))
                ?.groupValues
                ?.get(2)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

    private fun yamlBlocks(text: String): List<SourceBlock> =
        partitionAtStructure(text) { line ->
            yamlTopLevelKey.matchEntire(line.trimEnd('\r', '\n'))
                ?.groupValues
                ?.get(1)
                ?.let { "yaml:$it" }
        }

    private fun mergeHeadingOnlyBlocks(blocks: List<SourceBlock>): List<SourceBlock> {
        if (blocks.size < 2) return blocks
        val merged = mutableListOf<SourceBlock>()
        var carry = ""
        blocks.forEachIndexed { index, block ->
            val bodyIsBlank = block.text
                .lineSequence()
                .drop(1)
                .all(String::isBlank)
            if (bodyIsBlank && index < blocks.lastIndex) {
                carry += block.text
            } else {
                merged += SourceBlock(block.section, carry + block.text)
                carry = ""
            }
        }
        if (carry.isNotEmpty()) merged += SourceBlock(blocks.last().section, carry)
        return merged
    }

    private fun partitionAtStructure(text: String, sectionForStart: (String) -> String?): List<SourceBlock> {
        val blocks = mutableListOf<SourceBlock>()
        val buffer = StringBuilder()
        var section = "root"

        fun flush() {
            if (buffer.isNotEmpty()) {
                blocks += SourceBlock(section, buffer.toString())
                buffer.clear()
            }
        }

        linesWithTerminators(text).forEach { line ->
            val nextSection = sectionForStart(line)
            if (nextSection != null) {
                flush()
                section = nextSection
            }
            buffer.append(line)
        }
        flush()
        return blocks.ifEmpty { listOf(SourceBlock("root", text)) }
    }

    private fun pack(document: ProjectDocument, blocks: List<SourceBlock>): List<SourceBlock> {
        val packed = mutableListOf<SourceBlock>()
        var section = ""
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                packed += SourceBlock(section, buffer.toString())
                buffer.clear()
            }
        }

        blocks.forEach { block ->
            if (TextTools.approxTokens(block.text) > maxTokens) {
                flush()
                packed += splitOversizedBlock(block)
                return@forEach
            }
            val wouldChangeSection = buffer.isNotEmpty() && section != block.section
            val wouldOverflow = buffer.isNotEmpty() &&
                TextTools.approxTokens(buffer.toString() + block.text) > maxTokens
            if (wouldChangeSection || wouldOverflow) flush()
            if (buffer.isEmpty()) section = block.section
            buffer.append(block.text)
        }
        flush()
        return packed
    }

    private fun splitOversizedBlock(block: SourceBlock): List<SourceBlock> {
        val chunks = mutableListOf<SourceBlock>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                chunks += SourceBlock(block.section, buffer.toString())
                buffer.clear()
            }
        }

        linesWithTerminators(block.text).forEach { line ->
            if (TextTools.approxTokens(line) > maxTokens) {
                flush()
                splitLongLine(line).forEach { chunks += SourceBlock(block.section, it) }
                return@forEach
            }
            if (buffer.isNotEmpty() && TextTools.approxTokens(buffer.toString() + line) > maxTokens) flush()
            buffer.append(line)
        }
        flush()
        return chunks
    }

    private fun splitLongLine(line: String): List<String> {
        val maxChars = maxTokens * 4
        return line.chunked(maxChars.coerceAtLeast(1))
    }

    private fun linesWithTerminators(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        text.forEachIndexed { index, character ->
            if (character == '\n') {
                lines += text.substring(start, index + 1)
                start = index + 1
            }
        }
        if (start < text.length) lines += text.substring(start)
        return lines
    }

    private data class SourceBlock(val section: String, val text: String)

    private companion object {
        val markdownHeading = Regex("""^(#{1,6})\s+(.+?)\s*$""")
        val yamlTopLevelKey = Regex("""^([A-Za-z0-9][A-Za-z0-9_-]*):(?:\s.*)?$""")
    }
}
