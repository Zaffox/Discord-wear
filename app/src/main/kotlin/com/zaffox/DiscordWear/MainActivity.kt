package com.zaffox.discordwear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.material3.MaterialTheme
import com.zaffox.discordwear.screens.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Re-hydrate repository from saved token on cold start
        val token = SetupPreferences.getToken(this)
        if (token != null) {
            discordApp.initRepository(token)
        }

        setContent {
            MaterialTheme {
                val navController = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(
                    navController    = navController,
                    startDestination = "home"
                ) {
                    composable("home") {
                        HomeScreen(
                            onNavigateToDms = { navController.navigate("DMs") },
                            onNavigateToServers = { navController.navigate("servers") },
                            onNavigateToWelcome = { navController.navigate("Welcome") },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToChat = { chId, chName ->
                                navController.navigate("chatscreen/$chId/$chName")
                            }
                        )
                    }
                    composable("Welcome") {
                        WelcomeScreen(
                            onSetupComplete = {
                                navController.navigate("home") {
                                    popUpTo("Welcome") { inclusive = true }
                                }
                            }
                        )
                    }
                    // chatscreen/{channelId}/{channelName}
                    composable("chatscreen/{channelId}/{channelName}") { back ->
                        val channelId   = back.arguments?.getString("channelId")   ?: return@composable
                        val channelName = back.arguments?.getString("channelName") ?: channelId
                        ChatScreen(channelId = channelId, channelName = channelName)
                    }
                    // ServerChannels/{guildId}/{guildName}
                    composable("ServerChannels/{guildId}/{guildName}") { back ->
                        val guildId   = back.arguments?.getString("guildId")   ?: return@composable
                        val guildName = back.arguments?.getString("guildName") ?: guildId
                        ServerChannels(
                            guildId              = guildId,
                            guildName            = guildName,
                            onNavigateToChatScreen = { chId, chName ->
                                navController.navigate("chatscreen/$chId/$chName")
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen()
                    }
                    composable("DMs") {
                        DmsScreen(onNavigateToChatScreen = { chId, chName ->
                            navController.navigate("chatscreen/$chId/$chName")
                        })
                    }
                    composable("servers") {
                        ServerScreen(onNavigateToChannels = { gId, gName ->
                            navController.navigate("ServerChannels/$gId/$gName")
                        })
                    }
                }
            }
        }
    }
}
