package com.osei.aimusicstudio.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import com.osei.aimusicstudio.ai.MemoryProfiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class MixerTrackMixSpec(
    val trackId: Long,
    val name: String,
    val audioUri: String?,
    val durationMs: Long,
    val volume: Float,
    val pan: Float,
    val isMuted: Boolean,
    val isSolo: Boolean,
    val isEnabled: Boolean,
    val offsetMs: Long
)

data class StereoGain(
    val left: Float,
    val right: Float,
    val isAudible: Boolean
)

object TrackGainCalculator {
    fun computeAllGains(
        tracks: List<MixerTrackMixSpec>,
        masterVolume: Float
    ): Map<Long, StereoGain> {
        val anySolo = tracks.any { it.isEnabled && !it.audioUri.isNullOrBlank() && it.isSolo }
        val clampedMaster = masterVolume.coerceIn(0f, 1.5f)
        val result = LinkedHashMap<Long, StereoGain>(tracks.size)
        for (t in tracks) {
            val isAudible = t.isEnabled &&
                !t.audioUri.isNullOrBlank() &&
                !t.isMuted &&
                (!anySolo || t.isSolo)
            if (!isAudible || t.volume <= 0f || clampedMaster <= 0f) {
                result[t.trackId] = StereoGain(0f, 0f, false)
                continue
            }
            val clampedVol = t.volume.coerceIn(0f, 1.5f)
            val pan = t.pan.coerceIn(-1f, 1f)
            // Constant-power pan law across [-1.0 (L) .. +1.0 (R)]
            val angle = ((pan + 1.0) * 0.5) * (PI / 2.0)
            var left = (clampedVol * clampedMaster * cos(angle)).toFloat()
            var right = (clampedVol * clampedMaster * sin(angle)).toFloat()
            if (abs(left) < 1e-6f) left = 0f
            if (abs(right) < 1e-6f) right = 0f
            result[t.trackId] = StereoGain(left, right, true)
        }
        return result
    }
}

/**
 * Memory-bounded random-access PCM WAV reader.
 * Holds only a small reusable byte scratch buffer (~16-32 KB), never the full audio file.
 */
class PcmWavSeekReader(
    val file: File,
    val header: WavHeaderInfo
) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val bytesPerSample = header.bitsPerSample / 8
    private val bytesPerFrame = header.channels * bytesPerSample
    val totalFrames: Long = header.dataSizeBytes / bytesPerFrame.coerceAtLeast(1)
    private var scratchBytes = ByteArray(4096 * bytesPerFrame.coerceAtLeast(4))

    init {
        require(header.sampleRate == StreamingAudioDecoder.TARGET_SAMPLE_RATE) {
            "Expected ${StreamingAudioDecoder.TARGET_SAMPLE_RATE} Hz WAV, got ${header.sampleRate} Hz"
        }
    }

    /**
     * Reads [frameCount] stereo frames starting at [timelineFrame] (accounting for track [offsetFrames])
     * and accumulates them directly into [mixInterleaved] scaled by [leftGain] and [rightGain].
     */
    @Synchronized
    fun accumulateIntoMix(
        timelineFrame: Long,
        offsetFrames: Long,
        frameCount: Int,
        leftGain: Float,
        rightGain: Float,
        mixInterleaved: FloatArray
    ) {
        if (leftGain <= 0f && rightGain <= 0f) return
        val firstSrcFrame = timelineFrame - offsetFrames
        val lastSrcFrameExclusive = firstSrcFrame + frameCount

        if (lastSrcFrameExclusive <= 0L || firstSrcFrame >= totalFrames) {
            return
        }

        val destStart = if (firstSrcFrame < 0L) (-firstSrcFrame).toInt() else 0
        val srcReadStart = max(0L, firstSrcFrame)
        val srcReadEnd = min(totalFrames, lastSrcFrameExclusive)
        val framesToRead = (srcReadEnd - srcReadStart).toInt()
        if (framesToRead <= 0) return

        val byteOffset = header.dataOffset + (srcReadStart * bytesPerFrame)
        val bytesNeeded = framesToRead * bytesPerFrame
        if (scratchBytes.size < bytesNeeded) {
            scratchBytes = ByteArray(bytesNeeded)
        }

        raf.seek(byteOffset)
        var readTotal = 0
        while (readTotal < bytesNeeded) {
            val n = raf.read(scratchBytes, readTotal, bytesNeeded - readTotal)
            if (n <= 0) break
            readTotal += n
        }
        val actualFrames = readTotal / bytesPerFrame
        if (actualFrames <= 0) return

        val bb = ByteBuffer.wrap(scratchBytes, 0, actualFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until actualFrames) {
            val l: Float
            val r: Float
            if (header.audioFormat == 1) {
                l = bb.short / 32768.0f
                r = if (header.channels >= 2) {
                    val rv = bb.short / 32768.0f
                    for (c in 2 until header.channels) bb.short
                    rv
                } else l
            } else {
                l = bb.float
                r = if (header.channels >= 2) {
                    val rv = bb.float
                    for (c in 2 until header.channels) bb.float
                    rv
                } else l
            }
            val outIdx = (destStart + i) * 2
            mixInterleaved[outIdx] += l * leftGain
            mixInterleaved[outIdx + 1] += r * rightGain
        }
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Throwable) {
        }
    }
}

/**
 * Prepares tracks as disk-backed 44.1 kHz PCM WAVs sequentially (max 1 decode at a time)
 * to keep memory low on 4 GB RAM devices.
 */
object MixerTrackCachePreparer {
    private val decodeMutex = Mutex()

    suspend fun prepareReadableWavFile(
        context: Context,
        audioUriString: String,
        isCancelled: () -> Boolean = { false }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(audioUriString)

            // Fast zero-copy path for local 44.1kHz PCM/Float WAV files
            if (uri.scheme == "file") {
                val directFile = File(uri.path ?: return@withContext Result.failure(IllegalArgumentException("Invalid file URI")))
                if (!directFile.exists()) {
                    return@withContext Result.failure(IllegalStateException("Audio file not found: ${directFile.name}"))
                }
                val header = WavStreamReader.tryReadHeader(directFile)
                if (header != null && header.sampleRate == StreamingAudioDecoder.TARGET_SAMPLE_RATE) {
                    return@withContext Result.success(directFile)
                }
            }

            val cacheDir = File(context.cacheDir, "mixer_pcm_cache").apply { mkdirs() }
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(audioUriString.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val cachedWav = File(cacheDir, "$hash.wav")
            val cachedHeader = WavStreamReader.tryReadHeader(cachedWav)
            if (cachedHeader != null && cachedHeader.sampleRate == StreamingAudioDecoder.TARGET_SAMPLE_RATE && cachedHeader.dataSizeBytes > 0L) {
                return@withContext Result.success(cachedWav)
            }

            // Serialize decoding so 4 tracks never decode simultaneously on 4 GB RAM
            decodeMutex.withLock {
                // Double check after acquiring lock
                val rechecked = WavStreamReader.tryReadHeader(cachedWav)
                if (rechecked != null && rechecked.sampleRate == StreamingAudioDecoder.TARGET_SAMPLE_RATE && rechecked.dataSizeBytes > 0L) {
                    return@withLock Result.success(cachedWav)
                }

                val tmpWav = File(cacheDir, "$hash.tmp.wav")
                var framesWritten = 0L
                try {
                    WavStreamWriter(tmpWav, StreamingAudioDecoder.TARGET_SAMPLE_RATE, 2).use { writer ->
                        StreamingAudioDecoder.streamStereoChunks(
                            context = context,
                            uri = uri,
                            chunkFrames = 4096,
                            isCancelled = isCancelled,
                            onChunk = { stereo, frameCount, _ ->
                                if (isCancelled()) throw CancellationException("Cancelled")
                                writer.writeFloatSamples(stereo, frameCount * 2)
                                framesWritten += frameCount
                            }
                        )
                    }
                    if (isCancelled() || framesWritten <= 0L) {
                        tmpWav.delete()
                        if (isCancelled()) throw CancellationException("Cancelled")
                        return@withLock Result.failure(IllegalStateException("Decoded 0 audio frames from track"))
                    }
                    if (cachedWav.exists()) cachedWav.delete()
                    if (!tmpWav.renameTo(cachedWav)) {
                        tmpWav.copyTo(cachedWav, overwrite = true)
                        tmpWav.delete()
                    }
                    Result.success(cachedWav)
                } catch (oom: OutOfMemoryError) {
                    tmpWav.delete()
                    System.gc()
                    Result.failure(IllegalStateException("Insufficient RAM while decoding track audio", oom))
                } catch (t: Throwable) {
                    tmpWav.delete()
                    Result.failure(t)
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun deleteCachedWavForUri(context: Context, audioUriString: String) {
        try {
            val cacheDir = File(context.cacheDir, "mixer_pcm_cache")
            if (!cacheDir.exists()) return
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(audioUriString.toByteArray())
                .joinToString("") { "%02x".format(it) }
            File(cacheDir, "$hash.wav").delete()
            File(cacheDir, "$hash.tmp.wav").delete()
        } catch (_: Throwable) {
        }
    }

    fun clearAllCache(context: Context) {
        try {
            File(context.cacheDir, "mixer_pcm_cache").deleteRecursively()
        } catch (_: Throwable) {
        }
    }
}

object MultitrackWavExporter {
    private const val EXPORT_CHUNK_FRAMES = 4096

    suspend fun exportMixToFile(
        context: Context,
        tracks: List<MixerTrackMixSpec>,
        masterVolume: Float,
        outputFile: File,
        targetSampleRateHz: Int = StreamingAudioDecoder.TARGET_SAMPLE_RATE,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val gains = TrackGainCalculator.computeAllGains(tracks, masterVolume)
        val audibleTracks = tracks.filter { gains[it.trackId]?.isAudible == true }
        if (audibleTracks.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No audible tracks available to export (all tracks are empty, muted, or disabled).")
            )
        }

        val readers = LinkedHashMap<Long, PcmWavSeekReader>()
        val tempFile = File(outputFile.parentFile ?: context.cacheDir, "${outputFile.name}.mix.tmp")

        try {
            val jobContext = currentCoroutineContext()
            // Prepare & open readers one by one
            for ((idx, track) in audibleTracks.withIndex()) {
                jobContext.ensureActive()
                if (isCancelled()) throw CancellationException("Export cancelled")
                val uriStr = track.audioUri ?: continue
                val wavFile = MixerTrackCachePreparer.prepareReadableWavFile(context, uriStr, isCancelled)
                    .getOrThrow()
                val header = WavStreamReader.tryReadHeader(wavFile)
                    ?: throw IllegalStateException("Unreadable WAV stream for track '${track.name}'")
                readers[track.trackId] = PcmWavSeekReader(wavFile, header)
                onProgress(0.05f + 0.15f * ((idx + 1).toFloat() / audibleTracks.size.toFloat()))
            }

            if (readers.isEmpty()) {
                throw IllegalStateException("No readable audio streams found for export")
            }

            var totalTimelineFrames = 0L
            for (track in audibleTracks) {
                val reader = readers[track.trackId] ?: continue
                val offsetFrames = (track.offsetMs * StreamingAudioDecoder.TARGET_SAMPLE_RATE) / 1000L
                val trackEnd = max(0L, offsetFrames + reader.totalFrames)
                if (trackEnd > totalTimelineFrames) {
                    totalTimelineFrames = trackEnd
                }
            }
            if (totalTimelineFrames <= 0L) {
                throw IllegalStateException("Mixed timeline has zero duration")
            }

            val mixBuf = FloatArray(EXPORT_CHUNK_FRAMES * 2)
            val resampler = StreamingStereoResampler(StreamingAudioDecoder.TARGET_SAMPLE_RATE, targetSampleRateHz)
            WavStreamWriter(tempFile, targetSampleRateHz, 2).use { writer ->
                var frameCursor = 0L
                while (frameCursor < totalTimelineFrames) {
                    jobContext.ensureActive()
                    if (isCancelled()) throw CancellationException("Export cancelled")

                    val blockFrames = min(EXPORT_CHUNK_FRAMES.toLong(), totalTimelineFrames - frameCursor).toInt()
                    val sampleCount = blockFrames * 2
                    java.util.Arrays.fill(mixBuf, 0, sampleCount, 0f)

                    for (track in audibleTracks) {
                        val reader = readers[track.trackId] ?: continue
                        val gain = gains[track.trackId] ?: continue
                        val offsetFrames = (track.offsetMs * StreamingAudioDecoder.TARGET_SAMPLE_RATE) / 1000L
                        reader.accumulateIntoMix(
                            timelineFrame = frameCursor,
                            offsetFrames = offsetFrames,
                            frameCount = blockFrames,
                            leftGain = gain.left,
                            rightGain = gain.right,
                            mixInterleaved = mixBuf
                        )
                    }

                    for (i in 0 until sampleCount) {
                        mixBuf[i] = mixBuf[i].coerceIn(-1.0f, 1.0f)
                    }
                    resampler.processChunk(mixBuf, blockFrames) { outArr, count ->
                        writer.writeFloatSamples(outArr, count)
                    }
                    frameCursor += blockFrames

                    val mixProgress = 0.20f + 0.80f * (frameCursor.toFloat() / totalTimelineFrames.toFloat())
                    onProgress(mixProgress.coerceIn(0.20f, 0.99f))
                }
                resampler.flush { outArr, count ->
                    writer.writeFloatSamples(outArr, count)
                }
            }

            if (outputFile.exists()) outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(1.0f)
            Result.success(outputFile)
        } catch (ce: CancellationException) {
            tempFile.delete()
            Result.failure(ce)
        } catch (oom: OutOfMemoryError) {
            tempFile.delete()
            System.gc()
            Result.failure(IllegalStateException("Out of memory during WAV mix export", oom))
        } catch (t: Throwable) {
            tempFile.delete()
            Result.failure(t)
        } finally {
            readers.values.forEach { it.close() }
            readers.clear()
        }
    }

    suspend fun exportSingleTrackToFile(
        context: Context,
        track: MixerTrackMixSpec,
        outputFile: File,
        targetSampleRateHz: Int = StreamingAudioDecoder.TARGET_SAMPLE_RATE,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val uriStr = track.audioUri
        if (uriStr.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Track '${track.name}' has no audio loaded to export.")
            )
        }

        val standaloneSpec = track.copy(isMuted = false, isSolo = false, isEnabled = true)
        val gain = TrackGainCalculator.computeAllGains(listOf(standaloneSpec), masterVolume = 1.0f)[track.trackId]
            ?: StereoGain(1f, 1f, true)
        if (!gain.isAudible) {
            return@withContext Result.failure(
                IllegalStateException("Track '${track.name}' volume is 0% — increase volume before exporting.")
            )
        }

        var reader: PcmWavSeekReader? = null
        val tempFile = File(outputFile.parentFile ?: context.cacheDir, "${outputFile.name}.track.tmp")
        try {
            val jobContext = currentCoroutineContext()
            jobContext.ensureActive()
            if (isCancelled()) throw CancellationException("Track export cancelled")

            val wavFile = MixerTrackCachePreparer.prepareReadableWavFile(context, uriStr, isCancelled)
                .getOrThrow()
            val header = WavStreamReader.tryReadHeader(wavFile)
                ?: throw IllegalStateException("Unreadable WAV stream for track '${track.name}'")
            reader = PcmWavSeekReader(wavFile, header)
            val totalFrames = reader.totalFrames
            if (totalFrames <= 0L) {
                throw IllegalStateException("Track '${track.name}' has zero audio frames")
            }
            onProgress(0.15f)

            val mixBuf = FloatArray(EXPORT_CHUNK_FRAMES * 2)
            val resampler = StreamingStereoResampler(StreamingAudioDecoder.TARGET_SAMPLE_RATE, targetSampleRateHz)
            WavStreamWriter(tempFile, targetSampleRateHz, 2).use { writer ->
                var frameCursor = 0L
                while (frameCursor < totalFrames) {
                    jobContext.ensureActive()
                    if (isCancelled()) throw CancellationException("Track export cancelled")

                    val blockFrames = min(EXPORT_CHUNK_FRAMES.toLong(), totalFrames - frameCursor).toInt()
                    val sampleCount = blockFrames * 2
                    java.util.Arrays.fill(mixBuf, 0, sampleCount, 0f)
                    reader.accumulateIntoMix(
                        timelineFrame = frameCursor,
                        offsetFrames = 0L,
                        frameCount = blockFrames,
                        leftGain = gain.left,
                        rightGain = gain.right,
                        mixInterleaved = mixBuf
                    )
                    for (i in 0 until sampleCount) {
                        mixBuf[i] = mixBuf[i].coerceIn(-1.0f, 1.0f)
                    }
                    resampler.processChunk(mixBuf, blockFrames) { outArr, count ->
                        writer.writeFloatSamples(outArr, count)
                    }
                    frameCursor += blockFrames
                    onProgress((0.15f + 0.85f * (frameCursor.toFloat() / totalFrames.toFloat())).coerceIn(0.15f, 0.99f))
                }
                resampler.flush { outArr, count ->
                    writer.writeFloatSamples(outArr, count)
                }
            }

            if (outputFile.exists()) outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(1.0f)
            Result.success(outputFile)
        } catch (ce: CancellationException) {
            tempFile.delete()
            Result.failure(ce)
        } catch (oom: OutOfMemoryError) {
            tempFile.delete()
            System.gc()
            Result.failure(IllegalStateException("Out of memory during track WAV export", oom))
        } catch (t: Throwable) {
            tempFile.delete()
            Result.failure(t)
        } finally {
            reader?.close()
        }
    }
}

/**
 * Synchronized streaming multitrack audio engine using a single shared sample clock
 * driving one AudioTrack (MODE_STREAM) so tracks never drift out of sync.
 */
class MultitrackAudioEngine(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = StreamingAudioDecoder.TARGET_SAMPLE_RATE
        private const val RENDER_CHUNK_FRAMES = 2048 // ~46 ms latency @ 44.1 kHz
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var focusDuckMultiplier = 1.0f

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var noisyReceiverRegistered = false

    private val readers = ConcurrentHashMap<Long, PcmWavSeekReader>()
    private val trackSources = ConcurrentHashMap<Long, String>()

    @Volatile
    private var currentMixSpecs: List<MixerTrackMixSpec> = emptyList()

    @Volatile
    private var currentMasterVolume: Float = 0.85f

    private val currentFrame = AtomicLong(0L)
    private val seekPending = AtomicBoolean(false)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private val _preparingTrackId = MutableStateFlow<Long?>(null)
    val preparingTrackId: StateFlow<Long?> = _preparingTrackId.asStateFlow()

    private val _preparedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val preparedTrackIds: StateFlow<Set<Long>> = _preparedTrackIds.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _activeReaderCount = MutableStateFlow(0)
    val activeReaderCount: StateFlow<Int> = _activeReaderCount.asStateFlow()

    private val _lowMemoryNotice = MutableStateFlow<String?>(null)
    val lowMemoryNotice: StateFlow<String?> = _lowMemoryNotice.asStateFlow()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusDuckMultiplier = 1.0f
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusDuckMultiplier = 0.3f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusDuckMultiplier = 1.0f
            }
        }
    }

    fun updateMixState(tracks: List<MixerTrackMixSpec>, masterVolume: Float) {
        currentMixSpecs = tracks
        currentMasterVolume = masterVolume.coerceIn(0f, 1.5f)
        recalculateTotalDuration()
    }

    suspend fun syncTrackReaders(tracks: List<MixerTrackMixSpec>) {
        currentMixSpecs = tracks
        val activeIds = tracks.map { it.trackId }.toSet()

        // Release readers for removed tracks immediately
        val staleIds = readers.keys.filter { it !in activeIds }
        for (id in staleIds) {
            releaseTrackReader(id)
        }

        // Prepare any newly added or replaced track URIs sequentially (low RAM)
        for (track in tracks) {
            val uri = track.audioUri
            if (uri.isNullOrBlank()) {
                if (readers.containsKey(track.trackId)) {
                    releaseTrackReader(track.trackId)
                }
                continue
            }
            val existingUri = trackSources[track.trackId]
            if (existingUri == uri && readers.containsKey(track.trackId)) {
                continue
            }
            if (existingUri != null && existingUri != uri) {
                releaseTrackReader(track.trackId)
            }
            prepareSingleTrack(track.trackId, track.name, uri)
        }
        recalculateTotalDuration()
    }

    private suspend fun prepareSingleTrack(trackId: Long, trackName: String, audioUri: String) {
        val memSnap = MemoryProfiler.captureSnapshot(appContext)
        if (memSnap.isSystemLowMemory) {
            _lowMemoryNotice.value = "System memory low (${memSnap.deviceAvailableMb} MB free); limiting active track decode."
        }

        _preparingTrackId.value = trackId
        val result = MixerTrackCachePreparer.prepareReadableWavFile(appContext, audioUri)
        result.onSuccess { wavFile ->
            val header = WavStreamReader.tryReadHeader(wavFile)
            if (header == null) {
                _errorMessage.value = "Track '$trackName' could not be decoded as valid PCM audio"
            } else {
                try {
                    val reader = PcmWavSeekReader(wavFile, header)
                    readers[trackId]?.close()
                    readers[trackId] = reader
                    trackSources[trackId] = audioUri
                    _preparedTrackIds.value = readers.keys.toSet()
                    _activeReaderCount.value = readers.size
                    _errorMessage.value = null
                } catch (t: Throwable) {
                    _errorMessage.value = "Failed to open stream for '$trackName': ${t.localizedMessage}"
                }
            }
        }.onFailure { err ->
            _errorMessage.value = "Track '$trackName' load error: ${err.localizedMessage ?: "Unsupported format"}"
        }
        _preparingTrackId.value = null
    }

    fun releaseTrackReader(trackId: Long, clearDiskCacheForUri: String? = null) {
        readers.remove(trackId)?.close()
        val oldUri = trackSources.remove(trackId) ?: clearDiskCacheForUri
        _preparedTrackIds.value = readers.keys.toSet()
        _activeReaderCount.value = readers.size
        if (oldUri != null && trackSources.values.none { it == oldUri }) {
            MixerTrackCachePreparer.deleteCachedWavForUri(appContext, oldUri)
        }
        recalculateTotalDuration()
    }

    private fun recalculateTotalDuration() {
        var maxEndMs = 0L
        for (track in currentMixSpecs) {
            val reader = readers[track.trackId]
            val trackDurMs = if (reader != null) {
                (reader.totalFrames * 1000L) / SAMPLE_RATE
            } else {
                track.durationMs
            }
            if (!track.audioUri.isNullOrBlank() && trackDurMs > 0L) {
                val endMs = max(0L, track.offsetMs + trackDurMs)
                if (endMs > maxEndMs) maxEndMs = endMs
            }
        }
        _totalDurationMs.value = maxEndMs
    }

    fun play() {
        if (_isPlaying.value) return
        val hasAudio = currentMixSpecs.any { !it.audioUri.isNullOrBlank() && readers.containsKey(it.trackId) }
        if (!hasAudio) {
            _errorMessage.value = "Load at least one valid audio track before playing."
            return
        }
        if (!requestAudioFocus()) {
            _errorMessage.value = "Could not acquire audio focus from system."
            return
        }
        _errorMessage.value = null
        registerNoisyReceiver()

        val totalFrames = (_totalDurationMs.value * SAMPLE_RATE) / 1000L
        if (totalFrames > 0L && currentFrame.get() >= totalFrames) {
            currentFrame.set(0L)
            _positionMs.value = 0L
        }

        _isPlaying.value = true
        renderJob?.cancel()
        renderJob = scope.launch(Dispatchers.Default) {
            runStreamingRenderLoop()
        }
    }

    fun pause() {
        if (!_isPlaying.value) return
        _isPlaying.value = false
        renderJob?.cancel()
        renderJob = null
        try {
            audioTrack?.pause()
        } catch (_: Throwable) {
        }
        unregisterNoisyReceiver()
        abandonAudioFocus()
    }

    fun stop() {
        _isPlaying.value = false
        renderJob?.cancel()
        renderJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: Throwable) {
        }
        currentFrame.set(0L)
        _positionMs.value = 0L
        unregisterNoisyReceiver()
        abandonAudioFocus()
    }

    fun seekTo(positionMs: Long) {
        val dur = _totalDurationMs.value.coerceAtLeast(0L)
        val clampedMs = positionMs.coerceIn(0L, dur)
        val targetFrame = (clampedMs * SAMPLE_RATE) / 1000L
        currentFrame.set(targetFrame)
        _positionMs.value = clampedMs
        seekPending.set(true)
    }

    fun clearError() {
        _errorMessage.value = null
        _lowMemoryNotice.value = null
    }

    fun handleLowMemory() {
        pause()
        val activeUris = trackSources.values.toSet()
        // Keep active track readers closed until resumed, clear disk cache only for inactive tracks
        readers.values.forEach { it.close() }
        readers.clear()
        _preparedTrackIds.value = emptySet()
        _activeReaderCount.value = 0
        if (activeUris.isEmpty()) {
            MixerTrackCachePreparer.clearAllCache(appContext)
        }
        System.gc()
        _lowMemoryNotice.value = "Paused playback and released PCM stream readers due to low device memory."
    }

    private suspend fun runStreamingRenderLoop() {
        val minBufBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(RENDER_CHUNK_FRAMES * 4)
        val trackBufBytes = max(minBufBytes, RENDER_CHUNK_FRAMES * 4 * 2)

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(trackBufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()

            val mixBuf = FloatArray(RENDER_CHUNK_FRAMES * 2)
            val pcmOut = ShortArray(RENDER_CHUNK_FRAMES * 2)

            while (_isPlaying.value) {
                currentCoroutineContext().ensureActive()

                if (seekPending.getAndSet(false)) {
                    try {
                        track.pause()
                        track.flush()
                        track.play()
                    } catch (_: Throwable) {
                    }
                }

                val specs = currentMixSpecs
                val totalFrames = (_totalDurationMs.value * SAMPLE_RATE) / 1000L
                val framePos = currentFrame.get()
                if (totalFrames > 0L && framePos >= totalFrames) {
                    withContext(Dispatchers.Main) {
                        stop()
                    }
                    break
                }

                val framesToRender = if (totalFrames > 0L) {
                    min(RENDER_CHUNK_FRAMES.toLong(), totalFrames - framePos).toInt()
                } else {
                    RENDER_CHUNK_FRAMES
                }
                if (framesToRender <= 0) break

                val sampleCount = framesToRender * 2
                java.util.Arrays.fill(mixBuf, 0, sampleCount, 0f)

                val gains = TrackGainCalculator.computeAllGains(specs, currentMasterVolume * focusDuckMultiplier)
                for (spec in specs) {
                    val gain = gains[spec.trackId] ?: continue
                    if (!gain.isAudible) continue
                    val reader = readers[spec.trackId] ?: continue
                    val offsetFrames = (spec.offsetMs * SAMPLE_RATE) / 1000L
                    reader.accumulateIntoMix(
                        timelineFrame = framePos,
                        offsetFrames = offsetFrames,
                        frameCount = framesToRender,
                        leftGain = gain.left,
                        rightGain = gain.right,
                        mixInterleaved = mixBuf
                    )
                }

                for (i in 0 until sampleCount) {
                    val s = mixBuf[i].coerceIn(-1.0f, 1.0f)
                    pcmOut[i] = (s * 32767.0f).toInt().toShort()
                }

                val written = track.write(pcmOut, 0, sampleCount, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    throw IllegalStateException("AudioTrack write failed ($written)")
                }

                val nextFrame = framePos + framesToRender
                currentFrame.set(nextFrame)
                _positionMs.value = (nextFrame * 1000L) / SAMPLE_RATE
            }
        } catch (ce: CancellationException) {
            // Normal pause/stop cancellation
        } catch (oom: OutOfMemoryError) {
            handleLowMemory()
            _errorMessage.value = "Out of memory during multitrack playback; streams released safely."
        } catch (t: Throwable) {
            _isPlaying.value = false
            _errorMessage.value = "Playback error: ${t.localizedMessage ?: t::class.java.simpleName}"
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (_: Throwable) {
            }
            if (audioTrack === track) {
                audioTrack = null
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            try {
                androidx.core.content.ContextCompat.registerReceiver(
                    appContext,
                    noisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                noisyReceiverRegistered = true
            } catch (_: Throwable) {
            }
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            try {
                appContext.unregisterReceiver(noisyReceiver)
            } catch (_: Throwable) {
            }
            noisyReceiverRegistered = false
        }
    }

    fun release() {
        stop()
        readers.values.forEach { it.close() }
        readers.clear()
        trackSources.clear()
        _preparedTrackIds.value = emptySet()
        _activeReaderCount.value = 0
        scope.cancel()
    }
}
