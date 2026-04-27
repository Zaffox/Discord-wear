package com.zaffox.discordwear.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DiscordRestClient(private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://discord.com/api/v10"
    private val jsonMime = "application/json; charset=utf-8".toMediaType()

    private fun buildRequest(path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", token)
            .header("User-Agent", "DiscordWear/1.0 (WearOS)")

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

    private suspend fun delete(path: String): String =
        execute(buildRequest(path).delete().build())

    suspend fun getCurrentUser(): Result<DiscordUser> = runCatching {
        DiscordUser.fromJson(JSONObject(get("/users/@me")))
    }

    suspend fun getGuilds(): Result<List<Guild>> = runCatching {
        Guild.listFromJson(JSONArray(get("/users/@me/guilds?limit=200")))
    }

    suspend fun getGuildMember(guildId: String): Result<GuildMember> = runCatching {
        GuildMember.fromJson(JSONObject(get("/guilds/$guildId/members/@me")))
    }

    suspend fun getGuildMemberRoles(guildId: String, userId: String): Result<List<String>> = runCatching {
        val member = JSONObject(get("/guilds/$guildId/members/$userId"))
        val arr = member.getJSONArray("roles")
        (0 until arr.length()).map { arr.getString(it) }
    }

    suspend fun getGuildRoles(guildId: String): Result<List<GuildRole>> = runCatching {
        val guild = JSONObject(get("/guilds/$guildId"))
        GuildRole.listFromJson(guild.getJSONArray("roles"))
    }

    suspend fun getChannel(channelId: String): Result<Channel> = runCatching {
        Channel.fromJson(JSONObject(get("/channels/$channelId")))
    }

    suspend fun getGuildChannels(
        guildId: String,
        filterInaccessible: Boolean = true
    ): Result<List<CategoryGroup>> = runCatching {
        val raw = Channel.listFromJson(JSONArray(get("/guilds/$guildId/channels")))
        val member = getGuildMember(guildId).getOrNull()
        val roles = getGuildRoles(guildId).getOrNull()

        fun canView(channel: Channel): Boolean {
            if (member == null || roles == null) return true
            val perms = Permissions.effectiveForMember(
                member = member,
                guildId = guildId,
                everyoneRoleId = guildId,
                roles = roles,
                channel = channel
            )
            return Permissions.has(perms, Permissions.VIEW_CHANNEL)
        }

        val categories = raw.filter { it.isCategory }.sortedBy { it.position }
        val textChannels = raw.filter { it.isText }
        val byParent = textChannels.groupBy { it.parentId }
        val groups = mutableListOf<CategoryGroup>()

        val topLevel = byParent[null].orEmpty()
            .map { it.copy(hasAccess = canView(it)) }
            .filter { !filterInaccessible || it.hasAccess }
            .sortedBy { it.position }
        if (topLevel.isNotEmpty()) groups.add(CategoryGroup(category = null, channels = topLevel))

        for (cat in categories) {
            val children = byParent[cat.id].orEmpty()
                .map { it.copy(hasAccess = canView(it)) }
                .filter { !filterInaccessible || it.hasAccess }
                .sortedBy { it.position }
            if (children.isNotEmpty()) groups.add(CategoryGroup(category = cat, channels = children))
        }

        groups
    }

    suspend fun getDmChannels(): Result<List<Channel>> = runCatching {
        Channel.listFromJson(JSONArray(get("/users/@me/channels"))).filter { it.isDm }
    }

    suspend fun getMessages(channelId: String, limit: Int = 50): Result<List<DiscordMessage>> = runCatching {
        val safe = limit.coerceIn(1, 100)
        DiscordMessage.listFromJson(JSONArray(get("/channels/$channelId/messages?limit=$safe")))
    }

    suspend fun sendMessage(channelId: String, content: String): Result<DiscordMessage> = runCatching {
        val body = JSONObject().put("content", content)
        DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", body)))
    }

    suspend fun sendGifAttachment(channelId: String, gifUrl: String): Result<DiscordMessage> = runCatching {
        withContext(Dispatchers.IO) {
            // Fetch GIF bytes from Discord CDN
            val gifBytes = http.newCall(Request.Builder().url(gifUrl).build()).execute()
                .use { it.body?.bytes() } ?: throw IOException("Failed to fetch GIF")

            val payloadJson = JSONObject()
                .put("attachments", org.json.JSONArray().put(
                    JSONObject().put("id", 0).put("filename", "emoji.gif")
                ))
                .toString()

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", null,
                    payloadJson.toRequestBody("application/json".toMediaType()))
                .addFormDataPart("files[0]", "emoji.gif",
                    gifBytes.toRequestBody("image/gif".toMediaType()))
                .build()

            val request = buildRequest("/channels/$channelId/messages").post(multipart).build()
            val responseText = http.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: throw IOException("Empty response")
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $text")
                text
            }
            DiscordMessage.fromJson(JSONObject(responseText))
        }
    }

    suspend fun sendReply(channelId: String, content: String, replyToId: String): Result<DiscordMessage> = runCatching {
        val body = JSONObject()
            .put("content", content)
            .put("message_reference", JSONObject()
                .put("message_id", replyToId)
                .put("channel_id", channelId)
                .put("fail_if_not_exists", false)
            )
        DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", body)))
    }

    suspend fun deleteMessage(channelId: String, messageId: String): Result<Unit> = runCatching {
        delete("/channels/$channelId/messages/$messageId")
        Unit
    }

    suspend fun editMessage(channelId: String, messageId: String, newContent: String): Result<DiscordMessage> = runCatching {
        val body = JSONObject().put("content", newContent)
        val req = buildRequest("/channels/$channelId/messages/$messageId")
            .method("PATCH", body.toString().toRequestBody(jsonMime))
            .build()
        DiscordMessage.fromJson(JSONObject(execute(req)))
    }

    suspend fun sendTyping(channelId: String) {
        runCatching {
            execute(buildRequest("/channels/$channelId/typing")
                .post("".toRequestBody(jsonMime)).build())
        }
    }

    suspend fun getGuildEmojis(guildId: String): Result<List<GuildEmoji>> = runCatching {
        GuildEmoji.listFromJson(JSONArray(get("/guilds/$guildId/emojis")))
    }

    suspend fun getGuildStickers(guildId: String): Result<List<StickerItem>> = runCatching {
        val arr = JSONArray(get("/guilds/$guildId/stickers"))
        (0 until arr.length()).map { StickerItem.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun sendSticker(channelId: String, stickerId: String): Result<DiscordMessage> = runCatching {
        val body = JSONObject().put("sticker_ids", org.json.JSONArray().put(stickerId))
        DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", body)))
    }

    suspend fun addReaction(channelId: String, messageId: String, emojiKey: String): Result<Unit> = runCatching {
        val encoded = java.net.URLEncoder.encode(emojiKey, "UTF-8")
        execute(buildRequest("/channels/$channelId/messages/$messageId/reactions/$encoded/@me")
            .put("".toRequestBody(jsonMime)).build())
        Unit
    }

    suspend fun removeReaction(channelId: String, messageId: String, emojiKey: String): Result<Unit> = runCatching {
        val encoded = java.net.URLEncoder.encode(emojiKey, "UTF-8")
        execute(buildRequest("/channels/$channelId/messages/$messageId/reactions/$encoded/@me")
            .delete().build())
        Unit
    }

    suspend fun ackChannel(channelId: String, messageId: String) {
        runCatching {
            post(
                "/channels/$channelId/messages/$messageId/ack",
                JSONObject().put("token", null as String?)
            )
        }
    }

    suspend fun logout(): Result<Unit> = runCatching {
        post("/auth/logout", JSONObject())
        Unit
    }
}

class DiscordApiException(val httpCode: Int, message: String) :
    IOException("Discord API error $httpCode: $message")
