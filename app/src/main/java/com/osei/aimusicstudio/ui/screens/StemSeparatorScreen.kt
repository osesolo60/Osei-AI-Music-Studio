package com.osei.aimusicstudio.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.ai.HardwareCompatibility
import com.osei.aimusicstudio.ai.MemoryProfiler
import com.osei.aimusicstudio.ai.ModelAvailability
import com.osei.aimusicstudio.ai.ModelStatus
import com.osei.aimusicstudio.ai.SeparationModelCatalog
import com.osei.aimusicstudio.ai.StemMode
import com.osei.aimusicstudio.audio.AudioPlayerManager
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun StemSeparatorScreen(
    viewModel: StudioViewModel,
    onBack: () -> Unit,
    onOpenMixer: () -> Unit = {}
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = viewModel.stemSeparatorEngine
    val stemPlayer = remember { AudioPlayerManager(context) }

    DisposableEffect(Unit) {
        onDispose {
            stemPlayer.release()
        }
    }

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val selectedProject by viewModel.selectedProjectForTools.collectAsStateWithLifecycle()
    val selectedModel by engine.selectedModel.collectAsStateWithLifecycle()
    val modelStatus by engine.modelStatus.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val statusMessage by engine.statusMessage.collectAsStateWithLifecycle()
    val errorMessage by engine.errorMessage.collectAsStateWithLifecycle()
    val memorySnapshot by engine.memorySnapshot.collectAsStateWithLifecycle()
    val lastBenchmark by engine.lastBenchmark.collectAsStateWithLifecycle()

    val isPlaying by stemPlayer.isPlaying.collectAsStateWithLifecycle()

    val fourStemModels = remember {
        SeparationModelCatalog.models.filter { it.stemMode == StemMode.FOUR_STEM }
    }
    val sixStemModels = remember {
        SeparationModelCatalog.models.filter {
            it.stemMode == StemMode.SIX_STEM_INSTRUMENTS || it.stemMode == StemMode.SIX_STEM_HTDEMUCS
        }
    }

    var showSixStemTab by remember { mutableStateOf(false) }
    val displayedModels = if (showSixStemTab) sixStemModels else fourStemModels

    LaunchedEffect(showSixStemTab) {
        val targetList = if (showSixStemTab) sixStemModels else fourStemModels
        if (selectedModel !in targetList && targetList.isNotEmpty()) {
            engine.selectModel(targetList.first())
        }
        engine.refreshMemorySnapshot()
    }

    val activeProject = selectedProject ?: projects.firstOrNull()
    val memoryConfig = remember(memorySnapshot) { MemoryProfiler.recommendConfig(memorySnapshot) }

    var producedStems by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
    var activeStemName by remember { mutableStateOf<String?>(null) }
    var stemToExport by remember { mutableStateOf<Pair<String, File>?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val importLocalOnnxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                engine.importLocalOnnxModel(uri)
            }
        }
    }

    val exportWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destUri: Uri? ->
        val target = stemToExport
        if (destUri != null && target != null) {
            coroutineScope.launch {
                val res = viewModel.exportStemToUri(target.second, destUri)
                exportStatus = res.fold(
                    onSuccess = { "Exported ${target.first}.wav" },
                    onFailure = { "Export failed: ${it.localizedMessage}" }
                )
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
            IconButton(onClick = {
                stemPlayer.stop()
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (showSixStemTab) "6-Stem AI Research & Status" else "4-Stem AI Separation",
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Mode Switcher: Preserves 4-Stem + Exposes Honest 6-Stem Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = !showSixStemTab,
                onClick = { showSixStemTab = false },
                label = { Text("4-Stem Models") },
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("tab_4_stem_models")
            )
            FilterChip(
                selected = showSixStemTab,
                onClick = { showSixStemTab = true },
                label = { Text("6-Stem Status") },
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("tab_6_stem_models")
            )
        }

        // Low-RAM Hardware Profile
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = CardSurface
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "${memoryConfig.tier.label} • ${memorySnapshot.deviceAvailableMb} MB free / ${memorySnapshot.deviceTotalMb} MB",
                    color = NeonBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "Streaming Mode: ${memoryConfig.chunkSeconds}s chunks • Temp WAV disk streaming (avoids loading all stems into RAM).",
                    color = TextGrey,
                    fontSize = 12.sp
                )
            }
        }

        if (showSixStemTab) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF2B1C2E),
                border = BorderStroke(1.dp, Color(0xFFFFB703).copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFB703))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "6-Instrument AI Separation: Unavailable",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = "No verified open-source 4 GB-compatible Android ONNX model currently separates the exact 6 requested classes (Drums, Bass, Guitar, Strings, Keys, Other). Researched 6-stem ONNX models (HTDemucs 6s, BS-RoFormer-SW) output Vocals & Piano instead of Strings & Keys and exceed 4 GB RAM, while ZFTurbo's 53-stem BS-RoFormer (~1.37 GB) requires 12-16+ GB RAM.",
                        color = TextGrey,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    OutlinedButton(
                        onClick = onOpenMixer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("open_six_track_mixer_from_ai_btn")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open 6-Track Mixer (Import Audio per Instrument)", color = NeonBlue, fontSize = 12.sp)
                    }
                }
            }
        }

        // Model Selector
        Text(
            text = if (showSixStemTab) "Researched 6-Stem Models & Limitations" else "Evaluated 4-Stem Models",
            color = TextWhite,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        displayedModels.forEach { model ->
            val isSelected = selectedModel.id == model.id
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = modelStatus != ModelStatus.PROCESSING) {
                        engine.selectModel(model)
                    },
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) DarkNavy else CardSurface,
                border = if (isSelected) BorderStroke(1.5.dp, NeonBlue) else null
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = model.displayName,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val badgeColor = when {
                            model.availability == ModelAvailability.UNAVAILABLE -> Color(0xFFFF6B6B)
                            model.compatibility == HardwareCompatibility.RECOMMENDED_4GB -> NeonBlue
                            model.compatibility == HardwareCompatibility.CAUTION_4GB -> Color(0xFFFFB703)
                            else -> Color(0xFFFF6B6B)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (model.availability == ModelAvailability.UNAVAILABLE) {
                                    "Unavailable"
                                } else {
                                    model.compatibility.badge
                                },
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = "${model.quantization} • Est. Peak RAM: ~${model.estimatedPeakRamMb} MB • ${model.codeLicense}",
                        color = NeonPurple,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Outputs: [${model.outputStemOrder.joinToString(", ")}] • ${model.tensorSpecSummary}",
                        color = NeonBlue,
                        fontSize = 11.sp
                    )
                    Text(text = model.notes, color = TextGrey, fontSize = 12.sp)
                    Text(text = "Repo: ${model.sourceRepository}", color = TextGrey.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
        }

        // Status & Local ONNX Import
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = CardSurface
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Track: ${activeProject?.title ?: "No track selected"}", color = TextWhite, fontSize = 13.sp)
                Text(text = statusMessage, color = NeonBlue, fontSize = 12.sp)
                OutlinedButton(
                    onClick = { importLocalOnnxLauncher.launch(arrayOf("*/*")) },
                    enabled = modelStatus != ModelStatus.PROCESSING &&
                        selectedModel.availability != ModelAvailability.UNAVAILABLE
                ) {
                    Text(text = "Load Verified .onnx Model from Storage", color = TextWhite, fontSize = 12.sp)
                }
            }
        }

        if (modelStatus == ModelStatus.PROCESSING || modelStatus == ModelStatus.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = NeonBlue,
                trackColor = TextGrey.copy(alpha = 0.3f)
            )
            TextButton(onClick = { engine.cancelCurrentOperation() }) {
                Text(text = "Cancel Separation", color = Color(0xFFFF6B6B))
            }
        }

        if (errorMessage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF3A1620)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF6B6B))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = errorMessage!!, color = TextWhite, fontSize = 12.sp)
                }
            }
        }

        if (producedStems.isNotEmpty() && !showSixStemTab) {
            lastBenchmark?.let { bm ->
                Text(
                    text = "Benchmark • Peak RAM: ${bm.peakUsedMb} MB • Time: ${bm.totalDurationMs / 1000.0}s • Chunks: ${bm.chunksProcessed}",
                    color = NeonBlue,
                    fontSize = 12.sp
                )
            }
            producedStems.forEach { (stemName, file) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${stemName.replaceFirstChar { it.uppercase() }} (${file.length() / 1024} KB)",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row {
                            val thisPlaying = activeStemName == stemName && isPlaying
                            IconButton(onClick = {
                                if (activeStemName == stemName) {
                                    stemPlayer.togglePlayPause()
                                } else {
                                    activeStemName = stemName
                                    stemPlayer.loadAndPlay(Uri.fromFile(file).toString())
                                }
                            }) {
                                Icon(
                                    if (thisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play $stemName",
                                    tint = NeonPurple
                                )
                            }
                            IconButton(onClick = {
                                stemToExport = Pair(stemName, file)
                                exportWavLauncher.launch("${stemName}.wav")
                            }) {
                                Icon(Icons.Default.FileDownload, contentDescription = "Export $stemName", tint = NeonBlue)
                            }
                        }
                    }
                }
            }
            exportStatus?.let { Text(text = it, color = NeonBlue, fontSize = 12.sp) }
        } else if (showSixStemTab) {
            val statusLabel = if (selectedModel.availability == ModelAvailability.UNAVAILABLE) "Unavailable" else "Blocked on 4GB"
            StemItemRow(title = "Drums Stem (drums.wav)", icon = Icons.Default.Album, badgeText = statusLabel)
            StemItemRow(title = "Bass Stem (bass.wav)", icon = Icons.Default.Speaker, badgeText = statusLabel)
            StemItemRow(title = "Guitar Stem (guitar.wav)", icon = Icons.Default.QueueMusic, badgeText = statusLabel)
            StemItemRow(title = "Strings Stem (strings.wav)", icon = Icons.Default.Waves, badgeText = statusLabel)
            StemItemRow(title = "Keys Stem (keys.wav)", icon = Icons.Default.Piano, badgeText = statusLabel)
            StemItemRow(title = "Other Instruments Stem (other.wav)", icon = Icons.Default.MusicNote, badgeText = statusLabel)
        } else {
            StemItemRow(title = "Vocals Stem", icon = Icons.Default.Mic)
            StemItemRow(title = "Drums Stem", icon = Icons.Default.Album)
            StemItemRow(title = "Bass Stem", icon = Icons.Default.Speaker)
            StemItemRow(title = "Other Instruments Stem", icon = Icons.Default.MusicNote)
        }

        val canRunSeparation = activeProject != null &&
            selectedModel.availability != ModelAvailability.UNAVAILABLE &&
            modelStatus == ModelStatus.READY &&
            modelStatus != ModelStatus.PROCESSING

        Button(
            onClick = {
                val proj = activeProject ?: return@Button
                coroutineScope.launch {
                    val res = engine.separateStems(proj.audioUri, proj.id)
                    res.onSuccess { sepResult ->
                        producedStems = sepResult.stemFiles
                        viewModel.saveSeparatedStemsToProject(proj, sepResult.stemFiles)
                    }
                }
            },
            enabled = canRunSeparation,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag(if (showSixStemTab) "process_6_stems_button" else "process_4_stems_button"),
            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = when {
                    selectedModel.availability == ModelAvailability.UNAVAILABLE -> "6-Stem AI Model Unavailable on 4GB Device"
                    activeProject == null -> "Import a Song First"
                    showSixStemTab -> "Requires 6-8GB+ Verified Model"
                    else -> "Run 4-Stem Separation"
                },
                color = StudioBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
fun StemItemRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeText: String = "Awaiting Model"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CardSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Text(text = title, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = TextGrey.copy(alpha = 0.15f)
            ) {
                Text(text = badgeText, color = TextGrey, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}
