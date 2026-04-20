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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item { Text("Servers", style = MaterialTheme.typography.titleMedium) }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (guilds.isEmpty()) {
                item { Text("No servers found.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(guilds.size) { index ->
                    val guild = guilds[index]
                    ServerButton(
                        guild       = guild,
                        imageLoader = imageLoader,
                        onClick     = { onNavigateToChannels(guild.id, guild.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerButton(
    guild: Guild,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    val context   = LocalContext.current
    val bannerUrl = guild.bannerUrl()
    val iconUrl   = guild.iconUrl()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .clickable { onClick() }
    ) {
        // ── Background: banner image or solid surface colour ──────────────────
        if (bannerUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(bannerUrl)
                    .crossfade(true)
                    .build(),
                imageLoader        = imageLoader,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.matchParentSize()
            )
            // Dark gradient scrim so the name stays readable over any banner
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.30f)
                            )
                        )
                    )
            )
        } else {
            // No banner — plain surface tonal fill matching the rest of the UI
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }

        // ── Content row: icon + name ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Server icon — pure-Compose blurple circle with initial when no icon URL
            if (iconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(iconUrl)
                        .crossfade(true)
                        .build(),
                    imageLoader        = imageLoader,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFF5865F2), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text     = guild.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color    = Color.White,
                        fontSize = 14.sp,
                        style    = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Name — white + dark pill backing when over a banner, normal colour otherwise
            val nameColor = if (bannerUrl != null) Color.White
                            else MaterialTheme.colorScheme.onSurface

            val nameMod = if (bannerUrl != null)
                Modifier
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            else
                Modifier.weight(1f)

            Text(
                text     = guild.name,
                style    = MaterialTheme.typography.bodySmall,
                color    = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = nameMod
            )
        }
    }
}
