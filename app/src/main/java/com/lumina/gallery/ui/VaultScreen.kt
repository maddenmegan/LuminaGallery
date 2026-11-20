package com.lumina.gallery.ui

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.lumina.gallery.data.MediaRepository
import com.lumina.gallery.model.VaultEntry
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun VaultScreen(
    onRestoredToGallery: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    var hasPin by remember { mutableStateOf(MediaRepository.hasVaultPin(context)) }
    var unlocked by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<VaultEntry?>(null) }

    val canUseBiometric = remember(context) {
        val bm = BiometricManager.from(context)
        bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun launchBiometricPrompt() {
        val act = activity ?: return
        val executor = ContextCompat.getMainExecutor(act)
        val prompt = BiometricPrompt(
            act,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    pinInput = ""
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Lumina Private Vault")
            .setSubtitle("Use your fingerprint or face to access encrypted files")
            .setNegativeButtonText("Use PIN")
            .build()
        prompt.authenticate(info)
    }

    DisposableEffect(Unit) {
        onDispose { MediaRepository.clearVaultPreviewCache(context) }
    }

    LaunchedEffect(hasPin, unlocked) {
        if (unlocked) {
            entries = MediaRepository.getVaultEntries(context)
        } else if (hasPin && canUseBiometric) {
            launchBiometricPrompt()
        }
    }

    if (!unlocked) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Lock, null, tint = LuminaViolet, modifier = Modifier.size(46.dp))
            Spacer(Modifier.height(10.dp))
            Text(if (hasPin) "Unlock Private Vault" else "Set 4-Digit Vault PIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Files are encrypted offline & invisible to all other apps", style = MaterialTheme.typography.bodySmall, color = LuminaMuted)
            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { idx ->
                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(if (idx < pinInput.length) LuminaViolet else LuminaCard))
                }
            }
            Spacer(Modifier.height(24.dp))

            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf(if (hasPin && canUseBiometric) "BIO" else "C", "0", "DEL")
            )
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(if (key == "BIO") LuminaViolet.copy(alpha = 0.22f) else LuminaCard)
                                .clickable {
                                    when (key) {
                                        "C" -> pinInput = ""
                                        "BIO" -> launchBiometricPrompt()
                                        "DEL" -> if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1)
                                        else -> if (pinInput.length < 4) {
                                            pinInput += key
                                            if (pinInput.length == 4) {
                                                if (!hasPin) {
                                                    MediaRepository.setVaultPin(context, pinInput)
                                                    hasPin = true; unlocked = true; pinInput = ""
                                                    Toast.makeText(context, "Vault PIN saved!", Toast.LENGTH_SHORT).show()
                                                } else if (MediaRepository.verifyVaultPin(context, pinInput)) {
                                                    unlocked = true; pinInput = ""
                                                } else {
                                                    pinInput = ""
                                                    Toast.makeText(context, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            when (key) {
                                "DEL" -> Icon(Icons.Default.Backspace, null, tint = Color.White)
                                "BIO" -> Icon(Icons.Default.Fingerprint, null, tint = LuminaViolet, modifier = Modifier.size(32.dp))
                                else -> Text(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Private Vault (${entries.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = LuminaViolet)
                    Text("Open any media & tap 'Vault' to lock it here", style = MaterialTheme.typography.labelSmall, color = LuminaMuted)
                }
                OutlinedButton(onClick = { unlocked = false; MediaRepository.clearVaultPreviewCache(context) }) {
                    Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Lock")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Your encrypted vault is empty.", color = LuminaMuted) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(entries, key = { it.fileName }) { entry ->
                        VaultThumbnailTile(entry = entry, onClick = { selectedEntry = entry })
                    }
                }
            }
        }
    }

    val active = selectedEntry
    if (active != null) {
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            containerColor = LuminaSurface,
            title = { Text(active.originalName, maxLines = 1) },
            text = { Text("Size: ${MediaRepository.formatSize(active.sizeBytes)}\nRestore to gallery or permanently delete?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = MediaRepository.restoreFromVault(context, active)
                        selectedEntry = null
                        entries = MediaRepository.getVaultEntries(context)
                        if (ok) {
                            Toast.makeText(context, "Restored to LuminaRestored!", Toast.LENGTH_SHORT).show()
                            onRestoredToGallery()
                        }
                    }
                }) { Text("Restore", color = LuminaCyan) }
            },
            dismissButton = {
                TextButton(onClick = {
                    MediaRepository.deleteVaultEntry(context, active)
                    selectedEntry = null
                    entries = MediaRepository.getVaultEntries(context)
                    Toast.makeText(context, "Permanently deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete Forever", color = LuminaRed) }
            }
        )
    }
}

@Composable
private fun VaultThumbnailTile(entry: VaultEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    var previewFile by remember(entry.fileName) { mutableStateOf<File?>(null) }
    LaunchedEffect(entry.fileName) { previewFile = MediaRepository.decodeVaultPreviewFile(context, entry) }
    Box(
        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(LuminaCard).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (previewFile != null) {
            AsyncImage(model = previewFile, contentDescription = entry.originalName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (entry.isVideo) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}
