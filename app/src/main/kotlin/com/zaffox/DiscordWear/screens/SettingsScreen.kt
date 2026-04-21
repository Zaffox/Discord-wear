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
import com.zaffox.discordwear.ApkInstaller
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.UpdateChecker
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onLogOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val repo = context.discordApp.repository

    var hideInaccessible by remember {
        mutableStateOf(SetupPreferences.getHideInaccessibleChannels(context))
    }
    var sendAnimatedAsGif by remember {
        mutableStateOf(SetupPreferences.getSendAnimatedAsGif(context))
    }
    var spoilerRevealOnTap by remember {
        mutableStateOf(SetupPreferences.getSpoilerRevealOnTap(context))
    }
    var showMentionBadges by remember {
        mutableStateOf(SetupPreferences.getShowMentionBadges(context))
    }
    var compactMode by remember {
        mutableStateOf(SetupPreferences.getCompactMode(context))
    }

    var showLogoutConfirm by remember { mutableStateOf(false) }
    val updateState by UpdateChecker.state.collectAsState()

    if (showLogoutConfirm) {
        ScreenScaffold(scrollState = rememberScalingLazyListState()) {
            ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "Log out?",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text(
                        "Your token will be cleared from this device.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                }
                item {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { repo?.rest?.logout() }
                                repo?.disconnect()
                                SetupPreferences.clearAll(context)
                                context.discordApp.clearRepository()
                                onLogOut()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Log Out") }
                }
                item {
                    Button(
                        onClick = { showLogoutConfirm = false },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
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
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Text(
                    "GENERAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    checked = showMentionBadges,
                    onCheckedChange = {
                        showMentionBadges = it
                        SetupPreferences.setShowMentionBadges(context, it)
                    },
                    label = { Text("Show mention badges", style = MaterialTheme.typography.bodySmall) }
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    checked = spoilerRevealOnTap,
                    onCheckedChange = {
                        spoilerRevealOnTap = it
                        SetupPreferences.setSpoilerRevealOnTap(context, it)
                    },
                    label = {
                        Column {
                            Text("Spoiler reveal on tap", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "||spoiler|| text hidden until tapped",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    checked = compactMode,
                    onCheckedChange = {
                        compactMode = it
                        SetupPreferences.setCompactMode(context, it)
                    },
                    label = { 
                    label = { Text("Compact messages", style = MaterialTheme.typography.bodySmall) }
                        Text("Compact messages", style = MaterialTheme.typography.bodySmall) 
                         Text(
                            "Hides profile pictures",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            item {
                Text(
                    "VENCORD",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    checked = sendAnimatedAsGif,
                    onCheckedChange = {
                        sendAnimatedAsGif = it
                        SetupPreferences.setSendAnimatedAsGif(context, it)
                    },
                    label = {
                        Column {
                            Text("Send animated emoji as GIF", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Posts a GIF link instead of <a:emoji:id>",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    checked = hideInaccessible,
                    enables = false, // tempoary until fixed
                    onCheckedChange = {
                        hideInaccessible = it
                        SetupPreferences.setHideInaccessibleChannels(context, it)
                    },
                    label = { 
                    label = { Text("Hide locked channels", style = MaterialTheme.typography.bodySmall) }
                        Text("Hide locked channels", style = MaterialTheme.typography.bodySmall) 
                         Text(
                            "Not working with user API",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            item {
                Text(
                    "ACCOUNT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                Button(
                    onClick = { showLogoutConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Log Out") }
            }

            item {
                Text(
                    "UPDATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                val statusText = when (val s = updateState) {
                    is UpdateChecker.UpdateState.Idle -> "v${UpdateChecker.CURRENT_VERSION}"
                    is UpdateChecker.UpdateState.Checking -> "Checking…"
                    is UpdateChecker.UpdateState.UpToDate -> "v${UpdateChecker.CURRENT_VERSION} — up to date"
                    is UpdateChecker.UpdateState.UpdateAvailable -> "v${s.release.tagName} available!"
                    is UpdateChecker.UpdateState.Error -> "Check failed: ${s.message}"
                }
                val statusColor = when (updateState) {
                    is UpdateChecker.UpdateState.UpdateAvailable -> MaterialTheme.colorScheme.primary
                    is UpdateChecker.UpdateState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = { UpdateChecker.checkNow(context) },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    enabled = updateState !is UpdateChecker.UpdateState.Checking,
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Text(
                        if (updateState is UpdateChecker.UpdateState.Checking) "Checking…" else "Check for updates",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (updateState is UpdateChecker.UpdateState.UpdateAvailable) {
                val release = (updateState as UpdateChecker.UpdateState.UpdateAvailable).release
                var downloading by remember { mutableStateOf(false) }
                var downloadError by remember { mutableStateOf("") }

                if (release.apkUrl != null) {
                    item {
                        Button(
                            onClick = {
                                if (!downloading) {
                                    downloading = true
                                    downloadError = ""
                                    scope.launch {
                                        ApkInstaller.downloadAndInstall(context, release.apkUrl)
                                            .onFailure { downloadError = it.message ?: "Download failed" }
                                        downloading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            enabled = !downloading,
                            colors = ButtonDefaults.buttonColors()
                        ) {
                            Text(
                                if (downloading) "Downloading…" else "Download & Install APK",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (downloadError.isNotEmpty()) {
                        item {
                            Text(
                                downloadError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = { ApkInstaller.openInPhoneBrowser(context, release.htmlUrl) },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Text("Open release on phone", style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Text(
                        "Open on phone to download, then sideload via ADB:\nadb install DiscordWear.apk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
                }
            }

            item {
                Text(
                    "DiscordWear v${UpdateChecker.CURRENT_VERSION}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}
