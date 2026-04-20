package com.zaffox.discordwear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.api.CategoryGroup
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

    val hideInaccessible = remember { SetupPreferences.getHideInaccessibleChannels(context) }

    var groups  by remember { mutableStateOf<List<CategoryGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error   by remember { mutableStateOf("") }

    val readState by (repo?.readState ?: return).collectAsState()
    val pings     by repo.pings.collectAsState()

    // Build a map of channelId -> ping count from current pings list
    val pingsByChannel = remember(pings) {
        pings.groupBy { it.message.channelId }.mapValues { it.value.size }
    }

    LaunchedEffect(guildId) {
        scope.launch {
            val cached = repo?.getCachedChannels(guildId, hideInaccessible)
            if (!cached.isNullOrEmpty()) {
                groups = cached
                loading = false
                repo?.cacheChannelNames(cached)
            }
            repo?.rest?.getGuildChannels(guildId, filterInaccessible = hideInaccessible)
                ?.onSuccess {
                    groups = it
                    loading = false
                    repo.cacheChannelNames(it)
                    repo.saveChannels(guildId, it)
                }
                ?.onFailure {
                    if (groups.isEmpty()) { error = it.message ?: "Error"; loading = false }
                }
                ?: run { if (groups.isEmpty()) { error = "Not connected"; loading = false } }
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
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                        }

                        items(group.channels.size) { idx ->
                            val ch = group.channels[idx]
                            // Only show channels where the user has access
                            if (ch.hasAccess) {
                                val pingCount = pingsByChannel[ch.id] ?: 0
                                val hasUnread = run {
                                    val lastRead = readState[ch.id]
                                    val lastMsg  = ch.lastMessageId
                                    // Unread if last message exists and is newer than last read
                                    lastMsg != null && lastMsg.isNotEmpty() &&
                                        (lastRead == null || lastMsg > lastRead)
                                }
                                ChannelButton(
                                    channel   = ch,
                                    hasUnread = hasUnread,
                                    pingCount = pingCount,
                                    onClick   = { onNavigateToChatScreen(ch.id, ch.name) }
                                )
                            }
                            // Channels without access are hidden entirely
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelButton(
    channel: Channel,
    hasUnread: Boolean,
    pingCount: Int,
    onClick: () -> Unit
) {
    Button(
        modifier = Modifier.fillMaxWidth().height(36.dp),
        colors   = if (pingCount > 0)
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else if (hasUnread)
            ButtonDefaults.buttonColors()
        else
            ButtonDefaults.filledTonalButtonColors(),
        onClick  = onClick
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                text       = "# ${channel.name}",
                modifier   = Modifier.weight(1f),
                fontWeight = if (hasUnread || pingCount > 0) FontWeight.Bold else FontWeight.Normal
            )
            if (pingCount > 0) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier         = Modifier
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = if (pingCount > 9) "9+" else pingCount.toString(),
                        fontSize   = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onError
                    )
                }
            } else if (hasUnread) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
    }
}
