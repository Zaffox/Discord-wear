package com.zaffox.discordwear.screens

import android.app.RemoteInput
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

    // Cleanup server on dispose
    DisposableEffect(Unit) {
        onDispose { webServer?.stop() }
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
        val srv = TokenWebServer(port = 8080) { token ->
            SetupPreferences.saveToken(context, token)
            context.discordApp.initRepository(token)
            scope.launch {
                serverStatus = "Token received!"
                delay(500)
                webServer?.stop()
                webServer = null
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
                        onClick  = { webServer?.stop(); webServer = null; serverStatus = "" },
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

