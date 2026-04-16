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

        val token = SetupPreferences.getToken(this)
        if (token != null) discordApp.initRepository(token)

        setContent {
            MaterialTheme {
                val navController = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(navController = navController, startDestination = "home") {

                    composable("home") {
                        HomeScreen(
                            onNavigateToDms     = { navController.navigate("DMs") },
                            onNavigateToServers = { navController.navigate("servers") },
                            onNavigateToWelcome = { navController.navigate("Welcome") },
                            onNavigateToChat    = { chId, chName, guildId ->
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

                    // chatscreen/{channelId}/{channelName}/{guildId}
                    // guildId = "dm" means no guild (DM)
                    composable("chatscreen/{channelId}/{channelName}/{guildId}") { back ->
                        val channelId   = back.arguments?.getString("channelId")   ?: return@composable
                        val channelName = back.arguments?.getString("channelName") ?: channelId
                        val guildIdArg  = back.arguments?.getString("guildId")
                        val guildId     = if (guildIdArg == "dm") null else guildIdArg
                        ChatScreen(channelId = channelId, channelName = channelName, guildId = guildId)
                    }

                    composable("ServerChannels/{guildId}/{guildName}") { back ->
                        val guildId   = back.arguments?.getString("guildId")   ?: return@composable
                        val guildName = back.arguments?.getString("guildName") ?: guildId
                        ServerChannels(
                            guildId              = guildId,
                            guildName            = guildName,
                            onNavigateToChatScreen = { chId, chName ->
                                navController.navigate("chatscreen/$chId/$chName/$guildId")
                            }
                        )
                    }

                    composable("DMs") {
                        DmsScreen(onNavigateToChatScreen = { chId, chName ->
                            navController.navigate("chatscreen/$chId/$chName/dm")
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
