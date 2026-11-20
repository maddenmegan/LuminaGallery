package com.lumina.gallery.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.gallery.data.AiEngine
import com.lumina.gallery.model.FaceCluster
import com.lumina.gallery.model.MediaItem
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PeopleScreen(
    allMedia: List<MediaItem>,
    favorites: Set<Long>,
    onOpenMedia: (List<MediaItem>, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var clusters by remember { mutableStateOf(AiEngine.loadCachedClusters(context)) }
    var isScanning by remember { mutableStateOf(false) }
    var scannedCount by remember { mutableIntStateOf(0) }
    var totalToScan by remember { mutableIntStateOf(0) }
    var selectedPerson by remember { mutableStateOf<FaceCluster?>(null) }
    var renamingPerson by remember { mutableStateOf<FaceCluster?>(null) }

    val mediaById = remember(allMedia) { allMedia.associateBy { it.id } }

    fun triggerScan(resetFirst: Boolean = false) {
        if (isScanning) return
        scope.launch {
            isScanning = true
            if (resetFirst) {
                AiEngine.resetFaceCache(context)
                clusters = emptyList()
            }
            scannedCount = 0
            totalToScan = 0
            clusters = AiEngine.scanAndClusterFaces(
                context = context,
                photos = allMedia,
                maxScanBatch = 300,
                onProgress = { done, total, _ ->
                    scannedCount = done
                    totalToScan = total
                }
            )
            isScanning = false
        }
    }

    LaunchedEffect(allMedia.size) {
        if (clusters.isEmpty() && allMedia.any { !it.isVideo && !it.isScreenshot } && !isScanning) {
            triggerScan(false)
        }
    }

    val activePerson = selectedPerson
    if (activePerson != null) {
        val personPhotos = remember(activePerson, mediaById) {
            activePerson.mediaIds.mapNotNull { mediaById[it] }
                .sortedByDescending { it.effectiveTimeSec }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedPerson = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                AsyncImage(
                    model = File(activePerson.avatarPath),
                    contentDescription = activePerson.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(CircleShape).border(1.5.dp, LuminaCyan, CircleShape)
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(activePerson.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("${personPhotos.size} photos found", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
                }
                IconButton(onClick = { renamingPerson = activePerson }) {
                    Icon(Icons.Default.Edit, "Rename", tint = LuminaCyan)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(personPhotos, key = { it.id }) { media ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .animateItem()
                            .background(LuminaCard)
                            .clickable {
                                val idx = personPhotos.indexOf(media).coerceAtLeast(0)
                                onOpenMedia(personPhotos, idx)
                            }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(media.uri).size(360).crossfade(true).build(),
                            contentDescription = media.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("AI People & Faces", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = LuminaCyan)
                    Text("${clusters.size} people grouped • 100% On-Device AI", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
                }
                IconButton(onClick = { triggerScan(false) }, enabled = !isScanning) {
                    Icon(Icons.Default.Refresh, "Scan More", tint = Color.White)
                }
            }

            Spacer(Modifier.height(10.dp))

            AnimatedVisibility(visible = isScanning) {
                val progress by animateFloatAsState(
                    targetValue = if (totalToScan > 0) scannedCount.toFloat() / totalToScan.toFloat() else 0f,
                    animationSpec = spring(),
                    label = "faceProgress"
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = LuminaCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Scanning faces offline...", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("$scannedCount / $totalToScan", color = LuminaCyan, style = MaterialTheme.typography.labelMedium)
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                            color = LuminaCyan,
                            trackColor = LuminaPill
                        )
                    }
                }
            }

            if (clusters.isEmpty() && !isScanning) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Face, null, tint = LuminaViolet, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No faces grouped yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap below to scan your gallery completely offline.", style = MaterialTheme.typography.bodySmall, color = LuminaMuted)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { triggerScan(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Start AI Face Scan", fontWeight = FontWeight.Bold) }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { triggerScan(false) }, enabled = !isScanning, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("Scan New Photos")
                    }
                    OutlinedButton(onClick = { triggerScan(true) }, enabled = !isScanning, shape = RoundedCornerShape(12.dp)) {
                        Text("Rescan All")
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(clusters, key = { it.clusterId }) { person ->
                        val activeCount = person.mediaIds.count { mediaById.containsKey(it) }
                        AnimatedVisibility(visible = activeCount > 0, enter = fadeIn() + scaleIn()) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .animateItem()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { selectedPerson = person }
                                    .padding(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(92.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, Brush.linearGradient(listOf(LuminaCyan, LuminaViolet)), CircleShape)
                                        .padding(3.dp)
                                ) {
                                    AsyncImage(
                                        model = File(person.avatarPath),
                                        contentDescription = person.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(person.displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                                Text("$activeCount photos", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
                            }
                        }
                    }
                }
            }
        }
    }

    val targetRename = renamingPerson
    if (targetRename != null) {
        var nameInput by remember(targetRename.clusterId) { mutableStateOf(targetRename.displayName) }
        AlertDialog(
            onDismissRequest = { renamingPerson = null },
            containerColor = LuminaSurface,
            title = { Text("Rename Person") },
            text = { OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    AiEngine.renamePerson(context, targetRename.clusterId, nameInput)
                    clusters = AiEngine.loadCachedClusters(context)
                    selectedPerson = clusters.find { it.clusterId == targetRename.clusterId }
                    renamingPerson = null
                    Toast.makeText(context, "Saved name!", Toast.LENGTH_SHORT).show()
                }) { Text("Save", color = LuminaCyan) }
            },
            dismissButton = { TextButton(onClick = { renamingPerson = null }) { Text("Cancel") } }
        )
    }
}
