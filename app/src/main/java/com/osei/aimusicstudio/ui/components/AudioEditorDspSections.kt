package com.osei.aimusicstudio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osei.aimusicstudio.audio.AudioEffectsConfig
import com.osei.aimusicstudio.audio.VocalTransformConfig
import com.osei.aimusicstudio.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun AudioEditorEffectsPanel(
    effects: AudioEffectsConfig,
    enabled: Boolean,
    onBeforeSliderDrag: () -> Unit,
    onEffectsChange: (AudioEffectsConfig) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "DSP AUDIO EFFECTS (REAL PCM PROCESSING)",
                color = NeonBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )

            // Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PresetChip("Flat", enabled, modifier = Modifier.weight(1f)) {
                    onBeforeSliderDrag()
                    onEffectsChange(AudioEffectsConfig())
                }
                PresetChip("Warm Vocal", enabled, modifier = Modifier.weight(1f)) {
                    onBeforeSliderDrag()
                    onEffectsChange(
                        effects.copy(
                            eqLowGainDb = -2f,
                            eqMidGainDb = 3.5f,
                            eqHighGainDb = 2.5f,
                            reverbMix = 0.22f,
                            compressorThresholdDb = -16f,
                            compressorRatio = 3.0f
                        )
                    )
                }
                PresetChip("Bass Punch", enabled, modifier = Modifier.weight(1f)) {
                    onBeforeSliderDrag()
                    onEffectsChange(
                        effects.copy(
                            bassBoostDb = 6.5f,
                            eqLowGainDb = 3f,
                            compressorThresholdDb = -14f,
                            compressorRatio = 2.5f
                        )
                    )
                }
                PresetChip("Hall Echo", enabled, modifier = Modifier.weight(1f)) {
                    onBeforeSliderDrag()
                    onEffectsChange(
                        effects.copy(
                            reverbMix = 0.42f,
                            delayMix = 0.25f,
                            delayTimeMs = 280,
                            delayFeedback = 0.38f
                        )
                    )
                }
            }

            DspSliderRow(
                label = "EQ Low (120 Hz)",
                valueText = "${effects.eqLowGainDb.roundToInt()} dB",
                value = effects.eqLowGainDb,
                range = -12f..12f,
                enabled = enabled,
                testTag = "dsp_eq_low_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(eqLowGainDb = it)) }
            )
            DspSliderRow(
                label = "EQ Mid (1 kHz)",
                valueText = "${effects.eqMidGainDb.roundToInt()} dB",
                value = effects.eqMidGainDb,
                range = -12f..12f,
                enabled = enabled,
                testTag = "dsp_eq_mid_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(eqMidGainDb = it)) }
            )
            DspSliderRow(
                label = "EQ High (6 kHz)",
                valueText = "${effects.eqHighGainDb.roundToInt()} dB",
                value = effects.eqHighGainDb,
                range = -12f..12f,
                enabled = enabled,
                testTag = "dsp_eq_high_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(eqHighGainDb = it)) }
            )
            DspSliderRow(
                label = "Bass Boost (80 Hz)",
                valueText = "+${effects.bassBoostDb.roundToInt()} dB",
                value = effects.bassBoostDb,
                range = 0f..12f,
                enabled = enabled,
                testTag = "dsp_bass_boost_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(bassBoostDb = it)) }
            )
            DspSliderRow(
                label = "Reverb (Schroeder)",
                valueText = "${(effects.reverbMix * 100).roundToInt()}%",
                value = effects.reverbMix,
                range = 0f..0.75f,
                enabled = enabled,
                testTag = "dsp_reverb_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(reverbMix = it)) }
            )
            DspSliderRow(
                label = "Stereo Delay Mix",
                valueText = "${(effects.delayMix * 100).roundToInt()}% (${effects.delayTimeMs} ms)",
                value = effects.delayMix,
                range = 0f..0.75f,
                enabled = enabled,
                testTag = "dsp_delay_mix_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(delayMix = it)) }
            )
            if (effects.delayMix > 0.01f) {
                DspSliderRow(
                    label = "Delay Time",
                    valueText = "${effects.delayTimeMs} ms",
                    value = effects.delayTimeMs.toFloat(),
                    range = 80f..600f,
                    enabled = enabled,
                    testTag = "dsp_delay_time_slider",
                    onDragStart = onBeforeSliderDrag,
                    onValueChange = { onEffectsChange(effects.copy(delayTimeMs = it.roundToInt())) }
                )
            }
            DspSliderRow(
                label = "Compressor Threshold",
                valueText = if (effects.compressorThresholdDb >= -0.5f) "Off" else "${effects.compressorThresholdDb.roundToInt()} dB (${String.format("%.1f", effects.compressorRatio)}:1)",
                value = effects.compressorThresholdDb,
                range = -36f..0f,
                enabled = enabled,
                testTag = "dsp_comp_thresh_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = {
                    val autoRatio = if (it < -1f && effects.compressorRatio < 1.2f) 3.0f else effects.compressorRatio
                    onEffectsChange(effects.copy(compressorThresholdDb = it, compressorRatio = autoRatio))
                }
            )
            DspSliderRow(
                label = "Noise Gate / Reduction",
                valueText = if (effects.noiseGateThresholdDb <= -69f) "Off" else "${effects.noiseGateThresholdDb.roundToInt()} dB",
                value = effects.noiseGateThresholdDb,
                range = -70f..-20f,
                enabled = enabled,
                testTag = "dsp_noise_gate_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(noiseGateThresholdDb = it)) }
            )
            DspSliderRow(
                label = "Clip Volume",
                valueText = "${(effects.volume * 100).roundToInt()}%",
                value = effects.volume,
                range = 0f..1.5f,
                enabled = enabled,
                testTag = "dsp_volume_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(volume = it)) }
            )
            DspSliderRow(
                label = "Stereo Balance",
                valueText = when {
                    effects.balance < -0.05f -> "L ${(-effects.balance * 100).roundToInt()}%"
                    effects.balance > 0.05f -> "R ${(effects.balance * 100).roundToInt()}%"
                    else -> "Center"
                },
                value = effects.balance,
                range = -1f..1f,
                enabled = enabled,
                testTag = "dsp_balance_slider",
                onDragStart = onBeforeSliderDrag,
                onValueChange = { onEffectsChange(effects.copy(balance = it)) }
            )
        }
    }
}

@Composable
fun VocalControlsPanel(
    vocalConfig: VocalTransformConfig,
    enabled: Boolean,
    onBeforeEdit: () -> Unit,
    onVocalChange: (VocalTransformConfig) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VOCAL CONTROLS (PITCH & TEMPO)",
                    color = NeonPurple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                TextButton(
                    onClick = {
                        onBeforeEdit()
                        onVocalChange(VocalTransformConfig())
                    },
                    enabled = enabled && !vocalConfig.isBypassed
                ) {
                    Text("Reset", color = NeonBlue, fontSize = 12.sp)
                }
            }

            DspSliderRow(
                label = "Pitch Shift",
                valueText = "${if (vocalConfig.pitchSemitones >= 0f) "+" else ""}${vocalConfig.pitchSemitones.roundToInt()} st",
                value = vocalConfig.pitchSemitones,
                range = -12f..12f,
                enabled = enabled,
                testTag = "vocal_pitch_slider",
                onDragStart = onBeforeEdit,
                onValueChange = { onVocalChange(vocalConfig.copy(pitchSemitones = it.roundToInt().toFloat())) }
            )

            DspSliderRow(
                label = "Speed / Tempo",
                valueText = String.format("%.2fx", vocalConfig.tempoRatio),
                value = vocalConfig.tempoRatio,
                range = 0.50f..2.00f,
                enabled = enabled,
                testTag = "vocal_tempo_slider",
                onDragStart = onBeforeEdit,
                onValueChange = {
                    val stepped = (it * 20f).roundToInt() / 20f
                    onVocalChange(vocalConfig.copy(tempoRatio = stepped))
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Preserve Pitch on Tempo Change",
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (vocalConfig.preservePitchOnTempoChange) {
                            "WSOLA time-stretch active (pitch & tempo independent)"
                        } else {
                            "Varispeed mode (tempo changes pitch proportionally)"
                        },
                        color = TextGrey,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = vocalConfig.preservePitchOnTempoChange,
                    onCheckedChange = { checked ->
                        onBeforeEdit()
                        onVocalChange(vocalConfig.copy(preservePitchOnTempoChange = checked))
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("vocal_preserve_pitch_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StudioBackground,
                        checkedTrackColor = NeonBlue
                    )
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label, color = TextWhite, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DspSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    testTag: String,
    onDragStart: () -> Unit,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = TextWhite, fontSize = 12.sp)
            Text(text = valueText, color = NeonBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onValueChange(it) },
            onValueChangeFinished = onDragStart,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = NeonBlue,
                activeTrackColor = NeonPurple
            )
        )
    }
}
