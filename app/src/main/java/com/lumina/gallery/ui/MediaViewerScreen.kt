package com.lumina.gallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.gallery.data.AiEngine
import com.lumina.gallery.data.MediaRepository
import com.lumina.gallery.model.ExifReport
import com.lumina.gallery.model.MediaItem
import com.lumina.gallery.model.OcrResultData
import com.lumina.gallery.model.QrItemData
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    items: List<MediaItem>,
    initialIndex: Int,
    favorites: Set<Long>,
    hiddenFiles: Set<Long>,
    isRecycleBinMode: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onToggleHideFile: (Long) -> Unit,
    onTrashItem: (MediaItem) -> Unit,
    onRestoreTrashedItem: (MediaItem) -> Unit,
    onDeletePermanently: (MediaItem) -> Unit,
    onLockToVault: (MediaItem) -> Unit,
    onMediaSaved: () -> Unit
) {
    if (items.isEmpty()) {
        onBack()
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safeStart = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeStart, pageCount = { items.size })

    var showChrome by remember { mutableStateOf(true) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var showStudioPro by remember { mutableStateOf(false) }
    var showOcrSheet by remember { mutableStateOf(false) }
    var showTrimmerSheet by remember { mutableStateOf(false) }
    var showQrSheet by remember { mutableStateOf(false) }
    var isSharingClean by remember { mutableStateOf(false) }

    val currentItem = items.getOrNull(pagerState.currentPage) ?: return
    val isFav = favorites.contains(currentItem.id)
    val isHidden = hiddenFiles.contains(currentItem.id)

    // Zero-Click Poster/Image QR Auto-Detector (runs 100% offline whenever user views an image!)
    var autoDetectedQr by remember(currentItem.id) { mutableStateOf<List<QrItemData>>(emptyList()) }
    LaunchedEffect(currentItem.id) {
        if (!currentItem.isVideo && !isRecycleBinMode) {
            autoDetectedQr = AiEngine.scanQrFromUri(context, currentItem.uri)
        } else {
            autoDetectedQr = emptyList()
        }
    }

    // Full-Screen Lumina AI Studio Pro Overlay
    if (showStudioPro && !currentItem.isVideo) {
        StudioProScreen(
            item = currentItem,
            onClose = { showStudioPro = false },
            onSaved = { fileName ->
                showStudioPro = false
                Toast.makeText(context, "Saved: Pictures/LuminaStudio/$fileName", Toast.LENGTH_LONG).show()
                onMediaSaved()
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val media = items[page]
            if (media.isVideo) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (page == pagerState.currentPage) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(media.uri)
                                    val controller = MediaController(ctx)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = false
                                        start()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = media.uri,
                            contentDescription = media.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                ZoomablePhoto(
                    media = media,
                    onSingleTap = { showChrome = !showChrome }
                )
            }
        }

        // Auto-Detected QR Floating Pill (Zero-Click Poster Magic!)
        AnimatedVisibility(
            visible = showChrome && autoDetectedQr.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 86.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(LuminaCyan)
                    .clickable { showQrSheet = true }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Icon(Icons.Default.QrCode2, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "QR Detected (${autoDetectedQr.first().typeLabel}) • Tap to Open",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Animated Top Translucent Bar
        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentItem.displayName,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${MediaRepository.formatDateFull(currentItem.effectiveTimeSec)} • ${MediaRepository.formatSize(currentItem.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LuminaMuted
                    )
                }
                if (!isRecycleBinMode) {
                    IconButton(onClick = { onToggleHideFile(currentItem.id) }) {
                        Icon(
                            imageVector = if (isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Hide",
                            tint = if (isHidden) LuminaAmber else Color.White
                        )
                    }
                    IconButton(onClick = { onToggleFavorite(currentItem.id) }) {
                        Icon(
                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFav) LuminaRed else Color.White
                        )
                    }
                }
            }
        }

        // Animated Bottom Action Bar
        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (isRecycleBinMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .navigationBarsPadding()
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ViewerActionButton(
                        icon = Icons.Default.RestoreFromTrash,
                        label = "Restore",
                        tint = LuminaGreen,
                        onClick = { onRestoreTrashedItem(currentItem) }
                    )
                    ViewerActionButton(
                        icon = Icons.Default.DeleteForever,
                        label = "Delete Forever",
                        tint = LuminaRed,
                        onClick = { onDeletePermanently(currentItem) }
                    )
                }
            } else {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.84f))
                        .navigationBarsPadding()
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!currentItem.isVideo) {
                        item {
                            ViewerActionButton(
                                icon = Icons.Default.AutoFixHigh,
                                label = "Studio Pro",
                                tint = LuminaViolet,
                                onClick = { showStudioPro = true }
                            )
                        }
                        item {
                            ViewerActionButton(
                                icon = Icons.Default.TextSnippet,
                                label = "AI OCR",
                                tint = LuminaCyan,
                                onClick = { showOcrSheet = true }
                            )
                        }
                        item {
                            ViewerActionButton(
                                icon = Icons.Default.QrCode2,
                                label = "Scan QR",
                                tint = if (autoDetectedQr.isNotEmpty()) LuminaGreen else Color.White,
                                onClick = {
                                    if (autoDetectedQr.isNotEmpty()) {
                                        showQrSheet = true
                                    } else {
                                        scope.launch {
                                            val found = AiEngine.scanQrFromUri(context, currentItem.uri)
                                            if (found.isNotEmpty()) {
                                                autoDetectedQr = found
                                                showQrSheet = true
                                            } else {
                                                Toast.makeText(context, "No QR or barcode found in this image", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    } else {
                        item {
                            ViewerActionButton(
                                icon = Icons.Default.ContentCut,
                                label = "Trim",
                                tint = LuminaCyan,
                                onClick = { showTrimmerSheet = true }
                            )
                        }
                    }
                    item {
                        ViewerActionButton(
                            icon = Icons.Default.Shield,
                            label = if (isSharingClean) "Cleaning" else "Safe Share",
                            onClick = {
                                if (!isSharingClean) {
                                    isSharingClean = true
                                    scope.launch {
                                        val intent = MediaRepository.buildPrivacyScrubShareIntent(context, currentItem)
                                        isSharingClean = false
                                        if (intent != null) {
                                            Toast.makeText(context, "GPS & EXIF stripped!", Toast.LENGTH_SHORT).show()
                                            context.startActivity(Intent.createChooser(intent, "Privacy-Safe Share"))
                                        } else {
                                            Toast.makeText(context, "Unable to prepare share", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        )
                    }
                    item {
                        ViewerActionButton(
                            icon = Icons.Default.Lock,
                            label = "Vault",
                            onClick = { onLockToVault(currentItem) }
                        )
                    }
                    item {
                        ViewerActionButton(
                            icon = Icons.Default.Info,
                            label = "X-Ray",
                            onClick = { showInfoSheet = true }
                        )
                    }
                    item {
                        ViewerActionButton(
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            tint = LuminaRed,
                            onClick = { onTrashItem(currentItem) }
                        )
                    }
                }
            }
        }
    }

    if (showInfoSheet) {
        ExifAndPaletteSheet(item = currentItem, onDismiss = { showInfoSheet = false })
    }

    if (showOcrSheet && !currentItem.isVideo) {
        UniversalOcrSheet(item = currentItem, onDismiss = { showOcrSheet = false })
    }

    if (showQrSheet && autoDetectedQr.isNotEmpty()) {
        QrActionBottomSheet(qrItems = autoDetectedQr, onDismiss = { showQrSheet = false })
    }

    if (showTrimmerSheet && currentItem.isVideo) {
        VideoTrimmerSheet(
            item = currentItem,
            onDismiss = { showTrimmerSheet = false },
            onSaved = { fileName ->
                showTrimmerSheet = false
                Toast.makeText(context, "Saved: Movies/LuminaTrimmed/$fileName", Toast.LENGTH_LONG).show()
                onMediaSaved()
            }
        )
    }
}

@Composable
private fun ZoomablePhoto(
    media: MediaItem,
    onSingleTap: () -> Unit
) {
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 4.5f)
        scale = nextScale
        offset = if (nextScale > 1f) offset + panChange else Offset.Zero
    }

    val modifier = if (scale > 1.01f) {
        Modifier.fillMaxSize().transformable(state = transformState)
    } else {
        Modifier.fillMaxSize()
    }

    Box(
        modifier = modifier.pointerInput(media.id) {
            detectTapGestures(
                onTap = { onSingleTap() },
                onDoubleTap = {
                    if (scale > 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.6f
                    }
                }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(media.uri)
                .crossfade(true)
                .build(),
            contentDescription = media.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

@Composable
private fun ViewerActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UniversalOcrSheet(
    item: MediaItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 = Instant Offline, 1 = Any Language (Bangla/Arabic/Online)
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<OcrResultData?>(null) }

    LaunchedEffect(item.id, mode) {
        loading = true
        result = if (mode == 0) {
            AiEngine.recognizeTextOffline(context, item.uri)
        } else {
            AiEngine.recognizeTextAnyLanguageOnline(context, item.uri)
        }
        loading = false
    }

    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Lumina OCR", text))
        Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LuminaSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Universal AI Text Extractor",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = LuminaCyan
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == 0,
                    onClick = { mode = 0 },
                    label = { Text("Instant Offline") }
                )
                FilterChip(
                    selected = mode == 1,
                    onClick = { mode = 1 },
                    leadingIcon = { Icon(Icons.Default.Language, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("All Languages (Bangla/Arabic/Online)") }
                )
            }

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = LuminaCyan, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(
                        if (mode == 0) "Reading text offline..." else "Extracting multi-language text...",
                        color = LuminaMuted
                    )
                }
            } else {
                val data = result
                Text(
                    text = "Engine: ${data?.sourceEngine ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LuminaMuted
                )

                if (data == null || data.fullText.isBlank()) {
                    Text(
                        text = if (mode == 0) "No Latin text found offline. Tap 'All Languages' above for Bangla, Arabic or handwriting!"
                        else "No readable text detected.",
                        color = LuminaAmber,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    // Auto-Detected Clickable Smart Entities (Links, Phones, Emails)
                    if (data.urls.isNotEmpty() || data.phones.isNotEmpty() || data.emails.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(data.urls) { url ->
                                OutlinedButton(onClick = {
                                    val normalized = if (url.startsWith("http", true)) url else "https://$url"
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized))) } catch (_: Exception) {}
                                }) {
                                    Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(url.take(22), maxLines = 1)
                                }
                            }
                            items(data.phones) { phone ->
                                OutlinedButton(onClick = {
                                    try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) } catch (_: Exception) {}
                                }) {
                                    Icon(Icons.Default.Call, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(phone)
                                }
                            }
                            items(data.emails) { mail ->
                                OutlinedButton(onClick = {
                                    try { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$mail"))) } catch (_: Exception) {}
                                }) {
                                    Icon(Icons.Default.Email, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(mail)
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { copyText(data.fullText) },
                            colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Copy All", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, data.fullText)
                                }
                                context.startActivity(Intent.createChooser(send, "Share Extracted Text"))
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Share")
                        }
                    }

                    Text("Tap any line to copy individually:", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(data.lines) { line ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = LuminaCard),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().clickable { copyText(line) }
                            ) {
                                Text(
                                    text = line,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoTrimmerSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val totalMs = item.durationMs.coerceAtLeast(2000L)
    var range by remember(item.id) { mutableStateOf(0f..totalMs.toFloat()) }
    var isTrimming by remember { mutableStateOf(false) }

    val startMs = range.start.toLong()
    val endMs = range.endInclusive.toLong()
    val selectedDur = (endMs - startMs).coerceAtLeast(0L)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LuminaSurface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Lossless Video Trimmer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = LuminaCyan)
            Text("Zero re-encoding — trims instantly without losing quality.", style = MaterialTheme.typography.bodySmall, color = LuminaMuted)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Start: ${MediaRepository.formatDuration(startMs)}", fontWeight = FontWeight.SemiBold)
                Text("Clip: ${MediaRepository.formatDuration(selectedDur)}", color = LuminaGreen, fontWeight = FontWeight.Bold)
                Text("End: ${MediaRepository.formatDuration(endMs)}", fontWeight = FontWeight.SemiBold)
            }
            RangeSlider(
                value = range,
                onValueChange = { next -> if (next.endInclusive - next.start >= 1000f) range = next },
                valueRange = 0f..totalMs.toFloat()
            )
            Button(
                onClick = {
                    isTrimming = true
                    scope.launch {
                        val saved = AiEngine.trimVideoLossless(context, item.uri, startMs, endMs)
                        isTrimming = false
                        if (saved != null) onSaved(saved)
                        else Toast.makeText(context, "Could not trim this video format", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isTrimming && selectedDur >= 1000L,
                colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (isTrimming) "Trimming..." else "Save Trimmed Video", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExifAndPaletteSheet(
    item: MediaItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<ExifReport?>(null) }

    LaunchedEffect(item.id) {
        report = MediaRepository.inspectMedia(context, item)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LuminaSurface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("EXIF X-Ray & Color Palette", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = LuminaCyan)
            Spacer(Modifier.height(12.dp))

            val r = report
            if (r == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LuminaCyan)
                }
            } else {
                if (r.palette.isNotEmpty()) {
                    Text("Extracted Palette (Tap swatch to copy HEX)", style = MaterialTheme.typography.labelMedium, color = LuminaMuted)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(r.palette) { sw ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(LuminaCard)
                                    .clickable {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("HEX", sw.hex))
                                        Toast.makeText(context, "Copied: ${sw.hex}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(sw.colorInt))
                                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(sw.hex, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(sw.label, style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = LuminaCard)
                    Spacer(Modifier.height(10.dp))
                }

                InfoRow("File", item.displayName)
                InfoRow("Path", r.path)
                InfoRow("Size & Res", "${r.fileSize} • ${r.resolution}")
                InfoRow("Date", r.dateTaken)
                if (!item.isVideo) {
                    InfoRow("Camera", r.camera)
                    InfoRow("Exposure", "${r.aperture}  |  ${r.shutterSpeed}  |  ${r.iso}")
                    InfoRow("GPS Tag", r.location)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = LuminaMuted)
        Spacer(Modifier.size(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
