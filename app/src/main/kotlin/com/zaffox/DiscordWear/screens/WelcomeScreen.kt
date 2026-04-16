package com.zaffox.discordwear.screens

import android.app.RemoteInput
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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
import com.zaffox.discordwear.discordApp

private const val TOKEN_INPUT_KEY = "token_input"

@Composable
fun WelcomeScreen(onSetupComplete: () -> Unit) {
    val context   = LocalContext.current
    val listState = rememberScalingLazyListState()
    var statusMsg by remember { mutableStateOf("") }

    val inputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bundle: Bundle? = RemoteInput.getResultsFromIntent(
            result.data ?: return@rememberLauncherForActivityResult
        )
        val token = bundle?.getCharSequence(TOKEN_INPUT_KEY)?.toString()?.trim()
        if (!token.isNullOrBlank()) {
            SetupPreferences.saveToken(context, token)
            context.discordApp.initRepository(token)
            onSetupComplete()
        } else {
            statusMsg = "No token entered."
        }
    }

    fun openTokenInput() {
        val remoteInput = RemoteInput.Builder(TOKEN_INPUT_KEY)
            .setLabel("Paste Discord token")
            .wearableExtender {
                setEmojisAllowed(false)
                setInputActionType(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            }
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        inputLauncher.launch(intent)
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
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
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = { openTokenInput() }) {
                    Text("Enter Token")
                }
            }
            if (statusMsg.isNotEmpty()) {
                item {
                    Text(
                        statusMsg,
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
