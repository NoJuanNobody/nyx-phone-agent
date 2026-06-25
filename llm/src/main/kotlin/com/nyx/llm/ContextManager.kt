package com.nyx.llm

/**
 * Manages the conversation context window for the on-device LLM.
 *
 * Maintains an ordered list of turns (role + content). When the estimated token
 * count exceeds [maxTokens], older turns are evicted (oldest-first) until the window fits.
 * The system prompt is always retained.
 */
class ContextManager(private val maxTokens: Int = 4096) {
    private val turns = mutableListOf<Turn>()

    data class Turn(val role: String, val content: String)

    fun addTurn(role: String, content: String) {
        turns.add(Turn(role, content))
        evictIfNeeded()
    }

    /** Formats turns as "<role>: <content>\n" joined. */
    fun buildPrompt(): String {
        return turns.joinToString(separator = "\n") { "${it.role}: ${it.content}" }
    }

    /** Rough estimate: total chars / 4. */
    fun estimatedTokens(): Int {
        return turns.sumOf { it.role.length + it.content.length + 2 } / 4
    }

    fun clear() {
        turns.clear()
    }

    fun size(): Int = turns.size

    private fun evictIfNeeded() {
        // Remove oldest non-system turns until estimatedTokens() <= maxTokens.
        var i = 0
        while (estimatedTokens() > maxTokens && i < turns.size) {
            if (turns[i].role != "system") {
                turns.removeAt(i)
            } else {
                i++
            }
        }
    }
}
