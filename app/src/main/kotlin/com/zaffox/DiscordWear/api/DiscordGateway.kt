package com.zaffox.discordwear.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Discord Gateway (v10) WebSocket client.
 *
 * Handles:
 *  - Initial HELLO + heartbeat loop
 *  - IDENTIFY handshake
 *  - Session resumption on reconnect
 *  - Emitting Gateway events as a [SharedFlow]
 *
 * Usage:
 *   val gw = DiscordGateway(token)
 *   gw.connect()
 *   gw.events.collect { event -> ... }
 *   // later:
 *   gw.disconnect()
 */
class DiscordGateway(private val token: String) {

    // ── Public event flow ─────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events

    // ── Internal state ────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private val seq = AtomicInteger(-1)
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    @Volatile private var connected = false

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // no read timeout for WS
        .build()

    // ── Opcodes ───────────────────────────────────────────────────────────────
    private object Op {
        const val DISPATCH        = 0
        const val HEARTBEAT       = 1
        const val IDENTIFY        = 2
        const val RESUME          = 6
        const val RECONNECT       = 7
        const val INVALID_SESSION = 9
        const val HELLO           = 10
        const val HEARTBEAT_ACK   = 11
    }

    // ── Connect / Disconnect ──────────────────────────────────────────────────

    fun connect() {
        val url = resumeUrl ?: "wss://gateway.discord.gg/?v=10&encoding=json"
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, Listener())
    }

    fun disconnect() {
        connected = false
        heartbeatJob?.cancel()
        ws?.close(1000, "Goodbye")
        ws = null
    }

    // ── Outgoing helpers ──────────────────────────────────────────────────────

    private fun send(op: Int, d: Any?) {
        val payload = JSONObject()
            .put("op", op)
            .put("d", d)
        ws?.send(payload.toString())
    }

    private fun sendHeartbeat() {
        val s = seq.get().takeIf { it >= 0 }
        send(Op.HEARTBEAT, s)
    }

    private fun sendIdentify() {
        val d = JSONObject()
            .put("token", token)
            .put("intents", INTENTS)
            .put("properties", JSONObject()
                .put("\$os", "android")
                .put("\$browser", "discord_wear")
                .put("\$device", "wearos"))
        send(Op.IDENTIFY, d)
    }

    private fun sendResume() {
        val d = JSONObject()
            .put("token", token)
            .put("session_id", sessionId)
            .put("seq", seq.get())
        send(Op.RESUME, d)
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
            val op = payload.getInt("op")
            val d  = payload.opt("d")
            val s  = payload.optInt("s", -1).takeIf { it >= 0 }
            val t  = payload.optString("t").takeIf { it.isNotEmpty() }

            if (s != null) seq.set(s)

            when (op) {
                Op.HELLO -> {
                    val interval = (d as? JSONObject)?.getLong("heartbeat_interval") ?: 41250L
                    startHeartbeat(interval)
                    if (sessionId != null) sendResume() else sendIdentify()
                }

                Op.HEARTBEAT -> sendHeartbeat()

                Op.HEARTBEAT_ACK -> { /* connection is healthy */ }

                Op.RECONNECT -> {
                    disconnect()
                    scope.launch { delay(1_000); connect() }
                }

                Op.INVALID_SESSION -> {
                    val resumable = (d as? Boolean) == true
                    if (!resumable) { sessionId = null; resumeUrl = null }
                    scope.launch { delay(2_000); connect() }
                }

                Op.DISPATCH -> {
                    if (t != null) handleDispatch(t, d as? JSONObject)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            heartbeatJob?.cancel()
            scope.launch {
                delay(5_000)
                connect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            heartbeatJob?.cancel()
        }
    }

    // ── Heartbeat loop ────────────────────────────────────────────────────────

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((intervalMs * Math.random()).toLong()) // jitter on first beat
            while (isActive) {
                sendHeartbeat()
                delay(intervalMs)
            }
        }
    }

    // ── Dispatch handler ──────────────────────────────────────────────────────

    private fun handleDispatch(eventName: String, d: JSONObject?) {
        if (d == null) return

        val event: GatewayEvent? = when (eventName) {
            "READY" -> {
                sessionId = d.optString("session_id")
                resumeUrl = d.optString("resume_gateway_url")
                val user = runCatching { DiscordUser.fromJson(d.getJSONObject("user")) }.getOrNull()
                // Parse read_state: array of {id (channelId), last_message_id}
                val readState = mutableMapOf<String, String>()
                val rsArr = d.optJSONArray("read_state")
                    ?: d.optJSONObject("read_state")?.optJSONArray("entries")
                if (rsArr != null) {
                    for (i in 0 until rsArr.length()) {
                        runCatching {
                            val rs = rsArr.getJSONObject(i)
                            val chId = rs.optString("id").takeIf { it.isNotEmpty() } ?: return@runCatching
                            val lastRead = rs.optString("last_message_id").takeIf { it.isNotEmpty() && it != "null" }
                                ?: return@runCatching
                            readState[chId] = lastRead
                        }
                    }
                }
                if (user != null) GatewayEvent.Ready(user, readState) else null
            }
            "MESSAGE_CREATE" -> runCatching {
                GatewayEvent.MessageCreate(DiscordMessage.fromJson(d))
            }.getOrNull()
            "MESSAGE_UPDATE" -> runCatching {
                GatewayEvent.MessageUpdate(DiscordMessage.fromJson(d))
            }.getOrNull()
            "MESSAGE_DELETE" -> runCatching {
                GatewayEvent.MessageDelete(
                    id        = d.getString("id"),
                    channelId = d.getString("channel_id")
                )
            }.getOrNull()
            "MESSAGE_REACTION_ADD" -> runCatching {
                GatewayEvent.ReactionAdd(
                    messageId = d.getString("message_id"),
                    channelId = d.getString("channel_id"),
                    userId    = d.getString("user_id"),
                    emoji     = ReactionEmoji.fromJson(d.getJSONObject("emoji"))
                )
            }.getOrNull()
            "MESSAGE_REACTION_REMOVE" -> runCatching {
                GatewayEvent.ReactionRemove(
                    messageId = d.getString("message_id"),
                    channelId = d.getString("channel_id"),
                    userId    = d.getString("user_id"),
                    emoji     = ReactionEmoji.fromJson(d.getJSONObject("emoji"))
                )
            }.getOrNull()
            "TYPING_START" -> runCatching {
                GatewayEvent.TypingStart(
                    channelId = d.getString("channel_id"),
                    userId    = d.getString("user_id")
                )
            }.getOrNull()
            else -> GatewayEvent.Unknown(eventName)
        }

        if (event != null) {
            scope.launch { _events.emit(event) }
        }
    }

    companion object {
        /**
         * Intents bitmask:
         *  GUILDS (1) | GUILD_MESSAGES (512) | GUILD_MESSAGE_TYPING (2048) |
         *  DIRECT_MESSAGES (4096) | DIRECT_MESSAGE_TYPING (8192) |
         *  MESSAGE_CONTENT (32768)
         */
        private const val INTENTS =
            (1 or 512 or 2048 or 4096 or 8192 or 32768)
    }
}

// ── Event sealed class ────────────────────────────────────────────────────────

sealed class GatewayEvent {
    /** [readState] maps channelId -> last-read messageId from the READY payload. */
    data class Ready(val user: DiscordUser, val readState: Map<String, String> = emptyMap()) : GatewayEvent()
    data class MessageCreate(val message: DiscordMessage) : GatewayEvent()
    data class MessageUpdate(val message: DiscordMessage) : GatewayEvent()
    data class MessageDelete(val id: String, val channelId: String) : GatewayEvent()
    data class ReactionAdd(val messageId: String, val channelId: String, val userId: String, val emoji: ReactionEmoji) : GatewayEvent()
    data class ReactionRemove(val messageId: String, val channelId: String, val userId: String, val emoji: ReactionEmoji) : GatewayEvent()
    data class TypingStart(val channelId: String, val userId: String) : GatewayEvent()
    data class Unknown(val name: String) : GatewayEvent()
}
