package com.zaffox.discordwear

import android.content.Context
import androidx.core.content.edit

object SetupPreferences {
    private const val PREFS_NAME = "discord_wear_prefs"
    private const val KEY_TOKEN = "discord_token"
    private const val KEY_HIDE_INACCESSIBLE = "hide_inaccessible_channels"
    private const val KEY_SEND_ANIMATED_AS_GIF = "send_animated_emoji_as_gif"
    private const val KEY_SPOILER_REVEAL_ON_TAP = "spoiler_reveal_on_tap"
    private const val KEY_SHOW_MENTION_BADGES = "show_mention_badges"
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
    
    fun getSendAnimatedAsGif(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEND_ANIMATED_AS_GIF, true)

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
    private const val KEY_PINNED_SERVERS = "pinned_servers"

    fun getPinnedServers(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED_SERVERS, emptySet()) ?: emptySet()

    fun setPinnedServers(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_PINNED_SERVERS, ids) }
    }

    fun togglePinnedServer(context: Context, guildId: String): Boolean {
        val current = getPinnedServers(context).toMutableSet()
        val pinned = if (current.contains(guildId)) { current.remove(guildId); false }
                     else { current.add(guildId); true }
        setPinnedServers(context, current)
        return pinned
    }

    private const val KEY_HIDDEN_SERVERS = "hidden_servers"

    fun getHiddenServers(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_SERVERS, emptySet()) ?: emptySet()

    fun setHiddenServers(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_HIDDEN_SERVERS, ids) }
    }

    fun toggleHiddenServer(context: Context, guildId: String): Boolean {
        val current = getHiddenServers(context).toMutableSet()
        val hidden = if (current.contains(guildId)) { current.remove(guildId); false }
                     else { current.add(guildId); true }
        setHiddenServers(context, current)
        return hidden
    }

    private const val KEY_HIDDEN_DMS = "hidden_dms"

    fun getHiddenDms(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_DMS, emptySet()) ?: emptySet()

    fun setHiddenDms(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY_HIDDEN_DMS, ids) }
    }

    fun toggleHiddenDm(context: Context, channelId: String): Boolean {
        val current = getHiddenDms(context).toMutableSet()
        val hidden = if (current.contains(channelId)) { current.remove(channelId); false }
                     else { current.add(channelId); true }
        setHiddenDms(context, current)
        return hidden
    }

    fun clearDiscordCache(context: Context) {
        val cache = context.getSharedPreferences("discord_wear_cache", Context.MODE_PRIVATE)
        cache.edit { clear() }
    }

    fun clearAll(context: Context) {
        clearToken(context)
        clearDiscordCache(context)
    }
}
