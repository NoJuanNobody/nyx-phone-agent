package com.nyx.agent.launcher.apps

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import java.io.File

/**
 * Runs a Nyx-generated mini-app: loads its self-contained HTML into a full-screen [WebView]
 * with JavaScript + DOM storage enabled. The content is local (generated on-device), so no
 * network access is required for it to run.
 */
class AppHostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true       // mini-apps are interactive
            settings.domStorageEnabled = true        // allow localStorage (trackers, settings)
            settings.builtInZoomControls = false
            // The emulator's SwiftShader GPU makes WebView's hardware renderer crash; software
            // layer rendering is stable. (Negligible cost for these simple mini-apps.)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        setContentView(webView)

        val path = intent.getStringExtra(EXTRA_PATH)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Nyx app"

        val html = path?.let { File(it) }?.takeIf { it.exists() }?.readText()
        if (html != null) {
            // null base URL keeps it sandboxed; the doc is fully self-contained.
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        } else {
            webView.loadData(
                "<h2 style='font-family:sans-serif;padding:24px'>App not found</h2>",
                "text/html",
                "utf-8",
            )
        }
    }

    companion object {
        const val EXTRA_PATH = "app_path"
        const val EXTRA_TITLE = "app_title"
    }
}
