package com.osei.aimusicstudio.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class WaveformData(
    val peaks: List<Float>,
    val rms: List<Float>,
    val overallPeakDb: Float,
    val overallRmsDb: Float,
    val durationMs: Long
)

object WaveformGenerator {
    private const val CHUNK_FRAMES = 4096

    suspend fun generateWaveform(
        context: Context,
        uriString: String,
        barsCount: Int = 64,
        useCache: Boolean = true
    ): Result<WaveformData> = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Empty audio URI"))
        }

        val cacheFile = getCacheFile(context, uriString, barsCount)
        if (useCache && cacheFile.exists() && cacheFile.length() > 0L) {
            readCache(cacheFile)?.let { return@withContext Result.success(it) }
        }

        try {
            val uri = Uri.parse(uriString)
            var estTotalFrames = 0L
            var sampleRate = StreamingAudioDecoder.TARGET_SAMPLE_RATE

            // Two-pass bucket aggregation? No, single-pass streaming using estimated total frames
            // plus dynamic downsampling buffer so we NEVER load the whole file into memory!
            val bucketPeaks = FloatArray(barsCount)
            val bucketSumSquares = DoubleArray(barsCount)
            val bucketCounts = LongArray(barsCount)

            var globalPeak = 0f
            var globalSumSq = 0.0
            var globalSampleCount = 0L
            var processedFrames = 0L

            StreamingAudioDecoder.streamStereoChunks(
                context = context,
                uri = uri,
                chunkFrames = CHUNK_FRAMES,
                onMetadata = { sr, _, totalFrames ->
                    sampleRate = sr
                    estTotalFrames = totalFrames.coerceAtLeast(barsCount.toLong())
                },
                onChunk = { stereoInterleaved, frameCount, _ ->
                    val denom = estTotalFrames.coerceAtLeast(processedFrames + frameCount)
                    for (f in 0 until frameCount) {
                        val l = stereoInterleaved[f * 2]
                        val r = stereoInterleaved[f * 2 + 1]
                        val monoAbs = max(abs(l), abs(r))
                        val monoVal = (l + r) * 0.5f

                        if (monoAbs > globalPeak) globalPeak = monoAbs
                        globalSumSq += (monoVal * monoVal).toDouble()
                        globalSampleCount++

                        val framePos = processedFrames + f
                        val bucketIdx = ((framePos.toDouble() / denom.toDouble()) * barsCount)
                            .toInt()
                            .coerceIn(0, barsCount - 1)

                        if (monoAbs > bucketPeaks[bucketIdx]) {
                            bucketPeaks[bucketIdx] = monoAbs
                        }
                        bucketSumSquares[bucketIdx] += (monoVal * monoVal).toDouble()
                        bucketCounts[bucketIdx]++
                    }
                    processedFrames += frameCount
                }
            )

            if (processedFrames <= 0L) {
                return@withContext Result.failure(IllegalStateException("Decoded 0 audio frames from source"))
            }

            val peaksList = ArrayList<Float>(barsCount)
            val rmsList = ArrayList<Float>(barsCount)

            // Normalize relative to global peak if non-zero, preserving genuine dynamics
            val normFactor = if (globalPeak > 1e-6f) 1.0f / globalPeak else 1.0f
            for (i in 0 until barsCount) {
                val peak = (bucketPeaks[i] * normFactor).coerceIn(0f, 1f)
                val rms = if (bucketCounts[i] > 0L) {
                    (sqrt(bucketSumSquares[i] / bucketCounts[i].toDouble()).toFloat() * normFactor).coerceIn(0f, 1f)
                } else 0f
                peaksList.add(peak)
                rmsList.add(rms)
            }

            val overallPeakDb = if (globalPeak > 1e-9f) 20f * log10(globalPeak) else -96f
            val overallRms = if (globalSampleCount > 0L) sqrt(globalSumSq / globalSampleCount).toFloat() else 0f
            val overallRmsDb = if (overallRms > 1e-9f) 20f * log10(overallRms) else -96f
            val durationMs = (processedFrames * 1000L) / sampleRate.coerceAtLeast(1)

            val data = WaveformData(
                peaks = peaksList,
                rms = rmsList,
                overallPeakDb = overallPeakDb,
                overallRmsDb = overallRmsDb,
                durationMs = durationMs
            )

            if (useCache) {
                writeCache(cacheFile, data)
            }
            Result.success(data)
        } catch (oom: OutOfMemoryError) {
            System.gc()
            Result.failure(IllegalStateException("Out of memory generating waveform", oom))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getCacheFile(context: Context, uriString: String, barsCount: Int): File {
        val dir = File(context.filesDir, "waveforms").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$uriString#$barsCount".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$digest.wf")
    }

    private fun writeCache(file: File, data: WaveformData) {
        try {
            val content = buildString {
                append(data.overallPeakDb).append('|')
                append(data.overallRmsDb).append('|')
                append(data.durationMs).append('\n')
                append(data.peaks.joinToString(",")).append('\n')
                append(data.rms.joinToString(","))
            }
            file.writeText(content)
        } catch (_: Exception) {
        }
    }

    private fun readCache(file: File): WaveformData? {
        return try {
            val lines = file.readLines()
            if (lines.size < 3) return null
            val meta = lines[0].split('|')
            if (meta.size < 3) return null
            val peaks = lines[1].split(',').filter { it.isNotBlank() }.map { it.toFloat() }
            val rms = lines[2].split(',').filter { it.isNotBlank() }.map { it.toFloat() }
            if (peaks.isEmpty() || peaks.size != rms.size) return null
            WaveformData(
                peaks = peaks,
                rms = rms,
                overallPeakDb = meta[0].toFloat(),
                overallRmsDb = meta[1].toFloat(),
                durationMs = meta[2].toLong()
            )
        } catch (_: Exception) {
            null
        }
    }
}
