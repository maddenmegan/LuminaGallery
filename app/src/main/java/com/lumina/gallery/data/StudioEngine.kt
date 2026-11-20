package com.lumina.gallery.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.lumina.gallery.data.AiEngine.awaitTask
import com.lumina.gallery.model.StudioFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CutoutBackground(val label: String) {
    TRANSPARENT("Transparent PNG"),
    WHITE("Pure White"),
    DARK_STUDIO("Studio Dark"),
    SOFT_BLUSH("Soft Blush"),
    OCEAN_GLOW("Ocean Gradient"),
    SUNSET("Sunset Gradient")
}

enum class CropAspect(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    PORTRAIT_4_5("4:5 IG", 4f / 5f),
    STORY_9_16("9:16 Story", 9f / 16f),
    WIDE_16_9("16:9 Wide", 16f / 9f)
}

object StudioEngine {

    suspend fun loadEditableBitmap(context: Context, uri: Uri, maxDim: Int = 1600): Bitmap? =
        withContext(Dispatchers.IO) {
            AiEngine.decodeDownsampledBitmap(context, uri, maxDim)
        }

    // 1. Lightroom-style Adjustments + Preset + Vignette
    suspend fun applyAdjustments(
        source: Bitmap,
        filter: StudioFilter,
        exposure: Float,
        contrast: Float,
        saturation: Float,
        warmth: Float,
        vignette: Float
    ): Bitmap = withContext(Dispatchers.Default) {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val cm = ColorMatrix(filter.matrix)

        // Saturation
        if (saturation != 1f) {
            val satM = ColorMatrix()
            satM.setSaturation(saturation.coerceIn(0f, 2f))
            cm.postConcat(satM)
        }

        // Contrast & Exposure
        val c = contrast.coerceIn(0.5f, 1.8f)
        val t = (-0.5f * c + 0.5f) * 255f + exposure * 1.4f
        val ceM = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(ceM)

        // Warmth (Temperature shift)
        if (abs(warmth) > 0.5f) {
            val w = warmth * 0.45f
            val warmM = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, w,
                    0f, 1f, 0f, 0f, w * 0.25f,
                    0f, 0f, 1f, 0f, -w,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(warmM)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)

        // Soft Radial Vignette
        if (vignette > 0.02f) {
            val radius = max(out.width, out.height) * 0.72f
            val vPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    out.width / 2f,
                    out.height / 2f,
                    radius,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Color.argb((vignette * 190f).toInt().coerceIn(0, 220), 0, 0, 0)
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), vPaint)
        }
        out
    }

    // 2. Smart Aspect Crop + Rotate + Flip
    suspend fun applyCropTransform(
        source: Bitmap,
        aspect: CropAspect,
        rotationDeg: Int,
        flipH: Boolean,
        flipV: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val m = Matrix()
        if (rotationDeg != 0) m.postRotate(rotationDeg.toFloat())
        if (flipH || flipV) m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)

        val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        val targetRatio = aspect.ratio ?: return@withContext transformed

        val w = transformed.width
        val h = transformed.height
        val currentRatio = w.toFloat() / h.toFloat()

        val cropW: Int
        val cropH: Int
        if (currentRatio > targetRatio) {
            cropH = h
            cropW = (h * targetRatio).toInt().coerceIn(1, w)
        } else {
            cropW = w
            cropH = (w / targetRatio).toInt().coerceIn(1, h)
        }

        val x = ((w - cropW) / 2).coerceAtLeast(0)
        val y = ((h - cropH) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(transformed, x, y, cropW, cropH)
        if (transformed != source && transformed != cropped) transformed.recycle()
        cropped
    }

    // 3. AI Portrait & Beauty Retouch (ML Kit Face Contours + Selfie Segmentation DSLR Bokeh)
    suspend fun applyBeautyAiRetouch(
        context: Context,
        source: Bitmap,
        skinSmooth: Float,       // 0f..1f
        underEyeBrighten: Float, // 0f..1f
        eyeSparkle: Float,       // 0f..1f
        lipTint: Float,          // 0f..1f
        faceRelight: Float,      // 0f..1f
        portraitBokeh: Float     // 0f..1f
    ): Bitmap = withContext(Dispatchers.Default) {
        var working = source.copy(Bitmap.Config.ARGB_8888, true)

        // Step A: DSLR Portrait Bokeh via ML Kit Selfie Segmentation (blurs background only)
        if (portraitBokeh > 0.05f) {
            val mask = computeAiForegroundMask(working)
            if (mask != null) {
                val blurRadius = (portraitBokeh * 14f).roundToInt().coerceIn(2, 16)
                val blurredBg = fastBoxBlur(working, blurRadius)
                val w = working.width
                val h = working.height
                val fgPixels = IntArray(w * h)
                val bgPixels = IntArray(w * h)
                working.getPixels(fgPixels, 0, w, 0, 0, w, h)
                blurredBg.getPixels(bgPixels, 0, w, 0, 0, w, h)
                blurredBg.recycle()

                for (i in fgPixels.indices) {
                    val alpha = mask[i].coerceIn(0f, 1f)
                    if (alpha < 0.99f) {
                        fgPixels[i] = blendPixels(bgPixels[i], fgPixels[i], alpha)
                    }
                }
                working.setPixels(fgPixels, 0, w, 0, 0, w, h)
            }
        }

        // Step B: ML Kit Face Contour Detection for Natural Skin Smooth, Eyes, Lips, Relight
        if (skinSmooth > 0.03f || underEyeBrighten > 0.03f || eyeSparkle > 0.03f || lipTint > 0.03f || faceRelight > 0.03f) {
            val faceOpts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
            val detector = FaceDetection.getClient(faceOpts)
            val faces = try {
                detector.process(InputImage.fromBitmap(working, 0)).awaitTask()
            } catch (_: Exception) {
                emptyList()
            } finally {
                detector.close()
            }

            if (faces.isNotEmpty()) {
                val canvas = Canvas(working)

                // Natural Skin Smooth (blends gentle blurred skin ONLY inside face oval, skipping eyes & lips)
                if (skinSmooth > 0.03f) {
                    val softSkin = fastBoxBlur(working, (skinSmooth * 7f).roundToInt().coerceIn(2, 8))
                    val maskBmp = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
                    val maskCanvas = Canvas(maskBmp)
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.FILL
                        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
                    }
                    val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                        style = Paint.Style.FILL
                        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
                    }

                    for (face in faces) {
                        val facePts = face.getContour(FaceContour.FACE)?.points
                        if (!facePts.isNullOrEmpty()) {
                            maskCanvas.drawPath(pointsToPath(facePts), fillPaint)
                        }
                        // Protect eyes, eyebrows, and lips so face NEVER looks blurry or plastic!
                        listOf(
                            FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
                            FaceContour.LEFT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_TOP,
                            FaceContour.UPPER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM
                        ).forEach { contourType ->
                            val pts = face.getContour(contourType)?.points
                            if (!pts.isNullOrEmpty()) {
                                maskCanvas.drawPath(pointsToPath(pts), erasePaint)
                            }
                        }
                    }

                    // Composite soft skin through mask at controlled natural opacity (max 62%)
                    val w = working.width
                    val h = working.height
                    val origPx = IntArray(w * h)
                    val softPx = IntArray(w * h)
                    val maskPx = IntArray(w * h)
                    working.getPixels(origPx, 0, w, 0, 0, w, h)
                    softSkin.getPixels(softPx, 0, w, 0, 0, w, h)
                    maskBmp.getPixels(maskPx, 0, w, 0, 0, w, h)
                    softSkin.recycle()
                    maskBmp.recycle()

                    val strength = skinSmooth * 0.62f
                    for (i in origPx.indices) {
                        val ma = Color.alpha(maskPx[i]) / 255f
                        if (ma > 0.02f) {
                            // Edge-preserving check: keep strong high-contrast detail (hair strands, edges)
                            val dr = abs(Color.red(origPx[i]) - Color.red(softPx[i]))
                            val dg = abs(Color.green(origPx[i]) - Color.green(softPx[i]))
                            val edgeProtect = if (dr + dg > 48) 0.25f else 1f
                            val blend = (ma * strength * edgeProtect).coerceIn(0f, 0.7f)
                            origPx[i] = blendPixels(origPx[i], softPx[i], blend)
                        }
                    }
                    working.setPixels(origPx, 0, w, 0, 0, w, h)
                }

                // Studio Relight (Warm subtle radial glow on face)
                if (faceRelight > 0.03f) {
                    for (face in faces) {
                        val b = face.boundingBox
                        val cx = b.exactCenterX()
                        val cy = b.exactCenterY()
                        val rad = max(b.width(), b.height()) * 0.72f
                        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = RadialGradient(
                                cx, cy, rad.coerceAtLeast(10f),
                                intArrayOf(
                                    Color.argb((faceRelight * 46f).toInt().coerceIn(0, 60), 255, 242, 224),
                                    Color.TRANSPARENT
                                ),
                                floatArrayOf(0f, 1f),
                                Shader.TileMode.CLAMP
                            )
                        }
                        canvas.drawCircle(cx, cy, rad, glowPaint)
                    }
                }

                // Under-Eye Brighten (Dark Circle Softening)
                if (underEyeBrighten > 0.03f) {
                    for (face in faces) {
                        listOf(FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE).forEach { eyeType ->
                            val pts = face.getContour(eyeType)?.points
                            if (!pts.isNullOrEmpty()) {
                                val avgX = pts.map { it.x }.average().toFloat()
                                val maxY = pts.maxOf { it.y }
                                val eyeW = (pts.maxOf { it.x } - pts.minOf { it.x }).coerceAtLeast(16f)
                                val underY = maxY + eyeW * 0.24f
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = Color.argb((underEyeBrighten * 52f).toInt().coerceIn(0, 68), 255, 245, 236)
                                    maskFilter = BlurMaskFilter(eyeW * 0.35f, BlurMaskFilter.Blur.NORMAL)
                                }
                                canvas.drawOval(
                                    avgX - eyeW * 0.55f,
                                    underY - eyeW * 0.18f,
                                    avgX + eyeW * 0.55f,
                                    underY + eyeW * 0.22f,
                                    paint
                                )
                            }
                        }
                    }
                }

                // Eye Sparkle (Subtle brightness & crisp catchlight inside eyes)
                if (eyeSparkle > 0.03f) {
                    for (face in faces) {
                        listOf(FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE).forEach { eyeType ->
                            val pts = face.getContour(eyeType)?.points
                            if (!pts.isNullOrEmpty()) {
                                val cx = pts.map { it.x }.average().toFloat()
                                val cy = pts.map { it.y }.average().toFloat()
                                val rad = ((pts.maxOf { it.x } - pts.minOf { it.x }) * 0.24f).coerceAtLeast(4f)
                                val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    shader = RadialGradient(
                                        cx, cy, rad * 1.4f,
                                        intArrayOf(
                                            Color.argb((eyeSparkle * 85f).toInt().coerceIn(0, 100), 255, 255, 255),
                                            Color.TRANSPARENT
                                        ),
                                        floatArrayOf(0f, 1f),
                                        Shader.TileMode.CLAMP
                                    )
                                }
                                canvas.drawCircle(cx, cy, rad * 1.4f, sparklePaint)
                            }
                        }
                    }
                }

                // Soft Natural Lip Tint (Rose Peach)
                if (lipTint > 0.03f) {
                    val lipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb((lipTint * 78f).toInt().coerceIn(0, 95), 220, 75, 98)
                        style = Paint.Style.FILL
                        maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
                    }
                    for (face in faces) {
                        val upper = face.getContour(FaceContour.UPPER_LIP_TOP)?.points.orEmpty()
                        val lower = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points.orEmpty()
                        if (upper.isNotEmpty() && lower.isNotEmpty()) {
                            val combined = upper + lower.reversed()
                            canvas.drawPath(pointsToPath(combined), lipPaint)
                        }
                    }
                }
            }
        }

        working
    }

    // 4. Precision AI Background Remover (ML Kit Soft Alpha Mask + Background Replacement)
    suspend fun applyBackgroundCutout(
        source: Bitmap,
        bgMode: CutoutBackground,
        edgeFeather: Float = 0.5f
    ): Bitmap = withContext(Dispatchers.Default) {
        val mask = computeAiForegroundMask(source)
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // Draw chosen background first (except TRANSPARENT)
        if (bgMode != CutoutBackground.TRANSPARENT) {
            val canvas = Canvas(out)
            when (bgMode) {
                CutoutBackground.WHITE -> canvas.drawColor(Color.WHITE)
                CutoutBackground.DARK_STUDIO -> canvas.drawColor(Color.rgb(18, 22, 32))
                CutoutBackground.SOFT_BLUSH -> {
                    val p = Paint().apply {
                        shader = LinearGradient(
                            0f, 0f, w.toFloat(), h.toFloat(),
                            Color.rgb(255, 228, 236), Color.rgb(243, 212, 245),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                }
                CutoutBackground.OCEAN_GLOW -> {
                    val p = Paint().apply {
                        shader = LinearGradient(
                            0f, 0f, w.toFloat(), h.toFloat(),
                            Color.rgb(14, 165, 233), Color.rgb(99, 102, 241),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                }
                CutoutBackground.SUNSET -> {
                    val p = Paint().apply {
                        shader = LinearGradient(
                            0f, 0f, 0f, h.toFloat(),
                            Color.rgb(251, 146, 60), Color.rgb(219, 39, 119),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                }
                CutoutBackground.TRANSPARENT -> {}
            }
        }

        val srcPx = IntArray(w * h)
        val dstPx = IntArray(w * h)
        source.getPixels(srcPx, 0, w, 0, 0, w, h)
        out.getPixels(dstPx, 0, w, 0, 0, w, h)

        val fgMask = mask ?: computeFallbackEdgeCutoutMask(source)
        val lowCut = 0.25f + (1f - edgeFeather) * 0.22f
        val highCut = 0.85f - (1f - edgeFeather) * 0.15f
        val span = max(0.05f, highCut - lowCut)

        for (i in srcPx.indices) {
            val raw = fgMask[i]
            val smoothAlpha = ((raw - lowCut) / span).coerceIn(0f, 1f)
            if (bgMode == CutoutBackground.TRANSPARENT) {
                val c = srcPx[i]
                val a = (smoothAlpha * 255f).toInt().coerceIn(0, 255)
                dstPx[i] = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
            } else {
                dstPx[i] = blendPixels(dstPx[i], srcPx[i], smoothAlpha)
            }
        }
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        out
    }

    private suspend fun computeAiForegroundMask(source: Bitmap): FloatArray? {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        return try {
            val segMask = segmenter.process(InputImage.fromBitmap(source, 0)).awaitTask()
            val mw = segMask.width
            val mh = segMask.height
            val buf = segMask.buffer
            buf.rewind()
            val rawMask = FloatArray(mw * mh)
            var fgCount = 0
            for (i in rawMask.indices) {
                val v = buf.float
                rawMask[i] = v
                if (v > 0.55f) fgCount++
            }
            // If ML Kit found almost no portrait/subject (< 1.5% pixels), return null to use smart object cutout
            if (fgCount < (mw * mh * 0.015f)) return null

            if (mw == source.width && mh == source.height) return rawMask

            // Bilinear upscale mask to full image resolution for silky hair edges
            val w = source.width
            val h = source.height
            val full = FloatArray(w * h)
            val sx = (mw - 1).toFloat() / max(1, w - 1)
            val sy = (mh - 1).toFloat() / max(1, h - 1)
            for (y in 0 until h) {
                val fy = y * sy
                val y0 = fy.toInt().coerceIn(0, mh - 1)
                val y1 = min(y0 + 1, mh - 1)
                val wy = fy - y0
                for (x in 0 until w) {
                    val fx = x * sx
                    val x0 = fx.toInt().coerceIn(0, mw - 1)
                    val x1 = min(x0 + 1, mw - 1)
                    val wx = fx - x0
                    val v00 = rawMask[y0 * mw + x0]
                    val v10 = rawMask[y0 * mw + x1]
                    val v01 = rawMask[y1 * mw + x0]
                    val v11 = rawMask[y1 * mw + x1]
                    full[y * w + x] = (v00 * (1 - wx) + v10 * wx) * (1 - wy) +
                        (v01 * (1 - wx) + v11 * wx) * wy
                }
            }
            full
        } catch (_: Exception) {
            null
        } finally {
            segmenter.close()
        }
    }

    // Smart Color-Border Object Cutout Fallback (for products/logos/non-human objects)
    private fun computeFallbackEdgeCutoutMask(src: Bitmap): FloatArray {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Sample average border background color
        var br = 0L; var bg = 0L; var bb = 0L; var cnt = 0L
        for (x in 0 until w step 4) {
            val top = px[x]; val bot = px[(h - 1) * w + x]
            br += Color.red(top) + Color.red(bot)
            bg += Color.green(top) + Color.green(bot)
            bb += Color.blue(top) + Color.blue(bot)
            cnt += 2
        }
        val avgR = (br / max(1L, cnt)).toInt()
        val avgG = (bg / max(1L, cnt)).toInt()
        val avgB = (bb / max(1L, cnt)).toInt()

        val mask = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val dist = abs(Color.red(c) - avgR) + abs(Color.green(c) - avgG) + abs(Color.blue(c) - avgB)
            mask[i] = ((dist - 48f) / 60f).coerceIn(0f, 1f)
        }
        return mask
    }

    // 5. Magic Eraser (Interactive Multi-Pass Boundary-Diffusion Object/Blemish Inpainting)
    suspend fun applyMagicEraser(
        source: Bitmap,
        normalizedStrokes: List<List<Pair<Float, Float>>>,
        brushRadiusRatio: Float
    ): Bitmap = withContext(Dispatchers.Default) {
        if (normalizedStrokes.isEmpty()) return@withContext source
        val w = source.width
        val h = source.height
        val out = source.copy(Bitmap.Config.ARGB_8888, true)

        // Render stroke mask
        val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBmp)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (min(w, h) * brushRadiusRatio).coerceIn(14f, 160f)
        }
        for (stroke in normalizedStrokes) {
            if (stroke.size == 1) {
                val p = stroke.first()
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
                maskCanvas.drawCircle(p.first * w, p.second * h, strokePaint.strokeWidth / 2f, dotPaint)
            } else if (stroke.size > 1) {
                val path = Path()
                path.moveTo(stroke[0].first * w, stroke[0].second * h)
                for (i in 1 until stroke.size) {
                    path.lineTo(stroke[i].first * w, stroke[i].second * h)
                }
                maskCanvas.drawPath(path, strokePaint)
            }
        }

        val pixels = IntArray(w * h)
        val maskPx = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        maskBmp.getPixels(maskPx, 0, w, 0, 0, w, h)
        maskBmp.recycle()

        val hole = BooleanArray(w * h) { Color.alpha(maskPx[it]) > 40 }
        val known = BooleanArray(w * h) { !hole[it] }

        // Step A: Onion-peel inward fill from known boundary pixels
        val frontier = ArrayList<Int>(4096)
        var remaining = hole.count { it }
        var safety = 0
        while (remaining > 0 && safety < 180) {
            safety++
            frontier.clear()
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val idx = row + x
                    if (!known[idx]) {
                        if (known[idx - 1] || known[idx + 1] || known[idx - w] || known[idx + w]) {
                            frontier.add(idx)
                        }
                    }
                }
            }
            if (frontier.isEmpty()) break
            for (idx in frontier) {
                var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                val neighbors = intArrayOf(idx - 1, idx + 1, idx - w, idx + w, idx - w - 1, idx - w + 1, idx + w - 1, idx + w + 1)
                for (n in neighbors) {
                    if (n in pixels.indices && known[n]) {
                        val c = pixels[n]
                        sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
                        cnt++
                    }
                }
                if (cnt > 0) {
                    pixels[idx] = Color.rgb(sr / cnt, sg / cnt, sb / cnt)
                }
            }
            for (idx in frontier) known[idx] = true
            remaining -= frontier.size
        }

        // Step B: 6-pass harmonic diffusion smoothing inside the erased patch for seamless texture
        val temp = pixels.clone()
        repeat(6) {
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val idx = row + x
                    if (hole[idx]) {
                        val c1 = pixels[idx - 1]
                        val c2 = pixels[idx + 1]
                        val c3 = pixels[idx - w]
                        val c4 = pixels[idx + w]
                        val r = (Color.red(c1) + Color.red(c2) + Color.red(c3) + Color.red(c4)) / 4
                        val g = (Color.green(c1) + Color.green(c2) + Color.green(c3) + Color.green(c4)) / 4
                        val b = (Color.blue(c1) + Color.blue(c2) + Color.blue(c3) + Color.blue(c4)) / 4
                        temp[idx] = Color.rgb(r, g, b)
                    }
                }
            }
            for (i in hole.indices) if (hole[i]) pixels[i] = temp[i]
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        out
    }

    // 6. AI HD Quality Enhancer (Auto-Tone + Unsharp Mask Clarity + Optional 2x Upscale)
    suspend fun applyHdEnhance(
        source: Bitmap,
        clarityAmount: Float, // 0f..1f
        upscale2x: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val base = if (upscale2x && max(source.width, source.height) <= 1800) {
            Bitmap.createScaledBitmap(source, source.width * 2, source.height * 2, true)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val w = base.width
        val h = base.height
        val blurred = fastBoxBlur(base, 2)
        val srcPx = IntArray(w * h)
        val blurPx = IntArray(w * h)
        base.getPixels(srcPx, 0, w, 0, 0, w, h)
        blurred.getPixels(blurPx, 0, w, 0, 0, w, h)
        blurred.recycle()

        // Unsharp Mask + gentle contrast clarity boost
        val strength = 0.55f + clarityAmount * 1.15f
        for (i in srcPx.indices) {
            val s = srcPx[i]
            val b = blurPx[i]
            val r = (Color.red(s) + ((Color.red(s) - Color.red(b)) * strength).toInt()).coerceIn(0, 255)
            val g = (Color.green(s) + ((Color.green(s) - Color.green(b)) * strength).toInt()).coerceIn(0, 255)
            val bl = (Color.blue(s) + ((Color.blue(s) - Color.blue(b)) * strength).toInt()).coerceIn(0, 255)
            srcPx[i] = Color.argb(Color.alpha(s), r, g, bl)
        }
        base.setPixels(srcPx, 0, w, 0, 0, w, h)
        base
    }

    // Save Edited Image (JPG or Transparent PNG) into Pictures/LuminaStudio
    suspend fun saveBitmapToStudio(
        context: Context,
        bitmap: Bitmap,
        saveAsPng: Boolean
    ): String? = withContext(Dispatchers.IO) {
        try {
            val ext = if (saveAsPng) "png" else "jpg"
            val mime = if (saveAsPng) "image/png" else "image/jpeg"
            val fileName = "LuminaPro_${System.currentTimeMillis()}.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LuminaStudio")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null

            resolver.openOutputStream(outUri)?.use { out ->
                if (saveAsPng) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            }
            fileName
        } catch (_: Exception) {
            null
        }
    }

    private fun pointsToPath(pts: List<android.graphics.PointF>): Path {
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        path.close()
        return path
    }

    private fun blendPixels(bg: Int, fg: Int, alpha: Float): Int {
        val inv = 1f - alpha
        val r = (Color.red(bg) * inv + Color.red(fg) * alpha).toInt().coerceIn(0, 255)
        val g = (Color.green(bg) * inv + Color.green(fg) * alpha).toInt().coerceIn(0, 255)
        val b = (Color.blue(bg) * inv + Color.blue(fg) * alpha).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // Fast Separable Box Blur (O(W*H), works smoothly on full-res phone photos)
    private fun fastBoxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val r = radius.coerceIn(1, 20)
        val srcPx = IntArray(w * h)
        val tmpPx = IntArray(w * h)
        val dstPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val div = r * 2 + 1

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = srcPx[row + k.coerceIn(0, w - 1)]
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
            }
            for (x in 0 until w) {
                tmpPx[row + x] = Color.rgb(sr / div, sg / div, sb / div)
                val outC = srcPx[row + (x - r).coerceIn(0, w - 1)]
                val inC = srcPx[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += Color.red(inC) - Color.red(outC)
                sg += Color.green(inC) - Color.green(outC)
                sb += Color.blue(inC) - Color.blue(outC)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = tmpPx[k.coerceIn(0, h - 1) * w + x]
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
            }
            for (y in 0 until h) {
                dstPx[y * w + x] = Color.rgb(sr / div, sg / div, sb / div)
                val outC = tmpPx[(y - r).coerceIn(0, h - 1) * w + x]
                val inC = tmpPx[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += Color.red(inC) - Color.red(outC)
                sg += Color.green(inC) - Color.green(outC)
                sb += Color.blue(inC) - Color.blue(outC)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        return out
    }
}
