package com.zaffox.discordwear.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zaffox.discordwear.api.Channel
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun DmsScreen(
    onNavigateToChatScreen: (channelId: String, channelName: String) -> Unit
) {
    val context    = LocalContext.current
    val repo       = context.discordApp.repository
    val listState  = rememberScalingLazyListState()
    val scope      = rememberCoroutineScope()

    val dmChannels by (repo?.dmChannels ?: return).collectAsState()
    val readState  by repo.readState.collectAsState()
    var loading    by remember { mutableStateOf(dmChannels.isEmpty()) }

    val imageLoader = remember { ImageLoader(context) }

    LaunchedEffect(Unit) {
        if (dmChannels.isEmpty()) {
            scope.launch {
                repo.refreshDmChannels()
                loading = false
            }
        } else {
            loading = false
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item {
                Text(
                    "Direct Messages",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (dmChannels.isEmpty()) {
                item { Text("No DMs yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(dmChannels.size) { index ->
                    val dm = dmChannels[index]
                    val hasUnread = run {
                        val lastRead = readState[dm.id]
                        val lastMsg  = dm.lastMessageId
                        lastMsg != null && lastMsg.isNotEmpty() &&
                            (lastRead == null || lastMsg > lastRead)
                    }
                    DmButton(
                        dm          = dm,
                        hasUnread   = hasUnread,
                        imageLoader = imageLoader,
                        onClick     = { onNavigateToChatScreen(dm.id, dm.displayName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DmButton(dm: Channel, hasUnread: Boolean, imageLoader: ImageLoader, onClick: () -> Unit) {
    val context   = LocalContext.current
    val recipient = dm.recipients.firstOrNull()
    val avatarUrl = recipient?.avatarUrl(32)

    Button(
        modifier = Modifier.fillMaxWidth(),
        colors   = if (hasUnread) ButtonDefaults.buttonColors()
                   else ButtonDefaults.filledTonalButtonColors(),
        onClick  = onClick
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            if (avatarUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(avatarUrl).crossfade(true).build(),
                    imageLoader        = imageLoader,
                    contentDescription = dm.displayName,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                )
            } else {
                // Placeholder circle with initial
                androidx.compose.foundation.layout.Box(
                    modifier         = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .then(
                            Modifier.wrapContentSize(Alignment.Center)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text      = dm.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        fontSize  = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Text column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = dm.displayName,
                    style      = TextStyle(
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                        fontSize   = 13.sp
                    ),
                    maxLines   = 1
                )
                if (hasUnread) {
                    Text(
                        text  = "New messages",
                        style = TextStyle(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Unread dot
            if (hasUnread) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .then(Modifier)
                )
            }
        }
    }
}
