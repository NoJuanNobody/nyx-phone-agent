package com.nyx.agent.launcher.apps

import android.content.Context
import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

/**
 * Skills that let the conversational agent build and run mini-apps on the phone.
 *
 * [BuildAppSkill] generates a self-contained HTML app from a spec (via the injected
 * [generateHtml] code-gen function — OpenRouter now, a local model later), saves it, and
 * opens it. [OpenAppSkill] reopens a saved app; [ListAppsSkill] lists them.
 */
class BuildAppSkill(
    private val context: Context,
    private val generateHtml: suspend (spec: String) -> String,
) : Skill {
    override val name = "build_app"
    override val description =
        "Build and immediately run a small self-contained web app on the phone. " +
            "Use for calculators, trackers, timers, games, tools, forms, etc. " +
            "args: name (short app name, string), spec (string describing what the app should do and look like), " +
            "pin (optional boolean — set true to also add it to the home screen as an icon)."

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        val name = (args["name"] as? String)?.takeIf { it.isNotBlank() } ?: "App"
        val spec = (args["spec"] as? String ?: args["requirements"] as? String ?: args["description"] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("missing 'spec' describing the app to build")
        val pin = args["pin"] as? Boolean ?: false

        val html = try {
            AppStore.cleanHtml(generateHtml(spec))
        } catch (e: Exception) {
            return SkillResult.Failure("code generation failed: ${e.message}")
        }
        if (!html.contains("<")) return SkillResult.Failure("code generation produced no HTML")

        val file = AppStore.save(context, name, html)
        val pinned = if (pin) runCatching { HomeShortcuts.pin(context, name) }.getOrDefault(false) else false
        if (!pin) AppStore.open(context, file.absolutePath, name)  // pinning shows its own dialog; don't race it
        return SkillResult.Success(
            mapOf("name" to name, "opened" to !pin, "addedToHomeScreen" to pinned, "bytes" to html.length)
        )
    }
}

class PinAppSkill(private val context: Context) : Skill {
    override val name = "pin_app"
    override val description =
        "Add an already-built app to the home screen as a tappable icon. args: name (string)."

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        val name = (args["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("missing 'name'")
        if (!AppStore.fileFor(context, name).exists()) {
            val have = AppStore.list(context)
            return SkillResult.Failure(
                "no app named '$name'." + if (have.isEmpty()) "" else " You have: ${have.joinToString()}."
            )
        }
        return if (HomeShortcuts.pin(context, name)) {
            SkillResult.Success(mapOf("name" to name, "addedToHomeScreen" to true))
        } else {
            SkillResult.Failure("the launcher doesn't support adding home-screen shortcuts")
        }
    }
}

class OpenAppSkill(private val context: Context) : Skill {
    override val name = "open_app"
    override val description = "Open a mini-app you previously built. args: name (string)."

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        val name = (args["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("missing 'name'")
        val file = AppStore.fileFor(context, name)
        if (!file.exists()) {
            val have = AppStore.list(context)
            return SkillResult.Failure(
                "no app named '$name'." + if (have.isEmpty()) "" else " You have: ${have.joinToString()}."
            )
        }
        AppStore.open(context, file.absolutePath, name)
        return SkillResult.Success(mapOf("name" to name, "opened" to true))
    }
}

class ListAppsSkill(private val context: Context) : Skill {
    override val name = "list_apps"
    override val description = "List the mini-apps the user has built. No args."

    override suspend fun execute(args: Map<String, Any>): SkillResult =
        SkillResult.Success(mapOf("apps" to AppStore.list(context)))
}
