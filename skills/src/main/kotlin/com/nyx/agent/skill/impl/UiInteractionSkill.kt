package com.nyx.agent.skill.impl

import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

/** Represents a screen touch gesture. */
data class GestureParams(
    val x: Float,
    val y: Float,
    val endX: Float? = null,  // for swipe
    val endY: Float? = null,
    val durationMs: Long = 100,
)

/** Audit entry written for every dispatched gesture. */
data class GestureAuditEntry(
    val action: String,
    val params: GestureParams,
    val elementContext: String?,  // e.g. "button:Submit at (240,880)"
    val success: Boolean,
)

/**
 * Abstraction over Android gesture dispatch for testability.
 * Production impl uses AccessibilityService; ADB bridge is the fallback.
 */
interface UiInteractionBridge {
    /** Perform a single tap at (x, y). Returns true if dispatched. */
    fun tap(x: Float, y: Float): Boolean
    /** Perform a scroll from (x,y) to (endX,endY) over durationMs. */
    fun scroll(x: Float, y: Float, endX: Float, endY: Float, durationMs: Long): Boolean
    /** Perform a swipe from (x,y) to (endX,endY) over durationMs. */
    fun swipe(x: Float, y: Float, endX: Float, endY: Float, durationMs: Long): Boolean
}

/**
 * Skill that drives on-screen UI via tap, scroll, and swipe gestures.
 *
 * Coordinates come from screenshot bounding boxes (as absolute pixel positions).
 * Every dispatched gesture is written to [auditLog] for traceability.
 *
 * Args:
 * - `action` (String): "tap" | "scroll" | "swipe"
 * - `x` (Number): start X coordinate
 * - `y` (Number): start Y coordinate
 * - `end_x` (Number): end X — required for scroll/swipe
 * - `end_y` (Number): end Y — required for scroll/swipe
 * - `duration_ms` (Number, default 200): gesture duration
 * - `element_context` (String, optional): human label for audit (e.g. "Submit button")
 */
class UiInteractionSkill(
    private val bridge: UiInteractionBridge,
    private val auditLog: MutableList<GestureAuditEntry> = mutableListOf(),
) : Skill {
    override val name = "ui_interact"
    override val description = "Tap, scroll, or swipe on screen at specified coordinates"

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        val action = args["action"] as? String
            ?: return SkillResult.Failure("'action' is required: tap, scroll, swipe")
        val x = (args["x"] as? Number)?.toFloat()
            ?: return SkillResult.Failure("'x' coordinate is required")
        val y = (args["y"] as? Number)?.toFloat()
            ?: return SkillResult.Failure("'y' coordinate is required")
        val elementContext = args["element_context"] as? String
        val durationMs = (args["duration_ms"] as? Number)?.toLong() ?: 200L

        val params: GestureParams
        val success: Boolean

        when (action) {
            "tap" -> {
                params = GestureParams(x, y, durationMs = durationMs)
                success = bridge.tap(x, y)
            }
            "scroll", "swipe" -> {
                val endX = (args["end_x"] as? Number)?.toFloat()
                    ?: return SkillResult.Failure("'end_x' is required for $action")
                val endY = (args["end_y"] as? Number)?.toFloat()
                    ?: return SkillResult.Failure("'end_y' is required for $action")
                params = GestureParams(x, y, endX, endY, durationMs)
                success = if (action == "scroll") bridge.scroll(x, y, endX, endY, durationMs)
                          else bridge.swipe(x, y, endX, endY, durationMs)
            }
            else -> return SkillResult.Failure("Unknown action '$action'. Use tap, scroll, or swipe")
        }

        auditLog.add(GestureAuditEntry(action, params, elementContext, success))
        return if (success) {
            SkillResult.Success(mapOf("action" to action, "x" to x, "y" to y, "context" to (elementContext ?: "")))
        } else {
            SkillResult.Failure("Gesture '$action' at ($x, $y) failed to dispatch")
        }
    }
}
