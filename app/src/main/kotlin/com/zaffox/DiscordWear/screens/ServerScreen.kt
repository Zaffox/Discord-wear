package com.zaffox.discordwear.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.zaffox.discordwear.R
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.api.Guild
import com.zaffox.discordwear.api.Ping
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun ServerScreen(onNavigateToChannels: (guildId: String, guildName: String) -> Unit) {
    val context = LocalContext.current
    val repo = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val menuState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val imageLoader = remember { ImageLoader.Builder(context).build() }

    val guilds by (repo?.guilds ?: return).collectAsState()
    val pings by repo.pings.collectAsState()
    val readState by repo.readState.collectAsState()
    val serverPings = remember(pings) { pings.filter { it.guildName != null } }

    // Map guildId -> total mention count, using channelGuildCache for attribution
    val channelGuilds = remember(readState) { repo.getChannelGuilds() }
    val guildMentionCounts = remember(readState, channelGuilds) {
        val counts = mutableMapOf<String, Int>()
        readState.forEach { (channelId, state) ->
            if (state.mentionCount > 0) {
                val guildId = channelGuilds[channelId] ?: return@forEach
                counts[guildId] = (counts[guildId] ?: 0) + state.mentionCount
            }
        }
        counts
    }
    var loading by remember { mutableStateOf(guilds.isEmpty()) }

    var pinnedIds by remember { mutableStateOf(SetupPreferences.getPinnedServers(context)) }
    var hiddenIds by remember { mutableStateOf(SetupPreferences.getHiddenServers(context)) }
    var showHidden by remember { mutableStateOf(false) }
    var menuGuild by remember { mutableStateOf<Guild?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            if (guilds.isEmpty()) repo.refreshGuilds()
            else scope.launch { repo.refreshGuilds() }
            loading = false
        }
    }

    val sortedGuilds = remember(guilds, pinnedIds, hiddenIds, showHidden) {
        val visible = guilds.filter { showHidden || !hiddenIds.contains(it.id) }
        val pinned = visible.filter { pinnedIds.contains(it.id) }
        val rest = visible.filter { !pinnedIds.contains(it.id) }
        pinned + rest
    }

    val activeMenu = menuGuild
    if (activeMenu != null) {
        val isPinned = pinnedIds.contains(activeMenu.id)
        val isHidden = hiddenIds.contains(activeMenu.id)
        ScreenScaffold(scrollState = menuState) {
            ScalingLazyColumn(state = menuState, modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        activeMenu.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Button(
                        onClick = {
                            SetupPreferences.togglePinnedServer(context, activeMenu.id)
                            pinnedIds = SetupPreferences.getPinnedServers(context)
                            menuGuild = null
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Icon(painter = painterResource(id = if (isPinned) R.drawable.unpin else R.drawable.pin),tint = Color.White, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPinned) "Unpin" else "Pin to top")
                    }
                }
                item {
                    Button(
                        onClick = {
                            SetupPreferences.toggleHiddenServer(context, activeMenu.id)
                            hiddenIds = SetupPreferences.getHiddenServers(context)
                            menuGuild = null
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Icon(painter = painterResource(id = if (isHidden) R.drawable.unhide else R.drawable.hide), tint = Color.White, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isHidden) "Unhide" else "Hide")
                    }
                }
                item {
                    Button(
                        onClick = { menuGuild = null },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Cancel") }
                }
            }
        }
        return
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item { Text("Servers", style = MaterialTheme.typography.titleMedium) }

            if (serverPings.isNotEmpty()) {
                item {
                    Text(
                        text = "@  Mentions",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(serverPings.size) { index ->
                    val ping = serverPings[index]
                    PingCard(ping = ping, onClick = {
                        onNavigateToChannels(ping.message.guildId ?: return@PingCard, ping.guildName ?: return@PingCard)
                    })
                }
            }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (sortedGuilds.isEmpty()) {
                item { Text("No servers found.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(sortedGuilds.size) { index ->
                    val guild = sortedGuilds[index]
                    val isPinned = pinnedIds.contains(guild.id)
                    val isHidden = hiddenIds.contains(guild.id)
                    ServerButton(
                        guild = guild,
                        imageLoader = imageLoader,
                        isPinned = isPinned,
                        isHidden = isHidden,
                        mentionCount = guildMentionCounts[guild.id] ?: 0,
                        onClick = { if (!isHidden) onNavigateToChannels(guild.id, guild.name) },
                        onLongClick = { menuGuild = guild }
                    )
                }

                if (hiddenIds.isNotEmpty()) {
                    item {
                        Button(
                            onClick = { showHidden = !showHidden },
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            colors = ButtonDefaults.filledTonalButtonColors()
                        ) {
                            Text(
                                if (showHidden) "Hide hidden servers" else "Show hidden (${hiddenIds.size})",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerButton(
    guild: Guild,
    imageLoader: ImageLoader,
    isPinned: Boolean,
    isHidden: Boolean,
    mentionCount: Int = 0,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val bannerUrl = guild.bannerUrl()
    val iconUrl = guild.iconUrl()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (bannerUrl != null && !isHidden) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(bannerUrl).crossfade(true).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(0.55f), Color.Black.copy(0.30f))
                    )
                )
            )
        } else {
            Box(
                modifier = Modifier.matchParentSize().background(
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (isHidden) 0.4f else 1f)
                )
            )
        }

        Row(
            modifier = Modifier.matchParentSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isPinned) Icon(painter = painterResource(id = R.drawable.pin),tint = Color.White, contentDescription = "Pinned", modifier = Modifier.size(10.dp))

            if (iconUrl != null && !isHidden) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(iconUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier.size(32.dp).background(
                        if (isHidden) Color(0xFF5865F2).copy(alpha = 0.4f) else Color(0xFF5865F2),
                        CircleShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = guild.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color = Color.White.copy(alpha = if (isHidden) 0.4f else 1f),
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Text(
                text = guild.name,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    isHidden -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    bannerUrl != null -> Color.White
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // Red mention badge — top-right corner of the button
        if (mentionCount > 0 && !isHidden) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp)
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .background(Color(0xFFF23F43), CircleShape)
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (mentionCount > 99) "99+" else mentionCount.toString(),
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PingCard(ping: Ping, onClick: () -> Unit) {
    TitleCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        title = {
            Text(
                text = "${ping.guildName} • #${ping.channelName}",
                style = MaterialTheme.typography.titleSmall
            )
        },
        time = {
            Text(
                text = ping.message.author.displayName,
                style = MaterialTheme.typography.bodyExtraSmall
            )
        }
    ) {
        Text(
            text = ping.message.content.take(80),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
