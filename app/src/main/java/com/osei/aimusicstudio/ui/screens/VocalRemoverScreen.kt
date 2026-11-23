package com.osei.aimusicstudio.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import com.osei.aimusicstudio.ai.MemoryProfiler
import com.osei.aimusicstudio.ai.ModelStatus
import com.osei.aimusicstudio.ai.SeparationModelCatalog
import com.osei.aimusicstudio.ai.StemMode
import com.osei.aimusicstudio.audio.AudioPlayerManager
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun VocalRemoverScreen(
    viewModel: StudioViewModel,
    onBack: () -> Unit
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
    val modelStatus by engine.modelStatus.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val statusMessage by engine.statusMessage.collectAsStateWithLifecycle()
    val errorMessage by engine.errorMessage.collectAsStateWithLifecycle()
    val memorySnapshot by engine.memorySnapshot.collectAsStateWithLifecycle()
    val lastBenchmark by engine.lastBenchmark.collectAsStateWithLifecycle()

    val isPlaying by stemPlayer.isPlaying.collectAsStateWithLifecycle()
    val stemVolume by stemPlayer.volume.collectAsStateWithLifecycle()

    val twoStemModel = remember {
        SeparationModelCatalog.defaultVocalInstrumentalModel()
    }
    LaunchedEffect(Unit) {
        engine.selectModel(twoStemModel)
        engine.refreshMemorySnapshot()
    }

    val activeProject = selectedProject ?: projects.firstOrNull()
    val memoryConfig = remember(memorySnapshot) { MemoryProfiler.recommendConfig(memorySnapshot) }

    var showConsentDialog by remember { mutableStateOf(false) }
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
                    onSuccess = { "Exported ${target.first}.wav successfully" },
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                text = "AI Vocal Remover",
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Device Memory & Low-RAM Optimization Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = CardSurface
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = memoryConfig.tier.label, color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Text(
                    text = "RAM: ${memorySnapshot.deviceAvailableMb} MB free / ${memorySnapshot.deviceTotalMb} MB total • Process: ${memorySnapshot.combinedProcessUsedMb} MB",
                    color = TextGrey,
                    fontSize = 12.sp
                )
                Text(
                    text = "Chunked CPU Mode: ${memoryConfig.chunkSeconds}s chunks • ${memoryConfig.overlapSeconds}s Hann overlap • ${memoryConfig.intraOpThreads} threads (non-real-time)",
                    color = TextGrey,
                    fontSize = 12.sp
                )
            }
        }

        // Model Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = CardSurface
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = twoStemModel.displayName, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = "${twoStemModel.architecture} • ${twoStemModel.codeLicense}", color = NeonPurple, fontSize = 12.sp)
                Text(text = twoStemModel.notes, color = TextGrey, fontSize = 12.sp)
                if (twoStemModel.attributionNotice.isNotBlank()) {
                    Text(text = twoStemModel.attributionNotice, color = TextGrey.copy(alpha = 0.85f), fontSize = 10.sp)
                }
                Text(
                    text = "Track: ${activeProject?.title ?: "No imported track selected"}",
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(text = statusMessage, color = NeonBlue, fontSize = 12.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { importLocalOnnxLauncher.launch(arrayOf("*/*")) },
                        enabled = modelStatus != ModelStatus.PROCESSING && modelStatus != ModelStatus.DOWNLOADING
                    ) {
                        Text(text = "Import Local .onnx", color = TextWhite, fontSize = 12.sp)
                    }
                    if (modelStatus == ModelStatus.NOT_DOWNLOADED || modelStatus == ModelStatus.ERROR) {
                        Button(
                            onClick = { showConsentDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                        ) {
                            Text(text = "Download Model (~${twoStemModel.approximateDiskMb} MB)", color = TextWhite, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (modelStatus == ModelStatus.DOWNLOADING || modelStatus == ModelStatus.PROCESSING) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (modelStatus == ModelStatus.DOWNLOADING) "Downloading Verified Weights..." else "Running Chunked CPU Inference...",
                        color = NeonBlue,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonPurple,
                        trackColor = TextGrey.copy(alpha = 0.3f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "${(progress * 100).toInt()}%", color = TextGrey, fontSize = 12.sp)
                        TextButton(onClick = { engine.cancelCurrentOperation() }) {
                            Text(text = "Cancel", color = Color(0xFFFF6B6B))
                        }
                    }
                }
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
                    Text(text = errorMessage!!, color = TextWhite, fontSize = 13.sp)
                }
            }
        }

        // Real Output Stems Preview & Export
        if (producedStems.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "Separated Stems", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    lastBenchmark?.let { bm ->
                        Text(
                            text = "Peak RAM: ${bm.peakUsedMb} MB • Chunks: ${bm.chunksProcessed} • Time: ${bm.totalDurationMs / 1000.0}s",
                            color = NeonBlue,
                            fontSize = 12.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Preview Volume", color = TextGrey, fontSize = 12.sp)
                        Slider(
                            value = stemVolume,
                            onValueChange = { stemPlayer.setVolume(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    producedStems.forEach { (stemName, file) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${stemName.replaceFirstChar { it.uppercase() }} (${file.length() / 1024} KB)",
                                color = TextWhite,
                                fontSize = 14.sp
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

                    exportStatus?.let {
                        Text(text = it, color = NeonBlue, fontSize = 12.sp)
                    }
                }
            }
        }

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
            enabled = activeProject != null &&
                modelStatus == ModelStatus.READY &&
                modelStatus != ModelStatus.PROCESSING,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("start_vocal_separation_button"),
            colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = if (activeProject == null) "Import a Song First" else "Separate Vocals & Instrumental",
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Download Open-Source ONNX Weights?") },
            text = {
                Text(
                    "Model: ${twoStemModel.displayName}\n" +
                        "File: ${twoStemModel.fileName}\n" +
                        "License: ${twoStemModel.weightLicense}\n" +
                        "Attribution: ${twoStemModel.attributionNotice}\n" +
                        "Size: ~${twoStemModel.approximateDiskMb} MB\n\n" +
                        "All audio remains offline on your device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsentDialog = false
                    coroutineScope.launch {
                        engine.downloadModelWithConsent()
                    }
                }) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
