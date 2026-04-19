package com.zaffox.discordwear.api

import org.json.JSONArray
import org.json.JSONObject

// ── User ─────────────────────────────────────────────────────────────────────

data class DiscordUser(
    val id: String,
    val username: String,
    val discriminator: String,
    val globalName: String?,
    val avatarHash: String?,
    val premiumType: Int = 0,
    val nameplateAsset: String? = null   // e.g. "nameplates/nameplates/twilight/"
) {
    val hasNitro: Boolean get() = premiumType > 0
    val displayName: String get() = globalName ?: username
    fun avatarUrl(size: Int = 64): String? =
        if (avatarHash != null)
            "https://cdn.discordapp.com/avatars/$id/$avatarHash.png?size=$size"
        else null

    /** Full CDN URL for the nameplate background image, or null if none. */
    fun nameplateUrl(): String? =
        if (nameplateAsset != null)
            //"https://cdn.discordapp.com/${nameplateAsset}nameplate.png"
            "https://cdn.discordapp.com/media/v1/collectibles-shop/${nameplateAsset}/static"
        else null

    companion object {
        fun fromJson(o: JSONObject) = DiscordUser(
            id             = o.getString("id"),
            username       = o.optString("username").takeIf { it.isNotEmpty() && it != "null" } ?: "Unknown",
            discriminator  = o.optString("discriminator", "0"),
            globalName     = o.optString("global_name").takeIf { it.isNotEmpty() && it != "null" },
            avatarHash     = o.optString("avatar").takeIf   { it.isNotEmpty() && it != "null" },
            premiumType    = o.optInt("premium_type", 0),
            nameplateAsset = o.optJSONObject("collectibles")
                              ?.optJSONObject("nameplate")
                              ?.optString("sku_id") //discords API is hard for user accounts, not the same api as bots AFAIK for this
                              ?.takeIf { it.isNotEmpty() && it != "null" }
                              ?: o.optString("nameplate_asset").takeIf { it.isNotEmpty() && it != "null" }
        )
    }
}

// ── Presence ──────────────────────────────────────────────────────────────────

enum class OnlineStatus { ONLINE, IDLE, DND, INVISIBLE, OFFLINE }

data class ClientStatus(
    val desktop: OnlineStatus? = null,
    val mobile:  OnlineStatus? = null,
    val web:     OnlineStatus? = null
)

data class UserPresence(
    val userId: String,
    val status: OnlineStatus = OnlineStatus.OFFLINE,
    val clientStatus: ClientStatus = ClientStatus(),
    val customStatusText: String? = null,
    val customStatusEmoji: String? = null  // unicode char or CDN URL for custom emoji
) {
    companion object {
        private fun parseStatus(s: String?) = when (s) {
            "online"    -> OnlineStatus.ONLINE
            "idle"      -> OnlineStatus.IDLE
            "dnd"       -> OnlineStatus.DND
            "invisible" -> OnlineStatus.INVISIBLE
            else        -> OnlineStatus.OFFLINE
        }

        fun fromJson(o: JSONObject): UserPresence {
            val userId = o.optJSONObject("user")?.optString("id") ?: return UserPresence("")
            val status = parseStatus(o.optString("status"))
            val cs = o.optJSONObject("client_status")
            val clientStatus = ClientStatus(
                desktop = parseStatus(cs?.optString("desktop")),
                mobile  = parseStatus(cs?.optString("mobile")),
                web     = parseStatus(cs?.optString("web"))
            )
            // Find custom status activity (type == 4)
            val activities = o.optJSONArray("activities")
            var customText: String? = null
            var customEmoji: String? = null
            if (activities != null) {
                for (i in 0 until activities.length()) {
                    val act = activities.getJSONObject(i)
                    if (act.optInt("type") == 4) {
                        customText  = act.optString("state").takeIf { it.isNotEmpty() && it != "null" }
                        val emojiObj = act.optJSONObject("emoji")
                        customEmoji = if (emojiObj != null) {
                            val eid = emojiObj.optString("id").takeIf { it.isNotEmpty() && it != "null" }
                            if (eid != null) {
                                val animated = emojiObj.optBoolean("animated", false)
                                val ext = if (animated) "gif" else "webp"
                                "https://cdn.discordapp.com/emojis/$eid.$ext?size=16"
                            } else {
                                emojiObj.optString("name").takeIf { it.isNotEmpty() && it != "null" }
                            }
                        } else null
                        break
                    }
                }
            }
            return UserPresence(userId, status, clientStatus, customText, customEmoji)
        }
    }
}



data class Guild(
    val id: String,
    val name: String,
    val iconHash: String?,
    val bannerHash: String? = null
) {
    fun iconUrl(size: Int = 64): String? =
        if (iconHash != null)
            "https://cdn.discordapp.com/icons/$id/$iconHash.png?size=$size"
        else null

    fun bannerUrl(size: Int = 480): String? =
        if (bannerHash != null)
            "https://cdn.discordapp.com/banners/$id/$bannerHash.png?size=$size"
        else null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("icon", iconHash ?: "")
        .put("banner", bannerHash ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Guild(
            id         = o.getString("id"),
            name       = o.getString("name"),
            iconHash   = o.optString("icon").takeIf   { it.isNotEmpty() && it != "null" },
            bannerHash = o.optString("banner").takeIf { it.isNotEmpty() && it != "null" }
        )
        fun listFromJson(arr: JSONArray): List<Guild> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

// ── Channel ───────────────────────────────────────────────────────────────────

enum class ChannelType(val code: Int) {
    GUILD_TEXT(0), DM(1), GUILD_VOICE(2), GROUP_DM(3),
    GUILD_CATEGORY(4), GUILD_NEWS(5), UNKNOWN(-1);
    companion object { fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN }
}

// ── Permission overwrite ──────────────────────────────────────────────────────

data class PermissionOverwrite(
    val id: String,
    val type: Int,
    val allow: Long,
    val deny: Long
) {
    companion object {
        fun fromJson(o: JSONObject) = PermissionOverwrite(
            id    = o.getString("id"),
            type  = o.getInt("type"),
            allow = o.getString("allow").toLongOrNull() ?: 0L,
            deny  = o.getString("deny").toLongOrNull()  ?: 0L
        )
    }
}

// ── Guild member ──────────────────────────────────────────────────────────────

data class GuildMember(
    val userId: String,
    val roleIds: List<String>
) {
    companion object {
        fun fromJson(o: JSONObject): GuildMember {
            val user     = o.getJSONObject("user")
            val rolesArr = o.getJSONArray("roles")
            val roles    = (0 until rolesArr.length()).map { rolesArr.getString(it) }
            return GuildMember(userId = user.getString("id"), roleIds = roles)
        }
    }
}

// ── Guild role ────────────────────────────────────────────────────────────────

data class GuildRole(
    val id: String,
    val permissions: Long
) {
    companion object {
        fun fromJson(o: JSONObject) = GuildRole(
            id          = o.getString("id"),
            permissions = o.getString("permissions").toLongOrNull() ?: 0L
        )
        fun listFromJson(arr: JSONArray): List<GuildRole> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

// ── Permissions helper ────────────────────────────────────────────────────────

object Permissions {
    const val ADMINISTRATOR  = 1L shl 3
    const val VIEW_CHANNEL   = 1L shl 10
    const val SEND_MESSAGES  = 1L shl 11

    fun effectiveForMember(
        member: GuildMember,
        guildId: String,
        everyoneRoleId: String,
        roles: List<GuildRole>,
        channel: Channel
    ): Long {
        val roleMap = roles.associateBy { it.id }
        var perms = roleMap[everyoneRoleId]?.permissions ?: 0L
        for (roleId in member.roleIds) perms = perms or (roleMap[roleId]?.permissions ?: 0L)
        if (perms and ADMINISTRATOR != 0L) return Long.MAX_VALUE

        val overwrites = channel.permissionOverwrites
        overwrites.firstOrNull { it.id == everyoneRoleId }?.let {
            perms = (perms and it.deny.inv()) or it.allow
        }

        var roleDeny  = 0L
        var roleAllow = 0L
        for (ow in overwrites.filter { it.type == 0 && it.id in member.roleIds }) {
            roleDeny  = roleDeny  or ow.deny
            roleAllow = roleAllow or ow.allow
        }
        perms = (perms and roleDeny.inv()) or roleAllow

        overwrites.firstOrNull { it.type == 1 && it.id == member.userId }?.let {
            perms = (perms and it.deny.inv()) or it.allow
        }
        return perms
    }

    fun has(perms: Long, flag: Long) = perms and flag != 0L
}

// ── Channel ───────────────────────────────────────────────────────────────────

data class Channel(
    val id: String,
    val type: ChannelType,
    val guildId: String?,
    val name: String,
    val topic: String?,
    val lastMessageId: String?,
    val parentId: String?,
    val position: Int,
    val permissionOverwrites: List<PermissionOverwrite> = emptyList(),
    val recipients: List<DiscordUser> = emptyList(),
    /** Whether the current user has VIEW_CHANNEL permission. Default true (unknown). */
    val hasAccess: Boolean = true
) {
    val isDm: Boolean       get() = type == ChannelType.DM || type == ChannelType.GROUP_DM
    val isText: Boolean     get() = type == ChannelType.GUILD_TEXT || type == ChannelType.GUILD_NEWS
    val isCategory: Boolean get() = type == ChannelType.GUILD_CATEGORY

    val displayName: String
        get() = if (isDm && name.isEmpty())
            recipients.firstOrNull()?.displayName ?: "Unknown"
        else name

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.code)
        .put("guild_id", guildId ?: "")
        .put("name", name)
        .put("topic", topic ?: "")
        .put("last_message_id", lastMessageId ?: "")
        .put("parent_id", parentId ?: "")
        .put("position", position)
        .put("recipients", JSONArray(recipients.map { u ->
            JSONObject().put("id", u.id).put("username", u.username)
                .put("global_name", u.globalName ?: "").put("avatar", u.avatarHash ?: "")
                .put("nameplate_asset", u.nameplateAsset ?: "")
        }))

    companion object {
        fun fromJson(o: JSONObject): Channel {
            val recipientsArr = o.optJSONArray("recipients")
            val recipients = if (recipientsArr != null)
                (0 until recipientsArr.length()).map { DiscordUser.fromJson(recipientsArr.getJSONObject(it)) }
            else emptyList()

            val owArr = o.optJSONArray("permission_overwrites")
            val overwrites = if (owArr != null)
                (0 until owArr.length()).map { PermissionOverwrite.fromJson(owArr.getJSONObject(it)) }
            else emptyList()

            return Channel(
                id                   = o.getString("id"),
                type                 = ChannelType.from(o.getInt("type")),
                guildId              = o.optString("guild_id").takeIf { it.isNotEmpty() },
                name                 = o.optString("name"),
                topic                = o.optString("topic").takeIf { it.isNotEmpty() },
                lastMessageId        = o.optString("last_message_id").takeIf { it.isNotEmpty() },
                parentId             = o.optString("parent_id").takeIf { it.isNotEmpty() },
                position             = o.optInt("position", 0),
                permissionOverwrites = overwrites,
                recipients           = recipients
            )
        }
        fun listFromJson(arr: JSONArray): List<Channel> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

data class CategoryGroup(
    val category: Channel?,
    val channels: List<Channel>
)

// ── Attachment ────────────────────────────────────────────────────────────────

data class Attachment(
    val id: String,
    val filename: String,
    val url: String,
    val proxyUrl: String,
    val contentType: String?,
    val width: Int?,
    val height: Int?
) {
    val isImage: Boolean get() = contentType?.startsWith("image/") == true
        || filename.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg")
            || it.endsWith(".jpeg") || it.endsWith(".gif") || it.endsWith(".webp") }

    val isVideo: Boolean get() = contentType?.startsWith("video/") == true
        || filename.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov")
            || it.endsWith(".webm") || it.endsWith(".mkv") || it.endsWith(".avi") }

    val isAudio: Boolean get() = contentType?.startsWith("audio/") == true
        || filename.lowercase().let { it.endsWith(".mp3") || it.endsWith(".ogg")
            || it.endsWith(".wav") || it.endsWith(".flac") || it.endsWith(".m4a") }

    companion object {
        fun fromJson(o: JSONObject) = Attachment(
            id          = o.getString("id"),
            filename    = o.getString("filename"),
            url         = o.getString("url"),
            proxyUrl    = o.getString("proxy_url"),
            contentType = o.optString("content_type").takeIf { it.isNotEmpty() },
            width       = if (o.has("width")) o.getInt("width") else null,
            height      = if (o.has("height")) o.getInt("height") else null
        )
    }
}

// ── Embed ─────────────────────────────────────────────────────────────────────

data class Embed(
    val type: String,
    val title: String?,
    val description: String?,
    val url: String?,
    val color: Int?,
    val imageUrl: String?,
    val thumbnailUrl: String?,
    val videoUrl: String?,
    val authorName: String?,
    val footerText: String?
) {
    val displayImageUrl: String? get() = imageUrl ?: thumbnailUrl

    companion object {
        fun fromJson(o: JSONObject) = Embed(
            type         = o.optString("type", "rich"),
            title        = o.optString("title").takeIf { it.isNotEmpty() },
            description  = o.optString("description").takeIf { it.isNotEmpty() },
            url          = o.optString("url").takeIf { it.isNotEmpty() },
            color        = if (o.has("color")) o.getInt("color") else null,
            imageUrl     = o.optJSONObject("image")?.optString("url")?.takeIf { it.isNotEmpty() },
            thumbnailUrl = o.optJSONObject("thumbnail")?.optString("url")?.takeIf { it.isNotEmpty() },
            videoUrl     = o.optJSONObject("video")?.optString("url")?.takeIf { it.isNotEmpty() },
            authorName   = o.optJSONObject("author")?.optString("name")?.takeIf { it.isNotEmpty() },
            footerText   = o.optJSONObject("footer")?.optString("text")?.takeIf { it.isNotEmpty() }
        )
    }
}

// ── StickerItem ───────────────────────────────────────────────────────────────

data class StickerItem(
    val id: String,
    val name: String,
    val formatType: Int
) {
    val isDisplayable: Boolean get() = formatType in listOf(1, 2, 3)
    val imageUrl: String get() {
        val ext = when (formatType) {
            2    -> "gif"
            3    -> "json"
            else -> "png"
        }
        return "https://media.discordapp.net/stickers/$id.$ext?size=80"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("format_type", formatType)

    companion object {
        fun fromJson(o: JSONObject) = StickerItem(
            id         = o.getString("id"),
            name       = o.getString("name"),
            formatType = o.optInt("format_type", 1)
        )
    }
}

// ── Content parser ────────────────────────────────────────────────────────────

object ContentParser {

    sealed class Part {
        data class PlainText(val text: String) : Part()
        data class CustomEmoji(val name: String, val url: String, val animated: Boolean) : Part()
        data class UserMention(val userId: String, val displayName: String) : Part()
        data class RoleMention(val roleId: String, val roleName: String) : Part()
        data class ChannelMention(val channelId: String, val channelName: String) : Part()
        data class Link(val url: String) : Part()
    }

    private val TOKEN_RE = Regex(
        "<a?:\\w+:\\d+>" +
        "|<@!?\\d+>" +
        "|<@&\\d+>" +
        "|<#\\d+>" +
        "|https?://[^\\s>]+"
    )

    fun parse(
        content: String,
        userNames:    Map<String, String> = emptyMap(),
        roleNames:    Map<String, String> = emptyMap(),
        channelNames: Map<String, String> = emptyMap()
    ): List<Part> {
        if (content.isBlank()) return emptyList()
        val parts = mutableListOf<Part>()
        var last = 0

        for (match in TOKEN_RE.findAll(content)) {
            if (match.range.first > last)
                parts += Part.PlainText(content.substring(last, match.range.first))
            val token = match.value
            when {
                token.startsWith("<:") || token.startsWith("<a:") -> {
                    val animated = token.startsWith("<a:")
                    val inner    = token.removeSurrounding("<", ">").trimStart('a', ':').trimStart(':')
                    val segments = inner.split(":")
                    if (segments.size == 2) {
                        val name = segments[0]; val id = segments[1]
                        val ext = if (animated) "gif" else "webp"
                        parts += Part.CustomEmoji(name, "https://cdn.discordapp.com/emojis/$id.$ext?size=32", animated)
                    } else parts += Part.PlainText(token)
                }
                token.startsWith("<@&") -> {
                    val id = token.removeSurrounding("<@&", ">")
                    parts += Part.RoleMention(id, roleNames[id] ?: "@deleted-role")
                }
                token.startsWith("<@") -> {
                    val id = token.removePrefix("<@!").removePrefix("<@").removeSuffix(">")
                    parts += Part.UserMention(id, userNames[id] ?: "@unknown")
                }
                token.startsWith("<#") -> {
                    val id = token.removeSurrounding("<#", ">")
                    parts += Part.ChannelMention(id, channelNames[id] ?: "#unknown")
                }
                token.startsWith("http") -> parts += Part.Link(token)
                else -> parts += Part.PlainText(token)
            }
            last = match.range.last + 1
        }
        if (last < content.length) parts += Part.PlainText(content.substring(last))
        return parts.filter { it !is Part.PlainText || (it as Part.PlainText).text.isNotEmpty() }
    }
}

// ── Reaction ──────────────────────────────────────────────────────────────────

data class Reaction(
    val emoji: ReactionEmoji,
    val count: Int,
    val me: Boolean
)

data class ReactionEmoji(
    val id: String?,
    val name: String,
    val animated: Boolean
) {
    val apiKey: String get() = if (id != null) "$name:$id" else name
    val imageUrl: String? get() = if (id != null) {
        val ext = if (animated) "gif" else "webp"
        "https://cdn.discordapp.com/emojis/$id.$ext?size=16"
    } else null

    companion object {
        fun fromJson(o: JSONObject) = ReactionEmoji(
            // Discord sends id as JSON null (or the literal string "null") for unicode emoji.
            // optString() converts JSON null to the string "null" — filter both cases out.
            id       = o.optString("id").takeIf { it.isNotEmpty() && it != "null" },
            name     = o.optString("name").ifEmpty { "?" },
            animated = o.optBoolean("animated", false)
        )
    }
}

// ── GuildEmoji ────────────────────────────────────────────────────────────────

data class GuildEmoji(
    val id: String,
    val name: String,
    val animated: Boolean
) {
    val imageUrl: String get() {
        val ext = if (animated) "gif" else "webp"
        return "https://cdn.discordapp.com/emojis/$id.$ext?size=32"
    }
    val insertText: String get() = if (animated) "<a:$name:$id>" else "<:$name:$id>"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("animated", animated)

    companion object {
        fun fromJson(o: JSONObject) = GuildEmoji(
            id       = o.getString("id"),
            name     = o.getString("name"),
            animated = o.optBoolean("animated", false)
        )
        fun listFromJson(arr: JSONArray): List<GuildEmoji> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

// ── Message ───────────────────────────────────────────────────────────────────

data class DiscordMessage(
    val id: String,
    val channelId: String,
    val author: DiscordUser,
    val content: String,
    val timestamp: String,
    val editedTimestamp: String?,
    val attachments: List<Attachment> = emptyList(),
    val embeds: List<Embed> = emptyList(),
    val stickers: List<StickerItem> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val mentionedUsers: List<DiscordUser> = emptyList(),
    val mentionedUserIds: List<String> = emptyList(),
    val mentionedRoleIds: List<String> = emptyList(),
    val mentionEveryone: Boolean = false,
    val guildId: String? = null,
    /** Message type: 0=default, 19=reply, 23=forward */
    val type: Int = 0,
    /** The message being replied to (type=19) */
    val referencedMessage: DiscordMessage? = null,
    /** Forwarded message snapshot content (type=23) */
    val forwardedContent: String? = null,
    val forwardedAuthor: DiscordUser? = null,
    val forwardedAttachments: List<Attachment> = emptyList(),
    val forwardedEmbeds: List<Embed> = emptyList()
) {
    fun pingFor(userId: String, memberRoleIds: List<String> = emptyList()): Boolean =
        mentionEveryone ||
        userId in mentionedUserIds ||
        mentionedRoleIds.any { it in memberRoleIds }

    companion object {
        fun fromJson(o: JSONObject): DiscordMessage {
            val mentionsArr     = o.optJSONArray("mentions")
            val mentionedUsers  = if (mentionsArr != null)
                (0 until mentionsArr.length()).map { DiscordUser.fromJson(mentionsArr.getJSONObject(it)) }
            else emptyList()
            val mentionedUserIds = mentionedUsers.map { it.id }

            val roleArr          = o.optJSONArray("mention_roles")
            val mentionedRoles   = if (roleArr != null)
                (0 until roleArr.length()).map { roleArr.getString(it) }
            else emptyList()

            val attachArr   = o.optJSONArray("attachments")
            val attachments = if (attachArr != null)
                (0 until attachArr.length()).map { Attachment.fromJson(attachArr.getJSONObject(it)) }
            else emptyList()

            val embedArr = o.optJSONArray("embeds")
            val embeds   = if (embedArr != null)
                (0 until embedArr.length()).map { Embed.fromJson(embedArr.getJSONObject(it)) }
            else emptyList()

            val stickerArr = o.optJSONArray("sticker_items")
            val stickers   = if (stickerArr != null)
                (0 until stickerArr.length()).map { StickerItem.fromJson(stickerArr.getJSONObject(it)) }
            else emptyList()

            val reactArr  = o.optJSONArray("reactions")
            val reactions = if (reactArr != null)
                (0 until reactArr.length()).mapNotNull { i ->
                    runCatching {
                        val r = reactArr.getJSONObject(i)
                        Reaction(
                            emoji = ReactionEmoji.fromJson(r.getJSONObject("emoji")),
                            count = r.getInt("count"),
                            me    = r.optBoolean("me", false)
                        )
                    }.getOrNull()
                }
            else emptyList()

            val refMsgObj = o.optJSONObject("referenced_message")
            val refMsg    = if (refMsgObj != null) runCatching { fromJson(refMsgObj) }.getOrNull() else null

            val msgType        = o.optInt("type", 0)
            var fwdContent: String?              = null
            var fwdAuthor: DiscordUser?          = null
            var fwdAttachments: List<Attachment> = emptyList()
            var fwdEmbeds: List<Embed>           = emptyList()

            if (msgType == 23) {
                val snapshots = o.optJSONArray("message_snapshots")
                if (snapshots != null) {
                    for (i in 0 until snapshots.length()) {
                        val snapMsg = snapshots.getJSONObject(i)
                            .optJSONObject("message") ?: continue

                        fwdContent = snapMsg.optString("content").takeIf { it.isNotEmpty() }

                        // Note: message_snapshots do NOT include author — author must come
                        // from referenced_message, which is handled at display time.
                        fwdAuthor = null

                        // Parse forwarded attachments
                        val fwdAttachArr = snapMsg.optJSONArray("attachments")
                        if (fwdAttachArr != null) {
                            fwdAttachments = (0 until fwdAttachArr.length())
                                .map { Attachment.fromJson(fwdAttachArr.getJSONObject(it)) }
                        }

                        // Parse forwarded embeds
                        val fwdEmbedArr = snapMsg.optJSONArray("embeds")
                        if (fwdEmbedArr != null) {
                            fwdEmbeds = (0 until fwdEmbedArr.length())
                                .map { Embed.fromJson(fwdEmbedArr.getJSONObject(it)) }
                        }

                        // Stop at first valid snapshot
                        if (fwdContent != null || fwdAuthor != null ||
                            fwdAttachments.isNotEmpty() || fwdEmbeds.isNotEmpty()) break
                    }
                }
            }

            return DiscordMessage(
                id                   = o.getString("id"),
                channelId            = o.getString("channel_id"),
                author               = DiscordUser.fromJson(o.getJSONObject("author")),
                content              = o.optString("content", ""),
                timestamp            = o.getString("timestamp"),
                editedTimestamp      = o.optString("edited_timestamp").takeIf { it.isNotEmpty() },
                attachments          = attachments,
                embeds               = embeds,
                stickers             = stickers,
                reactions            = reactions,
                mentionedUsers       = mentionedUsers,
                mentionedUserIds     = mentionedUserIds,
                mentionedRoleIds     = mentionedRoles,
                mentionEveryone      = o.optBoolean("mention_everyone", false),
                guildId              = o.optString("guild_id").takeIf { it.isNotEmpty() },
                type                 = msgType,
                referencedMessage    = refMsg,
                forwardedContent     = fwdContent,
                forwardedAuthor      = fwdAuthor,
                forwardedAttachments = fwdAttachments,
                forwardedEmbeds      = fwdEmbeds
            )
        }

        fun listFromJson(arr: JSONArray): List<DiscordMessage> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

// ── Ping ─────────────────────────────────────────────────────────────────────

data class Ping(
    val message: DiscordMessage,
    val channelName: String,
    val guildName: String?
)
