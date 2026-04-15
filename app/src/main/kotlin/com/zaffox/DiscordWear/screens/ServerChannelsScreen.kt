//need to sort by Channel categoies
package com.zaffox.discordwear.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.zaffox.discordwear.api.Channel
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun ServerChannels(
    guildId: String,
    guildName: String,
    onNavigateToChatScreen: (channelId: String, channelName: String) -> Unit
) {
    val context   = LocalContext.current
    val repo      = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()

    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading  by remember { mutableStateOf(true) }
    var error    by remember { mutableStateOf("") }

    LaunchedEffect(guildId) {
        scope.launch {
            repo?.rest?.getGuildChannels(guildId)
                ?.onSuccess { channels = it; loading = false }
                ?.onFailure { error = it.message ?: "Error"; loading = false }
                ?: run { error = "Not connected"; loading = false }
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item { Text("#$guildName", style = MaterialTheme.typography.titleMedium) }

            when {
                loading -> item { CircularProgressIndicator() }
                error.isNotEmpty() -> item {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                channels.isEmpty() -> item {
                    Text("No text channels.", style = MaterialTheme.typography.bodySmall)
                }
                else -> items(channels.size) { index ->
                    val ch = channels[index]
                    Button(
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors(),
                        onClick  = { onNavigateToChatScreen(ch.id, ch.name) }
                    ) {
                        Text("# ${ch.name}")
                    }
                }
            }
        }
    }
}
