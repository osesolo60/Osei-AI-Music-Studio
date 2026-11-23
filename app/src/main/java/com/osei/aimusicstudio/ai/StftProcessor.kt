package com.osei.aimusicstudio.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Memory-efficient Mixed-Radix FFT / STFT / iSTFT and Overlap-Add (OLA) chunk reconstructor
 * using pre-allocated scratch buffers to avoid GC spikes on 4 GB RAM Android devices.
 *
 * Supports both power-of-2 sizes (e.g. 4096, 8192) and UVR MDX-Net even sizes
 * (e.g. 5120 = 5*1024, 6144 = 3*2048, 7680 = 15*512) via exact N = R * 2^k decomposition.
 */
class StftProcessor(
    val fftSize: Int = 4096,
    val hopSize: Int = 1024
) {
    init {
        require(fftSize > 0 && fftSize % 2 == 0) { "fftSize must be a positive even integer" }
        require(hopSize in 1..fftSize) { "hopSize must be in 1..fftSize" }
    }

    val numBins: Int = fftSize / 2 + 1

    private val pow2Factor: Int
    private val oddFactor: Int

    init {
        var m = fftSize
        var p2 = 1
        while (m % 2 == 0) {
            m /= 2
            p2 *= 2
        }
        pow2Factor = p2
        oddFactor = m
    }

    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / fftSize))).toFloat()
    }
    private val sqrtHannWindow = FloatArray(fftSize) { i ->
        sqrt(hannWindow[i])
    }

    // Reusable scratch buffers for zero-allocation inner FFT calls
    private val realScratch = FloatArray(fftSize)
    private val imagScratch = FloatArray(fftSize)

    private val cosPow2 = FloatArray(pow2Factor / 2) { i ->
        cos(-2.0 * PI * i / pow2Factor).toFloat()
    }
    private val sinPow2 = FloatArray(pow2Factor / 2) { i ->
        sin(-2.0 * PI * i / pow2Factor).toFloat()
    }

    // Precomputed buffers for mixed-radix decomposition when oddFactor > 1
    private val subReal = FloatArray(oddFactor * pow2Factor)
    private val subImag = FloatArray(oddFactor * pow2Factor)
    private val tempPow2Real = FloatArray(pow2Factor)
    private val tempPow2Imag = FloatArray(pow2Factor)
    private val twiddlePrCos = if (oddFactor > 1) FloatArray(oddFactor * pow2Factor) { idx ->
        val r = idx / pow2Factor
        val p = idx % pow2Factor
        cos(-2.0 * PI * p * r / fftSize).toFloat()
    } else FloatArray(0)
    private val twiddlePrSin = if (oddFactor > 1) FloatArray(oddFactor * pow2Factor) { idx ->
        val r = idx / pow2Factor
        val p = idx % pow2Factor
        sin(-2.0 * PI * p * r / fftSize).toFloat()
    } else FloatArray(0)
    private val dftRCos = if (oddFactor > 1) FloatArray(oddFactor * oddFactor) { idx ->
        val q = idx / oddFactor
        val r = idx % oddFactor
        cos(-2.0 * PI * q * r / oddFactor).toFloat()
    } else FloatArray(0)
    private val dftRSin = if (oddFactor > 1) FloatArray(oddFactor * oddFactor) { idx ->
        val q = idx / oddFactor
        val r = idx % oddFactor
        sin(-2.0 * PI * q * r / oddFactor).toFloat()
    } else FloatArray(0)

    /**
     * Computes complex STFT of a mono signal using sqrt-Hann analysis windowing (uncentered).
     * Returns Triple(realFrames, imagFrames, numFrames) each of size [numFrames * numBins].
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
     * Reconstructs a mono signal from complex STFT frames using COLA-normalized sqrt-Hann overlap-add.
     */
    fun istft(specReal: FloatArray, specImag: FloatArray, numFrames: Int, targetLength: Int): FloatArray {
        val output = FloatArray(targetLength)
        val windowSum = FloatArray(targetLength)

        for (frame in 0 until numFrames) {
            unpackHermitianAndIfft(specReal, specImag, frame)
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

    /**
     * Computes complex STFT matching PyTorch torch.stft(..., n_fft=fftSize, hop_length=hopSize,
     * window=torch.hann_window(fftSize, periodic=True), center=True, pad_mode="reflect", normalized=False),
     * as used by UVR MDX-Net ConvTDFNet models.
     */
    fun stftCenteredHann(monoSignal: FloatArray): Triple<FloatArray, FloatArray, Int> {
        val signalLen = monoSignal.size
        val pad = fftSize / 2
        val paddedLen = signalLen + fftSize
        val numFrames = if (signalLen == 0) 1 else (paddedLen - fftSize) / hopSize + 1
        val outReal = FloatArray(numFrames * numBins)
        val outImag = FloatArray(numFrames * numBins)

        for (frame in 0 until numFrames) {
            val centerStart = frame * hopSize - pad
            for (i in 0 until fftSize) {
                val srcIdx = reflectIndex(centerStart + i, signalLen)
                val sample = if (signalLen > 0) monoSignal[srcIdx] else 0f
                realScratch[i] = sample * hannWindow[i]
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
     * Reconstructs a mono signal matching PyTorch torch.istft(..., n_fft=fftSize, hop_length=hopSize,
     * window=torch.hann_window(fftSize, periodic=True), center=True, length=targetLength).
     */
    fun istftCenteredHann(specReal: FloatArray, specImag: FloatArray, numFrames: Int, targetLength: Int): FloatArray {
        if (targetLength <= 0) return FloatArray(0)
        val pad = fftSize / 2
        val paddedLen = targetLength + fftSize
        val acc = FloatArray(paddedLen)
        val windowSum = FloatArray(paddedLen)

        for (frame in 0 until numFrames) {
            unpackHermitianAndIfft(specReal, specImag, frame)
            val start = frame * hopSize
            for (i in 0 until fftSize) {
                val outIdx = start + i
                if (outIdx >= paddedLen) break
                val w = hannWindow[i]
                acc[outIdx] += realScratch[i] * w
                windowSum[outIdx] += w * w
            }
        }

        val output = FloatArray(targetLength)
        for (i in 0 until targetLength) {
            val wSum = windowSum[pad + i]
            if (wSum > 1e-11f) {
                output[i] = acc[pad + i] / wSum
            }
        }
        return output
    }

    private fun reflectIndex(i: Int, len: Int): Int {
        if (len <= 1) return 0
        var idx = i
        while (idx < 0 || idx >= len) {
            if (idx < 0) idx = -idx
            if (idx >= len) idx = 2 * (len - 1) - idx
        }
        return idx
    }

    private fun unpackHermitianAndIfft(specReal: FloatArray, specImag: FloatArray, frame: Int) {
        val baseOffset = frame * numBins
        realScratch[0] = specReal[baseOffset]
        imagScratch[0] = 0f
        val half = fftSize / 2
        for (k in 1 until half) {
            val r = specReal[baseOffset + k]
            val im = specImag[baseOffset + k]
            realScratch[k] = r
            imagScratch[k] = im
            realScratch[fftSize - k] = r
            imagScratch[fftSize - k] = -im
        }
        realScratch[half] = specReal[baseOffset + half]
        imagScratch[half] = 0f
        fftInPlace(realScratch, imagScratch, inverse = true)
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        if (oddFactor == 1) {
            radix2Fft(real, imag, pow2Factor, inverse)
            if (inverse) {
                val invN = 1.0f / fftSize
                for (i in 0 until fftSize) {
                    real[i] *= invN
                    imag[i] *= invN
                }
            }
            return
        }

        val rCount = oddFactor
        val mCount = pow2Factor
        val sign = if (inverse) -1f else 1f

        // Step 1: Run R independent M-point power-of-2 FFTs on strided inputs
        for (r in 0 until rCount) {
            for (m in 0 until mCount) {
                val srcIdx = m * rCount + r
                tempPow2Real[m] = real[srcIdx]
                tempPow2Imag[m] = imag[srcIdx]
            }
            radix2Fft(tempPow2Real, tempPow2Imag, mCount, inverse)
            val baseR = r * mCount
            for (p in 0 until mCount) {
                val re = tempPow2Real[p]
                val im = tempPow2Imag[p]
                val twCos = twiddlePrCos[baseR + p]
                val twSin = sign * twiddlePrSin[baseR + p]
                subReal[baseR + p] = re * twCos - im * twSin
                subImag[baseR + p] = re * twSin + im * twCos
            }
        }

        // Step 2: Combine R-point DFT across sub-transforms
        for (q in 0 until rCount) {
            val outBase = q * mCount
            val dftBase = q * rCount
            for (p in 0 until mCount) {
                var sumR = 0f
                var sumI = 0f
                for (r in 0 until rCount) {
                    val subIdx = r * mCount + p
                    val sr = subReal[subIdx]
                    val si = subImag[subIdx]
                    val wc = dftRCos[dftBase + r]
                    val ws = sign * dftRSin[dftBase + r]
                    sumR += sr * wc - si * ws
                    sumI += sr * ws + si * wc
                }
                real[outBase + p] = sumR
                imag[outBase + p] = sumI
            }
        }

        if (inverse) {
            val invN = 1.0f / fftSize
            for (i in 0 until fftSize) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }

    private fun radix2Fft(real: FloatArray, imag: FloatArray, n: Int, inverse: Boolean) {
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
                    val wr = cosPow2[tableIdx]
                    val wi = if (inverse) -sinPow2[tableIdx] else sinPow2[tableIdx]
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
