package com.zaffox.discordwear.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onLogOut: () -> Unit = {}
) {
    val context   = LocalContext.current
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()
    val repo      = context.discordApp.repository

    // Load persisted preferences
    var hideInaccessible by remember {
        mutableStateOf(SetupPreferences.getHideInaccessibleChannels(context))
    }

    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showLogoutConfirm) {
        // Confirmation dialog before logout
        ScreenScaffold(scrollState = rememberScalingLazyListState()) {
        
            ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "Log out?",
                        style     = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text(
                        "Your token will be cleared from this device.",
                        style     = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                }
                item {
                    Button(
                        onClick  = {
                            scope.launch {
                                runCatching { repo?.rest?.logout() }
                                repo?.disconnect()
                                SetupPreferences.clearToken(context)
                                context.discordApp.clearRepository()
                                onLogOut()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Log Out") }
                }
                item {
                    Button(
                        onClick  = { showLogoutConfirm = false },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Cancel") }
                }
            }
        }
        return
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "Settings",
                    style     = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )
            }

            // ── Hide inaccessible channels toggle ─────────────────────────────
            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    checked  = hideInaccessible,
                    onCheckedChange = {
                        hideInaccessible = it
                        SetupPreferences.setHideInaccessibleChannels(context, it)
                    },
                    label    = { Text("Hide locked channels", style = MaterialTheme.typography.bodySmall) }
                )
            }

            // ── Log out ───────────────────────────────────────────────────────
            item {
                Button(
                    onClick  = { showLogoutConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Log Out") }
            }

            // ── About ─────────────────────────────────────────────────────────
            item {
                Text(
                    "DiscordWear Alpha",
                    style     = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier  = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}
