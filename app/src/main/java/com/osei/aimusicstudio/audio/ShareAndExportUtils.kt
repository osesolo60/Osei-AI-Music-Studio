package com.osei.aimusicstudio.audio

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

enum class ExportQualityPreset(
    val label: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int = 16
) {
    WAV_22K("Standard WAV (22.05 kHz, 16-bit)", 22050),
    WAV_44K("CD Quality WAV (44.1 kHz, 16-bit)", 44100),
    WAV_48K("Studio Master WAV (48 kHz, 16-bit)", 48000)
}

/**
 * Stateful streaming Blackman-windowed sinc FIR resampler (~33 KB carry buffer).
 * Provides anti-aliasing low-pass filtering when downsampling (e.g., 44.1 kHz -> 22.05 kHz)
 * while maintaining bounded memory for 4 GB RAM devices.
 */
class StreamingStereoResampler(
    private val srcRate: Int,
    private val dstRate: Int
) {
    companion object {
        private const val HALF_TAPS = 12 // 24-tap windowed-sinc kernel
    }

    private val step = srcRate.toDouble() / dstRate.toDouble()
    private val cutoff = if (dstRate < srcRate) {
        0.42 * (dstRate.toDouble() / srcRate.toDouble())
    } else {
        0.45
    }

    private var carryBuf = FloatArray((HALF_TAPS * 2 + 4096) * 2)
    private var carryFrames = HALF_TAPS
    private var carryStartGlobalFrame = -HALF_TAPS.toLong()
    private var totalFedFrames = 0L
    private var globalCursor = 0.0
    private val outBuf = FloatArray(4096 * 2)

    fun processChunk(
        inputStereo: FloatArray,
        inputFrames: Int,
        writeOutput: (FloatArray, Int) -> Unit
    ) {
        if (inputFrames <= 0) return
        if (srcRate == dstRate) {
            writeOutput(inputStereo, inputFrames * 2)
            return
        }

        ensureCarryCapacity(carryFrames + inputFrames)
        System.arraycopy(inputStereo, 0, carryBuf, carryFrames * 2, inputFrames * 2)
        carryFrames += inputFrames
        totalFedFrames += inputFrames

        val safeLimit = (carryStartGlobalFrame + carryFrames - HALF_TAPS - 1).toDouble()
        emitSamplesUpTo(safeLimit, writeOutput)
        discardConsumedPrefix()
    }

    fun flush(writeOutput: (FloatArray, Int) -> Unit) {
        if (srcRate == dstRate || totalFedFrames <= 0L) return
        val tailZeros = HALF_TAPS + 2
        ensureCarryCapacity(carryFrames + tailZeros)
        carryBuf.fill(0f, carryFrames * 2, (carryFrames + tailZeros) * 2)
        carryFrames += tailZeros

        emitSamplesUpTo(totalFedFrames.toDouble() - 1e-9, writeOutput)
        reset()
    }

    private fun reset() {
        carryBuf.fill(0f, 0, HALF_TAPS * 2)
        carryFrames = HALF_TAPS
        carryStartGlobalFrame = -HALF_TAPS.toLong()
        totalFedFrames = 0L
        globalCursor = 0.0
    }

    private fun ensureCarryCapacity(neededFrames: Int) {
        val neededSamples = neededFrames * 2
        if (carryBuf.size < neededSamples) {
            carryBuf = carryBuf.copyOf(neededSamples + 2048)
        }
    }

    private fun emitSamplesUpTo(
        maxGlobalCursorInclusive: Double,
        writeOutput: (FloatArray, Int) -> Unit
    ) {
        var outCount = 0
        while (globalCursor <= maxGlobalCursorInclusive) {
            val localPos = globalCursor - carryStartGlobalFrame.toDouble()
            val centerIdx = floor(localPos).toInt()
            val frac = localPos - centerIdx

            var sumL = 0.0
            var sumR = 0.0
            var weightSum = 0.0

            for (k in -(HALF_TAPS - 1)..HALF_TAPS) {
                val sampleIdx = centerIdx + k
                if (sampleIdx < 0 || sampleIdx >= carryFrames) continue
                val d = k.toDouble() - frac
                val u = (d + HALF_TAPS) / (2.0 * HALF_TAPS)
                val blackman = if (u <= 0.0 || u >= 1.0) {
                    0.0
                } else {
                    0.42 - 0.5 * cos(2.0 * PI * u) + 0.08 * cos(4.0 * PI * u)
                }
                val sinc = if (abs(d) < 1e-8) {
                    2.0 * cutoff
                } else {
                    sin(2.0 * PI * cutoff * d) / (PI * d)
                }
                val w = blackman * sinc
                sumL += carryBuf[sampleIdx * 2] * w
                sumR += carryBuf[sampleIdx * 2 + 1] * w
                weightSum += w
            }

            val norm = if (abs(weightSum) > 1e-9) 1.0 / weightSum else 1.0
            if (outCount * 2 + 1 >= outBuf.size) {
                writeOutput(outBuf, outCount * 2)
                outCount = 0
            }
            outBuf[outCount * 2] = (sumL * norm).toFloat()
            outBuf[outCount * 2 + 1] = (sumR * norm).toFloat()
            outCount++
            globalCursor += step
        }

        if (outCount > 0) {
            writeOutput(outBuf, outCount * 2)
        }
    }

    private fun discardConsumedPrefix() {
        val localCursorIdx = floor(globalCursor - carryStartGlobalFrame.toDouble()).toInt()
        val dropFrames = (localCursorIdx - (HALF_TAPS - 1)).coerceAtLeast(0)
        if (dropFrames > 0 && dropFrames < carryFrames) {
            val remain = carryFrames - dropFrames
            System.arraycopy(carryBuf, dropFrames * 2, carryBuf, 0, remain * 2)
            carryFrames = remain
            carryStartGlobalFrame += dropFrames
        }
    }
}

/**
 * Safely cleans only verified-obsolete temporary render files, obsolete merged editor WAVs
 * not referenced by Room or undo/redo history, and expired share cache copies.
 * Never touches user imports, active Karaoke takes, saved projects, or active files.
 */
object SafeTempStorageCleanup {
    private val ABANDONED_CACHE_PREFIXES = listOf(
        "clip_trim_",
        "merge_seg_",
        "tl_seg_",
        "mix_export_",
        "track_export_",
        "editor_export_",
        "karaoke_export_",
        "share_mix_",
        "share_track_",
        "editor_share_",
        "karaoke_share_"
    )
    private val ABANDONED_CACHE_SUFFIXES = listOf(".mix.tmp", ".track.tmp", ".merge.tmp", ".tl.tmp")

    fun cleanUnusedEditorMergedFiles(
        context: Context,
        protectedUris: Set<String>,
        minAgeMs: Long = 5_000L,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        var deletedCount = 0
        try {
            val editorDir = File(context.filesDir, "editor_clips")
            if (!editorDir.exists() || !editorDir.isDirectory) return 0

            val protectedPaths = HashSet<String>(protectedUris.size * 2)
            for (uriStr in protectedUris) {
                if (uriStr.isBlank()) continue
                protectedPaths.add(uriStr)
                try {
                    val parsed = android.net.Uri.parse(uriStr)
                    if (parsed.scheme == "file" && parsed.path != null) {
                        protectedPaths.add(File(parsed.path!!).canonicalPath)
                    } else if (uriStr.startsWith("/")) {
                        protectedPaths.add(File(uriStr).canonicalPath)
                    }
                } catch (_: Throwable) {
                }
            }

            val files = editorDir.listFiles() ?: return 0
            for (file in files) {
                if (!file.isFile) continue
                if (!file.name.startsWith("merged_") || !file.name.endsWith(".wav")) continue
                val ageMs = nowMs - file.lastModified()
                if (ageMs < minAgeMs) continue
                val canonical = try {
                    file.canonicalPath
                } catch (_: Throwable) {
                    file.absolutePath
                }
                val fileUri = android.net.Uri.fromFile(file).toString()
                if (canonical !in protectedPaths && fileUri !in protectedPaths) {
                    if (file.delete()) deletedCount++
                }
            }
        } catch (_: Throwable) {
        }
        return deletedCount
    }

    fun cleanAbandonedTempRenderFiles(
        context: Context,
        minAgeMs: Long = 30 * 60_000L,
        nowMs: Long = System.currentTimeMillis(),
        activeFiles: Set<File> = emptySet()
    ): Int {
        var deletedCount = 0
        try {
            val cacheDir = context.cacheDir
            if (!cacheDir.exists() || !cacheDir.isDirectory) return 0
            val activeCanonical = activeFiles.mapNotNull {
                try {
                    it.canonicalPath
                } catch (_: Throwable) {
                    null
                }
            }.toSet()

            val files = cacheDir.listFiles() ?: return 0
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                val matchesTempPattern =
                    ABANDONED_CACHE_PREFIXES.any { name.startsWith(it) } ||
                        ABANDONED_CACHE_SUFFIXES.any { name.endsWith(it) }
                if (!matchesTempPattern) continue

                val canonical = try {
                    file.canonicalPath
                } catch (_: Throwable) {
                    file.absolutePath
                }
                if (canonical in activeCanonical) continue
                if (nowMs - file.lastModified() >= minAgeMs) {
                    if (file.delete()) deletedCount++
                }
            }
        } catch (_: Throwable) {
        }
        return deletedCount
    }

    private val activeSharePaths = ConcurrentHashMap.newKeySet<String>()

    internal fun markShareFileActive(file: File) {
        try {
            activeSharePaths.add(file.canonicalPath)
        } catch (_: Throwable) {
            activeSharePaths.add(file.absolutePath)
        }
    }

    internal fun unmarkShareFileActive(file: File) {
        try {
            activeSharePaths.remove(file.canonicalPath)
        } catch (_: Throwable) {
            activeSharePaths.remove(file.absolutePath)
        }
    }

    const val SHARED_EXPORT_RETENTION_MS: Long = 7L * 24L * 60L * 60_000L // 7 days

    fun cleanOldSharedExports(
        context: Context,
        maxAgeMs: Long = SHARED_EXPORT_RETENTION_MS,
        nowMs: Long = System.currentTimeMillis(),
        activeShareFile: File? = null
    ): Int {
        var deletedCount = 0
        try {
            val shareDir = File(context.cacheDir, "shared_exports")
            if (!shareDir.exists() || !shareDir.isDirectory) return 0
            val activeCanonical = try {
                activeShareFile?.canonicalPath
            } catch (_: Throwable) {
                activeShareFile?.absolutePath
            }

            val files = shareDir.listFiles()?.filter { it.isFile }.orEmpty()
            for (file in files) {
                val canonical = try {
                    file.canonicalPath
                } catch (_: Throwable) {
                    file.absolutePath
                }
                if (canonical == activeCanonical || canonical in activeSharePaths) {
                    continue
                }
                if ((nowMs - file.lastModified()) >= maxAgeMs) {
                    if (file.delete()) deletedCount++
                }
            }
        } catch (_: Throwable) {
        }
        return deletedCount
    }
}

object AudioShareHelper {
    private val shareSeq = AtomicLong(0L)

    fun prepareShareFile(context: Context, sourceFile: File, shareDisplayName: String): File {
        val shareDir = File(context.cacheDir, "shared_exports").apply { mkdirs() }
        val baseWithoutExt = shareDisplayName.removeSuffix(".wav").removeSuffix(".WAV")
        val sanitizedStem = baseWithoutExt
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "osei_audio" }

        var destFile: File
        while (true) {
            val seq = shareSeq.incrementAndGet()
            val token = UUID.randomUUID().toString().replace("-", "").take(6)
            val uniqueName = "${sanitizedStem}_${System.currentTimeMillis()}_${seq}_${token}.wav"
            val candidate = File(shareDir, uniqueName)
            if (candidate.createNewFile()) {
                destFile = candidate
                break
            }
        }

        SafeTempStorageCleanup.markShareFileActive(destFile)
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            }
            destFile.setLastModified(System.currentTimeMillis())
        } catch (t: Throwable) {
            destFile.delete()
            SafeTempStorageCleanup.unmarkShareFileActive(destFile)
            throw t
        }
        SafeTempStorageCleanup.cleanOldSharedExports(
            context = context,
            activeShareFile = destFile
        )
        SafeTempStorageCleanup.unmarkShareFileActive(destFile)
        return destFile
    }

    fun buildShareIntent(context: Context, wavFile: File, chooserTitle: String = "Share Audio (WAV)"): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            wavFile
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, wavFile.nameWithoutExtension)
            clipData = ClipData.newUri(context.contentResolver, wavFile.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
