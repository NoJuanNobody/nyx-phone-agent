package com.nyx.agent.skill

/**
 * Dispatches model tool-calls to registered [Skill] implementations via [SkillRegistry].
 *
 * All dispatches are fully async — callers must collect results inside a coroutine.
 * The router itself is stateless beyond its [registry] reference and is safe to share
 * across coroutines without additional synchronisation.
 *
 * @param registry The skill registry used to look up handlers at dispatch time.
 */
class SkillRouter(private val registry: SkillRegistry) {

    /**
     * Dispatch a tool-call by name.
     *
     * Looks up [toolName] in the [registry]. If no handler is found, returns
     * [SkillResult.SkillNotFound] immediately without throwing. If the skill's
     * [Skill.execute] throws an uncaught exception it is caught and wrapped in
     * [SkillResult.Failure] so callers always receive a typed result.
     *
     * @param toolName The tool-call name emitted by the model (must match [Skill.name]).
     * @param args     Key-value arguments from the model tool-call payload.
     * @return A [SkillResult] — never throws.
     */
    suspend fun dispatch(toolName: String, args: Map<String, Any>): SkillResult {
        val skill = registry.find(toolName)
            ?: return SkillResult.SkillNotFound(toolName)

        return try {
            skill.execute(args)
        } catch (t: Throwable) {
            SkillResult.Failure(
                error = "Skill '$toolName' threw an unexpected exception: ${t.message}",
                cause = t,
            )
        }
    }
}
