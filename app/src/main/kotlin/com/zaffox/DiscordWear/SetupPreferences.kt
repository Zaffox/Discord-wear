package com.zaffox.discordwear

import android.content.Context
import androidx.core.content.edit

object SetupPreferences {
    private const val PREFS_NAME = "discord_wear_prefs"
    private const val KEY_TOKEN  = "discord_token"
    private const val KEY_HIDE_INACCESSIBLE = "hide_inaccessible_channels"

    // ── Vencord-style settings ────────────────────────────────────────────────
    /** Send animated (Nitro) custom emoji as a raw GIF URL instead of the <a:name:id> syntax. */
    private const val KEY_SEND_ANIMATED_AS_GIF = "send_animated_emoji_as_gif"
    /** Show spoiler text blurred until tapped. */
    private const val KEY_SPOILER_REVEAL_ON_TAP = "spoiler_reveal_on_tap"
    /** Show unread mention badges on DM / channel lists. */
    private const val KEY_SHOW_MENTION_BADGES = "show_mention_badges"
    /** Compact message mode (hide avatars). */
    private const val KEY_COMPACT_MODE = "compact_mode"

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_TOKEN, token) }
    }

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_TOKEN) }
    }

    fun getHideInaccessibleChannels(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_INACCESSIBLE, true)

    fun setHideInaccessibleChannels(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_HIDE_INACCESSIBLE, hide) }
    }

    // ── Vencord settings accessors ────────────────────────────────────────────

    fun getSendAnimatedAsGif(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEND_ANIMATED_AS_GIF, false)

    fun setSendAnimatedAsGif(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SEND_ANIMATED_AS_GIF, value) }
    }

    fun getSpoilerRevealOnTap(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPOILER_REVEAL_ON_TAP, true)

    fun setSpoilerRevealOnTap(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SPOILER_REVEAL_ON_TAP, value) }
    }

    fun getShowMentionBadges(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_MENTION_BADGES, true)

    fun setShowMentionBadges(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SHOW_MENTION_BADGES, value) }
    }

    fun getCompactMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPACT_MODE, false)

    fun setCompactMode(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_COMPACT_MODE, value) }
    }

    fun isSetupComplete(context: Context): Boolean = getToken(context) != null
}
