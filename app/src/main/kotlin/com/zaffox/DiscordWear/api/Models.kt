package com.zaffox.discordwear.api

import org.json.JSONArray
import org.json.JSONObject

// ── User ─────────────────────────────────────────────────────────────────────

data class DiscordUser(
    val id: String,
    val username: String,
    val discriminator: String,
    val globalName: String?,
    val avatarHash: String?
) {
    val displayName: String get() = globalName ?: username
    fun avatarUrl(size: Int = 64): String =
        if (avatarHash != null)
            "https://cdn.discordapp.com/avatars/$id/$avatarHash.png?size=$size"
        else
            "https://cdn.discordapp.com/embed/avatars/${(id.toLongOrNull() ?: 0L) % 5}.png"

    companion object {
        fun fromJson(o: JSONObject) = DiscordUser(
            id            = o.getString("id"),
            username      = o.getString("username"),
            discriminator = o.optString("discriminator", "0"),
            globalName    = o.optString("global_name").takeIf { it.isNotEmpty() },
            avatarHash    = o.optString("avatar").takeIf { it.isNotEmpty() }
        )
    }
}

// ── Guild (Server) ────────────────────────────────────────────────────────────

data class Guild(
    val id: String,
    val name: String,
    val iconHash: String?
) {
    fun iconUrl(size: Int = 64): String =
        if (iconHash != null)
            "https://cdn.discordapp.com/icons/$id/$iconHash.png?size=$size"
        else
            "https://cdn.discordapp.com/embed/avatars/0.png"

    companion object {
        fun fromJson(o: JSONObject) = Guild(
            id       = o.getString("id"),
            name     = o.getString("name"),
            iconHash = o.optString("icon").takeIf { it.isNotEmpty() }
        )

        fun listFromJson(arr: JSONArray): List<Guild> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

// ── Channel ───────────────────────────────────────────────────────────────────

enum class ChannelType(val code: Int) {
    GUILD_TEXT(0), DM(1), GUILD_VOICE(2), GROUP_DM(3),
    GUILD_CATEGORY(4), GUILD_NEWS(5), UNKNOWN(-1);

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

// ── Permission overwrite ──────────────────────────────────────────────────────

/**
 * A single entry in a channel's permission_overwrites array.
 * [type] 0 = role overwrite, 1 = member overwrite.
 * [allow] and [deny] are bitmasks as Long (Discord sends them as strings).
 */
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

/**
 * Minimal guild member — we only need the role IDs and user ID for
 * permission calculation.
 */
data class GuildMember(
    val userId: String,
    val roleIds: List<String>
) {
    companion object {
        fun fromJson(o: JSONObject): GuildMember {
            val user = o.getJSONObject("user")
            val rolesArr = o.getJSONArray("roles")
            val roles = (0 until rolesArr.length()).map { rolesArr.getString(it) }
            return GuildMember(userId = user.getString("id"), roleIds = roles)
        }
    }
}

// ── Guild role ────────────────────────────────────────────────────────────────

data class GuildRole(
    val id: String,
    val permissions: Long  // base permissions bitmask
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

    /**
     * Compute the effective permissions for [member] in [channel].
     *
     * Follows the Discord permission hierarchy:
     *   1. Guild base permissions from @everyone role
     *   2. OR in permissions from member's other roles
     *   3. Apply channel role overwrites (deny then allow)
     *   4. Apply channel member overwrite (deny then allow)
     *
     * Returns the final permissions bitmask.
     */
    fun effectiveForMember(
        member: GuildMember,
        guildId: String,
        everyoneRoleId: String,          // same as guildId in Discord
        roles: List<GuildRole>,
        channel: Channel
    ): Long {
        // Build a map for quick lookup
        val roleMap = roles.associateBy { it.id }

        // 1. Start with @everyone base perms
        var perms = roleMap[everyoneRoleId]?.permissions ?: 0L

        // 2. OR in each of the member's roles
        for (roleId in member.roleIds) {
            perms = perms or (roleMap[roleId]?.permissions ?: 0L)
        }

        // Admins bypass all channel overwrites
        if (perms and ADMINISTRATOR != 0L) return Long.MAX_VALUE

        val overwrites = channel.permissionOverwrites

        // 3. Apply @everyone channel overwrite
        overwrites.firstOrNull { it.id == everyoneRoleId }?.let {
            perms = (perms and it.deny.inv()) or it.allow
        }

        // 4. Apply role overwrites (deny first across all roles, then allow)
        var roleDeny  = 0L
        var roleAllow = 0L
        for (ow in overwrites.filter { it.type == 0 && it.id in member.roleIds }) {
            roleDeny  = roleDeny  or ow.deny
            roleAllow = roleAllow or ow.allow
        }
        perms = (perms and roleDeny.inv()) or roleAllow

        // 5. Apply member-specific overwrite
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
    /** Snowflake ID of the parent category (null for top-level or DMs) */
    val parentId: String?,
    /** Discord position integer — used for ordering within a category */
    val position: Int,
    val permissionOverwrites: List<PermissionOverwrite> = emptyList(),
    /** For DMs — the other participant(s) */
    val recipients: List<DiscordUser> = emptyList()
) {
    val isDm: Boolean        get() = type == ChannelType.DM || type == ChannelType.GROUP_DM
    val isText: Boolean      get() = type == ChannelType.GUILD_TEXT || type == ChannelType.GUILD_NEWS
    val isCategory: Boolean  get() = type == ChannelType.GUILD_CATEGORY

    /** Human-readable display name (DMs show recipient name) */
    val displayName: String
        get() = if (isDm && name.isEmpty())
            recipients.firstOrNull()?.displayName ?: "Unknown"
        else name

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

// ── CategoryGroup — categories with their text channels ──────────────────────

data class CategoryGroup(
    /** Null = channels with no parent category (top-level) */
    val category: Channel?,
    val channels: List<Channel>
)

// ── Attachment ────────────────────────────────────────────────────────────────

data class Attachment(
    val id: String,
    val filename: String,
    val url: String,
    val proxyUrl: String,
    val width: Int?,
    val height: Int?,
    val contentType: String?
) {
    val isImage: Boolean get() =
        contentType?.startsWith("image/") == true ||
        filename.substringAfterLast('.').lowercase() in setOf("png","jpg","jpeg","gif","webp")

    companion object {
        fun fromJson(o: JSONObject) = Attachment(
            id          = o.getString("id"),
            filename    = o.getString("filename"),
            url         = o.getString("url"),
            proxyUrl    = o.optString("proxy_url").ifEmpty { o.getString("url") },
            width       = o.optInt("width").takeIf { it > 0 },
            height      = o.optInt("height").takeIf { it > 0 },
            contentType = o.optString("content_type").takeIf { it.isNotEmpty() }
        )
    }
}

// ── Embed ─────────────────────────────────────────────────────────────────────

data class EmbedImage(val url: String, val proxyUrl: String?)

data class Embed(
    val title: String?,
    val description: String?,
    val url: String?,
    val image: EmbedImage?,
    val thumbnail: EmbedImage?,
    val type: String?          // "image", "gifv", "rich", "video", …
) {
    /** URL to show as the primary visual for this embed */
    val displayImageUrl: String? get() =
        (image?.proxyUrl ?: image?.url)
            ?: (thumbnail?.proxyUrl ?: thumbnail?.url)

    companion object {
        fun fromJson(o: JSONObject): Embed {
            fun parseImg(key: String): EmbedImage? {
                val img = o.optJSONObject(key) ?: return null
                val u = img.optString("url").takeIf { it.isNotEmpty() } ?: return null
                return EmbedImage(u, img.optString("proxy_url").takeIf { it.isNotEmpty() })
            }
            return Embed(
                title       = o.optString("title").takeIf { it.isNotEmpty() },
                description = o.optString("description").takeIf { it.isNotEmpty() },
                url         = o.optString("url").takeIf { it.isNotEmpty() },
                image       = parseImg("image"),
                thumbnail   = parseImg("thumbnail"),
                type        = o.optString("type").takeIf { it.isNotEmpty() }
            )
        }
    }
}

// ── Sticker ───────────────────────────────────────────────────────────────────

data class StickerItem(
    val id: String,
    val name: String,
    /** 1 = PNG, 2 = APNG, 3 = LOTTIE, 4 = GIF */
    val formatType: Int
) {
    /** CDN URL — Lottie stickers use a JSON url, everything else is an image */
    val imageUrl: String get() = when (formatType) {
        3    -> "https://discord.com/stickers/$id.json"   // Lottie — can't display natively
        4    -> "https://media.discordapp.net/stickers/$id.gif"
        else -> "https://media.discordapp.net/stickers/$id.png"
    }
    val isDisplayable: Boolean get() = formatType != 3  // skip Lottie

    companion object {
        fun fromJson(o: JSONObject) = StickerItem(
            id         = o.getString("id"),
            name       = o.getString("name"),
            formatType = o.optInt("format_type", 1)
        )
    }
}

// ── Content parser ────────────────────────────────────────────────────────────

/**
 * Parses Discord message content into typed segments:
 *   - Plain text (possibly containing URLs)
 *   - Custom emoji  <:name:id> / <a:name:id>
 *   - User mention  <@userid> / <@!userid>
 *   - Role mention  <@&roleid>
 *   - Channel mention <#channelid>
 *
 * The caller supplies name-lookup maps so mentions can display human-readable
 * names rather than raw IDs.
 */
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
        "<a?:\\w+:\\d+>" +              // custom emoji
        "|<@!?\\d+>" +                  // user mention
        "|<@&\\d+>" +                   // role mention
        "|<#\\d+>" +                    // channel mention
        "|https?://[^\\s>]+"            // URL
    )

    fun parse(
        content: String,
        userNames:    Map<String, String> = emptyMap(),   // userId → displayName
        roleNames:    Map<String, String> = emptyMap(),   // roleId → name
        channelNames: Map<String, String> = emptyMap()    // channelId → name
    ): List<Part> {
        if (content.isBlank()) return emptyList()
        val parts = mutableListOf<Part>()
        var last = 0

        for (match in TOKEN_RE.findAll(content)) {
            if (match.range.first > last) {
                parts += Part.PlainText(content.substring(last, match.range.first))
            }
            val token = match.value
            when {
                // Custom emoji  <:name:id>  or  <a:name:id>
                token.startsWith("<:") || token.startsWith("<a:") -> {
                    val animated = token.startsWith("<a:")
                    val inner = token.removeSurrounding("<", ">").trimStart('a', ':').trimStart(':')
                    val segments = inner.split(":")
                    if (segments.size == 2) {
                        val name = segments[0]; val id = segments[1]
                        val ext = if (animated) "gif" else "webp"
                        parts += Part.CustomEmoji(
                            name     = name,
                            url      = "https://cdn.discordapp.com/emojis/$id.$ext?size=32",
                            animated = animated
                        )
                    } else parts += Part.PlainText(token)
                }
                // Role mention  <@&id>
                token.startsWith("<@&") -> {
                    val id = token.removeSurrounding("<@&", ">")
                    parts += Part.RoleMention(id, roleNames[id] ?: "@deleted-role")
                }
                // User mention  <@id>  or  <@!id>
                token.startsWith("<@") -> {
                    val id = token.removePrefix("<@!").removePrefix("<@").removeSuffix(">")
                    parts += Part.UserMention(id, userNames[id] ?: "@unknown")
                }
                // Channel mention  <#id>
                token.startsWith("<#") -> {
                    val id = token.removeSurrounding("<#", ">")
                    parts += Part.ChannelMention(id, channelNames[id] ?: "#unknown")
                }
                // URL
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
    val me: Boolean        // true if the current user reacted
)

data class ReactionEmoji(
    val id: String?,       // null for standard Unicode emoji
    val name: String,      // unicode char or custom emoji name
    val animated: Boolean
) {
    /** For PUT/DELETE — Unicode emoji uses the char; custom uses name:id */
    val apiKey: String get() =
        if (id != null) "$name:$id" else name

    /** URL for custom emoji, null for standard unicode */
    val imageUrl: String? get() = if (id != null) {
        val ext = if (animated) "gif" else "webp"
        "https://cdn.discordapp.com/emojis/$id.$ext?size=16"
    } else null

    companion object {
        fun fromJson(o: JSONObject) = ReactionEmoji(
            id       = o.optString("id").takeIf { it.isNotEmpty() },
            name     = o.optString("name").ifEmpty { "?" },
            animated = o.optBoolean("animated", false)
        )
    }
}

// ── GuildEmoji (for picker) ───────────────────────────────────────────────────

data class GuildEmoji(
    val id: String,
    val name: String,
    val animated: Boolean
) {
    val imageUrl: String get() {
        val ext = if (animated) "gif" else "webp"
        return "https://cdn.discordapp.com/emojis/$id.$ext?size=32"
    }
    /** Content string to insert into a message */
    val insertText: String get() = if (animated) "<a:$name:$id>" else "<:$name:$id>"

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
    /** Users mentioned in this message */
    val mentionedUserIds: List<String> = emptyList(),
    /** Role IDs mentioned */
    val mentionedRoleIds: List<String> = emptyList(),
    /** True if @everyone or @here was used */
    val mentionEveryone: Boolean = false,
    /** Guild/server name hint (populated when storing as a ping) */
    val guildId: String? = null
) {
    /** Returns true if [userId] is directly pinged, or @everyone/@here was used */
    fun pingFor(userId: String, memberRoleIds: List<String> = emptyList()): Boolean =
        mentionEveryone ||
        userId in mentionedUserIds ||
        mentionedRoleIds.any { it in memberRoleIds }

    companion object {
        fun fromJson(o: JSONObject): DiscordMessage {
            val mentionsArr = o.optJSONArray("mentions")
            val mentionedUsers = if (mentionsArr != null)
                (0 until mentionsArr.length()).map { mentionsArr.getJSONObject(it).getString("id") }
            else emptyList()

            val roleArr = o.optJSONArray("mention_roles")
            val mentionedRoles = if (roleArr != null)
                (0 until roleArr.length()).map { roleArr.getString(it) }
            else emptyList()

            val attachArr = o.optJSONArray("attachments")
            val attachments = if (attachArr != null)
                (0 until attachArr.length()).map { Attachment.fromJson(attachArr.getJSONObject(it)) }
            else emptyList()

            val embedArr = o.optJSONArray("embeds")
            val embeds = if (embedArr != null)
                (0 until embedArr.length()).map { Embed.fromJson(embedArr.getJSONObject(it)) }
            else emptyList()

            val stickerArr = o.optJSONArray("sticker_items")
            val stickers = if (stickerArr != null)
                (0 until stickerArr.length()).map { StickerItem.fromJson(stickerArr.getJSONObject(it)) }
            else emptyList()

            val reactArr = o.optJSONArray("reactions")
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

            return DiscordMessage(
                id               = o.getString("id"),
                channelId        = o.getString("channel_id"),
                author           = DiscordUser.fromJson(o.getJSONObject("author")),
                content          = o.getString("content"),
                timestamp        = o.getString("timestamp"),
                editedTimestamp  = o.optString("edited_timestamp").takeIf { it.isNotEmpty() },
                attachments      = attachments,
                embeds           = embeds,
                stickers         = stickers,
                reactions        = reactions,
                mentionedUserIds = mentionedUsers,
                mentionedRoleIds = mentionedRoles,
                mentionEveryone  = o.optBoolean("mention_everyone", false),
                guildId          = o.optString("guild_id").takeIf { it.isNotEmpty() }
            )
        }

        fun listFromJson(arr: JSONArray): List<DiscordMessage> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

// ── Ping (a message that mentioned the current user) ─────────────────────────

data class Ping(
    val message: DiscordMessage,
    val channelName: String,
    val guildName: String?   // null for DMs
)
// kept at end — reactions/emojis already defined above
