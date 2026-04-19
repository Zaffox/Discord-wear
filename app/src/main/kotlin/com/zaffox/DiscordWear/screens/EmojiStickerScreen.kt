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
import com.zaffox.discordwear.R
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch

// Common unicode emoji for reactions when in react mode
private val COMMON_UNICODE_EMOJI = listOf(
    "👍", "👎", "❤️", "😂", "😮", "😢", "😡", "🎉",
    "🔥", "✅", "👀", "🙏", "💯", "⭐", "💀", "🫡",
    "😭", "😍", "🤔", "💪", "🤣", "😎", "🥹", "🫶"
)

/**
 * Emoji + sticker picker.
 *
 * [reactMode]            when true, shows a quick unicode emoji grid at top for reactions,
 *                        followed by guild custom emojis. Sticker tab is hidden.
 * [onEmojiPicked]        called with insert string e.g. "<:name:id>" or unicode char
 * [onUnicodeEmojiPicked] called specifically for plain unicode emoji (so caller can distinguish)
 * [onStickerPicked]      called with the sticker ID
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EmojiStickerScreen(
    tab: Int,
    guildId: String?,
    hasNitro: Boolean = false,
    reactMode: Boolean = false,
    onEmojiPicked: (String) -> Unit,
    onUnicodeEmojiPicked: (String) -> Unit,
    onStickerPicked: (String) -> Unit
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

    LaunchedEffect(guildId) {
        if (guildId != null && repo != null) {
            scope.launch {
                emojis = repo.getGuildEmojis(guildId)
                if (!reactMode) {
                    stickers = repo.getGuildStickers(guildId)
                }
                loading = false
            }
        } else {
            loading = false
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {

            // Header
            item {
                Text(
                    if (reactMode) "React" else if (tab == 0) "Emojis" else "Stickers",
                    style     = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )
            }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (tab == 0 || reactMode) {

                // ── Unicode quick-react row (react mode only) ──────────────────
                if (reactMode) {
                    val unicodeRows = COMMON_UNICODE_EMOJI.chunked(6)
                    items(unicodeRows.size) { rowIdx ->
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            unicodeRows[rowIdx].forEach { emoji ->
                                Text(
                                    text     = emoji,
                                    fontSize = 20.sp,
                                    modifier = Modifier
                                        .clickable { onUnicodeEmojiPicked(emoji) }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }


                    // Divider before guild emojis
                    if (emojis.isNotEmpty()) {
                         //temporary logic
                        item {
                            Text(
                                "Server Emojis",
                                style     = MaterialTheme.typography.labelSmall,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier  = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                    }
                }

                // ── Guild emoji grid ───────────────────────────────────────────
                if (emojis.isEmpty() && !reactMode) {
                    item {
                        Text(
                            "No custom emojis.",
                            style     = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }
                } else if (emojis.isNotEmpty()) {
                    val rows = emojis.chunked(4)
                    items(rows.size) { rowIdx ->
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rows[rowIdx].forEach { emoji ->
                                val isAnimated = emoji.animated
                                val isLocked   = isAnimated && !hasNitro

                                Box {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(emoji.imageUrl).crossfade(true).build(),
                                        imageLoader        = imageLoader,
                                        contentDescription = emoji.name,
                                        contentScale       = ContentScale.Fit,
                                        modifier           = Modifier
                                            .size(32.dp)
                                            .clickable { 
                                                if (!isLocked) {
                                                    onEmojiPicked(emoji.insertText) 
                                                }
                                            }
                                    )

                                    if (isLocked) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.lock),
                                            contentDescription = "Locked",
                                            modifier = Modifier
                                                .size(30.dp)
                                                .align(Alignment.Center)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Sticker list ───────────────────────────────────────────────
                if (stickers.isEmpty()) {
                    item {
                        Text(
                            "No stickers.",
                            style     = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    val rows = stickers.chunked(3)
                    items(rows.size) { rowIdx ->
                        Row(
                            modifier              = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            }
                        }
                    }
                }
            }
        }
    }
}
