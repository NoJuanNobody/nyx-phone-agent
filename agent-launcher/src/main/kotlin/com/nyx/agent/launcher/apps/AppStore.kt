package com.nyx.agent.launcher.apps

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Storage for Nyx-generated mini-apps: single self-contained HTML files under
 * `filesDir/apps/`. Each app is identified by a slug derived from its name.
 */
object AppStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "apps").apply { mkdirs() }

    fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "app" }

    fun fileFor(context: Context, name: String): File = File(dir(context), "${slug(name)}.html")

    fun save(context: Context, name: String, html: String): File =
        fileFor(context, name).apply { writeText(html) }

    fun list(context: Context): List<String> =
        dir(context).listFiles { f -> f.extension == "html" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    /** Strips markdown fences / prose so only the HTML document remains. */
    fun cleanHtml(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```html").removePrefix("```HTML").removePrefix("```").trim()
            s = s.removeSuffix("```").trim()
        }
        val lower = s.lowercase()
        val start = lower.indexOf("<!doctype").let { if (it >= 0) it else lower.indexOf("<html") }
        if (start > 0) s = s.substring(start)
        return s
    }

    /** Opens [path] full-screen in [AppHostActivity]. Safe to call from a non-Activity context. */
    fun open(context: Context, path: String, title: String) {
        val intent = Intent(context, AppHostActivity::class.java).apply {
            putExtra(AppHostActivity.EXTRA_PATH, path)
            putExtra(AppHostActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
