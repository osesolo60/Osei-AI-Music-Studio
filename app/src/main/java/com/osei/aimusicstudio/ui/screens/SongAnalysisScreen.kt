package com.osei.aimusicstudio.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.audio.KeySourceLabel
import com.osei.aimusicstudio.audio.SongAnalysisResult
import com.osei.aimusicstudio.audio.SongAudioAnalyzer
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@Composable
fun SongAnalysisScreen(
    viewModel: StudioViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val selectedProject by viewModel.selectedProjectForTools.collectAsStateWithLifecycle()
    val activeProject: MusicProjectEntity? = selectedProject ?: projects.firstOrNull()

    var isImporting by remember { mutableStateOf(false) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analysisProgress by remember { mutableFloatStateOf(0f) }
    var analysisResult by remember(activeProject?.id) { mutableStateOf<SongAnalysisResult?>(null) }
    var displayedKey by remember(activeProject?.id) {
        mutableStateOf(activeProject?.musicalKey?.takeIf { it.isNotBlank() })
    }
    var displayedBpm by remember(activeProject?.id) {
        mutableStateOf(activeProject?.bpm?.takeIf { it > 0 })
    }
    var keySource by remember(activeProject?.id) {
        mutableStateOf(
            if (!activeProject?.musicalKey.isNullOrBlank()) KeySourceLabel.DETECTED
            else KeySourceLabel.LOW_CONFIDENCE
        )
    }
    var statusNote by remember(activeProject?.id) { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    var showKeyPickerDialog by remember { mutableStateOf(false) }
    var showLibraryPicker by remember { mutableStateOf(false) }

    val cancelFlag = remember { AtomicBoolean(false) }

    val importSongLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            errorMessage = null
            saveFeedback = null
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importAudioUriAsProject(uri) { res ->
                isImporting = false
                res.onSuccess { proj ->
                    statusNote = "Imported '${proj.title}'. Ready for local key & BPM analysis."
                }.onFailure { err ->
                    errorMessage = "Import failed: ${err.localizedMessage ?: "Unsupported audio"}"
                }
            }
        }
    }

    fun runLocalAnalysis(applyKey: Boolean, applyBpm: Boolean) {
        val proj = activeProject ?: return
        cancelFlag.set(false)
        isAnalyzing = true
        analysisProgress = 0f
        errorMessage = null
        saveFeedback = null

        coroutineScope.launch {
            val res = SongAudioAnalyzer.analyzeUri(
                context = context,
                uri = Uri.parse(proj.audioUri),
                isCancelled = { cancelFlag.get() },
                onProgress = { p -> analysisProgress = p }
            )
            isAnalyzing = false
            res.onSuccess { result ->
                analysisResult = result
                if (applyKey) {
                    displayedKey = result.detectedKey
                    keySource = if (result.isKeyReliable) {
                        KeySourceLabel.DETECTED
                    } else {
                        KeySourceLabel.LOW_CONFIDENCE
                    }
                }
                if (applyBpm) {
                    displayedBpm = result.detectedBpm
                }
                statusNote = result.statusNote
            }.onFailure { err ->
                errorMessage = err.localizedMessage ?: "Local audio analysis failed"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    cancelFlag.set(true)
                    onBack()
                },
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Song Analysis",
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        // 1. Import Song Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = CardSurface
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Source Song (Local Streaming DSP)", color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    text = activeProject?.let { "${it.title} • ${formatDuration(it.durationMs)}" }
                        ?: "No song selected. Import audio to analyze key and BPM.",
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("song_analysis_selected_song")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { importSongLauncher.launch(arrayOf("audio/*")) },
                        enabled = !isImporting && !isAnalyzing,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("song_analysis_import_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null, tint = StudioBackground, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Song", color = StudioBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { showLibraryPicker = true },
                        enabled = projects.isNotEmpty() && !isAnalyzing,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("song_analysis_pick_library_btn"),
                        border = BorderStroke(1.dp, NeonPurple),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Library (${projects.size})", color = NeonPurple, fontSize = 13.sp)
                    }
                }
            }
        }

        // 2. Detection Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { runLocalAnalysis(applyKey = true, applyBpm = false) },
                enabled = activeProject != null && !isAnalyzing,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("song_analysis_detect_key_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPurple,
                    disabledContainerColor = CardSurface,
                    disabledContentColor = TextGrey
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Detect Song Key", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
                onClick = { runLocalAnalysis(applyKey = false, applyBpm = true) },
                enabled = activeProject != null && !isAnalyzing,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("song_analysis_detect_bpm_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonBlue,
                    disabledContainerColor = CardSurface,
                    disabledContentColor = TextGrey
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = StudioBackground, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Detect BPM", color = StudioBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        OutlinedButton(
            onClick = { runLocalAnalysis(applyKey = true, applyBpm = true) },
            enabled = activeProject != null && !isAnalyzing,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("song_analysis_run_all_btn"),
            border = BorderStroke(1.dp, NeonBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Analyze Key & BPM Together", color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        if (isAnalyzing) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = CardSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Analyzing local PCM audio chunks...", color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    LinearProgressIndicator(
                        progress = { analysisProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonPurple,
                        trackColor = TextGrey.copy(alpha = 0.3f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${(analysisProgress * 100).toInt()}%", color = TextGrey, fontSize = 12.sp)
                        TextButton(onClick = { cancelFlag.set(true) }) {
                            Text("Cancel", color = Color(0xFFFF6B6B))
                        }
                    }
                }
            }
        }

        errorMessage?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF3A1620)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF6B6B))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(err, color = TextWhite, fontSize = 12.sp)
                }
            }
        }

        // 3. Musical Key & BPM Readout Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface,
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Musical Key", color = TextGrey, fontSize = 12.sp)
                    Text(
                        text = displayedKey ?: "Undetermined",
                        color = if (displayedKey != null) TextWhite else Color(0xFFFFB703),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("song_analysis_key_value")
                    )
                    Text(
                        text = when {
                            keySource == KeySourceLabel.MANUAL -> "Manually Corrected"
                            analysisResult != null -> "Confidence: ${(analysisResult!!.keyConfidence * 100).roundToInt()}%" +
                                if (analysisResult!!.isKeyReliable) " (Supported)" else " (Low)"
                            displayedKey != null -> "Saved in Library"
                            else -> "Run Detect Song Key"
                        },
                        color = if (keySource == KeySourceLabel.MANUAL || analysisResult?.isKeyReliable == true) NeonBlue else TextGrey,
                        fontSize = 11.sp,
                        modifier = Modifier.testTag("song_analysis_key_confidence")
                    )
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface,
                border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Tempo (BPM)", color = TextGrey, fontSize = 12.sp)
                    Text(
                        text = displayedBpm?.let { "$it BPM" } ?: "Undetermined",
                        color = if (displayedBpm != null) TextWhite else Color(0xFFFFB703),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("song_analysis_bpm_value")
                    )
                    Text(
                        text = when {
                            analysisResult != null -> "Confidence: ${(analysisResult!!.bpmConfidence * 100).roundToInt()}%" +
                                if (analysisResult!!.isBpmReliable) " (Supported)" else " (Low)"
                            displayedBpm != null -> "Saved in Library"
                            else -> "Run Detect BPM"
                        },
                        color = if (analysisResult?.isBpmReliable == true) NeonBlue else TextGrey,
                        fontSize = 11.sp,
                        modifier = Modifier.testTag("song_analysis_bpm_confidence")
                    )
                }
            }
        }

        // 4. Chromagram Evidence & Signal Levels
        analysisResult?.let { res ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Pitch Class Energy & Signal Metrics",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Peak: ${String.format("%.1f", res.peakDbfs)} dBFS • RMS: ${String.format("%.1f", res.rmsDbfs)} dBFS • Analyzed: ${formatDuration(res.analyzedDurationMs)}",
                        color = NeonBlue,
                        fontSize = 11.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        res.chromaBins.forEachIndexed { idx, value ->
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((value.coerceIn(0.06f, 1f) * 44f).dp)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (value > 0.65f) NeonBlue else NeonPurple.copy(alpha = 0.7f))
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = SongAudioAnalyzer.PITCH_CLASS_NAMES[idx],
                                    color = TextGrey,
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        statusNote?.let { note ->
            Text(
                text = note,
                color = TextGrey,
                fontSize = 12.sp,
                modifier = Modifier.testTag("song_analysis_status_note")
            )
        }

        // 5. Manual Key Correction & Save Results
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { showKeyPickerDialog = true },
                enabled = activeProject != null && !isAnalyzing,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 50.dp)
                    .testTag("song_analysis_manual_key_btn"),
                border = BorderStroke(1.dp, NeonPurple),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Manual Key Correction", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
                onClick = {
                    val proj = activeProject ?: return@Button
                    viewModel.saveSongAnalysisResults(
                        project = proj,
                        bpm = displayedBpm,
                        musicalKey = displayedKey
                    ) { res ->
                        saveFeedback = res.fold(
                            onSuccess = {
                                val kText = it.musicalKey.ifBlank { "Undetermined" }
                                val bText = if (it.bpm > 0) "${it.bpm} BPM" else "Undetermined BPM"
                                "Saved analysis ($kText • $bText) to '${it.title}'"
                            },
                            onFailure = { "Save failed: ${it.localizedMessage}" }
                        )
                    }
                },
                enabled = activeProject != null && !isAnalyzing && (displayedKey != null || displayedBpm != null),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 50.dp)
                    .testTag("song_analysis_save_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonBlue,
                    disabledContainerColor = CardSurface,
                    disabledContentColor = TextGrey
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Analysis", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        saveFeedback?.let { msg ->
            Text(
                text = msg,
                color = NeonBlue,
                fontSize = 12.sp,
                modifier = Modifier.testTag("song_analysis_save_status")
            )
        }
    }

    if (showKeyPickerDialog) {
        AlertDialog(
            onDismissRequest = { showKeyPickerDialog = false },
            title = { Text("Manual Key Correction") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SongAudioAnalyzer.ALL_MUSICAL_KEYS.forEach { keyOption ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    displayedKey = keyOption
                                    keySource = KeySourceLabel.MANUAL
                                    showKeyPickerDialog = false
                                    saveFeedback = null
                                }
                                .testTag("key_option_${keyOption.replace(" ", "_").replace("#", "sharp")}"),
                            shape = RoundedCornerShape(8.dp),
                            color = if (displayedKey == keyOption) NeonPurple.copy(alpha = 0.25f) else DarkNavy
                        ) {
                            Text(
                                text = keyOption,
                                color = TextWhite,
                                fontSize = 14.sp,
                                fontWeight = if (displayedKey == keyOption) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKeyPickerDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLibraryPicker) {
        AlertDialog(
            onDismissRequest = { showLibraryPicker = false },
            title = { Text("Select Song from Library") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects.take(10).forEach { proj ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectProjectForTools(proj)
                                    showLibraryPicker = false
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = DarkNavy
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(proj.title, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                val meta = buildString {
                                    append(proj.artist)
                                    if (proj.musicalKey.isNotBlank()) append(" • ${proj.musicalKey}")
                                    if (proj.bpm > 0) append(" • ${proj.bpm} BPM")
                                }
                                Text(meta, color = TextGrey, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLibraryPicker = false }) { Text("Close") }
            }
        )
    }
}
