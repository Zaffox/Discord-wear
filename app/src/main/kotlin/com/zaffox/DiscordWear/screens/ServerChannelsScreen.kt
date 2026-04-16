package com.zaffox.discordwear.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.zaffox.discordwear.api.CategoryGroup
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

    var groups  by remember { mutableStateOf<List<CategoryGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error   by remember { mutableStateOf("") }

    LaunchedEffect(guildId) {
        scope.launch {
            repo?.rest?.getGuildChannels(guildId)
                ?.onSuccess {
                    groups = it
                    loading = false
                    // Seed the ping channel-name cache
                    repo.cacheChannelNames(it)
                }
                ?.onFailure { error = it.message ?: "Error"; loading = false }
                ?: run { error = "Not connected"; loading = false }
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item {
                Text(guildName, style = MaterialTheme.typography.titleMedium)
            }

            when {
                loading -> item { CircularProgressIndicator() }
                error.isNotEmpty() -> item {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                groups.isEmpty() -> item {
                    Text("No text channels.", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    for (group in groups) {
                        // Category header (null = uncategorised top-level channels)
                        if (group.category != null) {
                            item {
                                Text(
                                    text  = "▸ ${group.category.name.uppercase()}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight    = FontWeight.Bold,
                                        fontSize      = 10.sp,
                                        letterSpacing = 1.sp
                                    ),
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                        }

                        items(group.channels.size) { idx ->
                            val ch = group.channels[idx]
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
    }
}
