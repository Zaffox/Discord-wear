package com.zaffox.discordwear

import android.app.Application
import com.zaffox.discordwear.api.DiscordRepository

class DiscordWearApp : Application() {

    /** Null until a token is provided via [initRepository]. */
    var repository: DiscordRepository? = null
        private set

    /**
     * Call this once the user's token is known (after setup or on cold start
     * when a saved token is found in [SetupPreferences]).
     */
    fun initRepository(token: String) {
        repository?.disconnect()
        val repo = DiscordRepository(token)
        repo.connect()
        repository = repo
    }

    fun clearRepository() {
        repository?.disconnect()
        repository = null
    }
}

/** Convenience extension so any Context can reach the app. */
val android.content.Context.discordApp: DiscordWearApp
    get() = applicationContext as DiscordWearApp
