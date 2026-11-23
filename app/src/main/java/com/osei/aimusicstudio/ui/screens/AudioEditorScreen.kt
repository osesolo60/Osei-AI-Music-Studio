package com.osei.aimusicstudio.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.ai.MemoryProfiler
import com.osei.aimusicstudio.audio.ExportQualityPreset
import com.osei.aimusicstudio.ui.components.AudioEditorEffectsPanel
import com.osei.aimusicstudio.ui.components.VocalControlsPanel
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.AudioEditorViewModel
import kotlin.math.roundToLong

@Composable
fun AudioEditorScreen(
    editorViewModel: AudioEditorViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val allProjects by editorViewModel.allProjects.collectAsStateWithLifecycle()
    val currentProject by editorViewModel.currentProject.collectAsStateWithLifecycle()
    val clips by editorViewModel.clips.collectAsStateWithLifecycle()
    val selectedClipId by editorViewModel.selectedClipId.collectAsStateWithLifecycle()
    val playheadMs by editorViewModel.playheadMs.collectAsStateWithLifecycle()
    val canUndo by editorViewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by editorViewModel.canRedo.collectAsStateWithLifecycle()
    val hasUnsavedChanges by editorViewModel.hasUnsavedChanges.collectAsStateWithLifecycle()
    val selectedQuality by editorViewModel.selectedExportQuality.collectAsStateWithLifecycle()
    val isBusy by editorViewModel.isBusy.collectAsStateWithLifecycle()
    val renderProgress by editorViewModel.renderProgress.collectAsStateWithLifecycle()
    val statusMessage by editorViewModel.statusMessage.collectAsStateWithLifecycle()
    val uiError by editorViewModel.uiError.collectAsStateWithLifecycle()
    val memorySnapshot by editorViewModel.memorySnapshot.collectAsStateWithLifecycle()

    val isPreviewPlaying by editorViewModel.previewPlayer.isPlaying.collectAsStateWithLifecycle()
    val previewPosMs by editorViewModel.previewPlayer.currentPosition.collectAsStateWithLifecycle()
    val previewDurMs by editorViewModel.previewPlayer.duration.collectAsStateWithLifecycle()
    val previewError by editorViewModel.previewPlayer.playbackError.collectAsStateWithLifecycle()

    val memConfig = remember(memorySnapshot) { MemoryProfiler.recommendConfig(memorySnapshot) }
    val selectedClip = remember(clips, selectedClipId) { clips.firstOrNull { it.clipId == selectedClipId } }
    val totalTimelineMs = remember(clips) { (clips.maxOfOrNull { it.timelineEndMs } ?: 0L).coerceAtLeast(1000L) }

    var showProjectDialog by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var activeControlTab by remember { mutableIntStateOf(0) } // 0 = Effects, 1 = Vocal Pitch/Tempo, 2 = Export/Share

    var trimStartDraft by remember(selectedClip?.clipId, selectedClip?.sourceStartMs) {
        mutableFloatStateOf((selectedClip?.sourceStartMs ?: 0L).toFloat())
    }
    var trimEndDraft by remember(selectedClip?.clipId, selectedClip?.sourceEndMs) {
        mutableFloatStateOf((selectedClip?.sourceEndMs ?: 1000L).toFloat())
    }

    val importClipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            editorViewModel.importAudioClip(uri)
        }
    }

    val exportClipWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destUri: Uri? ->
        if (destUri != null) editorViewModel.exportSelectedClipToWavUri(destUri)
    }

    val exportTimelineWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destUri: Uri? ->
        if (destUri != null) editorViewModel.exportTimelineToWavUri(destUri)
    }

    val combinedError = uiError ?: previewError

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Bar
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
                            text = "TIMELINE & DSP EDITOR",
                            color = NeonPurple,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = (currentProject?.name ?: "Audio Edit") + if (hasUnsavedChanges) " *" else "",
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
                        onClick = { editorViewModel.undo() },
                        enabled = canUndo && !isBusy,
                        modifier = Modifier.size(48.dp).testTag("editor_undo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if (canUndo) NeonBlue else TextGrey)
                    }
                    IconButton(
                        onClick = { editorViewModel.redo() },
                        enabled = canRedo && !isBusy,
                        modifier = Modifier.size(48.dp).testTag("editor_redo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = if (canRedo) NeonBlue else TextGrey)
                    }
                    IconButton(
                        onClick = { editorViewModel.saveProjectLocally() },
                        enabled = currentProject != null && !isBusy,
                        modifier = Modifier.size(48.dp).testTag("editor_save_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save Project", tint = if (hasUnsavedChanges) NeonPurple else NeonBlue)
                    }
                    IconButton(
                        onClick = { showProjectDialog = true },
                        modifier = Modifier.size(48.dp).testTag("editor_projects_button")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Editor Projects", tint = TextWhite)
                    }
                }
            }
        }

        // Low-RAM Guard & Import Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { importClipLauncher.launch(arrayOf("audio/*")) },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_import_clip_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = StudioBackground, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import Clip", color = StudioBackground, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        newProjectName = "Audio Edit ${allProjects.size + 1}"
                        showNewProjectDialog = true
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("editor_new_project_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("New Edit", color = TextWhite, fontSize = 12.sp)
                }
            }
        }

        item {
            Text(
                text = "${memConfig.tier.label} • ${memorySnapshot.deviceAvailableMb} MB free RAM • ${clips.size} timeline clip(s)",
                color = TextGrey,
                fontSize = 11.sp
            )
        }

        // Busy indicator + Errors/Status
        if (isBusy) {
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = DarkNavy, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(progress = { renderProgress }, color = NeonBlue, modifier = Modifier.fillMaxWidth())
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Processing audio on disk (${(renderProgress * 100).toInt()}%)...", color = NeonBlue, fontSize = 12.sp)
                            TextButton(onClick = { editorViewModel.cancelActiveRender() }) {
                                Text("Cancel", color = Color(0xFFFF6B6B))
                            }
                        }
                    }
                }
            }
        }

        if (combinedError != null) {
            item {
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF3A1219), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(combinedError, color = Color(0xFFFF8A8A), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editorViewModel.clearNotices() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss error", tint = Color(0xFFFF8A8A))
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

        // Timeline Clips & Actions
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TIMELINE CLIPS", color = NeonBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)

                    if (clips.isEmpty()) {
                        Text(
                            text = "No clips loaded yet. Import an MP3, WAV, M4A, AAC, FLAC, or OGG file to trim, split, merge, and apply DSP effects.",
                            color = TextGrey,
                            fontSize = 13.sp
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(clips, key = { it.clipId }) { clip ->
                                val selected = clip.clipId == selectedClipId
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) NeonPurple.copy(alpha = 0.28f) else DarkNavy,
                                    modifier = Modifier
                                        .widthIn(min = 140.dp, max = 210.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { editorViewModel.selectClip(clip.clipId) }
                                        .testTag("editor_clip_chip_${clip.clipId}")
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = clip.name,
                                            color = if (selected) TextWhite else TextGrey,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${formatSeconds(clip.timelineStartMs)} -> ${formatSeconds(clip.timelineEndMs)} (${formatSeconds(clip.outputDurationMs)})",
                                            color = NeonBlue,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Playhead scrubber for Split & Positioning
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Timeline Playhead", color = TextWhite, fontSize = 12.sp)
                                Text(formatSeconds(playheadMs), color = NeonBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = playheadMs.toFloat().coerceIn(0f, totalTimelineMs.toFloat()),
                                onValueChange = { editorViewModel.setPlayheadMs(it.roundToLong()) },
                                valueRange = 0f..totalTimelineMs.toFloat(),
                                modifier = Modifier.testTag("editor_playhead_slider"),
                                colors = SliderDefaults.colors(thumbColor = NeonBlue, activeTrackColor = NeonPurple)
                            )
                        }

                        // Trim Range for Selected Clip
                        if (selectedClip != null) {
                            val maxSrc = selectedClip.totalSourceDurationMs.coerceAtLeast(200L).toFloat()
                            Text(
                                text = "Trim '${selectedClip.name}': ${formatSeconds(trimStartDraft.roundToLong())} – ${formatSeconds(trimEndDraft.roundToLong())}",
                                color = TextWhite,
                                fontSize = 12.sp
                            )
                            RangeSlider(
                                value = trimStartDraft.coerceIn(0f, maxSrc - 100f)..trimEndDraft.coerceIn(100f, maxSrc),
                                onValueChange = { range ->
                                    trimStartDraft = range.start
                                    trimEndDraft = (range.endInclusive).coerceAtLeast(range.start + 100f)
                                },
                                onValueChangeFinished = {
                                    editorViewModel.applyTrimToSelected(
                                        trimStartDraft.roundToLong(),
                                        trimEndDraft.roundToLong()
                                    )
                                },
                                valueRange = 0f..maxSrc,
                                enabled = !isBusy,
                                modifier = Modifier.testTag("editor_trim_range_slider"),
                                colors = SliderDefaults.colors(thumbColor = NeonPurple, activeTrackColor = NeonBlue)
                            )
                        }

                        // Edit Actions: Split, Move -/+, Merge, Delete
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { editorViewModel.splitSelectedClipAtPlayhead() },
                                enabled = selectedClip != null && !isBusy,
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_split_btn"),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("Split", color = TextWhite, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { editorViewModel.moveSelectedClipBy(-500L) },
                                enabled = selectedClip != null && !isBusy,
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_move_left_btn"),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("-0.5s", color = TextWhite, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { editorViewModel.moveSelectedClipBy(500L) },
                                enabled = selectedClip != null && !isBusy,
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_move_right_btn"),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("+0.5s", color = TextWhite, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { editorViewModel.mergeTimelineClipsSequentially() },
                                enabled = clips.size >= 2 && !isBusy,
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_merge_btn"),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("Merge", color = NeonBlue, fontSize = 11.sp)
                            }
                            IconButton(
                                onClick = { editorViewModel.deleteSelectedClip() },
                                enabled = selectedClip != null && !isBusy,
                                modifier = Modifier.size(48.dp).testTag("editor_delete_clip_btn")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Clip", tint = Color(0xFFFF6B6B))
                            }
                        }

                        // Preview Render & Playback Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { editorViewModel.previewSelectedClip() },
                                enabled = selectedClip != null && !isBusy,
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_preview_clip_btn"),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextWhite, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Preview Clip", color = TextWhite, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { editorViewModel.previewFullTimeline() },
                                enabled = clips.isNotEmpty() && !isBusy,
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_preview_timeline_btn")
                            ) {
                                Text("Preview All", color = NeonBlue, fontSize = 12.sp)
                            }
                            IconButton(
                                onClick = { editorViewModel.previewPlayer.togglePlayPause() },
                                enabled = previewDurMs > 0L,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayCircle,
                                    contentDescription = "Pause or Resume Preview",
                                    tint = NeonBlue
                                )
                            }
                            IconButton(
                                onClick = { editorViewModel.previewPlayer.stop() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop Preview", tint = TextGrey)
                            }
                        }
                        if (previewDurMs > 0L) {
                            Text(
                                text = "Preview: ${formatSeconds(previewPosMs)} / ${formatSeconds(previewDurMs)}",
                                color = NeonBlue,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // Control Section Tabs: DSP Effects / Vocal Controls / Export & Share
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tabs = listOf("Audio Effects", "Vocal Controls", "Export WAV")
                tabs.forEachIndexed { index, title ->
                    FilterChip(
                        selected = activeControlTab == index,
                        onClick = { activeControlTab = index },
                        label = { Text(title, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).testTag("editor_tab_$index")
                    )
                }
            }
        }

        if (selectedClip != null) {
            item {
                when (activeControlTab) {
                    0 -> AudioEditorEffectsPanel(
                        effects = selectedClip.effectsConfig,
                        enabled = !isBusy,
                        onBeforeSliderDrag = { editorViewModel.recordUndoPointBeforeEdit() },
                        onEffectsChange = { editorViewModel.updateSelectedEffects(it) }
                    )
                    1 -> VocalControlsPanel(
                        vocalConfig = selectedClip.vocalConfig,
                        enabled = !isBusy,
                        onBeforeEdit = { editorViewModel.recordUndoPointBeforeEdit() },
                        onVocalChange = { editorViewModel.updateSelectedVocalTransform(it) }
                    )
                    else -> EditorExportShareCard(
                        selectedQuality = selectedQuality,
                        hasSelectedClip = true,
                        hasTimelineClips = clips.isNotEmpty(),
                        isBusy = isBusy,
                        onSelectQuality = { editorViewModel.setExportQuality(it) },
                        onExportClip = { exportClipWavLauncher.launch("${selectedClip.name}_edited.wav") },
                        onExportTimeline = { exportTimelineWavLauncher.launch("${currentProject?.name ?: "osei_timeline"}.wav") },
                        onShareClip = {
                            editorViewModel.shareRenderedAudio(exportSingleClipOnly = true) { intent ->
                                context.startActivity(intent)
                            }
                        },
                        onShareTimeline = {
                            editorViewModel.shareRenderedAudio(exportSingleClipOnly = false) { intent ->
                                context.startActivity(intent)
                            }
                        }
                    )
                }
            }
        } else if (activeControlTab == 2) {
            item {
                EditorExportShareCard(
                    selectedQuality = selectedQuality,
                    hasSelectedClip = false,
                    hasTimelineClips = false,
                    isBusy = isBusy,
                    onSelectQuality = { editorViewModel.setExportQuality(it) },
                    onExportClip = {},
                    onExportTimeline = {},
                    onShareClip = {},
                    onShareTimeline = {}
                )
            }
        }
    }

    if (showProjectDialog) {
        AlertDialog(
            onDismissRequest = { showProjectDialog = false },
            containerColor = CardSurface,
            title = { Text("Saved Editor Projects", color = TextWhite) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    allProjects.forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                editorViewModel.selectProject(p.project.id)
                                showProjectDialog = false
                            }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.project.name, color = TextWhite, fontWeight = FontWeight.Medium)
                                Text("${p.rawClips.size} clip(s)", color = TextGrey, fontSize = 11.sp)
                            }
                            if (allProjects.size > 1) {
                                IconButton(onClick = { editorViewModel.deleteProject(p.project.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF6B6B))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProjectDialog = false }) { Text("Close", color = NeonBlue) }
            }
        )
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            containerColor = CardSurface,
            title = { Text("New Editor Project", color = TextWhite) },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("Project Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editorViewModel.createNewProject(newProjectName)
                    showNewProjectDialog = false
                }) { Text("Create", color = NeonBlue) }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text("Cancel", color = TextGrey) }
            }
        )
    }
}

@Composable
private fun EditorExportShareCard(
    selectedQuality: ExportQualityPreset,
    hasSelectedClip: Boolean,
    hasTimelineClips: Boolean,
    isBusy: Boolean,
    onSelectQuality: (ExportQualityPreset) -> Unit,
    onExportClip: () -> Unit,
    onExportTimeline: () -> Unit,
    onShareClip: () -> Unit,
    onShareTimeline: () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("EXPORT & SHARE (16-BIT PCM WAV)", color = NeonBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            ExportQualityPreset.entries.forEach { preset ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectQuality(preset) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedQuality == preset,
                        onClick = { onSelectQuality(preset) },
                        colors = RadioButtonDefaults.colors(selectedColor = NeonBlue)
                    )
                    Text(preset.label, color = TextWhite, fontSize = 13.sp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onExportClip,
                    enabled = hasSelectedClip && !isBusy,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_export_clip_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                ) {
                    Text("Save Clip WAV", color = TextWhite, fontSize = 12.sp)
                }
                Button(
                    onClick = onExportTimeline,
                    enabled = hasTimelineClips && !isBusy,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_export_timeline_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                ) {
                    Text("Save Timeline WAV", color = StudioBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onShareClip,
                    enabled = hasSelectedClip && !isBusy,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_share_clip_btn")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share Clip", color = TextWhite, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onShareTimeline,
                    enabled = hasTimelineClips && !isBusy,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("editor_share_timeline_btn")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share Timeline", color = TextWhite, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun formatSeconds(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSecs = safe / 1000L
    val mins = totalSecs / 60L
    val secs = totalSecs % 60L
    val tenths = (safe % 1000L) / 100L
    return String.format("%d:%02d.%d", mins, secs, tenths)
}
