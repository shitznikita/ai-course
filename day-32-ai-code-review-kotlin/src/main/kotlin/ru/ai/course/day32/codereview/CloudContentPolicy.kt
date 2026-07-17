package ru.ai.course.day32.codereview

enum class CloudContentRejection {
    SENSITIVE_PATH,
    SENSITIVE_CONTENT,
}

class CloudContentRejectedException(
    val rejection: CloudContentRejection,
) : IllegalStateException("Cloud review blocked by the sensitive-content policy.")

class CloudContentPolicy {
    fun pathRejection(path: String): CloudContentRejection? {
        val lower = path.lowercase()
        val segments = lower.split('/')
        val name = segments.lastOrNull().orEmpty()
        return if (
            segments.any { segment ->
                segment.startsWith(".env") ||
                    segment.startsWith(".cert") ||
                    "secret" in segment ||
                    "credential" in segment ||
                    "private-key" in segment
            } ||
            name in sensitiveNames ||
            sensitiveExtensions.any(lower::endsWith)
        ) {
            CloudContentRejection.SENSITIVE_PATH
        } else {
            null
        }
    }

    fun contentRejection(content: String): CloudContentRejection? {
        if (privateKeyMarker.containsMatchIn(content)) return CloudContentRejection.SENSITIVE_CONTENT
        if (containsAuthorizationCredential(content)) return CloudContentRejection.SENSITIVE_CONTENT
        if (providerCredential.containsMatchIn(content)) return CloudContentRejection.SENSITIVE_CONTENT
        if (jwtCredential.containsMatchIn(content)) return CloudContentRejection.SENSITIVE_CONTENT
        val containsCredential = (
            credentialName.findAll(content).mapNotNull { match ->
                quotedAssignedValue(content, match.range.last + 1)
            } +
                unquotedCredential.findAll(content)
                    .map { match -> match.groupValues[2] }
            )
            .any(::isSuspiciousCredentialValue)
        return CloudContentRejection.SENSITIVE_CONTENT.takeIf { containsCredential }
    }

    fun requireSafePath(path: String) {
        pathRejection(path)?.let { throw CloudContentRejectedException(it) }
    }

    fun requireSafeContent(content: String?) {
        if (content == null) return
        contentRejection(content)?.let { throw CloudContentRejectedException(it) }
    }

    fun requireSafePrompt(prompt: String) {
        requireSafeContent(prompt)
        promptPath.findAll(prompt).forEach { match ->
            requireSafePath(match.groupValues[1].trim())
        }
    }

    fun requireSafe(file: ChangedFile) {
        requireSafePath(file.path)
        file.previousPath?.let(::requireSafePath)
        requireSafeContent(file.patch)
        requireSafeContent(file.content)
    }

    fun requireSafe(files: Iterable<ChangedFile>) {
        files.forEach(::requireSafe)
    }

    private fun isSuspiciousCredentialValue(value: String): Boolean {
        val normalized = normalizeCredentialCandidate(value)
        if (normalized.length < 8) return false
        if (isKnownPlaceholder(normalized)) return false
        return true
    }

    private fun containsAuthorizationCredential(content: String): Boolean {
        val quoted = authorizationName.findAll(content)
            .mapNotNull { match -> quotedAssignedValue(content, match.range.last + 1) }
            .any(::isSuspiciousAuthorizationValue)
        return quoted || authorizationHeader.findAll(content)
            .map { match -> match.groupValues[1] }
            .any(::isSuspiciousAuthorizationValue)
    }

    private fun isSuspiciousAuthorizationValue(value: String): Boolean {
        val match = authorizationValue.matchEntire(decodeQuotedValue(value).trim()) ?: return false
        return isSuspiciousCredentialValue(match.groupValues[1])
    }

    private fun quotedAssignedValue(content: String, afterName: Int): String? {
        var index = skipWhitespace(content, afterName)
        index = when {
            content.startsWith("\\\"", index) || content.startsWith("\\'", index) -> index + 2
            content.getOrNull(index) == '"' || content.getOrNull(index) == '\'' -> index + 1
            else -> index
        }
        index = skipWhitespace(content, index)
        if (content.getOrNull(index) !in setOf(':', '=')) return null
        index = skipWhitespace(content, index + 1)

        val escapedDelimiter = content.getOrNull(index) == '\\' &&
            content.getOrNull(index + 1) in setOf('"', '\'')
        val quote = if (escapedDelimiter) content[index + 1] else content.getOrNull(index)
        if (quote !in setOf('"', '\'')) return null
        index += if (escapedDelimiter) 2 else 1
        val start = index
        while (index < content.length) {
            if (escapedDelimiter && content[index] == '\\') {
                var end = index
                while (end < content.length && content[end] == '\\') end++
                if (end < content.length && content[end] == quote && end - index == 1) {
                    return decodeQuotedValue(content.substring(start, index))
                }
                index = if (end < content.length && content[end] == quote) end + 1 else end
                continue
            }
            if (escapedDelimiter && content[index] == quote) return null
            if (!escapedDelimiter && content[index] == quote) {
                return decodeQuotedValue(content.substring(start, index))
            }
            if (!escapedDelimiter && content[index] == '\\' && index + 1 < content.length) {
                index += 2
            } else {
                index++
            }
        }
        return null
    }

    private fun decodeQuotedValue(value: String): String {
        val decoded = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] != '\\' || index + 1 >= value.length) {
                decoded.append(value[index++])
                continue
            }
            val escaped = value[index + 1]
            when (escaped) {
                'b' -> decoded.append('\b')
                'f' -> decoded.append('\u000C')
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                't' -> decoded.append('\t')
                'u' -> {
                    val digits = value.substring(index + 2, minOf(index + 6, value.length))
                    val codePoint = digits.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (codePoint == null) {
                        decoded.append(escaped)
                    } else {
                        decoded.append(codePoint.toChar())
                        index += 4
                    }
                }
                else -> decoded.append(escaped)
            }
            index += 2
        }
        return decoded.toString()
    }

    private fun normalizeCredentialCandidate(value: String): String =
        decodeQuotedValue(value)
            .trim()
            .trimEnd { character ->
                character.isWhitespace() || character in setOf('"', '\'', '`', '\\', ',', ';', '.')
            }

    private fun isKnownPlaceholder(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized in placeholderValues ||
            environmentReference.matches(value) ||
            angleReference.matches(value) ||
            isKnownShellDefault(value) ||
            interpolationReference.matches(value) ||
            printfReference.matches(value)
    }

    private fun isKnownShellDefault(value: String): Boolean {
        val match = shellDefaultReference.matchEntire(value) ?: return false
        val fallback = match.groupValues[1].lowercase()
        return fallback.isEmpty() || fallback in placeholderValues
    }

    private fun skipWhitespace(content: String, start: Int): Int {
        var index = start
        while (content.getOrNull(index)?.isWhitespace() == true) index++
        return index
    }

    companion object {
        private const val credentialNamePattern =
            """(?:[A-Za-z0-9]+[_-])*(?:secret[_-]?(?:access[_-]?key|key)|api[_-]?key|access[_-]?token|oauth[_-]?token|access[_-]?key(?:[_-]?id)?|token|password|client[_-]?secret|private[_-]?key|secret)"""
        private val sensitiveNames = setOf(
            ".npmrc", ".netrc", "id_rsa", "id_ed25519", "credentials.json", "service-account.json",
            "application.properties", "local.properties",
        )
        private val sensitiveExtensions = setOf(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".der", ".crt", ".cer",
        )
        private val privateKeyMarker = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""")
        private val credentialName = Regex("""(?i)\b$credentialNamePattern\b""")
        private val unquotedCredential = Regex(
            """(?im)^[+\- ]?\s*(?:export\s+)?($credentialNamePattern)\b\s*[:=]\s*([^\s#"'`,;()\[\]]{8,})\s*(?:#.*)?$""",
        )
        private val authorizationName = Regex("""(?i)\bauthorization\b""")
        private val authorizationHeader = Regex(
            """(?i)\bauthorization\s*:\s*([A-Za-z][A-Za-z0-9_-]{0,30}[ \t]+[^\r\n]+)""",
        )
        private val authorizationValue = Regex("""(?is)^[A-Za-z][A-Za-z0-9_-]{0,30}[ \t]+(.+)$""")
        private val providerCredential = Regex(
            """\b(?:ghp_|github_pat_|sk_live_|sk-|xox[baprs]-|ya29\.|AKIA|AIza)[A-Za-z0-9._~+/\-=]{12,}""",
        )
        private val jwtCredential = Regex(
            """\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b""",
        )
        private val placeholderValues = setOf(
            "changeit", "test-token", "dummy", "sample", "fake", "redacted", "not-for-cloud",
            "null", "none", "local-oauth-token-only", "llm-test-token", "github-test-token",
            "your_eliza_oauth_token_here", "your_deepseek_api_key_here", "put_oauth_token_here",
            "replace-with-oauth-token",
        )
        private val environmentReference = Regex("""\$(?:[A-Z][A-Z0-9_]{3,}|\{[A-Z][A-Z0-9_]{3,}})""")
        private val angleReference = Regex("""<[A-Za-z][A-Za-z0-9_-]{2,}>""")
        private val shellDefaultReference = Regex("""\$\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)}""")
        private val interpolationReference = Regex("""\$\{[A-Za-z_][A-Za-z0-9_.-]*}""")
        private val printfReference = Regex(
            """(?s)^%s\r?\n(?:Content-Type:\s*application/json\r?\n)?["']\s+["']\$[A-Za-z_][A-Za-z0-9_]*["']\s*\|?$""",
        )
        private val promptPath = Regex("""(?m)^path=(.+)$""")
    }
}

object ReviewFailureDiagnostics {
    fun message(error: Throwable): String = when (error) {
        is CloudContentRejectedException ->
            "cloud review пропущен sensitive-content policy: 0 файлов проверено, PR content не отправлялся модели"
        is ReviewInputLimitExceededException ->
            "cloud review пропущен changed-file safety limit: 0 файлов проверено, PR content не отправлялся модели"
        is ModelValidationException ->
            "ответ модели не прошёл строгую проверку; job завершён без публикации сырого ответа"
        is LlmHttpException, is LlmProtocolException, is HttpTransportException ->
            error.message ?: "провайдер LLM недоступен или вернул неподдерживаемый ответ"
        is GitHubHttpException, is GitHubProtocolException ->
            "не удалось безопасно получить или опубликовать данные GitHub"
        is IllegalArgumentException -> "конфигурация или входные данные не прошли проверку"
        else -> "внутренняя проверка ревью завершилась ошибкой (${error.javaClass.simpleName})"
    }
}
