package com.nyx.agent.launcher.mcp

import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 Authorization Code + PKCE client for an MCP server's auth server, with RFC 7591
 * dynamic client registration. Tokens are kept in app-private [prefs] (`oauth_*`). Built for
 * Higgsfield (`https://mcp.higgsfield.ai/oauth2/...`) but configurable.
 *
 * The browser/redirect leg is driven by the caller: build the URL with [authorizeUrl], open it,
 * await the code via [OAuthFlow], then [exchangeCode].
 */
class McpOAuth(
    private val prefs: SharedPreferences,
    authBase: String = "https://mcp.higgsfield.ai",
    private val redirectUri: String = "nyx://oauth/callback",
    private val scope: String = "openid email offline_access",
) {
    private val registerEndpoint = "$authBase/oauth2/register"
    private val authorizeEndpoint = "$authBase/oauth2/authorize"
    private val tokenEndpoint = "$authBase/oauth2/token"
    private val json = Json { ignoreUnknownKeys = true }

    fun accessToken(): String? = prefs.getString("oauth_access", null)
    fun isLoggedIn(): Boolean = accessToken() != null

    fun clearTokens() = prefs.edit().remove("oauth_access").remove("oauth_refresh").apply()

    /** Dynamically registers a client (once) and returns its client_id. */
    suspend fun ensureClientId(): String {
        prefs.getString("oauth_client_id", null)?.let { return it }
        val body = buildJsonObject {
            put("client_name", "Nyx Phone Agent")
            put("redirect_uris", buildJsonArray { add(redirectUri) })
            put("grant_types", buildJsonArray { add("authorization_code"); add("refresh_token") })
            put("response_types", buildJsonArray { add("code") })
            put("token_endpoint_auth_method", "none")
            put("scope", scope)
        }.toString()
        val resp = postJson(registerEndpoint, body)
        val clientId = resp["client_id"]?.jsonPrimitive?.contentOrNull
            ?: error("registration returned no client_id: $resp")
        prefs.edit().putString("oauth_client_id", clientId).apply()
        return clientId
    }

    /** Fresh PKCE verifier; stored so [exchangeCode] can use it after the browser round-trip. */
    fun newPkceVerifier(): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = b64(bytes)
        prefs.edit().putString("oauth_verifier", verifier).apply()
        return verifier
    }

    fun authorizeUrl(clientId: String, verifier: String, state: String): String {
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        prefs.edit().putString("oauth_state", state).apply()
        return Uri.parse(authorizeEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scope)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build().toString()
    }

    /** Exchanges the authorization [code] for tokens (stores access + refresh). Survives process death — clientId/verifier come from prefs. */
    suspend fun exchangeCode(code: String) {
        val verifier = prefs.getString("oauth_verifier", null) ?: error("missing PKCE verifier")
        val clientId = prefs.getString("oauth_client_id", null) ?: error("missing client_id")
        val form = form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier,
        )
        saveTokens(postForm(tokenEndpoint, form))
    }

    /** Refreshes the access token; returns true on success. */
    suspend fun refresh(): Boolean {
        val rt = prefs.getString("oauth_refresh", null) ?: return false
        val clientId = prefs.getString("oauth_client_id", null) ?: return false
        return runCatching {
            saveTokens(postForm(tokenEndpoint, form(
                "grant_type" to "refresh_token",
                "refresh_token" to rt,
                "client_id" to clientId,
            )))
        }.isSuccess
    }

    private fun saveTokens(resp: kotlinx.serialization.json.JsonObject) {
        val access = resp["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("token response had no access_token: $resp")
        val edit = prefs.edit().putString("oauth_access", access)
        resp["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { edit.putString("oauth_refresh", it) }
        edit.apply()
    }

    // -- http --------------------------------------------------------------

    private suspend fun postJson(url: String, body: String) = post(url, "application/json", body)
    private suspend fun postForm(url: String, body: String) = post(url, "application/x-www-form-urlencoded", body)

    private suspend fun post(url: String, contentType: String, body: String) = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("oauth HTTP $code: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
