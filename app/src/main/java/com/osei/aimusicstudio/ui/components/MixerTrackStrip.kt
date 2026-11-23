package com.osei.aimusicstudio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osei.aimusicstudio.audio.WaveformData
import com.osei.aimusicstudio.data.MixerTrackEntity
import com.osei.aimusicstudio.data.TrackRole
import com.osei.aimusicstudio.ui.screens.formatDuration
import com.osei.aimusicstudio.ui.theme.*
import kotlin.math.abs

@Composable
fun MixerTrackStrip(
    track: MixerTrackEntity,
    waveformData: WaveformData?,
    isPreparing: Boolean,
    isStreamReady: Boolean,
    timelinePositionMs: Long,
    timelineDurationMs: Long,
    isExporting: Boolean,
    onLoadOrReplaceAudio: () -> Unit,
    onExportTrack: () -> Unit,
    onToggleEnabled: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPanChange: (Float) -> Unit,
    onNudgeOffset: (Long) -> Unit,
    onResetOffset: () -> Unit,
    onSeekTimelineFraction: (Float) -> Unit,
    onRemoveTrack: () -> Unit
) {
    val hasAudio = !track.audioUri.isNullOrBlank()
    val icon: ImageVector = when (track.role) {
        TrackRole.DRUMS.name -> Icons.Default.Album
        TrackRole.BASS.name -> Icons.Default.Speaker
        TrackRole.GUITAR.name -> Icons.Default.QueueMusic
        TrackRole.STRINGS.name -> Icons.Default.Waves
        TrackRole.KEYS.name -> Icons.Default.Piano
        TrackRole.OTHER.name -> Icons.Default.MusicNote
        TrackRole.VOCALS.name -> Icons.Default.Mic
        else -> Icons.Default.Audiotrack
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mixer_track_card_${track.id}"),
        shape = RoundedCornerShape(16.dp),
        color = if (track.isEnabled) CardSurface else CardSurface.copy(alpha = 0.55f),
        border = if (track.isSolo && track.isEnabled) BorderStroke(1.5.dp, NeonBlue) else null
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Icon, Name, Enable Switch, Mute, Solo, Remove
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = NeonPurple.copy(alpha = 0.18f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = track.name, tint = NeonPurple, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.name,
                            color = if (track.isEnabled) TextWhite else TextGrey,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (hasAudio) {
                                val stateTag = when {
                                    isPreparing -> "Preparing stream..."
                                    isStreamReady -> formatDuration(track.durationMs)
                                    else -> "Pending"
                                }
                                "${track.displayFileName} • $stateTag"
                            } else {
                                "No audio loaded"
                            },
                            color = if (hasAudio) NeonBlue else TextGrey,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Switch(
                        checked = track.isEnabled,
                        onCheckedChange = { onToggleEnabled() },
                        modifier = Modifier.testTag("track_enable_switch_${track.id}"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonBlue,
                            checkedTrackColor = NeonBlue.copy(alpha = 0.45f)
                        )
                    )
                    Button(
                        onClick = onToggleMute,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (track.isMuted) Color(0xFFD93838) else StudioBackground
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("track_mute_btn_${track.id}"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text(text = "M", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Button(
                        onClick = onToggleSolo,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (track.isSolo) NeonBlue else StudioBackground
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("track_solo_btn_${track.id}"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "S",
                            color = if (track.isSolo) StudioBackground else TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    IconButton(
                        onClick = onRemoveTrack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("track_remove_btn_${track.id}")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${track.name}", tint = TextGrey)
                    }
                }
            }

            // Synchronized Waveform & Load/Replace Row
            if (isPreparing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = NeonBlue,
                    trackColor = TextGrey.copy(alpha = 0.25f)
                )
            } else if (waveformData != null && hasAudio) {
                val safeDur = timelineDurationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
                val trackLocalMs = (timelinePositionMs - track.offsetMs).coerceIn(0L, track.durationMs.coerceAtLeast(1L))
                val progressFrac = (trackLocalMs.toFloat() / track.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                WaveformCanvas(
                    waveformData = waveformData,
                    progressFraction = progressFrac,
                    height = 48.dp,
                    onSeekFraction = { frac ->
                        val targetMs = (track.offsetMs + (frac * track.durationMs)).toLong().coerceIn(0L, safeDur)
                        onSeekTimelineFraction(targetMs.toFloat() / safeDur.toFloat())
                    }
                )
            }

            // Import/Replace Audio & Alignment Nudge Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onLoadOrReplaceAudio,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("track_load_audio_btn_${track.id}"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            if (hasAudio) Icons.Default.SwapHoriz else Icons.Default.AudioFile,
                            contentDescription = null,
                            tint = NeonBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (hasAudio) "Replace" else "Load Audio",
                            color = NeonBlue,
                            fontSize = 12.sp
                        )
                    }
                    if (hasAudio) {
                        IconButton(
                            onClick = onExportTrack,
                            enabled = !isExporting,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("track_export_btn_${track.id}")
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "Export ${track.name} WAV",
                                tint = NeonPurple
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onNudgeOffset(-100L) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("track_nudge_left_${track.id}"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(text = "-0.1s", fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = onResetOffset,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        val sign = if (track.offsetMs > 0L) "+" else ""
                        Text(
                            text = "${sign}${track.offsetMs}ms",
                            color = if (track.offsetMs == 0L) TextGrey else NeonPurple,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    FilledTonalButton(
                        onClick = { onNudgeOffset(100L) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("track_nudge_right_${track.id}"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(text = "+0.1s", fontSize = 11.sp)
                    }
                }
            }

            // Volume Slider (0% - 150%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Vol", color = TextGrey, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                Slider(
                    value = track.volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1.5f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("track_volume_slider_${track.id}"),
                    colors = SliderDefaults.colors(
                        thumbColor = NeonPurple,
                        activeTrackColor = NeonPurple,
                        inactiveTrackColor = TextGrey.copy(alpha = 0.3f)
                    )
                )
                Text(
                    text = "${(track.volume * 100).toInt()}%",
                    color = TextWhite,
                    fontSize = 12.sp,
                    modifier = Modifier.width(42.dp)
                )
            }

            // Pan Slider (-1.0 L .. 0.0 C .. +1.0 R)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Pan", color = TextGrey, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                Slider(
                    value = track.pan,
                    onValueChange = onPanChange,
                    valueRange = -1f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("track_pan_slider_${track.id}"),
                    colors = SliderDefaults.colors(
                        thumbColor = NeonBlue,
                        activeTrackColor = NeonBlue,
                        inactiveTrackColor = TextGrey.copy(alpha = 0.3f)
                    )
                )
                val panLabel = when {
                    abs(track.pan) < 0.04f -> "C"
                    track.pan < 0f -> "L${(-track.pan * 100).toInt()}"
                    else -> "R${(track.pan * 100).toInt()}"
                }
                Text(
                    text = panLabel,
                    color = NeonBlue,
                    fontSize = 12.sp,
                    modifier = Modifier.width(42.dp)
                )
            }
        }
    }
}
