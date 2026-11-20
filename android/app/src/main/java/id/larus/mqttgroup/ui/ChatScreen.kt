package id.larus.mqttgroup.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import id.larus.mqttgroup.R
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.data.db.MemberEntity
import id.larus.mqttgroup.data.db.MessageEntity
import id.larus.mqttgroup.data.db.MessageReceiptEntity
import id.larus.mqttgroup.mqtt.ConnectionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A group's chat: what phase 4 was for. Everything else about the group — the invite, the
 * member list, leaving it — lives one tap away behind the info icon, because none of it is
 * something you look at as often as the messages themselves.
 *
 * The input bar mirrors the layout everyone already knows from a chat app: attach and camera
 * inside the text field, and a single trailing circle that is a microphone until there is
 * something typed, then a send arrow. There is no emoji picker — this app does not have one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    group: GroupEntity,
    messages: List<MessageEntity>,
    members: List<MemberEntity>,
    receipts: List<MessageReceiptEntity>,
    selfUserId: String,
    connection: ConnectionState,
    attachmentFor: (String) -> ByteArray?,
    avatarFor: (MemberEntity) -> ByteArray?,
    onSend: (String, replyToId: String?) -> Unit,
    onSendImage: (ByteArray, String, caption: String?, replyToId: String?) -> Unit,
    onSendVoice: (ByteArray, String, replyToId: String?) -> Unit,
    onReconnect: () -> Unit,
    onOpenInfo: () -> Unit,
    /** Buzz one member, from the card their avatar opens. Null when there is no connection to
     * publish through, which greys the button rather than hiding it. */
    onBuzz: ((MemberEntity) -> Unit)?,
    onBack: () -> Unit,
    /** Opens this group's map. A button in the bar rather than a tab: the map is somewhere you
     * go and come back from, which a tab row denies by drawing chat and map as equals and
     * spending a whole strip of the screen saying so on every message. */
    onOpenMap: () -> Unit = {},
) {
    val namesById = remember(members) { members.associateBy { it.userId } }

    /** Whose card is open, if any. Tapping an avatar in the thread is the shortest path from
     * "who said this" to doing something about it. */
    var showing by remember { mutableStateOf<MemberEntity?>(null) }
    fun nameFor(userId: String): String = namesById[userId]?.displayName?.ifBlank { null } ?: "Someone"
    val messagesById = remember(messages) { messages.associateBy { it.id } }
    val receiptsByMessage = remember(receipts) { receipts.groupBy { it.messageId } }
    // Everyone this message could still be waiting on — kicked or left members do not count,
    // since there is nobody there anymore to deliver to or read it.
    val recipientCount = remember(members, selfUserId) {
        members.count { !it.isRemoved && it.userId != selfUserId }
    }
    fun statusFor(message: MessageEntity): MessageStatus = when (message.state) {
        "PENDING" -> MessageStatus.Pending
        "FAILED" -> MessageStatus.Failed
        else -> {
            val forMessage = receiptsByMessage[message.id].orEmpty()
            val read = forMessage.count { it.state == "READ" }
            val delivered = forMessage.count { it.state == "DELIVERED" || it.state == "READ" }
            when {
                recipientCount == 0 -> MessageStatus.Sent
                read >= recipientCount -> MessageStatus.Read
                delivered >= recipientCount -> MessageStatus.Delivered
                else -> MessageStatus.Sent
            }
        }
    }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Messages are stored newest-first, and a reversed list plus a bottom-anchored layout is
    // how a chat stays pinned to the newest message without any manual scrolling.
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    /** What the next message will quote, cleared the moment it is actually sent — the reply
     * banner above the input bar is the only UI for this, there is no swipe gesture. */
    var replyingTo by remember { mutableStateOf<MessageEntity?>(null) }
    /** Which bubble's long-press menu is open, at most one at a time. */
    var menuFor by remember { mutableStateOf<MessageEntity?>(null) }
    /** The message whose per-member delivery/read times the info sheet is showing. */
    var infoFor by remember { mutableStateOf<MessageEntity?>(null) }
    /** An image being looked at full-screen, with its already-loaded bytes — opened only once
     * they exist, so there is nothing to view before that. */
    var viewerFor by remember { mutableStateOf<Pair<MessageEntity, ByteArray>?>(null) }

    fun shareBytes(bytes: ByteArray, mime: String, fileName: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    // A picked or captured photo waits here — compressed already, but not sent — until the
    // preview screen confirms it. Nothing is queued to the outbox before that tap.
    var pendingImage by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }

    fun previewPicked(uri: Uri) {
        scope.launch {
            val prepared = withContext(Dispatchers.Default) { ImagePrep.prepare(context.contentResolver, uri) }
            // A picker result that fails to decode or never fits under the cap is silently
            // dropped rather than shown as an error — the common cause is a format this phone's
            // decoder does not handle, not something the sender did wrong.
            pendingImage = prepared
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(::previewPicked) }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> if (success) cameraUri?.let(::previewPicked) }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (granted) {
            val uri = newCameraUri(context)
            cameraUri = uri
            takePhoto.launch(uri)
        }
    }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { micGranted = it }

    val recorder = remember { AudioRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isRecording) {
        recordingSeconds = 0
        while (isRecording) {
            kotlinx.coroutines.delay(1_000)
            recordingSeconds++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.name)
                        // The connection reads as a line under the name rather than as a pill
                        // among the actions: it is something to glance at, not something to
                        // aim at, and it still takes the tap that reconnects.
                        ConnectionLine(connection, onReconnect = onReconnect)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(BackArrow, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenMap) {
                        Icon(MapIcon, contentDescription = "Map")
                    }
                    IconButton(onClick = onOpenInfo) {
                        Icon(InfoIcon, contentDescription = "Group info")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .fillMaxSize()
                .imePadding(),
        ) {
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No messages yet. Say something.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                        val self = message.senderId == selfUserId
                        // The name above a bubble is only worth showing when it just changed —
                        // a run of messages from the same person does not need to repeat it.
                        val previous = messages.getOrNull(index + 1)
                        val showName = !self && previous?.senderId != message.senderId
                        val quoted = message.replyToId?.let { messagesById[it] }
                        MessageBubble(
                            message = message,
                            self = self,
                            senderName = if (showName) nameFor(message.senderId) else null,
                            // Only at the top of a run, same as the name: a repeated avatar down
                            // a whole run of someone's messages is just noise WhatsApp itself
                            // does not add.
                            avatarBytes = if (showName) namesById[message.senderId]?.let(avatarFor) else null,
                            showAvatarSlot = !self,
                            onAvatarTap = { namesById[message.senderId]?.let { showing = it } },
                            status = if (self) statusFor(message) else null,
                            quoted = quoted,
                            quotedSenderName = quoted?.let {
                                if (it.senderId == selfUserId) "You" else nameFor(it.senderId)
                            },
                            onQuoteTap = { target ->
                                val index = messages.indexOfFirst { it.id == target }
                                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                            },
                            attachmentFor = attachmentFor,
                            onLongPress = { menuFor = message },
                            onImageTap = { bytes -> viewerFor = message to bytes },
                        )
                    }
                }
            }

            replyingTo?.let { target ->
                ReplyBanner(
                    senderName = if (target.senderId == selfUserId) "You" else nameFor(target.senderId),
                    preview = previewText(target),
                    onCancel = { replyingTo = null },
                )
            }

            ChatInputBar(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    onSend(draft.trim(), replyingTo?.id)
                    draft = ""
                    replyingTo = null
                },
                onAttach = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onCamera = {
                    if (cameraGranted) {
                        val uri = newCameraUri(context)
                        cameraUri = uri
                        takePhoto.launch(uri)
                    } else {
                        requestCamera.launch(Manifest.permission.CAMERA)
                    }
                },
                isRecording = isRecording,
                recordingSeconds = recordingSeconds,
                onMicPress = {
                    if (!micGranted) {
                        requestMic.launch(Manifest.permission.RECORD_AUDIO)
                        return@ChatInputBar false
                    }
                    isRecording = true
                    recorder.start()
                    true
                },
                onMicRelease = { heldMillis ->
                    isRecording = false
                    val bytes = recorder.stop()
                    if (bytes != null && heldMillis >= MIN_RECORDING_MILLIS) {
                        onSendVoice(bytes, "audio/mp4", replyingTo?.id)
                        replyingTo = null
                    }
                },
                onMicCancel = {
                    isRecording = false
                    recorder.cancel()
                },
            )
        }
    }

    pendingImage?.let { (bytes, mime) ->
        ImagePreviewDialog(
            bytes = bytes,
            onCancel = { pendingImage = null },
            onSend = { caption ->
                onSendImage(bytes, mime, caption.trim().ifBlank { null }, replyingTo?.id)
                replyingTo = null
                pendingImage = null
            },
        )
    }

    menuFor?.let { target ->
        MessageActionSheet(
            canCopy = target.type != "audio" && !target.body.isNullOrBlank(),
            canShare = target.blobId != null,
            onDismiss = { menuFor = null },
            onReply = { replyingTo = target; menuFor = null },
            onCopy = {
                clipboard.setText(AnnotatedString(target.body.orEmpty()))
                menuFor = null
            },
            onInfo = { infoFor = target; menuFor = null },
            onShare = {
                val blobId = target.blobId
                val bytes = blobId?.let(attachmentFor)
                if (bytes != null) {
                    val ext = if (target.type == "audio") "m4a" else "jpg"
                    val mime = if (target.type == "audio") "audio/mp4" else "image/jpeg"
                    shareBytes(bytes, mime, "shared_${target.id}.$ext")
                }
                menuFor = null
            },
        )
    }

    infoFor?.let { target ->
        MessageInfoSheet(
            message = target,
            members = members,
            selfUserId = selfUserId,
            receiptsFor = receiptsByMessage[target.id].orEmpty(),
            nameFor = ::nameFor,
            onDismiss = { infoFor = null },
        )
    }

    viewerFor?.let { (target, bytes) ->
        MediaViewerDialog(
            bytes = bytes,
            onDismiss = { viewerFor = null },
            onShare = { shareBytes(bytes, "image/jpeg", "shared_${target.id}.jpg") },
        )
    }

    showing?.let { member ->
        MemberCard(
            member = member,
            avatar = avatarFor(member),
            isSelf = member.userId == selfUserId,
            onBuzz = onBuzz?.let { buzz -> { buzz(member) } },
            onDismiss = { showing = null },
        )
    }
}

/**
 * The row every chat app has: a pill-shaped text field with attach and camera inside it, and one
 * trailing circular control that swaps between a microphone and a send arrow depending on
 * whether there is anything typed — never both, since a message is either words or a recording.
 */
@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    isRecording: Boolean,
    recordingSeconds: Int,
    /** Returns false to abort (permission not yet granted) rather than start recording. */
    onMicPress: () -> Boolean,
    onMicRelease: (heldMillis: Long) -> Unit,
    onMicCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentDot(size = 10.dp, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Recording… ${formatDuration(recordingSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onAttach, modifier = Modifier.size(48.dp)) {
                        Icon(
                            AttachIcon,
                            contentDescription = "Send a photo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // BasicTextField rather than Material's TextField: the latter has a ~56dp
                    // minimum height baked into the M3 spec, which is taller than the 48dp round
                    // button beside it and left the two looking mismatched.
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text(
                                "Message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions.Default,
                            keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) onSend() }),
                        )
                    }
                    IconButton(onClick = onCamera, modifier = Modifier.size(48.dp)) {
                        Icon(
                            CameraIcon,
                            contentDescription = "Take a photo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // The one control that changes meaning: a microphone to press and hold, or a send arrow
        // to tap, depending on whether there is text — matching what every other chat app trains
        // a thumb to expect.
        var pressStart by remember { mutableStateOf(0L) }
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .then(
                    if (draft.isBlank()) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (!onMicPress()) return@detectTapGestures
                                    pressStart = System.currentTimeMillis()
                                    val released = try {
                                        awaitRelease()
                                        true
                                    } catch (_: Exception) {
                                        false
                                    }
                                    val held = System.currentTimeMillis() - pressStart
                                    if (released) onMicRelease(held) else onMicCancel()
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (draft.isBlank()) {
                Icon(MicIcon, contentDescription = "Hold to record a voice note", tint = Color.White)
            } else {
                IconButton(onClick = onSend) {
                    Icon(SendIcon, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Sits above the input bar the moment a reply is chosen from the long-press menu, and nowhere
 * else — there is no swipe-to-reply gesture, just this and an explicit cancel. */
@Composable
private fun ReplyBanner(senderName: String, preview: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 32.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Replying to $senderName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
            Icon(CloseIcon, contentDescription = "Cancel reply")
        }
    }
}

/** The long-press menu: Reply always, Copy only for text with a body, Info always (it is
 * meaningful even for an incoming message, since other members' receipts are visible too, not
 * only what this phone sent), Share only when there are bytes to hand off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    canCopy: Boolean,
    canShare: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 16.dp)) {
            ActionRow(ReplyIcon, "Reply", onReply)
            if (canCopy) ActionRow(CopyIcon, "Copy", onCopy)
            if (canShare) ActionRow(ShareIcon, "Share", onShare)
            ActionRow(InfoIcon, "Info", onInfo)
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Who has this message, and since when. Meaningful for any message, not only one this phone
 * sent — receipts are broadcast to the whole group's control topic, so an incoming message's
 * info shows what every *other* member has done with it just as well.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInfoSheet(
    message: MessageEntity,
    members: List<MemberEntity>,
    selfUserId: String,
    receiptsFor: List<MessageReceiptEntity>,
    nameFor: (String) -> String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val receiptByUser = remember(receiptsFor) { receiptsFor.associateBy { it.userId } }
    // Everyone but the sender: the sender does not receipt their own message, and self's own
    // status (for a message someone else sent) comes from the local `read` flag instead, since
    // a receipt for it never had to leave this phone to know.
    val others = remember(members, message.senderId) { members.filter { it.userId != message.senderId && !it.isRemoved } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Message info", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                previewText(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Spacer(Modifier.height(16.dp))
            if (others.isEmpty()) {
                Text(
                    "No one else is in this group yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
            others.forEach { member ->
                val statusText = if (member.userId == selfUserId) {
                    if (message.read) "Read" else "Delivered"
                } else {
                    when (val receipt = receiptByUser[member.userId]) {
                        null -> "Not delivered yet"
                        else -> {
                            val verb = if (receipt.state == "READ") "Read" else "Delivered"
                            "$verb ${TIME_FORMAT.format(Date(receipt.at))}"
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (member.userId == selfUserId) "You" else nameFor(member.userId),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A full-screen look at a photo already in the chat, with a share action — the counterpart to
 * [ImagePreviewDialog], which is the same layout for a photo on its way out instead of one
 * already sent. */
@Composable
private fun MediaViewerDialog(bytes: ByteArray, onDismiss: () -> Unit, onShare: () -> Unit) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(CloseIcon, contentDescription = "Close", tint = Color.White)
                }
                IconButton(onClick = onShare) {
                    Icon(ShareIcon, contentDescription = "Share", tint = Color.White)
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Photo",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** A full-screen look at a photo before it goes anywhere, with a caption field like the bubble
 * it is about to become. Nothing here has touched the network yet — Send is the only door out
 * that does. */
@Composable
private fun ImagePreviewDialog(bytes: ByteArray, onCancel: () -> Unit, onSend: (caption: String) -> Unit) {
    var caption by remember { mutableStateOf("") }
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                IconButton(onClick = onCancel) {
                    Icon(CloseIcon, contentDescription = "Cancel", tint = Color.White)
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Photo to send",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(24.dp)),
                    placeholder = { Text("Add a caption", color = Color(0xCCFFFFFF)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                    ),
                    maxLines = 3,
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { onSend(caption) }) {
                        Icon(SendIcon, contentDescription = "Send photo", tint = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Who said that, and the one thing you are likely to want to do about it.
 *
 * Reached by tapping a face in the thread, which is the shortest path there is from reading a
 * message to buzzing the person who sent it — shorter than the group screen, and in the place
 * where the question actually occurs to you.
 *
 * Deliberately thin. It is a card, not a profile page: a large avatar, a name, whatever the
 * member list already knows, and Buzz. Anything more would be a second group screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberCard(
    member: MemberEntity,
    avatar: ByteArray?,
    isSelf: Boolean,
    onBuzz: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(bytes = avatar, name = member.displayName, size = 88.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                member.displayName.ifBlank { "Someone" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            val note = when {
                member.isRemoved -> stringResource(R.string.member_card_removed)
                member.isAdmin -> stringResource(R.string.member_card_admin)
                member.lastSeenAt == 0L -> stringResource(R.string.member_card_unseen)
                else -> null
            }
            note?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(22.dp))
            // Not for yourself, and not for someone who has left: neither would ring anything.
            if (!isSelf && !member.isRemoved) {
                Button(
                    onClick = { onBuzz?.invoke(); onDismiss() },
                    enabled = onBuzz != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(BuzzIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.buzz_action))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.buzz_explains),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    self: Boolean,
    senderName: String?,
    /** Only set at the top of a run — see the comment where this is computed. Null both when
     * there is no avatar yet and when this message is a continuation with none to show. */
    avatarBytes: ByteArray?,
    /** Whether this row belongs to someone else at all, so a continuation row still reserves the
     * same leading space a named one does — otherwise every other bubble would drift left. */
    showAvatarSlot: Boolean,
    onAvatarTap: () -> Unit = {},
    /** Null for anything that is not our own message — an incoming message has no send status
     * to show, only a time. */
    status: MessageStatus?,
    /** The message this one replies to, if it does and it is still around locally to quote. */
    quoted: MessageEntity?,
    quotedSenderName: String?,
    onQuoteTap: (String) -> Unit,
    attachmentFor: (String) -> ByteArray?,
    onLongPress: (MessageEntity) -> Unit,
    /** Fired only once the image's bytes actually exist — nothing to view full-screen before
     * that. */
    onImageTap: (ByteArray) -> Unit,
) {
    val bubbleColor = if (self) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val onBubbleColor = if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    // Self never has an avatar slot, so it keeps the plain full-width, end-aligned column a
    // weighted one would break End-alignment for (a weight sized to content, not to the row,
    // leaves nothing on the trailing side to push against). Incoming messages fill the space
    // left of the avatar instead, which is what a weighted column is actually for.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showAvatarSlot) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    // Only where there is actually a face to tap. The slot is kept for every
                    // message in a run so the bubbles line up, but the empty ones are spacing
                    // and a click target on spacing is a mystery.
                    .then(
                        if (avatarBytes != null || senderName != null) {
                            Modifier.clip(CircleShape).clickable(onClick = onAvatarTap)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarBytes != null || senderName != null) {
                    Avatar(bytes = avatarBytes, name = senderName.orEmpty(), size = 28.dp)
                }
            }
        }
        Column(
            modifier = if (showAvatarSlot) Modifier.weight(1f) else Modifier.fillMaxWidth(),
            horizontalAlignment = if (self) Alignment.End else Alignment.Start,
        ) {
            senderName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (self) {
                    TimeLabel(message.createdAt)
                    status?.let { StatusTicks(it) }
                }
                // widthIn only — the long-press (and, for an image, the tap) is applied once, by
                // each bubble type's own outermost container below, never doubled up with a
                // second gesture detector layered on top of it.
                val bubbleModifier = Modifier.widthIn(max = 280.dp)
                when (message.type) {
                    "image" -> message.blobId?.let {
                        ImageBubble(
                            blobId = it,
                            bytes = attachmentFor(it),
                            caption = message.body,
                            self = self,
                            quoted = quoted,
                            quotedSenderName = quotedSenderName,
                            onQuoteTap = onQuoteTap,
                            modifier = bubbleModifier,
                            onLongPress = { onLongPress(message) },
                            onTap = onImageTap,
                        )
                    }

                    "audio" -> message.blobId?.let {
                        VoiceBubble(
                            blobId = it,
                            bytes = attachmentFor(it),
                            self = self,
                            quoted = quoted,
                            quotedSenderName = quotedSenderName,
                            onQuoteTap = onQuoteTap,
                            modifier = bubbleModifier,
                            onLongPress = { onLongPress(message) },
                        )
                    }

                    else -> Column(
                        modifier = bubbleModifier
                            .combinedClickable(onClick = {}, onLongClick = { onLongPress(message) })
                            .background(bubbleColor, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        quoted?.let {
                            QuotedPreview(
                                quoted = it,
                                senderName = quotedSenderName.orEmpty(),
                                self = self,
                                onTap = { onQuoteTap(it.id) },
                            )
                        }
                        Text(
                            message.body.orEmpty(),
                            color = onBubbleColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (!self) TimeLabel(message.createdAt)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

/** A quoted snippet of the message being replied to, the same shape whichever kind of bubble it
 * sits inside — a coloured bar on the leading edge, the original sender's name, and one line of
 * whatever it said. Tapping it jumps the list to the original. */
@Composable
private fun QuotedPreview(quoted: MessageEntity, senderName: String, self: Boolean, onTap: () -> Unit) {
    val barColor = if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val textColor = if (self) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(bottom = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 32.dp)
                .background(barColor, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                senderName,
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
                fontWeight = FontWeight.Bold,
            )
            Text(
                previewText(quoted),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 1,
            )
        }
    }
}

/** One line describing any message, for a reply banner or a quoted snippet — never the full
 * caption or body, since both of those are what the bubble itself is already for. */
private fun previewText(message: MessageEntity): String = when (message.type) {
    "image" -> message.body?.ifBlank { null }?.let { "Photo: $it" } ?: "Photo"
    "audio" -> "Voice message"
    else -> message.body.orEmpty()
}

/**
 * The bytes may not be here yet — this phone might have received the reference message before
 * the (larger, separately-published) attachment itself. A spinner rather than a broken-image
 * icon is the honest state: it is still coming, not lost.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageBubble(
    blobId: String,
    bytes: ByteArray?,
    caption: String?,
    self: Boolean,
    quoted: MessageEntity?,
    quotedSenderName: String?,
    onQuoteTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onTap: (ByteArray) -> Unit,
) {
    Column(
        modifier = modifier
            .combinedClickable(
                onClick = { bytes?.let(onTap) },
                onLongClick = onLongPress,
            )
            .background(
                if (self) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(18.dp),
            )
            .padding(if (quoted != null) 8.dp else 0.dp),
    ) {
        quoted?.let {
            QuotedPreview(quoted = it, senderName = quotedSenderName.orEmpty(), self = self, onTap = { onQuoteTap(it.id) })
        }
        Box(
            modifier = Modifier.widthIn(max = 220.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bytes == null) {
                Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                val bitmap = remember(blobId) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bitmap == null) {
                    Text(
                        "Couldn't load this photo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Photo",
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                color = if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A play/pause button and nothing more — no waveform, since there is no way to draw one without
 * decoding the whole clip up front, and a voice note this app expects to be short. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceBubble(
    blobId: String,
    bytes: ByteArray?,
    self: Boolean,
    quoted: MessageEntity?,
    quotedSenderName: String?,
    onQuoteTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    var player by remember(blobId) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(blobId) { mutableStateOf(false) }

    DisposableEffect(blobId) {
        onDispose {
            player?.release()
            player = null
        }
    }

    val tint = if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .widthIn(min = 150.dp, max = 240.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .background(
                if (self) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(18.dp),
            )
            .padding(horizontal = if (quoted != null) 8.dp else 4.dp, vertical = if (quoted != null) 8.dp else 4.dp),
    ) {
        quoted?.let {
            QuotedPreview(quoted = it, senderName = quotedSenderName.orEmpty(), self = self, onTap = { onQuoteTap(it.id) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = playAction@{
                    val data = bytes ?: return@playAction
                    val current = player
                    if (current != null) {
                        if (playing) current.pause() else current.start()
                        playing = !playing
                        return@playAction
                    }
                    val temp = File.createTempFile("voice", ".m4a", context.cacheDir)
                    temp.writeBytes(data)
                    val created = MediaPlayer().apply {
                        setDataSource(temp.absolutePath)
                        setOnCompletionListener { playing = false }
                        prepare()
                        start()
                    }
                    player = created
                    playing = true
                },
                enabled = bytes != null,
            ) {
                if (bytes == null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(if (playing) PauseIcon else PlayIcon, contentDescription = "Play voice note", tint = tint)
                }
            }
            Text("Voice message", style = MaterialTheme.typography.bodyMedium, color = tint)
        }
    }
}

@Composable
private fun TimeLabel(epochMillis: Long) {
    Text(
        remember(epochMillis) { TIME_FORMAT.format(Date(epochMillis)) },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A fresh file under this app's own cache for the camera app to write a full-res photo into. */
private fun newCameraUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private const val MIN_RECORDING_MILLIS = 400L

private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * What a self message's status icon says: still queued locally, given up on, out of the phone
 * but not confirmed by anyone, confirmed received by everyone still in the group, or confirmed
 * opened by everyone. "Everyone" is whoever [ChatScreen] currently counts as a recipient — a
 * group with nobody else in it yet never gets past [Sent].
 */
private enum class MessageStatus { Pending, Failed, Sent, Delivered, Read }

/** A single check for queued/sent, a doubled one for delivered, and the same doubled pair
 * recoloured for read — the vocabulary this already meant before this app existed. */
@Composable
private fun StatusTicks(status: MessageStatus) {
    when (status) {
        MessageStatus.Pending -> Icon(
            ClockIcon,
            contentDescription = "Sending",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MessageStatus.Failed -> Icon(
            ErrorIcon,
            contentDescription = "Not sent",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        MessageStatus.Sent -> Icon(
            CheckIcon,
            contentDescription = "Sent",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MessageStatus.Delivered, MessageStatus.Read -> {
            val tint = if (status == MessageStatus.Read) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val description = if (status == MessageStatus.Read) "Read" else "Delivered"
            Box(modifier = Modifier.width(20.dp).height(14.dp)) {
                Icon(
                    CheckIcon,
                    contentDescription = description,
                    tint = tint,
                    modifier = Modifier.size(14.dp).align(Alignment.CenterStart),
                )
                Icon(
                    CheckIcon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp).align(Alignment.CenterEnd),
                )
            }
        }
    }
}

private val CheckIcon by lazy { simpleIcon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }

private val ClockIcon by lazy {
    simpleIcon(
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z M12.5,7H11v6l5.25,3.15" +
            "0.75,-1.23 -4.5,-2.67z",
    )
}

private val ErrorIcon by lazy {
    simpleIcon(
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z M13,17h-2v-2h2v2z" +
            "M13,13h-2L11,7h2v6z",
    )
}

/** The folded paper map, the same shape the web app uses for the same button. */
private val MapIcon by lazy {
    simpleIcon(
        "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5" +
            "c0,0.28 0.22,0.5 0.5,0.5l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48" +
            "V3.5c0,-0.28 -0.22,-0.5 -0.5,-0.5zM15,19l-6,-2.11V5l6,2.11V19z",
    )
}

private val InfoIcon by lazy {
    simpleIcon(
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z M13,17h-2v-6h2v6z" +
            "M13,9h-2L11,7h2v2z",
    )
}

private val SendIcon by lazy {
    simpleIcon("M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z")
}

private val AttachIcon by lazy {
    simpleIcon(
        "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5" +
            "v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5" +
            "c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6H16.5z",
    )
}

private val CameraIcon by lazy {
    simpleIcon(
        "M9.4,2l-1.83,2H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2" +
            "h-3.57L14.6,2H9.4z M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
            "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
    )
}

private val PlayIcon by lazy { simpleIcon("M8,5v14l11,-7z") }

private val PauseIcon by lazy { simpleIcon("M6,19h4V5H6v14z M14,5v14h4V5h-4z") }

private val CloseIcon by lazy {
    simpleIcon(
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59" +
            "13.41,12z",
    )
}

private val ReplyIcon by lazy {
    simpleIcon("M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z")
}

private val CopyIcon by lazy {
    simpleIcon(
        "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1z M19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9" +
            "2,-2V7c0,-1.1 -0.9,-2 -2,-2z M19,21H8V7h11v14z",
    )
}

private val ShareIcon by lazy {
    simpleIcon(
        "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7" +
            "s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3" +
            "s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L7.04,9.81C6.5,9.31 5.79,9 5,9" +
            "c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16" +
            "c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31" +
            "2.92,-2.92s-1.31,-2.92 -2.92,-2.92z",
    )
}
