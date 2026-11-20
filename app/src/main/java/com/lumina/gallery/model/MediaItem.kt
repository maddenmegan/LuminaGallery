package com.lumina.gallery.model

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val bucketName: String,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val dateTakenMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val isVideo: Boolean,
    val durationMs: Long = 0L,
    val relativePath: String = "",
    val isTrashed: Boolean = false
) {
    val isScreenshot: Boolean
        get() = bucketName.contains("screenshot", ignoreCase = true) ||
            displayName.contains("screenshot", ignoreCase = true)

    val isGif: Boolean
        get() = mimeType.contains("gif", ignoreCase = true) ||
            displayName.endsWith(".gif", ignoreCase = true)

    val extension: String
        get() = displayName.substringAfterLast('.', "").uppercase()

    val effectiveTimeSec: Long
        get() = maxOf(dateModifiedSec, dateAddedSec, dateTakenMs / 1000L)
}

data class PdfDocItem(
    val id: String,
    val name: String,
    val folderName: String,
    val filePath: String?,
    val uri: Uri?,
    val sizeBytes: Long,
    val modifiedSec: Long,
    val pageCount: Int = 0,
    val thumbPath: String? = null
)

data class MediaFolder(
    val name: String,
    val coverUri: Uri,
    val count: Int,
    val totalBytes: Long,
    val latestModifiedSec: Long,
    val items: List<MediaItem>,
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    val isRecycleBin: Boolean = false
)

enum class SortField(val label: String) {
    NAME("Name"),
    PATH("Path"),
    SIZE("Size"),
    LAST_MODIFIED("Last modified"),
    DATE_TAKEN("Date taken"),
    ITEM_COUNT("Item count"),
    RANDOM("Random")
}

enum class MediaTypeFilter(val label: String) {
    ALL("All Media"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    GIFS("GIFs"),
    SCREENSHOTS("Screenshots"),
    FAVORITES("Favorites")
}

data class DuplicateGroup(
    val key: String,
    val sizeBytes: Long,
    val items: List<MediaItem>
) {
    val reclaimableBytes: Long
        get() = if (items.size > 1) sizeBytes * (items.size - 1) else 0L
}

data class ColorSwatchInfo(
    val hex: String,
    val colorInt: Int,
    val label: String
)

data class ExifReport(
    val camera: String,
    val resolution: String,
    val fileSize: String,
    val dateTaken: String,
    val iso: String,
    val aperture: String,
    val shutterSpeed: String,
    val location: String,
    val path: String,
    val palette: List<ColorSwatchInfo>
)

data class VaultEntry(
    val fileName: String,
    val originalName: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val savedAtMs: Long
)

data class FaceCluster(
    val clusterId: String,
    val displayName: String,
    val avatarPath: String,
    val mediaIds: List<Long>
)

data class OcrResultData(
    val fullText: String,
    val lines: List<String>,
    val urls: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val sourceEngine: String = "Offline ML Kit"
)

data class QrItemData(
    val rawValue: String,
    val displayValue: String,
    val typeLabel: String,
    val url: String? = null,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val phone: String? = null,
    val email: String? = null
)

data class OmniIndexEntry(
    val key: String,
    val kind: String, // "PHOTO", "VIDEO", "PDF"
    val labels: List<String>,
    val ocrText: String,
    val colorTags: List<String>
)

enum class StudioFilter(val label: String, val matrix: FloatArray) {
    ORIGINAL(
        "Original",
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    ),
    SOFT_GLOW(
        "Soft Glow",
        floatArrayOf(
            1.08f, 0.03f, 0.02f, 0f, 12f,
            0.02f, 1.05f, 0.02f, 0f, 10f,
            0.02f, 0.01f, 1.03f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f
        )
    ),
    WARM_CREAM(
        "Warm Cream",
        floatArrayOf(
            1.14f, 0.04f, 0f, 0f, 10f,
            0.02f, 1.07f, 0f, 0f, 6f,
            0f, 0f, 0.90f, 0f, 4f,
            0f, 0f, 0f, 1f, 0f
        )
    ),
    GOLDEN(
        "Golden Hour",
        floatArrayOf(
            1.18f, 0.06f, 0f, 0f, 12f,
            0f, 1.06f, 0f, 0f, 4f,
            0f, 0f, 0.84f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f
        )
    ),
    VIVID(
        "Vivid",
        floatArrayOf(
            1.22f, -0.08f, -0.08f, 0f, 8f,
            -0.08f, 1.22f, -0.08f, 0f, 8f,
            -0.08f, -0.08f, 1.22f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f
        )
    ),
    CINEMA(
        "Cinema",
        floatArrayOf(
            1.10f, 0.02f, -0.05f, 0f, 4f,
            0f, 1.04f, 0.02f, 0f, 2f,
            -0.06f, 0.04f, 1.12f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f
        )
    ),
    FILM_35MM(
        "35mm Film",
        floatArrayOf(
            1.06f, 0.04f, -0.02f, 0f, 8f,
            0.01f, 1.02f, 0.02f, 0f, 6f,
            -0.02f, 0.04f, 0.94f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f
        )
    ),
    COOL_MATTE(
        "Cool Matte",
        floatArrayOf(
            0.92f, 0f, 0.05f, 0f, 10f,
            0f, 0.98f, 0.04f, 0f, 10f,
            0.04f, 0.06f, 1.14f, 0f, 14f,
            0f, 0f, 0f, 1f, 0f
        )
    ),
    NOIR(
        "Noir B&W",
        floatArrayOf(
            0.35f, 0.65f, 0.12f, 0f, -10f,
            0.35f, 0.65f, 0.12f, 0f, -10f,
            0.35f, 0.65f, 0.12f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}
