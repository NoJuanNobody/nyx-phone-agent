package com.nyx.agent.skill

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry for [Skill] instances.
 *
 * Skills register at startup (or dynamically at runtime); the registry is queried by
 * [SkillRouter] at dispatch time. All operations are safe to call from multiple threads.
 */
class SkillRegistry {
    private val skills = ConcurrentHashMap<String, Skill>()

    /**
     * Register a skill. If a skill with the same [Skill.name] already exists it is replaced.
     *
     * @param skill The skill to register.
     */
    fun register(skill: Skill) {
        skills[skill.name] = skill
    }

    /**
     * Deregister the skill identified by [name].
     *
     * @param name The [Skill.name] to remove.
     * @return `true` if the skill was present and removed; `false` if it was not registered.
     */
    fun unregister(name: String): Boolean = skills.remove(name) != null

    /**
     * Look up a skill by name.
     *
     * @param name The [Skill.name] to search for.
     * @return The matching [Skill], or `null` if none is registered under that name.
     */
    fun find(name: String): Skill? = skills[name]

    /**
     * Return a snapshot of all currently registered skills.
     *
     * The returned list is a point-in-time copy; subsequent registrations or
     * deregistrations are not reflected in it.
     */
    fun all(): List<Skill> = skills.values.toList()
}
