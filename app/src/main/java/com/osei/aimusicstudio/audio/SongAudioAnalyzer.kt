package com.osei.aimusicstudio.audio

import android.content.Context
import android.net.Uri
import com.osei.aimusicstudio.ai.StftProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class KeySourceLabel(val displayLabel: String) {
    DETECTED("Auto-Detected"),
    LOW_CONFIDENCE("Low Confidence / Undetermined"),
    MANUAL("Manually Corrected")
}

data class SongAnalysisResult(
    val detectedKey: String?,
    val keyConfidence: Float,
    val detectedBpm: Int?,
    val bpmConfidence: Float,
    val peakDbfs: Float,
    val rmsDbfs: Float,
    val chromaBins: FloatArray,
    val analyzedDurationMs: Long,
    val statusNote: String
) {
    val isKeyReliable: Boolean
        get() = !detectedKey.isNullOrBlank() && keyConfidence >= SongAudioAnalyzer.MIN_KEY_CONFIDENCE

    val isBpmReliable: Boolean
        get() = detectedBpm != null && bpmConfidence >= SongAudioAnalyzer.MIN_BPM_CONFIDENCE
}

/**
 * Streaming local audio analyzer for musical key (12-bin chromagram + Krumhansl-Schmuckler
 * profiles) and BPM (rectified onset-strength autocorrelation).
 *
 * Designed for 4 GB RAM Android phones: streams 1-second stereo chunks via StreamingAudioDecoder
 * and caps envelope/FFT buffers under ~250 KB total RAM. Never invents fake results when signal
 * is silent, unpitched noise, or below confidence thresholds.
 */
object SongAudioAnalyzer {
    const val MIN_KEY_CONFIDENCE = 0.38f
    const val MIN_BPM_CONFIDENCE = 0.35f
    private const val SAMPLE_RATE = StreamingAudioDecoder.TARGET_SAMPLE_RATE
    private const val CHUNK_FRAMES = SAMPLE_RATE // 1 second chunks (~352 KB)
    private const val HOP_ONSET = 512
    private const val MAX_ANALYZED_SECONDS = 120
    private const val MAX_ONSET_FRAMES = (MAX_ANALYZED_SECONDS * SAMPLE_RATE) / HOP_ONSET
    private const val KEY_FFT_SIZE = 8192
    private const val KEY_HOP_SIZE = 4096

    val PITCH_CLASS_NAMES = listOf(
        "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"
    )

    val ALL_MUSICAL_KEYS: List<String> = buildList {
        for (name in PITCH_CLASS_NAMES) add("$name Major")
        for (name in PITCH_CLASS_NAMES) add("$name Minor")
    }

    // Krumhansl-Kessler tonal hierarchy profiles
    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )
    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    suspend fun analyzeUri(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<SongAnalysisResult> = withContext(Dispatchers.IO) {
        try {
            val stft = StftProcessor(fftSize = KEY_FFT_SIZE, hopSize = KEY_HOP_SIZE)
            val hzPerBin = SAMPLE_RATE.toDouble() / KEY_FFT_SIZE.toDouble()
            val minBin = max(1, (55.0 / hzPerBin).toInt())
            val maxBin = min(stft.numBins - 1, (2200.0 / hzPerBin).toInt())

            val chromaAccum = DoubleArray(12)
            var chromaFrameWeight = 0.0

            val onsetList = ArrayList<Float>(2048)
            var prevHopEnergy = 0.0

            var globalPeak = 0f
            var globalSumSq = 0.0
            var totalMonoFrames = 0L

            val jobContext = currentCoroutineContext()

            StreamingAudioDecoder.streamStereoChunks(
                context = context,
                uri = uri,
                chunkFrames = CHUNK_FRAMES,
                isCancelled = isCancelled,
                onChunk = { stereo, frameCount, progress ->
                    jobContext.ensureActive()
                    if (isCancelled()) throw CancellationException("Song analysis cancelled")

                    if (totalMonoFrames / SAMPLE_RATE >= MAX_ANALYZED_SECONDS) {
                        return@streamStereoChunks
                    }

                    val mono = FloatArray(frameCount)
                    for (i in 0 until frameCount) {
                        val m = 0.5f * (stereo[i * 2] + stereo[i * 2 + 1])
                        mono[i] = m
                        val absM = abs(m)
                        if (absM > globalPeak) globalPeak = absM
                        globalSumSq += m.toDouble() * m.toDouble()
                    }
                    totalMonoFrames += frameCount

                    // 1) Onset envelope for BPM
                    var offset = 0
                    while (offset + HOP_ONSET <= frameCount && onsetList.size < MAX_ONSET_FRAMES) {
                        var sumSqHop = 0.0
                        for (j in 0 until HOP_ONSET) {
                            val s = mono[offset + j].toDouble()
                            sumSqHop += s * s
                        }
                        val hopRms = sqrt(sumSqHop / HOP_ONSET)
                        val compressed = sqrt(hopRms)
                        val flux = max(0.0, compressed - prevHopEnergy).toFloat()
                        onsetList.add(flux)
                        prevHopEnergy = compressed
                        offset += HOP_ONSET
                    }

                    // 2) Chromagram accumulation via 8192-point STFT
                    val (specReal, specImag, numFrames) = stft.stft(mono)
                    for (f in 0 until numFrames) {
                        val base = f * stft.numBins
                        var frameEnergy = 0.0
                        for (b in minBin..maxBin) {
                            val re = specReal[base + b].toDouble()
                            val im = specImag[base + b].toDouble()
                            val mag = sqrt(re * re + im * im)
                            frameEnergy += mag
                            if (mag > 1e-4) {
                                val freq = b * hzPerBin
                                val midi = 69.0 + 12.0 * (ln(freq / 440.0) / ln(2.0))
                                val nearest = midi.roundToInt()
                                val delta = midi - nearest
                                val weight = exp(-0.5 * (delta / 0.32) * (delta / 0.32))
                                val pc = ((nearest % 12) + 12) % 12
                                chromaAccum[pc] += mag * weight
                            }
                        }
                        chromaFrameWeight += frameEnergy
                    }

                    onProgress(progress.coerceIn(0f, 0.98f))
                }
            )

            if (isCancelled()) throw CancellationException("Song analysis cancelled")

            if (totalMonoFrames <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Audio file produced no decodable frames"))
            }

            val overallRms = sqrt(globalSumSq / totalMonoFrames.toDouble()).toFloat()
            val peakDbfs = if (globalPeak > 1e-6f) (20.0 * ln(globalPeak.toDouble()) / ln(10.0)).toFloat() else -120f
            val rmsDbfs = if (overallRms > 1e-6f) (20.0 * ln(overallRms.toDouble()) / ln(10.0)).toFloat() else -120f
            val analyzedDurationMs = (totalMonoFrames * 1000L) / SAMPLE_RATE

            if (overallRms < 1e-4f || globalPeak < 3e-4f) {
                onProgress(1f)
                return@withContext Result.success(
                    SongAnalysisResult(
                        detectedKey = null,
                        keyConfidence = 0f,
                        detectedBpm = null,
                        bpmConfidence = 0f,
                        peakDbfs = peakDbfs,
                        rmsDbfs = rmsDbfs,
                        chromaBins = FloatArray(12),
                        analyzedDurationMs = analyzedDurationMs,
                        statusNote = "Audio signal is silent or near silence; no key or BPM detected."
                    )
                )
            }

            val (detectedKey, keyConfidence, normalizedChroma) = estimateMusicalKey(chromaAccum)
            val (detectedBpm, bpmConfidence) = estimateBpm(onsetList, overallRms)

            val noteParts = mutableListOf<String>()
            if (detectedKey == null) {
                noteParts += "Key confidence too low (${(keyConfidence * 100).roundToInt()}%) — audio may be unpitched or harmonically ambiguous."
            }
            if (detectedBpm == null) {
                noteParts += "BPM confidence too low (${(bpmConfidence * 100).roundToInt()}%) — no steady rhythmic pulse detected."
            }
            val statusNote = if (noteParts.isEmpty()) {
                "Local chroma & onset autocorrelation analysis completed."
            } else {
                noteParts.joinToString(" ")
            }

            onProgress(1f)
            Result.success(
                SongAnalysisResult(
                    detectedKey = detectedKey,
                    keyConfidence = keyConfidence,
                    detectedBpm = detectedBpm,
                    bpmConfidence = bpmConfidence,
                    peakDbfs = peakDbfs,
                    rmsDbfs = rmsDbfs,
                    chromaBins = normalizedChroma,
                    analyzedDurationMs = analyzedDurationMs,
                    statusNote = statusNote
                )
            )
        } catch (ce: CancellationException) {
            Result.failure(ce)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    internal fun estimateMusicalKey(chromaRaw: DoubleArray): Triple<String?, Float, FloatArray> {
        val total = chromaRaw.sum()
        if (total <= 1e-9) {
            return Triple(null, 0f, FloatArray(12))
        }
        val norm = DoubleArray(12) { chromaRaw[it] / total }
        val maxBin = norm.max()
        val chromaFloat = FloatArray(12) { idx ->
            if (maxBin > 1e-9) (norm[idx] / maxBin).toFloat() else 0f
        }

        // Check tonal flatness: broadband noise distributes evenly (~0.0833 per pitch class)
        val mean = 1.0 / 12.0
        var variance = 0.0
        for (v in norm) {
            val d = v - mean
            variance += d * d
        }
        val std = sqrt(variance / 12.0)
        val cv = std / mean
        if (cv < 0.28) {
            return Triple(null, (cv / 0.28 * 0.25).toFloat().coerceIn(0f, 0.30f), chromaFloat)
        }

        data class Candidate(val label: String, val root: Int, val isMajor: Boolean, val corr: Double)
        val candidates = ArrayList<Candidate>(24)

        for (root in 0 until 12) {
            val rotatedMajor = DoubleArray(12) { idx -> MAJOR_PROFILE[(idx - root + 12) % 12] }
            val rotatedMinor = DoubleArray(12) { idx -> MINOR_PROFILE[(idx - root + 12) % 12] }
            candidates += Candidate("${PITCH_CLASS_NAMES[root]} Major", root, true, pearson(norm, rotatedMajor))
            candidates += Candidate("${PITCH_CLASS_NAMES[root]} Minor", root, false, pearson(norm, rotatedMinor))
        }

        candidates.sortByDescending { it.corr }
        val best = candidates[0]
        val second = candidates[1]
        val margin = (best.corr - second.corr).coerceAtLeast(0.0)

        // Combine absolute correlation strength and separation from second-best key
        val strengthScore = ((best.corr - 0.25) / 0.65).coerceIn(0.0, 1.0)
        val marginScore = (margin / 0.14).coerceIn(0.0, 1.0)
        val flatnessPenalty = ((cv - 0.25) / 0.65).coerceIn(0.2, 1.0)
        val confidence = ((0.65 * strengthScore + 0.35 * marginScore) * flatnessPenalty).toFloat().coerceIn(0f, 1f)

        val keyResult = if (best.corr >= 0.45 && confidence >= MIN_KEY_CONFIDENCE) {
            best.label
        } else {
            null
        }
        return Triple(keyResult, confidence, chromaFloat)
    }

    internal fun estimateBpm(onsetList: List<Float>, overallRms: Float): Pair<Int?, Float> {
        if (onsetList.size < 180) { // < ~2.1s cannot establish tempo
            return null to 0f
        }
        val envRate = SAMPLE_RATE.toDouble() / HOP_ONSET.toDouble()
        val n = onsetList.size
        val centered = DoubleArray(n)
        var sumFlux = 0.0
        var sumSqFlux = 0.0
        for (i in 0 until n) {
            val v = onsetList[i].toDouble()
            sumFlux += v
            sumSqFlux += v * v
        }
        val meanFlux = sumFlux / n
        // Steady tones have near-zero onset flux relative to signal RMS
        val relativeFluxStrength = sumFlux / (n * sqrt(overallRms.toDouble().coerceAtLeast(1e-6)))
        if (relativeFluxStrength < 0.012) {
            return null to 0.05f
        }

        var energy = 0.0
        for (i in 0 until n) {
            val c = max(0.0, onsetList[i].toDouble() - meanFlux * 0.75)
            centered[i] = c
            energy += c * c
        }
        if (energy <= 1e-9) {
            return null to 0f
        }

        val minBpm = 60
        val maxBpm = 190
        val acfScores = DoubleArray(maxBpm - minBpm + 1)
        val rawAcf = DoubleArray(maxBpm - minBpm + 1)

        for (bpm in minBpm..maxBpm) {
            val exactLag = envRate * 60.0 / bpm.toDouble()
            val lag0 = exactLag.toInt()
            val lag1 = min(n - 1, lag0 + 1)
            val frac = exactLag - lag0

            val r0 = unbiasedNormAcf(centered, lag0)
            val r1 = unbiasedNormAcf(centered, lag1)
            val r = r0 * (1.0 - frac) + r1 * frac
            rawAcf[bpm - minBpm] = r

            // Mild human-tempo prior around 116 BPM to break exact subharmonic ties
            val z = (bpm - 116.0) / 68.0
            val prior = 1.0 - 0.08 * (z * z)
            acfScores[bpm - minBpm] = r * prior
        }

        var bestIdx = 0
        for (i in acfScores.indices) {
            if (acfScores[i] > acfScores[bestIdx]) {
                bestIdx = i
            }
        }
        var bestBpm = minBpm + bestIdx
        var bestRawAcf = rawAcf[bestIdx]

        // Octave correction: if winner < 92 BPM and double-tempo still has strong ACF, prefer double-tempo
        if (bestBpm < 92 && bestBpm * 2 <= maxBpm) {
            val dblIdx = (bestBpm * 2) - minBpm
            if (rawAcf[dblIdx] >= bestRawAcf * 0.85) {
                bestBpm *= 2
                bestRawAcf = rawAcf[dblIdx]
            }
        }

        val sorted = rawAcf.sorted()
        val medianAcf = sorted[sorted.size / 2]
        val prominence = (bestRawAcf - medianAcf).coerceAtLeast(0.0)

        val confidence = (
            (bestRawAcf / 0.55).coerceIn(0.0, 1.0) * 0.55 +
                (prominence / 0.30).coerceIn(0.0, 1.0) * 0.45
            ).toFloat().coerceIn(0f, 1f)

        return if (bestRawAcf >= 0.18 && prominence >= 0.075 && confidence >= MIN_BPM_CONFIDENCE) {
            bestBpm to confidence
        } else {
            null to confidence
        }
    }

    private fun unbiasedNormAcf(x: DoubleArray, lag: Int): Double {
        if (lag <= 0 || lag >= x.size) return 0.0
        val count = x.size - lag
        var num = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until count) {
            val a = x[i]
            val b = x[i + lag]
            num += a * b
            normA += a * a
            normB += b * b
        }
        val denom = sqrt(normA * normB)
        return if (denom > 1e-12) (num / denom).coerceIn(0.0, 1.0) else 0.0
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until n) {
            sumX += x[i]
            sumY += y[i]
        }
        val meanX = sumX / n
        val meanY = sumY / n
        var num = 0.0
        var denX = 0.0
        var denY = 0.0
        for (i in 0 until n) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            num += dx * dy
            denX += dx * dx
            denY += dy * dy
        }
        val den = sqrt(denX * denY)
        return if (den > 1e-12) num / den else 0.0
    }
}
