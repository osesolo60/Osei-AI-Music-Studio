package com.osei.aimusicstudio.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.audio.AudioPlayerManager
import com.osei.aimusicstudio.audio.AudioValidator
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AudioImportScreen(
    onImportSuccess: (MusicProjectEntity) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewPlayer = remember { AudioPlayerManager(context) }

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer.release()
        }
    }

    val isPreviewPlaying by previewPlayer.isPlaying.collectAsStateWithLifecycle()
    val previewError by previewPlayer.playbackError.collectAsStateWithLifecycle()

    var songTitle by remember { mutableStateOf("") }
    var artistName by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No audio file selected") }
    var durationMs by remember { mutableLongStateOf(0L) }
    var fileSizeMb by remember { mutableFloatStateOf(0f) }
    var mimeInfo by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            previewPlayer.stop()
            isValidating = true
            validationError = null

            scope.launch {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Some providers do not support persistable permissions; continue if readable
                }

                val validation = withContext(Dispatchers.IO) {
                    AudioValidator.inspectAndValidate(context, uri)
                }

                validation.onSuccess { meta ->
                    selectedUri = uri
                    fileName = meta.displayName
                    songTitle = meta.title
                    artistName = meta.artist
                    durationMs = meta.durationMs
                    fileSizeMb = meta.fileSizeBytes / (1024f * 1024f)
                    mimeInfo = "${meta.mimeType} • ${meta.sampleRate} Hz • ${meta.channelCount}ch"
                    validationError = null
                }.onFailure { err ->
                    selectedUri = null
                    fileName = "Invalid or unsupported file"
                    durationMs = 0L
                    fileSizeMb = 0f
                    mimeInfo = ""
                    validationError = err.localizedMessage ?: "Unsupported or corrupted audio file"
                }
                isValidating = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    previewPlayer.stop()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Import Audio (SAF)",
                    color = TextWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = fileName, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    if (selectedUri != null) {
                        Text(
                            text = "Size: ${String.format("%.2f", fileSizeMb)} MB • Duration: ${formatDuration(durationMs)}",
                            color = NeonBlue,
                            fontSize = 13.sp
                        )
                        if (mimeInfo.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = mimeInfo, color = TextGrey, fontSize = 12.sp)
                        }
                    } else {
                        Text(
                            text = "Decodes MP3, WAV, M4A, AAC, FLAC, OGG",
                            color = TextGrey,
                            fontSize = 12.sp
                        )
                    }

                    if (isValidating) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CircularProgressIndicator(color = NeonBlue, modifier = Modifier.size(28.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                audioPickerLauncher.launch(
                                    arrayOf(
                                        "audio/mpeg",
                                        "audio/wav",
                                        "audio/x-wav",
                                        "audio/mp4",
                                        "audio/aac",
                                        "audio/flac",
                                        "audio/ogg",
                                        "audio/*"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("select_audio_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                        ) {
                            Text(text = "Select Audio", color = TextWhite, fontWeight = FontWeight.Bold)
                        }

                        if (selectedUri != null) {
                            OutlinedButton(
                                onClick = {
                                    if (isPreviewPlaying) {
                                        previewPlayer.pause()
                                    } else {
                                        previewPlayer.loadAndPlay(selectedUri.toString())
                                    }
                                },
                                modifier = Modifier.testTag("preview_audio_button")
                            ) {
                                Icon(
                                    if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Preview Audio",
                                    tint = NeonBlue
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPreviewPlaying) "Pause" else "Preview",
                                    color = NeonBlue
                                )
                            }
                        }
                    }
                }
            }

            if (validationError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF3A1620)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF6B6B))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = validationError!!, color = TextWhite, fontSize = 13.sp)
                    }
                }
            }

            if (previewError != null) {
                Text(text = previewError!!, color = Color(0xFFFF6B6B), fontSize = 12.sp)
            }

            OutlinedTextField(
                value = songTitle,
                onValueChange = { songTitle = it },
                label = { Text("Song Title") },
                enabled = selectedUri != null,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = TextGrey,
                    focusedLabelColor = NeonBlue,
                    unfocusedLabelColor = TextGrey,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite
                )
            )

            OutlinedTextField(
                value = artistName,
                onValueChange = { artistName = it },
                label = { Text("Artist / Producer") },
                enabled = selectedUri != null,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = TextGrey,
                    focusedLabelColor = NeonBlue,
                    unfocusedLabelColor = TextGrey,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite
                )
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                val uri = selectedUri ?: return@Button
                previewPlayer.stop()
                val project = MusicProjectEntity(
                    title = songTitle.ifBlank { fileName },
                    artist = artistName.ifBlank { "Unknown Artist" },
                    audioUri = uri.toString(),
                    durationMs = durationMs,
                    fileSizeMb = fileSizeMb
                )
                onImportSuccess(project)
            },
            enabled = selectedUri != null && validationError == null && !isValidating,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("save_project_button"),
            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(text = "Save Project to Library", color = StudioBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format("%d:%02d", min, sec)
}
