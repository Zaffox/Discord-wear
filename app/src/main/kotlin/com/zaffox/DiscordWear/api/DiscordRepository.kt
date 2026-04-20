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
    private val _readState = MutableStateFlow<Map<String, ChannelUnreadState>>(emptyMap())
    val readState: StateFlow<Map<String, ChannelUnreadState>> = _readState.asStateFlow()

    /** Total unread mention count across all channels. */
    val totalMentionCount: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0).also { /* see below */ }.let {
        // We derive this live from _readState in collectMentionCount()
        _readState
    }.let { MutableStateFlow(0) } // placeholder; real value comes from readState.map { it.values.sumOf { s -> s.mentionCount } }
    // Real total mention count derived from readState
    private val _totalMentions = kotlinx.coroutines.flow.MutableStateFlow(0)
    val totalMentions: StateFlow<Int> = _totalMentions.asStateFlow()

    // Emoji / sticker cache: guildId → list
    private val emojiCache  = mutableMapOf<String, List<GuildEmoji>>()
    private val stickerCache = mutableMapOf<String, List<StickerItem>>()

    private val channelNameCache  = mutableMapOf<String, String>()
    private val channelGuildCache = mutableMapOf<String, String>()
    // Typing timeout jobs: "channelId:userId" -> cancellable Job
    private val typingJobs = mutableMapOf<String, Job>()
    // slowmode: channelId -> last send timestamp (ms)
    private val lastSentAt = mutableMapOf<String, Long>()

    /**
     * Returns how many seconds the user must wait before sending again in a slow-mode channel.
     * Returns 0 if they can send immediately.
     */
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

    // userId -> display name, populated from TYPING_START and message authors
    private val userDisplayNames = mutableMapOf<String, String>()

    // guildId -> current user's role IDs, populated from MESSAGE_CREATE member data
    private val myRolesByGuild = mutableMapOf<String, List<String>>()

    // channelId -> CategoryGroup channel object, rebuilt whenever channels are cached
    private val channelCache = mutableMapOf<String, Channel>()

    // userId -> UserPresence, populated from READY and PRESENCE_UPDATE
    private val _presences = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    val presences: StateFlow<Map<String, UserPresence>> = _presences.asStateFlow()

    fun getPresence(userId: String): UserPresence? = _presences.value[userId]

    val gatewayEvents = gateway.events

    fun getChannelNames(): Map<String, String> = channelNameCache.toMap()

    /**
     * Returns true if the current user can send messages in [channelId].
     * Uses cached channel permission overwrites with known role data.
     * Announcement channels (GUILD_NEWS) are always read-only.
     * Falls back to true (optimistic) when data is unavailable.
     */
    fun canSendMessage(channelId: String): Boolean {
        val channel = channelCache[channelId] ?: return true
        // Announcement channels are always read-only for non-admins
        if (channel.type == ChannelType.GUILD_NEWS) return false
        val guildId = channel.guildId ?: return true
        val overwrites = channel.permissionOverwrites
        if (overwrites.isEmpty()) return true

        var allow = 0L
        var deny  = 0L

        // @everyone overwrite (type=0, id == guildId)
        overwrites.firstOrNull { it.type == 0 && it.id == guildId }?.let {
            deny  = deny  or it.deny
            allow = allow or it.allow
        }

        // Role overwrites for the user's known roles in this guild
        val myRoles = myRolesByGuild[guildId] ?: emptyList()
        for (ow in overwrites.filter { it.type == 0 && it.id in myRoles }) {
            deny  = deny  or ow.deny
            allow = allow or ow.allow
        }

        // If explicitly allowed by a role overwrite, trust it
        if (allow and Permissions.SEND_MESSAGES != 0L) return true
        // If denied, block
        if (deny  and Permissions.SEND_MESSAGES != 0L) return false
        // Otherwise assume allowed (Discord defaults to allow for visible channels)
        return true
    }

    /** Returns a StateFlow of user IDs currently typing in [channelId]. */
    fun typingInChannel(channelId: String): StateFlow<Set<String>> =
        // Map the whole typing map to just this channel's set.
        // stateIn would be ideal but requires a scope — instead expose a derived
        // flow that ChatScreen subscribes to directly.
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun connect() {
        gateway.connect()
        observeGatewayEvents()
        scope.launch { refreshCurrentUser() }
        scope.launch { refreshGuilds() }
        scope.launch { refreshDmChannels() }
    }

    fun disconnect() = gateway.disconnect()

    /**
     * Called when the app returns to foreground after being backgrounded.
     * Re-fetches messages for any channel that was actively viewed so new
     * messages sent while the app was paused appear immediately.
     */
    fun refreshOnResume(activeChannelId: String?) {
        scope.launch {
            // Refresh DM list in case new DMs arrived
            runCatching { refreshDmChannels() }
            // Re-load messages for the active channel
            if (activeChannelId != null) {
                runCatching { loadMessages(activeChannelId) }
            }
        }
    }

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
        groups.forEach { g ->
            g.channels.forEach { ch ->
                channelNameCache[ch.id] = ch.name
                channelCache[ch.id] = ch
            }
        }
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

    // Track which channels have had a full REST history load
    private val loadedChannels = mutableSetOf<String>()

    /**
     * Fetch message history for a channel from REST and merge with any live
     * gateway messages that arrived before or after the fetch.
     * Always performs the REST call — never skips based on existing state.
     */
    /**
     * Mark [channelId] as read up to [messageId].
     * Updates the local [readState] immediately (so the UI dot/badge clears at once),
     * then fires the REST ack in the background so Discord's servers agree.
     * The read pointer is only advanced — it never goes backwards.
     */
    fun markChannelRead(channelId: String, messageId: String) {
        val existing = _readState.value[channelId]
        // Only advance — never go backwards
        if (existing != null && messageId <= existing.lastMessageId) return
        _readState.update { current ->
            current + (channelId to ChannelUnreadState(lastMessageId = messageId, mentionCount = 0))
        }
        scope.launch { rest.ackChannel(channelId, messageId) }
    }

    suspend fun loadMessages(channelId: String) {
        rest.getMessages(channelId).onSuccess { fetched ->
            val fetchedList = fetched.reversed() // oldest-first
            // Cache all author names so typing indicator can resolve them
            fetchedList.forEach { userDisplayNames[it.author.id] = it.author.displayName }
            _messages.update { current ->
                val existing = current[channelId].orEmpty()
                // Keep any gateway messages newer than the last REST-fetched message
                val lastFetchedId = fetchedList.lastOrNull()?.id ?: ""
                val newOnly = existing.filter { it.id > lastFetchedId }
                current + (channelId to (fetchedList + newOnly))
            }
            loadedChannels.add(channelId)
            // Mark the channel as read up to the newest message we just loaded
            fetchedList.lastOrNull()?.id?.let { markChannelRead(channelId, it) }
        }
    }

    /** Resolve a userId to a display name for the typing indicator. */
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
            updateReactionLocally(channelId, messageId, emoji, delta = -1, me = false) // own remove
        } else {
            rest.addReaction(channelId, messageId, emoji.apiKey)
            updateReactionLocally(channelId, messageId, emoji, delta = +1, me = true)
        }
    }

    /**
     * [me] — whether the *current user's* reaction state changes:
     *   ReactionAdd by me      → me = true
     *   ReactionAdd by other   → me = null (preserve existing)
     *   ReactionRemove by me   → me = false
     *   ReactionRemove by other → me = null (preserve existing)
     */
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
                    // null means "don't change the me flag" (another user reacted/unreacted)
                    val newMe = me ?: existing.me
                    if (newCount <= 0) msg.reactions.filter { it.emoji.apiKey != emoji.apiKey }
                    else msg.reactions.map {
                        if (it.emoji.apiKey == emoji.apiKey) it.copy(count = newCount, me = newMe) else it
                    }
                } else {
                    // Brand new reaction — only add if delta is positive
                    if (delta > 0) msg.reactions + Reaction(emoji, 1, me = me == true)
                    else msg.reactions // ignore spurious remove for unknown reaction
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
                        // Cache author name for typing indicator display
                        userDisplayNames[msg.author.id] = msg.author.displayName
                        // Cache the current user's roles for this guild from the member object
                        val myId = currentUserId
                        if (myId != null && msg.author.id == myId &&
                            event.guildId != null && event.memberRoleIds.isNotEmpty()) {
                            myRolesByGuild[event.guildId] = event.memberRoleIds
                        }
                        _messages.update { current ->
                            // Always append — do NOT drop messages for channels not yet loaded.
                            // loadMessages() will merge/overwrite with the full REST fetch later.
                            val list = current[msg.channelId]?.toMutableList() ?: mutableListOf()
                            if (list.none { it.id == msg.id }) list.add(msg)
                            current + (msg.channelId to list)
                        }
                        msg.guildId?.let { channelGuildCache[msg.channelId] = it }
                        if (myId != null && msg.author.id != myId) {
                            // Look up the user's roles for this guild for role-ping detection
                            val guildId = event.guildId ?: msg.guildId
                            val memberRoles = if (guildId != null) myRolesByGuild[guildId] ?: emptyList() else emptyList()
                            if (msg.pingFor(myId, memberRoles)) {
                                val channelName = channelNameCache[msg.channelId] ?: msg.channelId
                                val guildName   = msg.guildId?.let { gid ->
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
                        // Cancel the auto-clear job for this user since they sent a message
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
                        // If it's our reaction: me=true. If someone else's: null (preserve existing me)
                        updateReactionLocally(event.channelId, event.messageId, event.emoji, +1, if (isMe) true else null)
                    }

                    is GatewayEvent.ReactionRemove -> {
                        val isMe = event.userId == currentUserId
                        // If our reaction removed: me=false. If someone else's: null (preserve existing me)
                        updateReactionLocally(event.channelId, event.messageId, event.emoji, -1, if (isMe) false else null)
                    }

                    is GatewayEvent.TypingStart -> {
                        // Cache display name if provided by the event
                        event.displayName?.let { userDisplayNames[event.userId] = it }
                        // Use a simple value copy to guarantee StateFlow sees a new reference
                        val updated = _typing.value.toMutableMap()
                        val users = (updated[event.channelId] ?: emptySet()) + event.userId
                        updated[event.channelId] = users
                        _typing.value = updated.toMap()
                        // Auto-clear after 10 seconds (Discord's typing indicator duration)
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
