package com.zaffox.discordwear.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    var loading    by remember { mutableStateOf(dmChannels.isEmpty()) }

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
                    DmButton(dm = dm, onClick = {
                        onNavigateToChatScreen(dm.id, dm.displayName)
                    })
                }
            }
        }
    }
}

@Composable
private fun DmButton(dm: Channel, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.filledTonalButtonColors(),
        onClick  = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text  = dm.displayName,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp)
            )
            if (!dm.lastMessageId.isNullOrEmpty()) {
                Text(
                    text  = "Tap to open",
                    style = TextStyle(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
