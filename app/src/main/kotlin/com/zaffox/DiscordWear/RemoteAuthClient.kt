package com.zaffox.discordwear

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

sealed class RemoteAuthState {
    object Connecting : RemoteAuthState()
    data class WaitingForScan(val fingerprint: String) : RemoteAuthState()
    data class UserScanned(val userId: String, val username: String, val avatarHash: String) : RemoteAuthState()
    object Canceled : RemoteAuthState()
    data class Error(val message: String) : RemoteAuthState()
}

data class RemoteAuthStatus(val lines: List<String> = emptyList()) {
    fun plus(line: String) = RemoteAuthStatus(lines + line)
}

class RemoteAuthClient(
    private val onStateChange: (RemoteAuthState) -> Unit,
    private val onStatusUpdate: (RemoteAuthStatus) -> Unit,
    private val onTokenReceived: suspend (String) -> Unit
) {
    private val TAG = "RemoteAuthClient"
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null

    private var privateKey: RSAPrivateKey? = null
    private var publicKeySpki: ByteArray? = null  // Java encoded() = SPKI DER

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

private val oaepSpec = OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
    )

    private var status = RemoteAuthStatus()

    private fun setState(s: RemoteAuthState) { mainScope.launch { onStateChange(s) } }

    private fun log(line: String) {
        Log.d(TAG, line)
        status = status.plus(line)
        mainScope.launch { onStatusUpdate(status) }
    }

    fun connect() {
        setState(RemoteAuthState.Connecting)
        log("Opening WebSocket…")
        val request = Request.Builder()
            .url("wss://remote-auth-gateway.discord.gg/?v=2")
            .header("Origin", "https://discord.com")
            .build()

        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("Connected (HTTP ${response.code})")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "<<< $text")
                ioScope.launch { handleMessage(text) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("Failed (HTTP ${response?.code}): ${t.message}")
                setState(RemoteAuthState.Error("Connection failed: ${t.message}"))
                cleanup()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("Closed: $code ${reason.ifBlank { "(no reason)" }}")
                if (code != 1000) setState(RemoteAuthState.Error("Disconnected ($code)"))
                cleanup()
            }
        })
    }

    private suspend fun handleMessage(text: String) {
        val json = JSONObject(text)
        when (val op = json.getString("op")) {
            "hello" -> {
                val intervalMs = json.getLong("heartbeat_interval")
                log("← hello (heartbeat ${intervalMs}ms)")
                log("Generating RSA-2048 keypair…")
                generateKeyPair()
                val b64 = Base64.encodeToString(publicKeySpki, Base64.NO_WRAP)
                log("Pubkey: ${b64.take(20)}… (${publicKeySpki!!.size}B SPKI)")
                send(JSONObject().put("op", "init").put("encoded_public_key", b64))
                startHeartbeat(intervalMs)
                log("→ init sent, waiting for nonce_proof…")
            }
            "nonce_proof" -> {
                log("← nonce_proof, decrypting…")
                val encryptedNonce = json.getString("encrypted_nonce")
                val decryptedNonce = decryptBytes(Base64.decode(encryptedNonce, Base64.DEFAULT))
                val proof = Base64.encodeToString(decryptedNonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                log("Nonce decrypted (${decryptedNonce.size}B), proof=${proof.take(16)}…")
                send(JSONObject().put("op", "nonce_proof").put("nonce", proof))
                log("→ nonce_proof sent, waiting for pending_remote_init…")
            }
            "pending_remote_init" -> {
                val fingerprint = json.getString("fingerprint")
                log("← pending_remote_init!")
                val spki = publicKeySpki!!
                val digest = MessageDigest.getInstance("SHA-256").digest(spki)
                val computed = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                log("Fingerprint match: ${fingerprint == computed}")
                if (fingerprint != computed) {
                    log("Expected: $fingerprint / Got: $computed")
                }
                setState(RemoteAuthState.WaitingForScan(fingerprint))
            }
            "pending_ticket" -> {
                log("← pending_ticket, decrypting user…")
                val payload = decryptBytes(Base64.decode(json.getString("encrypted_user_payload"), Base64.DEFAULT))
                    .toString(Charsets.UTF_8)
                val parts = payload.split(":")
                if (parts.size >= 4) {
                    log("User: ${parts[3]}")
                    setState(RemoteAuthState.UserScanned(userId = parts[0], avatarHash = parts[2], username = parts[3]))
                }
            }
            "pending_login" -> {
                log("← pending_login, exchanging ticket…")
                exchangeTicketForToken(json.getString("ticket"))
            }
            "cancel" -> { log("← cancel"); setState(RemoteAuthState.Canceled) }
            "heartbeat_ack" -> log("← heartbeat_ack")
            else -> log("← unknown op: $op")
        }
    }

    private fun generateKeyPair() {
        val kp = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }.generateKeyPair()
        privateKey = kp.private as RSAPrivateKey
        publicKeySpki = kp.public.encoded   // Java always encodes RSA public keys as SPKI DER
    }

    private fun decryptBytes(cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec)
        return cipher.doFinal(cipherBytes)
    }

    private suspend fun exchangeTicketForToken(ticket: String) {
        try {
            val body = JSONObject().put("ticket", ticket).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://discord.com/api/v9/users/@me/remote-auth/login")
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            val response = withContext(Dispatchers.IO) { http.newCall(request).execute() }
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            log("Ticket exchange HTTP ${response.code}: $responseBody")
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $responseBody")
            val encryptedToken = JSONObject(responseBody).getString("encrypted_token")
            val token = decryptBytes(Base64.decode(encryptedToken, Base64.DEFAULT)).toString(Charsets.UTF_8)
            log("Token decrypted! Logging in…")
            withContext(Dispatchers.Main) { onTokenReceived(token) }
        } catch (e: Exception) {
            log("Token exchange error: ${e.message}")
            setState(RemoteAuthState.Error("Token exchange failed: ${e.message}"))
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = ioScope.launch {
            while (isActive) {
                delay(intervalMs)
                send(JSONObject().put("op", "heartbeat"))
                log("→ heartbeat")
            }
        }
    }

    private fun send(json: JSONObject) {
        Log.d(TAG, ">>> $json")
        ws?.send(json.toString())
    }

    fun disconnect() {
        ws?.close(1000, "user cancelled")
        cleanup()
    }

    private fun cleanup() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        mainScope.cancel()
        ioScope.cancel()
    }
}
