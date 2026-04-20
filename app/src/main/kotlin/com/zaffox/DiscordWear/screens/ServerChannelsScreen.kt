package com.zaffox.discordwear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.api.ChannelUnreadState
import com.zaffox.discordwear.api.CategoryGroup
import com.zaffox.discordwear.api.Channel
import com.zaffox.discordwear.api.ChannelType
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

/** Returns the icon prefix for a channel based on its type and name. */
private fun channelIcon(ch: Channel, allChannels: List<Channel>): String {
    return when {
        // Announcement / News channel
        ch.type == ChannelType.GUILD_NEWS -> "📣"
        // Rules channel heuristic: named "rules", "rules-and-info", "server-rules", etc.
        // Also only show 🔖 for the FIRST rules-named channel across the whole server.
        ch.name.contains("rule", ignoreCase = true) &&
            allChannels.firstOrNull { it.name.contains("rule", ignoreCase = true) }?.id == ch.id -> "📋"
        // Default text channel
        else -> "#"
    }
}

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

    // Read setting: hide channels user has no access to
    val hideInaccessible  = remember { SetupPreferences.getHideInaccessibleChannels(context) }
    val showMentionBadges = remember { SetupPreferences.getShowMentionBadges(context) }
    val readState by (repo?.readState ?: return).collectAsState()

    var groups  by remember { mutableStateOf<List<CategoryGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error   by remember { mutableStateOf("") }

    LaunchedEffect(guildId) {
        scope.launch {
            // Load cached channels first so the list shows instantly
            val cached = repo?.getCachedChannels(guildId, hideInaccessible)
            if (!cached.isNullOrEmpty()) {
                groups = cached
                loading = false
                repo?.cacheChannelNames(cached)
            }
            // Then refresh from network
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

    // Flat list of all channels for rules-channel detection (only one rules channel per server)
    val allChannels = remember(groups) { groups.flatMap { it.channels } }

    ScreenScaffold(scrollState = listState) {
    
        ScalingLazyColumn(state = listState) {
            item {
                Text(guildName, style = MaterialTheme.typography.titleMedium)
            }

            when {
                loading -> item { CircularProgressIndicator() }
                error.isNotEmpty()  -> item {
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
                            val icon = channelIcon(ch, allChannels)
                            if (ch.hasAccess) {
                                val unread = if (showMentionBadges) readState[ch.id] else null
                                val mentionCount = unread?.mentionCount ?: 0
                                Button(
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    colors   = ButtonDefaults.filledTonalButtonColors(),
                                    onClick  = { onNavigateToChatScreen(ch.id, ch.name) }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            text = "$icon ${ch.name}",
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (mentionCount > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                                    .background(Color(0xFFF23F43), CircleShape)
                                                    .padding(horizontal = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (mentionCount > 99) "99+" else mentionCount.toString(),
                                                    color = Color.White,
                                                    fontSize = 8.sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                )
                                            }
                                        } else if (unread != null) {
                                            // Unread dot (no ping)
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .background(Color.White.copy(alpha = 0.8f), CircleShape)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Inaccessible channel — shown greyed out, not tappable
                                Button(
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    enabled  = false,
                                    colors   = ButtonDefaults.filledTonalButtonColors(),
                                    onClick  = {}
                                ) {
                                    Text("🔒 ${ch.name}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

