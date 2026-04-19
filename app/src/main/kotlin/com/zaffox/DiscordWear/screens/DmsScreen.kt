package com.zaffox.discordwear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.zaffox.discordwear.api.*
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun DmsScreen(
    onNavigateToChatScreen: (channelId: String, channelName: String) -> Unit
) {
    val context   = LocalContext.current
    val repo      = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()

    val imageLoader = remember { ImageLoader.Builder(context).build() }

    if (repo == null) return
    val dmChannels by repo.dmChannels.collectAsState()
    val presences  by repo.presences.collectAsState()
    var loading    by remember { mutableStateOf(dmChannels.isEmpty()) }

    LaunchedEffect(Unit) {
        if (dmChannels.isEmpty()) {
            scope.launch { repo.refreshDmChannels(); loading = false }
        } else {
            loading = false
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item { Text("Direct Messages", style = MaterialTheme.typography.titleMedium) }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (dmChannels.isEmpty()) {
                item { Text("No DMs yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(dmChannels.size) { index ->
                    val dm = dmChannels[index]
                    val recipient = dm.recipients.firstOrNull()
                    val presence  = recipient?.let { presences[it.id] }
                    DmButton(
                        dm          = dm,
                        recipient   = recipient,
                        presence    = presence,
                        imageLoader = imageLoader,
                        onClick     = { onNavigateToChatScreen(dm.id, dm.displayName) }
                    )
                }
            }
        }
    }
}

// ── Status helpers ────────────────────────────────────────────────────────────

private fun OnlineStatus.dotColor(): Color = when (this) {
    OnlineStatus.ONLINE  -> Color(0xFF23A55A)
    OnlineStatus.IDLE    -> Color(0xFFF0B232)
    OnlineStatus.DND     -> Color(0xFFF23F43)
    OnlineStatus.INVISIBLE,
    OnlineStatus.OFFLINE -> Color(0xFF80848E)
}

private fun OnlineStatus.label(): String = when (this) {
    OnlineStatus.ONLINE  -> "Online"
    OnlineStatus.IDLE    -> "Idle"
    OnlineStatus.DND     -> "Do Not Disturb"
    OnlineStatus.INVISIBLE -> "Invisible"
    OnlineStatus.OFFLINE -> "Offline"
}

/** Returns the active platform icon string based on client_status, preferring mobile. */
private fun ClientStatus.platformIcon(): String? = when {
    mobile  != null && mobile  != OnlineStatus.OFFLINE -> "📱"// res/drawable/mobile
    desktop != null && desktop != OnlineStatus.OFFLINE -> "🖥️"// res/drawable/desktop
    else -> null
}

// ── DM Button ────────────────────────────────────────────────────────────────

@Composable
private fun DmButton(
    dm: Channel,
    recipient: DiscordUser?,
    presence: UserPresence?,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    val context       = LocalContext.current
    val avatarUrl     = recipient?.avatarUrl(64)
    val nameplateUrl  = recipient?.nameplateUrl()
    val status        = presence?.status ?: OnlineStatus.OFFLINE
    val initial       = dm.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    val backgroundUrl = nameplateUrl

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        if (backgroundUrl != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context).data(backgroundUrl).crossfade(true).build(),
                imageLoader        = imageLoader,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.matchParentSize(),
                error = {
                    Box(Modifier.matchParentSize())
                }
            )
            // Lighter scrim for nameplates (they're designed to be visible),
            // darker for plain avatar fills
            val scrimAlphaStart = if (nameplateUrl != null) 0.45f else 0.72f
            val scrimAlphaEnd   = if (nameplateUrl != null) 0.20f else 0.45f
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(scrimAlphaStart), Color.Black.copy(scrimAlphaEnd))
                        )
                    )
            )
        } else {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainer))
        }

        // ── Content ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Avatar with status dot
            Box(contentAlignment = Alignment.BottomEnd) {
                if (avatarUrl != null) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build(),
                        imageLoader        = imageLoader,
                        contentDescription = null,
                        alignment = Alignment.CenterEnd,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.size(38.dp).clip(CircleShape),
                        error = {
                            Box(
                                modifier = Modifier.size(38.dp).background(Color(0xFF1E1F22), CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text(initial, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.size(38.dp).background(Color(0xFF1E1F22), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text(initial, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
                // Status dot with dark border ring
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .background(Color(0xFF1E1F22), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(status.dotColor(), CircleShape)
                    )
                }
            }

            // Text column
            Column(modifier = Modifier.weight(1f)) {
                // Name + platform icon
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text       = dm.displayName,
                        style      = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false)
                    )
                    presence?.clientStatus?.platformIcon()?.let { icon ->
                        Text(icon, fontSize = 10.sp)
                    }
                }

                // Status line: custom status text/emoji OR generic status label
                val customText  = presence?.customStatusText
                val customEmoji = presence?.customStatusEmoji
                if (customText != null || customEmoji != null) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Custom emoji — CDN URL or unicode
                        if (customEmoji != null) {
                            if (customEmoji.startsWith("http")) {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context).data(customEmoji).crossfade(true).build(),
                                    imageLoader        = imageLoader,
                                    contentDescription = null,
                                    modifier           = Modifier.size(12.dp)
                                )
                            } else {
                                Text(customEmoji, fontSize = 11.sp, lineHeight = 13.sp)
                            }
                        }
                        if (customText != null) {
                            Text(
                                text     = customText,
                                style    = MaterialTheme.typography.labelSmall,
                                color    = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 9.sp
                            )
                        }
                    }
                } else {
                    Text(
                        text     = status.label(),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = status.dotColor().copy(alpha = 0.9f),
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}
