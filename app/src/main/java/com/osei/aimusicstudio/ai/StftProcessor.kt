package com.osei.aimusicstudio.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Memory-efficient Radix-2 FFT / STFT / iSTFT and Overlap-Add (OLA) chunk reconstructor
 * using pre-allocated buffers to avoid GC spikes on 4 GB RAM Android devices.
 */
class StftProcessor(
    val fftSize: Int = 4096,
    val hopSize: Int = 1024
) {
    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) { "fftSize must be a power of 2" }
        require(hopSize in 1..fftSize) { "hopSize must be in 1..fftSize" }
    }

    val numBins: Int = fftSize / 2 + 1

    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / fftSize))).toFloat()
    }
    private val sqrtHannWindow = FloatArray(fftSize) { i ->
        sqrt(hannWindow[i])
    }

    // Reusable scratch buffers for zero-allocation inner FFT calls
    private val realScratch = FloatArray(fftSize)
    private val imagScratch = FloatArray(fftSize)

    private val cosTable = FloatArray(fftSize / 2) { i ->
        cos(-2.0 * PI * i / fftSize).toFloat()
    }
    private val sinTable = FloatArray(fftSize / 2) { i ->
        sin(-2.0 * PI * i / fftSize).toFloat()
    }

    /**
     * Computes complex STFT of a mono signal.
     * Returns Pair(realFrames, imagFrames) each of size [numFrames * numBins].
     */
    fun stft(monoSignal: FloatArray): Triple<FloatArray, FloatArray, Int> {
        val numFrames = if (monoSignal.size <= fftSize) {
            1
        } else {
            ((monoSignal.size - fftSize + hopSize - 1) / hopSize) + 1
        }
        val outReal = FloatArray(numFrames * numBins)
        val outImag = FloatArray(numFrames * numBins)

        for (frame in 0 until numFrames) {
            val start = frame * hopSize
            for (i in 0 until fftSize) {
                val sample = if (start + i < monoSignal.size) monoSignal[start + i] else 0f
                realScratch[i] = sample * sqrtHannWindow[i]
                imagScratch[i] = 0f
            }
            fftInPlace(realScratch, imagScratch, inverse = false)
            val baseOffset = frame * numBins
            System.arraycopy(realScratch, 0, outReal, baseOffset, numBins)
            System.arraycopy(imagScratch, 0, outImag, baseOffset, numBins)
        }
        return Triple(outReal, outImag, numFrames)
    }

    /**
     * Reconstructs a mono signal from complex STFT frames using COLA-normalized overlap-add.
     */
    fun istft(specReal: FloatArray, specImag: FloatArray, numFrames: Int, targetLength: Int): FloatArray {
        val output = FloatArray(targetLength)
        val windowSum = FloatArray(targetLength)

        for (frame in 0 until numFrames) {
            val baseOffset = frame * numBins
            // Mirror Hermitian symmetry for real signal
            realScratch[0] = specReal[baseOffset]
            imagScratch[0] = 0f
            for (k in 1 until fftSize / 2) {
                val r = specReal[baseOffset + k]
                val im = specImag[baseOffset + k]
                realScratch[k] = r
                imagScratch[k] = im
                realScratch[fftSize - k] = r
                imagScratch[fftSize - k] = -im
            }
            realScratch[fftSize / 2] = specReal[baseOffset + fftSize / 2]
            imagScratch[fftSize / 2] = 0f

            fftInPlace(realScratch, imagScratch, inverse = true)

            val start = frame * hopSize
            for (i in 0 until fftSize) {
                val outIdx = start + i
                if (outIdx >= targetLength) break
                val w = sqrtHannWindow[i]
                output[outIdx] += realScratch[i] * w
                windowSum[outIdx] += w * w
            }
        }

        for (i in 0 until targetLength) {
            if (windowSum[i] > 1e-6f) {
                output[i] /= windowSum[i]
            }
        }
        return output
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = fftSize
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val step = n / len
            for (i in 0 until n step len) {
                var tableIdx = 0
                for (k in 0 until halfLen) {
                    val wr = cosTable[tableIdx]
                    val wi = if (inverse) -sinTable[tableIdx] else sinTable[tableIdx]
                    val idx1 = i + k
                    val idx2 = idx1 + halfLen
                    val xr = real[idx2] * wr - imag[idx2] * wi
                    val xi = real[idx2] * wi + imag[idx2] * wr
                    real[idx2] = real[idx1] - xr
                    imag[idx2] = imag[idx1] - xi
                    real[idx1] += xr
                    imag[idx1] += xi
                    tableIdx += step
                }
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1.0f / n
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }
}

/**
 * Overlap-Add stereo chunk stitcher. Maintains only a single small overlap tail buffer
 * (e.g. 0.5s * 44100 * 2 = 176 KB) in RAM so arbitrarily long songs reconstruct seamlessly.
 */
class StereoOverlapAddStitcher(
    private val overlapFrames: Int
) {
    private var prevTailStereo: FloatArray? = null

    /**
     * Crossfades the leading [overlapFrames] of [chunkStereo] with the trailing overlap of the
     * previous chunk, saves the new trailing [overlapFrames] as tail, and returns the exact
     * non-overlapping samples ready to flush to disk.
     */
    fun processChunk(chunkStereo: FloatArray, frameCount: Int, isLastChunk: Boolean): FloatArray {
        val totalSamples = frameCount * 2
        if (overlapFrames <= 0 || frameCount <= overlapFrames) {
            return chunkStereo.copyOf(totalSamples)
        }

        val overlapSamples = overlapFrames * 2
        val working = chunkStereo.copyOf(totalSamples)

        val prevTail = prevTailStereo
        if (prevTail != null) {
            val fadeFrames = minOf(overlapFrames, frameCount, prevTail.size / 2)
            for (f in 0 until fadeFrames) {
                // Raised cosine crossfade: prevFade + curFade == 1.0
                val alpha = (0.5 * (1.0 - cos(PI * f / fadeFrames))).toFloat()
                val invAlpha = 1.0f - alpha
                val idx = f * 2
                working[idx] = prevTail[idx] * invAlpha + working[idx] * alpha
                working[idx + 1] = prevTail[idx + 1] * invAlpha + working[idx + 1] * alpha
            }
        }

        return if (isLastChunk) {
            prevTailStereo = null
            working
        } else {
            val emitSamples = totalSamples - overlapSamples
            val nextTail = FloatArray(overlapSamples)
            System.arraycopy(working, emitSamples, nextTail, 0, overlapSamples)
            prevTailStereo = nextTail
            working.copyOf(emitSamples)
        }
    }

    fun flushRemainingTail(): FloatArray? {
        val tail = prevTailStereo
        prevTailStereo = null
        return tail
    }

    fun reset() {
        prevTailStereo = null
    }
}
