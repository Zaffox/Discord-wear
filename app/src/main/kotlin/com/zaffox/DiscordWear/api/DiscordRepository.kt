package com.zaffox.discordwear.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class DiscordRepository(token: String, private val context: Context? = null) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rest = DiscordRestClient(token)
    private val gateway = DiscordGateway(token)

    private val prefs: SharedPreferences? = context?.getSharedPreferences("discord_wear_cache", Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser.asStateFlow()

    @Volatile private var currentUserId: String? = null

    private val _guilds = MutableStateFlow<List<Guild>>(loadCachedGuilds())
    val guilds: StateFlow<List<Guild>> = _guilds.asStateFlow()

    private val _dmChannels = MutableStateFlow<List<Channel>>(loadCachedDmChannels())
    val dmChannels: StateFlow<List<Channel>> = _dmChannels.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<DiscordMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<DiscordMessage>>> = _messages.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _pings = MutableStateFlow<List<Ping>>(emptyList())
    val pings: StateFlow<List<Ping>> = _pings.asStateFlow()

    private val _readState = MutableStateFlow<Map<String, ChannelUnreadState>>(emptyMap())
    val readState: StateFlow<Map<String, ChannelUnreadState>> = _readState.asStateFlow()

    val totalMentionCount: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0).also {}.let {
        _readState
    }.let { MutableStateFlow(0) }
    private val _totalMentions = kotlinx.coroutines.flow.MutableStateFlow(0)
    val totalMentions: StateFlow<Int> = _totalMentions.asStateFlow()
    private val emojiCache = mutableMapOf<String, List<GuildEmoji>>()
    private val stickerCache = mutableMapOf<String, List<StickerItem>>()
    private val channelNameCache = mutableMapOf<String, String>()
    private val channelGuildCache = mutableMapOf<String, String>()
    private val typingJobs = mutableMapOf<String, Job>()
    private val lastSentAt = mutableMapOf<String, Long>()

    fun slowModeRemainingSeconds(channelId: String): Int {
        val ch = channelCache[channelId] ?: return 0
        val intervalMs = ch.slowModeSeconds * 1000L
        if (intervalMs <= 0) return 0
        val lastMs = lastSentAt[channelId] ?: return 0
        val elapsed = System.currentTimeMillis() - lastMs
        val remaining = intervalMs - elapsed
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    fun getSlowModeSeconds(channelId: String): Int =
        channelCache[channelId]?.slowModeSeconds ?: 0

    private val userDisplayNames = mutableMapOf<String, String>()
    private val myRolesByGuild = mutableMapOf<String, List<String>>()
    private val channelCache = mutableMapOf<String, Channel>()
    private val _presences = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    val presences: StateFlow<Map<String, UserPresence>> = _presences.asStateFlow()

    fun getPresence(userId: String): UserPresence? = _presences.value[userId]

    val gatewayEvents = gateway.events

    fun getChannelNames(): Map<String, String> = channelNameCache.toMap()
    fun getChannelGuilds(): Map<String, String> = channelGuildCache.toMap()

    fun canSendMessage(channelId: String): Boolean {
        val channel = channelCache[channelId] ?: return true
        if (channel.type == ChannelType.GUILD_NEWS) return false
        val guildId = channel.guildId ?: return true
        val overwrites = channel.permissionOverwrites
        if (overwrites.isEmpty()) return true

        var allow = 0L
        var deny = 0L

        overwrites.firstOrNull { it.type == 0 && it.id == guildId }?.let {
            deny = deny  or it.deny
            allow = allow or it.allow
        }

        val myRoles = myRolesByGuild[guildId] ?: emptyList()
        for (ow in overwrites.filter { it.type == 0 && it.id in myRoles }) {
            deny = deny  or ow.deny
            allow = allow or ow.allow
        }

        if (allow and Permissions.SEND_MESSAGES != 0L) return true
        if (deny  and Permissions.SEND_MESSAGES != 0L) return false
        return true
    }

    fun typingInChannel(channelId: String): StateFlow<Set<String>> =
        object : StateFlow<Set<String>> {
            override val value: Set<String>
                get() = _typing.value[channelId] ?: emptySet()
            override val replayCache: List<Set<String>>
                get() = listOf(value)
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Set<String>>): Nothing {
                _typing.map { it[channelId] ?: emptySet() }.collect(collector)
                error("unreachable")
            }
        }

    fun connect() {
        gateway.connect()
        observeGatewayEvents()
        scope.launch { refreshCurrentUser() }
        scope.launch { refreshGuilds() }
        scope.launch { refreshDmChannels() }
        // Pre-populate channel name/guild caches from all previously cached guild channel data
        // so mention cards on HomeScreen show names instead of raw IDs on first load
        scope.launch {
            val cachedGuildIds = loadCachedGuilds().map { it.id }
            for (guildId in cachedGuildIds) {
                val groups = getCachedChannels(guildId, filterInaccessible = false) ?: continue
                groups.forEach { group ->
                    group.channels.forEach { ch ->
                        channelNameCache[ch.id] = ch.name
                        channelGuildCache[ch.id] = guildId
                    }
                }
            }
        }
    }

    fun disconnect() = gateway.disconnect()
    fun refreshOnResume(activeChannelId: String?) {
        scope.launch {
            runCatching { refreshDmChannels() }
            if (activeChannelId != null) {
                runCatching { loadMessages(activeChannelId) }
            }
        }
    }

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
        groups.forEach { g ->
            g.channels.forEach { ch ->
                channelNameCache[ch.id] = ch.name
                channelCache[ch.id] = ch
            }
        }
    }

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

   fun getCachedChannels(guildId: String, filterInaccessible: Boolean): List<CategoryGroup>? = runCatching {
        val json = prefs?.getString("channels_$guildId", null) ?: return null
        val arr = JSONArray(json)
        val allChannels = Channel.listFromJson(arr)
        val textChannels = allChannels.filter { it.isText }
        val categories = allChannels.filter { it.isCategory }.sortedBy { it.position }
        val byParent = textChannels.groupBy { it.parentId }
        val groups = mutableListOf<CategoryGroup>()
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

    fun saveChannels(guildId: String, groups: List<CategoryGroup>) = runCatching {
        val allChannels = groups.flatMap { g -> listOfNotNull(g.category) + g.channels }
        prefs?.edit()?.putString("channels_$guildId", JSONArray(allChannels.map { it.toJson() }).toString())?.apply()
    }

    private val loadedChannels = mutableSetOf<String>()

    fun markChannelRead(channelId: String, messageId: String) {
        val existing = _readState.value[channelId]
        if (existing != null && messageId <= existing.lastMessageId) return
        _readState.update { current ->
            current + (channelId to ChannelUnreadState(lastMessageId = messageId, mentionCount = 0))
        }
        scope.launch { rest.ackChannel(channelId, messageId) }
    }

    suspend fun loadMessages(channelId: String) {
        rest.getMessages(channelId).onSuccess { fetched ->
            val fetchedList = fetched.reversed() 
            fetchedList.forEach { userDisplayNames[it.author.id] = it.author.displayName }
            _messages.update { current ->
                val existing = current[channelId].orEmpty()
                val lastFetchedId = fetchedList.lastOrNull()?.id ?: ""
                val newOnly = existing.filter { it.id > lastFetchedId }
                current + (channelId to (fetchedList + newOnly))
            }
            loadedChannels.add(channelId)
            fetchedList.lastOrNull()?.id?.let { markChannelRead(channelId, it) }
        }
    }

    fun getDisplayName(userId: String): String? = userDisplayNames[userId]
    fun isChannelLoaded(channelId: String) = channelId in loadedChannels

    suspend fun sendMessage(channelId: String, content: String): Result<DiscordMessage> {
        val result = rest.sendMessage(channelId, content)
        result.onSuccess { msg ->
            lastSentAt[channelId] = System.currentTimeMillis()
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun sendGifAttachment(channelId: String, gifUrl: String): Result<DiscordMessage> {
        val result = rest.sendGifAttachment(channelId, gifUrl)
        result.onSuccess { msg ->
            lastSentAt[channelId] = System.currentTimeMillis()
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
            lastSentAt[channelId] = System.currentTimeMillis()
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
            lastSentAt[channelId] = System.currentTimeMillis()
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
        emoji: ReactionEmoji, delta: Int, me: Boolean?
    ) {
        _messages.update { current ->
            val list = current[channelId]?.map { msg ->
                if (msg.id != messageId) return@map msg
                val existing = msg.reactions.firstOrNull { it.emoji.apiKey == emoji.apiKey }
                val newReactions = if (existing != null) {
                    val newCount = existing.count + delta
                    val newMe = me ?: existing.me
                    if (newCount <= 0) msg.reactions.filter { it.emoji.apiKey != emoji.apiKey }
                    else msg.reactions.map {
                        if (it.emoji.apiKey == emoji.apiKey) it.copy(count = newCount, me = newMe) else it
                    }
                } else {
                    if (delta > 0) msg.reactions + Reaction(emoji, 1, me = me == true)
                    else msg.reactions
                }
                msg.copy(reactions = newReactions)
            } ?: return@update current
            current + (channelId to list)
        }
    }

    private fun observeGatewayEvents() {
        scope.launch {
            gateway.events.collect { event ->
                when (event) {
                    is GatewayEvent.Ready -> {
                        _currentUser.value = event.user
                        currentUserId = event.user.id
                        if (event.readState.isNotEmpty()) {
                            _readState.value = event.readState
                            _totalMentions.value = event.readState.values.sumOf { it.mentionCount }
                        }
                        if (event.presences.isNotEmpty()) {
                            _presences.value = event.presences
                                .filter { it.userId.isNotEmpty() }
                                .associateBy { it.userId }
                        }
                    }

                    is GatewayEvent.MessageCreate -> {
                        val msg = event.message
                        userDisplayNames[msg.author.id] = msg.author.displayName
                        val myId = currentUserId
                        if (myId != null && msg.author.id == myId &&
                            event.guildId != null && event.memberRoleIds.isNotEmpty()) {
                            myRolesByGuild[event.guildId] = event.memberRoleIds
                        }
                        _messages.update { current ->
                            val list = current[msg.channelId]?.toMutableList() ?: mutableListOf()
                            if (list.none { it.id == msg.id }) list.add(msg)
                            current + (msg.channelId to list)
                        }
                        val isDmChannel = _dmChannels.value.any { it.id == msg.channelId }
                        if (isDmChannel) {
                            _dmChannels.update { channels ->
                                channels.map { ch ->
                                    if (ch.id == msg.channelId &&
                                        (ch.lastMessageId == null || msg.id > ch.lastMessageId))
                                        ch.copy(lastMessageId = msg.id)
                                    else ch
                                }
                            }
                        }
                        msg.guildId?.let { channelGuildCache[msg.channelId] = it }
                        if (myId != null && msg.author.id != myId) {
                            val guildId = event.guildId ?: msg.guildId
                            val memberRoles = if (guildId != null) myRolesByGuild[guildId] ?: emptyList() else emptyList()
                            if (msg.pingFor(myId, memberRoles)) {
                                val channelName = channelNameCache[msg.channelId] ?: msg.channelId
                                val guildName = msg.guildId?.let { gid ->
                                    _guilds.value.firstOrNull { it.id == gid }?.name
                                }
                                _pings.update { current ->
                                    (listOf(Ping(msg, channelName, guildName)) + current).take(5)
                                }
                            }
                        }
                        _typing.update { current ->
                            val users = current[msg.channelId].orEmpty() - msg.author.id
                            if (users.isEmpty()) current - msg.channelId
                            else current + (msg.channelId to users)
                        }
                        val typingKey = "${msg.channelId}:${msg.author.id}"
                        typingJobs[typingKey]?.cancel()
                        typingJobs.remove(typingKey)
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
                        val isMe = event.userId == currentUserId
                        updateReactionLocally(event.channelId, event.messageId, event.emoji, +1, if (isMe) true else null)
                    }

                    is GatewayEvent.ReactionRemove -> {
                        val isMe = event.userId == currentUserId
                        updateReactionLocally(event.channelId, event.messageId, event.emoji, -1, if (isMe) false else null)
                    }

                    is GatewayEvent.TypingStart -> {
                        event.displayName?.let { userDisplayNames[event.userId] = it }
                        val updated = _typing.value.toMutableMap()
                        val users = (updated[event.channelId] ?: emptySet()) + event.userId
                        updated[event.channelId] = users
                        _typing.value = updated.toMap()
                        val key = "${event.channelId}:${event.userId}"
                        typingJobs[key]?.cancel()
                        typingJobs[key] = scope.launch {
                            delay(10_000)
                            _typing.update { current ->
                                val users = current[event.channelId].orEmpty() - event.userId
                                if (users.isEmpty()) current - event.channelId
                                else current + (event.channelId to users)
                            }
                            typingJobs.remove(key)
                        }
                    }

                    is GatewayEvent.PresenceUpdate -> {
                        val p = event.presence
                        if (p.userId.isNotEmpty()) {
                            _presences.update { current -> current + (p.userId to p) }
                        }
                    }

                    is GatewayEvent.Unknown -> {}
                }
            }
        }
    }

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
