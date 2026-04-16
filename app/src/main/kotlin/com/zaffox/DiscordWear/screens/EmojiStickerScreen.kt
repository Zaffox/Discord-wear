package com.zaffox.discordwear.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.zaffox.discordwear.api.GuildEmoji
import com.zaffox.discordwear.api.StickerItem
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

/**
 * A simple emoji + sticker picker.
 *
 * Shows two sections:
 *   1. Emojis — guild custom emojis displayed in a grid (3 per row via FlowRow)
 *   2. Stickers — guild stickers one per row with name
 *
 * [onEmojiPicked]   called with the insert string e.g. "<:name:id>"
 * [onStickerPicked] called with the sticker ID
 * Add Logic for Animated Emoji with no nitro :P
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EmojiStickerScreen(
    tab: Int,
    guildId: String?,
    onEmojiPicked: (String) -> Unit,
    onStickerPicked: (String) -> Unit
    //add 'tab' variable to have different button for Chat screen
) {
    val context   = LocalContext.current
    val repo      = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    add(ImageDecoderDecoder.Factory())
                else
                    add(GifDecoder.Factory())
            }.build()
    }

    var emojis   by remember { mutableStateOf<List<GuildEmoji>>(emptyList()) }
    var stickers by remember { mutableStateOf<List<StickerItem>>(emptyList()) }
    var loading  by remember { mutableStateOf(true) }
    //var tab      by remember { mutableStateOf(0) } // 0 = emoji, 1 = stickers

    LaunchedEffect(guildId) {
        if (guildId != null) {
            scope.launch {
                repo?.rest?.getGuildEmojis(guildId)?.onSuccess { emojis = it }
                repo?.rest?.getGuildStickers(guildId)?.onSuccess { stickers = it.filter { s -> s.isDisplayable } }
            }
        }
        loading = false
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            // Tab selector
            if (tab == 0) {
                item {
                    Text("Emojis", style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            } else if (tab == 1) {
                item {
                    Text("Stickers", style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            if (loading) {
                item { CircularProgressIndicator() }
            } else if (tab == 0) {
                // ── Emoji grid ─────────────────────────────────────────────────
                if (emojis.isEmpty()) {
                    item { Text("No custom emojis.", style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                } else {
                    // Chunk into rows of 4
                    val rows = emojis.chunked(4)
                    items(rows.size) { rowIdx ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)//.center
                        ) {
                            rows[rowIdx].forEach { emoji ->
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(emoji.imageUrl).crossfade(true).build(),
                                    imageLoader        = imageLoader,
                                    contentDescription = emoji.name,
                                    contentScale       = ContentScale.Fit,
                                    modifier           = Modifier
                                        .size(32.dp)
                                        .clickable { onEmojiPicked(emoji.insertText) }
                                )
                            }
                        }
                    }
                }
            } else {
                // ── Sticker list ───────────────────────────────────────────────
                if (stickers.isEmpty()) {//make multiple rows?
                    item { Text("No stickers.", style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                } else {
                     val rows = stickers.chunked(3)
                    items(rows.size) { rowIdx ->
                        //val sticker = stickers[idx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                //.clickable { onStickerPicked(sticker.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)//.center
                        ) {
                            rows[rowIdx].forEach { sticker ->
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(sticker.imageUrl).crossfade(true).build(),
                                    imageLoader        = imageLoader,
                                    contentDescription = sticker.name,
                                    contentScale       = ContentScale.Fit,
                                    modifier           = Modifier
                                        .size(48.dp)
                                        .clickable { onStickerPicked(sticker.id) }
                                )
                                //Text(sticker.name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
