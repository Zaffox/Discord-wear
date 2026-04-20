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
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.api.Guild
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun ServerScreen(onNavigateToChannels: (guildId: String, guildName: String) -> Unit) {
    val context   = LocalContext.current
    val repo      = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()

    val imageLoader = remember { ImageLoader.Builder(context).build() }

    val guilds  by (repo?.guilds ?: return).collectAsState()
    var loading by remember { mutableStateOf(guilds.isEmpty()) }

    // Mutable sets that trigger recomposition
    var pinnedIds by remember { mutableStateOf(SetupPreferences.getPinnedServers(context)) }
    var hiddenIds by remember { mutableStateOf(SetupPreferences.getHiddenServers(context)) }
    var showHidden by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            if (guilds.isEmpty()) {
                repo.refreshGuilds()
            } else {
                scope.launch { repo.refreshGuilds() }
            }
            loading = false
        }
    }

    // Sort: pinned first (in original order), then rest (hidden excluded unless showHidden)
    val sortedGuilds = remember(guilds, pinnedIds, hiddenIds, showHidden) {
        val visible = guilds.filter { showHidden || !hiddenIds.contains(it.id) }
        val pinned  = visible.filter { pinnedIds.contains(it.id) }
        val rest    = visible.filter { !pinnedIds.contains(it.id) }
        pinned + rest
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item { Text("Servers", style = MaterialTheme.typography.titleMedium) }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (sortedGuilds.isEmpty() && !showHidden) {
                item { Text("No servers found.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(sortedGuilds.size) { index ->
                    val guild  = sortedGuilds[index]
                    val isPinned = pinnedIds.contains(guild.id)
                    val isHidden = hiddenIds.contains(guild.id)
                    ServerButton(
                        guild       = guild,
                        imageLoader = imageLoader,
                        isPinned    = isPinned,
                        isHidden    = isHidden,
                        onClick     = { if (!isHidden) onNavigateToChannels(guild.id, guild.name) },
                        onPin       = {
                            SetupPreferences.togglePinnedServer(context, guild.id)
                            pinnedIds = SetupPreferences.getPinnedServers(context)
                        },
                        onHide      = {
                            SetupPreferences.toggleHiddenServer(context, guild.id)
                            hiddenIds = SetupPreferences.getHiddenServers(context)
                        }
                    )
                }

                // Show/hide hidden servers toggle at the bottom
                if (hiddenIds.isNotEmpty()) {
                    item {
                        Button(
                            onClick  = { showHidden = !showHidden },
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            colors   = ButtonDefaults.filledTonalButtonColors()
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
    onClick: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit
) {
    val context   = LocalContext.current
    val bannerUrl = guild.bannerUrl()
    val iconUrl   = guild.iconUrl()

    var showMenu by remember { mutableStateOf(false) }

    if (showMenu) {
        // Context menu overlay
        ScreenScaffold(scrollState = rememberScalingLazyListState()) {
            ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        guild.name,
                        style    = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Button(
                        onClick  = { onPin(); showMenu = false },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors()
                    ) { Text(if (isPinned) "📌 Unpin" else "📌 Pin to top") }
                }
                item {
                    Button(
                        onClick  = { onHide(); showMenu = false },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors()
                    ) { Text(if (isHidden) "👁 Unhide" else "🙈 Hide") }
                }
                item {
                    Button(
                        onClick  = { showMenu = false },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Cancel") }
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .combinedClickable(
                onClick      = onClick,
                onLongClick  = { showMenu = true }
            )
            .then(if (isHidden) Modifier.background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)) else Modifier)
    ) {
        // ── Background ────────────────────────────────────────────────────────
        if (bannerUrl != null && !isHidden) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(bannerUrl).crossfade(true).build(),
                imageLoader        = imageLoader,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.30f))
                        )
                    )
            )
        } else {
            Box(modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainer))
        }

        // ── Content row ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pin indicator
            if (isPinned) {
                Text("📌", fontSize = 10.sp)
            }

            // Server icon
            if (iconUrl != null && !isHidden) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(iconUrl).crossfade(true).build(),
                    imageLoader        = imageLoader,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(32.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (isHidden) Color(0xFF5865F2).copy(alpha = 0.4f) else Color(0xFF5865F2),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text     = guild.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color    = Color.White.copy(alpha = if (isHidden) 0.4f else 1f),
                        fontSize = 14.sp,
                        style    = MaterialTheme.typography.labelSmall
                    )
                }
            }

            val nameColor = when {
                isHidden  -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                bannerUrl != null -> Color.White
                else      -> MaterialTheme.colorScheme.onSurface
            }

            Text(
                text     = guild.name,
                style    = MaterialTheme.typography.bodySmall,
                color    = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
