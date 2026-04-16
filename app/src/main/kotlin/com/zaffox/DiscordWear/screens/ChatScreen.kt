package com.zaffox.discordwear.screens

import android.app.RemoteInput
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.zaffox.discordwear.api.*
import com.zaffox.discordwear.discordApp
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.zaffox.discordwear.R

private const val INPUT_KEY = "message_input"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    channelId: String,
    channelName: String,
    guildId: String? = null,
    currentUserId: String = ""
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

    val allMessages by (repo?.messages ?: return).collectAsState()
    val messages = allMessages[channelId].orEmpty()

    var loading      by remember { mutableStateOf(messages.isEmpty()) }
    var sendError    by remember { mutableStateOf("") }
    var showPicker   by remember { mutableStateOf(false) }
    var tab      by remember { mutableStateOf(0) } // 0 = emoji, 1 = stickers

    // Name lookup maps for mention rendering
    val currentUser by repo.currentUser.collectAsState()
    val guilds      by repo.guilds.collectAsState()
    val myId = currentUser?.id ?: currentUserId
    val hasNitro = currentUser?.hasNitro ?: false
    
    val userNames = remember(messages) {
        messages.flatMap { it.mentionedUsers + it.author } 
                .associate { it.id to it.displayName } 
    }
    val channelNames = remember { repo.getChannelNames() } 


    LaunchedEffect(channelId) {
        if (messages.isEmpty()) {
            scope.launch { repo.loadMessages(channelId); loading = false }
        } else loading = false
    }

    // ── Text input launcher ───────────────────────────────────────────────────
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
        val ri = RemoteInput.Builder(INPUT_KEY)
            .setLabel("Message #$channelName")
            .wearableExtender {
                setEmojisAllowed(true)
                setInputActionType(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            }.build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(ri))
        inputLauncher.launch(intent)
    }

    // ── Emoji/sticker picker overlay ──────────────────────────────────────────
    if (showPicker) {
        EmojiStickerScreen(
            tab = tab,
            guildId = guildId,
            onEmojiPicked = { insertText ->
                showPicker = false
                scope.launch {
                    repo.sendMessage(channelId, insertText)
                        .onFailure { sendError = "Failed: ${it.message}" }
                }
            },
            onStickerPicked = { stickerId ->
                showPicker = false
                scope.launch {
                    repo.sendSticker(channelId, stickerId)
                        .onFailure { sendError = "Failed: ${it.message}" }
                }
            }
        )
        return
    }

    // ── Main chat view ────────────────────────────────────────────────────────
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
                        isOwn       = messages[index].author.id == myId,
                        imageLoader = imageLoader,
                        userNames = userNames, 
                        channelNames = channelNames,
                        onReact     = { emoji ->
                            scope.launch { repo.toggleReaction(channelId, messages[index].id, emoji) }
                        }
                    )
                }
            }

            if (sendError.isNotEmpty()) {
                item {
                    Text(sendError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Action buttons ────────────────────────────────────────────────
            item {
                Button(
                    onClick  = { openInput() },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Message #$channelName") }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly // Distributes space between buttons 
                ) {
                    FilledIconButton(
                        onClick  = {
                            showPicker = true
                            tab = 0
                        },
                        modifier = Modifier.height(40.dp),
                        //colors   = IconButtonDefaults.filledTonalButtonColors()
                    ) {//!
                        Icon(
                           painter = painterResource(id = R.drawable.emoji),
                            contentDescription = "Emoji" 
                        )
                    }//add emoji material icon
                    FilledIconButton(
                        onClick  = {
                            showPicker = true
                            tab = 1
                        },
                        modifier = Modifier.height(40.dp),
                        //colors   = IconButtonDefaults.filledTonalButtonColors()
                    ) { 
                        Icon(
                            painter = painterResource(id = R.drawable.sticker),
                            contentDescription = "Stickers" 
                        )
                    } //add sticker material icon
                }
            }
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    msg: DiscordMessage,
    isOwn: Boolean,
    imageLoader: ImageLoader,
    userNames: Map<String, String>,//!
    channelNames: Map<String, String>,//!
    onReact: (ReactionEmoji) -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isOwn) {
            DiscordAvatar(url = msg.author.avatarUrl(32), imageLoader = imageLoader, size = 22.dp)
            Spacer(Modifier.width(4.dp))
        }

        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.widthIn(max = 155.dp)
        ) {
            Text(
                text  = msg.author.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (msg.type == 19 && msg.referencedMessage != null) {//!
                ReplyPreview(msg.referencedMessage, imageLoader, userNames) 
            } 

            if (msg.type == 23) {//!
                ForwardPreview(msg, imageLoader, userNames) //!
            } else {//!
                if (msg.content.isNotBlank()) {
                    MessageContent(
                        content = msg.content,
                        imageLoader = imageLoader,
                        context = context,
                        userNames = userNames,//!
                        channelNames = channelNames//!
                    )
                }//!
            }

            msg.attachments.filter { it.isImage }.forEach { att ->
                MediaImage(att.proxyUrl, att.filename, imageLoader)
            }
            msg.stickers.filter { it.isDisplayable }.forEach { s ->
                MediaImage(s.imageUrl, s.name, imageLoader, size = 80.dp)
            }
            msg.embeds.forEach { embed ->
                EmbedCard(embed, imageLoader)
            }
            

            // Reactions row
            if (msg.reactions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    msg.reactions.forEach { reaction ->
                        ReactionChip(reaction = reaction, imageLoader = imageLoader, onClick = {
                            onReact(reaction.emoji)
                        })
                    }
                }
            }
        }
    }
}

//!!
@Composable 
private fun ReplyPreview(
    ref: DiscordMessage, 
    imageLoader: ImageLoader, 
    userNames: Map<String, String> 
) {
    val preview = when {
        ref.content.isNotBlank() -> ref.content.take(60)
        ref.stickers.isNotEmpty() -> "[Sticker: ${ref.stickers.first().name}]"
        ref.attachments.isNotEmpty() -> "[Image]" else -> "[Message]" 
    } 
    Row(
        modifier = Modifier
            .fillMaxWidth() 
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), 
                shape = RoundedCornerShape(4.dp) 
            ) 
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Small avatar 
        DiscordAvatar(url = ref.author.avatarUrl(16), imageLoader = imageLoader, size = 12.dp) 
        Text( text = "${ref.author.displayName}: $preview", 
             style = MaterialTheme.typography.bodyExtraSmall, 
             color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1 
            ) 
    } 
    Spacer(Modifier.height(2.dp)) 
}//!!

@Composable
private fun ForwardPreview(
    msg: DiscordMessage, 
    imageLoader: ImageLoader, 
    userNames: Map<String, String> 
) {
    Column( 
        modifier = Modifier 
            .fillMaxWidth() 
            .background( 
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = RoundedCornerShape(4.dp) 
            ) 
            .padding(6.dp) 
    ) {
        Text(
            text = "â†ª Forwarded", 
            style = MaterialTheme.typography.labelSmall, 
            color = MaterialTheme.colorScheme.onSurfaceVariant 
        ) 
        if (msg.forwardedAuthor != null) {
            Text(
                text = msg.forwardedAuthor.displayName,
                style = MaterialTheme.typography.labelSmall 
            ) 
        } 
        if (!msg.forwardedContent.isNullOrBlank()) {
            Text(
                text = msg.forwardedContent,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic 
            ) 
        } 
    } 
}

// ── Content: text + mentions + emoji + links ──────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageContent(
    content: String,
    imageLoader: ImageLoader,
    context: Context
) {
    val parts = remember(content) { ContentParser.parse(content) }

    FlowRow(modifier = Modifier.fillMaxWidth()) {
        parts.forEach { part ->
            when (part) {
                is ContentParser.Part.PlainText -> {
                    Text(text = part.text, style = MaterialTheme.typography.bodySmall)
                }
                is ContentParser.Part.CustomEmoji -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(part.url).crossfade(true).build(),
                        imageLoader        = imageLoader,
                        contentDescription = part.name,
                        modifier           = Modifier.size(18.dp)
                    )
                }
                is ContentParser.Part.UserMention -> {
                    val annotated = buildAnnotatedString {
                        withStyle(SpanStyle(
                            color      = MaterialTheme.colorScheme.primary,
                            background = MaterialTheme.colorScheme.primaryContainer
                        )) { append("@${part.displayName}") }
                    }
                    Text(text = annotated, style = MaterialTheme.typography.bodySmall)
                }
                is ContentParser.Part.RoleMention -> {
                    val annotated = buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                            append("@${part.roleName}")
                        }
                    }
                    Text(text = annotated, style = MaterialTheme.typography.bodySmall)
                }
                is ContentParser.Part.ChannelMention -> {
                    val annotated = buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                            append("#${part.channelName}")
                        }
                    }
                    Text(text = annotated, style = MaterialTheme.typography.bodySmall)
                }
                is ContentParser.Part.Link -> {
                    val annotated = buildAnnotatedString {
                        withStyle(SpanStyle(
                            color          = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline
                        )) { append(part.url) }
                    }
                    Text(
                        text     = annotated,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(part.url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        }
                    )
                }
            }
        }
    }
}

// ── Reaction chip ─────────────────────────────────────────────────────────────

@Composable
private fun ReactionChip(reaction: Reaction, imageLoader: ImageLoader, onClick: () -> Unit) {
    val bgColor = if (reaction.me)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .height(22.dp)
            .background(color = bgColor, shape = RoundedCornerShape(11.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val imgUrl = reaction.emoji.imageUrl
            if (imgUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imgUrl).crossfade(true).build(),
                    imageLoader        = imageLoader,
                    contentDescription = reaction.emoji.name,
                    modifier           = Modifier.size(14.dp)
                )
            } else {
                Text(reaction.emoji.name, fontSize = 12.sp)
            }
            Text(
                text     = reaction.count.toString(),
                style    = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun MediaImage(url: String, contentDesc: String, imageLoader: ImageLoader, size: Dp = 120.dp) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url).crossfade(true).build(),
        imageLoader        = imageLoader,
        contentDescription = contentDesc,
        contentScale       = ContentScale.Fit,
        modifier           = Modifier.padding(top = 2.dp).widthIn(max = size).heightIn(max = size)
    )
}

@Composable
private fun EmbedCard(embed: Embed, imageLoader: ImageLoader) {
    val imgUrl = embed.displayImageUrl ?: return
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
            modifier           = Modifier.widthIn(max = 140.dp).heightIn(max = 140.dp)
        )
    }
}

@Composable
private fun DiscordAvatar(url: String, imageLoader: ImageLoader, size: Dp) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url).crossfade(true).build(),
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size)//.clip(CircleShape) //make avatar circle
    )
}
