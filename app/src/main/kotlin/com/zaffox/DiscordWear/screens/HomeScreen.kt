package com.zaffox.discordwear.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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

@Composable
fun HomeScreen(
    onNavigateToDms: () -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToWelcome: () -> Unit,
    onNavigateToChat: (channelId: String, channelName: String, guildId: String?) -> Unit
) {
    val context   = LocalContext.current
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) {
        if (!SetupPreferences.isSetupComplete(context)) {
            onNavigateToWelcome()
        }
    }

    val repo        = context.discordApp.repository
    val currentUser by (repo?.currentUser ?: return).collectAsState()
    val pings       by repo.pings.collectAsState()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {

            item {
                Text(
                    text  = if (currentUser != null) "Hi, ${currentUser!!.displayName}" else "Discord",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick  = onNavigateToDms,
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Direct Messages") }
            }

            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick  = onNavigateToServers,
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Servers") }
            }

            // ── Pings panel ───────────────────────────────────────────────────
            if (pings.isNotEmpty()) {
                item {
                    Text(
                        text     = "@  Mentions",
                        style    = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                items(pings.size) { index ->
                    val ping = pings[index]
                    PingCard(ping = ping, onClick = {
                        onNavigateToChat(
                            ping.message.channelId,
                            ping.channelName,
                            ping.message.guildId
                        )
                    })
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
        }
    }
}

@Composable
private fun PingCard(ping: Ping, onClick: () -> Unit) {
    val location = if (ping.guildName != null) "${ping.guildName} • #${ping.channelName}"
                   else "DM • ${ping.channelName}"

    TitleCard(
        modifier = Modifier.fillMaxWidth(),
        onClick  = onClick,
        title    = {
            Text(
                text  = ping.message.author.displayName,
                style = MaterialTheme.typography.titleSmall
            )
        },
        time = {
            Text(
                text  = location,
                style = MaterialTheme.typography.bodyExtraSmall
            )
        }
    ) {
        Text(
            text  = ping.message.content.take(80),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
