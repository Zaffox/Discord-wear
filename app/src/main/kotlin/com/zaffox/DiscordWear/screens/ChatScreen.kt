package com.zaffox.discordwear.screens

import android.app.RemoteInput
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.input.pointer.pointerInput
 
import android.os.Bundle
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
    val readState by repo.readState.collectAsState()

    var loading      by remember { mutableStateOf(messages.isEmpty()) }
    var sendError    by remember { mutableStateOf("") }
    var showPicker   by remember { mutableStateOf(false) }
    var tab          by remember { mutableStateOf(0) } // 0 = emoji, 1 = stickers

    // Pending text to send (emoji/text composed before sending)
    var pendingText  by remember { mutableStateOf("") }

    // Reply state
    var replyingTo   by remember { mutableStateOf<DiscordMessage?>(null) }

    // Message options dialog state
    var selectedMsg  by remember { mutableStateOf<DiscordMessage?>(null) }

    // React picker state — show emoji picker in "react" mode
    var reactingToMsg by remember { mutableStateOf<DiscordMessage?>(null) }

    val currentUser by repo.currentUser.collectAsState()
    val myId = currentUser?.id ?: currentUserId
    val hasNitro = currentUser?.hasNitro ?: false


    LaunchedEffect(channelId) {
        if (messages.isEmpty()) {
            scope.launch {
                repo.loadMessages(channelId)
                loading = false
            }
        } else loading = false
    }

    // Scroll to first unread message once messages are loaded
    val scrolledToUnread = remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, loading) {
        if (!loading && !scrolledToUnread.value && messages.isNotEmpty()) {
            scrolledToUnread.value = true
            val lastRead = readState[channelId]
            if (lastRead != null) {
                // Find the first message after the last-read message ID
                // Messages are oldest-first; IDs are snowflakes (numerically ordered)
                val firstUnreadIdx = messages.indexOfFirst { it.id > lastRead }
                if (firstUnreadIdx > 0) {
                    // +2 accounts for the channel title item and reply banner items
                    scope.launch { listState.animateScrollToItem(firstUnreadIdx + 1) }
                } else if (firstUnreadIdx == -1) {
                    // All messages are read — scroll to bottom
                    scope.launch { listState.animateScrollToItem(messages.size) }
                }
            } else {
                // No read state — scroll to bottom (newest)
                scope.launch { listState.animateScrollToItem(messages.size) }
            }
        }
    }

    // ── Text input launcher ───────────────────────────────────────────────────
    val inputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Only process if user confirmed (RESULT_OK) — back button returns RESULT_CANCELED
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val bundle: Bundle? = RemoteInput.getResultsFromIntent(
            result.data ?: return@rememberLauncherForActivityResult
        )
        val text = bundle?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        //if (!text.isNullOrBlank()) {//make it so it does not auto send
            val combined = if (pendingText.isNotBlank()) "$pendingText $text".trim() else text
        pendingText = " $combined"
        /*
        if (!text.isNullOrBlank()) {
            val replyTarget = replyingTo
            val combined = if (pendingText.isNotBlank()) "$pendingText $text".trim() else text
            scope.launch {
                if (replyTarget != null) {
                    repo.sendReply(channelId, combined, replyTarget.id)
                        .onFailure { sendError = "Failed: ${it.message}" }
                    replyingTo = null
                } else {
                    repo.sendMessage(channelId, combined)
                        .onFailure { sendError = "Failed: ${it.message}" }
                }
                pendingText = ""
            }
        }*/
    }

    fun openInput(replyTarget: DiscordMessage? = null) {
        val label = if (replyTarget != null)
            "Reply to ${replyTarget.author.displayName}"
        else
            "Message #$channelName"
        val ri = RemoteInput.Builder(INPUT_KEY)
            .setLabel(label)
            .wearableExtender {
                setEmojisAllowed(true)
                setInputActionType(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            }.build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(ri))
        inputLauncher.launch(intent)
    }

    // ── Edit input launcher ───────────────────────────────────────────────────
    var editingMsg by remember { mutableStateOf<DiscordMessage?>(null) }
    val editLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val bundle: Bundle? = RemoteInput.getResultsFromIntent(
            result.data ?: return@rememberLauncherForActivityResult
        )
        val text = bundle?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        val target = editingMsg
        if (!text.isNullOrBlank() && target != null) {
            scope.launch {
                repo.editMessage(channelId, target.id, text)
                    .onFailure { sendError = "Failed: ${it.message}" }
                editingMsg = null
            }
        }
    }

    fun openEdit(msg: DiscordMessage) {
        editingMsg = msg
        val ri = RemoteInput.Builder(INPUT_KEY)
            .setLabel("Edit message")
            .wearableExtender {
                setEmojisAllowed(true)
                setInputActionType(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            }.build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(ri))
        editLauncher.launch(intent)
    }

    // ── Emoji/sticker picker overlay ──────────────────────────────────────────
    if (showPicker) {
        val isReactMode = reactingToMsg != null
        EmojiStickerScreen(
            tab = if (isReactMode) 0 else tab, // reactions always use emoji tab
            guildId = guildId,
            hasNitro = hasNitro, //disable animated emoji without nitro
            reactMode = isReactMode,
            onEmojiPicked = { insertText ->
                showPicker = false
                val target = reactingToMsg
                if (target != null) {
                    // React mode: toggle reaction with the picked emoji
                    // insertText is like "<:name:id>" — extract for custom emoji, or raw unicode
                    val reactionEmoji = parseInsertTextToReactionEmoji(insertText)
                    if (reactionEmoji != null) {
                        scope.launch {
                            try {
                                repo.toggleReaction(channelId, target.id, reactionEmoji)
                            } catch (e: Exception) {
                                sendError = "Failed: ${e.message}"
                            }
                        }
                    }
                    reactingToMsg = null
                } else {
                    // Normal mode: append emoji to pending text, do NOT send yet
                    pendingText = if (pendingText.isBlank()) insertText
                                  else "$pendingText$insertText"
                }
            },
            onUnicodeEmojiPicked = { unicode ->
                showPicker = false
                val target = reactingToMsg
                if (target != null) {
                    val reactionEmoji = ReactionEmoji(id = null, name = unicode, animated = false)
                    scope.launch {
                        try { repo.toggleReaction(channelId, target.id, reactionEmoji) }
                        catch (e: Exception) { sendError = "Failed: ${e.message}" }
                    }
                    reactingToMsg = null
                } else {
                    pendingText = if (pendingText.isBlank()) unicode else "$pendingText$unicode"
                }
            },
            onStickerPicked = { stickerId ->
                showPicker = false
                reactingToMsg = null
                scope.launch {
                    try {
                        repo.sendSticker(channelId, stickerId)
                    } catch (e: Exception) {
                       sendError = "Failed: ${e.message}"
                    }
                }
            }
        )
        return
    }

    // ── Message options dialog ────────────────────────────────────────────────
    val msgForOptions = selectedMsg
    if (msgForOptions != null) {
        MessageOptionsDialog(
            msg     = msgForOptions,
            isOwn   = msgForOptions.author.id == myId,
            onReply = {
                replyingTo  = msgForOptions
                selectedMsg = null
                openInput(msgForOptions)
            },
            onCopy  = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("message", msgForOptions.content)
                )
                selectedMsg = null
            },
            onEdit  = {
                selectedMsg = null
                openEdit(msgForOptions)
            },
            onDelete = {
                scope.launch {
                    repo.deleteMessage(channelId, msgForOptions.id)
                        .onFailure { sendError = "Failed: ${it.message}" }
                }
                selectedMsg = null
            },
            onReact = {
                // Open emoji picker in react mode
                reactingToMsg = msgForOptions
                selectedMsg   = null
                showPicker    = true
            },
            onDismiss = { selectedMsg = null }
        )
        return
    }

    // ── Main chat view ────────────────────────────────────────────────────────
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Text("#$channelName", style = MaterialTheme.typography.titleMedium) }

            // Reply indicator banner
            if (replyingTo != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            "↩ ${replyingTo!!.author.displayName}: ${replyingTo!!.content.take(30)}",
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "✕",
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.clickable { replyingTo = null }.padding(4.dp)
                        )
                    }
                }
            }

            // Pending text preview
            if (pendingText.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            pendingText,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 2
                        )
                        Row {
                            // Send button
                            Text(
                                "▶",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        val text = pendingText
                                        pendingText = ""
                                        scope.launch {
                                            val replyTarget = replyingTo
                                            if (replyTarget != null) {
                                                repo.sendReply(channelId, text, replyTarget.id)
                                                    .onFailure { sendError = "Failed: ${it.message}" }
                                                replyingTo = null
                                            } else {
                                                repo.sendMessage(channelId, text)
                                                    .onFailure { sendError = "Failed: ${it.message}" }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                            // Clear button
                            Text(
                                "✕",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable { pendingText = "" }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

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
                        onReact     = { emoji ->
                            scope.launch { repo.toggleReaction(channelId, messages[index].id, emoji) }
                        },
                        onSwipeLeft = {
                            replyingTo = messages[index]
                            openInput(messages[index])
                        },
                        onLongPress = {
                            selectedMsg = messages[index]
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
                ) { 
                    if (pendingText == "") {
                        Text("Message #$channelName") 
                    } else {
                        Text("$pendingText")
                    }
                  
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilledIconButton(
                        onClick  = {
                            reactingToMsg = null
                            showPicker = true
                            tab = 0
                        },
                        modifier = Modifier.height(40.dp).width(40.dp),
                    ) {
                        Icon(
                           painter = painterResource(id = R.drawable.emoji),
                            contentDescription = "Emoji"
                        )
                    }
                    FilledIconButton(
                        onClick  = {
                            reactingToMsg = null
                            showPicker = true
                            tab = 1
                        },
                        modifier = Modifier.height(40.dp).width(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.sticker),
                            contentDescription = "Stickers"
                        )
                    }
                    FilledIconButton(
                        onClick = {
                            val text = pendingText
                            pendingText = ""
                            scope.launch {
                               val replyTarget = replyingTo
                               if (replyTarget != null) {
                                   repo.sendReply(channelId, text, replyTarget.id)
                                       .onFailure { sendError = "Failed: ${it.message}" }
                                   replyingTo = null
                               } else {
                                   repo.sendMessage(channelId, text)
                                       .onFailure { sendError = "Failed: ${it.message}" }
                               }
                            }
                        },
                        modifier = Modifier.height(40.dp).width(40.dp),
                        enabled = pendingText.isNotBlank()
                    ) {
                        Text("▶", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

// ── Helper: parse "<:name:id>" or "<a:name:id>" or plain unicode into ReactionEmoji ──

fun parseInsertTextToReactionEmoji(insertText: String): ReactionEmoji? {
    // Custom animated: <a:name:id>
    val animatedMatch = Regex("""<a:(\w+):(\d+)>""").find(insertText)
    if (animatedMatch != null) {
        return ReactionEmoji(
            id       = animatedMatch.groupValues[2],
            name     = animatedMatch.groupValues[1],
            animated = true
        )
    }
    // Custom static: <:name:id>
    val customMatch = Regex("""<:(\w+):(\d+)>""").find(insertText)
    if (customMatch != null) {
        return ReactionEmoji(
            id       = customMatch.groupValues[2],
            name     = customMatch.groupValues[1],
            animated = false
        )
    }
    // Plain unicode emoji
    if (insertText.isNotBlank()) {
        return ReactionEmoji(id = null, name = insertText, animated = false)
    }
    return null
}

// ── Message options dialog ────────────────────────────────────────────────────

@Composable
private fun MessageOptionsDialog(
    msg: DiscordMessage,
    isOwn: Boolean,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "Message by ${msg.author.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Button(
                    onClick  = onReply,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("↩ Reply") }
            }
            item {
                Button(
                    onClick  = onReact,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("😀 React") }
            }
            item {
                Button(
                    onClick  = onCopy,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors()
                ) { Text("📋 Copy Text") }
            }
            if (isOwn) {
                item {
                    Button(
                        onClick  = onEdit,
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("✏️ Edit") }
                }
                item {
                    Button(
                        onClick  = onDelete,
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("🗑️ Delete") }
                }
            }
            item {
                Button(
                    onClick  = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.outlinedButtonColors()
                ) { Text("Cancel") }
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
    onReact: (ReactionEmoji) -> Unit,
    onSwipeLeft: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    var offsetX by remember { mutableStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX < -40f) onSwipeLeft()
                        offsetX = 0f
                    },
                    onHorizontalDrag = { _, delta ->
                        if (delta < 0) offsetX += delta
                    }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.changes.any { it.pressed }) {
                            val timeout = 500L
                            val endTime = System.currentTimeMillis() + timeout
                            var lifted = false
                            while (System.currentTimeMillis() < endTime) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.all { !it.pressed }) { lifted = true; break }
                            }
                            if (!lifted) {
                                onLongPress()
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
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

            // ── Reply reference ───────────────────────────────────────────────
            val ref = msg.referencedMessage
            if (msg.type == 19 && ref != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "↩ ${ref.author.displayName}: ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        ref.content.take(40).ifBlank { "📎 attachment" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(2.dp))
            }

            // ── Forwarded message ─────────────────────────────────────────────
            if (msg.type == 23) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(6.dp)
                ) {
                    Text(
                        "📨 Forwarded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    // Author always comes from referenced_message — snapshots never include it
                    val fwdAuthorName = msg.referencedMessage?.author?.displayName ?: "Unknown"
                    Text(
                        fwdAuthorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // Content: prefer snapshot content (forwardedContent), fall back to referenced_message
                    val fwdText = msg.forwardedContent?.takeIf { it.isNotBlank() }
                        ?: msg.referencedMessage?.content?.takeIf { it.isNotBlank() }
                    if (!fwdText.isNullOrBlank()) {
                        MessageContent(content = fwdText, imageLoader = imageLoader, context = context)
                    }
                    // Forwarded attachments (from snapshot first, then referenced_message)
                    val fwdAtts = msg.forwardedAttachments.ifEmpty {
                        msg.referencedMessage?.attachments ?: emptyList()
                    }
                    fwdAtts.filter { it.isImage }.forEach { att ->
                        MediaImage(att.proxyUrl, att.filename, imageLoader)
                    }
                    // Forwarded embeds
                    val fwdEmbeds = msg.forwardedEmbeds.ifEmpty {
                        msg.referencedMessage?.embeds ?: emptyList()
                    }
                    fwdEmbeds.forEach { embed ->
                        EmbedCard(embed, imageLoader)
                    }
                    // Forwarded stickers (from referenced_message since snapshots omit them)
                    val fwdStickers = msg.referencedMessage?.stickers ?: emptyList()
                    fwdStickers.filter { it.isDisplayable }.forEach { s ->
                        MediaImage(s.imageUrl, s.name, imageLoader, size = 80.dp)
                    }
                    // Placeholder only if truly nothing to show
                    if (fwdText.isNullOrBlank() && fwdAtts.isEmpty() && fwdEmbeds.isEmpty() && fwdStickers.isEmpty()) {
                        Text(
                            "(no preview available)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Normal message content
                if (msg.content.isNotBlank()) {
                    MessageContent(content = msg.content, imageLoader = imageLoader, context = context)
                }
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
                // Custom guild emoji — load from CDN
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imgUrl).crossfade(true).build(),
                    imageLoader        = imageLoader,
                    contentDescription = reaction.emoji.name,
                    modifier           = Modifier.size(14.dp)
                )
            } else {
                // Unicode emoji — render using EmojiCompat/text with explicit emoji font size.
                // reaction.emoji.name IS the unicode character (e.g. "👍") from the Discord API.
                Text(
                    text     = reaction.emoji.name,
                    fontSize = 13.sp,
                    // Disable font scaling so the emoji renders at the correct size
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                    lineHeight = 14.sp,
                    modifier = Modifier.wrapContentSize()
                )
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
    Column(modifier = Modifier.padding(top = 2.dp)) {
        embed.title?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        embed.description?.let {
            Text(it.take(120), style = MaterialTheme.typography.bodySmall)
        }
        val imgUrl = embed.displayImageUrl
        if (imgUrl != null) {
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
}

@Composable
private fun DiscordAvatar(url: String, imageLoader: ImageLoader, size: Dp) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url).crossfade(true).build(),
        imageLoader        = imageLoader,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        modifier           = Modifier.size(size)
    )
}
