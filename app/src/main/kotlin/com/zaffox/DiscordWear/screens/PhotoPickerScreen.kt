package com.zaffox.discordwear.screens

import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAdded: Long
)

@Composable
fun PhotoPickerScreen(
    imageLoader: ImageLoader,
    onImageSelected: (Uri, String) -> Unit,  // uri, mimeType
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    BackHandler { onDismiss() }

    var images by remember { mutableStateOf<List<MediaImage>>(emptyList()) }
    var permGranted by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        permGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permGranted = granted
    }

    LaunchedEffect(permGranted) {
        if (!permGranted) return@LaunchedEffect
        val result = mutableListOf<MediaImage>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                result.add(MediaImage(
                    id = id,
                    uri = uri,
                    displayName = cursor.getString(nameCol) ?: "image",
                    mimeType = cursor.getString(mimeCol) ?: "image/jpeg",
                    dateAdded = cursor.getLong(dateCol)
                ))
            }
        }
        images = result
    }

    val confirmUri = selectedUri
    if (confirmUri != null) {
        val confirmImg = images.find { it.uri == confirmUri }
        ImageConfirmScreen(
            uri = confirmUri,
            mimeType = confirmImg?.mimeType ?: "image/jpeg",
            imageLoader = imageLoader,
            onConfirm = { uri, mime ->
                onImageSelected(uri, mime)
            },
            onCancel = { selectedUri = null }
        )
        return
    }

    if (!permGranted) {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        val listState = rememberScalingLazyListState()
        ScreenScaffold(scrollState = listState) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Text(
                        "Photo Access",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Text(
                        "Allow DiscordWear to access your photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Button(
                        onClick = { permLauncher.launch(perm) },
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) { Text("Allow") }
                }
                item {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(0.8f),
                        colors = ButtonDefaults.outlinedButtonColors()
                    ) { Text("Cancel") }
                }
            }
        }
        return
    }

    if (images.isEmpty()) {
        val listState = rememberScalingLazyListState()
        ScreenScaffold(scrollState = listState) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    CircularProgressIndicator()
                }
                item {
                    Text(
                        "No photos found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors()
                    ) { Text("Back") }
                }
            }
        }
        return
    }

    val gridState = rememberLazyGridState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Select Photo",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(images, key = { it.id }) { img ->
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(img.uri)
                            .size(120)
                            .crossfade(true)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = img.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { selectedUri = img.uri }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            CompactButton(
                onClick = onDismiss,
                colors = ButtonDefaults.filledTonalButtonColors()
            ) {
                Text("X", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ImageConfirmScreen(
    uri: Uri,
    mimeType: String,
    imageLoader: ImageLoader,
    onConfirm: (Uri, String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    "Send this photo?",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            item {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(uri)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onConfirm(uri, mimeType) },
                        modifier = Modifier.weight(1f).height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Send") }
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).height(36.dp),
                        colors = ButtonDefaults.outlinedButtonColors()
                    ) { Text("Back") }
                }
            }
        }
    }
}
