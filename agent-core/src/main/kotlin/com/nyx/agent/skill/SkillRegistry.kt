package com.nyx.agent.skill

/**
 * Registry that holds all available [Skill] implementations.
 */
class SkillRegistry {
    private val skills = mutableMapOf<String, Skill>()

    fun register(skill: Skill) {
        skills[skill.name] = skill
    }

    fun find(name: String): Skill? = skills[name]

    fun all(): Collection<Skill> = skills.values
}
