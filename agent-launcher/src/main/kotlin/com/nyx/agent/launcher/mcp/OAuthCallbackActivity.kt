package com.nyx.agent.launcher.mcp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.nyx.agent.launcher.MainActivity

/**
 * Receives the OAuth redirect (`nyx://oauth/callback?code=...`). To survive the app process
 * being killed while the browser is foreground, the result is **persisted to prefs** rather than
 * handed to an in-memory coroutine; [MainActivity] completes the token exchange on resume.
 */
class OAuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")

        val prefs = getSharedPreferences("nyx", Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (code != null) putString("oauth_pending_code", code)
            else putString("oauth_error", error ?: "authorization cancelled")
        }.apply()

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
