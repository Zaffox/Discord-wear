package com.zaffox.discordwear

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val apkUrl: String?,
    val htmlUrl: String,
    val publishedAt: String
)

object UpdateChecker {

    // Point at the GitHub Releases API — update owner/repo if the repo is forked/renamed
    private const val GITHUB_OWNER = "zaffox"
    private const val GITHUB_REPO  = "Discord-WearOS-Alpha"
    private const val API_URL      = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    // Current bundled version — must match versionName in build.gradle
    const val CURRENT_VERSION = "1.0"

    private const val PREFS_NAME        = "update_checker"
    private const val KEY_LAST_CHECK    = "last_check_ms"
    private const val KEY_LATEST_TAG    = "latest_tag"
    private const val KEY_LATEST_APK    = "latest_apk_url"
    private const val KEY_LATEST_HTML   = "latest_html_url"
    private const val KEY_LATEST_NAME   = "latest_release_name"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L  // 24 hours

    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http   = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    sealed class UpdateState {
        object Idle       : UpdateState()
        object Checking   : UpdateState()
        object UpToDate   : UpdateState()
        data class UpdateAvailable(val release: ReleaseInfo) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Call once from Application.onCreate — runs a 24-hour polling loop. */
    fun start(context: Context) {
        scope.launch {
            // Restore cached state so Settings shows info immediately on launch
            restoreCachedState(context)

            val appContext = context.applicationContext
            while (true) {
                val prefs       = prefs(appContext)
                val lastCheckMs = prefs.getLong(KEY_LAST_CHECK, 0L)
                val nowMs       = System.currentTimeMillis()
                val dueMs       = (lastCheckMs + CHECK_INTERVAL_MS - nowMs).coerceAtLeast(0L)

                if (dueMs > 0) delay(dueMs)

                check(appContext)
            }
        }
    }

    /** Force an immediate check (called from Settings "Check Now" button). */
    fun checkNow(context: Context) {
        scope.launch { check(context.applicationContext) }
    }

    private fun restoreCachedState(context: Context) {
        val p   = prefs(context)
        val tag = p.getString(KEY_LATEST_TAG, null) ?: return
        if (isNewer(tag, CURRENT_VERSION)) {
            _state.value = UpdateState.UpdateAvailable(
                ReleaseInfo(
                    tagName     = tag,
                    name        = p.getString(KEY_LATEST_NAME, tag) ?: tag,
                    body        = "",
                    apkUrl      = p.getString(KEY_LATEST_APK, null),
                    htmlUrl     = p.getString(KEY_LATEST_HTML, "") ?: "",
                    publishedAt = ""
                )
            )
        } else {
            _state.value = UpdateState.UpToDate
        }
    }

    private suspend fun check(context: Context) {
        _state.value = UpdateState.Checking
        runCatching {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            val body = http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty body")
            }

            val json      = JSONObject(body)
            val tag       = json.getString("tag_name").trimStart('v')
            val name      = json.optString("name", tag)
            val releaseBody = json.optString("body", "")
            val htmlUrl   = json.getString("html_url")

            // Find the first .apk asset
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            // Persist result
            prefs(context).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_LATEST_TAG, tag)
                .putString(KEY_LATEST_NAME, name)
                .putString(KEY_LATEST_APK, apkUrl)
                .putString(KEY_LATEST_HTML, htmlUrl)
                .apply()

            val release = ReleaseInfo(tag, name, releaseBody, apkUrl, htmlUrl, json.optString("published_at"))
            _state.value = if (isNewer(tag, CURRENT_VERSION))
                UpdateState.UpdateAvailable(release)
            else
                UpdateState.UpToDate
        }.onFailure { e ->
            _state.value = UpdateState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Returns true if [candidate] is strictly newer than [current].
     * Compares dot-separated numeric segments (e.g. "1.2.0" > "1.1.9").
     * Falls back to string inequality for non-numeric tags.
     */
    private fun isNewer(candidate: String, current: String): Boolean {
        val cParts = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val oParts = current.split(".").mapNotNull { it.toIntOrNull() }
        if (cParts.isEmpty() || oParts.isEmpty()) return candidate != current
        val len = maxOf(cParts.size, oParts.size)
        for (i in 0 until len) {
            val c = cParts.getOrElse(i) { 0 }
            val o = oParts.getOrElse(i) { 0 }
            if (c > o) return true
            if (c < o) return false
        }
        return false
    }
}
