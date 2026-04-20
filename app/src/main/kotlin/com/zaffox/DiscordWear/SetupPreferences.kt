package com.zaffox.discordwear

import android.content.Context
import androidx.core.content.edit

object SetupPreferences {
    private const val PREFS_NAME = "discord_wear_prefs"
    private const val KEY_TOKEN  = "discord_token"
    private const val KEY_HIDE_INACCESSIBLE = "hide_inaccessible_channels"

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
            .getBoolean(KEY_HIDE_INACCESSIBLE, true)  // default: hide locked channels

    fun setHideInaccessibleChannels(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_HIDE_INACCESSIBLE, hide) }
    }

    fun isSetupComplete(context: Context): Boolean = getToken(context) != null
}
