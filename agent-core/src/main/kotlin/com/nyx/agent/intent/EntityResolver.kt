package com.nyx.agent.intent

import kotlinx.serialization.Serializable

/**
 * Resolves ambiguous entities in parsed intents to concrete values.
 *
 *  - Contact names ("Call Sarah") → phone numbers via ContactsProvider.
 *  - Natural-language time ("tomorrow 3pm") → epoch-millis UTC timestamps.
 *  - Location names → coordinates (delegated to a maps backend).
 *
 * The resolver is abstracted over a [ContactsProvider] so it is unit-testable
 * without a real Android ContactsProvider.
 */
class EntityResolver(
    private val contacts: ContactsProvider = InMemoryContactsProvider(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** Resolve a contact name (full or first name) to a phone number. */
    fun resolveContact(name: String): ContactResolution {
        val matches = contacts.search(name)
        return when {
            matches.isEmpty() -> ContactResolution.NotFound(name)
            matches.size == 1 -> ContactResolution.Resolved(name, matches.single().number)
            else -> ContactResolution.Ambiguous(name, matches)
        }
    }

    /**
     * Parse a natural-language time expression to epoch-millis (UTC).
     * Supports: "now", "in N minutes/hours", "tomorrow [HH(:MM)]",
     * "today HH:MM", ISO-8601 passthrough.
     */
    fun resolveTime(expression: String): Long? {
        val now = clock()
        val lower = expression.trim().lowercase()
        return when {
            lower == "now" -> now
            lower.startsWith("in ") -> {
                val m = Regex("""in\s+(\d+)\s+(minute|hour|day)s?""").find(lower)
                val n = m?.groupValues?.get(1)?.toIntOrNull() ?: return null
                val unit = m.groupValues[2]
                val mult = when (unit) { "minute" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L; else -> return null }
                now + n * mult
            }
            lower.startsWith("tomorrow") -> {
                val tod = lower.removePrefix("tomorrow").trim()
                val base = now + 86_400_000L
                if (tod.isEmpty()) base else base + timeOfDayMillis(tod)
            }
            lower.startsWith("today") -> {
                val tod = lower.removePrefix("today").trim()
                if (tod.isEmpty()) null else now + timeOfDayMillis(tod)
            }
            else -> try { java.time.Instant.parse(expression).toEpochMilli() } catch (_: Throwable) { null }
        }
    }

    private fun timeOfDayMillis(hhmm: String): Long {
        val m = Regex("""(\d{1,2}):?(\d{2})?""").find(hhmm) ?: return 0L
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifBlank { "0" }.toInt()
        return (h * 3_600_000L + min * 60_000L)
    }
}

@Serializable
sealed class ContactResolution {
    abstract val query: String
    data class Resolved(override val query: String, val number: String) : ContactResolution()
    data class Ambiguous(override val query: String, val matches: List<Contact>) : ContactResolution()
    data class NotFound(override val query: String) : ContactResolution()
}

@Serializable
data class Contact(val name: String, val number: String)

/** Android ContactsProvider abstraction. */
interface ContactsProvider {
    fun search(query: String): List<Contact>
}

class InMemoryContactsProvider(initial: List<Contact> = emptyList()) : ContactsProvider {
    private val contacts = initial.toMutableList()
    fun add(c: Contact) { contacts.add(c) }
    override fun search(query: String): List<Contact> {
        val q = query.trim().lowercase()
        return contacts.filter { it.name.lowercase().startsWith(q) || it.name.lowercase().split(" ").first() == q }
    }
}
