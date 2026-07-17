package ru.ai.course.day33.supportassistant

import kotlinx.serialization.decodeFromString
import java.nio.file.Path

interface SupportDataRepository {
    fun ticket(ticketId: String): SupportTicket?
    fun user(userId: String): SupportUser?
}

class JsonSupportDataRepository(path: Path) : SupportDataRepository {
    private val data: SupportDataFile
    private val tickets: Map<String, SupportTicket>
    private val users: Map<String, SupportUser>

    init {
        val raw = TextTools.readUtf8Bounded(path, 1_000_000, "Support fixture")
        data = runCatching { SupportJson.strict.decodeFromString<SupportDataFile>(raw) }
            .getOrElse { throw IllegalArgumentException("Support fixture has an invalid strict schema.", it) }
            .let(SupportDataPolicy::requireValid)
        tickets = data.tickets.associateBy(SupportTicket::id)
        users = data.users.associateBy(SupportUser::id)
    }

    override fun ticket(ticketId: String): SupportTicket? = tickets[SupportDataPolicy.requireTicketId(ticketId)]

    override fun user(userId: String): SupportUser? = users[SupportDataPolicy.requireUserId(userId)]
}
