package com.lumina.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.palette.graphics.Palette
import com.lumina.gallery.model.ColorSwatchInfo
import com.lumina.gallery.model.DuplicateGroup
import com.lumina.gallery.model.ExifReport
import com.lumina.gallery.model.MediaFolder
import com.lumina.gallery.model.MediaItem
import com.lumina.gallery.model.PdfDocItem
import com.lumina.gallery.model.SortField
import com.lumina.gallery.model.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object MediaRepository {

    private const val PREF_NAME = "lumina_prefs_v3"
    private const val KEY_FAVORITES = "favorite_ids"
    private const val KEY_HIDDEN_FOLDERS = "hidden_folders"
    private const val KEY_HIDDEN_FILES = "hidden_files"
    private const val KEY_PINNED_FOLDERS = "pinned_folders"
    private const val KEY_CUSTOM_FOLDERS = "custom_folders"
    private const val KEY_VAULT_PIN = "vault_pin_hash"
    private const val KEY_VAULT_META = "vault_entries"
    private const val VAULT_XOR_KEY: Byte = 0x5A

    suspend fun loadAllMedia(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        val result = ArrayList<MediaItem>(512)
        queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, false, result)
        queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, false, result)
        result.sortByDescending { it.effectiveTimeSec }
        result
    }

    suspend fun loadTrashedMedia(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        val result = ArrayList<MediaItem>(64)
        queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, true, result)
        queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, true, result)
        result.sortByDescending { it.dateModifiedSec }
        result
    }

    private fun queryCollection(
        context: Context,
        collection: Uri,
        isVideo: Boolean,
        trashedOnly: Boolean,
        out: MutableList<MediaItem>
    ) {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            "bucket_display_name",
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE
        )
        if (isVideo) cols.add("duration")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols.add(MediaStore.MediaColumns.RELATIVE_PATH)
            cols.add(MediaStore.MediaColumns.DATE_TAKEN)
        }

        try {
            val cursor = if (trashedOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val args = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
                    )
                    putInt(
                        ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    )
                }
                context.contentResolver.query(collection, cols.toTypedArray(), args, null)
            } else {
                val selection = "${MediaStore.MediaColumns.SIZE} > 0"
                val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                context.contentResolver.query(collection, cols.toTypedArray(), selection, null, sortOrder)
            }

            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val bucketIdx = c.getColumnIndex("bucket_display_name")
                val addIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val wIdx = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val hIdx = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val durIdx = if (isVideo) c.getColumnIndex("duration") else -1
                val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else -1
                val takenIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                } else -1

                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "Media" else "Media"
                    val bucket = if (bucketIdx >= 0) c.getString(bucketIdx) ?: "Other" else "Other"
                    val dateAdded = if (addIdx >= 0) c.getLong(addIdx) else 0L
                    val dateMod = if (modIdx >= 0) c.getLong(modIdx) else dateAdded
                    val dateTaken = if (takenIdx >= 0) c.getLong(takenIdx) else dateAdded * 1000L
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val w = if (wIdx >= 0) c.getInt(wIdx) else 0
                    val h = if (hIdx >= 0) c.getInt(hIdx) else 0
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else ""
                    val dur = if (durIdx >= 0) c.getLong(durIdx) else 0L
                    val rel = if (relIdx >= 0) c.getString(relIdx) ?: "" else ""

                    out.add(
                        MediaItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            bucketName = bucket,
                            dateAddedSec = dateAdded,
                            dateModifiedSec = dateMod,
                            dateTakenMs = dateTaken,
                            sizeBytes = size,
                            width = w,
                            height = h,
                            mimeType = mime,
                            isVideo = isVideo,
                            durationMs = dur,
                            relativePath = rel,
                            isTrashed = trashedOnly
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    fun openPdfExternally(context: Context, pdf: PdfDocItem): Boolean {
        return try {
            val targetUri = when {
                pdf.filePath != null -> FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(pdf.filePath)
                )
                pdf.uri != null -> pdf.uri
                else -> return false
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(targetUri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open PDF"))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun buildFolders(
        items: List<MediaItem>,
        trashedItems: List<MediaItem>,
        pinnedFolders: Set<String>,
        hiddenFolders: Set<String>,
        showHidden: Boolean,
        sortField: SortField,
        ascending: Boolean
    ): List<MediaFolder> {
        val grouped = items.groupBy { it.bucketName }.mapNotNull { (name, list) ->
            val hidden = hiddenFolders.contains(name)
            if (hidden && !showHidden) return@mapNotNull null
            val latest = list.maxOfOrNull { it.effectiveTimeSec } ?: 0L
            MediaFolder(
                name = name,
                coverUri = list.first().uri,
                count = list.size,
                totalBytes = list.sumOf { it.sizeBytes },
                latestModifiedSec = latest,
                items = list,
                isPinned = pinnedFolders.contains(name),
                isHidden = hidden,
                isRecycleBin = false
            )
        }

        val comparator: Comparator<MediaFolder> = when (sortField) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.PATH -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.items.firstOrNull()?.relativePath ?: it.name }
            SortField.SIZE -> compareBy { it.totalBytes }
            SortField.LAST_MODIFIED -> compareBy { it.latestModifiedSec }
            SortField.DATE_TAKEN -> compareBy { it.items.maxOfOrNull { m -> m.dateTakenMs } ?: 0L }
            SortField.ITEM_COUNT -> compareBy { it.count }
            SortField.RANDOM -> compareBy { it.name.hashCode() }
        }

        val sortedNormal = if (sortField == SortField.RANDOM) {
            grouped.shuffled()
        } else {
            val base = grouped.sortedWith(if (ascending) comparator else comparator.reversed())
            base.sortedByDescending { it.isPinned }
        }

        val finalList = mutableListOf<MediaFolder>()
        if (trashedItems.isNotEmpty()) {
            finalList.add(
                MediaFolder(
                    name = "Recycle Bin",
                    coverUri = trashedItems.first().uri,
                    count = trashedItems.size,
                    totalBytes = trashedItems.sumOf { it.sizeBytes },
                    latestModifiedSec = trashedItems.maxOfOrNull { it.dateModifiedSec } ?: 0L,
                    items = trashedItems,
                    isPinned = true,
                    isHidden = false,
                    isRecycleBin = true
                )
            )
        }
        finalList.addAll(sortedNormal)
        return finalList
    }

    fun sortMediaItems(items: List<MediaItem>, sortField: SortField, ascending: Boolean): List<MediaItem> {
        if (sortField == SortField.RANDOM) return items.shuffled()
        val cmp: Comparator<MediaItem> = when (sortField) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            SortField.PATH -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath + it.displayName }
            SortField.SIZE -> compareBy { it.sizeBytes }
            SortField.LAST_MODIFIED -> compareBy { it.effectiveTimeSec }
            SortField.DATE_TAKEN -> compareBy { it.dateTakenMs }
            SortField.ITEM_COUNT -> compareBy { it.effectiveTimeSec }
            SortField.RANDOM -> compareBy { it.id }
        }
        return items.sortedWith(if (ascending) cmp else cmp.reversed())
    }

    fun createNewFolder(context: Context, folderName: String): Boolean {
        val clean = folderName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "")
        if (clean.isEmpty()) return false
        return try {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                clean
            )
            if (!dir.exists()) dir.mkdirs()
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val set = (prefs.getStringSet(KEY_CUSTOM_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
            set.add(clean)
            prefs.edit().putStringSet(KEY_CUSTOM_FOLDERS, set).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getPinnedFolders(context: Context): Set<String> =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED_FOLDERS, emptySet()) ?: emptySet()

    fun togglePinFolder(context: Context, folderName: String): Set<String> {
        val set = getPinnedFolders(context).toMutableSet()
        if (!set.add(folderName)) set.remove(folderName)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PINNED_FOLDERS, set).apply()
        return set
    }

    fun getHiddenFolders(context: Context): Set<String> =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_FOLDERS, emptySet()) ?: emptySet()

    fun toggleHideFolder(context: Context, folderName: String): Set<String> {
        val set = getHiddenFolders(context).toMutableSet()
        if (!set.add(folderName)) set.remove(folderName)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HIDDEN_FOLDERS, set).apply()
        return set
    }

    fun getHiddenFiles(context: Context): Set<Long> =
        (context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_FILES, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()

    fun toggleHideFile(context: Context, id: Long): Set<Long> {
        val set = getHiddenFiles(context).toMutableSet()
        if (!set.add(id)) set.remove(id)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HIDDEN_FILES, set.map { it.toString() }.toSet()).apply()
        return set
    }

    suspend fun findDuplicates(context: Context, items: List<MediaItem>): List<DuplicateGroup> =
        withContext(Dispatchers.IO) {
            val sizeCandidates = items
                .filter { it.sizeBytes > 10_000L }
                .groupBy { it.sizeBytes }
                .filter { it.value.size > 1 }

            val duplicateGroups = mutableListOf<DuplicateGroup>()
            for ((size, group) in sizeCandidates) {
                val byHash = mutableMapOf<String, MutableList<MediaItem>>()
                for (item in group) {
                    val fp = fastFingerprint(context, item.uri, size) ?: continue
                    byHash.getOrPut(fp) { mutableListOf() }.add(item)
                }
                for ((fp, matches) in byHash) {
                    if (matches.size > 1) {
                        duplicateGroups.add(
                            DuplicateGroup(
                                key = fp,
                                sizeBytes = size,
                                items = matches.sortedByDescending { it.effectiveTimeSec }
                            )
                        )
                    }
                }
            }
            duplicateGroups.sortedByDescending { it.reclaimableBytes }
        }

    private fun fastFingerprint(context: Context, uri: Uri, size: Long): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(size.toString().toByteArray())
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(16384)
                val read = input.read(buf)
                if (read > 0) md.update(buf, 0, read)
            }
            md.digest().joinToString("") { "%02x".format(it) }.take(24)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun inspectMedia(context: Context, item: MediaItem): ExifReport =
        withContext(Dispatchers.IO) {
            var camera = "Unknown"
            var dateTaken = formatDateFull(item.effectiveTimeSec)
            var iso = "-"
            var aperture = "-"
            var shutter = "-"
            var location = "No GPS tag"
            val paletteList = mutableListOf<ColorSwatchInfo>()

            if (!item.isVideo) {
                try {
                    context.contentResolver.openInputStream(item.uri)?.use { stream ->
                        val exif = ExifInterface(stream)
                        val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
                        val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
                        if (make.isNotEmpty() || model.isNotEmpty()) {
                            camera = if (model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
                        }
                        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                            if (it.isNotBlank()) dateTaken = it
                        }
                        exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let {
                            if (it.isNotBlank()) iso = "ISO $it"
                        }
                        exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let {
                            if (it.isNotBlank()) aperture = "f/$it"
                        }
                        exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { exp ->
                            shutter = if (exp > 0 && exp < 1.0) "1/${(1.0 / exp).roundToInt()}s" else "${exp}s"
                        }
                        val latLong = exif.latLong
                        if (latLong != null) {
                            location = String.format(Locale.US, "%.4f, %.4f", latLong[0], latLong[1])
                        }
                    }
                } catch (_: Exception) {
                }

                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 6 }
                    val bmp = context.contentResolver.openInputStream(item.uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    if (bmp != null) {
                        val pal = Palette.from(bmp).maximumColorCount(8).generate()
                        val pairs = listOf(
                            "Dominant" to pal.dominantSwatch,
                            "Vibrant" to pal.vibrantSwatch,
                            "Dark" to pal.darkVibrantSwatch,
                            "Light" to pal.lightVibrantSwatch,
                            "Muted" to pal.mutedSwatch
                        )
                        val seen = mutableSetOf<String>()
                        for ((label, sw) in pairs) {
                            if (sw != null) {
                                val hex = String.format("#%06X", 0xFFFFFF and sw.rgb)
                                if (seen.add(hex)) paletteList.add(ColorSwatchInfo(hex, sw.rgb, label))
                            }
                        }
                        bmp.recycle()
                    }
                } catch (_: Exception) {
                }
            }

            val resText = if (item.width > 0 && item.height > 0) "${item.width} x ${item.height}" else "Unknown"
            ExifReport(
                camera = camera,
                resolution = resText,
                fileSize = formatSize(item.sizeBytes),
                dateTaken = dateTaken,
                iso = iso,
                aperture = aperture,
                shutterSpeed = shutter,
                location = location,
                path = "${item.relativePath}${item.displayName}".ifBlank { item.bucketName },
                palette = paletteList
            )
        }

    suspend fun buildPrivacyScrubShareIntent(context: Context, item: MediaItem): Intent? =
        withContext(Dispatchers.IO) {
            try {
                val shareDir = File(context.cacheDir, "clean_share").apply {
                    if (exists()) listFiles()?.forEach { it.delete() }
                    mkdirs()
                }
                val safeExt = if (item.isVideo) item.extension.lowercase().ifBlank { "mp4" } else "jpg"
                val cleanFile = File(shareDir, "lumina_clean_${System.currentTimeMillis()}.$safeExt")

                if (item.isVideo) {
                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                        FileOutputStream(cleanFile).use { output -> input.copyTo(output) }
                    }
                } else {
                    val bmp = context.contentResolver.openInputStream(item.uri)?.use {
                        BitmapFactory.decodeStream(it)
                    } ?: return@withContext null
                    FileOutputStream(cleanFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    bmp.recycle()
                }

                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cleanFile
                )

                Intent(Intent.ACTION_SEND).apply {
                    type = if (item.isVideo) "video/*" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) {
                null
            }
        }

    fun getFavorites(context: Context): Set<Long> =
        (context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()

    fun toggleFavorite(context: Context, id: Long): Set<Long> {
        val current = getFavorites(context).toMutableSet()
        if (!current.add(id)) current.remove(id)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_FAVORITES, current.map { it.toString() }.toSet()).apply()
        return current
    }

    fun hasVaultPin(context: Context): Boolean =
        !context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VAULT_PIN, null).isNullOrBlank()

    fun setVaultPin(context: Context, pin: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VAULT_PIN, sha256(pin)).apply()
    }

    fun verifyVaultPin(context: Context, pin: String): Boolean {
        val saved = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VAULT_PIN, null) ?: return false
        return saved == sha256(pin)
    }

    private fun vaultDir(context: Context): File =
        File(context.filesDir, "lumina_vault").apply {
            if (!exists()) mkdirs()
            val nomedia = File(this, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
        }

    suspend fun lockIntoVault(context: Context, item: MediaItem): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val safeName = "v_${System.currentTimeMillis()}_${item.id}.lmv"
                val dest = File(vaultDir(context), safeName)
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            for (i in 0 until bytesRead) {
                                buffer[i] = (buffer[i].toInt() xor VAULT_XOR_KEY.toInt()).toByte()
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                } ?: return@withContext false

                val entries = getVaultEntries(context).toMutableList()
                entries.add(
                    0,
                    VaultEntry(
                        fileName = safeName,
                        originalName = item.displayName,
                        isVideo = item.isVideo,
                        sizeBytes = dest.length(),
                        savedAtMs = System.currentTimeMillis()
                    )
                )
                saveVaultEntries(context, entries)
                true
            } catch (_: Exception) {
                false
            }
        }

    suspend fun decodeVaultPreviewFile(context: Context, entry: VaultEntry): File? =
        withContext(Dispatchers.IO) {
            try {
                val src = File(vaultDir(context), entry.fileName)
                if (!src.exists()) return@withContext null
                val previewDir = File(context.cacheDir, "vault_preview").apply { mkdirs() }
                val ext = entry.originalName.substringAfterLast('.', if (entry.isVideo) "mp4" else "jpg")
                val outFile = File(previewDir, "${entry.fileName}.$ext")
                if (outFile.exists() && outFile.length() == src.length()) return@withContext outFile

                src.inputStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            for (i in 0 until bytesRead) {
                                buffer[i] = (buffer[i].toInt() xor VAULT_XOR_KEY.toInt()).toByte()
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                outFile
            } catch (_: Exception) {
                null
            }
        }

    fun clearVaultPreviewCache(context: Context) {
        File(context.cacheDir, "vault_preview").deleteRecursively()
    }

    suspend fun restoreFromVault(context: Context, entry: VaultEntry): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val decrypted = decodeVaultPreviewFile(context, entry) ?: return@withContext false
                val collection = if (entry.isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val mime = if (entry.isVideo) "video/mp4" else "image/jpeg"
                val relDir = if (entry.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, entry.originalName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$relDir/LuminaRestored")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val resolver = context.contentResolver
                val outUri = resolver.insert(collection, values) ?: return@withContext false
                resolver.openOutputStream(outUri)?.use { out ->
                    decrypted.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(outUri, values, null, null)
                }

                deleteVaultEntry(context, entry)
                true
            } catch (_: Exception) {
                false
            }
        }

    fun deleteVaultEntry(context: Context, entry: VaultEntry) {
        File(vaultDir(context), entry.fileName).delete()
        val remaining = getVaultEntries(context).filterNot { it.fileName == entry.fileName }
        saveVaultEntries(context, remaining)
    }

    fun getVaultEntries(context: Context): List<VaultEntry> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VAULT_META, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                VaultEntry(
                    fileName = o.getString("fileName"),
                    originalName = o.getString("originalName"),
                    isVideo = o.optBoolean("isVideo", false),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    savedAtMs = o.optLong("savedAtMs", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveVaultEntries(context: Context, entries: List<VaultEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("fileName", e.fileName)
                    put("originalName", e.originalName)
                    put("isVideo", e.isVideo)
                    put("sizeBytes", e.sizeBytes)
                    put("savedAtMs", e.savedAtMs)
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VAULT_META, arr.toString()).apply()
    }

    private fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    fun formatDateHeader(sec: Long): String {
        if (sec <= 0L) return "Other"
        return SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(sec * 1000L))
    }

    fun formatDateFull(sec: Long): String {
        if (sec <= 0L) return "Unknown"
        return SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(sec * 1000L))
    }
}
