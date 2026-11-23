package com.osei.aimusicstudio.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.ui.components.WaveformCanvas
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel

@Composable
fun ProjectDetailsScreen(
    projectId: Long,
    viewModel: StudioViewModel,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onOpenAudioEditor: () -> Unit = { onNavigate("audio_editor") }
) {
    BackHandler { onBack() }

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val project = projects.firstOrNull { it.id == projectId }
    val currentPlaying by viewModel.currentPlayingProject.collectAsStateWithLifecycle()
    val isPlaying by viewModel.audioPlayerManager.isPlaying.collectAsStateWithLifecycle()
    val currentPos by viewModel.audioPlayerManager.currentPosition.collectAsStateWithLifecycle()
    val totalDur by viewModel.audioPlayerManager.duration.collectAsStateWithLifecycle()
    val playbackError by viewModel.audioPlayerManager.playbackError.collectAsStateWithLifecycle()
    val waveformData by viewModel.waveformData.collectAsStateWithLifecycle()
    val waveformLoading by viewModel.isWaveformLoading.collectAsStateWithLifecycle()
    val waveformError by viewModel.waveformError.collectAsStateWithLifecycle()

    LaunchedEffect(project?.id, project?.audioUri) {
        if (project != null) {
            viewModel.selectProjectForTools(project)
            viewModel.loadWaveformForUri(project.audioUri)
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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = project?.title ?: "Project #$projectId",
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        if (project == null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Text(
                    text = "Project not found in local database.",
                    color = TextGrey,
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = project.title, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${project.artist} • ${String.format("%.2f", project.fileSizeMb)} MB • ${formatDuration(project.durationMs)}",
                        color = TextGrey,
                        fontSize = 13.sp
                    )
                    if (project.musicalKey.isNotBlank() || project.bpm > 0) {
                        val analysisSummary = buildString {
                            if (project.musicalKey.isNotBlank()) append("Key: ${project.musicalKey}")
                            if (project.musicalKey.isNotBlank() && project.bpm > 0) append(" • ")
                            if (project.bpm > 0) append("Tempo: ${project.bpm} BPM")
                        }
                        Text(text = analysisSummary, color = NeonPurple, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }

                    if (waveformLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = NeonBlue,
                            trackColor = TextGrey.copy(alpha = 0.2f)
                        )
                        Text(text = "Decoding PCM samples for waveform...", color = TextGrey, fontSize = 12.sp)
                    } else if (waveformError != null) {
                        Text(text = "Waveform unavailable: $waveformError", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                    } else if (waveformData != null) {
                        val isThisActive = currentPlaying?.id == project.id
                        val dur = (if (isThisActive) totalDur else project.durationMs).coerceAtLeast(1L)
                        val pos = if (isThisActive) currentPos else 0L
                        val progress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)

                        WaveformCanvas(
                            waveformData = waveformData!!,
                            progressFraction = progress,
                            height = 80.dp,
                            onSeekFraction = { frac ->
                                if (!isThisActive) {
                                    viewModel.playProject(project)
                                }
                                viewModel.audioPlayerManager.seekTo((frac * dur).toLong())
                            }
                        )
                        Text(
                            text = "Peak: ${String.format("%.1f", waveformData!!.overallPeakDb)} dBFS • RMS: ${String.format("%.1f", waveformData!!.overallRmsDb)} dBFS",
                            color = NeonBlue,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isThisPlaying = currentPlaying?.id == project.id && isPlaying
                        Button(
                            onClick = {
                                if (currentPlaying?.id == project.id) {
                                    viewModel.togglePlayPause()
                                } else {
                                    viewModel.playProject(project)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                        ) {
                            Icon(
                                if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isThisPlaying) "Pause" else "Play",
                                tint = TextWhite
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = if (isThisPlaying) "Pause" else "Play Track", color = TextWhite)
                        }
                        if (project.stemsSeparated) {
                            AssistChip(
                                onClick = {},
                                label = { Text("Stems Ready", color = NeonBlue) },
                                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonBlue) }
                            )
                        }
                    }

                    if (playbackError != null) {
                        Text(text = playbackError!!, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "Production Tools", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            viewModel.selectProjectForTools(project)
                            onNavigate("vocal_remover")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                    ) {
                        Icon(Icons.Default.MicOff, contentDescription = null, tint = TextWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "AI Vocal Remover (2-Stem)", color = TextWhite)
                    }
                    Button(
                        onClick = {
                            viewModel.selectProjectForTools(project)
                            onNavigate("stem_separator")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = StudioBackground)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "4-Stem AI Separation", color = StudioBackground)
                    }
                    OutlinedButton(
                        onClick = { onNavigate("studio_mixer") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = NeonBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Open in Studio Mixer", color = NeonBlue)
                    }
                    OutlinedButton(
                        onClick = onOpenAudioEditor,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = TextWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Open Audio Editor", color = TextWhite)
                    }
                }
            }
        }
    }
}
