package com.zaffox.discordwear.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth — owns REST client + Gateway, exposes StateFlows.
 * Also persists guilds, channels, emojis, and stickers to SharedPreferences
 * so they are available instantly on next launch without waiting for network.
 */
class DiscordRepository(token: String, private val context: Context? = null) {

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rest            = DiscordRestClient(token)
    private val gateway = DiscordGateway(token)

    private val prefs: SharedPreferences? = context?.getSharedPreferences("discord_wear_cache", Context.MODE_PRIVATE)

    // ── Exposed state ─────────────────────────────────────────────────────────

    private val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser.asStateFlow()

    @Volatile private var currentUserId: String? = null

    private val _guilds = MutableStateFlow<List<Guild>>(loadCachedGuilds())
    val guilds: StateFlow<List<Guild>> = _guilds.asStateFlow()

    private val _dmChannels = MutableStateFlow<List<Channel>>(loadCachedDmChannels())
    val dmChannels: StateFlow<List<Channel>> = _dmChannels.asStateFlow()

    /** channelId → messages (oldest-first) */
    private val _messages = MutableStateFlow<Map<String, List<DiscordMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<DiscordMessage>>> = _messages.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _pings = MutableStateFlow<List<Ping>>(emptyList())
    val pings: StateFlow<List<Ping>> = _pings.asStateFlow()

    /**
     * channelId → last-read messageId, populated from the READY gateway event.
     * Used by ChatScreen to scroll to the first unread message.
     */
    private val _readState = MutableStateFlow<Map<String, String>>(emptyMap())
    val readState: StateFlow<Map<String, String>> = _readState.asStateFlow()

    // Emoji / sticker cache: guildId → list
    private val emojiCache  = mutableMapOf<String, List<GuildEmoji>>()
    private val stickerCache = mutableMapOf<String, List<StickerItem>>()

    private val channelNameCache  = mutableMapOf<String, String>()
    private val channelGuildCache = mutableMapOf<String, String>()

    val gatewayEvents = gateway.events

    fun getChannelNames(): Map<String, String> = channelNameCache.toMap()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun connect() {
        gateway.connect()
        observeGatewayEvents()
        scope.launch { refreshCurrentUser() }
        scope.launch { refreshGuilds() }
        scope.launch { refreshDmChannels() }
    }

    fun disconnect() = gateway.disconnect()

    // ── Refresh helpers ───────────────────────────────────────────────────────

    suspend fun refreshCurrentUser() {
        rest.getCurrentUser().onSuccess {
            _currentUser.value = it
            currentUserId = it.id
        }
    }

    suspend fun refreshGuilds() {
        rest.getGuilds().onSuccess { list ->
            _guilds.value = list
            saveGuilds(list)
        }
    }

    suspend fun refreshDmChannels() {
        rest.getDmChannels().onSuccess { list ->
            _dmChannels.value = list
            list.forEach { ch -> channelNameCache[ch.id] = ch.displayName }
            saveDmChannels(list)
        }
    }

    fun cacheChannelNames(groups: List<CategoryGroup>) {
        groups.forEach { g -> g.channels.forEach { ch -> channelNameCache[ch.id] = ch.name } }
    }

    /** Returns cached emojis immediately, fetches fresh copy if not cached. */
    suspend fun getGuildEmojis(guildId: String): List<GuildEmoji> {
        emojiCache[guildId]?.let { return it }
        val loaded = loadCachedEmojis(guildId)
        if (loaded.isNotEmpty()) { emojiCache[guildId] = loaded; return loaded }
        rest.getGuildEmojis(guildId).onSuccess { list ->
            emojiCache[guildId] = list
            saveEmojis(guildId, list)
            return list
        }
        return emptyList()
    }

    /** Returns cached stickers immediately, fetches fresh copy if not cached. */
    suspend fun getGuildStickers(guildId: String): List<StickerItem> {
        stickerCache[guildId]?.let { return it }
        val loaded = loadCachedStickers(guildId)
        if (loaded.isNotEmpty()) { stickerCache[guildId] = loaded; return loaded }
        rest.getGuildStickers(guildId).onSuccess { list ->
            val displayable = list.filter { it.isDisplayable }
            stickerCache[guildId] = displayable
            saveStickers(guildId, displayable)
            return displayable
        }
        return emptyList()
    }

    /** Load cached channels for a guild (used to populate list before network response). */
    fun getCachedChannels(guildId: String, filterInaccessible: Boolean): List<CategoryGroup>? = runCatching {
        val json = prefs?.getString("channels_$guildId", null) ?: return null
        val arr = JSONArray(json)
        val allChannels = Channel.listFromJson(arr)
        val textChannels = allChannels.filter { it.isText }
        val categories   = allChannels.filter { it.isCategory }.sortedBy { it.position }
        val byParent     = textChannels.groupBy { it.parentId }
        val groups       = mutableListOf<CategoryGroup>()
        val topLevel = byParent[null].orEmpty()
            .filter { !filterInaccessible || it.hasAccess }.sortedBy { it.position }
        if (topLevel.isNotEmpty()) groups.add(CategoryGroup(null, topLevel))
        for (cat in categories) {
            val children = byParent[cat.id].orEmpty()
                .filter { !filterInaccessible || it.hasAccess }.sortedBy { it.position }
            if (children.isNotEmpty()) groups.add(CategoryGroup(cat, children))
        }
        groups.ifEmpty { null }
    }.getOrNull()

    /** Persist guild channels to disk. */
    fun saveChannels(guildId: String, groups: List<CategoryGroup>) = runCatching {
        val allChannels = groups.flatMap { g -> listOfNotNull(g.category) + g.channels }
        prefs?.edit()?.putString("channels_$guildId", JSONArray(allChannels.map { it.toJson() }).toString())?.apply()
    }

    /** Fetch messages for a channel and cache them. */
    suspend fun loadMessages(channelId: String) {
        rest.getMessages(channelId).onSuccess { msgs ->
            _messages.update { it + (channelId to msgs.reversed()) }
        }
    }

    suspend fun sendMessage(channelId: String, content: String): Result<DiscordMessage> {
        val result = rest.sendMessage(channelId, content)
        result.onSuccess { msg ->
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun sendSticker(channelId: String, stickerId: String): Result<DiscordMessage> {
        val result = rest.sendSticker(channelId, stickerId)
        result.onSuccess { msg ->
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun sendReply(channelId: String, content: String, replyToId: String): Result<DiscordMessage> {
        val result = rest.sendReply(channelId, content, replyToId)
        result.onSuccess { msg ->
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun editMessage(channelId: String, messageId: String, newContent: String): Result<DiscordMessage> {
        val result = rest.editMessage(channelId, messageId, newContent)
        result.onSuccess { updated ->
            _messages.update { current ->
                val list = current[channelId]?.map { if (it.id == updated.id) updated else it }
                    ?: return@update current
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun deleteMessage(channelId: String, messageId: String): Result<Unit> {
        val result = rest.deleteMessage(channelId, messageId)
        result.onSuccess {
            _messages.update { current ->
                val list = current[channelId]?.filter { it.id != messageId }
                    ?: return@update current
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun toggleReaction(channelId: String, messageId: String, emoji: ReactionEmoji) {
        val msg = _messages.value[channelId]?.firstOrNull { it.id == messageId } ?: return
        val existing = msg.reactions.firstOrNull { it.emoji.apiKey == emoji.apiKey }
        if (existing?.me == true) {
            rest.removeReaction(channelId, messageId, emoji.apiKey)
            updateReactionLocally(channelId, messageId, emoji, delta = -1, me = false)
        } else {
            rest.addReaction(channelId, messageId, emoji.apiKey)
            updateReactionLocally(channelId, messageId, emoji, delta = +1, me = true)
        }
    }

    private fun updateReactionLocally(
        channelId: String, messageId: String,
        emoji: ReactionEmoji, delta: Int, me: Boolean
    ) {
        _messages.update { current ->
            val list = current[channelId]?.map { msg ->
                if (msg.id != messageId) return@map msg
                val existing = msg.reactions.firstOrNull { it.emoji.apiKey == emoji.apiKey }
                val newReactions = if (existing != null) {
                    val newCount = existing.count + delta
                    if (newCount <= 0) msg.reactions.filter { it.emoji.apiKey != emoji.apiKey }
                    else msg.reactions.map {
                        if (it.emoji.apiKey == emoji.apiKey) it.copy(count = newCount, me = me) else it
                    }
                } else {
                    msg.reactions + Reaction(emoji, 1, me = true)
                }
                msg.copy(reactions = newReactions)
            } ?: return@update current
            current + (channelId to list)
        }
    }

    // ── Gateway event handling ────────────────────────────────────────────────

    private fun observeGatewayEvents() {
        scope.launch {
            gateway.events.collect { event ->
                when (event) {
                    is GatewayEvent.Ready -> {
                        _currentUser.value = event.user
                        currentUserId = event.user.id
                        if (event.readState.isNotEmpty()) {
                            _readState.value = event.readState
                        }
                    }

                    is GatewayEvent.MessageCreate -> {
                        val msg = event.message
                        _messages.update { current ->
                            val list = current[msg.channelId]?.toMutableList() ?: return@update current
                            if (list.none { it.id == msg.id }) list.add(msg)
                            current + (msg.channelId to list)
                        }
                        msg.guildId?.let { channelGuildCache[msg.channelId] = it }
                        val myId = currentUserId
                        if (myId != null && msg.author.id != myId && msg.pingFor(myId)) {
                            val channelName = channelNameCache[msg.channelId] ?: msg.channelId
                            val guildName   = msg.guildId?.let { gid ->
                                _guilds.value.firstOrNull { it.id == gid }?.name
                            }
                            _pings.update { current ->
                                (listOf(Ping(msg, channelName, guildName)) + current).take(5)
                            }
                        }
                        _typing.update { current ->
                            val users = current[msg.channelId].orEmpty() - msg.author.id
                            if (users.isEmpty()) current - msg.channelId
                            else current + (msg.channelId to users)
                        }
                    }

                    is GatewayEvent.MessageUpdate -> {
                        val updated = event.message
                        _messages.update { current ->
                            val list = current[updated.channelId]?.map {
                                if (it.id == updated.id) updated else it
                            } ?: return@update current
                            current + (updated.channelId to list)
                        }
                    }

                    is GatewayEvent.MessageDelete -> {
                        _messages.update { current ->
                            val list = current[event.channelId]?.filter { it.id != event.id }
                                ?: return@update current
                            current + (event.channelId to list)
                        }
                    }

                    is GatewayEvent.ReactionAdd -> {
                        updateReactionLocally(event.channelId, event.messageId, event.emoji, +1, event.userId == currentUserId)
                    }

                    is GatewayEvent.ReactionRemove -> {
                        updateReactionLocally(event.channelId, event.messageId, event.emoji, -1, false)
                    }

                    is GatewayEvent.TypingStart -> {
                        _typing.update { current ->
                            val users = (current[event.channelId] ?: emptySet()) + event.userId
                            current + (event.channelId to users)
                        }
                    }

                    is GatewayEvent.Unknown -> {}
                }
            }
        }
    }

    // ── Disk cache helpers ────────────────────────────────────────────────────

    private fun loadCachedGuilds(): List<Guild> = runCatching {
        val json = prefs?.getString("guilds", null) ?: return emptyList()
        val arr = JSONArray(json)
        (0 until arr.length()).map { Guild.fromJson(arr.getJSONObject(it)) }
    }.getOrElse { emptyList() }

    private fun saveGuilds(list: List<Guild>) = runCatching {
        prefs?.edit()?.putString("guilds", JSONArray(list.map { it.toJson() }).toString())?.apply()
    }

    private fun loadCachedDmChannels(): List<Channel> = runCatching {
        val json = prefs?.getString("dm_channels", null) ?: return emptyList()
        Channel.listFromJson(JSONArray(json))
    }.getOrElse { emptyList() }

    private fun saveDmChannels(list: List<Channel>) = runCatching {
        prefs?.edit()?.putString("dm_channels", JSONArray(list.map { it.toJson() }).toString())?.apply()
    }

    private fun loadCachedEmojis(guildId: String): List<GuildEmoji> = runCatching {
        val json = prefs?.getString("emojis_$guildId", null) ?: return emptyList()
        val arr = JSONArray(json)
        (0 until arr.length()).map { GuildEmoji.fromJson(arr.getJSONObject(it)) }
    }.getOrElse { emptyList() }

    private fun saveEmojis(guildId: String, list: List<GuildEmoji>) = runCatching {
        prefs?.edit()?.putString("emojis_$guildId", JSONArray(list.map { it.toJson() }).toString())?.apply()
    }

    private fun loadCachedStickers(guildId: String): List<StickerItem> = runCatching {
        val json = prefs?.getString("stickers_$guildId", null) ?: return emptyList()
        val arr = JSONArray(json)
        (0 until arr.length()).map { StickerItem.fromJson(arr.getJSONObject(it)) }
    }.getOrElse { emptyList() }

    private fun saveStickers(guildId: String, list: List<StickerItem>) = runCatching {
        prefs?.edit()?.putString("stickers_$guildId", JSONArray(list.map { it.toJson() }).toString())?.apply()
    }
}
