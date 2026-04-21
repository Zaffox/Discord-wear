package com.zaffox.discordwear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.api.Ping
import com.zaffox.discordwear.discordApp
import com.zaffox.discordwear.R
import androidx.compose.ui.res.painterResource


@Composable
fun HomeScreen(
    onNavigateToDms: () -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToWelcome: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: (channelId: String, channelName: String, guildId: String?) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) {
        if (!SetupPreferences.isSetupComplete(context)) {
            onNavigateToWelcome()
        }
    }

    val repo = context.discordApp.repository
    val currentUser by (repo?.currentUser ?: return).collectAsState()
    val pings by repo.pings.collectAsState()
    val readState by repo.readState.collectAsState()
    val dmChannels by repo.dmChannels.collectAsState()
    val guilds by repo.guilds.collectAsState()

    // Mention counts for badges
    val dmIds = remember(dmChannels) { dmChannels.map { it.id }.toSet() }
    val dmMentionCount = remember(readState, dmIds) {
        readState.entries.filter { it.key in dmIds }.sumOf { it.value.mentionCount }
    }
    val serverMentionCount = remember(readState, dmIds) {
        readState.entries.filter { it.key !in dmIds }.sumOf { it.value.mentionCount }
    }

    // Mention cards: combine live gateway pings with readState channels that have mention counts
    // so cards appear immediately on load, not only after a new message arrives
    val channelNames = remember(readState) { repo.getChannelNames() }
    val channelGuilds = remember(readState) { repo.getChannelGuilds() }
    data class MentionEntry(val channelId: String, val channelName: String, val guildName: String?, val count: Int, val isDm: Boolean)
    val mentionEntries = remember(readState, dmIds, channelNames, channelGuilds, guilds) {
        readState.entries
            .filter { it.value.mentionCount > 0 }
            .map { (channelId, state) ->
                val isDm = channelId in dmIds
                val chName = channelNames[channelId] ?: channelId
                val guildId = channelGuilds[channelId]
                val guildName = guildId?.let { id -> guilds.firstOrNull { it.id == id }?.name }
                MentionEntry(channelId, chName, guildName, state.mentionCount, isDm)
            }
            .sortedByDescending { it.count }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {

            item {
                Text(
                    text = "Discord",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Box {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavigateToDms,
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Direct Messages") }
                    if (dmMentionCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp, end = 2.dp)
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .background(androidx.compose.ui.graphics.Color(0xFFF23F43), androidx.compose.foundation.shape.CircleShape)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (dmMentionCount > 99) "99+" else dmMentionCount.toString(),
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                Box {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavigateToServers,
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Servers") }
                    if (serverMentionCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp, end = 2.dp)
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .background(androidx.compose.ui.graphics.Color(0xFFF23F43), androidx.compose.foundation.shape.CircleShape)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (serverMentionCount > 99) "99+" else serverMentionCount.toString(),
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (mentionEntries.isNotEmpty() || pings.isNotEmpty()) {
                item {
                    Text(
                        text = "@  Mentions",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                // readState-derived cards (visible from startup)
                items(mentionEntries.size) { index ->
                    val entry = mentionEntries[index]
                    val label = if (entry.isDm) "DM • ${entry.channelName}"
                                else "${entry.guildName ?: "Server"} • #${entry.channelName}"
                    MentionCard(
                        label = label,
                        count = entry.count,
                        onClick = {
                            onNavigateToChat(entry.channelId, entry.channelName, if (entry.isDm) null else channelGuilds[entry.channelId])
                        }
                    )
                }
                // live gateway pings not yet in readState (e.g. arrived after last ack)
                items(pings.size) { index ->
                    val ping = pings[index]
                    // Skip if this channel already covered by a readState card above
                    if (mentionEntries.none { it.channelId == ping.message.channelId }) {
                        PingCard(ping = ping, onClick = {
                            onNavigateToChat(ping.message.channelId, ping.channelName, ping.message.guildId)
                        })
                    }
                }
            } else {
                item {
                    Text(
                        "No mentions yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            item {
                FilledIconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.height(40.dp).width(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.settings),
                            contentDescription = "Settings"
                        )
                    }
              }
        }
    }
}

@Composable
private fun MentionCard(label: String, count: Int, onClick: () -> Unit) {
    TitleCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (count > 99) "99+" else "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFFF23F43),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) {
        Text(
            text = "Tap to open",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PingCard(ping: Ping, onClick: () -> Unit) {
    val location = if (ping.guildName != null) "${ping.guildName} • #${ping.channelName}"
                   else "DM • ${ping.channelName}"

    TitleCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ping.message.author.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    ) {
        Text(
            text = ping.message.content.take(80),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
