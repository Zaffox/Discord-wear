package com.zaffox.discordwear

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class TokenWebServer(
    private val port: Int = 8080,
    private val onTokenReceived: suspend (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    fun getLocalAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || !iface.name.startsWith("wlan")) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) {
                        addresses.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TokenWebServer", "Error getting addresses", e)
        }
        return addresses
    }

    fun start() {
        if (running) return
        running = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port, 5, InetAddress.getByName("0.0.0.0"))
                Log.i("TokenWebServer", "Listening on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) Log.e("TokenWebServer", "Server error", e)
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val requestLine = reader.readLine() ?: return@withContext
            val headers = mutableListOf<String>()
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                headers.add(line)
                line = reader.readLine()
            }

            val isPost = requestLine.startsWith("POST")
            val contentLength = headers.firstOrNull { it.startsWith("Content-Length:") }
                ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0

            var submittedToken: String? = null

            if (isPost && contentLength > 0) {
                val body = CharArray(contentLength)
                reader.read(body, 0, contentLength)
                val bodyStr = String(body)
                submittedToken = bodyStr.split("&")
                    .firstOrNull { it.startsWith("token=") }
                    ?.removePrefix("token=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?.trim()
            }

            if (!submittedToken.isNullOrBlank()) {
                val successHtml = buildSuccessPage()
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: text/html; charset=utf-8\r\n")
                writer.print("Content-Length: ${successHtml.length}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(successHtml)
                writer.flush()
                socket.close()
                onTokenReceived(submittedToken)
            } else {
                val formHtml = buildFormPage()
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: text/html; charset=utf-8\r\n")
                writer.print("Content-Length: ${formHtml.length}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(formHtml)
                writer.flush()
                socket.close()
            }
        } catch (e: Exception) {
            Log.e("TokenWebServer", "Client error", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildFormPage() = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DiscordWear Token Setup</title>
<style>
  body { font-family: system-ui, sans-serif; background: #1e1f22; color: #dbdee1;
         display: flex; flex-direction: column; align-items: center;
         justify-content: center; min-height: 100vh; margin: 0; padding: 16px; }
  .card { background: #2b2d31; border-radius: 12px; padding: 24px;
          width: 100%; max-width: 420px; box-sizing: border-box; }
  h1 { margin: 0 0 8px; font-size: 1.3rem; color: #fff; }
  p  { margin: 0 0 16px; font-size: .85rem; color: #949ba4; line-height: 1.5; }
  input[type=password] {
    width: 100%; padding: 10px 12px; border-radius: 4px; border: 1px solid #3d3f45;
    background: #1e1f22; color: #dbdee1; font-size: 1rem; box-sizing: border-box;
    margin-bottom: 12px;
  }
  button {
    width: 100%; padding: 10px; border: none; border-radius: 4px;
    background: #5865f2; color: #fff; font-size: 1rem; cursor: pointer;
    font-weight: 600;
  }
  button:hover { background: #4752c4; }
  .warn { background: #f23f42; border-radius: 6px; padding: 8px 12px;
          font-size: .8rem; margin-bottom: 16px; color: #fff; }
</style>
</head>
<body>
<div class="card">
  <h1>DiscordWear</h1>
  <p>Enter your Discord token below to log in on your watch. The token is stored only on your device.</p>
  <div class="warn">Never share your token. Only enter it on your own device.</div>
  <form method="POST" action="/">
    <input type="password" name="token" placeholder="Paste your Discord token…" autocomplete="off" required>
    <button type="submit">Save Token</button>
  </form>
</div>
</body>
</html>"""

    private fun buildSuccessPage() = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DiscordWear</title>
<style>
  body { font-family: system-ui, sans-serif; background: #1e1f22; color: #dbdee1;
         display: flex; align-items: center; justify-content: center;
         min-height: 100vh; margin: 0; }
  .card { background: #2b2d31; border-radius: 12px; padding: 32px;
          text-align: center; max-width: 320px; }
  .icon { font-size: 3rem; }
  h1 { color: #23a55a; margin: 8px 0; }
  p  { color: #949ba4; font-size: .9rem; }
</style>
</head>
<body>
<div class="card">
  <h1>Token Saved!</h1>
  <p>Your Discord token has been saved. You can now close this page.</p>
</div>
</body>
</html>"""
}
