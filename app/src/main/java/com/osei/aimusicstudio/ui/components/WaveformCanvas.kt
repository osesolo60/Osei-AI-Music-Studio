package com.osei.aimusicstudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.osei.aimusicstudio.audio.WaveformData
import com.osei.aimusicstudio.ui.theme.NeonBlue
import com.osei.aimusicstudio.ui.theme.NeonPurple
import com.osei.aimusicstudio.ui.theme.TextGrey

@Composable
fun WaveformCanvas(
    waveformData: WaveformData,
    progressFraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    onSeekFraction: ((Float) -> Unit)? = null
) {
    val peaks = waveformData.peaks
    val rms = waveformData.rms

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .let { base ->
                if (onSeekFraction != null) {
                    base.pointerInput(waveformData) {
                        detectTapGestures { offset ->
                            if (size.width > 0) {
                                val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onSeekFraction(fraction)
                            }
                        }
                    }
                } else {
                    base
                }
            }
    ) {
        if (peaks.isEmpty()) return@Canvas

        val count = peaks.size
        val slotWidth = size.width / count.toFloat()
        val barWidth = (slotWidth * 0.65f).coerceAtLeast(2f)
        val centerY = size.height / 2f
        val clampedProgress = progressFraction.coerceIn(0f, 1f)

        for (i in 0 until count) {
            val fraction = i.toFloat() / count.toFloat()
            val isPlayed = fraction <= clampedProgress

            val peakAmp = peaks[i].coerceIn(0.04f, 1f)
            val rmsAmp = rms.getOrElse(i) { peakAmp * 0.6f }.coerceIn(0.02f, peakAmp)

            val peakBarH = peakAmp * size.height * 0.9f
            val rmsBarH = rmsAmp * size.height * 0.9f
            val x = i * slotWidth + (slotWidth - barWidth) / 2f

            // Peak outer bar
            drawRoundRect(
                color = if (isPlayed) NeonPurple.copy(alpha = 0.55f) else TextGrey.copy(alpha = 0.25f),
                topLeft = Offset(x, centerY - peakBarH / 2f),
                size = Size(barWidth, peakBarH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )

            // RMS inner bar
            drawRoundRect(
                color = if (isPlayed) NeonBlue else TextGrey.copy(alpha = 0.55f),
                topLeft = Offset(x, centerY - rmsBarH / 2f),
                size = Size(barWidth, rmsBarH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }

        // Playhead indicator line
        val playheadX = clampedProgress * size.width
        drawLine(
            color = NeonBlue,
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, size.height),
            strokeWidth = 3f
        )
    }
}
