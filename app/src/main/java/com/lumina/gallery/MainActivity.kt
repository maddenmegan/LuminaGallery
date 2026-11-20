package com.lumina.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.gallery.data.AiEngine
import com.lumina.gallery.data.MediaRepository
import com.lumina.gallery.model.MediaFolder
import com.lumina.gallery.model.MediaItem
import com.lumina.gallery.model.MediaTypeFilter
import com.lumina.gallery.model.SortField
import com.lumina.gallery.ui.CleanHubScreen
import com.lumina.gallery.ui.LiveQrScannerScreen
import com.lumina.gallery.ui.LuminaAmber
import com.lumina.gallery.ui.LuminaBlack
import com.lumina.gallery.ui.LuminaCard
import com.lumina.gallery.ui.LuminaCyan
import com.lumina.gallery.ui.LuminaGreen
import com.lumina.gallery.ui.LuminaMuted
import com.lumina.gallery.ui.LuminaPill
import com.lumina.gallery.ui.LuminaRed
import com.lumina.gallery.ui.LuminaSurface
import com.lumina.gallery.ui.LuminaTheme
import com.lumina.gallery.ui.MediaViewerScreen
import com.lumina.gallery.ui.OmniSearchScreen
import com.lumina.gallery.ui.PeopleScreen
import com.lumina.gallery.ui.VaultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LuminaTheme {
                LuminaRootApp()
            }
        }
    }
}

private fun requiredMediaPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
fun LuminaRootApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            requiredMediaPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermission = grants.values.any { it } ||
            requiredMediaPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }

    var allMedia by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var trashedMedia by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var favorites by remember { mutableStateOf(MediaRepository.getFavorites(context)) }
    var pinnedFolders by remember { mutableStateOf(MediaRepository.getPinnedFolders(context)) }
    var hiddenFolders by remember { mutableStateOf(MediaRepository.getHiddenFolders(context)) }
    var hiddenFiles by remember { mutableStateOf(MediaRepository.getHiddenFiles(context)) }

    var activeTab by remember { mutableIntStateOf(0) }
    var showAllTimelineMode by remember { mutableStateOf(false) }
    var selectedFolderName by remember { mutableStateOf<String?>(null) }
    var isViewingRecycleBin by remember { mutableStateOf(false) }
    var showLiveQrScanner by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var sortField by remember { mutableStateOf(SortField.LAST_MODIFIED) }
    var sortAscending by remember { mutableStateOf(false) }
    var mediaFilter by remember { mutableStateOf(MediaTypeFilter.ALL) }
    var showHiddenTemp by remember { mutableStateOf(false) }
    var isListView by remember { mutableStateOf(false) }
    var columnCount by remember { mutableIntStateOf(3) }

    var showSortDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showColumnDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showAiSettingsDialog by remember { mutableStateOf(false) }
    var folderActionTarget by remember { mutableStateOf<MediaFolder?>(null) }

    var viewerList by remember { mutableStateOf<List<MediaItem>?>(null) }
    var viewerIndex by remember { mutableIntStateOf(0) }
    var viewerIsRecycleBin by remember { mutableStateOf(false) }

    fun reloadMedia() {
        if (!hasPermission) return
        scope.launch {
            isLoading = true
            allMedia = MediaRepository.loadAllMedia(context)
            trashedMedia = MediaRepository.loadTrashedMedia(context)
            if (isViewingRecycleBin && trashedMedia.isEmpty()) {
                isViewingRecycleBin = false
                selectedFolderName = null
            }
            isLoading = false
        }
    }

    DisposableEffect(hasPermission) {
        if (!hasPermission) {
            onDispose { }
        } else {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { reloadMedia() }
            }
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            onDispose { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            viewerList = null
            reloadMedia()
        }
    }

    fun requestMoveToRecycleBin(targets: List<MediaItem>) {
        if (targets.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pi = MediaStore.createTrashRequest(context.contentResolver, targets.map { it.uri }, true)
                intentSenderLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                Toast.makeText(context, "Trash failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            scope.launch(Dispatchers.IO) {
                targets.forEach { try { context.contentResolver.delete(it.uri, null, null) } catch (_: Exception) {} }
                withContext(Dispatchers.Main) { viewerList = null; reloadMedia() }
            }
        }
    }

    fun requestRestoreFromRecycleBin(targets: List<MediaItem>) {
        if (targets.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val pi = MediaStore.createTrashRequest(context.contentResolver, targets.map { it.uri }, false)
            intentSenderLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            Toast.makeText(context, "Restore failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestPermanentDelete(targets: List<MediaItem>) {
        if (targets.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, targets.map { it.uri })
            intentSenderLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            Toast.makeText(context, "Delete failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) reloadMedia()
        else permLauncher.launch(requiredMediaPermissions())
    }

    val visibleMedia = remember(allMedia, mediaFilter, favorites, hiddenFiles, showHiddenTemp) {
        allMedia.filter { item ->
            if (!showHiddenTemp && hiddenFiles.contains(item.id)) return@filter false
            when (mediaFilter) {
                MediaTypeFilter.ALL -> true
                MediaTypeFilter.PHOTOS -> !item.isVideo
                MediaTypeFilter.VIDEOS -> item.isVideo
                MediaTypeFilter.GIFS -> item.isGif
                MediaTypeFilter.SCREENSHOTS -> item.isScreenshot
                MediaTypeFilter.FAVORITES -> favorites.contains(item.id)
            }
        }
    }

    val folders = remember(
        visibleMedia, trashedMedia, pinnedFolders, hiddenFolders, showHiddenTemp, sortField, sortAscending, searchQuery
    ) {
        val built = MediaRepository.buildFolders(
            items = visibleMedia,
            trashedItems = trashedMedia,
            pinnedFolders = pinnedFolders,
            hiddenFolders = hiddenFolders,
            showHidden = showHiddenTemp,
            sortField = sortField,
            ascending = sortAscending
        )
        if (searchQuery.isBlank()) built
        else built.filter { f -> f.isRecycleBin || f.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    if (showLiveQrScanner) {
        LiveQrScannerScreen(onClose = { showLiveQrScanner = false })
        return
    }

    val activeViewer = viewerList
    if (activeViewer != null) {
        MediaViewerScreen(
            items = activeViewer,
            initialIndex = viewerIndex,
            favorites = favorites,
            hiddenFiles = hiddenFiles,
            isRecycleBinMode = viewerIsRecycleBin,
            onBack = { viewerList = null },
            onToggleFavorite = { id -> favorites = MediaRepository.toggleFavorite(context, id) },
            onToggleHideFile = { id ->
                hiddenFiles = MediaRepository.toggleHideFile(context, id)
                Toast.makeText(context, if (hiddenFiles.contains(id)) "File hidden" else "File unhidden", Toast.LENGTH_SHORT).show()
            },
            onTrashItem = { item -> requestMoveToRecycleBin(listOf(item)) },
            onRestoreTrashedItem = { item -> requestRestoreFromRecycleBin(listOf(item)) },
            onDeletePermanently = { item -> requestPermanentDelete(listOf(item)) },
            onLockToVault = { item ->
                scope.launch {
                    val ok = MediaRepository.lockIntoVault(context, item)
                    if (ok) {
                        Toast.makeText(context, "Encrypted in Vault! Confirm trash for original.", Toast.LENGTH_LONG).show()
                        requestMoveToRecycleBin(listOf(item))
                    } else {
                        Toast.makeText(context, "Vault lock failed", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onMediaSaved = { reloadMedia() }
        )
        return
    }

    Scaffold(
        containerColor = LuminaBlack,
        bottomBar = {
            NavigationBar(containerColor = LuminaSurface) {
                NavigationBarItem(selected = activeTab == 0, onClick = { activeTab = 0 }, icon = { Icon(Icons.Default.Folder, "Albums") }, label = { Text("Albums") })
                NavigationBarItem(selected = activeTab == 1, onClick = { activeTab = 1; selectedFolderName = null; isViewingRecycleBin = false }, icon = { Icon(Icons.Default.AutoAwesome, "AI Find") }, label = { Text("AI Find") })
                NavigationBarItem(selected = activeTab == 2, onClick = { activeTab = 2; selectedFolderName = null; isViewingRecycleBin = false }, icon = { Icon(Icons.Default.Face, "People") }, label = { Text("People") })
                NavigationBarItem(selected = activeTab == 3, onClick = { activeTab = 3; selectedFolderName = null; isViewingRecycleBin = false }, icon = { Icon(Icons.Default.CleaningServices, "Clean") }, label = { Text("Clean") })
                NavigationBarItem(selected = activeTab == 4, onClick = { activeTab = 4; selectedFolderName = null; isViewingRecycleBin = false }, icon = { Icon(Icons.Default.Lock, "Vault") }, label = { Text("Vault") })
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasPermission) {
                PermissionGate(onGrant = { permLauncher.launch(requiredMediaPermissions()) })
            } else {
                AnimatedContent(targetState = activeTab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tabTransition") { tab ->
                    when (tab) {
                        0 -> {
                            val activeFolder = if (isViewingRecycleBin) {
                                folders.firstOrNull { it.isRecycleBin }
                            } else {
                                selectedFolderName?.let { name -> folders.firstOrNull { !it.isRecycleBin && it.name == name } }
                            }

                            if (activeFolder != null) {
                                FolderDetailView(
                                    folder = activeFolder,
                                    columnCount = columnCount,
                                    favorites = favorites,
                                    hiddenFiles = hiddenFiles,
                                    sortField = sortField,
                                    sortAscending = sortAscending,
                                    onBack = { selectedFolderName = null; isViewingRecycleBin = false },
                                    onOpenSort = { showSortDialog = true },
                                    onEmptyRecycleBin = { requestPermanentDelete(activeFolder.items) },
                                    onRestoreAllRecycleBin = { requestRestoreFromRecycleBin(activeFolder.items) },
                                    onOpenMedia = { list, idx ->
                                        viewerList = list; viewerIndex = idx; viewerIsRecycleBin = activeFolder.isRecycleBin
                                    }
                                )
                            } else {
                                AlbumsHomeView(
                                    folders = folders,
                                    visibleMedia = visibleMedia,
                                    favorites = favorites,
                                    isLoading = isLoading,
                                    searchQuery = searchQuery,
                                    onSearchChange = { searchQuery = it },
                                    showAllTimelineMode = showAllTimelineMode,
                                    onToggleAllTimeline = { showAllTimelineMode = !showAllTimelineMode },
                                    showHiddenTemp = showHiddenTemp,
                                    onToggleShowHidden = {
                                        showHiddenTemp = !showHiddenTemp
                                        Toast.makeText(context, if (showHiddenTemp) "Showing hidden items" else "Hidden items concealed", Toast.LENGTH_SHORT).show()
                                    },
                                    isListView = isListView,
                                    onToggleViewType = { isListView = !isListView },
                                    columnCount = columnCount,
                                    mediaFilter = mediaFilter,
                                    onSelectMediaFilter = { mediaFilter = it },
                                    sortField = sortField,
                                    sortAscending = sortAscending,
                                    onOpenQrScanner = { showLiveQrScanner = true },
                                    onOpenCamera = {
                                        try { context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
                                        catch (_: Exception) { Toast.makeText(context, "Camera not found", Toast.LENGTH_SHORT).show() }
                                    },
                                    onOpenSort = { showSortDialog = true },
                                    onOpenFilter = { showFilterDialog = true },
                                    onOpenColumns = { showColumnDialog = true },
                                    onOpenCreateFolder = { showCreateFolderDialog = true },
                                    onOpenAiSettings = { showAiSettingsDialog = true },
                                    onOpenFolder = { folder ->
                                        if (folder.isRecycleBin) { isViewingRecycleBin = true; selectedFolderName = null }
                                        else { isViewingRecycleBin = false; selectedFolderName = folder.name }
                                    },
                                    onLongPressFolder = { folder -> if (!folder.isRecycleBin) folderActionTarget = folder },
                                    onOpenMedia = { list, idx -> viewerList = list; viewerIndex = idx; viewerIsRecycleBin = false }
                                )
                            }
                        }
                        1 -> OmniSearchScreen(allMedia = visibleMedia, onOpenMedia = { list, idx -> viewerList = list; viewerIndex = idx; viewerIsRecycleBin = false })
                        2 -> PeopleScreen(allMedia = allMedia, favorites = favorites, onOpenMedia = { list, idx -> viewerList = list; viewerIndex = idx; viewerIsRecycleBin = false })
                        3 -> CleanHubScreen(allMedia = visibleMedia, onRequestBatchTrash = { requestMoveToRecycleBin(it) })
                        4 -> VaultScreen(onRestoredToGallery = { reloadMedia() })
                    }
                }
            }
        }
    }

    if (showSortDialog) {
        var tempField by remember { mutableStateOf(sortField) }
        var tempAsc by remember { mutableStateOf(sortAscending) }
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            containerColor = LuminaSurface,
            title = { Text("Sort by", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SortField.entries.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { tempField = f }.padding(vertical = 4.dp)) {
                            RadioButton(selected = tempField == f, onClick = { tempField = f })
                            Spacer(Modifier.width(8.dp))
                            Text(f.label, color = Color.White)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = LuminaCard)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { tempAsc = true }.padding(vertical = 4.dp)) {
                        RadioButton(selected = tempAsc, onClick = { tempAsc = true })
                        Spacer(Modifier.width(8.dp))
                        Text("Ascending", color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { tempAsc = false }.padding(vertical = 4.dp)) {
                        RadioButton(selected = !tempAsc, onClick = { tempAsc = false })
                        Spacer(Modifier.width(8.dp))
                        Text("Descending", color = Color.White)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sortField = tempField; sortAscending = tempAsc; showSortDialog = false }) { Text("OK", color = LuminaCyan, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showSortDialog = false }) { Text("Cancel") } }
        )
    }

    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            containerColor = LuminaSurface,
            title = { Text("Filter media", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    MediaTypeFilter.entries.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { mediaFilter = f; showFilterDialog = false }.padding(vertical = 6.dp)) {
                            RadioButton(selected = mediaFilter == f, onClick = { mediaFilter = f; showFilterDialog = false })
                            Spacer(Modifier.width(8.dp))
                            Text(f.label, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFilterDialog = false }) { Text("Close") } }
        )
    }

    if (showColumnDialog) {
        AlertDialog(
            onDismissRequest = { showColumnDialog = false },
            containerColor = LuminaSurface,
            title = { Text("Column count", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    listOf(2, 3, 4).forEach { cnt ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { columnCount = cnt; showColumnDialog = false }.padding(vertical = 6.dp)) {
                            RadioButton(selected = columnCount == cnt, onClick = { columnCount = cnt; showColumnDialog = false })
                            Spacer(Modifier.width(8.dp))
                            Text("$cnt Columns", color = Color.White)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showColumnDialog = false }) { Text("Close") } }
        )
    }

    if (showCreateFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            containerColor = LuminaSurface,
            title = { Text("Create new folder", fontWeight = FontWeight.Bold) },
            text = { OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text("Folder name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = MediaRepository.createNewFolder(context, newFolderName)
                    showCreateFolderDialog = false
                    Toast.makeText(context, if (ok) "Created Pictures/$newFolderName" else "Invalid name", Toast.LENGTH_SHORT).show()
                }) { Text("Create", color = LuminaCyan) }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") } }
        )
    }

    if (showAiSettingsDialog) {
        var keyInput by remember { mutableStateOf(AiEngine.getGeminiApiKey(context)) }
        AlertDialog(
            onDismissRequest = { showAiSettingsDialog = false },
            containerColor = LuminaSurface,
            title = { Text("AI Multi-Language OCR Key", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optional: Paste a free Google AI Studio (Gemini) API key for 99% accurate Bangla, Arabic, Hindi & handwriting OCR.", style = MaterialTheme.typography.bodySmall, color = LuminaMuted)
                    OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, label = { Text("Gemini API Key (Optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AiEngine.setGeminiApiKey(context, keyInput)
                    showAiSettingsDialog = false
                    Toast.makeText(context, "AI OCR settings saved!", Toast.LENGTH_SHORT).show()
                }) { Text("Save", color = LuminaCyan) }
            },
            dismissButton = { TextButton(onClick = { showAiSettingsDialog = false }) { Text("Cancel") } }
        )
    }

    val targetFolder = folderActionTarget
    if (targetFolder != null) {
        AlertDialog(
            onDismissRequest = { folderActionTarget = null },
            containerColor = LuminaSurface,
            title = { Text(targetFolder.name, fontWeight = FontWeight.Bold) },
            text = { Text("${targetFolder.count} items • ${MediaRepository.formatSize(targetFolder.totalBytes)}") },
            confirmButton = {
                TextButton(onClick = {
                    pinnedFolders = MediaRepository.togglePinFolder(context, targetFolder.name)
                    folderActionTarget = null
                    Toast.makeText(context, if (pinnedFolders.contains(targetFolder.name)) "Pinned to top!" else "Unpinned", Toast.LENGTH_SHORT).show()
                }) { Text(if (targetFolder.isPinned) "Unpin Album" else "Pin to Top", color = LuminaCyan) }
            },
            dismissButton = {
                TextButton(onClick = {
                    hiddenFolders = MediaRepository.toggleHideFolder(context, targetFolder.name)
                    folderActionTarget = null
                    Toast.makeText(context, if (hiddenFolders.contains(targetFolder.name)) "Album hidden!" else "Album visible", Toast.LENGTH_SHORT).show()
                }) { Text(if (targetFolder.isHidden) "Unhide Album" else "Hide Album", color = LuminaAmber) }
            }
        )
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Lumina Gallery", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = LuminaCyan)
        Spacer(Modifier.height(10.dp))
        Text("Grant media access to unlock AI OmniSearch (Photos, Videos, PDFs), Face Albums, AI Studio Pro, QR Scanner & Private Vault.", style = MaterialTheme.typography.bodyMedium, color = LuminaMuted)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant, shape = RoundedCornerShape(14.dp)) { Text("Allow Media Access", fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumsHomeView(
    folders: List<MediaFolder>,
    visibleMedia: List<MediaItem>,
    favorites: Set<Long>,
    isLoading: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    showAllTimelineMode: Boolean,
    onToggleAllTimeline: () -> Unit,
    showHiddenTemp: Boolean,
    onToggleShowHidden: () -> Unit,
    isListView: Boolean,
    onToggleViewType: () -> Unit,
    columnCount: Int,
    mediaFilter: MediaTypeFilter,
    onSelectMediaFilter: (MediaTypeFilter) -> Unit,
    sortField: SortField,
    sortAscending: Boolean,
    onOpenQrScanner: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenSort: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenColumns: () -> Unit,
    onOpenCreateFolder: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenFolder: (MediaFolder) -> Unit,
    onLongPressFolder: (MediaFolder) -> Unit,
    onOpenMedia: (List<MediaItem>, Int) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LuminaPill)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = LuminaMuted, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(7.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text(if (showAllTimelineMode) "Search media" else "Search folders", color = LuminaMuted, fontSize = 14.sp, maxLines = 1)
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(LuminaCyan),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Clear", tint = Color.White, modifier = Modifier.size(17.dp))
                }
            }
            IconButton(onClick = onOpenQrScanner, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.QrCodeScanner, "Live QR", tint = LuminaCyan, modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onOpenCamera, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.CameraAlt, "Camera", tint = Color.White, modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onToggleAllTimeline, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (showAllTimelineMode) Icons.Default.Folder else Icons.Default.PhotoLibrary,
                    contentDescription = "Switch View",
                    tint = if (showAllTimelineMode) LuminaCyan else Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onOpenSort, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = Color.White, modifier = Modifier.size(19.dp))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "Menu", tint = Color.White, modifier = Modifier.size(19.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, containerColor = LuminaSurface) {
                    DropdownMenuItem(text = { Text("Filter media") }, leadingIcon = { Icon(Icons.Default.FilterList, null) }, onClick = { menuExpanded = false; onOpenFilter() })
                    DropdownMenuItem(text = { Text(if (isListView) "Switch to Grid view" else "Switch to List view") }, leadingIcon = { Icon(if (isListView) Icons.Default.GridView else Icons.Default.ViewList, null) }, onClick = { menuExpanded = false; onToggleViewType() })
                    DropdownMenuItem(text = { Text(if (showHiddenTemp) "Conceal hidden items" else "Temporarily show hidden") }, leadingIcon = { Icon(if (showHiddenTemp) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) }, onClick = { menuExpanded = false; onToggleShowHidden() })
                    DropdownMenuItem(text = { Text("Create new folder") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }, onClick = { menuExpanded = false; onOpenCreateFolder() })
                    DropdownMenuItem(text = { Text("Column count ($columnCount)") }, leadingIcon = { Icon(Icons.Default.GridView, null) }, onClick = { menuExpanded = false; onOpenColumns() })
                    DropdownMenuItem(text = { Text("AI OCR Language Key") }, leadingIcon = { Icon(Icons.Default.Key, null, tint = LuminaCyan) }, onClick = { menuExpanded = false; onOpenAiSettings() })
                }
            }
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            items(MediaTypeFilter.entries) { f ->
                FilterChip(selected = mediaFilter == f, onClick = { onSelectMediaFilter(f) }, label = { Text(f.label) })
            }
        }

        AnimatedVisibility(visible = showHiddenTemp) {
            Row(modifier = Modifier.fillMaxWidth().background(LuminaAmber.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Temporarily showing hidden albums & files", style = MaterialTheme.typography.labelSmall, color = LuminaAmber)
                Text("Hide Now", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = LuminaCyan, modifier = Modifier.clickable { onToggleShowHidden() })
            }
        }

        if (isLoading && visibleMedia.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LuminaCyan) }
        } else if (showAllTimelineMode) {
            val sortedItems = remember(visibleMedia, sortField, sortAscending, searchQuery) {
                val base = if (searchQuery.isBlank()) visibleMedia else visibleMedia.filter {
                    it.displayName.contains(searchQuery.trim(), ignoreCase = true) ||
                        it.bucketName.contains(searchQuery.trim(), ignoreCase = true) ||
                        it.extension.contains(searchQuery.trim(), ignoreCase = true)
                }
                MediaRepository.sortMediaItems(base, sortField, sortAscending)
            }
            val grouped = remember(sortedItems) { sortedItems.groupBy { MediaRepository.formatDateHeader(it.effectiveTimeSec) } }
            LazyVerticalGrid(columns = GridCells.Fixed(columnCount), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                grouped.forEach { (dateHeader, dateItems) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(dateHeader, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    items(dateItems, key = { "${it.isVideo}_${it.id}" }) { media ->
                        MediaGridTile(item = media, isFavorite = favorites.contains(media.id), modifier = Modifier.animateItem(), onClick = { onOpenMedia(sortedItems, sortedItems.indexOf(media).coerceAtLeast(0)) })
                    }
                }
            }
        } else if (folders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No albums match your filter", color = LuminaMuted) }
        } else if (isListView) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(folders, key = { "${it.isRecycleBin}_${it.name}" }) { folder ->
                    Card(colors = CardDefaults.cardColors(containerColor = LuminaSurface), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().animateItem().combinedClickable(onClick = { onOpenFolder(folder) }, onLongClick = { onLongPressFolder(folder) })) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(folder.coverUri).size(240).crossfade(true).build(), contentDescription = folder.name, contentScale = ContentScale.Crop, modifier = Modifier.size(62.dp).clip(RoundedCornerShape(10.dp)))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (folder.isPinned) {
                                        Icon(Icons.Default.PushPin, null, tint = if (folder.isRecycleBin) LuminaRed else LuminaCyan, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(folder.name, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                }
                                Text("${folder.count} items • ${MediaRepository.formatSize(folder.totalBytes)}", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
                            }
                            if (folder.isHidden) Icon(Icons.Default.VisibilityOff, null, tint = LuminaAmber, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(columnCount), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                items(folders, key = { "${it.isRecycleBin}_${it.name}" }) { folder ->
                    Box(modifier = Modifier.aspectRatio(1f).animateItem().background(LuminaCard).combinedClickable(onClick = { onOpenFolder(folder) }, onLongClick = { onLongPressFolder(folder) })) {
                        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(folder.coverUri).size(380).crossfade(true).build(), contentDescription = folder.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.82f)))))
                        if (folder.isPinned) Icon(Icons.Default.PushPin, "Pinned", tint = if (folder.isRecycleBin) LuminaRed else Color.White, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp))
                        if (folder.isHidden) Icon(Icons.Default.VisibilityOff, "Hidden", tint = LuminaAmber, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
                        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(folder.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(folder.count.toString(), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderDetailView(
    folder: MediaFolder,
    columnCount: Int,
    favorites: Set<Long>,
    hiddenFiles: Set<Long>,
    sortField: SortField,
    sortAscending: Boolean,
    onBack: () -> Unit,
    onOpenSort: () -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onRestoreAllRecycleBin: () -> Unit,
    onOpenMedia: (List<MediaItem>, Int) -> Unit
) {
    val sorted = remember(folder.items, sortField, sortAscending) { MediaRepository.sortMediaItems(folder.items, sortField, sortAscending) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(modifier = Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (folder.isRecycleBin) LuminaRed else Color.White)
                Text("${folder.count} items • ${MediaRepository.formatSize(folder.totalBytes)}", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
            }
            IconButton(onClick = onOpenSort) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = Color.White) }
        }
        if (folder.isRecycleBin) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRestoreAllRecycleBin, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.RestoreFromTrash, null, tint = LuminaGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore All", color = LuminaGreen)
                }
                Button(onClick = onEmptyRecycleBin, colors = ButtonDefaults.buttonColors(containerColor = LuminaRed, contentColor = Color.Black), modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Empty Bin", fontWeight = FontWeight.Bold)
                }
            }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(columnCount), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            items(sorted, key = { "${it.isVideo}_${it.id}" }) { media ->
                MediaGridTile(item = media, isFavorite = favorites.contains(media.id), isHidden = hiddenFiles.contains(media.id), modifier = Modifier.animateItem(), onClick = { onOpenMedia(sorted, sorted.indexOf(media).coerceAtLeast(0)) })
            }
        }
    }
}

@Composable
private fun MediaGridTile(item: MediaItem, isFavorite: Boolean, isHidden: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(modifier = modifier.aspectRatio(1f).background(LuminaCard).clickable(onClick = onClick)) {
        AsyncImage(model = ImageRequest.Builder(context).data(item.uri).size(360).crossfade(true).build(), contentDescription = item.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.isVideo) {
            Row(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(MediaRepository.formatDuration(item.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        if (isFavorite) Icon(Icons.Default.Favorite, null, tint = LuminaRed, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(15.dp))
        if (isHidden) Icon(Icons.Default.VisibilityOff, null, tint = LuminaAmber, modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(15.dp))
    }
}
