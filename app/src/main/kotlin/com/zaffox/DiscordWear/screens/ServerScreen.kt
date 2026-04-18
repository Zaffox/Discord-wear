package com.zaffox.discordwear.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun ServerScreen(onNavigateToChannels: (guildId: String, guildName: String) -> Unit) {
    val context   = LocalContext.current
    val repo      = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()

    val guilds  by (repo?.guilds ?: return).collectAsState()
    // guilds StateFlow is pre-seeded from disk cache so loading=false if cache exists
    var loading by remember { mutableStateOf(guilds.isEmpty()) }

    LaunchedEffect(Unit) {
        scope.launch {
            if (guilds.isEmpty()) {
                repo.refreshGuilds()
            } else {
                // Silently refresh in background; cache already shows content
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
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.filledTonalButtonColors(),
                        onClick  = { onNavigateToChannels(guild.id, guild.name) }
                    ) {
                        Text(guild.name)
                    }
                }
            }
        }
    }
}
