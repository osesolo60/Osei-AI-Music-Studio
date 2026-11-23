package com.osei.aimusicstudio.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.ai.MemoryProfiler
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.ui.components.MixerTrackStrip
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.MixerViewModel
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel

@Composable
fun StudioMixerScreen(
    mixerViewModel: MixerViewModel,
    studioViewModel: StudioViewModel,
    onNavigate: (String) -> Unit
) {
    val allProjects by mixerViewModel.allMixerProjects.collectAsStateWithLifecycle()
    val currentProjectWithTracks by mixerViewModel.currentMixerProject.collectAsStateWithLifecycle()
    val libraryProjects by studioViewModel.projects.collectAsStateWithLifecycle()

    val isPlaying by mixerViewModel.audioEngine.isPlaying.collectAsStateWithLifecycle()
    val positionMs by mixerViewModel.audioEngine.positionMs.collectAsStateWithLifecycle()
    val totalDurationMs by mixerViewModel.audioEngine.totalDurationMs.collectAsStateWithLifecycle()
    val preparingTrackId by mixerViewModel.audioEngine.preparingTrackId.collectAsStateWithLifecycle()
    val preparedTrackIds by mixerViewModel.audioEngine.preparedTrackIds.collectAsStateWithLifecycle()
    val activeReaderCount by mixerViewModel.audioEngine.activeReaderCount.collectAsStateWithLifecycle()
    val engineError by mixerViewModel.audioEngine.errorMessage.collectAsStateWithLifecycle()
    val lowMemoryNotice by mixerViewModel.audioEngine.lowMemoryNotice.collectAsStateWithLifecycle()

    val trackWaveforms by mixerViewModel.trackWaveforms.collectAsStateWithLifecycle()
    val uiError by mixerViewModel.uiError.collectAsStateWithLifecycle()
    val statusMessage by mixerViewModel.statusMessage.collectAsStateWithLifecycle()
    val isExporting by mixerViewModel.isExporting.collectAsStateWithLifecycle()
    val exportProgress by mixerViewModel.exportProgress.collectAsStateWithLifecycle()
    val memorySnapshot by mixerViewModel.memorySnapshot.collectAsStateWithLifecycle()

    val memoryConfig = remember(memorySnapshot) { MemoryProfiler.recommendConfig(memorySnapshot) }

    var pendingTrackIdForAudio by remember { mutableStateOf<Long?>(null) }
    var pendingTrackIdForExport by remember { mutableStateOf<Long?>(null) }
    var showProjectPickerDialog by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteProjectConfirm by remember { mutableStateOf(false) }
    var showAddTrackDialog by remember { mutableStateOf(false) }
    var showLoadStemsDialog by remember { mutableStateOf(false) }
    var dialogTextInput by remember { mutableStateOf("") }

    val audioImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val trackId = pendingTrackIdForAudio
        if (uri != null && trackId != null) {
            mixerViewModel.importOrReplaceTrackAudio(trackId, uri)
        }
        pendingTrackIdForAudio = null
    }

    val exportWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destUri: Uri? ->
        if (destUri != null) {
            mixerViewModel.exportMixToWavUri(destUri)
        }
    }

    val exportTrackWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destUri: Uri? ->
        val trackId = pendingTrackIdForExport
        if (destUri != null && trackId != null) {
            mixerViewModel.exportSingleTrackToWavUri(trackId, destUri)
        }
        pendingTrackIdForExport = null
    }

    val activeProject = currentProjectWithTracks?.project
    val tracks = currentProjectWithTracks?.tracks.orEmpty()
    val combinedError = uiError ?: engineError

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + Project Controls + Export
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SIX-TRACK STUDIO MIXER",
                        color = NeonPurple,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Text(
                        text = activeProject?.name ?: "Loading Mix...",
                        color = TextWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = { mixerViewModel.saveCurrentProjectSnapshot() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardSurface)
                            .testTag("mixer_save_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save Mixer Project", tint = NeonBlue)
                    }
                    IconButton(
                        onClick = { showProjectPickerDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardSurface)
                            .testTag("mixer_projects_button")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open Mixer Projects", tint = NeonPurple)
                    }
                    IconButton(
                        onClick = { onNavigate("export") },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardSurface)
                            .testTag("mixer_export_screen_button")
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export Mix", tint = NeonBlue)
                    }
                }
            }
        }

        // Quick Actions Bar: New Mix, Rename, Delete, Load Stems, Direct WAV Export
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        dialogTextInput = "Studio Mix ${allProjects.size + 1}"
                        showNewProjectDialog = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("mixer_new_btn"),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("New Mix", color = TextWhite, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        dialogTextInput = activeProject?.name ?: ""
                        showRenameDialog = true
                    },
                    enabled = activeProject != null,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("mixer_rename_btn"),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Rename", color = TextWhite, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { showLoadStemsDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("mixer_load_stems_btn"),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Load Stems", color = NeonBlue, fontSize = 12.sp)
                }
                IconButton(
                    onClick = { showDeleteProjectConfirm = true },
                    enabled = activeProject != null && allProjects.size > 1,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mixer_delete_project_btn")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Mixer Project", tint = TextGrey)
                }
            }
        }

        // 4 GB RAM Resource Telemetry Bar
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkNavy
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${memoryConfig.tier.label} • ${memorySnapshot.deviceAvailableMb} MB free • $activeReaderCount stream(s) open",
                            color = TextGrey,
                            fontSize = 11.sp
                        )
                    }
                    TextButton(
                        onClick = { mixerViewModel.handleLowMemory() },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("Trim RAM", color = NeonPurple, fontSize = 11.sp)
                    }
                }
            }
        }

        // Error or Status Banners
        if (combinedError != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF3A1620)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF6B6B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = combinedError, color = TextWhite, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { mixerViewModel.clearNotices() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextGrey)
                        }
                    }
                }
            }
        }

        if (lowMemoryNotice != null || statusMessage != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardSurface
                ) {
                    Text(
                        text = lowMemoryNotice ?: statusMessage.orEmpty(),
                        color = NeonBlue,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // Master Transport, Timeline Seek & Master Volume Card
        item {
            val masterVol = activeProject?.masterVolume ?: 0.85f
            val safeDur = totalDurationMs.coerceAtLeast(1L)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Shared Timeline Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formatDuration(positionMs), color = NeonBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Shared Sync Clock (44.1 kHz)", color = TextGrey, fontSize = 11.sp)
                        Text(text = formatDuration(totalDurationMs), color = TextWhite, fontSize = 12.sp)
                    }
                    Slider(
                        value = (positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { frac ->
                            mixerViewModel.seekTo((frac * safeDur).toLong())
                        },
                        enabled = totalDurationMs > 0L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mixer_timeline_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = NeonBlue,
                            activeTrackColor = NeonBlue,
                            inactiveTrackColor = TextGrey.copy(alpha = 0.3f)
                        )
                    )

                    // Transport Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { mixerViewModel.skipBy(-5000L) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("mixer_rewind_btn")
                        ) {
                            Icon(Icons.Default.FastRewind, contentDescription = "Rewind 5 seconds", tint = TextWhite)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { mixerViewModel.stopPlayback() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("mixer_stop_btn")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop Playback", tint = TextGrey)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        FloatingActionButton(
                            onClick = {
                                studioViewModel.audioPlayerManager.stop()
                                mixerViewModel.togglePlayPause()
                            },
                            containerColor = NeonPurple,
                            contentColor = TextWhite,
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("mixer_play_pause_btn")
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause Mix" else "Play Mix",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        IconButton(
                            onClick = { mixerViewModel.skipBy(5000L) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("mixer_forward_btn")
                        ) {
                            Icon(Icons.Default.FastForward, contentDescription = "Forward 5 seconds", tint = TextWhite)
                        }
                    }

                    // Master Volume + Quick Export WAV
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Master", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = masterVol,
                            onValueChange = { mixerViewModel.updateMasterVolume(it) },
                            valueRange = 0f..1.5f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mixer_master_volume_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = NeonBlue,
                                activeTrackColor = NeonBlue,
                                inactiveTrackColor = TextGrey.copy(alpha = 0.3f)
                            )
                        )
                        Text(text = "${(masterVol * 100).toInt()}%", color = NeonBlue, fontSize = 12.sp)
                    }

                    if (isExporting) {
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = NeonPurple
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Mixing WAV: ${(exportProgress * 100).toInt()}%", color = TextGrey, fontSize = 12.sp)
                            TextButton(onClick = { mixerViewModel.cancelExport() }) {
                                Text("Cancel", color = Color(0xFFFF6B6B))
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                val safeName = (activeProject?.name ?: "osei_mix").replace(Regex("[^A-Za-z0-9_-]"), "_")
                                exportWavLauncher.launch("${safeName}_mix.wav")
                            },
                            enabled = tracks.any { it.isEnabled && !it.audioUri.isNullOrBlank() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("mixer_export_wav_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, tint = StudioBackground, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export Mixed Audio (WAV)", color = StudioBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Tracks Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mixer Tracks (${tracks.size})",
                    color = TextWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                FilledTonalButton(
                    onClick = {
                        dialogTextInput = "Track ${tracks.size + 1}"
                        showAddTrackDialog = true
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("mixer_add_track_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Track")
                }
            }
        }

        items(tracks, key = { it.id }) { track ->
            MixerTrackStrip(
                track = track,
                waveformData = trackWaveforms[track.id],
                isPreparing = preparingTrackId == track.id,
                isStreamReady = track.id in preparedTrackIds,
                timelinePositionMs = positionMs,
                timelineDurationMs = totalDurationMs,
                isExporting = isExporting,
                onLoadOrReplaceAudio = {
                    pendingTrackIdForAudio = track.id
                    audioImportLauncher.launch(
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
                onExportTrack = {
                    pendingTrackIdForExport = track.id
                    val safeTrack = track.name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
                    exportTrackWavLauncher.launch("${safeTrack}.wav")
                },
                onToggleEnabled = { mixerViewModel.toggleTrackEnabled(track.id) },
                onToggleMute = { mixerViewModel.toggleTrackMute(track.id) },
                onToggleSolo = { mixerViewModel.toggleTrackSolo(track.id) },
                onVolumeChange = { mixerViewModel.updateTrackVolume(track.id, it) },
                onPanChange = { mixerViewModel.updateTrackPan(track.id, it) },
                onNudgeOffset = { delta -> mixerViewModel.nudgeTrackOffset(track.id, delta) },
                onResetOffset = { mixerViewModel.resetTrackOffset(track.id) },
                onSeekTimelineFraction = { frac ->
                    mixerViewModel.seekTo((frac * totalDurationMs.coerceAtLeast(1L)).toLong())
                },
                onRemoveTrack = { mixerViewModel.removeTrack(track) }
            )
        }
    }

    // Saved Mixer Projects Picker Dialog
    if (showProjectPickerDialog) {
        AlertDialog(
            onDismissRequest = { showProjectPickerDialog = false },
            title = { Text("Saved Mixer Projects") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    allProjects.forEach { item ->
                        val isCurrent = item.project.id == activeProject?.id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mixerViewModel.selectMixerProject(item.project.id)
                                    showProjectPickerDialog = false
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCurrent) DarkNavy else CardSurface
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(item.project.name, color = TextWhite, fontWeight = FontWeight.Bold)
                                Text(
                                    "${item.tracks.count { !it.audioUri.isNullOrBlank() }} / ${item.tracks.size} tracks loaded",
                                    color = NeonBlue,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProjectPickerDialog = false }) { Text("Close") }
            }
        )
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("New Mixer Project") },
            text = {
                OutlinedTextField(
                    value = dialogTextInput,
                    onValueChange = { dialogTextInput = it },
                    label = { Text("Project Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    mixerViewModel.createNewMixerProject(dialogTextInput)
                    showNewProjectDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Mixer Project") },
            text = {
                OutlinedTextField(
                    value = dialogTextInput,
                    onValueChange = { dialogTextInput = it },
                    label = { Text("Project Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    mixerViewModel.renameCurrentProject(dialogTextInput)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteProjectConfirm && activeProject != null) {
        AlertDialog(
            onDismissRequest = { showDeleteProjectConfirm = false },
            title = { Text("Delete Mixer Project") },
            text = { Text("Delete '${activeProject.name}' and its track configuration?") },
            confirmButton = {
                TextButton(onClick = {
                    mixerViewModel.deleteMixerProject(activeProject.id)
                    showDeleteProjectConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProjectConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddTrackDialog) {
        AlertDialog(
            onDismissRequest = { showAddTrackDialog = false },
            title = { Text("Add Mixer Track") },
            text = {
                OutlinedTextField(
                    value = dialogTextInput,
                    onValueChange = { dialogTextInput = it },
                    label = { Text("Track Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    mixerViewModel.addTrack(dialogTextInput)
                    showAddTrackDialog = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddTrackDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLoadStemsDialog) {
        LoadStemsFromProjectDialog(
            libraryProjects = libraryProjects,
            onSelectProject = { proj ->
                mixerViewModel.loadExistingStemsFromMusicProject(proj)
                showLoadStemsDialog = false
            },
            onDismiss = { showLoadStemsDialog = false }
        )
    }
}

@Composable
private fun LoadStemsFromProjectDialog(
    libraryProjects: List<MusicProjectEntity>,
    onSelectProject: (MusicProjectEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load Existing Stem Files") },
        text = {
            if (libraryProjects.isEmpty()) {
                Text("No library projects found. Import audio directly into mixer tracks instead.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Loads only real separated stem files saved on disk (does not run AI automatically).",
                        fontSize = 12.sp,
                        color = TextGrey
                    )
                    libraryProjects.take(6).forEach { proj ->
                        val stemCount = listOfNotNull(proj.vocalUri, proj.drumsUri, proj.bassUri, proj.otherUri).size
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProject(proj) },
                            shape = RoundedCornerShape(10.dp),
                            color = DarkNavy
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(proj.title, color = TextWhite, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (stemCount > 0) "$stemCount stem file(s) linked" else "No separated stems saved yet",
                                    color = if (stemCount > 0) NeonBlue else TextGrey,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
