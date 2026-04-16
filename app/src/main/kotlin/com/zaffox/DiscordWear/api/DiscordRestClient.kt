package com.zaffox.discordwear.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the Discord REST API (v10).
 *
 * All functions are suspend and run on [Dispatchers.IO].
 * They return [Result] so the call-site decides how to handle errors.
 *
 * Usage:
 *   val client = DiscordRestClient(token)
 *   val user   = client.getCurrentUser().getOrThrow()
 */
class DiscordRestClient(private val token: String) {

    // ── HTTP client ───────────────────────────────────────────────────────────

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://discord.com/api/v10"
    private val jsonMime = "application/json; charset=utf-8".toMediaType()

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildRequest(path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", token)           // token already has "Bot " prefix if bot
            .header("User-Agent", "DiscordWear/1.0 (WearOS)")

    /** Execute a request and return the body as a string, or throw on HTTP error. */
    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = http.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val msg = runCatching { JSONObject(body).optString("message", body) }.getOrDefault(body)
            throw DiscordApiException(response.code, msg)
        }
        body
    }

    private suspend fun get(path: String): String =
        execute(buildRequest(path).get().build())

    private suspend fun post(path: String, body: JSONObject): String =
        execute(buildRequest(path)
            .post(body.toString().toRequestBody(jsonMime))
            .build())

    // ── Public API ────────────────────────────────────────────────────────────

    /** GET /users/@me */
    suspend fun getCurrentUser(): Result<DiscordUser> = runCatching {
        DiscordUser.fromJson(JSONObject(get("/users/@me")))
    }

    /** GET /users/@me/guilds — returns up to 200 guilds */
    suspend fun getGuilds(): Result<List<Guild>> = runCatching {
        Guild.listFromJson(JSONArray(get("/users/@me/guilds?limit=200")))
    }

    /** GET /guilds/{guildId}/members/@me — current user's member object */
    suspend fun getGuildMember(guildId: String): Result<GuildMember> = runCatching {
        GuildMember.fromJson(JSONObject(get("/guilds/$guildId/members/@me")))
    }

    /** GET /guilds/{guildId} — includes roles array */
    suspend fun getGuildRoles(guildId: String): Result<List<GuildRole>> = runCatching {
        val guild = JSONObject(get("/guilds/$guildId"))
        GuildRole.listFromJson(guild.getJSONArray("roles"))
    }

    /**
     * GET /guilds/{guildId}/channels — returns channels grouped into
     * [CategoryGroup]s, filtered to only those the current user can see,
     * sorted by Discord position within each group.
     */
    suspend fun getGuildChannels(guildId: String): Result<List<CategoryGroup>> = runCatching {
        val raw = Channel.listFromJson(JSONArray(get("/guilds/$guildId/channels")))

        // Fetch member + roles for permission filtering
        val member = getGuildMember(guildId).getOrNull()
        val roles  = getGuildRoles(guildId).getOrNull()

        fun canView(channel: Channel): Boolean {
            if (member == null || roles == null) return true
            val perms = Permissions.effectiveForMember(
                member         = member,
                guildId        = guildId,
                everyoneRoleId = guildId,
                roles          = roles,
                channel        = channel
            )
            return Permissions.has(perms, Permissions.VIEW_CHANNEL)
        }

        val categories  = raw.filter { it.isCategory }.sortedBy { it.position }
        val textChannels = raw.filter { it.isText && canView(it) }

        // Map category ID → sorted text channels
        val byParent = textChannels.groupBy { it.parentId }

        // Build ordered groups: categories that have at least one visible channel
        val groups = mutableListOf<CategoryGroup>()

        // Top-level channels (no parent category)
        val topLevel = byParent[null].orEmpty().sortedBy { it.position }
        if (topLevel.isNotEmpty()) groups.add(CategoryGroup(category = null, channels = topLevel))

        // Category groups
        for (cat in categories) {
            val children = byParent[cat.id].orEmpty().sortedBy { it.position }
            if (children.isNotEmpty()) groups.add(CategoryGroup(category = cat, channels = children))
        }

        groups
    }

    /** GET /users/@me/channels — DM channels */
    suspend fun getDmChannels(): Result<List<Channel>> = runCatching {
        Channel.listFromJson(JSONArray(get("/users/@me/channels")))
            .filter { it.isDm }
    }

    /**
     * GET /channels/{channelId}/messages
     * Returns up to [limit] messages (max 100), newest first.
     * The UI should reverse the list to show oldest-at-top.
     */
    suspend fun getMessages(channelId: String, limit: Int = 50): Result<List<DiscordMessage>> = runCatching {
        val safe = limit.coerceIn(1, 100)
        DiscordMessage.listFromJson(JSONArray(get("/channels/$channelId/messages?limit=$safe")))
    }

    /**
     * POST /channels/{channelId}/messages — send a text message.
     * Returns the created [DiscordMessage].
     */
    suspend fun sendMessage(channelId: String, content: String): Result<DiscordMessage> = runCatching {
        val body = JSONObject().put("content", content)
        DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", body)))
    }

    /**
     * POST /channels/{channelId}/typing — sends the typing indicator.
     * Fire-and-forget: ignore errors silently.
     */
    suspend fun sendTyping(channelId: String) {
        runCatching {
            execute(buildRequest("/channels/$channelId/typing")
                .post("".toRequestBody(jsonMime))
                .build())
        }
    }

    /** GET /guilds/{guildId}/emojis — custom emojis for the picker */
    suspend fun getGuildEmojis(guildId: String): Result<List<GuildEmoji>> = runCatching {
        GuildEmoji.listFromJson(JSONArray(get("/guilds/$guildId/emojis")))
    }

    /** GET /guilds/{guildId}/stickers — guild-specific stickers */
    suspend fun getGuildStickers(guildId: String): Result<List<StickerItem>> = runCatching {
        val arr = JSONArray(get("/guilds/$guildId/stickers"))
        (0 until arr.length()).map { StickerItem.fromJson(arr.getJSONObject(it)) }
    }

    /**
     * Send a message with a sticker.
     * Discord requires sticker_ids to be sent separately from content.
     */
    suspend fun sendSticker(channelId: String, stickerId: String): Result<DiscordMessage> = runCatching {
        val body = JSONObject().put("sticker_ids", org.json.JSONArray().put(stickerId))
        DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", body)))
    }

    /**
     * PUT /channels/{channelId}/messages/{messageId}/reactions/{emoji}/@me
     * Adds a reaction. [emojiKey] is the URL-encoded emoji identifier.
     */
    suspend fun addReaction(channelId: String, messageId: String, emojiKey: String): Result<Unit> = runCatching {
        val encoded = java.net.URLEncoder.encode(emojiKey, "UTF-8")
        execute(buildRequest("/channels/$channelId/messages/$messageId/reactions/$encoded/@me")
            .put("".toRequestBody(jsonMime))
            .build())
        Unit
    }

    /**
     * DELETE /channels/{channelId}/messages/{messageId}/reactions/{emoji}/@me
     * Removes the current user's reaction.
     */
    suspend fun removeReaction(channelId: String, messageId: String, emojiKey: String): Result<Unit> = runCatching {
        val encoded = java.net.URLEncoder.encode(emojiKey, "UTF-8")
        execute(buildRequest("/channels/$channelId/messages/$messageId/reactions/$encoded/@me")
            .delete()
            .build())
        Unit
    }
}

// ── Exception ─────────────────────────────────────────────────────────────────

class DiscordApiException(val httpCode: Int, message: String) :
    IOException("Discord API error $httpCode: $message")
