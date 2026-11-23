package com.osei.aimusicstudio.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

data class TimelineClipSpec(
    val clipId: Long,
    val name: String,
    val sourceUri: String,
    val totalSourceDurationMs: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long = 0L,
    val effectsConfig: AudioEffectsConfig = AudioEffectsConfig(),
    val vocalConfig: VocalTransformConfig = VocalTransformConfig()
) {
    val sourceTrimmedDurationMs: Long
        get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0L)

    val outputDurationMs: Long
        get() {
            val tempo = vocalConfig.tempoRatio.coerceIn(0.5f, 2.0f)
            return ((sourceTrimmedDurationMs.toDouble() / tempo.toDouble()).toLong()).coerceAtLeast(1L)
        }

    val timelineEndMs: Long
        get() = timelineStartMs + outputDurationMs
}

data class EditorHistorySnapshot(
    val clips: List<TimelineClipSpec>,
    val selectedClipId: Long?
)

class TimelineHistoryManager<T>(
    private val maxDepth: Int = 30
) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    fun recordForUndo(previousState: T) {
        if (undoStack.size >= maxDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(previousState)
        redoStack.clear()
    }

    fun undo(currentState: T): T? {
        if (undoStack.isEmpty()) return null
        val prev = undoStack.removeLast()
        if (redoStack.size >= maxDepth) {
            redoStack.removeFirst()
        }
        redoStack.addLast(currentState)
        return prev
    }

    fun redo(currentState: T): T? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        if (undoStack.size >= maxDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(currentState)
        return next
    }

    fun allSnapshots(): List<T> = undoStack.toList() + redoStack.toList()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

object TimelineClipOperations {
    private const val MIN_CLIP_DURATION_MS = 100L

    fun trimClip(
        clips: List<TimelineClipSpec>,
        clipId: Long,
        newSourceStartMs: Long,
        newSourceEndMs: Long
    ): List<TimelineClipSpec> {
        return clips.map { clip ->
            if (clip.clipId != clipId) {
                clip
            } else {
                val maxDur = clip.totalSourceDurationMs.coerceAtLeast(MIN_CLIP_DURATION_MS)
                val clampedStart = newSourceStartMs.coerceIn(0L, (maxDur - MIN_CLIP_DURATION_MS).coerceAtLeast(0L))
                val clampedEnd = newSourceEndMs.coerceIn(clampedStart + MIN_CLIP_DURATION_MS, maxDur)
                clip.copy(sourceStartMs = clampedStart, sourceEndMs = clampedEnd)
            }
        }
    }

    fun splitClipAtTimelineMs(
        clips: List<TimelineClipSpec>,
        clipId: Long,
        splitTimelineMs: Long,
        secondHalfClipId: Long
    ): Result<List<TimelineClipSpec>> {
        val target = clips.firstOrNull { it.clipId == clipId }
            ?: return Result.failure(IllegalArgumentException("Clip not found for split"))

        val relativeOutMs = splitTimelineMs - target.timelineStartMs
        val tempo = target.vocalConfig.tempoRatio.coerceIn(0.5f, 2.0f)
        val splitSourceMs = target.sourceStartMs + (relativeOutMs * tempo).toLong()

        if (splitSourceMs - target.sourceStartMs < MIN_CLIP_DURATION_MS ||
            target.sourceEndMs - splitSourceMs < MIN_CLIP_DURATION_MS
        ) {
            return Result.failure(
                IllegalArgumentException("Split playhead must leave at least $MIN_CLIP_DURATION_MS ms on each side")
            )
        }

        val firstHalf = target.copy(
            name = "${target.name} (A)",
            sourceEndMs = splitSourceMs
        )
        val secondHalf = target.copy(
            clipId = secondHalfClipId,
            name = "${target.name} (B)",
            sourceStartMs = splitSourceMs,
            timelineStartMs = splitTimelineMs
        )

        val updated = clips.flatMap { c ->
            if (c.clipId == clipId) listOf(firstHalf, secondHalf) else listOf(c)
        }.sortedBy { it.timelineStartMs }

        return Result.success(updated)
    }

    fun moveClipTimelineStart(
        clips: List<TimelineClipSpec>,
        clipId: Long,
        newTimelineStartMs: Long
    ): List<TimelineClipSpec> {
        val clamped = newTimelineStartMs.coerceAtLeast(0L)
        return clips.map { c ->
            if (c.clipId == clipId) c.copy(timelineStartMs = clamped) else c
        }.sortedBy { it.timelineStartMs }
    }

    fun deleteClip(
        clips: List<TimelineClipSpec>,
        clipId: Long
    ): List<TimelineClipSpec> = clips.filterNot { it.clipId == clipId }
}

object TimelineAudioRenderer {
    private const val CHUNK_FRAMES = 4096

    /**
     * Extracts [clip.sourceStartMs .. clip.sourceEndMs] from [clip.sourceUri], applies the clip's
     * VocalTransformConfig & AudioEffectsConfig, and writes the resulting PCM WAV at [targetSampleRateHz].
     */
    suspend fun renderSingleClipToFile(
        context: Context,
        clip: TimelineClipSpec,
        outputFile: File,
        targetSampleRateHz: Int = StreamingAudioDecoder.TARGET_SAMPLE_RATE,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val trimTempFile = File(context.cacheDir, "clip_trim_${clip.clipId}_${System.nanoTime()}.wav")
        var reader: PcmWavSeekReader? = null

        try {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw CancellationException("Clip render cancelled")

            val sourceWav = MixerTrackCachePreparer.prepareReadableWavFile(
                context = context,
                audioUriString = clip.sourceUri,
                isCancelled = isCancelled
            ).getOrThrow()

            val header = WavStreamReader.tryReadHeader(sourceWav)
                ?: throw IllegalStateException("Unable to read WAV stream for clip '${clip.name}'")
            reader = PcmWavSeekReader(sourceWav, header)

            val totalFrames = reader.totalFrames
            val startFrame = ((clip.sourceStartMs * StreamingAudioDecoder.TARGET_SAMPLE_RATE) / 1000L)
                .coerceIn(0L, (totalFrames - 1L).coerceAtLeast(0L))
            val endFrame = ((clip.sourceEndMs * StreamingAudioDecoder.TARGET_SAMPLE_RATE) / 1000L)
                .coerceIn(startFrame + 1L, totalFrames)
            val clipFrames = (endFrame - startFrame).coerceAtLeast(1L)

            val needsTrimCopy = startFrame > 0L || endFrame < totalFrames
            if (clip.vocalConfig.isBypassed && clip.effectsConfig.isDspBypassed && targetSampleRateHz == StreamingAudioDecoder.TARGET_SAMPLE_RATE && !needsTrimCopy) {
                // Direct copy when completely unedited
                sourceWav.copyTo(outputFile, overwrite = true)
                onProgress(1.0f)
                return@withContext Result.success(outputFile)
            }

            if (clip.vocalConfig.isBypassed) {
                // Stream trimmed frame range directly through DSP chain + resampler in one pass
                val dspChain = StreamingAudioDspChain(StreamingAudioDecoder.TARGET_SAMPLE_RATE)
                val resampler = StreamingStereoResampler(StreamingAudioDecoder.TARGET_SAMPLE_RATE, targetSampleRateHz)
                val buf = FloatArray(CHUNK_FRAMES * 2)
                WavStreamWriter(outputFile, targetSampleRateHz, 2).use { writer ->
                    var offset = 0L
                    while (offset < clipFrames) {
                        currentCoroutineContext().ensureActive()
                        if (isCancelled()) throw CancellationException("Clip render cancelled")
                        val n = min(CHUNK_FRAMES.toLong(), clipFrames - offset).toInt()
                        Arrays.fill(buf, 0, n * 2, 0f)
                        reader.accumulateIntoMix(
                            timelineFrame = startFrame + offset,
                            offsetFrames = 0L,
                            frameCount = n,
                            leftGain = 1f,
                            rightGain = 1f,
                            mixInterleaved = buf
                        )
                        dspChain.processInPlace(buf, n, clip.effectsConfig)
                        resampler.processChunk(buf, n) { outArr, count ->
                            writer.writeFloatSamples(outArr, count)
                        }
                        offset += n
                        onProgress((offset.toFloat() / clipFrames.toFloat()).coerceIn(0f, 0.99f))
                    }
                    resampler.flush { outArr, count ->
                        writer.writeFloatSamples(outArr, count)
                    }
                }
                onProgress(1.0f)
                return@withContext Result.success(outputFile)
            }

            // Write trimmed range to small temp WAV on disk, close source reader first, then run WSOLA/pitch DSP
            val buf = FloatArray(CHUNK_FRAMES * 2)
            WavStreamWriter(trimTempFile, StreamingAudioDecoder.TARGET_SAMPLE_RATE, 2).use { trimWriter ->
                var offset = 0L
                while (offset < clipFrames) {
                    currentCoroutineContext().ensureActive()
                    if (isCancelled()) throw CancellationException("Clip render cancelled")
                    val n = min(CHUNK_FRAMES.toLong(), clipFrames - offset).toInt()
                    Arrays.fill(buf, 0, n * 2, 0f)
                    reader.accumulateIntoMix(
                        timelineFrame = startFrame + offset,
                        offsetFrames = 0L,
                        frameCount = n,
                        leftGain = 1f,
                        rightGain = 1f,
                        mixInterleaved = buf
                    )
                    trimWriter.writeFloatSamples(buf, n * 2)
                    offset += n
                    onProgress((offset.toFloat() / clipFrames.toFloat()) * 0.25f)
                }
            }
            reader.close()
            reader = null

            val processor = StreamingPitchTempoProcessor(StreamingAudioDecoder.TARGET_SAMPLE_RATE)
            processor.transformFile(
                context = context,
                inputUri = Uri.fromFile(trimTempFile),
                outputWavFile = outputFile,
                config = clip.vocalConfig,
                effects = clip.effectsConfig,
                targetSampleRateHz = targetSampleRateHz,
                isCancelled = isCancelled,
                onProgress = { p -> onProgress(0.25f + p * 0.75f) }
            )
        } catch (ce: CancellationException) {
            outputFile.delete()
            Result.failure(ce)
        } catch (oom: OutOfMemoryError) {
            outputFile.delete()
            System.gc()
            Result.failure(IllegalStateException("Low memory during audio clip processing", oom))
        } catch (t: Throwable) {
            outputFile.delete()
            Result.failure(t)
        } finally {
            reader?.close()
            trimTempFile.delete()
        }
    }

    /**
     * Merges a list of clips sequentially end-to-end into a single WAV file (chunked on disk, 4 GB RAM safe).
     */
    suspend fun mergeClipsSequentiallyToFile(
        context: Context,
        clips: List<TimelineClipSpec>,
        outputFile: File,
        targetSampleRateHz: Int = StreamingAudioDecoder.TARGET_SAMPLE_RATE,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        if (clips.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Select at least 1 clip to merge"))
        }

        val ordered = clips.sortedBy { it.timelineStartMs }
        val tempRendered = mutableListOf<File>()
        val tempOutFile = File(outputFile.parentFile ?: context.cacheDir, "${outputFile.name}.merge.tmp")

        try {
            val jobContext = currentCoroutineContext()
            val mixBuf = FloatArray(CHUNK_FRAMES * 2)
            val resampler = StreamingStereoResampler(StreamingAudioDecoder.TARGET_SAMPLE_RATE, targetSampleRateHz)

            WavStreamWriter(tempOutFile, targetSampleRateHz, 2).use { writer ->
                for ((idx, clip) in ordered.withIndex()) {
                    jobContext.ensureActive()
                    if (isCancelled()) throw CancellationException("Merge cancelled")

                    val renderedClip = File(context.cacheDir, "merge_seg_${clip.clipId}_${System.nanoTime()}.wav")
                    tempRendered.add(renderedClip)

                    renderSingleClipToFile(
                        context = context,
                        clip = clip,
                        outputFile = renderedClip,
                        targetSampleRateHz = StreamingAudioDecoder.TARGET_SAMPLE_RATE,
                        isCancelled = isCancelled,
                        onProgress = { segProg ->
                            val base = idx.toFloat() / ordered.size.toFloat()
                            val span = 0.7f / ordered.size.toFloat()
                            onProgress(base * 0.7f + segProg * span)
                        }
                    ).getOrThrow()

                    val hdr = WavStreamReader.tryReadHeader(renderedClip)
                        ?: throw IllegalStateException("Merged segment unreadable for '${clip.name}'")

                    PcmWavSeekReader(renderedClip, hdr).use { segReader ->
                        var cursor = 0L
                        val total = segReader.totalFrames
                        while (cursor < total) {
                            jobContext.ensureActive()
                            if (isCancelled()) throw CancellationException("Merge cancelled")
                            val n = min(CHUNK_FRAMES.toLong(), total - cursor).toInt()
                            Arrays.fill(mixBuf, 0, n * 2, 0f)
                            segReader.accumulateIntoMix(cursor, 0L, n, 1f, 1f, mixBuf)
                            resampler.processChunk(mixBuf, n) { outArr, count ->
                                writer.writeFloatSamples(outArr, count)
                            }
                            cursor += n
                        }
                    }

                    renderedClip.delete()
                }
                resampler.flush { outArr, count ->
                    writer.writeFloatSamples(outArr, count)
                }
            }

            if (outputFile.exists()) outputFile.delete()
            if (!tempOutFile.renameTo(outputFile)) {
                tempOutFile.copyTo(outputFile, overwrite = true)
                tempOutFile.delete()
            }
            onProgress(1.0f)
            Result.success(outputFile)
        } catch (ce: CancellationException) {
            tempOutFile.delete()
            Result.failure(ce)
        } catch (oom: OutOfMemoryError) {
            tempOutFile.delete()
            System.gc()
            Result.failure(IllegalStateException("Insufficient RAM while merging audio clips", oom))
        } catch (t: Throwable) {
            tempOutFile.delete()
            Result.failure(t)
        } finally {
            tempRendered.forEach { it.delete() }
        }
    }

    /**
     * Renders the entire edited timeline (honoring each clip's timelineStartMs, gaps, overlaps,
     * DSP effects, pitch/tempo transforms, and target sample rate) into a single WAV file.
     */
    suspend fun renderTimelineToFile(
        context: Context,
        clips: List<TimelineClipSpec>,
        outputFile: File,
        targetSampleRateHz: Int = StreamingAudioDecoder.TARGET_SAMPLE_RATE,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        if (clips.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Timeline has no clips to render"))
        }

        val renderedFiles = mutableListOf<Pair<Long, File>>()
        val readers = mutableListOf<Pair<Long, PcmWavSeekReader>>()
        val tempOutFile = File(outputFile.parentFile ?: context.cacheDir, "${outputFile.name}.tl.tmp")

        try {
            val jobContext = currentCoroutineContext()
            for ((idx, clip) in clips.withIndex()) {
                jobContext.ensureActive()
                if (isCancelled()) throw CancellationException("Timeline render cancelled")

                val segFile = File(context.cacheDir, "tl_seg_${clip.clipId}_${System.nanoTime()}.wav")
                renderSingleClipToFile(
                    context = context,
                    clip = clip,
                    outputFile = segFile,
                    targetSampleRateHz = StreamingAudioDecoder.TARGET_SAMPLE_RATE,
                    isCancelled = isCancelled,
                    onProgress = { p ->
                        val base = (idx.toFloat() / clips.size.toFloat()) * 0.6f
                        val span = (1f / clips.size.toFloat()) * 0.6f
                        onProgress(base + p * span)
                    }
                ).getOrThrow()

                renderedFiles.add(clip.timelineStartMs to segFile)
            }

            var totalTimelineFrames = 0L
            for ((timelineStartMs, segFile) in renderedFiles) {
                val hdr = WavStreamReader.tryReadHeader(segFile)
                    ?: throw IllegalStateException("Rendered timeline segment missing WAV header")
                val r = PcmWavSeekReader(segFile, hdr)
                val offsetFrames = (timelineStartMs * StreamingAudioDecoder.TARGET_SAMPLE_RATE) / 1000L
                val endFrame = max(0L, offsetFrames + r.totalFrames)
                if (endFrame > totalTimelineFrames) {
                    totalTimelineFrames = endFrame
                }
                readers.add(offsetFrames to r)
            }

            if (totalTimelineFrames <= 0L) {
                throw IllegalStateException("Timeline has zero audio duration")
            }

            val mixBuf = FloatArray(CHUNK_FRAMES * 2)
            val resampler = StreamingStereoResampler(StreamingAudioDecoder.TARGET_SAMPLE_RATE, targetSampleRateHz)
            WavStreamWriter(tempOutFile, targetSampleRateHz, 2).use { writer ->
                var cursor = 0L
                while (cursor < totalTimelineFrames) {
                    jobContext.ensureActive()
                    if (isCancelled()) throw CancellationException("Timeline render cancelled")

                    val blockFrames = min(CHUNK_FRAMES.toLong(), totalTimelineFrames - cursor).toInt()
                    val sampleCount = blockFrames * 2
                    Arrays.fill(mixBuf, 0, sampleCount, 0f)

                    for ((offsetFrames, r) in readers) {
                        r.accumulateIntoMix(
                            timelineFrame = cursor,
                            offsetFrames = offsetFrames,
                            frameCount = blockFrames,
                            leftGain = 1f,
                            rightGain = 1f,
                            mixInterleaved = mixBuf
                        )
                    }

                    for (i in 0 until sampleCount) {
                        val v = mixBuf[i]
                        mixBuf[i] = if (abs(v) > 0.98f) tanh(v.toDouble()).toFloat() else v
                    }

                    resampler.processChunk(mixBuf, blockFrames) { outArr, count ->
                        writer.writeFloatSamples(outArr, count)
                    }
                    cursor += blockFrames
                    onProgress((0.6f + 0.4f * (cursor.toFloat() / totalTimelineFrames.toFloat())).coerceIn(0.6f, 0.99f))
                }
                resampler.flush { outArr, count ->
                    writer.writeFloatSamples(outArr, count)
                }
            }

            if (outputFile.exists()) outputFile.delete()
            if (!tempOutFile.renameTo(outputFile)) {
                tempOutFile.copyTo(outputFile, overwrite = true)
                tempOutFile.delete()
            }
            onProgress(1.0f)
            Result.success(outputFile)
        } catch (ce: CancellationException) {
            tempOutFile.delete()
            Result.failure(ce)
        } catch (oom: OutOfMemoryError) {
            tempOutFile.delete()
            System.gc()
            Result.failure(IllegalStateException("Insufficient RAM during timeline mixdown", oom))
        } catch (t: Throwable) {
            tempOutFile.delete()
            Result.failure(t)
        } finally {
            readers.forEach { (_, r) -> r.close() }
            readers.clear()
            renderedFiles.forEach { (_, f) -> f.delete() }
        }
    }
}
