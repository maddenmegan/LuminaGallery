package com.lumina.gallery.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.gallery.data.AiEngine
import com.lumina.gallery.data.MediaRepository
import com.lumina.gallery.model.MediaItem
import com.lumina.gallery.model.OmniIndexEntry
import com.lumina.gallery.model.PdfDocItem
import kotlinx.coroutines.launch
import java.io.File

private data class ScoredMediaHit(
    val item: MediaItem,
    val score: Int,
    val matchHint: String
)

private data class ScoredPdfHit(
    val pdf: PdfDocItem,
    val score: Int,
    val matchHint: String
)

@Composable
fun OmniSearchScreen(
    allMedia: List<MediaItem>,
    onOpenMedia: (List<MediaItem>, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var scopeFilter by remember { mutableIntStateOf(0) } // 0=All, 1=Photos, 2=Videos, 3=PDFs, 4=Screenshots
    var pdfs by remember { mutableStateOf<List<PdfDocItem>>(emptyList()) }
    var omniIndex by remember { mutableStateOf<Map<String, OmniIndexEntry>>(AiEngine.loadOmniIndex(context)) }
    var isIndexing by remember { mutableStateOf(false) }
    var indexDone by remember { mutableIntStateOf(0) }
    var indexTotal by remember { mutableIntStateOf(0) }
    var currentFile by remember { mutableStateOf("") }

    val needsAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    fun refreshPdfs() {
        scope.launch {
            pdfs = AiEngine.loadAllPdfs(context)
        }
    }

    fun startAiIndexing() {
        if (isIndexing) return
        scope.launch {
            isIndexing = true
            indexDone = 0
            indexTotal = 0
            val loadedPdfs = AiEngine.loadAllPdfs(context)
            pdfs = loadedPdfs
            omniIndex = AiEngine.buildOmniSearchIndex(
                context = context,
                media = allMedia,
                pdfs = loadedPdfs,
                maxMediaBatch = 220,
                maxPdfBatch = 80,
                onProgress = { d, t, label ->
                    indexDone = d
                    indexTotal = t
                    currentFile = label
                }
            )
            // Refresh PDFs again so newly cached Page-1 thumbnails appear immediately
            pdfs = AiEngine.loadAllPdfs(context)
            isIndexing = false
            Toast.makeText(context, "AI Memory Index updated (${omniIndex.size} files)!", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(allMedia.size) {
        refreshPdfs()
    }

    val tokens = remember(query) {
        query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
    }

    // Score & rank matching Media (Photos, Screenshots, Videos) by filename, folder, OCR text, AI labels, color & date
    val matchedMedia = remember(allMedia, omniIndex, tokens, scopeFilter) {
        if (scopeFilter == 3) return@remember emptyList<ScoredMediaHit>()
        val base = allMedia.filter { item ->
            when (scopeFilter) {
                1 -> !item.isVideo
                2 -> item.isVideo
                4 -> item.isScreenshot
                else -> true
            }
        }
        if (tokens.isEmpty()) {
            base.take(24).map { ScoredMediaHit(it, 1, it.bucketName) }
        } else {
            val hits = mutableListOf<ScoredMediaHit>()
            for (item in base) {
                val key = "${if (item.isVideo) "V" else "P"}_${item.id}"
                val idx = omniIndex[key]
                var score = 0
                val reasons = mutableListOf<String>()

                val nameLow = item.displayName.lowercase()
                val folderLow = item.bucketName.lowercase()
                val dateLow = MediaRepository.formatDateHeader(item.effectiveTimeSec).lowercase()
                val ocrLow = idx?.ocrText?.lowercase().orEmpty()
                val labels = idx?.labels.orEmpty()
                val colors = idx?.colorTags.orEmpty()

                for (tk in tokens) {
                    var matchedToken = false
                    if (tk == "video" && item.isVideo) { score += 5; matchedToken = true }
                    if ((tk == "photo" || tk == "image") && !item.isVideo) { score += 4; matchedToken = true }
                    if (tk == "screenshot" && item.isScreenshot) { score += 7; reasons.add("Screenshot"); matchedToken = true }
                    if (nameLow.contains(tk)) { score += 8; reasons.add("Name"); matchedToken = true }
                    if (folderLow.contains(tk)) { score += 6; reasons.add("Folder: ${item.bucketName}"); matchedToken = true }
                    if (dateLow.contains(tk)) { score += 5; reasons.add(dateLow); matchedToken = true }
                    if (ocrLow.contains(tk)) {
                        score += 12
                        val pos = ocrLow.indexOf(tk)
                        val snippet = idx!!.ocrText.substring(
                            (pos - 12).coerceAtLeast(0),
                            (pos + 32).coerceAtMost(idx.ocrText.length)
                        ).trim()
                        reasons.add("Text: \"$snippet\"")
                        matchedToken = true
                    }
                    val lblMatch = labels.firstOrNull { it.contains(tk) }
                    if (lblMatch != null) {
                        score += 10
                        reasons.add("AI: $lblMatch")
                        matchedToken = true
                    }
                    if (colors.any { it == tk }) {
                        score += 7
                        reasons.add("Color: $tk")
                        matchedToken = true
                    }
                    if (!matchedToken && tokens.size > 1) {
                        score -= 3
                    }
                }

                if (score > 0) {
                    hits.add(
                        ScoredMediaHit(
                            item = item,
                            score = score,
                            matchHint = reasons.distinct().take(2).joinToString(" • ").ifBlank { item.bucketName }
                        )
                    )
                }
            }
            hits.sortedByDescending { it.score }.take(60)
        }
    }

    // Score & rank matching PDFs by filename, folder, AND Page-1 OCR content inside the PDF!
    val matchedPdfs = remember(pdfs, omniIndex, tokens, scopeFilter) {
        if (scopeFilter in listOf(1, 2, 4)) return@remember emptyList<ScoredPdfHit>()
        if (tokens.isEmpty()) {
            pdfs.take(20).map { ScoredPdfHit(it, 1, "Folder: ${it.folderName}") }
        } else {
            val hits = mutableListOf<ScoredPdfHit>()
            for (pdf in pdfs) {
                val idx = omniIndex[pdf.id]
                var score = 0
                val reasons = mutableListOf<String>()
                val nameLow = pdf.name.lowercase()
                val folderLow = pdf.folderName.lowercase()
                val ocrLow = idx?.ocrText?.lowercase().orEmpty()

                for (tk in tokens) {
                    if (tk == "pdf" || tk == "doc" || tk == "document") { score += 5; reasons.add("PDF") }
                    if (nameLow.contains(tk)) { score += 10; reasons.add("Filename") }
                    if (folderLow.contains(tk)) { score += 6; reasons.add("Folder: ${pdf.folderName}") }
                    if (ocrLow.contains(tk)) {
                        score += 14
                        val pos = ocrLow.indexOf(tk)
                        val snippet = idx!!.ocrText.substring(
                            (pos - 12).coerceAtLeast(0),
                            (pos + 36).coerceAtMost(idx.ocrText.length)
                        ).trim()
                        reasons.add("Inside PDF: \"$snippet\"")
                    }
                }
                if (score > 0) {
                    hits.add(
                        ScoredPdfHit(
                            pdf = pdf,
                            score = score,
                            matchHint = reasons.distinct().take(2).joinToString(" • ").ifBlank { pdf.folderName }
                        )
                    )
                }
            }
            hits.sortedByDescending { it.score }.take(40)
        }
    }

    val quickHints = listOf("pdf", "screenshot", "receipt", "car", "food", "person", "red", "blue", "sky", "document")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "AI OmniSearch",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = LuminaCyan
                )
                Text(
                    text = "Find lost Photos, Videos, Screenshots & PDFs by any hint",
                    style = MaterialTheme.typography.labelSmall,
                    color = LuminaMuted
                )
            }
            IconButton(onClick = { startAiIndexing() }, enabled = !isIndexing) {
                Icon(Icons.Default.Refresh, "Index AI", tint = LuminaCyan)
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Type any hint: word in screenshot, PDF text, object, color...") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = LuminaCyan) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        // Scope Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            val scopes = listOf("All", "Photos", "Videos", "PDFs (${pdfs.size})", "Screenshots")
            items(scopes.size) { i ->
                FilterChip(
                    selected = scopeFilter == i,
                    onClick = { scopeFilter = i },
                    label = { Text(scopes[i]) }
                )
            }
        }

        // Quick Hint Chips
        if (query.isBlank()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(quickHints) { hint ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(LuminaPill)
                            .clickable { query = hint }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("#$hint", style = MaterialTheme.typography.labelSmall, color = LuminaCyan)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Optional Full PDF Access Banner on Android 11+
            if (needsAllFilesAccess) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LuminaAmber.copy(alpha = 0.14f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Unlock Full PDF Search", fontWeight = FontWeight.Bold, color = LuminaAmber)
                                Text(
                                    "Allow All Files Access to search PDFs inside Downloads, WhatsApp & Telegram.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                            OutlinedButton(onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            }) {
                                Text("Enable", color = LuminaAmber)
                            }
                        }
                    }
                }
            }

            // AI Memory Indexer Status Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LuminaCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, null, tint = LuminaCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "AI Memory Index: ${omniIndex.size} files indexed",
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!isIndexing) {
                                Text(
                                    text = "Scan More",
                                    color = LuminaCyan,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clickable { startAiIndexing() }
                                )
                            }
                        }
                        AnimatedVisibility(visible = isIndexing) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { if (indexTotal > 0) indexDone.toFloat() / indexTotal else 0f },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                                    color = LuminaCyan,
                                    trackColor = LuminaPill
                                )
                                Text(
                                    text = "Indexing ($indexDone/$indexTotal): $currentFile",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LuminaMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (!isIndexing && omniIndex.isEmpty()) {
                            Button(
                                onClick = { startAiIndexing() },
                                colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Build Offline AI Search Index Now", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Matched PDFs Section
            if (matchedPdfs.isNotEmpty()) {
                item {
                    Text(
                        text = "PDF Documents (${matchedPdfs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LuminaRed
                    )
                }
                items(matchedPdfs, key = { it.pdf.id }) { hit ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LuminaSurface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val opened = MediaRepository.openPdfExternally(context, hit.pdf)
                                if (!opened) Toast.makeText(context, "No PDF viewer app found", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LuminaRed.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (hit.pdf.thumbPath != null && File(hit.pdf.thumbPath).exists()) {
                                    AsyncImage(
                                        model = File(hit.pdf.thumbPath),
                                        contentDescription = hit.pdf.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Default.PictureAsPdf, null, tint = LuminaRed, modifier = Modifier.size(28.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = hit.pdf.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = hit.matchHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LuminaCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FolderOpen, null, tint = LuminaMuted, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "${hit.pdf.folderName} • ${MediaRepository.formatSize(hit.pdf.sizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LuminaMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Matched Photos, Screenshots & Videos Section
            if (matchedMedia.isNotEmpty()) {
                item {
                    Text(
                        text = if (tokens.isEmpty()) "Recent Media" else "Matching Photos & Videos (${matchedMedia.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                val rawItems = matchedMedia.map { it.item }
                items(matchedMedia, key = { "${it.item.isVideo}_${it.item.id}" }) { hit ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LuminaSurface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val idx = rawItems.indexOf(hit.item).coerceAtLeast(0)
                                onOpenMedia(rawItems, idx)
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(62.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LuminaCard),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(hit.item.uri)
                                        .size(200)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = hit.item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (hit.item.isVideo) {
                                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = hit.item.displayName,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = hit.matchHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LuminaCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${hit.item.bucketName} • ${MediaRepository.formatDateHeader(hit.item.effectiveTimeSec)} • ${MediaRepository.formatSize(hit.item.sizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LuminaMuted
                                )
                            }
                        }
                    }
                }
            }

            if (tokens.isNotEmpty() && matchedMedia.isEmpty() && matchedPdfs.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Description, null, tint = LuminaMuted, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No matches found for \"$query\"", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Try tapping 'Scan More' above so AI indexes more screenshots, videos & PDFs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LuminaMuted
                        )
                    }
                }
            }
        }
    }
}
