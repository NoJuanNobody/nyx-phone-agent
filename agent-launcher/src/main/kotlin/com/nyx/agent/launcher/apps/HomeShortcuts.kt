package com.nyx.agent.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon

/**
 * Adds Nyx-built mini-apps to the device home screen as **pinned shortcuts** (API 26+).
 * A pinned shortcut is a launcher icon whose intent reopens the app in [AppHostActivity];
 * Android shows a one-time confirmation dialog before placing it (apps can't pin silently).
 */
object HomeShortcuts {

    /**
     * Requests that the app named [name] be pinned to the home screen.
     * @return false if the app doesn't exist or the launcher doesn't support pin shortcuts.
     */
    fun pin(context: Context, name: String): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false

        val file = AppStore.fileFor(context, name)
        if (!file.exists()) return false

        val launch = Intent(context, AppHostActivity::class.java).apply {
            action = Intent.ACTION_VIEW   // pinned-shortcut intents must declare an action
            putExtra(AppHostActivity.EXTRA_PATH, file.absolutePath)
            putExtra(AppHostActivity.EXTRA_TITLE, name)
        }

        val shortcut = ShortcutInfo.Builder(context, "nyxapp-${AppStore.slug(name)}")
            .setShortLabel(name.take(20))
            .setLongLabel(name)
            .setIcon(Icon.createWithBitmap(letterTile(name)))
            .setIntent(launch)
            .build()

        return manager.requestPinShortcut(shortcut, null)
    }

    /** A simple square icon: the app's first letter, white on a name-derived color. */
    private fun letterTile(name: String): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = TILE_COLORS[(name.hashCode() and Int.MAX_VALUE) % TILE_COLORS.size]
        canvas.drawColor(bg)

        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.52f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(letter, size / 2f, baseline, text)
        return bitmap
    }

    private val TILE_COLORS = intArrayOf(
        Color.parseColor("#3D5AFE"), Color.parseColor("#00897B"), Color.parseColor("#E53935"),
        Color.parseColor("#8E24AA"), Color.parseColor("#F4511E"), Color.parseColor("#1E88E5"),
        Color.parseColor("#43A047"),
    )
}
