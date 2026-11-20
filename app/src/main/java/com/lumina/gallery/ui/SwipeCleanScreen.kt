package com.lumina.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lumina.gallery.data.MediaRepository
import com.lumina.gallery.model.DuplicateGroup
import com.lumina.gallery.model.MediaItem
import kotlin.math.roundToInt

@Composable
fun CleanHubScreen(
    allMedia: List<MediaItem>,
    onRequestBatchTrash: (List<MediaItem>) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Text("Smart Cleaner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = LuminaCyan)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Swipe Clean") })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Duplicate & Heavy Radar") })
        }
        Spacer(Modifier.height(10.dp))
        if (mode == 0) SwipeCleanDeck(allMedia = allMedia, onRequestBatchTrash = onRequestBatchTrash)
        else StorageDetectivePanel(allMedia = allMedia, onRequestBatchTrash = onRequestBatchTrash)
    }
}

@Composable
private fun SwipeCleanDeck(
    allMedia: List<MediaItem>,
    onRequestBatchTrash: (List<MediaItem>) -> Unit
) {
    var cursorIndex by remember(allMedia.size) { mutableIntStateOf(0) }
    val markedForTrash = remember { mutableStateListOf<MediaItem>() }
    var dragX by remember { mutableFloatStateOf(0f) }

    val current = allMedia.getOrNull(cursorIndex)
    val savedBytes = markedForTrash.sumOf { it.sizeBytes }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Card(colors = CardDefaults.cardColors(containerColor = LuminaCard), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Queued for Recycle Bin: ${markedForTrash.size}", fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("Space to reclaim: ${MediaRepository.formatSize(savedBytes)}", style = MaterialTheme.typography.labelSmall, color = LuminaGreen)
                }
                Button(
                    onClick = {
                        val batch = markedForTrash.toList()
                        markedForTrash.clear()
                        onRequestBatchTrash(batch)
                    },
                    enabled = markedForTrash.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuminaRed, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Trash", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (current == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("All media reviewed!", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { cursorIndex = 0 }) { Text("Review Again") }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .offset { IntOffset(dragX.roundToInt(), 0) }
                    .graphicsLayer(rotationZ = (dragX / 35f).coerceIn(-12f, 12f))
                    .clip(RoundedCornerShape(22.dp))
                    .background(LuminaCard)
                    .pointerInput(current.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    dragX < -180f -> { markedForTrash.add(current); cursorIndex++ }
                                    dragX > 180f -> cursorIndex++
                                }
                                dragX = 0f
                            },
                            onDragCancel = { dragX = 0f },
                            onHorizontalDrag = { _, amount -> dragX += amount }
                        )
                    }
            ) {
                AsyncImage(model = current.uri, contentDescription = current.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (dragX < -40f) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(10.dp)).background(LuminaRed.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("TRASH", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                    }
                } else if (dragX > 40f) {
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(16.dp).clip(RoundedCornerShape(10.dp)).background(LuminaGreen.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("KEEP", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.72f)).padding(14.dp)) {
                    Text(current.displayName, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${current.bucketName} • ${MediaRepository.formatSize(current.sizeBytes)} • ${MediaRepository.formatDateHeader(current.effectiveTimeSec)}", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { markedForTrash.add(current); cursorIndex++ }, modifier = Modifier.size(62.dp).clip(CircleShape).background(LuminaRed.copy(alpha = 0.18f))) {
                    Icon(Icons.Default.Close, "Trash", tint = LuminaRed, modifier = Modifier.size(30.dp))
                }
                Text("Swipe Left: Trash  •  Right: Keep", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
                IconButton(onClick = { cursorIndex++ }, modifier = Modifier.size(62.dp).clip(CircleShape).background(LuminaGreen.copy(alpha = 0.18f))) {
                    Icon(Icons.Default.Check, "Keep", tint = LuminaGreen, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
private fun StorageDetectivePanel(
    allMedia: List<MediaItem>,
    onRequestBatchTrash: (List<MediaItem>) -> Unit
) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(true) }
    var duplicates by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }

    val heavyFiles = remember(allMedia) { allMedia.filter { it.sizeBytes >= 15L * 1024L * 1024L }.sortedByDescending { it.sizeBytes }.take(30) }
    val screenshots = remember(allMedia) { allMedia.filter { it.isScreenshot } }

    LaunchedEffect(allMedia) {
        scanning = true
        duplicates = MediaRepository.findDuplicates(context, allMedia)
        scanning = false
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = LuminaCard), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FindInPage, null, tint = LuminaCyan)
                        Spacer(Modifier.size(8.dp))
                        Text("Offline Duplicate Radar", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    if (scanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(10.dp))
                            Text("Scanning SHA-256 fingerprints...", style = MaterialTheme.typography.bodySmall, color = LuminaMuted)
                        }
                    } else if (duplicates.isEmpty()) {
                        Text("No duplicate files detected!", style = MaterialTheme.typography.bodySmall, color = LuminaGreen)
                    } else {
                        val totalReclaim = duplicates.sumOf { it.reclaimableBytes }
                        val extraCopies = duplicates.flatMap { it.items.drop(1) }
                        Text("${extraCopies.size} duplicate copies across ${duplicates.size} groups (${MediaRepository.formatSize(totalReclaim)} reclaimable)", style = MaterialTheme.typography.bodySmall, color = LuminaMuted)
                        Button(
                            onClick = { onRequestBatchTrash(extraCopies) },
                            colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Move Extra Copies to Recycle Bin", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        if (screenshots.isNotEmpty()) {
            item {
                val ssBytes = screenshots.sumOf { it.sizeBytes }
                Card(colors = CardDefaults.cardColors(containerColor = LuminaCard), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Screenshots (${screenshots.size})", fontWeight = FontWeight.Bold)
                            Text("Using ${MediaRepository.formatSize(ssBytes)} of storage", style = MaterialTheme.typography.bodySmall, color = LuminaMuted)
                        }
                        OutlinedButton(onClick = { onRequestBatchTrash(screenshots.take(50)) }) { Text("Clean 50") }
                    }
                }
            }
        }
        item { Text("Largest Files (15 MB+ • ${heavyFiles.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White) }
        if (heavyFiles.isEmpty()) {
            item { Text("No media files larger than 15 MB.", style = MaterialTheme.typography.bodySmall, color = LuminaMuted) }
        } else {
            items(heavyFiles, key = { it.id }) { file ->
                Card(colors = CardDefaults.cardColors(containerColor = LuminaSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(LuminaCard), contentAlignment = Alignment.Center) {
                            AsyncImage(model = file.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (file.isVideo) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text("${MediaRepository.formatSize(file.sizeBytes)} • ${file.bucketName}", style = MaterialTheme.typography.labelSmall, color = LuminaCyan)
                        }
                        OutlinedButton(onClick = { onRequestBatchTrash(listOf(file)) }) { Text("Trash", color = LuminaRed) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
