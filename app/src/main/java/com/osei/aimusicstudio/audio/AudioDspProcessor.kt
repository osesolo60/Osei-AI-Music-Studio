package com.osei.aimusicstudio.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class AudioEffectsConfig(
    val eqLowGainDb: Float = 0f,       // -12..+12 dB @ 120 Hz
    val eqMidGainDb: Float = 0f,       // -12..+12 dB @ 1000 Hz
    val eqHighGainDb: Float = 0f,      // -12..+12 dB @ 6000 Hz
    val bassBoostDb: Float = 0f,       // 0..+12 dB @ 80 Hz
    val reverbMix: Float = 0f,         // 0..0.75
    val delayMix: Float = 0f,          // 0..0.75
    val delayTimeMs: Int = 240,        // 80..600 ms
    val delayFeedback: Float = 0.35f,  // 0..0.65
    val compressorThresholdDb: Float = 0f, // -36..0 dB (0 = off)
    val compressorRatio: Float = 1f,   // 1..8
    val noiseGateThresholdDb: Float = -70f, // -70 = off; -60..-20 active downward expander
    val volume: Float = 1.0f,          // 0..1.5
    val balance: Float = 0.0f          // -1.0 Left .. 0.0 Center .. +1.0 Right
) {
    val isDspBypassed: Boolean
        get() = abs(eqLowGainDb) < 0.1f &&
            abs(eqMidGainDb) < 0.1f &&
            abs(eqHighGainDb) < 0.1f &&
            bassBoostDb < 0.1f &&
            reverbMix < 0.01f &&
            delayMix < 0.01f &&
            (compressorThresholdDb >= -0.5f || compressorRatio <= 1.05f) &&
            noiseGateThresholdDb <= -69f &&
            abs(volume - 1f) < 0.01f &&
            abs(balance) < 0.01f
}

data class VocalTransformConfig(
    val pitchSemitones: Float = 0f,
    val tempoRatio: Float = 1.0f,
    val preservePitchOnTempoChange: Boolean = true
) {
    val isBypassed: Boolean
        get() = abs(pitchSemitones) < 0.05f && abs(tempoRatio - 1.0f) < 0.02f
}

class BiquadStereoFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var lx1 = 0.0
    private var lx2 = 0.0
    private var ly1 = 0.0
    private var ly2 = 0.0
    private var rx1 = 0.0
    private var rx2 = 0.0
    private var ry1 = 0.0
    private var ry2 = 0.0

    fun setLowShelf(sampleRate: Int, freqHz: Double, gainDb: Double, slope: Double = 0.9) {
        if (abs(gainDb) < 0.05) {
            setIdentity()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        b0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
        b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
        b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
        a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
        a2 = ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0
    }

    fun setHighShelf(sampleRate: Int, freqHz: Double, gainDb: Double, slope: Double = 0.9) {
        if (abs(gainDb) < 0.05) {
            setIdentity()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
        b0 = (a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
        b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
        b2 = (a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
        a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
        a2 = ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0
    }

    fun setPeakingEq(sampleRate: Int, freqHz: Double, q: Double, gainDb: Double) {
        if (abs(gainDb) < 0.05) {
            setIdentity()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)

        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW0) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    private fun setIdentity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
    }

    fun processStereoInPlace(buf: FloatArray, frameCount: Int) {
        if (b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0) return
        for (i in 0 until frameCount) {
            val lIn = buf[i * 2].toDouble()
            val lOut = b0 * lIn + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
            lx2 = lx1; lx1 = lIn
            ly2 = ly1; ly1 = lOut
            buf[i * 2] = lOut.toFloat()

            val rIn = buf[i * 2 + 1].toDouble()
            val rOut = b0 * rIn + b1 * rx1 + b2 * rx2 - a1 * ry1 - a2 * ry2
            rx2 = rx1; rx1 = rIn
            ry2 = ry1; ry1 = rOut
            buf[i * 2 + 1] = rOut.toFloat()
        }
    }

    fun reset() {
        lx1 = 0.0; lx2 = 0.0; ly1 = 0.0; ly2 = 0.0
        rx1 = 0.0; rx2 = 0.0; ry1 = 0.0; ry2 = 0.0
    }
}

/**
 * Lightweight Freeverb/Schroeder stereo reverb (~56 KB total state, CPU-safe for 4 GB RAM).
 */
class SchroederStereoReverb(sampleRate: Int = 44100) {
    private val scale = sampleRate / 44100.0
    private val combLengthsL = intArrayOf(1116, 1188, 1277, 1356).map { (it * scale).toInt().coerceAtLeast(64) }
    private val combLengthsR = intArrayOf(1139, 1211, 1300, 1379).map { (it * scale).toInt().coerceAtLeast(64) }
    private val apLengthsL = intArrayOf(225, 556).map { (it * scale).toInt().coerceAtLeast(32) }
    private val apLengthsR = intArrayOf(248, 579).map { (it * scale).toInt().coerceAtLeast(32) }

    private val combsL = combLengthsL.map { FloatArray(it) }
    private val combsR = combLengthsR.map { FloatArray(it) }
    private val combIdxL = IntArray(4)
    private val combIdxR = IntArray(4)
    private val combFilterL = FloatArray(4)
    private val combFilterR = FloatArray(4)

    private val apL = apLengthsL.map { FloatArray(it) }
    private val apR = apLengthsR.map { FloatArray(it) }
    private val apIdxL = IntArray(2)
    private val apIdxR = IntArray(2)

    fun processInPlace(buf: FloatArray, frameCount: Int, wetMix: Float) {
        val mix = wetMix.coerceIn(0f, 0.75f)
        if (mix < 0.01f) return
        val dry = 1.0f - (mix * 0.45f)
        val feedback = 0.84f
        val damp = 0.25f

        for (i in 0 until frameCount) {
            val inL = buf[i * 2]
            val inR = buf[i * 2 + 1]
            val monoIn = (inL + inR) * 0.018f

            var outL = 0f
            var outR = 0f
            for (c in 0 until 4) {
                val cBufL = combsL[c]
                val idxL = combIdxL[c]
                val yL = cBufL[idxL]
                combFilterL[c] = yL * (1f - damp) + combFilterL[c] * damp
                cBufL[idxL] = monoIn + combFilterL[c] * feedback
                combIdxL[c] = (idxL + 1) % cBufL.size
                outL += yL

                val cBufR = combsR[c]
                val idxR = combIdxR[c]
                val yR = cBufR[idxR]
                combFilterR[c] = yR * (1f - damp) + combFilterR[c] * damp
                cBufR[idxR] = monoIn + combFilterR[c] * feedback
                combIdxR[c] = (idxR + 1) % cBufR.size
                outR += yR
            }

            for (a in 0 until 2) {
                val aBufL = apL[a]
                val iaL = apIdxL[a]
                val bOutL = aBufL[iaL]
                val nextL = -outL + bOutL
                aBufL[iaL] = outL + bOutL * 0.5f
                apIdxL[a] = (iaL + 1) % aBufL.size
                outL = nextL

                val aBufR = apR[a]
                val iaR = apIdxR[a]
                val bOutR = aBufR[iaR]
                val nextR = -outR + bOutR
                aBufR[iaR] = outR + bOutR * 0.5f
                apIdxR[a] = (iaR + 1) % aBufR.size
                outR = nextR
            }

            buf[i * 2] = (inL * dry + outL * mix * 3.5f)
            buf[i * 2 + 1] = (inR * dry + outR * mix * 3.5f)
        }
    }

    fun reset() {
        combsL.forEach { it.fill(0f) }
        combsR.forEach { it.fill(0f) }
        apL.forEach { it.fill(0f) }
        apR.forEach { it.fill(0f) }
        combFilterL.fill(0f)
        combFilterR.fill(0f)
    }
}

/**
 * Stereo feedback delay line bounded to <= 700 ms (~240 KB total).
 */
class StereoDelayLine(private val sampleRate: Int = 44100) {
    private val maxFrames = ((sampleRate * 0.70f).toInt()).coerceAtLeast(1024)
    private val delayBuf = FloatArray(maxFrames * 2)
    private var writePos = 0

    fun processInPlace(buf: FloatArray, frameCount: Int, mix: Float, timeMs: Int, feedback: Float) {
        val m = mix.coerceIn(0f, 0.75f)
        if (m < 0.01f) return
        val delayFrames = ((timeMs.coerceIn(40, 680) / 1000.0) * sampleRate).toInt().coerceIn(1, maxFrames - 1)
        val fb = feedback.coerceIn(0f, 0.65f)
        val dry = 1.0f - m * 0.35f

        for (i in 0 until frameCount) {
            val readPos = (writePos - delayFrames + maxFrames) % maxFrames
            val dL = delayBuf[readPos * 2]
            val dR = delayBuf[readPos * 2 + 1]

            val inL = buf[i * 2]
            val inR = buf[i * 2 + 1]

            delayBuf[writePos * 2] = inL + dR * fb
            delayBuf[writePos * 2 + 1] = inR + dL * fb
            writePos = (writePos + 1) % maxFrames

            buf[i * 2] = inL * dry + dL * m
            buf[i * 2 + 1] = inR * dry + dR * m
        }
    }

    fun reset() {
        delayBuf.fill(0f)
        writePos = 0
    }
}

/**
 * Stateful streaming audio effects chain processing 44.1 kHz stereo PCM float chunks in place.
 */
class StreamingAudioDspChain(private val sampleRate: Int = 44100) {
    private val bassFilter = BiquadStereoFilter()
    private val eqLowFilter = BiquadStereoFilter()
    private val eqMidFilter = BiquadStereoFilter()
    private val eqHighFilter = BiquadStereoFilter()
    private val reverb = SchroederStereoReverb(sampleRate)
    private val delay = StereoDelayLine(sampleRate)

    private var gateEnvelope = 0f
    private var compEnvelope = 0f
    private var lastConfig: AudioEffectsConfig? = null

    fun processInPlace(buf: FloatArray, frameCount: Int, config: AudioEffectsConfig) {
        if (frameCount <= 0) return
        if (config != lastConfig) {
            bassFilter.setLowShelf(sampleRate, 80.0, config.bassBoostDb.toDouble(), 0.85)
            eqLowFilter.setLowShelf(sampleRate, 120.0, config.eqLowGainDb.toDouble(), 0.9)
            eqMidFilter.setPeakingEq(sampleRate, 1000.0, 0.85, config.eqMidGainDb.toDouble())
            eqHighFilter.setHighShelf(sampleRate, 6000.0, config.eqHighGainDb.toDouble(), 0.9)
            lastConfig = config
        }

        // 1. Noise reduction / downward noise gate (when threshold > -69 dB)
        if (config.noiseGateThresholdDb > -69f) {
            val threshAmp = 10.0.pow(config.noiseGateThresholdDb / 20.0).toFloat()
            val att = 0.004f
            val rel = 0.0006f
            for (i in 0 until frameCount) {
                val peak = max(abs(buf[i * 2]), abs(buf[i * 2 + 1]))
                gateEnvelope += if (peak > gateEnvelope) att * (peak - gateEnvelope) else rel * (peak - gateEnvelope)
                val gateGain = if (gateEnvelope >= threshAmp) {
                    1.0f
                } else {
                    (gateEnvelope / threshAmp.coerceAtLeast(1e-6f)).pow(2.0f).coerceIn(0.02f, 1.0f)
                }
                buf[i * 2] *= gateGain
                buf[i * 2 + 1] *= gateGain
            }
        }

        // 2. Bass boost & 3-band EQ
        bassFilter.processStereoInPlace(buf, frameCount)
        eqLowFilter.processStereoInPlace(buf, frameCount)
        eqMidFilter.processStereoInPlace(buf, frameCount)
        eqHighFilter.processStereoInPlace(buf, frameCount)

        // 3. Dynamics compressor
        if (config.compressorThresholdDb < -0.5f && config.compressorRatio > 1.05f) {
            val thresh = config.compressorThresholdDb
            val ratio = config.compressorRatio.coerceIn(1f, 8f)
            val att = 0.005f
            val rel = 0.0005f
            val makeup = 10.0.pow(((-thresh) * (1f - 1f / ratio) * 0.35f) / 20.0).toFloat()

            for (i in 0 until frameCount) {
                val peak = max(abs(buf[i * 2]), abs(buf[i * 2 + 1])).coerceAtLeast(1e-6f)
                compEnvelope += if (peak > compEnvelope) att * (peak - compEnvelope) else rel * (peak - compEnvelope)
                val envDb = 20.0f * (ln(compEnvelope.coerceAtLeast(1e-6f)) / ln(10.0f))
                val gain = if (envDb > thresh) {
                    val compressedDb = thresh + (envDb - thresh) / ratio
                    10.0.pow((compressedDb - envDb) / 20.0).toFloat()
                } else {
                    1.0f
                }
                val finalGain = gain * makeup
                buf[i * 2] *= finalGain
                buf[i * 2 + 1] *= finalGain
            }
        }

        // 4. Stereo Delay & Reverb
        delay.processInPlace(buf, frameCount, config.delayMix, config.delayTimeMs, config.delayFeedback)
        reverb.processInPlace(buf, frameCount, config.reverbMix)

        // 5. Volume, Stereo Balance & soft clipper safety
        val vol = config.volume.coerceIn(0f, 1.5f)
        val bal = config.balance.coerceIn(-1f, 1f)
        val leftGain = vol * (if (bal > 0f) 1f - bal else 1f)
        val rightGain = vol * (if (bal < 0f) 1f + bal else 1f)

        for (i in 0 until frameCount) {
            val l = buf[i * 2] * leftGain
            val r = buf[i * 2 + 1] * rightGain
            buf[i * 2] = if (abs(l) > 0.98f) tanh(l.toDouble()).toFloat() else l
            buf[i * 2 + 1] = if (abs(r) > 0.98f) tanh(r.toDouble()).toFloat() else r
        }
    }

    fun reset() {
        bassFilter.reset()
        eqLowFilter.reset()
        eqMidFilter.reset()
        eqHighFilter.reset()
        reverb.reset()
        delay.reset()
        gateEnvelope = 0f
        compEnvelope = 0f
    }
}

/**
 * Memory-bounded WSOLA (Waveform Similarity Overlap-Add) + linear resampler for genuine
 * independent vocal pitch shifting and pitch-preserving tempo stretching on 4 GB phones.
 * Uses only a small sliding ring (~16k frames = ~128 KB RAM).
 */
class StreamingPitchTempoProcessor(
    private val sampleRate: Int = 44100
) {
    private val winFrames = 2048
    private val synHop = winFrames / 2 // 1024 frames = 50% overlap -> COLA Hann sums cleanly to 1.0
    private val searchRadius = 192
    private val hannWindow = FloatArray(winFrames) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i.toDouble() / winFrames.toDouble()))).toFloat()
    }

    suspend fun transformFile(
        context: Context,
        inputUri: Uri,
        outputWavFile: File,
        config: VocalTransformConfig,
        effects: AudioEffectsConfig = AudioEffectsConfig(),
        targetSampleRateHz: Int = sampleRate,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<File> {
        var reader: PcmWavSeekReader? = null
        return try {
            val wavFile = MixerTrackCachePreparer.prepareReadableWavFile(context, inputUri.toString(), isCancelled)
                .getOrThrow()
            val header = WavStreamReader.tryReadHeader(wavFile)
                ?: throw IllegalStateException("Unable to read WAV stream for vocal/DSP processing")
            reader = PcmWavSeekReader(wavFile, header)
            val totalInFrames = reader.totalFrames
            if (totalInFrames <= 0L) {
                throw IllegalStateException("Audio file contains zero frames")
            }

            val dspChain = StreamingAudioDspChain(sampleRate)
            val resampler = StreamingStereoResampler(sampleRate, targetSampleRateHz)

            val pitchFactor = 2.0.pow(config.pitchSemitones.toDouble() / 12.0).coerceIn(0.5, 2.0)
            val tempoFactor = config.tempoRatio.toDouble().coerceIn(0.5, 2.0)

            WavStreamWriter(outputWavFile, targetSampleRateHz, 2).use { writer ->
                if (config.isBypassed) {
                    streamDirectWithDsp(reader, totalInFrames, dspChain, effects, resampler, writer, isCancelled, onProgress)
                } else if (!config.preservePitchOnTempoChange) {
                    // Varispeed: resampling directly changes both tempo AND pitch proportionally
                    val speedRatio = (tempoFactor * pitchFactor).coerceIn(0.4, 2.5)
                    streamVarispeedWithDsp(reader, totalInFrames, speedRatio, dspChain, effects, resampler, writer, isCancelled, onProgress)
                } else {
                    // Pitch-preserving WSOLA time-scale followed by resampling for pitch shift
                    val wsolaDurationScale = pitchFactor / tempoFactor
                    streamWsolaThenResample(
                        reader = reader,
                        totalInFrames = totalInFrames,
                        wsolaDurationScale = wsolaDurationScale,
                        resampleStep = pitchFactor,
                        dspChain = dspChain,
                        effects = effects,
                        resampler = resampler,
                        writer = writer,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                }
            }
            onProgress(1.0f)
            Result.success(outputWavFile)
        } catch (ce: CancellationException) {
            outputWavFile.delete()
            Result.failure(ce)
        } catch (oom: OutOfMemoryError) {
            outputWavFile.delete()
            System.gc()
            Result.failure(IllegalStateException("Insufficient RAM during audio DSP processing", oom))
        } catch (t: Throwable) {
            outputWavFile.delete()
            Result.failure(t)
        } finally {
            reader?.close()
        }
    }

    private fun streamDirectWithDsp(
        reader: PcmWavSeekReader,
        totalFrames: Long,
        dspChain: StreamingAudioDspChain,
        effects: AudioEffectsConfig,
        resampler: StreamingStereoResampler,
        writer: WavStreamWriter,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit
    ) {
        val blockSize = 4096
        val buf = FloatArray(blockSize * 2)
        var cursor = 0L
        while (cursor < totalFrames) {
            if (isCancelled()) throw CancellationException("Cancelled")
            val n = min(blockSize.toLong(), totalFrames - cursor).toInt()
            buf.fill(0f, 0, n * 2)
            reader.accumulateIntoMix(cursor, 0L, n, 1f, 1f, buf)
            dspChain.processInPlace(buf, n, effects)
            resampler.processChunk(buf, n) { outArr, count -> writer.writeFloatSamples(outArr, count) }
            cursor += n
            onProgress((cursor.toFloat() / totalFrames.toFloat()).coerceIn(0f, 0.99f))
        }
        resampler.flush { outArr, count -> writer.writeFloatSamples(outArr, count) }
    }

    private fun streamVarispeedWithDsp(
        reader: PcmWavSeekReader,
        totalInFrames: Long,
        speedRatio: Double,
        dspChain: StreamingAudioDspChain,
        effects: AudioEffectsConfig,
        resampler: StreamingStereoResampler,
        writer: WavStreamWriter,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit
    ) {
        val outBlockFrames = 2048
        val outBuf = FloatArray(outBlockFrames * 2)
        val inReadMargin = (outBlockFrames * speedRatio).toInt() + 16
        val inBuf = FloatArray(inReadMargin * 2)

        var srcPos = 0.0
        while (srcPos < totalInFrames - 1) {
            if (isCancelled()) throw CancellationException("Cancelled")
            val blockBase = srcPos.toLong()
            val framesNeeded = min(inReadMargin.toLong(), totalInFrames - blockBase).toInt()
            if (framesNeeded < 2) break

            inBuf.fill(0f, 0, framesNeeded * 2)
            reader.accumulateIntoMix(blockBase, 0L, framesNeeded, 1f, 1f, inBuf)

            var produced = 0
            while (produced < outBlockFrames) {
                val relPos = srcPos - blockBase.toDouble()
                val idx0 = relPos.toInt()
                if (idx0 + 1 >= framesNeeded) break
                val frac = (relPos - idx0).toFloat()
                val p0 = idx0 * 2
                val p1 = (idx0 + 1) * 2
                outBuf[produced * 2] = inBuf[p0] + (inBuf[p1] - inBuf[p0]) * frac
                outBuf[produced * 2 + 1] = inBuf[p0 + 1] + (inBuf[p1 + 1] - inBuf[p0 + 1]) * frac
                produced++
                srcPos += speedRatio
            }

            if (produced == 0) break
            dspChain.processInPlace(outBuf, produced, effects)
            resampler.processChunk(outBuf, produced) { outArr, count -> writer.writeFloatSamples(outArr, count) }
            onProgress((srcPos / totalInFrames.toDouble()).toFloat().coerceIn(0f, 0.99f))
        }
        resampler.flush { outArr, count -> writer.writeFloatSamples(outArr, count) }
    }

    private fun streamWsolaThenResample(
        reader: PcmWavSeekReader,
        totalInFrames: Long,
        wsolaDurationScale: Double,
        resampleStep: Double,
        dspChain: StreamingAudioDspChain,
        effects: AudioEffectsConfig,
        resampler: StreamingStereoResampler,
        writer: WavStreamWriter,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit
    ) {
        val anaHop = synHop.toDouble() / wsolaDurationScale
        val totalOutWsola = ((totalInFrames.toDouble() / anaHop) * synHop).toLong().coerceAtLeast(winFrames.toLong())

        // Fixed small scratch buffers in RAM (~40 KB total)
        val searchWindowFrames = winFrames + searchRadius * 2 + 8
        val srcWinBuf = FloatArray(searchWindowFrames * 2)
        val overlapAccum = FloatArray(winFrames * 2)
        val emitBuf = FloatArray(synHop * 2)
        val pitchResampler = StreamingStereoResampler(
            srcRate = (sampleRate * resampleStep).toInt().coerceAtLeast(8000),
            dstRate = sampleRate
        )

        var nominalAnaPos = 0.0
        var lastChosenAnaStart = 0L
        var firstFrame = true

        while (nominalAnaPos + winFrames < totalInFrames) {
            if (isCancelled()) throw CancellationException("Cancelled")
            val centerNominal = nominalAnaPos.toLong()
            val expectedNaturalNext = lastChosenAnaStart + synHop

            val readStart = (centerNominal - searchRadius).coerceAtLeast(0L)
            val readCount = min(searchWindowFrames.toLong(), totalInFrames - readStart).toInt()
            if (readCount < winFrames) break

            srcWinBuf.fill(0f, 0, readCount * 2)
            reader.accumulateIntoMix(readStart, 0L, readCount, 1f, 1f, srcWinBuf)

            val bestRelOffset = if (firstFrame) {
                (centerNominal - readStart).toInt()
            } else {
                findBestWsolaOffset(
                    srcBuf = srcWinBuf,
                    readCount = readCount,
                    readStart = readStart,
                    expectedNaturalStart = expectedNaturalNext,
                    nominalCenter = centerNominal,
                    overlapAccum = overlapAccum
                )
            }
            firstFrame = false
            lastChosenAnaStart = readStart + bestRelOffset

            // Overlap-add Hann-windowed grain
            for (i in 0 until winFrames) {
                val w = hannWindow[i]
                val sIdx = (bestRelOffset + i) * 2
                overlapAccum[i * 2] += srcWinBuf[sIdx] * w
                overlapAccum[i * 2 + 1] += srcWinBuf[sIdx + 1] * w
            }

            // Emit synHop frames, then shift overlapAccum left by synHop
            System.arraycopy(overlapAccum, 0, emitBuf, 0, synHop * 2)
            System.arraycopy(overlapAccum, synHop * 2, overlapAccum, 0, (winFrames - synHop) * 2)
            overlapAccum.fill(0f, (winFrames - synHop) * 2, winFrames * 2)

            if (abs(resampleStep - 1.0) > 0.002) {
                pitchResampler.processChunk(emitBuf, synHop) { pitchedArr, pitchedSamples ->
                    val pitchedFrames = pitchedSamples / 2
                    if (pitchedFrames > 0) {
                        dspChain.processInPlace(pitchedArr, pitchedFrames, effects)
                        resampler.processChunk(pitchedArr, pitchedFrames) { outArr, count ->
                            writer.writeFloatSamples(outArr, count)
                        }
                    }
                }
            } else {
                dspChain.processInPlace(emitBuf, synHop, effects)
                resampler.processChunk(emitBuf, synHop) { outArr, count ->
                    writer.writeFloatSamples(outArr, count)
                }
            }

            nominalAnaPos += anaHop
            val progress = (nominalAnaPos / totalInFrames.toDouble()).toFloat().coerceIn(0f, 0.99f)
            onProgress(progress)
        }

        // Flush remaining half-window tail & resampler tails cleanly
        if (abs(resampleStep - 1.0) > 0.002) {
            pitchResampler.processChunk(overlapAccum, synHop) { pitchedArr, pitchedSamples ->
                val pf = pitchedSamples / 2
                if (pf > 0) {
                    dspChain.processInPlace(pitchedArr, pf, effects)
                    resampler.processChunk(pitchedArr, pf) { outArr, count -> writer.writeFloatSamples(outArr, count) }
                }
            }
            pitchResampler.flush { pitchedArr, pitchedSamples ->
                val pf = pitchedSamples / 2
                if (pf > 0) {
                    dspChain.processInPlace(pitchedArr, pf, effects)
                    resampler.processChunk(pitchedArr, pf) { outArr, count -> writer.writeFloatSamples(outArr, count) }
                }
            }
        } else {
            dspChain.processInPlace(overlapAccum, synHop, effects)
            resampler.processChunk(overlapAccum, synHop) { outArr, count -> writer.writeFloatSamples(outArr, count) }
        }
        resampler.flush { outArr, count -> writer.writeFloatSamples(outArr, count) }
    }

    private fun findBestWsolaOffset(
        srcBuf: FloatArray,
        readCount: Int,
        readStart: Long,
        expectedNaturalStart: Long,
        nominalCenter: Long,
        overlapAccum: FloatArray
    ): Int {
        val maxRel = (readCount - winFrames).coerceAtLeast(0)
        if (maxRel == 0) return 0

        // Target reference waveform is the half-window tail already sitting in overlapAccum[0..synHop)
        var bestOffset = (nominalCenter - readStart).toInt().coerceIn(0, maxRel)
        var bestScore = Double.NEGATIVE_INFINITY
        val compareFrames = min(synHop, 512)

        // Coarse step 4 for fast CPU throughput on 4 GB phones, then fine +-4 around best
        var offset = 0
        while (offset <= maxRel) {
            var corr = 0.0
            var energy = 1e-9
            for (k in 0 until compareFrames step 2) {
                val a = overlapAccum[k * 2]
                val b = srcBuf[(offset + k) * 2]
                corr += a * b
                energy += b * b
            }
            val score = corr / sqrt(energy)
            if (score > bestScore) {
                bestScore = score
                bestOffset = offset
            }
            offset += 4
        }

        val fineStart = (bestOffset - 4).coerceAtLeast(0)
        val fineEnd = (bestOffset + 4).coerceAtMost(maxRel)
        for (fOff in fineStart..fineEnd) {
            var corr = 0.0
            var energy = 1e-9
            for (k in 0 until compareFrames) {
                val a = overlapAccum[k * 2]
                val b = srcBuf[(fOff + k) * 2]
                corr += a * b
                energy += b * b
            }
            val score = corr / sqrt(energy)
            if (score > bestScore) {
                bestScore = score
                bestOffset = fOff
            }
        }
        return bestOffset
    }
}

object AudioEffectsRenderer {
    suspend fun processAudioToFile(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        effects: AudioEffectsConfig,
        vocalTransform: VocalTransformConfig = VocalTransformConfig(),
        targetSampleRateHz: Int = 44100,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val processor = StreamingPitchTempoProcessor(StreamingAudioDecoder.TARGET_SAMPLE_RATE)
        processor.transformFile(
            context = context,
            inputUri = inputUri,
            outputWavFile = outputFile,
            config = vocalTransform,
            effects = effects,
            targetSampleRateHz = targetSampleRateHz,
            isCancelled = isCancelled,
            onProgress = onProgress
        )
    }
}
