interface ChunkingStrategy {
    val name: String
    fun chunk(document: SourceDocument): List<ChunkDraft>
}

class FixedSizeChunker(private val chunkTokens: Int, private val overlapTokens: Int) : ChunkingStrategy {
    override val name: String = "fixed"

    override fun chunk(document: SourceDocument): List<ChunkDraft> {
        val windows = Tokenizer.tokenWindows(document.text, chunkTokens, overlapTokens)
        return windows.mapIndexed { index, text ->
            draft(
                document = document,
                strategy = name,
                ordinal = index + 1,
                section = "fixed-window-${index + 1}",
                text = text,
            )
        }
    }
}

class StructuredChunker(private val maxTokens: Int) : ChunkingStrategy {
    override val name: String = "structured"

    override fun chunk(document: SourceDocument): List<ChunkDraft> {
        val blocks = when (document.type) {
            "md" -> markdownBlocks(document.text)
            "kt", "kts" -> codeBlocks(document.text)
            else -> paragraphBlocks(document.text)
        }
        return packBlocks(document, blocks)
    }

    private fun markdownBlocks(text: String): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        var section = "intro"
        val buffer = StringBuilder()
        fun flush() {
            val value = buffer.toString().trim()
            if (value.isNotBlank()) blocks += TextBlock(section, value)
            buffer.clear()
        }

        text.lines().forEach { line ->
            val heading = markdownHeading.find(line)
            if (heading != null) {
                flush()
                section = heading.groupValues[2].trim().ifBlank { "heading" }
            }
            buffer.appendLine(line)
        }
        flush()
        return blocks.ifEmpty { paragraphBlocks(text) }
    }

    private fun codeBlocks(text: String): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        var section = "file header"
        val buffer = StringBuilder()
        fun flush() {
            val value = buffer.toString().trim()
            if (value.isNotBlank()) blocks += TextBlock(section, value)
            buffer.clear()
        }

        text.lines().forEach { line ->
            val declaration = codeDeclaration.find(line)
            if (declaration != null && buffer.isNotBlank()) {
                flush()
                section = declaration.value.trim().shortPreview(90)
            } else if (declaration != null) {
                section = declaration.value.trim().shortPreview(90)
            }
            buffer.appendLine(line)
        }
        flush()
        return blocks.ifEmpty { paragraphBlocks(text) }
    }

    private fun paragraphBlocks(text: String): List<TextBlock> =
        text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, value -> TextBlock("paragraphs-${index + 1}", value) }

    private fun packBlocks(document: SourceDocument, blocks: List<TextBlock>): List<ChunkDraft> {
        val chunks = mutableListOf<Pair<String, String>>()
        var currentSection = ""
        val current = StringBuilder()
        var currentTokens = 0

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotBlank()) chunks += currentSection to text
            current.clear()
            currentTokens = 0
        }

        blocks.forEach { block ->
            val tokens = Tokenizer.approxTokens(block.text)
            if (tokens > maxTokens) {
                flush()
                Tokenizer.tokenWindows(block.text, maxTokens, 0).forEach { window ->
                    chunks += block.section to window
                }
            } else {
                val sameSection = currentSection == block.section
                if (current.isNotBlank() && (!sameSection || currentTokens + tokens > maxTokens)) {
                    flush()
                }
                if (current.isBlank()) currentSection = block.section
                current.appendLine(block.text)
                currentTokens += tokens
            }
        }
        flush()

        return chunks.mapIndexed { index, (section, text) ->
            draft(
                document = document,
                strategy = name,
                ordinal = index + 1,
                section = section,
                text = text,
            )
        }
    }

    companion object {
        private val markdownHeading = Regex("""^(#{1,6})\s+(.+)$""")
        private val codeDeclaration = Regex(
            """^\s*((private|public|internal|sealed)\s+)*(data\s+class|enum\s+class|class|object|interface|fun)\s+.+""",
        )
    }
}

private data class TextBlock(val section: String, val text: String)

private fun draft(
    document: SourceDocument,
    strategy: String,
    ordinal: Int,
    section: String,
    text: String,
): ChunkDraft {
    val chunkId = "$strategy-${stableId(document.source, section, ordinal.toString(), text)}"
    return ChunkDraft(
        metadata = ChunkMetadata(
            source = document.source,
            title = document.title,
            section = section.shortPreview(120),
            chunkId = chunkId,
            strategy = strategy,
            ordinal = ordinal,
            approxTokens = Tokenizer.approxTokens(text),
        ),
        text = text,
    )
}
