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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.ai.MemoryProfiler
import com.osei.aimusicstudio.ai.ModelStatus
import com.osei.aimusicstudio.ai.SeparationModelCatalog
import com.osei.aimusicstudio.ai.StemMode
import com.osei.aimusicstudio.audio.AudioPlayerManager
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.launch
import java.io.File

enum class VocalActionFocus(val label: String) {
    EXTRACT_VOCALS("Extract Vocals"),
    REMOVE_VOCALS("Remove Vocals (Instrumental)")
}

private fun resolveExistingFileFromUri(uriStr: String?): File? {
    if (uriStr.isNullOrBlank()) return null
    val parsed = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
    val file = when {
        parsed.scheme == "file" && parsed.path != null -> File(parsed.path!!)
        uriStr.startsWith("/") -> File(uriStr)
        else -> null
    } ?: return null
    return file.takeIf { it.exists() && it.length() > 44L }
}

@Composable
fun VocalSeparationScreen(
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

    val selectedModel by engine.selectedModel.collectAsStateWithLifecycle()
    val modelSha256 by engine.modelSha256.collectAsStateWithLifecycle()

    val isPlaying by stemPlayer.isPlaying.collectAsStateWithLifecycle()
    val stemVolume by stemPlayer.volume.collectAsStateWithLifecycle()
    val playbackError by stemPlayer.playbackError.collectAsStateWithLifecycle()

    val vocalInstModels = remember {
        SeparationModelCatalog.vocalInstrumentalModels()
    }
    val activeTwoStemModel = remember(selectedModel, vocalInstModels) {
        vocalInstModels.firstOrNull { it.id == selectedModel.id }
            ?: SeparationModelCatalog.defaultVocalInstrumentalModel()
    }
    LaunchedEffect(Unit) {
        if (selectedModel.stemMode != StemMode.TWO_STEM_VOCAL_INST ||
            selectedModel.id == "mdx_kim_vocal_2_onnx"
        ) {
            engine.selectModel(SeparationModelCatalog.defaultVocalInstrumentalModel())
        } else {
            engine.refreshMemorySnapshot()
        }
    }

    val activeProject: MusicProjectEntity? = selectedProject ?: projects.firstOrNull()
    val memoryConfig = remember(memorySnapshot) { MemoryProfiler.recommendConfig(memorySnapshot) }

    var actionFocus by remember { mutableStateOf(VocalActionFocus.EXTRACT_VOCALS) }
    var sessionStems by remember(activeProject?.id) { mutableStateOf<Map<String, File>>(emptyMap()) }
    var activePlayingStem by remember { mutableStateOf<String?>(null) }
    var stemToExport by remember { mutableStateOf<Pair<String, File>?>(null) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var showConsentDialog by remember { mutableStateOf(false) }
    var showLibraryPicker by remember { mutableStateOf(false) }
    var isImportingSong by remember { mutableStateOf(false) }

    val vocalsFile: File? = remember(sessionStems, activeProject?.vocalUri) {
        sessionStems["vocals"]?.takeIf { it.exists() && it.length() > 44L }
            ?: resolveExistingFileFromUri(activeProject?.vocalUri)
    }
    val instrumentalFile: File? = remember(sessionStems, activeProject?.instrumentalUri) {
        sessionStems["instrumental"]?.takeIf { it.exists() && it.length() > 44L }
            ?: resolveExistingFileFromUri(activeProject?.instrumentalUri)
    }

    val importSongLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            stemPlayer.stop()
            isImportingSong = true
            actionFeedback = null
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importAudioUriAsProject(uri) { res ->
                isImportingSong = false
                actionFeedback = res.fold(
                    onSuccess = { "Imported '${it.title}' to Library" },
                    onFailure = { "Import failed: ${it.localizedMessage ?: "Unsupported audio"}" }
                )
            }
        }
    }

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
                actionFeedback = res.fold(
                    onSuccess = { "Exported ${target.first}.wav successfully" },
                    onFailure = { "Export failed: ${it.localizedMessage}" }
                )
            }
        }
        stemToExport = null
    }

    fun triggerSeparation(mode: VocalActionFocus) {
        val proj = activeProject ?: return
        actionFocus = mode
        actionFeedback = null
        coroutineScope.launch {
            val res = engine.separateStems(proj.audioUri, proj.id)
            res.onSuccess { sepResult ->
                sessionStems = sepResult.stemFiles
                viewModel.saveSeparatedStemsToProject(proj, sepResult.stemFiles)
                val vStats = sepResult.stemStats["vocals"]
                val iStats = sepResult.stemStats["instrumental"]
                actionFeedback = if (mode == VocalActionFocus.EXTRACT_VOCALS) {
                    "AI vocals isolated (${sepResult.benchmark.chunksProcessed} chunks • Vocals ${vStats?.rmsDbfs?.toInt() ?: 0} dBFS)."
                } else {
                    "AI instrumental separated (${sepResult.benchmark.chunksProcessed} chunks • Inst ${iStats?.rmsDbfs?.toInt() ?: 0} dBFS)."
                }
            }.onFailure { err ->
                actionFeedback = "AI separation did not run: ${err.localizedMessage ?: "Model unavailable"}"
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
                    stemPlayer.stop()
                    onBack()
                },
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Mic, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Vocal Separation",
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        // 1. Song Selection & Import Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = CardSurface
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = "Source Song", color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    text = activeProject?.let { "${it.title} • ${formatDuration(it.durationMs)}" }
                        ?: "No song selected. Import an audio track to begin.",
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("vocal_sep_selected_song")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { importSongLauncher.launch(arrayOf("audio/*")) },
                        enabled = !isImportingSong && modelStatus != ModelStatus.PROCESSING,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("vocal_sep_import_song_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null, tint = StudioBackground, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Song", color = StudioBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { showLibraryPicker = true },
                        enabled = projects.isNotEmpty() && modelStatus != ModelStatus.PROCESSING,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("vocal_sep_pick_library_btn"),
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

        // 2. Genuine ONNX AI Model Card (4 GB RAM Safe, Vocals vs Instrumental)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = CardSurface
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Vocals / Instrumental Model Selector",
                    color = NeonBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    vocalInstModels.filter { it.id != "local_custom_onnx" }.forEach { specOption ->
                        val selected = activeTwoStemModel.id == specOption.id
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (modelStatus != ModelStatus.PROCESSING && modelStatus != ModelStatus.DOWNLOADING) {
                                    engine.selectModel(specOption)
                                }
                            },
                            label = {
                                Text(
                                    text = when (specOption.id) {
                                        "uvr_mdx_inst_hq_4" -> "Inst_HQ_4"
                                        "uvr_mdx_inst_hq_3" -> "Inst_HQ_3"
                                        "uvr_mdx_main" -> "MDX_Main"
                                        else -> specOption.fileName.substringBefore(".")
                                    },
                                    fontSize = 11.sp
                                )
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("model_chip_${specOption.id}")
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeTwoStemModel.displayName,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vocal_sep_model_name")
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (modelStatus == ModelStatus.READY || modelStatus == ModelStatus.COMPLETED) {
                            NeonBlue.copy(alpha = 0.2f)
                        } else {
                            Color(0xFFFFB703).copy(alpha = 0.2f)
                        },
                        modifier = Modifier.testTag("vocal_sep_model_badge")
                    ) {
                        Text(
                            text = modelStatus.name,
                            color = if (modelStatus == ModelStatus.READY || modelStatus == ModelStatus.COMPLETED) NeonBlue else Color(0xFFFFB703),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Text(
                    text = "${memoryConfig.tier.label} • ${activeTwoStemModel.quantization} • Est. ~${activeTwoStemModel.estimatedPeakRamMb} MB RAM",
                    color = NeonPurple,
                    fontSize = 11.sp
                )
                Text(
                    text = activeTwoStemModel.tensorSpecSummary,
                    color = TextGrey,
                    fontSize = 11.sp
                )
                activeTwoStemModel.expectedSha256?.let { expectedSha ->
                    Text(
                        text = if (modelSha256 != null) {
                            "SHA-256 Verified: ${modelSha256!!.take(16)}..."
                        } else {
                            "Expected SHA-256: ${expectedSha.take(16)}..."
                        },
                        color = NeonBlue,
                        fontSize = 10.sp
                    )
                }
                if (activeTwoStemModel.attributionNotice.isNotBlank()) {
                    Text(
                        text = activeTwoStemModel.attributionNotice,
                        color = TextGrey.copy(alpha = 0.85f),
                        fontSize = 10.sp
                    )
                }
                Text(text = statusMessage, color = TextGrey, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { importLocalOnnxLauncher.launch(arrayOf("*/*")) },
                        enabled = modelStatus != ModelStatus.PROCESSING && modelStatus != ModelStatus.DOWNLOADING,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Import .onnx", color = TextWhite, fontSize = 12.sp)
                    }
                    if (modelStatus == ModelStatus.NOT_DOWNLOADED || modelStatus == ModelStatus.ERROR) {
                        Button(
                            onClick = { showConsentDialog = true },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                        ) {
                            Text("Download AI Weights (~${activeTwoStemModel.approximateDiskMb} MB)", color = TextWhite, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 3. Extract Vocals & Remove Vocals Actions
        val canRunAi = activeProject != null &&
            (modelStatus == ModelStatus.READY || modelStatus == ModelStatus.COMPLETED)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { triggerSeparation(VocalActionFocus.EXTRACT_VOCALS) },
                enabled = canRunAi,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 50.dp)
                    .testTag("vocal_sep_extract_vocals_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonBlue,
                    disabledContainerColor = CardSurface,
                    disabledContentColor = TextGrey
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Extract Vocals", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Button(
                onClick = { triggerSeparation(VocalActionFocus.REMOVE_VOCALS) },
                enabled = canRunAi,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 50.dp)
                    .testTag("vocal_sep_remove_vocals_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPurple,
                    disabledContainerColor = CardSurface,
                    disabledContentColor = TextGrey
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.MicOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Remove Vocals", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (!canRunAi && modelStatus != ModelStatus.PROCESSING && modelStatus != ModelStatus.DOWNLOADING) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vocal_sep_requires_model_notice"),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2B1C2E),
                border = BorderStroke(1.dp, Color(0xFFFFB703).copy(alpha = 0.6f))
            ) {
                Text(
                    text = if (activeProject == null) {
                        "Import or select a song first. AI vocal separation uses genuine ONNX Runtime inference and never creates fake stems."
                    } else {
                        "Genuine AI separation requires verified ONNX weights (${activeTwoStemModel.fileName}, ~${activeTwoStemModel.approximateDiskMb} MB) or a local .onnx file. No fake stems will be created."
                    },
                    color = TextGrey,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (modelStatus == ModelStatus.PROCESSING || modelStatus == ModelStatus.DOWNLOADING) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = CardSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (modelStatus == ModelStatus.DOWNLOADING) {
                            "Downloading Verified Weights..."
                        } else {
                            "Running ${actionFocus.label} (ONNX CPU)..."
                        },
                        color = NeonBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
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
                        Text("${(progress * 100).toInt()}%", color = TextGrey, fontSize = 12.sp)
                        TextButton(onClick = { engine.cancelCurrentOperation() }) {
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
                    Text(text = err, color = TextWhite, fontSize = 12.sp)
                }
            }
        }

        playbackError?.let { pErr ->
            Text(text = "Playback error: $pErr", color = Color(0xFFFF6B6B), fontSize = 12.sp)
        }

        actionFeedback?.let { msg ->
            Text(
                text = msg,
                color = NeonBlue,
                fontSize = 12.sp,
                modifier = Modifier.testTag("vocal_sep_feedback_text")
            )
        }

        lastBenchmark?.let { bm ->
            Text(
                text = "Last Run • Peak RAM: ${bm.peakUsedMb} MB • Chunks: ${bm.chunksProcessed} • ${bm.totalDurationMs / 1000.0}s",
                color = TextGrey,
                fontSize = 11.sp
            )
        }

        // 4. Playback Volume Control
        if (vocalsFile != null || instrumentalFile != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Preview Volume", color = TextGrey, fontSize = 12.sp)
                Slider(
                    value = stemVolume,
                    onValueChange = { stemPlayer.setVolume(it) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 5. Separated Vocals Output Card (Playback, Save, Export & Share)
        StemOutputActionCard(
            title = "Vocals Stem",
            accentColor = NeonBlue,
            stemFile = vocalsFile,
            isPlaying = activePlayingStem == "vocals" && isPlaying,
            playTag = "vocal_sep_play_vocals_btn",
            saveTag = "vocal_sep_save_vocals_btn",
            exportTag = "vocal_sep_export_vocals_btn",
            shareTag = "vocal_sep_share_vocals_btn",
            saveLabel = "Save Vocals",
            onTogglePlay = { file ->
                if (activePlayingStem == "vocals") {
                    stemPlayer.togglePlayPause()
                } else {
                    activePlayingStem = "vocals"
                    stemPlayer.loadAndPlay(Uri.fromFile(file).toString())
                }
            },
            onSave = { file ->
                val proj = activeProject ?: return@StemOutputActionCard
                viewModel.saveSeparatedStemPermanently(proj, "vocals", file) { res ->
                    actionFeedback = res.fold(
                        onSuccess = { "Saved Vocals stem to '${it.title}' library entry" },
                        onFailure = { "Save Vocals failed: ${it.localizedMessage}" }
                    )
                }
            },
            onExport = { file ->
                stemToExport = "vocals" to file
                val baseName = (activeProject?.title ?: "track").replace(Regex("[^A-Za-z0-9_-]"), "_")
                exportWavLauncher.launch("${baseName}_vocals.wav")
            },
            onShare = { file ->
                val baseName = activeProject?.title ?: "Osei Track"
                viewModel.prepareShareIntentForWav(file, "${baseName}_Vocals") { res ->
                    res.onSuccess { intent ->
                        context.startActivity(intent)
                    }.onFailure { err ->
                        actionFeedback = "Share failed: ${err.localizedMessage}"
                    }
                }
            }
        )

        // 6. Separated Instrumental Output Card (Playback, Save, Export & Share)
        StemOutputActionCard(
            title = "Instrumental Stem",
            accentColor = NeonPurple,
            stemFile = instrumentalFile,
            isPlaying = activePlayingStem == "instrumental" && isPlaying,
            playTag = "vocal_sep_play_instrumental_btn",
            saveTag = "vocal_sep_save_instrumental_btn",
            exportTag = "vocal_sep_export_instrumental_btn",
            shareTag = "vocal_sep_share_instrumental_btn",
            saveLabel = "Save Instrumental",
            onTogglePlay = { file ->
                if (activePlayingStem == "instrumental") {
                    stemPlayer.togglePlayPause()
                } else {
                    activePlayingStem = "instrumental"
                    stemPlayer.loadAndPlay(Uri.fromFile(file).toString())
                }
            },
            onSave = { file ->
                val proj = activeProject ?: return@StemOutputActionCard
                viewModel.saveSeparatedStemPermanently(proj, "instrumental", file) { res ->
                    actionFeedback = res.fold(
                        onSuccess = { "Saved Instrumental stem to '${it.title}' library entry" },
                        onFailure = { "Save Instrumental failed: ${it.localizedMessage}" }
                    )
                }
            },
            onExport = { file ->
                stemToExport = "instrumental" to file
                val baseName = (activeProject?.title ?: "track").replace(Regex("[^A-Za-z0-9_-]"), "_")
                exportWavLauncher.launch("${baseName}_instrumental.wav")
            },
            onShare = { file ->
                val baseName = activeProject?.title ?: "Osei Track"
                viewModel.prepareShareIntentForWav(file, "${baseName}_Instrumental") { res ->
                    res.onSuccess { intent ->
                        context.startActivity(intent)
                    }.onFailure { err ->
                        actionFeedback = "Share failed: ${err.localizedMessage}"
                    }
                }
            }
        )
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Download Open-Source ONNX Weights?") },
            text = {
                Text(
                    "Model: ${activeTwoStemModel.displayName}\n" +
                        "File: ${activeTwoStemModel.fileName}\n" +
                        "License: ${activeTwoStemModel.weightLicense}\n" +
                        "Attribution: ${activeTwoStemModel.attributionNotice}\n" +
                        "Size: ~${activeTwoStemModel.approximateDiskMb} MB\n" +
                        "SHA-256: ${activeTwoStemModel.expectedSha256?.take(20)}...\n\n" +
                        "All separation executes offline on your device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsentDialog = false
                    coroutineScope.launch { engine.downloadModelWithConsent() }
                }) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) { Text("Cancel") }
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
                                    stemPlayer.stop()
                                    viewModel.selectProjectForTools(proj)
                                    showLibraryPicker = false
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = DarkNavy
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(proj.title, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${proj.artist} • ${formatDuration(proj.durationMs)}", color = TextGrey, fontSize = 11.sp)
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

@Composable
private fun StemOutputActionCard(
    title: String,
    accentColor: Color,
    stemFile: File?,
    isPlaying: Boolean,
    playTag: String,
    saveTag: String,
    exportTag: String,
    shareTag: String,
    saveLabel: String,
    onTogglePlay: (File) -> Unit,
    onSave: (File) -> Unit,
    onExport: (File) -> Unit,
    onShare: (File) -> Unit
) {
    val ready = stemFile != null && stemFile.exists() && stemFile.length() > 44L

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = if (ready) BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = if (stemFile != null && stemFile.exists() && stemFile.length() > 44L) {
                        "Ready (${stemFile.length() / 1024} KB)"
                    } else {
                        "Not Separated Yet"
                    },
                    color = if (ready) accentColor else TextGrey,
                    fontSize = 12.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { stemFile?.let(onTogglePlay) },
                    enabled = ready,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(playTag),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        disabledContainerColor = DarkNavy,
                        disabledContentColor = TextGrey
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause $title" else "Play $title",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isPlaying) "Pause" else "Play", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { stemFile?.let(onSave) },
                    enabled = ready,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(saveTag),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(saveLabel, fontSize = 11.sp, maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { stemFile?.let(onExport) },
                    enabled = ready,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(exportTag),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export WAV", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { stemFile?.let(onShare) },
                    enabled = ready,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(shareTag),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", fontSize = 12.sp)
                }
            }
        }
    }
}
