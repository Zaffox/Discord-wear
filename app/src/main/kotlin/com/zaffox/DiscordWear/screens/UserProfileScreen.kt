package com.zaffox.discordwear.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
fun UserProfileScreen(
    userId: String,
    initialUser: DiscordUser? = null,
    onNavigateToChat: (channelId: String, channelName: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val imageLoader = remember { ImageLoader.Builder(context).build() }
    var user by remember { mutableStateOf(initialUser) }
    var loading by remember { mutableStateOf(initialUser == null) }
    var dmLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val presences by (repo?.presences ?: return).collectAsState()
    val presence = presences[userId]
    val currentUser by repo.currentUser.collectAsState()
    val isSelf = currentUser?.id == userId

    BackHandler(onBack = onBack)

    LaunchedEffect(userId) {
        if (repo == null) return@LaunchedEffect
        loading = true
        repo.fetchUserProfile(userId)
            .onSuccess { user = it; loading = false }
            .onFailure {
                loading = false
                if (initialUser == null) error = "Could not load profile"
            }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                val u = user
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(70.dp)
                    ) {
                        val bannerUrl = u?.bannerUrl(480)
                        if (bannerUrl != null) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(bannerUrl).crossfade(true).build(),
                                imageLoader = imageLoader,
                                contentDescription = "Banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF5865F2), Color(0xFF3B429C))
                                        )
                                    )
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileAvatar(
                            user = u,
                            imageLoader = imageLoader,
                            size = 44,
                            presence = presence
                        )
                    }
                }
            }

l            item {
                val u = user
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else if (u != null) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            text = u.displayName,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (u.username.isNotEmpty()) {
                            Text(
                                text = "@${u.username}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            if (presence != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusDot(status = presence.status, size = 8)
                        val statusLabel = when (presence.status) {
                            OnlineStatus.ONLINE -> "Online"
                            OnlineStatus.IDLE -> "Idle"
                            OnlineStatus.DND -> "Do Not Disturb"
                            OnlineStatus.INVISIBLE, OnlineStatus.OFFLINE -> "Offline"
                        }
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val customText = presence.customStatusText
                if (!customText.isNullOrBlank()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = customText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            val bio = user?.bio
            if (!bio.isNullOrBlank()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "ABOUT ME",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = bio,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            if (error.isNotEmpty()) {
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            if (!isSelf && user != null) {
                item {
                    Button(
                        onClick = {
                            if (dmLoading) return@Button
                            dmLoading = true
                            val u = user ?: return@Button
                            scope.launch {
                                repo.openDmWithUser(userId)
                                    .onSuccess { channel ->
                                        dmLoading = false
                                        onNavigateToChat(channel.id, u.displayName)
                                    }
                                    .onFailure {
                                        dmLoading = false
                                        error = "Could not open DM"
                                    }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(40.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        enabled = !dmLoading
                    ) {
                        if (dmLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Text("Message")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ProfileAvatar(
    user: DiscordUser?,
    imageLoader: ImageLoader,
    size: Int,
    presence: UserPresence?
) {
    val sizeDp = size.dp
    val blurple = Color(0xFF5865F2)
    val initial = user?.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val avatarUrl = user?.avatarUrl(128)

    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(sizeDp + 4.dp)
                .background(MaterialTheme.colorScheme.background, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUrl != null) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(avatarUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(sizeDp).clip(CircleShape),
                    error = {
                        Box(
                            modifier = Modifier
                                .size(sizeDp)
                                .background(blurple, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(initial, color = Color.White, fontSize = (size * 0.4f).sp)
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(sizeDp)
                        .background(blurple, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initial, color = Color.White, fontSize = (size * 0.4f).sp)
                }
            }
        }

        if (presence != null) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(2.dp)
            ) {
                StatusDot(status = presence.status, size = 8)
            }
        }
    }
}

@Composable
private fun StatusDot(status: OnlineStatus, size: Int) {
    val color = when (status) {
        OnlineStatus.ONLINE -> Color(0xFF23A559)
        OnlineStatus.IDLE -> Color(0xFFF0B232)
        OnlineStatus.DND -> Color(0xFFF23F43)
        OnlineStatus.INVISIBLE, OnlineStatus.OFFLINE -> Color(0xFF80848E)
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}
