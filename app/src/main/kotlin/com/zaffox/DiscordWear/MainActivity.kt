package com.zaffox.discordwear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.zaffox.discordwear.screens.*

class MainActivity : ComponentActivity() {
    var activeChannelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = SetupPreferences.getToken(this)
        if (token != null) discordApp.initRepository(token)

        setContent {
            MaterialTheme {
                AppScaffold(timeText = { TimeText() }) {
                    val navController = rememberSwipeDismissableNavController()
                    SwipeDismissableNavHost(
                        navController = navController,
                        startDestination = if (token != null) "home" else "Welcome"
                    ) {

                        composable("home") {
                            HomeScreen(
                                onNavigateToDms = { navController.navigate("DMs") },
                                onNavigateToServers = { navController.navigate("servers") },
                                onNavigateToWelcome = { navController.navigate("Welcome") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToChat = { chId, chName, guildId ->
                                    val guildSeg = guildId ?: "dm"
                                    navController.navigate("chatscreen/$chId/$chName/$guildSeg")
                                }
                            )
                        }

                        composable("Welcome") {
                            WelcomeScreen(onSetupComplete = {
                                navController.navigate("home") {
                                    popUpTo("Welcome") { inclusive = true }
                                }
                            })
                        }

                        composable("settings") {
                            SettingsScreen(
                                onLogOut = {
                                    navController.navigate("Welcome") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("chatscreen/{channelId}/{channelName}/{guildId}") { back ->
                            val channelId = back.arguments?.getString("channelId")   ?: return@composable
                            val channelName = back.arguments?.getString("channelName") ?: channelId
                            val guildIdArg = back.arguments?.getString("guildId")
                            val guildId = if (guildIdArg == "dm") null else guildIdArg
                            activeChannelId = channelId

                            ChatScreen(
                                channelId = channelId,
                                channelName = channelName,
                                guildId = guildId
                            )
                        }

                        composable("ServerChannels/{guildId}/{guildName}") { back ->
                            val guildId = back.arguments?.getString("guildId")   ?: return@composable
                            val guildName = back.arguments?.getString("guildName") ?: guildId
                            activeChannelId = null
                            ServerChannels(
                                guildId = guildId,
                                guildName = guildName,
                                onNavigateToChatScreen = { chId, chName ->
                                    navController.navigate("chatscreen/$chId/$chName/$guildId")
                                }
                            )
                        }

                        composable("DMs") {
                            activeChannelId = null
                            DmsScreen(onNavigateToChatScreen = { chId, chName ->
                                navController.navigate("chatscreen/$chId/$chName/dm")
                            })
                        }

                        composable("servers") {
                            activeChannelId = null
                            ServerScreen(onNavigateToChannels = { gId, gName ->
                                navController.navigate("ServerChannels/$gId/$gName")
                            })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        discordApp.repository?.refreshOnResume(activeChannelId)
    }
}
