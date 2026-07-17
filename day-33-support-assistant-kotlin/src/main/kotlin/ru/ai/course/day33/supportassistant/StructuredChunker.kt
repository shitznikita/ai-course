package ru.ai.course.day33.supportassistant

class StructuredChunker(private val maxChunkChars: Int) {
    init {
        require(maxChunkChars >= 300) { "Chunk character limit is too small." }
    }

    fun chunk(document: KnowledgeDocument): List<KnowledgeChunk> {
        val sections = parseSections(document)
        return sections.flatMap { section ->
            splitSection(section.body).mapIndexed { index, body ->
                val suffix = if (index == 0) "" else "-${index + 1}"
                val stableKey = section.heading.substringBefore('—').trim()
                val sourceId = "${document.relativePath}#${TextTools.slug(stableKey)}$suffix"
                val text = buildString {
                    append("# ").append(document.title).append("\n\n")
                    append("## ").append(section.heading).append("\n\n")
                    append(body.trim())
                }.trim()
                KnowledgeChunk(
                    sourceId = sourceId,
                    documentPath = document.relativePath,
                    heading = section.heading,
                    text = text,
                    fingerprint = fingerprint(document.relativePath, section.heading, index, text),
                    embedding = emptyList(),
                )
            }
        }
    }

    companion object {
        internal fun fingerprint(
            documentPath: String,
            heading: String,
            chunkIndex: Int,
            text: String,
        ): String = TextTools.sha256("$documentPath\u0000$heading\u0000$chunkIndex\u0000$text")
    }

    private fun parseSections(document: KnowledgeDocument): List<Section> {
        val sections = mutableListOf<Section>()
        var heading: String? = null
        val body = mutableListOf<String>()
        fun flush() {
            val current = heading ?: return
            val text = body.joinToString("\n").trim()
            require(text.isNotBlank()) { "Knowledge section '$current' is empty." }
            sections += Section(current, text)
            body.clear()
        }

        document.markdown.lineSequence().forEach { line ->
            when {
                line.startsWith("## ") -> {
                    flush()
                    heading = line.removePrefix("## ").trim()
                    require(heading.isNotBlank()) { "Knowledge section has a blank heading." }
                }
                line.startsWith("# ") && heading == null -> Unit
                heading != null -> body += line
            }
        }
        flush()
        require(sections.isNotEmpty()) { "Knowledge document ${document.relativePath} has no sections." }
        return sections
    }

    private fun splitSection(body: String): List<String> {
        if (body.length <= maxChunkChars) return listOf(body)
        val paragraphs = body.split(Regex("""\n\s*\n""")).flatMap(::splitParagraph)
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        paragraphs.forEach { paragraph ->
            val separator = if (current.isEmpty()) 0 else 2
            if (current.length + separator + paragraph.length > maxChunkChars) {
                chunks += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun splitParagraph(paragraph: String): List<String> {
        if (paragraph.length <= maxChunkChars) return listOf(paragraph)
        val sentences = paragraph.split(Regex("""(?<=[.!?])\s+"""))
        val pieces = mutableListOf<String>()
        var current = StringBuilder()
        sentences.flatMap(::splitLongUnit).forEach { unit ->
            val separator = if (current.isEmpty()) 0 else 1
            if (current.length + separator + unit.length > maxChunkChars) {
                pieces += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(unit)
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }

    private fun splitLongUnit(unit: String): List<String> {
        if (unit.length <= maxChunkChars) return listOf(unit)
        val pieces = mutableListOf<String>()
        var current = StringBuilder()
        unit.split(Regex("""\s+""")).forEach { word ->
            if (word.length > maxChunkChars) {
                if (current.isNotEmpty()) {
                    pieces += current.toString()
                    current = StringBuilder()
                }
                word.chunked(maxChunkChars).forEach(pieces::add)
            } else {
                val separator = if (current.isEmpty()) 0 else 1
                if (current.length + separator + word.length > maxChunkChars) {
                    pieces += current.toString()
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }

    private data class Section(val heading: String, val body: String)
}
