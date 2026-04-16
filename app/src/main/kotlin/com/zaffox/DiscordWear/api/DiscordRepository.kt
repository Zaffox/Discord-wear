package com.zaffox.discordwear.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single source of truth — owns REST client + Gateway, exposes StateFlows.
 */
class DiscordRepository(token: String) {

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rest            = DiscordRestClient(token)
    private val gateway = DiscordGateway(token)

    // ── Exposed state ─────────────────────────────────────────────────────────

    private val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser.asStateFlow()

    /**
     * Cached current-user ID — set immediately on READY so ping detection
     * works even before the StateFlow is observed.
     */
    @Volatile private var currentUserId: String? = null

    private val _guilds = MutableStateFlow<List<Guild>>(emptyList())
    val guilds: StateFlow<List<Guild>> = _guilds.asStateFlow()

    private val _dmChannels = MutableStateFlow<List<Channel>>(emptyList())
    val dmChannels: StateFlow<List<Channel>> = _dmChannels.asStateFlow()

    /** channelId → messages (oldest-first) */
    private val _messages = MutableStateFlow<Map<String, List<DiscordMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<DiscordMessage>>> = _messages.asStateFlow()

    /** channelId → set of userIds currently typing */
    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    /**
     * Up to 5 most-recent pings (messages that mention the current user,
     * @everyone, or @here). Newest first.
     */
    private val _pings = MutableStateFlow<List<Ping>>(emptyList())
    val pings: StateFlow<List<Ping>> = _pings.asStateFlow()

    /**
     * Lightweight channel name cache populated from Gateway events so that
     * pings can show a channel name without extra REST calls.
     * channelId → channelName
     */
    private val channelNameCache = mutableMapOf<String, String>()

    /** Snapshot of the channel name cache for mention rendering. */
    fun getChannelNames(): Map<String, String> = channelNameCache.toMap()

    /**
     * Guild ID cache from message events: channelId → guildId
     * (the gateway MESSAGE_CREATE payload includes guild_id)
     */
    private val channelGuildCache = mutableMapOf<String, String>()

    val gatewayEvents = gateway.events

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
            currentUserId = it.id   // seed early so pings work immediately
        }
    }

    suspend fun refreshGuilds() {
        rest.getGuilds().onSuccess { _guilds.value = it }
    }

    suspend fun refreshDmChannels() {
        rest.getDmChannels().onSuccess { list ->
            _dmChannels.value = list
            // Seed the name cache from DM recipients
            list.forEach { ch -> channelNameCache[ch.id] = ch.displayName }
        }
    }

    /** Seed channel name cache when the user opens a server's channel list. */
    fun cacheChannelNames(groups: List<CategoryGroup>) {
        groups.forEach { g -> g.channels.forEach { ch -> channelNameCache[ch.id] = ch.name } }
    }

    /** Fetch messages for a channel and cache them. */
    suspend fun loadMessages(channelId: String) {
        rest.getMessages(channelId).onSuccess { msgs ->
            _messages.update { it + (channelId to msgs.reversed()) }
        }
    }

    /** Send a message. */
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

    /** Send a sticker. */
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

    /** Toggle a reaction on a message (add if not reacted, remove if already reacted). */
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
                    }

                    is GatewayEvent.MessageCreate -> {
                        val msg = event.message

                        // Update message cache for open channels
                        _messages.update { current ->
                            val list = current[msg.channelId]?.toMutableList() ?: return@update current
                            if (list.none { it.id == msg.id }) list.add(msg)
                            current + (msg.channelId to list)
                        }

                        // Cache guild association from the payload
                        msg.guildId?.let { channelGuildCache[msg.channelId] = it }

                        // Check if this is a ping for the current user
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

                        // Clear typing indicator for this user
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
                        val myId = currentUserId
                        updateReactionLocally(
                            channelId = event.channelId,
                            messageId = event.messageId,
                            emoji     = event.emoji,
                            delta     = +1,
                            me        = event.userId == myId
                        )
                    }

                    is GatewayEvent.ReactionRemove -> {
                        val myId = currentUserId
                        updateReactionLocally(
                            channelId = event.channelId,
                            messageId = event.messageId,
                            emoji     = event.emoji,
                            delta     = -1,
                            me        = event.userId == myId && false // remove doesn't flip me
                        )
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
}
