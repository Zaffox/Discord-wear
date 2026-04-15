package com.zaffox.discordwear.screens

import android.app.RemoteInput
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.zaffox.discordwear.api.Attachment
import com.zaffox.discordwear.api.DiscordMessage
import com.zaffox.discordwear.api.Embed
import com.zaffox.discordwear.api.EmojiParser
import com.zaffox.discordwear.api.StickerItem
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch

private const val INPUT_KEY = "message_input"

@Composable
fun ChatScreen(
    channelId: String,
    channelName: String,
    currentUserId: String = ""
) {
    val context   = LocalContext.current
    val repo      = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope     = rememberCoroutineScope()

    // GIF-aware Coil loader (shared per composition)
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    add(ImageDecoderDecoder.Factory())
                else
                    add(GifDecoder.Factory())
            }
            .build()
    }

    val allMessages by (repo?.messages ?: return).collectAsState()
    val messages = allMessages[channelId].orEmpty()

    var loading   by remember { mutableStateOf(messages.isEmpty()) }
    var sendError by remember { mutableStateOf("") }

    LaunchedEffect(channelId) {
        if (messages.isEmpty()) {
            scope.launch { repo.loadMessages(channelId); loading = false }
        } else {
            loading = false
        }
    }

    val inputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bundle: Bundle? = RemoteInput.getResultsFromIntent(
            result.data ?: return@rememberLauncherForActivityResult
        )
        val text = bundle?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            scope.launch {
                repo.sendMessage(channelId, text).onFailure { sendError = "Failed: ${it.message}" }
            }
        }
    }

    fun openInput() {
        val remoteInput = RemoteInput.Builder(INPUT_KEY)
            .setLabel("Message #$channelName")
            .wearableExtender {
                setEmojisAllowed(true)
                setInputActionType(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            }.build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        inputLauncher.launch(intent)
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Text("#$channelName", style = MaterialTheme.typography.titleMedium) }

            when {
                loading -> item { CircularProgressIndicator() }
                messages.isEmpty() -> item {
                    Text("No messages yet.", style = MaterialTheme.typography.bodySmall)
                }
                else -> items(messages.size) { index ->
                    MessageBubble(
                        msg         = messages[index],
                        isOwn       = messages[index].author.id == currentUserId,
                        imageLoader = imageLoader
                    )
                }
            }

            if (sendError.isNotEmpty()) {
                item {
                    Text(sendError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Button(
                    onClick  = { openInput() },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Message #$channelName") }
            }
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(msg: DiscordMessage, isOwn: Boolean, imageLoader: ImageLoader) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Avatar
        if (!isOwn) {
            DiscordAvatar(url = msg.author.avatarUrl(32), size = 22.dp)//Crop to circle
            Spacer(Modifier.width(4.dp))
        }

        Column(
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 150.dp)
        ) {
            // Author name
            Text(
                text  = msg.author.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Text content (with custom emoji inline)
            if (msg.content.isNotBlank()) {
                MessageContent(content = msg.content, imageLoader = imageLoader)
            }

            // Image attachments
            msg.attachments.filter { it.isImage }.forEach { att ->
                MediaImage(url = att.proxyUrl, contentDesc = att.filename, imageLoader = imageLoader)
            }
            //Add @mention logic <@user id>
            //Add #channel logic <#channel id>
            // Stickers
            msg.stickers.filter { it.isDisplayable }.forEach { sticker ->
                MediaImage(
                    url         = sticker.imageUrl,
                    contentDesc = sticker.name,
                    imageLoader = imageLoader,
                    size        = 80.dp
                )
            }

            // Embeds (image/gifv/rich with image)
            msg.embeds.forEach { embed ->
                EmbedCard(embed = embed, imageLoader = imageLoader)
            }
        }
    }
}

// ── Inline text + custom emoji ────────────────────────────────────────────────

@Composable
private fun MessageContent(content: String, imageLoader: ImageLoader) {
    val parts = remember(content) { EmojiParser.parse(content) }
    // Wrap emoji inline with text — use a Row that wraps
    androidx.compose.foundation.layout.FlowRow {
        parts.forEach { part ->
            if (part.text != null && part.text.isNotEmpty()) {
                Text(text = part.text, style = MaterialTheme.typography.bodySmall)
            } else if (part.emojiUrl != null) {
                AsyncImage(
                    model       = ImageRequest.Builder(LocalContext.current)
                        .data(part.emojiUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = part.emojiName,
                    modifier    = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Media image (attachment / sticker) ───────────────────────────────────────

@Composable
private fun MediaImage(
    url: String,
    contentDesc: String,
    imageLoader: ImageLoader,
    size: androidx.compose.ui.unit.Dp = 120.dp
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        imageLoader        = imageLoader,
        contentDescription = contentDesc,
        contentScale       = ContentScale.Fit,
        modifier           = Modifier
            .padding(top = 2.dp)
            .widthIn(max = size)
            .heightIn(max = size)
    )
}

// ── Embed card ────────────────────────────────────────────────────────────────

@Composable
private fun EmbedCard(embed: Embed, imageLoader: ImageLoader) {
    val imgUrl = embed.displayImageUrl ?: return  // nothing to show

    Column(modifier = Modifier.padding(top = 2.dp)) {
        embed.title?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imgUrl).crossfade(true).build(),
            imageLoader        = imageLoader,
            contentDescription = embed.title ?: "embed",
            contentScale       = ContentScale.Fit,
            modifier           = Modifier
                .widthIn(max = 140.dp)
                .heightIn(max = 140.dp)
        )
    }
}

// ── Circular avatar ───────────────────────────────────────────────────────────

@Composable
private fun DiscordAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url).crossfade(true).build(),
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        modifier           = Modifier
            .size(size)
            .then(Modifier) // clip to circle
    )
}
