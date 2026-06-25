package com.nyx.agent.skill

class SkillRegistry {
    private val skills = mutableMapOf<String, Skill>()
    fun register(skill: Skill) { skills[skill.name] = skill }
    fun find(name: String): Skill? = skills[name]
}
