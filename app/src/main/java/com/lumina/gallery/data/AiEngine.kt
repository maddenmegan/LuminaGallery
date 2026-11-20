package com.lumina.gallery.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lumina.gallery.model.FaceCluster
import com.lumina.gallery.model.MediaItem
import com.lumina.gallery.model.OcrResultData
import com.lumina.gallery.model.OmniIndexEntry
import com.lumina.gallery.model.PdfDocItem
import com.lumina.gallery.model.QrItemData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AiEngine {

    private const val PREF_AI = "lumina_ai_v3"
    private const val KEY_CLUSTERS = "face_clusters_v3"
    private const val KEY_SCANNED_IDS = "scanned_photo_ids_v3"
    private const val KEY_CUSTOM_NAMES = "face_custom_names_v3"
    private const val KEY_GEMINI_API = "gemini_api_key"
    private const val OMNI_FILE = "lumina_omni_index_v3.json"

    suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { ex ->
            if (cont.isActive) cont.resumeWithException(ex)
        }
    }

    fun getGeminiApiKey(context: Context): String =
        context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
            .getString(KEY_GEMINI_API, "") ?: ""

    fun setGeminiApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEMINI_API, key.trim()).apply()
    }

    // =========================================================================
    // 1. UNIVERSAL OCR (OFFLINE ML KIT + ONLINE MULTI-LANGUAGE BANGLA/ARABIC/ALL)
    // =========================================================================
    private fun extractEntities(fullText: String, engine: String): OcrResultData {
        val clean = fullText.trim()
        val lines = clean.lines().map { it.trim() }.filter { it.isNotBlank() }
        val urlRegex = Regex("https?://[^\\s]+|www\\.[^\\s]+", RegexOption.IGNORE_CASE)
        val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val phoneRegex = Regex("(?:\\+?\\d{1,3}[\\s-]?)?(?:\\(?\\d{3,4}\\)?[\\s-]?)\\d{3,4}[\\s-]?\\d{3,5}")

        val urls = urlRegex.findAll(clean).map { it.value.trimEnd('.', ',', ')') }.distinct().toList()
        val emails = emailRegex.findAll(clean).map { it.value }.distinct().toList()
        val phones = phoneRegex.findAll(clean)
            .map { it.value.trim() }
            .filter { it.count { ch -> ch.isDigit() } in 8..15 }
            .distinct()
            .toList()

        return OcrResultData(
            fullText = clean,
            lines = lines,
            urls = urls,
            phones = phones,
            emails = emails,
            sourceEngine = engine
        )
    }

    suspend fun recognizeTextOffline(context: Context, uri: Uri): OcrResultData =
        withContext(Dispatchers.IO) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val image = InputImage.fromFilePath(context, uri)
                val result = recognizer.process(image).awaitTask()
                extractEntities(result.text, "Offline ML Kit")
            } catch (_: Exception) {
                OcrResultData("", emptyList(), sourceEngine = "Offline ML Kit")
            } finally {
                recognizer.close()
            }
        }

    // Online Multi-Language OCR (Bangla, Arabic, Hindi, Urdu, English, Handwriting)
    suspend fun recognizeTextAnyLanguageOnline(context: Context, uri: Uri): OcrResultData =
        withContext(Dispatchers.IO) {
            val bmp = decodeDownsampledBitmap(context, uri, 1280)
                ?: return@withContext OcrResultData("", emptyList(), sourceEngine = "Failed to load image")

            val jpegBytes = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
            bmp.recycle()
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

            // Tier A: If user configured a free Google AI Studio Gemini key -> 99% accurate ANY language (Bangla/Arabic/etc.)
            val geminiKey = getGeminiApiKey(context)
            if (geminiKey.isNotBlank()) {
                val geminiText = callGeminiVisionOcr(geminiKey, b64)
                if (!geminiText.isNullOrBlank()) {
                    return@withContext extractEntities(geminiText, "Gemini Vision AI (All Languages)")
                }
            }

            // Tier B: Built-in Zero-Setup Cloud OCR fallback (Multi-Language OCR.space Engine 2)
            val cloudText = callOcrSpaceFallback(b64)
            if (!cloudText.isNullOrBlank()) {
                return@withContext extractEntities(cloudText, "Cloud Multi-Language OCR")
            }

            // Tier C: Fallback to offline ML Kit
            val offline = recognizeTextOffline(context, uri)
            offline.copy(sourceEngine = "Offline Fallback (Check Internet or add Gemini Key in Menu)")
        }

    private fun callGeminiVisionOcr(apiKey: String, base64Jpeg: String): String? {
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put(
                                "parts",
                                JSONArray()
                                    .put(
                                        JSONObject().put(
                                            "text",
                                            "Extract ALL visible text from this image accurately in its original language (Bangla, Arabic, English, Hindi, or any language). Return ONLY the extracted text with original line breaks, no commentary."
                                        )
                                    )
                                    .put(
                                        JSONObject().put(
                                            "inline_data",
                                            JSONObject().apply {
                                                put("mime_type", "image/jpeg")
                                                put("data", base64Jpeg)
                                            }
                                        )
                                    )
                            )
                        }
                    )
                )
            }

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val root = JSONObject(body)
                root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun callOcrSpaceFallback(base64Jpeg: String): String? {
        return try {
            val conn = (URL("https://api.ocr.space/parse/image").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("apikey", "helloworld")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val dataUri = "data:image/jpeg;base64,$base64Jpeg"
            val form = "base64Image=${URLEncoder.encode(dataUri, "UTF-8")}&OCREngine=2&scale=true"
            OutputStreamWriter(conn.outputStream).use { it.write(form) }

            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val root = JSONObject(body)
                val results = root.optJSONArray("ParsedResults")
                results?.optJSONObject(0)?.optString("ParsedText")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 2. SMART DUAL QR & BARCODE ENGINE (POSTERS / IMAGES + LIVE CAMERA)
    // =========================================================================
    fun mapBarcodeToQrItem(bc: Barcode): QrItemData? {
        val raw = bc.rawValue ?: bc.displayValue ?: return null
        val display = bc.displayValue ?: raw
        return when (bc.valueType) {
            Barcode.TYPE_URL -> QrItemData(
                rawValue = raw,
                displayValue = bc.url?.url ?: display,
                typeLabel = "Website Link",
                url = bc.url?.url ?: raw
            )
            Barcode.TYPE_WIFI -> QrItemData(
                rawValue = raw,
                displayValue = "Wi-Fi: ${bc.wifi?.ssid ?: "Network"}",
                typeLabel = "Wi-Fi Network",
                wifiSsid = bc.wifi?.ssid,
                wifiPassword = bc.wifi?.password
            )
            Barcode.TYPE_PHONE -> QrItemData(
                rawValue = raw,
                displayValue = bc.phone?.number ?: display,
                typeLabel = "Phone Number",
                phone = bc.phone?.number ?: raw
            )
            Barcode.TYPE_EMAIL -> QrItemData(
                rawValue = raw,
                displayValue = bc.email?.address ?: display,
                typeLabel = "Email",
                email = bc.email?.address ?: raw
            )
            else -> {
                val looksLikeUrl = raw.startsWith("http://", true) || raw.startsWith("https://", true)
                QrItemData(
                    rawValue = raw,
                    displayValue = display,
                    typeLabel = if (looksLikeUrl) "Website Link" else "QR / Barcode",
                    url = if (looksLikeUrl) raw else null
                )
            }
        }
    }

    suspend fun scanQrFromUri(context: Context, uri: Uri): List<QrItemData> =
        withContext(Dispatchers.IO) {
            val scanner = BarcodeScanning.getClient()
            try {
                val image = InputImage.fromFilePath(context, uri)
                val barcodes = scanner.process(image).awaitTask()
                barcodes.mapNotNull { mapBarcodeToQrItem(it) }.distinctBy { it.rawValue }
            } catch (_: Exception) {
                emptyList()
            } finally {
                scanner.close()
            }
        }

    // =========================================================================
    // 3. UNIVERSAL PDF DISCOVERY + PAGE-1 RENDERING (PdfRenderer)
    // =========================================================================
    suspend fun loadAllPdfs(context: Context): List<PdfDocItem> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, PdfDocItem>()
        val thumbDir = File(context.cacheDir, "pdf_thumbs").apply { if (!exists()) mkdirs() }

        // A. Direct fast filesystem walk (works with All Files Access on Android 11+ or Android <= 10)
        val canWalkFs = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        if (canWalkFs) {
            val root = Environment.getExternalStorageDirectory()
            val scanDirs = listOf(
                File(root, "Download"),
                File(root, "Downloads"),
                File(root, "Documents"),
                File(root, "WhatsApp/Media/WhatsApp Documents"),
                File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
                File(root, "Telegram/Telegram Documents"),
                File(root, "DCIM")
            )
            for (dir in scanDirs) {
                if (dir.exists() && dir.isDirectory) {
                    collectPdfsRecursive(dir, 0, 4, thumbDir, found)
                }
            }
            // Also check top-level PDFs directly in root
            root.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".pdf", true) && f.length() > 0) {
                    addPdfFile(f, thumbDir, found)
                }
            }
        }

        // B. MediaStore.Files query (always works for scoped media/downloads)
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val cols = mutableListOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cols.add(MediaStore.Files.FileColumns.RELATIVE_PATH)
            }
            val sel = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("application/pdf", "%.pdf")

            context.contentResolver.query(
                uri,
                cols.toTypedArray(),
                sel,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val modIdx = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                } else -1

                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "Document.pdf" else "Document.pdf"
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val mod = if (modIdx >= 0) c.getLong(modIdx) else 0L
                    val rel = if (relIdx >= 0) c.getString(relIdx) ?: "Storage" else "Storage"
                    val contentUri = ContentUris.withAppendedId(uri, id)
                    val key = "${name.lowercase()}_$size"
                    if (!found.containsKey(key) && size > 0) {
                        val thumb = File(thumbDir, "pdf_${key.hashCode()}.jpg")
                        found[key] = PdfDocItem(
                            id = "pdf_ms_$id",
                            name = name,
                            folderName = rel.trimEnd('/').substringAfterLast('/').ifBlank { "Documents" },
                            filePath = null,
                            uri = contentUri,
                            sizeBytes = size,
                            modifiedSec = mod,
                            thumbPath = if (thumb.exists()) thumb.absolutePath else null
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        found.values.sortedByDescending { it.modifiedSec }
    }

    private fun collectPdfsRecursive(
        dir: File,
        depth: Int,
        maxDepth: Int,
        thumbDir: File,
        out: MutableMap<String, PdfDocItem>
    ) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory && !f.name.startsWith(".")) {
                collectPdfsRecursive(f, depth + 1, maxDepth, thumbDir, out)
            } else if (f.isFile && f.name.endsWith(".pdf", true) && f.length() > 0) {
                addPdfFile(f, thumbDir, out)
            }
        }
    }

    private fun addPdfFile(f: File, thumbDir: File, out: MutableMap<String, PdfDocItem>) {
        val key = "${f.name.lowercase()}_${f.length()}"
        if (!out.containsKey(key)) {
            val thumb = File(thumbDir, "pdf_${key.hashCode()}.jpg")
            out[key] = PdfDocItem(
                id = "pdf_fs_${f.absolutePath.hashCode()}",
                name = f.name,
                folderName = f.parentFile?.name ?: "Storage",
                filePath = f.absolutePath,
                uri = null,
                sizeBytes = f.length(),
                modifiedSec = f.lastModified() / 1000L,
                thumbPath = if (thumb.exists()) thumb.absolutePath else null
            )
        }
    }

    fun renderPdfFirstPage(context: Context, pdf: PdfDocItem): Pair<Bitmap?, Int> {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = when {
                pdf.filePath != null -> ParcelFileDescriptor.open(File(pdf.filePath), ParcelFileDescriptor.MODE_READ_ONLY)
                pdf.uri != null -> context.contentResolver.openFileDescriptor(pdf.uri, "r")
                else -> null
            } ?: return null to 0

            renderer = PdfRenderer(pfd)
            val pages = renderer.pageCount
            if (pages <= 0) return null to 0

            val page = renderer.openPage(0)
            val scale = min(1.5f, 720f / max(1, page.width).toFloat())
            val w = (page.width * scale).toInt().coerceIn(200, 900)
            val h = (page.height * scale).toInt().coerceIn(200, 1200)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            // Save cached thumbnail
            val thumbDir = File(context.cacheDir, "pdf_thumbs").apply { if (!exists()) mkdirs() }
            val key = "${pdf.name.lowercase()}_${pdf.sizeBytes}"
            val thumbFile = File(thumbDir, "pdf_${key.hashCode()}.jpg")
            if (!thumbFile.exists()) {
                FileOutputStream(thumbFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
            }

            bmp to pages
        } catch (_: Exception) {
            null to 0
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // 4. LUMINA AI OMNISEARCH INDEXER (PHOTOS + SCREENSHOTS + VIDEOS + PDFS!)
    // =========================================================================
    fun loadOmniIndex(context: Context): Map<String, OmniIndexEntry> {
        val file = File(context.filesDir, OMNI_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val arr = JSONArray(file.readText())
            val map = HashMap<String, OmniIndexEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.getString("k")
                val kind = o.optString("t", "PHOTO")
                val labelsArr = o.optJSONArray("l") ?: JSONArray()
                val labels = List(labelsArr.length()) { idx -> labelsArr.getString(idx) }
                val colorsArr = o.optJSONArray("c") ?: JSONArray()
                val colors = List(colorsArr.length()) { idx -> colorsArr.getString(idx) }
                val ocr = o.optString("o", "")
                map[key] = OmniIndexEntry(key, kind, labels, ocr, colors)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveOmniIndex(context: Context, map: Map<String, OmniIndexEntry>) {
        try {
            val arr = JSONArray()
            map.values.forEach { e ->
                val obj = JSONObject()
                obj.put("k", e.key)
                obj.put("t", e.kind)
                obj.put("o", e.ocrText)
                val lArr = JSONArray()
                e.labels.forEach { lArr.put(it) }
                obj.put("l", lArr)
                val cArr = JSONArray()
                e.colorTags.forEach { cArr.put(it) }
                obj.put("c", cArr)
                arr.put(obj)
            }
            File(context.filesDir, OMNI_FILE).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    suspend fun buildOmniSearchIndex(
        context: Context,
        media: List<MediaItem>,
        pdfs: List<PdfDocItem>,
        maxMediaBatch: Int = 220,
        maxPdfBatch: Int = 80,
        onProgress: (done: Int, total: Int, currentLabel: String) -> Unit
    ): Map<String, OmniIndexEntry> = withContext(Dispatchers.IO) {
        val index = loadOmniIndex(context).toMutableMap()

        val unindexedMedia = media.filter { !index.containsKey("${if (it.isVideo) "V" else "P"}_${it.id}") }
            .take(maxMediaBatch)
        val unindexedPdfs = pdfs.filter { !index.containsKey(it.id) }.take(maxPdfBatch)
        val total = unindexedMedia.size + unindexedPdfs.size
        if (total == 0) return@withContext index

        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var done = 0

        try {
            // 1. Index Photos, Screenshots & Video Keyframes
            for (item in unindexedMedia) {
                val key = "${if (item.isVideo) "V" else "P"}_${item.id}"
                val bmp = if (item.isVideo) {
                    extractVideoKeyframe(context, item.uri)
                } else {
                    decodeDownsampledBitmap(context, item.uri, 512)
                }

                if (bmp != null) {
                    try {
                        val inputImg = InputImage.fromBitmap(bmp, 0)
                        val labels = try {
                            labeler.process(inputImg).awaitTask()
                                .filter { it.confidence >= 0.58f }
                                .map { it.text.lowercase() }
                                .take(8)
                        } catch (_: Exception) {
                            emptyList()
                        }

                        val ocr = try {
                            recognizer.process(inputImg).awaitTask().text
                                .replace("\n", " ")
                                .trim()
                                .take(500)
                        } catch (_: Exception) {
                            ""
                        }

                        val colors = extractDominantColorNames(bmp)
                        index[key] = OmniIndexEntry(
                            key = key,
                            kind = if (item.isVideo) "VIDEO" else "PHOTO",
                            labels = labels,
                            ocrText = ocr,
                            colorTags = colors
                        )
                    } finally {
                        bmp.recycle()
                    }
                } else {
                    index[key] = OmniIndexEntry(key, if (item.isVideo) "VIDEO" else "PHOTO", emptyList(), "", emptyList())
                }

                done++
                if (done % 3 == 0 || done == total) {
                    onProgress(done, total, item.displayName)
                }
            }

            // 2. Index PDF Page-1 Text & Preview Thumbnails
            for (pdf in unindexedPdfs) {
                val (bmp, _) = renderPdfFirstPage(context, pdf)
                val ocrText = if (bmp != null) {
                    try {
                        recognizer.process(InputImage.fromBitmap(bmp, 0)).awaitTask().text
                            .replace("\n", " ")
                            .trim()
                            .take(800)
                    } catch (_: Exception) {
                        ""
                    } finally {
                        bmp.recycle()
                    }
                } else ""

                index[pdf.id] = OmniIndexEntry(
                    key = pdf.id,
                    kind = "PDF",
                    labels = listOf("pdf", "document"),
                    ocrText = ocrText,
                    colorTags = emptyList()
                )
                done++
                onProgress(done, total, pdf.name)
            }

            saveOmniIndex(context, index)
        } finally {
            labeler.close()
            recognizer.close()
        }

        index
    }

    private fun extractVideoKeyframe(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val raw = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (raw != null && (raw.width > 512 || raw.height > 512)) {
                val scale = 512f / max(raw.width, raw.height).toFloat()
                val scaled = Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(1),
                    (raw.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                raw.recycle()
                scaled
            } else {
                raw
            }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractDominantColorNames(bmp: Bitmap): List<String> {
        val counts = mutableMapOf<String, Int>()
        val stepX = max(1, bmp.width / 16)
        val stepY = max(1, bmp.height / 16)
        val hsv = FloatArray(3)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                Color.colorToHSV(bmp.getPixel(x, y), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                val name = when {
                    v < 0.18f -> "black"
                    s < 0.14f && v > 0.82f -> "white"
                    s < 0.16f -> "gray"
                    h < 15f || h >= 342f -> "red"
                    h < 42f -> if (v < 0.48f) "brown" else "orange"
                    h < 68f -> "yellow"
                    h < 162f -> "green"
                    h < 252f -> "blue"
                    h < 298f -> "purple"
                    else -> "pink"
                }
                counts[name] = (counts[name] ?: 0) + 1
                x += stepX
            }
            y += stepY
        }
        return counts.entries.sortedByDescending { it.value }.take(2).map { it.key }
    }

    // =========================================================================
    // 5. OFFLINE AI PEOPLE & FACES CLUSTERING
    // =========================================================================
    private data class StoredCluster(
        val clusterId: String,
        val avatarPath: String,
        val centroid: FloatArray,
        val mediaIds: MutableSet<Long>,
        var sampleCount: Int
    )

    fun loadCachedClusters(context: Context): List<FaceCluster> {
        val prefs = context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CLUSTERS, null) ?: return emptyList()
        val namesMap = loadCustomNames(context)
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<FaceCluster>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val avatar = obj.getString("avatar")
                val idsJson = obj.getJSONArray("mediaIds")
                val ids = List(idsJson.length()) { idx -> idsJson.getLong(idx) }
                if (ids.isNotEmpty() && File(avatar).exists()) {
                    val label = namesMap[id] ?: "Person ${i + 1}"
                    list.add(FaceCluster(id, label, avatar, ids))
                }
            }
            list.sortedByDescending { it.mediaIds.size }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun renamePerson(context: Context, clusterId: String, newName: String) {
        val names = loadCustomNames(context).toMutableMap()
        val clean = newName.trim()
        if (clean.isEmpty()) names.remove(clusterId) else names[clusterId] = clean
        val obj = JSONObject()
        names.forEach { (k, v) -> obj.put(k, v) }
        context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_NAMES, obj.toString()).apply()
    }

    fun resetFaceCache(context: Context) {
        context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CLUSTERS)
            .remove(KEY_SCANNED_IDS)
            .apply()
        File(context.filesDir, "lumina_faces").deleteRecursively()
    }

    private fun loadCustomNames(context: Context): Map<String, String> {
        val raw = context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_NAMES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k, "")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun scanAndClusterFaces(
        context: Context,
        photos: List<MediaItem>,
        maxScanBatch: Int = 300,
        onProgress: (scanned: Int, total: Int, peopleFound: Int) -> Unit
    ): List<FaceCluster> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
        val scannedSet = (prefs.getStringSet(KEY_SCANNED_IDS, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toMutableSet()

        val existingClusters = loadStoredClusters(context).toMutableList()
        val faceDir = File(context.filesDir, "lumina_faces").apply { if (!exists()) mkdirs() }

        val toScan = photos
            .filter { !it.isVideo && !it.isScreenshot && !scannedSet.contains(it.id) }
            .take(maxScanBatch)

        if (toScan.isEmpty()) return@withContext loadCachedClusters(context)

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()

        val detector = FaceDetection.getClient(options)

        try {
            toScan.forEachIndexed { idx, photo ->
                val bmp = decodeDownsampledBitmap(context, photo.uri, 640)
                if (bmp != null) {
                    try {
                        val faces = detector.process(InputImage.fromBitmap(bmp, 0)).awaitTask()
                        for (face in faces) {
                            val box = face.boundingBox
                            if (box.width() < 28 || box.height() < 28) continue
                            val embedding = computeFaceSignature(bmp, face) ?: continue
                            val bestCluster = findBestMatchingCluster(existingClusters, embedding)
                            if (bestCluster != null) {
                                bestCluster.mediaIds.add(photo.id)
                                updateCentroid(bestCluster, embedding)
                            } else {
                                val newId = "p_${System.currentTimeMillis()}_${existingClusters.size}"
                                val avatarFile = File(faceDir, "$newId.jpg")
                                saveFaceAvatar(bmp, box, avatarFile)
                                existingClusters.add(
                                    StoredCluster(
                                        clusterId = newId,
                                        avatarPath = avatarFile.absolutePath,
                                        centroid = embedding,
                                        mediaIds = mutableSetOf(photo.id),
                                        sampleCount = 1
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        bmp.recycle()
                    }
                }
                scannedSet.add(photo.id)
                if (idx % 3 == 0 || idx == toScan.lastIndex) {
                    onProgress(idx + 1, toScan.size, existingClusters.size)
                }
            }
            saveStoredClusters(context, existingClusters, scannedSet)
        } finally {
            detector.close()
        }

        loadCachedClusters(context)
    }

    fun decodeDownsampledBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeFaceSignature(bmp: Bitmap, face: Face): FloatArray? {
        val box = face.boundingBox
        val left = box.left.coerceIn(0, bmp.width - 1)
        val top = box.top.coerceIn(0, bmp.height - 1)
        val right = box.right.coerceIn(left + 1, bmp.width)
        val bottom = box.bottom.coerceIn(top + 1, bmp.height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW < 16 || cropH < 16) return null

        val insetX = (cropW * 0.12f).toInt()
        val insetY = (cropH * 0.10f).toInt()
        val cLeft = (left + insetX).coerceIn(0, bmp.width - 2)
        val cTop = (top + insetY).coerceIn(0, bmp.height - 2)
        val cW = (cropW - insetX * 2).coerceAtLeast(8).coerceAtMost(bmp.width - cLeft)
        val cH = (cropH - insetY * 2).coerceAtLeast(8).coerceAtMost(bmp.height - cTop)

        val cropped = Bitmap.createBitmap(bmp, cLeft, cTop, cW, cH)
        val scaled = Bitmap.createScaledBitmap(cropped, 12, 12, true)
        cropped.recycle()

        val vec = FloatArray(154)
        var sum = 0f
        for (y in 0 until 12) {
            for (x in 0 until 12) {
                val px = scaled.getPixel(x, y)
                val lum = 0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)
                vec[y * 12 + x] = lum
                sum += lum
            }
        }
        scaled.recycle()

        val mean = sum / 144f
        var sqSum = 0f
        for (i in 0 until 144) {
            val v = vec[i] - mean
            vec[i] = v
            sqSum += v * v
        }
        val norm = sqrt(sqSum).coerceAtLeast(1e-5f)
        for (i in 0 until 144) vec[i] = (vec[i] / norm) * 0.78f

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthB = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

        if (leftEye != null && rightEye != null) {
            val eyeDist = hypot((rightEye.x - leftEye.x).toDouble(), (rightEye.y - leftEye.y).toDouble())
                .toFloat().coerceAtLeast(1f)
            val eyeMidX = (leftEye.x + rightEye.x) / 2f
            val eyeMidY = (leftEye.y + rightEye.y) / 2f
            val geo = FloatArray(10)
            geo[0] = cropW.toFloat() / cropH.toFloat()
            if (nose != null) {
                geo[1] = hypot((nose.x - eyeMidX).toDouble(), (nose.y - eyeMidY).toDouble()).toFloat() / eyeDist
                geo[2] = hypot((nose.x - leftEye.x).toDouble(), (nose.y - leftEye.y).toDouble()).toFloat() / eyeDist
                geo[3] = hypot((nose.x - rightEye.x).toDouble(), (nose.y - rightEye.y).toDouble()).toFloat() / eyeDist
            }
            if (mouthL != null && mouthR != null) {
                geo[4] = hypot((mouthR.x - mouthL.x).toDouble(), (mouthR.y - mouthL.y).toDouble()).toFloat() / eyeDist
                val mMidX = (mouthL.x + mouthR.x) / 2f
                val mMidY = (mouthL.y + mouthR.y) / 2f
                geo[5] = hypot((mMidX - eyeMidX).toDouble(), (mMidY - eyeMidY).toDouble()).toFloat() / eyeDist
                if (nose != null) {
                    geo[6] = hypot((mMidX - nose.x).toDouble(), (mMidY - nose.y).toDouble()).toFloat() / eyeDist
                }
            }
            if (mouthB != null) {
                geo[7] = hypot((mouthB.x - eyeMidX).toDouble(), (mouthB.y - eyeMidY).toDouble()).toFloat() / eyeDist
            }
            geo[8] = eyeDist / cropW.toFloat()
            geo[9] = face.headEulerAngleY / 90f

            var gSq = 0f
            for (g in geo) gSq += g * g
            val gNorm = sqrt(gSq).coerceAtLeast(1e-5f)
            for (i in 0 until 10) vec[144 + i] = (geo[i] / gNorm) * 0.62f
        }

        var totalSq = 0f
        for (v in vec) totalSq += v * v
        val totalNorm = sqrt(totalSq).coerceAtLeast(1e-5f)
        for (i in vec.indices) vec[i] /= totalNorm
        return vec
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in 0 until min(a.size, b.size)) dot += a[i] * b[i]
        return dot
    }

    private fun findBestMatchingCluster(clusters: List<StoredCluster>, embedding: FloatArray): StoredCluster? {
        var best: StoredCluster? = null
        var bestScore = 0.78f
        for (c in clusters) {
            val score = cosineSimilarity(c.centroid, embedding)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    private fun updateCentroid(cluster: StoredCluster, newVec: FloatArray) {
        val w = cluster.sampleCount.toFloat()
        var sq = 0f
        for (i in cluster.centroid.indices) {
            val updated = (cluster.centroid[i] * w + newVec[i]) / (w + 1f)
            cluster.centroid[i] = updated
            sq += updated * updated
        }
        val norm = sqrt(sq).coerceAtLeast(1e-5f)
        for (i in cluster.centroid.indices) cluster.centroid[i] /= norm
        cluster.sampleCount += 1
    }

    private fun saveFaceAvatar(bmp: Bitmap, box: Rect, dest: File) {
        try {
            val padX = (box.width() * 0.25f).toInt()
            val padY = (box.height() * 0.28f).toInt()
            val l = (box.left - padX).coerceAtLeast(0)
            val t = (box.top - padY).coerceAtLeast(0)
            val r = (box.right + padX).coerceAtMost(bmp.width)
            val b = (box.bottom + padY).coerceAtMost(bmp.height)
            val crop = Bitmap.createBitmap(bmp, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
            val avatar = Bitmap.createScaledBitmap(crop, 180, 180, true)
            crop.recycle()
            FileOutputStream(dest).use { out -> avatar.compress(Bitmap.CompressFormat.JPEG, 86, out) }
            avatar.recycle()
        } catch (_: Exception) {
        }
    }

    private fun loadStoredClusters(context: Context): List<StoredCluster> {
        val raw = context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
            .getString(KEY_CLUSTERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                val cArr = obj.getJSONArray("centroid")
                val vec = FloatArray(cArr.length()) { idx -> cArr.getDouble(idx).toFloat() }
                val mArr = obj.getJSONArray("mediaIds")
                val mSet = mutableSetOf<Long>()
                for (j in 0 until mArr.length()) mSet.add(mArr.getLong(j))
                StoredCluster(
                    clusterId = obj.getString("id"),
                    avatarPath = obj.getString("avatar"),
                    centroid = vec,
                    mediaIds = mSet,
                    sampleCount = obj.optInt("count", mSet.size.coerceAtLeast(1))
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveStoredClusters(context: Context, clusters: List<StoredCluster>, scannedIds: Set<Long>) {
        val arr = JSONArray()
        clusters.forEach { c ->
            val obj = JSONObject()
            obj.put("id", c.clusterId)
            obj.put("avatar", c.avatarPath)
            obj.put("count", c.sampleCount)
            val vecArr = JSONArray()
            c.centroid.forEach { vecArr.put(it.toDouble()) }
            obj.put("centroid", vecArr)
            val idArr = JSONArray()
            c.mediaIds.forEach { idArr.put(it) }
            obj.put("mediaIds", idArr)
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_AI, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLUSTERS, arr.toString())
            .putStringSet(KEY_SCANNED_IDS, scannedIds.map { it.toString() }.toSet())
            .apply()
    }

    // =========================================================================
    // 6. LOSSLESS HARDWARE VIDEO TRIMMER (MediaExtractor + MediaMuxer)
    // =========================================================================
    suspend fun trimVideoLossless(
        context: Context,
        sourceUri: Uri,
        startMs: Long,
        endMs: Long
    ): String? = withContext(Dispatchers.IO) {
        val tempOut = File(context.cacheDir, "lumina_trim_${System.currentTimeMillis()}.mp4")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r") ?: return@withContext null
            pfd.use { extractor.setDataSource(it.fileDescriptor) }

            val rotation = try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, sourceUri)
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                retriever.release()
                rot
            } catch (_: Exception) { 0 }

            muxer = MediaMuxer(tempOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) muxer.setOrientationHint(rotation)

            val trackIndexMap = mutableMapOf<Int, Int>()
            var maxBufferSize = 1024 * 1024

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackIndexMap[i] = muxer.addTrack(format)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val sz = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (sz > maxBufferSize) maxBufferSize = sz
                    }
                }
            }
            if (trackIndexMap.isEmpty()) return@withContext null

            val startUs = max(0L, startMs * 1000L)
            val endUs = endMs * 1000L

            muxer.start()
            if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var baseTimeUs = -1L

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (endUs > 0L && sampleTimeUs > endUs) break
                val dstTrack = trackIndexMap[extractor.sampleTrackIndex]
                if (dstTrack != null) {
                    if (baseTimeUs < 0L) baseTimeUs = sampleTimeUs
                    bufferInfo.presentationTimeUs = max(0L, sampleTimeUs - baseTimeUs)
                    bufferInfo.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else 0
                    muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                }
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()

            val fileName = "Lumina_Trim_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/LuminaTrimmed")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            resolver.openOutputStream(outUri)?.use { out -> tempOut.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            }
            fileName
        } catch (_: Exception) {
            null
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            tempOut.delete()
        }
    }
}
