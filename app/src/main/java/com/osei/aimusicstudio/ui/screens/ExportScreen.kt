package com.osei.aimusicstudio.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.osei.aimusicstudio.audio.ExportQualityPreset
import com.osei.aimusicstudio.audio.MixerTrackMixSpec
import com.osei.aimusicstudio.audio.TrackGainCalculator
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.MixerViewModel

@Composable
fun ExportScreen(
    mixerViewModel: MixerViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val currentMixerProject by mixerViewModel.currentMixerProject.collectAsStateWithLifecycle()
    val selectedQuality by mixerViewModel.selectedExportQuality.collectAsStateWithLifecycle()
    val isExporting by mixerViewModel.isExporting.collectAsStateWithLifecycle()
    val exportProgress by mixerViewModel.exportProgress.collectAsStateWithLifecycle()
    val statusMessage by mixerViewModel.statusMessage.collectAsStateWithLifecycle()
    val uiError by mixerViewModel.uiError.collectAsStateWithLifecycle()

    var pendingTrackIdForExport by remember { mutableStateOf<Long?>(null) }

    val exportWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destUri: Uri? ->
        if (destUri != null) {
            mixerViewModel.exportMixToWavUri(destUri)
        }
    }

    val exportTrackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destUri: Uri? ->
        val tid = pendingTrackIdForExport
        if (destUri != null && tid != null) {
            mixerViewModel.exportSingleTrackToWavUri(tid, destUri)
        }
        pendingTrackIdForExport = null
    }

    val project = currentMixerProject?.project
    val tracks = currentMixerProject?.tracks.orEmpty()
    val gains = remember(tracks, project?.masterVolume) {
        val specs = tracks.map {
            MixerTrackMixSpec(
                trackId = it.id,
                name = it.name,
                audioUri = it.audioUri,
                durationMs = it.durationMs,
                volume = it.volume,
                pan = it.pan,
                isMuted = it.isMuted,
                isSolo = it.isSolo,
                isEnabled = it.isEnabled,
                offsetMs = it.offsetMs
            )
        }
        TrackGainCalculator.computeAllGains(specs, project?.masterVolume ?: 0.85f)
    }
    val audibleCount = gains.values.count { it.isAudible }

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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Export & Share (WAV)",
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
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = project?.name ?: "No Active Mix",
                                color = TextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${selectedQuality.sampleRateHz} Hz • 16-bit Stereo PCM WAV • $audibleCount audible track(s)",
                                color = NeonBlue,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        text = "Renders the combined multitrack signal chunk-by-chunk with each track's volume, pan, mute, solo, enable, offset, and master volume settings. Exports 16-bit PCM WAV files locally or shares via Android Share sheet.",
                        color = TextGrey,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // Sample-Rate Quality Presets
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("WAV Audio Quality Preset", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    ExportQualityPreset.entries.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { mixerViewModel.setExportQuality(preset) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedQuality == preset,
                                onClick = { mixerViewModel.setExportQuality(preset) },
                                colors = RadioButtonDefaults.colors(selectedColor = NeonBlue)
                            )
                            Text(text = preset.label, color = TextWhite, fontSize = 13.sp)
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "Track Mix, Export & Share", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    tracks.forEach { t ->
                        val g = gains[t.id]
                        val hasAudio = !t.audioUri.isNullOrBlank()
                        val statusLabel = when {
                            !hasAudio -> "Empty"
                            !t.isEnabled -> "Disabled"
                            g?.isAudible == true -> "Active (Vol ${(t.volume * 100).toInt()}%)"
                            else -> "Muted / Excluded by Solo"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = t.name, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = statusLabel,
                                    color = if (g?.isAudible == true) NeonBlue else TextGrey,
                                    fontSize = 12.sp
                                )
                            }
                            if (hasAudio) {
                                Row {
                                    TextButton(
                                        onClick = {
                                            pendingTrackIdForExport = t.id
                                            val safeTrack = t.name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
                                            exportTrackLauncher.launch("${safeTrack}.wav")
                                        },
                                        enabled = !isExporting && t.volume > 0f,
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    ) {
                                        Text("Save", color = NeonPurple, fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            mixerViewModel.shareSingleTrackWav(t.id) { intent ->
                                                context.startActivity(intent)
                                            }
                                        },
                                        enabled = !isExporting && t.volume > 0f,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share ${t.name}", tint = NeonBlue)
                                    }
                                }
                            }
                        }
                    }
                }
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
                    Text("Rendering WAV: ${(exportProgress * 100).toInt()}%", color = NeonBlue, fontSize = 12.sp)
                    TextButton(onClick = { mixerViewModel.cancelExport() }) {
                        Text("Cancel", color = Color(0xFFFF6B6B))
                    }
                }
            }

            uiError?.let { err ->
                Text(text = err, color = Color(0xFFFF6B6B), fontSize = 13.sp)
            }
            statusMessage?.let { msg ->
                Text(text = msg, color = NeonBlue, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val safeName = (project?.name ?: "osei_mix").replace(Regex("[^A-Za-z0-9_-]"), "_")
                    exportWavLauncher.launch("${safeName}_mix.wav")
                },
                enabled = !isExporting && audibleCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("export_screen_wav_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (audibleCount > 0) "Save Mixed WAV (${selectedQuality.sampleRateHz} Hz)" else "Load & Enable Audible Tracks First",
                    color = StudioBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            OutlinedButton(
                onClick = {
                    mixerViewModel.shareMixWav { intent ->
                        context.startActivity(intent)
                    }
                },
                enabled = !isExporting && audibleCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("export_screen_share_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = NeonPurple)
                Spacer(Modifier.width(8.dp))
                Text("Share Mixed WAV via Android", color = TextWhite, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
