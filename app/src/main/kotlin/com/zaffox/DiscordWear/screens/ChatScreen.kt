package com.zaffox.discordwear.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import android.content.Context
import android.media.MediaPlayer
import com.zaffox.discordwear.api.*
import com.zaffox.discordwear.discordApp
import com.zaffox.discordwear.SetupPreferences
import com.zaffox.discordwear.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    val scope          = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

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
    // Collect typing users directly for this channel — avoids stale map lookups
    val typingSet by repo.typingInChannel(channelId).collectAsState(initial = emptySet())

    var loading      by remember { mutableStateOf(true) }
    var sendError    by remember { mutableStateOf("") }
    var showPicker   by remember { mutableStateOf(false) }
    var tab          by remember { mutableStateOf(0) } // 0 = emoji, 1 = stickers

    // Text field input state
    var inputText    by remember { mutableStateOf("") }

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
    val typingUsers = typingSet.filter { it != myId }

    // Vencord / custom settings
    val sendAnimatedAsGif  = remember { SetupPreferences.getSendAnimatedAsGif(context) }
    val spoilerRevealOnTap = remember { SetupPreferences.getSpoilerRevealOnTap(context) }
    val compactMode        = remember { SetupPreferences.getCompactMode(context) }

    // Slowmode — how many seconds must elapse between sends
    val slowModeSecs  = remember(channelId) { repo.getSlowModeSeconds(channelId) }
    var slowRemaining by remember { mutableStateOf(0) }
    // Tick the slowmode countdown every second while active
    LaunchedEffect(slowModeSecs) {
        if (slowModeSecs > 0) {
            while (true) {
                slowRemaining = repo.slowModeRemainingSeconds(channelId)
                if (slowRemaining <= 0) break
                delay(1_000)
            }
        }
    }

    // Check if the user can send messages in this channel
    val canSend = remember(channelId) { repo.canSendMessage(channelId) }


    LaunchedEffect(channelId) {
        // Always load from REST — never skip. If gateway already delivered some
        // messages before the screen opened, loadMessages merges them properly.
        scope.launch {
            repo.loadMessages(channelId)
            loading = false
        }
    }

    // Scroll to the last-read message on first load, so the user sees where they left off
    // with any unread messages visible below.
    val scrolledToUnread = remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, loading) {
        if (!loading && !scrolledToUnread.value && messages.isNotEmpty()) {
            scrolledToUnread.value = true
            val lastRead = readState[channelId]?.lastMessageId
            if (lastRead != null) {
                // Find the last message the user has already read
                // Messages are oldest-first; IDs are snowflakes (numerically ordered)
                val lastReadIdx = messages.indexOfLast { it.id <= lastRead }
                when {
                    lastReadIdx >= 0 -> {
                        // Scroll to the last-read message (+1 for the channel title item)
                        scope.launch { listState.animateScrollToItem(lastReadIdx + 1) }
                    }
                    else -> {
                        // All messages are unread — jump to the very top of the message list
                        scope.launch { listState.animateScrollToItem(1) }
                    }
                }
            } else {
                // No read state — scroll to bottom (newest)
                scope.launch { listState.animateScrollToItem(messages.size + 1) }
            }
        }
    }

    // While this channel is open, mark every newly-arriving message as read
    LaunchedEffect(messages.size) {
        if (!loading) {
            messages.lastOrNull()?.id?.let { repo?.markChannelRead(channelId, it) }
        }
    }

    // ── Edit state ────────────────────────────────────────────────────────────
    var editingMsg by remember { mutableStateOf<DiscordMessage?>(null) }
    var editText   by remember { mutableStateOf("") }

    fun openEdit(msg: DiscordMessage) {
        editingMsg = msg
        editText   = msg.content
    }

    // ── Back handler — dismiss keyboard/picker before navigating away ─────────
    // Only intercept back when an overlay is open; otherwise let it navigate back.
    BackHandler(enabled = showPicker) {
        showPicker = false
        reactingToMsg = null
    }
    BackHandler(enabled = editingMsg != null) {
        editingMsg = null
        editText = ""
    }

    // ── Edit message screen ───────────────────────────────────────────────────
    val msgBeingEdited = editingMsg
    if (msgBeingEdited != null) {
        val editListState = rememberScalingLazyListState()
        ScreenScaffold(scrollState = editListState) {
            ScalingLazyColumn(state = editListState, modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "Edit message",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                item {
                    OutlinedTextField(
                        value           = editText,
                        onValueChange   = { editText = it },
                        modifier        = Modifier.fillMaxWidth(),
                        label           = { Text("Edit", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        textStyle       = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        colors          = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor        = MaterialTheme.colorScheme.primary
                        ),
                        minLines        = 1,
                        maxLines        = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                val text = editText.trim()
                                if (text.isNotBlank()) {
                                    scope.launch {
                                        repo.editMessage(channelId, msgBeingEdited.id, text)
                                            .onFailure { sendError = "Failed: ${it.message}" }
                                        editingMsg = null
                                        editText   = ""
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            colors   = ButtonDefaults.filledTonalButtonColors(),
                            enabled  = editText.isNotBlank()
                        ) { Text("Save") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick  = { editingMsg = null; editText = "" },
                            modifier = Modifier.weight(1f).height(36.dp),
                            colors   = ButtonDefaults.outlinedButtonColors()
                        ) { Text("Cancel") }
                    }
                }
            }
        }
        return
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
                    // Normal mode: optionally rewrite animated emoji as raw GIF link
                    val finalText = if (sendAnimatedAsGif) {
                        rewriteAnimatedEmojiAsGif(insertText)
                    } else insertText
                    val newText = if (inputText.isBlank()) finalText else "$inputText$finalText"
                    inputText   = newText
                    pendingText = newText
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
                    val newText = if (inputText.isBlank()) unicode else "$inputText$unicode"
                    inputText   = newText
                    pendingText = newText
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
                // Focus is on the text field — no need to launch an intent
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
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 1
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenScaffold(scrollState = listState) {
            ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "channel_title") { Text("#$channelName", style = MaterialTheme.typography.titleMedium) }

            // Reply indicator banner
            if (replyingTo != null) {
                item(key = "reply_banner") {
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

            // Pending text preview (shown when text is typed but not yet sent)
            if (pendingText.isNotBlank()) {
                item(key = "pending_text") {
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
                                color    = if (slowRemaining > 0)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        if (slowRemaining > 0) return@clickable
                                        val text = pendingText
                                        pendingText = ""
                                        inputText   = ""
                                        scope.launch {
                                            slowRemaining = slowModeSecs
                                            val replyTarget = replyingTo
                                            if (replyTarget != null) {
                                                repo.sendReply(channelId, text, replyTarget.id)
                                                    .onFailure { sendError = "Failed: ${it.message}" }
                                                replyingTo = null
                                            } else {
                                                repo.sendMessage(channelId, text)
                                                    .onFailure { sendError = "Failed: ${it.message}" }
                                            }
                                            while (true) {
                                                delay(1_000)
                                                slowRemaining = repo.slowModeRemainingSeconds(channelId)
                                                if (slowRemaining <= 0) break
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
                                    .clickable {
                                        pendingText = ""
                                        inputText   = ""
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

            when {
                loading -> item(key = "loading") { CircularProgressIndicator() }
                messages.isEmpty() -> item(key = "empty") {
                    Text("No messages yet.", style = MaterialTheme.typography.bodySmall)
                }
                else -> items(messages.size, key = { messages[it].id }) { index ->
                    val msg     = messages[index]
                    val prevMsg = if (index > 0) messages[index - 1] else null

                    // Parse ISO-8601 timestamps to epoch millis for gap comparison
                    fun String.toEpochMillis(): Long = runCatching {
                        java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli()
                    }.getOrElse { 0L }

                    val gapMs = if (prevMsg != null)
                        msg.timestamp.toEpochMillis() - prevMsg.timestamp.toEpochMillis()
                    else Long.MAX_VALUE

                    val isContinuation = prevMsg != null &&
                        prevMsg.author.id == msg.author.id &&
                        msg.type !in listOf(19, 23) &&
                        prevMsg.type !in listOf(19, 23) &&
                        gapMs < 10 * 60 * 1000L  // break group after 10 minutes

                    MessageBubble(
                        msg            = msg,
                        isOwn          = msg.author.id == myId,
                        isContinuation = isContinuation,
                        imageLoader    = imageLoader,
                        channelNames   = repo.getChannelNames(),
                        compactMode    = compactMode,
                        spoilerRevealOnTap = spoilerRevealOnTap,
                        onReact        = { emoji ->
                            scope.launch { repo.toggleReaction(channelId, msg.id, emoji) }
                        },
                        onSwipeLeft    = {
                            replyingTo = msg
                        },
                        onLongPress    = {
                            selectedMsg = msg
                        }
                    )
                }
            }

            if (sendError.isNotEmpty()) {
                item(key = "send_error") {
                    Text(sendError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Typing indicator ──────────────────────────────────────────────
            if (typingUsers.isNotEmpty()) {
                item(key = "typing_indicator") {
                    val names = typingUsers.mapNotNull { uid -> repo?.getDisplayName(uid) }
                    val label = when {
                        names.isEmpty() -> "Someone is typing…"
                        names.size == 1 -> "${names[0]} is typing…"
                        names.size == 2 -> "${names[0]} and ${names[1]} are typing…"
                        else            -> "Several people are typing…"
                    }
                    Text(
                        text  = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
                }
            }

            // ── Text input field (GBoard / system keyboard) ───────────────────
            item(key = "text_input") {
                if (canSend) {
                    OutlinedTextField(
                        value           = inputText,
                        onValueChange   = { newValue ->
                            inputText   = newValue
                            pendingText = newValue
                        },
                        modifier        = Modifier.fillMaxWidth(),
                        placeholder     = { Text("Message #$channelName", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        textStyle       = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        colors          = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor        = MaterialTheme.colorScheme.primary
                        ),
                        minLines        = 1,
                        maxLines        = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                } else {
                    Text(
                        text     = "🔒 You cannot send messages here",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
            }

            // ── Action buttons (only when user can send) ──────────────────────
            item(key = "action_buttons") {
                if (canSend) {
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
                            val text = inputText.trim()
                            if (text.isBlank() || slowRemaining > 0) return@FilledIconButton
                            inputText   = ""
                            pendingText = ""
                            scope.launch {
                               slowRemaining = slowModeSecs  // optimistic countdown start
                               val replyTarget = replyingTo
                               if (replyTarget != null) {
                                   repo.sendReply(channelId, text, replyTarget.id)
                                       .onFailure { sendError = "Failed: ${it.message}" }
                                   replyingTo = null
                               } else {
                                   repo.sendMessage(channelId, text)
                                       .onFailure { sendError = "Failed: ${it.message}" }
                               }
                               // Tick down after actual send
                               while (true) {
                                   delay(1_000)
                                   slowRemaining = repo.slowModeRemainingSeconds(channelId)
                                   if (slowRemaining <= 0) break
                               }
                            }
                        },
                        modifier = Modifier.height(40.dp).width(40.dp),
                        enabled = inputText.isNotBlank() && slowRemaining <= 0
                    ) {
                        Text("▶", fontSize = 18.sp)
                    }
                }
                } // end if (canSend)
            }
        }
        }  // end ScreenScaffold

        // ── Scroll-to-bottom button ───────────────────────────────────────────
        if (!isAtBottom) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 10.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                FilledIconButton(
                    onClick  = { scope.launch { listState.animateScrollToItem(Int.MAX_VALUE) } },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("↓", fontSize = 16.sp)
                }
            }
        }
    }  // end Box
}

// ── Spoiler text: blurred until tapped ─────────────────────────────────────

@Composable
private fun SpoilerText(text: String, revealOnTap: Boolean) {
    var revealed by remember { mutableStateOf(false) }
    val modifier = if (revealOnTap) Modifier.clickable { revealed = true } else Modifier
    Box(modifier = modifier) {
        Text(
            text  = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (revealed) MaterialTheme.colorScheme.onSurface
                    else          MaterialTheme.colorScheme.onSurface.copy(alpha = 0f)
        )
        if (!revealed) {
            // Draw a solid pill over the text
            Text(
                text  = text.map { if (it == ' ') ' ' else '█' }.joinToString(""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Helper: rewrite animated emoji insert text as a raw GIF URL ──────────────

/**
 * When "send animated emoji as GIF" is enabled, converts <a:name:id> into
 * the raw CDN GIF URL so the recipient sees an animated image even without Nitro.
 * Static and unicode emoji are returned unchanged.
 */
private fun rewriteAnimatedEmojiAsGif(insertText: String): String {
    val animated = Regex("""<a:(\w+):(\d+)>""").find(insertText) ?: return insertText
    val id = animated.groupValues[2]
    return "https://cdn.discordapp.com/emojis/$id.gif?size=48&quality=lossless"
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
    isContinuation: Boolean,
    imageLoader: ImageLoader,
    channelNames: Map<String, String> = emptyMap(),
    compactMode: Boolean = false,
    spoilerRevealOnTap: Boolean = true,
    onReact: (ReactionEmoji) -> Unit,
    onSwipeLeft: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current

    // Build user name map from this message's mentioned users list
    val userNames = remember(msg.mentionedUsers) {
        msg.mentionedUsers.associate { it.id to it.displayName }
    }
    var offsetX by remember { mutableStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isContinuation) 0.dp else 4.dp, bottom = 2.dp)
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
        if (!compactMode && !isOwn && !isContinuation) {
            DiscordAvatarWithDecoration(
                user = msg.author,
                imageLoader = imageLoader,
                size = 22.dp
            )
            Spacer(Modifier.width(4.dp))
        } else if (!compactMode && !isOwn) {
            // Indent to align with messages that have an avatar
            Spacer(Modifier.width(26.dp))
        }

        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.widthIn(max = 155.dp)
        ) {
            if (!isContinuation) {
                // Format timestamp respecting device timezone and 12/24hr system setting
                val timeLabel = remember(msg.timestamp) {
                    runCatching {
                        val instant = java.time.OffsetDateTime.parse(msg.timestamp).toInstant()
                        val zoned   = instant.atZone(java.time.ZoneId.systemDefault())
                        val now     = java.time.ZonedDateTime.now()
                        val use24   = android.text.format.DateFormat.is24HourFormat(context)
                        val timeFmt = if (use24)
                            java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        else
                            java.time.format.DateTimeFormatter.ofPattern("h:mma")
                        val timeStr = zoned.format(timeFmt).lowercase()

                        val todayDate     = now.toLocalDate()
                        val yesterdayDate = todayDate.minusDays(1)
                        when (zoned.toLocalDate()) {
                            todayDate     -> timeStr
                            yesterdayDate -> "Yesterday $timeStr"
                            else          -> "${zoned.monthValue}/${zoned.dayOfMonth} $timeStr"
                        }
                    }.getOrElse { "" }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text  = msg.author.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (timeLabel.isNotEmpty()) {
                        Text(
                            text  = timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 9.sp
                        )
                    }
                }
            }

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
                        MessageContent(
                            content = fwdText,
                            imageLoader = imageLoader,
                            context = context,
                            userNames = userNames,
                            channelNames = channelNames,
                            spoilerRevealOnTap = spoilerRevealOnTap
                        )
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
                    MessageContent(
                        content = msg.content,
                        imageLoader = imageLoader,
                        context = context,
                        userNames = userNames,
                        channelNames = channelNames,
                        spoilerRevealOnTap = spoilerRevealOnTap
                    )
                }
            }

            msg.attachments.filter { it.isImage }.forEach { att ->
                MediaImage(att.proxyUrl, att.filename, imageLoader)
            }
            msg.attachments.filter { it.isVideo }.forEach { att ->
                VideoAttachment(att, imageLoader)
            }
            msg.attachments.filter { it.isAudio }.forEach { att ->
                AudioAttachment(att)
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
    context: Context,
    userNames: Map<String, String> = emptyMap(),
    roleNames: Map<String, String> = emptyMap(),
    channelNames: Map<String, String> = emptyMap(),
    spoilerRevealOnTap: Boolean = true
) {
    val parts = remember(content, userNames, roleNames, channelNames) {
        ContentParser.parse(content, userNames, roleNames, channelNames)
    }

    FlowRow(modifier = Modifier.fillMaxWidth()) {
        parts.forEach { part ->
            when (part) {
                is ContentParser.Part.PlainText -> {
                    // Parse markdown spans within the plain-text segment
                    val spans = ContentParser.parseMarkdown(part.text)
                    spans.forEach { span ->
                        if (span.spoiler) {
                            SpoilerText(span.text, spoilerRevealOnTap)
                        } else {
                            val annotated = buildAnnotatedString {
                                val style = SpanStyle(
                                    fontWeight = if (span.bold) androidx.compose.ui.text.font.FontWeight.Bold
                                                 else null,
                                    fontStyle  = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic
                                                 else null,
                                    textDecoration = when {
                                        span.strikethrough -> TextDecoration.LineThrough
                                        else               -> null
                                    },
                                    background = if (span.code)
                                        MaterialTheme.colorScheme.surfaceContainer
                                    else androidx.compose.ui.graphics.Color.Unspecified,
                                    fontFamily = if (span.code)
                                        androidx.compose.ui.text.font.FontFamily.Monospace
                                    else null
                                )
                                withStyle(style) { append(span.text) }
                            }
                            Text(text = annotated, style = MaterialTheme.typography.bodySmall)
                        }
                    }
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
                Text(
                    text     = reaction.emoji.name,
                    fontSize = 13.sp,
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
private fun VideoAttachment(att: Attachment, imageLoader: ImageLoader) {
    val context = LocalContext.current
    // Show a thumbnail (proxy URL) with a play button overlay, tapping opens video externally
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .widthIn(max = 140.dp)
            .heightIn(max = 100.dp)
            .clickable {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(att.url))
                        .setDataAndType(Uri.parse(att.url), att.contentType ?: "video/*")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
    ) {
        // Thumbnail — Discord provides proxy images for videos with width/height
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(att.proxyUrl)
                .crossfade(true)
                .build(),
            imageLoader        = imageLoader,
            contentDescription = att.filename,
            contentScale       = ContentScale.Fit,
            modifier           = Modifier
                .widthIn(max = 140.dp)
                .heightIn(max = 100.dp)
        )
        // Play button overlay
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", fontSize = 22.sp, color = androidx.compose.ui.graphics.Color.White)
        }
    }
    // Filename label
    Text(
        text  = att.filename,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

@Composable
private fun AudioAttachment(att: Attachment) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var isPlaying  by remember { mutableStateOf(false) }
    var progress   by remember { mutableStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(att.url) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Progress tracking coroutine
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                val dur = mp.duration
                if (dur > 0) progress = mp.currentPosition.toFloat() / dur.toFloat()
            }
            delay(300)
        }
    }

    Column(modifier = Modifier.padding(top = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Play/pause button
            Text(
                text     = if (isPlaying) "⏸" else "▶",
                fontSize = 16.sp,
                modifier = Modifier.clickable {
                    scope.launch {
                        if (isPlaying) {
                            mediaPlayer?.pause()
                            isPlaying = false
                        } else {
                            val mp = mediaPlayer ?: MediaPlayer().also {
                                it.setDataSource(att.url)
                                it.prepare()
                                it.setOnCompletionListener { _ ->
                                    isPlaying = false
                                    progress  = 0f
                                }
                                mediaPlayer = it
                            }
                            mp.start()
                            isPlaying = true
                        }
                    }
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = att.filename,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
    }
}


@Composable
private fun DiscordAvatarWithDecoration(user: com.zaffox.discordwear.api.DiscordUser, imageLoader: ImageLoader, size: Dp) {
    val decorUrl = user.avatarDecorationUrl()
    Box(contentAlignment = Alignment.Center) {
        DiscordAvatar(url = user.avatarUrl(32), displayName = user.displayName, imageLoader = imageLoader, size = size)
        if (decorUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(decorUrl).crossfade(false).build(),
                imageLoader        = imageLoader,
                contentDescription = null,
                contentScale       = ContentScale.Fit,
                // Decoration frame is slightly larger than the avatar
                modifier           = Modifier.size(size * 1.4f)
            )
        }
    }
}

@Composable
private fun DiscordAvatar(url: String?, displayName: String, imageLoader: ImageLoader, size: Dp) {
    val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val blurple = androidx.compose.ui.graphics.Color(0xFF5865F2)

    if (url == null) {
        Box(
            modifier = Modifier
                .size(size)
                .background(color = blurple, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text     = initial,
                style    = MaterialTheme.typography.labelSmall,
                color    = androidx.compose.ui.graphics.Color.White,
                fontSize = (size.value * 0.45f).sp
            )
        }
    } else {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
            imageLoader        = imageLoader,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.size(size).clip(CircleShape),
            error = {
                Box(
                    modifier = Modifier
                        .size(size)
                        .background(color = blurple, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text     = initial,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = androidx.compose.ui.graphics.Color.White,
                        fontSize = (size.value * 0.45f).sp
                    )
                }
            }
        )
    }
}
