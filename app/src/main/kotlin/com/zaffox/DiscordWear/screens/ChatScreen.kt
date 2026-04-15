//Add quick responce and server emoji support
package com.zaffox.discordwear.screens

import android.app.RemoteInput
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.zaffox.discordwear.api.DiscordMessage
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

private const val INPUT_KEY = "message_input"

@Composable
fun ChatScreen(
    channelId: String,
    channelName: String,
    currentUserId: String = ""
) {
    val context   = LocalContext.current
    val repo      = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()

    val allMessages by (repo?.messages ?: return).collectAsState()
    val messages = allMessages[channelId].orEmpty()

    var loading   by remember { mutableStateOf(messages.isEmpty()) }
    var sendError by remember { mutableStateOf("") }

    LaunchedEffect(channelId) {
        if (messages.isEmpty()) {
            scope.launch {
                repo.loadMessages(channelId)
                loading = false
            }
        } else {
            loading = false
        }
    }

    val inputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bundle: Bundle? = RemoteInput.getResultsFromIntent(
            result.data ?: return@rememberLauncherForActivityResult
        )
        val text = bundle?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            scope.launch {
                repo.sendMessage(channelId, text)
                    .onFailure { sendError = "Failed: ${it.message}" }
            }
        }
    }

    fun openInput() {
        val remoteInput = RemoteInput.Builder(INPUT_KEY)
            .setLabel("Message #$channelName")
            .wearableExtender {
                setEmojisAllowed(true)
                setInputActionType(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            }
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        inputLauncher.launch(intent)
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text("#$channelName", style = MaterialTheme.typography.titleMedium)
            }

            when {
                loading -> item { CircularProgressIndicator() }
                messages.isEmpty() -> item {
                    Text("No messages yet.", style = MaterialTheme.typography.bodySmall)
                }
                else -> items(messages.size) { index ->
                    MessageBubble(
                        msg   = messages[index],
                        isOwn = messages[index].author.id == currentUserId
                    )
                }
            }

            if (sendError.isNotEmpty()) {
                item {
                    Text(sendError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Button(
                    onClick  = { openInput() },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Text("Message #$channelName")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: DiscordMessage, isOwn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start) {
            Text(
                text  = msg.author.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = msg.content, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
