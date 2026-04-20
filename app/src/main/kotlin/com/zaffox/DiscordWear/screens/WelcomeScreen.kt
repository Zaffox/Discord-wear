package com.zaffox.discordwear.screens

import android.app.RemoteInput
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.TokenWebServer
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INPUT_KEY = "token_input"

@Composable
fun WelcomeScreen(onSetupComplete: () -> Unit) {
    val context   = LocalContext.current
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()

    var error        by remember { mutableStateOf("") }
    var serverStatus by remember { mutableStateOf("") }
    var serverAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var webServer    by remember { mutableStateOf<TokenWebServer?>(null) }
    var showQr       by remember { mutableStateOf(false) }

    // WiFi lock — held while the web server is running so the watch doesn't drop WiFi
    val wifiManager  = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    val wifiLock = remember {
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DiscordWear:TokenServer")
    }
    // ConnectivityManager callback — binds the process to WiFi so all traffic uses it
    val connectivityManager = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    var wifiNetworkCallback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }

    fun acquireWifiLock() {
        if (!wifiLock.isHeld) wifiLock.acquire()
        // Request a WiFi network and bind the process to it so HTTP traffic goes over WiFi
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
            }
            override fun onLost(network: Network) {
                connectivityManager.bindProcessToNetwork(null)
            }
        }
        connectivityManager.requestNetwork(req, cb)
        wifiNetworkCallback = cb
    }

    fun releaseWifiLock() {
        if (wifiLock.isHeld) wifiLock.release()
        wifiNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        wifiNetworkCallback = null
        connectivityManager.bindProcessToNetwork(null)
    }

    // Cleanup server on dispose
    DisposableEffect(Unit) {
        onDispose {
            webServer?.stop()
            releaseWifiLock()
        }
    }

    val inputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bundle: Bundle? = RemoteInput.getResultsFromIntent(
            result.data ?: return@rememberLauncherForActivityResult
        )
        val token = bundle?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        if (!token.isNullOrBlank()) {
            scope.launch {
                SetupPreferences.saveToken(context, token)
                context.discordApp.initRepository(token)
                onSetupComplete()
            }
        }
    }

    fun openTokenInput() {
        val ri = RemoteInput.Builder(INPUT_KEY)
            .setLabel("Paste Discord token")
            .wearableExtender {
                setEmojisAllowed(false)
                setInputActionType(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            }.build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(ri))
        inputLauncher.launch(intent)
    }

    fun startWebServer() {
        acquireWifiLock()
        val srv = TokenWebServer(port = 8080) { token ->
            SetupPreferences.saveToken(context, token)
            context.discordApp.initRepository(token)
            scope.launch {
                serverStatus = "Token received!"
                delay(500)
                webServer?.stop()
                webServer = null
                releaseWifiLock()
                onSetupComplete()
            }
        }
        srv.start()
        webServer = srv
        serverAddresses = srv.getLocalAddresses()
        serverStatus = if (serverAddresses.isEmpty()) {
            "Server started — connect to Wi-Fi first"
        } else {
            "Server running"
        }
    }

    ScreenScaffold(scrollState = listState) {
        
        ScalingLazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text(
                    "DiscordWear",
                    style     = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    "Enter your Discord token to get started.",
                    style     = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }

            // ── Manual token entry ─────────────────────────────────────────────
            item {
                Button(
                    onClick  = { openTokenInput() },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Type Token") }
            }

            // ── Web server entry ───────────────────────────────────────────────
            if (webServer == null) {
                item {
                    Button(
                        onClick  = { startWebServer() },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Enter via Browser") }
                }
            } else {
                item {
                    Text(
                        serverStatus,
                        style     = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color     = MaterialTheme.colorScheme.primary,
                        modifier  = Modifier.fillMaxWidth()
                    )
                }
                serverAddresses.forEachIndexed { idx, addr ->
                    item {
                        Text(
                            "http://$addr:8080",
                            style     = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }
                }
                item {
                    Button(
                        onClick  = { webServer?.stop(); webServer = null; serverStatus = ""; releaseWifiLock() },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Stop Server") }
                }
            }

            // ── QR code login ──────────────────────────────────────────────────

            if (error.isNotEmpty()) {
                item {
                    Text(
                        error,
                        color     = MaterialTheme.colorScheme.error,
                        style     = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

