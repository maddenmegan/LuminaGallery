package com.lumina.gallery.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumina.gallery.data.CropAspect
import com.lumina.gallery.data.CutoutBackground
import com.lumina.gallery.data.StudioEngine
import com.lumina.gallery.model.MediaItem
import com.lumina.gallery.model.StudioFilter
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StudioProScreen(
    item: MediaItem,
    onClose: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val historyStack = remember { mutableStateListOf<Bitmap>() }
    var isProcessing by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("Loading Studio Pro...") }
    var showBeforeCompare by remember { mutableStateOf(false) }
    var exportAsPng by remember { mutableStateOf(false) }

    // Active Tool Tab: 0=Adjust/Presets, 1=Crop, 2=Beauty AI, 3=Cutout (BG), 4=Magic Eraser & HD
    var activeTool by remember { mutableIntStateOf(0) }

    // Adjust & Preset State
    var selectedFilter by remember { mutableStateOf(StudioFilter.ORIGINAL) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var warmth by remember { mutableFloatStateOf(0f) }
    var vignette by remember { mutableFloatStateOf(0f) }

    // Crop State
    var cropAspect by remember { mutableStateOf(CropAspect.ORIGINAL) }

    // Beauty AI State
    var skinSmooth by remember { mutableFloatStateOf(0.45f) }
    var underEye by remember { mutableFloatStateOf(0.35f) }
    var eyeSparkle by remember { mutableFloatStateOf(0.30f) }
    var lipTint by remember { mutableFloatStateOf(0.15f) }
    var faceRelight by remember { mutableFloatStateOf(0.25f) }
    var portraitBokeh by remember { mutableFloatStateOf(0f) }

    // Cutout State
    var cutoutBg by remember { mutableStateOf(CutoutBackground.TRANSPARENT) }
    var edgeFeather by remember { mutableFloatStateOf(0.55f) }

    // Magic Eraser & HD State
    val eraserStrokes = remember { mutableStateListOf<List<Pair<Float, Float>>>() }
    var activeStroke by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var brushSizeRatio by remember { mutableFloatStateOf(0.06f) }
    var previewBoxSize by remember { mutableStateOf(IntSize(1, 1)) }
    var hdClarity by remember { mutableFloatStateOf(0.5f) }

    LaunchedEffect(item.id) {
        isProcessing = true
        val loaded = StudioEngine.loadEditableBitmap(context, item.uri, 1500)
        originalBitmap = loaded
        currentBitmap = loaded
        isProcessing = false
    }

    fun pushAndSet(nextBmp: Bitmap) {
        currentBitmap?.let { historyStack.add(it) }
        if (historyStack.size > 8) historyStack.removeAt(0)
        currentBitmap = nextBmp
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LuminaBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LuminaSurface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Lumina AI Studio Pro", color = LuminaCyan, fontWeight = FontWeight.Bold)
                Text(
                    text = if (exportAsPng) "PNG Transparent Mode" else "High-Quality JPG Mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = LuminaMuted
                )
            }
            if (historyStack.isNotEmpty()) {
                IconButton(onClick = {
                    val prev = historyStack.removeAt(historyStack.lastIndex)
                    currentBitmap = prev
                }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White)
                }
            }
            IconButton(onClick = { showBeforeCompare = !showBeforeCompare }) {
                Icon(
                    Icons.Default.Compare,
                    "Compare",
                    tint = if (showBeforeCompare) LuminaAmber else Color.White
                )
            }
            Button(
                onClick = {
                    val bmpToSave = currentBitmap ?: return@Button
                    scope.launch {
                        isProcessing = true
                        statusText = "Saving copy..."
                        val savedName = StudioEngine.saveBitmapToStudio(context, bmpToSave, exportAsPng)
                        isProcessing = false
                        if (savedName != null) onSaved(savedName)
                        else Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isProcessing && currentBitmap != null,
                colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save", fontWeight = FontWeight.Bold)
            }
        }

        // Canvas Preview Area (with Checkerboard for Transparent PNG + Interactive Magic Eraser Brush)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF10141D))
                .onSizeChanged { previewBoxSize = it },
            contentAlignment = Alignment.Center
        ) {
            val displayBmp = if (showBeforeCompare) originalBitmap else currentBitmap
            if (displayBmp != null) {
                // Draw subtle checkerboard behind image so transparent PNG cutout is clearly visible
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val step = 28f
                    var y = 0f
                    var row = 0
                    while (y < size.height) {
                        var x = 0f
                        var col = 0
                        while (x < size.width) {
                            val isLight = (row + col) % 2 == 0
                            drawRect(
                                color = if (isLight) Color(0xFF1B2230) else Color(0xFF121722),
                                topLeft = Offset(x, y),
                                size = androidx.compose.ui.geometry.Size(step, step)
                            )
                            x += step; col++
                        }
                        y += step; row++
                    }
                }

                // Image Display + Interactive Drawing Surface for Magic Eraser (Tab 4)
                val brushModifier = if (activeTool == 4) {
                    Modifier
                        .fillMaxSize()
                        .pointerInput(previewBoxSize) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val nx = (offset.x / previewBoxSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    val ny = (offset.y / previewBoxSize.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    activeStroke = listOf(nx to ny)
                                },
                                onDrag = { change, _ ->
                                    val nx = (change.position.x / previewBoxSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    val ny = (change.position.y / previewBoxSize.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    activeStroke = activeStroke + (nx to ny)
                                },
                                onDragEnd = {
                                    if (activeStroke.isNotEmpty()) {
                                        eraserStrokes.add(activeStroke)
                                        activeStroke = emptyList()
                                    }
                                }
                            )
                        }
                } else {
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    showBeforeCompare = true
                                    tryAwaitRelease()
                                    showBeforeCompare = false
                                }
                            )
                        }
                }

                Box(modifier = brushModifier, contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = displayBmp.asImageBitmap(),
                        contentDescription = "Studio Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Render red translucent brush strokes when Magic Eraser tab is active
                    if (activeTool == 4 && (eraserStrokes.isNotEmpty() || activeStroke.isNotEmpty())) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeW = minOf(size.width, size.height) * brushSizeRatio
                            val allPaths = eraserStrokes + listOfNotNull(activeStroke.takeIf { it.isNotEmpty() })
                            for (pts in allPaths) {
                                if (pts.size == 1) {
                                    drawCircle(
                                        color = LuminaRed.copy(alpha = 0.55f),
                                        radius = strokeW / 2f,
                                        center = Offset(pts[0].first * size.width, pts[0].second * size.height)
                                    )
                                } else if (pts.size > 1) {
                                    val p = Path()
                                    p.moveTo(pts[0].first * size.width, pts[0].second * size.height)
                                    for (i in 1 until pts.size) {
                                        p.lineTo(pts[i].first * size.width, pts[i].second * size.height)
                                    }
                                    drawPath(
                                        path = p,
                                        color = LuminaRed.copy(alpha = 0.55f),
                                        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                }
                            }
                        }
                    }
                }

                if (showBeforeCompare) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text("ORIGINAL (Hold to Compare)", color = LuminaAmber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = LuminaCyan)
                        Spacer(Modifier.height(10.dp))
                        Text(statusText, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Tool Controls Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(235.dp)
                .background(LuminaSurface)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (activeTool) {
                // TAB 0: Lightroom-Style Adjust & Aesthetic Presets
                0 -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StudioFilter.entries) { f ->
                            FilterChip(
                                selected = selectedFilter == f,
                                onClick = { selectedFilter = f },
                                label = { Text(f.label) }
                            )
                        }
                    }
                    StudioSlider("Exposure", exposure, -40f..40f) { exposure = it }
                    StudioSlider("Contrast", contrast, 0.6f..1.5f) { contrast = it }
                    StudioSlider("Saturation", saturation, 0f..1.8f) { saturation = it }
                    StudioSlider("Warmth", warmth, -40f..40f) { warmth = it }
                    StudioSlider("Vignette", vignette, 0f..1f) { vignette = it }
                    Button(
                        onClick = {
                            val base = currentBitmap ?: return@Button
                            scope.launch {
                                isProcessing = true
                                statusText = "Applying Color Grade..."
                                val res = StudioEngine.applyAdjustments(base, selectedFilter, exposure, contrast, saturation, warmth, vignette)
                                pushAndSet(res)
                                exposure = 0f; contrast = 1f; saturation = 1f; warmth = 0f; vignette = 0f; selectedFilter = StudioFilter.ORIGINAL
                                isProcessing = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Apply Color & Filter", fontWeight = FontWeight.Bold) }
                }

                // TAB 1: Smart Crop, Rotate & Flip
                1 -> {
                    Text("Aspect Ratio Crop", style = MaterialTheme.typography.labelMedium, color = LuminaMuted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CropAspect.entries) { asp ->
                            FilterChip(
                                selected = cropAspect == asp,
                                onClick = { cropAspect = asp },
                                label = { Text(asp.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                val base = currentBitmap ?: return@OutlinedButton
                                scope.launch {
                                    isProcessing = true; statusText = "Rotating..."
                                    pushAndSet(StudioEngine.applyCropTransform(base, CropAspect.ORIGINAL, 90, false, false))
                                    isProcessing = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rotate 90°")
                        }
                        OutlinedButton(
                            onClick = {
                                val base = currentBitmap ?: return@OutlinedButton
                                scope.launch {
                                    isProcessing = true; statusText = "Flipping..."
                                    pushAndSet(StudioEngine.applyCropTransform(base, CropAspect.ORIGINAL, 0, true, false))
                                    isProcessing = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Flip, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Mirror Flip")
                        }
                    }
                    Button(
                        onClick = {
                            val base = currentBitmap ?: return@Button
                            scope.launch {
                                isProcessing = true; statusText = "Cropping..."
                                pushAndSet(StudioEngine.applyCropTransform(base, cropAspect, 0, false, false))
                                cropAspect = CropAspect.ORIGINAL
                                isProcessing = false
                            }
                        },
                        enabled = cropAspect != CropAspect.ORIGINAL,
                        colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Apply ${cropAspect.label} Crop", fontWeight = FontWeight.Bold) }
                }

                // TAB 2: Natural Beauty AI & DSLR Portrait Bokeh
                2 -> {
                    Text("ML Kit Face Contour Retouch (Keeps Natural Skin Texture)", style = MaterialTheme.typography.labelSmall, color = LuminaCyan)
                    StudioSlider("Natural Skin Smooth", skinSmooth, 0f..1f) { skinSmooth = it }
                    StudioSlider("Under-Eye Brighten", underEye, 0f..1f) { underEye = it }
                    StudioSlider("Eye Sparkle & Clarity", eyeSparkle, 0f..1f) { eyeSparkle = it }
                    StudioSlider("Soft Natural Lip Tint", lipTint, 0f..1f) { lipTint = it }
                    StudioSlider("3D Studio Face Glow", faceRelight, 0f..1f) { faceRelight = it }
                    StudioSlider("DSLR Portrait Bokeh", portraitBokeh, 0f..1f) { portraitBokeh = it }
                    Button(
                        onClick = {
                            val base = currentBitmap ?: return@Button
                            scope.launch {
                                isProcessing = true
                                statusText = "Running Beauty AI Retouch..."
                                val res = StudioEngine.applyBeautyAiRetouch(
                                    context, base, skinSmooth, underEye, eyeSparkle, lipTint, faceRelight, portraitBokeh
                                )
                                pushAndSet(res)
                                isProcessing = false
                                Toast.makeText(context, "Beauty AI applied! Hold photo to compare.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LuminaViolet, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Apply Natural Beauty AI", fontWeight = FontWeight.Bold) }
                }

                // TAB 3: Precision AI Background Remover (Cutout + Backgrounds + PNG)
                3 -> {
                    Text("AI Subject & Object Background Remover", style = MaterialTheme.typography.labelSmall, color = LuminaCyan)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CutoutBackground.entries) { bg ->
                            FilterChip(
                                selected = cutoutBg == bg,
                                onClick = { cutoutBg = bg },
                                label = { Text(bg.label) }
                            )
                        }
                    }
                    StudioSlider("Edge Softness / Hair Feather", edgeFeather, 0.1f..1f) { edgeFeather = it }
                    Button(
                        onClick = {
                            val base = currentBitmap ?: return@Button
                            scope.launch {
                                isProcessing = true
                                statusText = "Removing Background with AI..."
                                val res = StudioEngine.applyBackgroundCutout(base, cutoutBg, edgeFeather)
                                pushAndSet(res)
                                exportAsPng = (cutoutBg == CutoutBackground.TRANSPARENT)
                                isProcessing = false
                                Toast.makeText(
                                    context,
                                    if (exportAsPng) "Cutout ready! Tap Save for Transparent PNG." else "Background replaced!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LuminaCyan, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Remove Background Now", fontWeight = FontWeight.Bold) }
                }

                // TAB 4: Interactive Magic Eraser + AI HD Enhancer
                4 -> {
                    Text("Paint with finger over any object/blemish above, then tap Erase:", style = MaterialTheme.typography.labelSmall, color = LuminaRed)
                    StudioSlider("Eraser Brush Size", brushSizeRatio, 0.02f..0.14f) { brushSizeRatio = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { eraserStrokes.clear(); activeStroke = emptyList() },
                            enabled = eraserStrokes.isNotEmpty()
                        ) { Text("Clear Brush") }
                        Button(
                            onClick = {
                                val base = currentBitmap ?: return@Button
                                val strokes = eraserStrokes.toList()
                                scope.launch {
                                    isProcessing = true
                                    statusText = "Magic Erasing..."
                                    val res = StudioEngine.applyMagicEraser(base, strokes, brushSizeRatio)
                                    pushAndSet(res)
                                    eraserStrokes.clear()
                                    isProcessing = false
                                }
                            },
                            enabled = eraserStrokes.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = LuminaRed, contentColor = Color.Black),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Magic Erase", fontWeight = FontWeight.Bold) }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("AI HD Quality Enhancer & Upscaler", style = MaterialTheme.typography.labelSmall, color = LuminaGreen)
                    StudioSlider("HD Clarity & Sharpen", hdClarity, 0.1f..1f) { hdClarity = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                val base = currentBitmap ?: return@OutlinedButton
                                scope.launch {
                                    isProcessing = true; statusText = "Enhancing Clarity..."
                                    pushAndSet(StudioEngine.applyHdEnhance(base, hdClarity, false))
                                    isProcessing = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("HD Enhance") }
                        Button(
                            onClick = {
                                val base = currentBitmap ?: return@Button
                                scope.launch {
                                    isProcessing = true; statusText = "AI 2x Upscaling..."
                                    pushAndSet(StudioEngine.applyHdEnhance(base, hdClarity, true))
                                    isProcessing = false
                                    Toast.makeText(context, "Upscaled 2x HD!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LuminaGreen, contentColor = Color.Black),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("2x HD Upscale", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // Bottom 5-Tool Selector Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF070A10))
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val tools = listOf(
                Triple(0, Icons.Default.Tune, "Adjust"),
                Triple(1, Icons.Default.Crop, "Crop"),
                Triple(2, Icons.Default.FaceRetouchingNatural, "Beauty AI"),
                Triple(3, Icons.Default.ContentCut, "BG Remove"),
                Triple(4, Icons.Default.AutoFixHigh, "Erase/HD")
            )
            tools.forEach { (idx, icon, label) ->
                val selected = activeTool == idx
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) LuminaCyan.copy(alpha = 0.18f) else Color.Transparent)
                        .pointerInput(idx) { detectTapGestures { activeTool = idx } }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(icon, label, tint = if (selected) LuminaCyan else LuminaMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) LuminaCyan else LuminaMuted)
                }
            }
        }
    }
}

@Composable
private fun StudioSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
            Text(String.format(Locale.US, "%.2f", value), style = MaterialTheme.typography.labelSmall, color = LuminaCyan)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
