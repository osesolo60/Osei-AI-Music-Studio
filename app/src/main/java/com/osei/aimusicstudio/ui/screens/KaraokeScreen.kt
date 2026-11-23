package com.osei.aimusicstudio.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.audio.ExportQualityPreset
import com.osei.aimusicstudio.audio.LrcParser
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.KaraokeViewModel
import kotlin.math.roundToLong

@Composable
fun KaraokeScreen(
    karaokeViewModel: KaraokeViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val allSessions by karaokeViewModel.allSessions.collectAsStateWithLifecycle()
    val session by karaokeViewModel.currentSession.collectAsStateWithLifecycle()
    val lyricLines by karaokeViewModel.lyricLines.collectAsStateWithLifecycle()
    val activeLyricIndex by karaokeViewModel.activeLyricIndex.collectAsStateWithLifecycle()

    val isPlaying by karaokeViewModel.audioEngine.isPlaying.collectAsStateWithLifecycle()
    val positionMs by karaokeViewModel.audioEngine.positionMs.collectAsStateWithLifecycle()
    val totalDurationMs by karaokeViewModel.audioEngine.totalDurationMs.collectAsStateWithLifecycle()
    val engineError by karaokeViewModel.audioEngine.errorMessage.collectAsStateWithLifecycle()

    val isRecording by karaokeViewModel.micRecorder.isRecording.collectAsStateWithLifecycle()
    val recordedDurationMs by karaokeViewModel.micRecorder.recordedDurationMs.collectAsStateWithLifecycle()
    val inputLevel by karaokeViewModel.micRecorder.inputLevel.collectAsStateWithLifecycle()
    val micError by karaokeViewModel.micRecorder.errorMessage.collectAsStateWithLifecycle()

    val selectedQuality by karaokeViewModel.selectedExportQuality.collectAsStateWithLifecycle()
    val isExporting by karaokeViewModel.isExporting.collectAsStateWithLifecycle()
    val exportProgress by karaokeViewModel.exportProgress.collectAsStateWithLifecycle()
    val statusMessage by karaokeViewModel.statusMessage.collectAsStateWithLifecycle()
    val uiError by karaokeViewModel.uiError.collectAsStateWithLifecycle()

    var playInstrumentalOnRecord by remember { mutableStateOf(true) }
    var editLyricsMode by remember { mutableStateOf(false) }
    var rawLyricsDraft by remember(session?.id, session?.rawLrcText) {
        mutableStateOf(session?.rawLrcText ?: "")
    }
    var showSessionPicker by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var newSessionTitle by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            karaokeViewModel.startVocalRecording(playInstrumentalOnRecord)
        } else {
            karaokeViewModel.reportUiError(
                "Microphone permission (RECORD_AUDIO) is required to record your vocal take."
            )
        }
    }

    val importInstrumentalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) karaokeViewModel.importInstrumental(uri) }

    val importVocalRefLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) karaokeViewModel.importVocalReference(uri) }

    val importLrcLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) karaokeViewModel.importLyricsFile(uri) }

    val exportKaraokeWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destUri: Uri? -> if (destUri != null) karaokeViewModel.exportKaraokeMixToWavUri(destUri) }

    val combinedError = uiError ?: micError ?: engineError

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                    Column {
                        Text(
                            text = "KARAOKE STUDIO",
                            color = NeonPurple,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = session?.title ?: "Karaoke Session",
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { karaokeViewModel.saveSessionLocally() },
                        modifier = Modifier.size(48.dp).testTag("karaoke_save_btn")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save Karaoke Session", tint = NeonBlue)
                    }
                    IconButton(
                        onClick = { showSessionPicker = true },
                        modifier = Modifier.size(48.dp).testTag("karaoke_sessions_btn")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Karaoke Sessions", tint = NeonPurple)
                    }
                }
            }
        }

        // Audio Track Import & Levels Card
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("KARAOKE TRACKS", color = NeonBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { importInstrumentalLauncher.launch(arrayOf("audio/*")) },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_import_instrumental_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                        ) {
                            Text(
                                text = if (session?.instrumentalUri != null) "Replace Instrumental" else "Load Instrumental",
                                color = StudioBackground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { importVocalRefLauncher.launch(arrayOf("audio/*")) },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_import_vocal_btn")
                        ) {
                            Text(
                                text = if (session?.vocalReferenceUri != null) "Replace Vocal Guide" else "Load Vocal Guide",
                                color = TextWhite,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (session?.instrumentalName?.isNotBlank() == true) {
                        Text("Instrumental: ${session?.instrumentalName}", color = TextWhite, fontSize = 12.sp)
                        Slider(
                            value = session?.instrumentalVolume ?: 0.85f,
                            onValueChange = { karaokeViewModel.updateVolumes(instrumentalVol = it) },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = NeonBlue, activeTrackColor = NeonBlue)
                        )
                    }

                    if (session?.vocalReferenceName?.isNotBlank() == true) {
                        Text("Vocal Guide: ${session?.vocalReferenceName} (${((session?.vocalReferenceVolume ?: 0f) * 100).toInt()}%)", color = TextGrey, fontSize = 12.sp)
                        Slider(
                            value = session?.vocalReferenceVolume ?: 0.35f,
                            onValueChange = { karaokeViewModel.updateVolumes(vocalRefVol = it) },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = NeonPurple, activeTrackColor = NeonPurple)
                        )
                    }

                    if (session?.recordedTakeName?.isNotBlank() == true) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Recorded Vocal: ${session?.recordedTakeName}", color = NeonBlue, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { karaokeViewModel.clearRecordedTake() }) {
                                Text("Remove", color = Color(0xFFFF6B6B), fontSize = 11.sp)
                            }
                        }
                        Slider(
                            value = session?.recordedTakeVolume ?: 1.0f,
                            onValueChange = { karaokeViewModel.updateVolumes(recordedTakeVol = it) },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = NeonBlue, activeTrackColor = NeonPurple)
                        )
                        val takeOffsetMs = session?.recordedTakeOffsetMs ?: 0L
                        val offsetLabel = if (takeOffsetMs > 0L) "+${takeOffsetMs} ms" else "${takeOffsetMs} ms"
                        Text(
                            text = "Recorded Take Sync: $offsetLabel",
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Manual alignment step adjustment (no auto latency calibration)",
                            color = TextGrey,
                            fontSize = 10.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { karaokeViewModel.adjustRecordedTakeOffset(-50L) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_take_sync_minus_50")
                            ) {
                                Text("-50ms", color = TextWhite, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { karaokeViewModel.adjustRecordedTakeOffset(-10L) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_take_sync_minus_10")
                            ) {
                                Text("-10ms", color = TextWhite, fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { karaokeViewModel.resetRecordedTakeOffset() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("karaoke_take_sync_reset")
                            ) {
                                Text("0 ms", color = NeonBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { karaokeViewModel.adjustRecordedTakeOffset(10L) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_take_sync_plus_10")
                            ) {
                                Text("+10ms", color = TextWhite, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { karaokeViewModel.adjustRecordedTakeOffset(50L) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_take_sync_plus_50")
                            ) {
                                Text("+50ms", color = TextWhite, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Transport + Mic Recording Card
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TRANSPORT & MIC RECORDING", color = NeonPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${LrcParser.formatShortTime(positionMs)} / ${LrcParser.formatShortTime(totalDurationMs)}", color = NeonBlue, fontSize = 11.sp)
                    }

                    Slider(
                        value = positionMs.toFloat().coerceIn(0f, totalDurationMs.coerceAtLeast(100L).toFloat()),
                        onValueChange = { karaokeViewModel.seekTo(it.roundToLong()) },
                        valueRange = 0f..totalDurationMs.coerceAtLeast(100L).toFloat(),
                        enabled = totalDurationMs > 0L,
                        colors = SliderDefaults.colors(thumbColor = NeonBlue, activeTrackColor = NeonPurple)
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { karaokeViewModel.togglePlayPause() },
                            enabled = totalDurationMs > 0L && !isRecording,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_play_pause_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                        ) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = StudioBackground)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isPlaying) "Pause" else "Play Mix", color = StudioBackground, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { karaokeViewModel.stopPlayback() },
                            enabled = !isRecording,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("karaoke_stop_btn")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = TextWhite)
                        }
                        Button(
                            onClick = {
                                if (isRecording) {
                                    karaokeViewModel.stopVocalRecording()
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        karaokeViewModel.startVocalRecording(playInstrumentalOnRecord)
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_record_btn"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xFFD32F2F) else NeonPurple
                            )
                        ) {
                            Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, tint = TextWhite)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isRecording) "Stop (${recordedDurationMs / 1000}s)" else "Record Mic", color = TextWhite, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = playInstrumentalOnRecord,
                            onCheckedChange = { playInstrumentalOnRecord = it },
                            enabled = !isRecording
                        )
                        Text("Play instrumental while recording mic WAV", color = TextGrey, fontSize = 12.sp)
                    }

                    if (isRecording) {
                        LinearProgressIndicator(
                            progress = { inputLevel.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFFF6B6B)
                        )
                    }
                }
            }
        }

        if (combinedError != null) {
            item {
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF3A1219), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(combinedError, color = Color(0xFFFF8A8A), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { karaokeViewModel.clearNotices() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color(0xFFFF8A8A))
                        }
                    }
                }
            }
        }

        if (statusMessage != null) {
            item {
                Surface(shape = RoundedCornerShape(10.dp), color = DarkNavy, modifier = Modifier.fillMaxWidth()) {
                    Text(statusMessage!!, color = NeonBlue, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // Lyrics Controls Header + Mode Switch + Global Sync Offset
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("SYNCHRONIZED LYRICS", color = NeonBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { importLrcLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) {
                                Text("Import .LRC/.TXT", color = NeonPurple, fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { editLyricsMode = !editLyricsMode },
                                modifier = Modifier.testTag("karaoke_toggle_edit_lyrics_btn")
                            ) {
                                Text(if (editLyricsMode) "Sing View" else "Edit Lyrics", color = NeonBlue, fontSize = 11.sp)
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Global Lyric Sync: ${session?.lyricsOffsetMs ?: 0L} ms", color = TextGrey, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { karaokeViewModel.adjustGlobalLyricsOffset(-250L) }) {
                                Text("-0.25s", color = TextWhite, fontSize = 11.sp)
                            }
                            TextButton(onClick = { karaokeViewModel.adjustGlobalLyricsOffset(250L) }) {
                                Text("+0.25s", color = TextWhite, fontSize = 11.sp)
                            }
                            TextButton(onClick = { karaokeViewModel.resetGlobalLyricsOffset() }) {
                                Text("Reset", color = NeonBlue, fontSize = 11.sp)
                            }
                        }
                    }

                    if (editLyricsMode) {
                        OutlinedTextField(
                            value = rawLyricsDraft,
                            onValueChange = { rawLyricsDraft = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("karaoke_lyrics_input"),
                            label = { Text("Paste LRC ([mm:ss.xx] line) or plain lyrics") }
                        )
                        Button(
                            onClick = {
                                karaokeViewModel.updateRawLyricsText(rawLyricsDraft)
                                editLyricsMode = false
                            },
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("karaoke_apply_lyrics_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                        ) {
                            Text("Save Lyrics to Session", color = TextWhite, fontWeight = FontWeight.Bold)
                        }
                    } else if (lyricLines.isEmpty()) {
                        Text(
                            text = "No lyrics loaded. Import an .lrc or .txt file, or tap 'Edit Lyrics' to type or paste lyrics and stamp manual timings.",
                            color = TextGrey,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Live Highlighted Lyrics List (with per-line manual timing adjustments)
        if (!editLyricsMode && lyricLines.isNotEmpty()) {
            itemsIndexed(lyricLines, key = { idx, line -> "${idx}_${line.id}" }) { idx, line ->
                val isActive = idx == activeLyricIndex
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isActive) NeonPurple.copy(alpha = 0.28f) else CardSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (line.timestampMs >= 0L) {
                                karaokeViewModel.seekTo((line.timestampMs + (session?.lyricsOffsetMs ?: 0L)).coerceAtLeast(0L))
                            }
                        }
                        .testTag("karaoke_lyric_row_$idx")
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = line.text,
                            color = if (isActive) NeonBlue else TextWhite,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            fontSize = if (isActive) 16.sp else 14.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LrcParser.formatShortTime(line.timestampMs),
                                color = if (line.timestampMs >= 0L) NeonBlue else TextGrey,
                                fontSize = 11.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { karaokeViewModel.nudgeLyricLineTiming(idx, -250L) }) {
                                    Text("-0.25s", color = TextGrey, fontSize = 11.sp)
                                }
                                TextButton(onClick = { karaokeViewModel.stampLyricLineToCurrentTime(idx) }) {
                                    Text("Stamp", color = NeonPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { karaokeViewModel.nudgeLyricLineTiming(idx, 250L) }) {
                                    Text("+0.25s", color = TextGrey, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Export & Share Karaoke Mix
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("EXPORT & SHARE KARAOKE WAV", color = NeonBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    ExportQualityPreset.entries.forEach { preset ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { karaokeViewModel.setExportQuality(preset) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedQuality == preset,
                                onClick = { karaokeViewModel.setExportQuality(preset) }
                            )
                            Text(preset.label, color = TextWhite, fontSize = 12.sp)
                        }
                    }

                    if (isExporting) {
                        LinearProgressIndicator(progress = { exportProgress }, color = NeonPurple, modifier = Modifier.fillMaxWidth())
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val safe = (session?.title ?: "karaoke").replace(Regex("[^A-Za-z0-9_-]"), "_")
                                exportKaraokeWavLauncher.launch("${safe}_karaoke.wav")
                            },
                            enabled = !isExporting && !isRecording,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_export_wav_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                        ) {
                            Text("Save Mix WAV", color = StudioBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                karaokeViewModel.shareKaraokeMixWav { intent ->
                                    context.startActivity(intent)
                                }
                            },
                            enabled = !isExporting && !isRecording,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("karaoke_share_wav_btn")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share WAV", color = TextWhite, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (showSessionPicker) {
        AlertDialog(
            onDismissRequest = { showSessionPicker = false },
            containerColor = CardSurface,
            title = { Text("Karaoke Sessions", color = TextWhite) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showSessionPicker = false
                            newSessionTitle = "Karaoke Session ${allSessions.size + 1}"
                            showNewSessionDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ New Karaoke Session", color = NeonBlue)
                    }
                    allSessions.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                karaokeViewModel.selectSession(item.id)
                                showSessionPicker = false
                            }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.title, color = TextWhite, modifier = Modifier.weight(1f))
                            if (allSessions.size > 1) {
                                IconButton(onClick = { karaokeViewModel.deleteSession(item.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF6B6B))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionPicker = false }) { Text("Close", color = NeonBlue) }
            }
        )
    }

    if (showNewSessionDialog) {
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            containerColor = CardSurface,
            title = { Text("New Karaoke Session", color = TextWhite) },
            text = {
                OutlinedTextField(
                    value = newSessionTitle,
                    onValueChange = { newSessionTitle = it },
                    label = { Text("Session Title") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    karaokeViewModel.createNewSession(newSessionTitle)
                    showNewSessionDialog = false
                }) { Text("Create", color = NeonBlue) }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) { Text("Cancel", color = TextGrey) }
            }
        )
    }
}
