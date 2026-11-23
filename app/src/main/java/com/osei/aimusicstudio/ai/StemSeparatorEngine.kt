package com.osei.aimusicstudio.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import com.osei.aimusicstudio.audio.StreamingAudioDecoder
import com.osei.aimusicstudio.audio.WavStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    PROCESSING,
    COMPLETED,
    CANCELLED,
    ERROR
}

data class SeparationResult(
    val stemFiles: Map<String, File>,
    val benchmark: InferenceBenchmark,
    val modelSpec: SeparationModelSpec
)

class StemSeparatorEngine(private val context: Context) {
    private val _selectedModel = MutableStateFlow(SeparationModelCatalog.models.first())
    val selectedModel: StateFlow<SeparationModelSpec> = _selectedModel.asStateFlow()

    private val _modelStatus = MutableStateFlow(ModelStatus.NOT_DOWNLOADED)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusMessage = MutableStateFlow("Select a model to check availability")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _memorySnapshot = MutableStateFlow(MemoryProfiler.captureSnapshot(context))
    val memorySnapshot: StateFlow<MemorySnapshot> = _memorySnapshot.asStateFlow()

    private val _lastBenchmark = MutableStateFlow<InferenceBenchmark?>(null)
    val lastBenchmark: StateFlow<InferenceBenchmark?> = _lastBenchmark.asStateFlow()

    private val _modelSha256 = MutableStateFlow<String?>(null)
    val modelSha256: StateFlow<String?> = _modelSha256.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    private val modelsDir: File
        get() = File(context.filesDir, "onnx_models").apply { mkdirs() }

    private fun modelFileFor(spec: SeparationModelSpec): File = File(modelsDir, spec.fileName)

    init {
        checkModelAvailability()
    }

    fun selectModel(spec: SeparationModelSpec) {
        _selectedModel.value = spec
        _errorMessage.value = null
        checkModelAvailability()
    }

    fun refreshMemorySnapshot() {
        _memorySnapshot.value = MemoryProfiler.captureSnapshot(context)
    }

    fun checkModelAvailability() {
        refreshMemorySnapshot()
        val spec = _selectedModel.value
        if (spec.availability == ModelAvailability.UNAVAILABLE) {
            _modelStatus.value = ModelStatus.NOT_DOWNLOADED
            _modelSha256.value = null
            _statusMessage.value = spec.unavailableReason ?: "${spec.displayName} is unavailable on this device."
            return
        }
        val file = modelFileFor(spec)
        if (file.exists() && file.length() > 1024L) {
            _modelStatus.value = ModelStatus.READY
            _statusMessage.value = "Model file present (${file.length() / (1024 * 1024)} MB). Ready for validation & CPU inference."
        } else {
            _modelStatus.value = ModelStatus.NOT_DOWNLOADED
            _modelSha256.value = null
            _statusMessage.value = if (spec.downloadUrl != null) {
                "Model weights not downloaded (~${spec.approximateDiskMb} MB required)."
            } else {
                "No local ONNX weights installed for ${spec.displayName}. Import a verified .onnx file from storage."
            }
        }
    }

    fun cancelCurrentOperation() {
        cancelRequested.set(true)
        _statusMessage.value = "Cancelling and cleaning up buffers..."
    }

    /**
     * Downloads verified open-source ONNX weights ONLY when user explicitly consents.
     * Never writes fake fallback bytes if network or storage fails.
     */
    suspend fun downloadModelWithConsent(): Result<File> = withContext(Dispatchers.IO) {
        val spec = _selectedModel.value
        if (spec.availability == ModelAvailability.UNAVAILABLE) {
            val reason = spec.unavailableReason ?: "${spec.displayName} is marked unavailable."
            _errorMessage.value = reason
            _modelStatus.value = ModelStatus.ERROR
            return@withContext Result.failure(IllegalStateException(reason))
        }
        val urlStr = spec.downloadUrl
            ?: return@withContext Result.failure<File>(
                IllegalStateException("Model '${spec.displayName}' has no automatic download URL. Import a verified local .onnx file instead.")
            ).also {
                _errorMessage.value = it.exceptionOrNull()?.message
                _modelStatus.value = ModelStatus.ERROR
            }

        cancelRequested.set(false)
        _errorMessage.value = null
        _modelStatus.value = ModelStatus.DOWNLOADING
        _progress.value = 0f
        _statusMessage.value = "Connecting to model repository..."

        val targetFile = modelFileFor(spec)
        val tempFile = File(modelsDir, "${spec.fileName}.download.tmp")
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Download failed with HTTP $responseCode from repository")
            }

            val totalBytes = connection.contentLengthLong
            val availableBytes = modelsDir.usableSpace
            if (totalBytes > 0L && availableBytes < totalBytes + (32L * 1024L * 1024L)) {
                throw IllegalStateException("Insufficient storage space (${availableBytes / (1024 * 1024)} MB free)")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelRequested.get()) {
                            throw CancellationException("Model download cancelled by user")
                        }
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalBytes > 0L) {
                            _progress.value = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
                        }
                        _statusMessage.value = "Downloading: ${downloaded / (1024 * 1024)} MB" +
                            if (totalBytes > 0L) " / ${totalBytes / (1024 * 1024)} MB" else ""
                    }
                    output.flush()
                }
            }

            if (tempFile.length() < 1024L) {
                throw IllegalStateException("Downloaded file is too small to be a valid ONNX model")
            }

            // Validate ONNX loadability before promoting to active model
            _statusMessage.value = "Verifying ONNX model integrity..."
            validateOnnxModelFile(tempFile)

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            _modelSha256.value = sha
            _progress.value = 1f
            _modelStatus.value = ModelStatus.READY
            _statusMessage.value = "Model verified & ready (SHA-256: ${sha.take(12)}...)"
            Result.success(targetFile)
        } catch (ce: CancellationException) {
            tempFile.delete()
            _modelStatus.value = ModelStatus.CANCELLED
            _statusMessage.value = "Download cancelled"
            Result.failure(ce)
        } catch (oom: OutOfMemoryError) {
            tempFile.delete()
            System.gc()
            _errorMessage.value = "Out of memory while verifying ONNX model on 4 GB device"
            _modelStatus.value = ModelStatus.ERROR
            Result.failure(oom)
        } catch (e: Throwable) {
            tempFile.delete()
            _errorMessage.value = e.localizedMessage ?: "Model download/verification failed"
            _modelStatus.value = ModelStatus.ERROR
            _statusMessage.value = "Download failed"
            Result.failure(e)
        } finally {
            connection?.disconnect()
            refreshMemorySnapshot()
        }
    }

    /**
     * Imports a user-supplied .onnx model via Storage Access Framework and validates it
     * with ONNX Runtime before accepting it.
     */
    suspend fun importLocalOnnxModel(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        val spec = _selectedModel.value
        if (spec.availability == ModelAvailability.UNAVAILABLE) {
            val reason = spec.unavailableReason ?: "${spec.displayName} is marked unavailable."
            _errorMessage.value = reason
            _modelStatus.value = ModelStatus.ERROR
            return@withContext Result.failure(IllegalStateException(reason))
        }
        val targetFile = modelFileFor(spec)
        val tempFile = File(modelsDir, "${spec.fileName}.import.tmp")
        cancelRequested.set(false)
        _errorMessage.value = null
        _modelStatus.value = ModelStatus.DOWNLOADING
        _statusMessage.value = "Copying & validating local ONNX model..."

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open selected ONNX file")

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (cancelRequested.get()) throw CancellationException("Import cancelled")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            validateOnnxModelFile(tempFile)

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            _modelSha256.value = sha
            _modelStatus.value = ModelStatus.READY
            _statusMessage.value = "Local ONNX model verified (SHA-256: ${sha.take(12)}...)"
            Result.success(targetFile)
        } catch (e: Throwable) {
            tempFile.delete()
            _errorMessage.value = "Invalid ONNX model: ${e.localizedMessage ?: "Corrupted or unsupported ONNX graph"}"
            _modelStatus.value = ModelStatus.ERROR
            Result.failure(e)
        } finally {
            refreshMemorySnapshot()
        }
    }

    /**
     * Validates that [file] can actually be opened by ONNX Runtime Mobile with low-memory options.
     */
    fun validateOnnxModelFile(file: File): Pair<String, LongArray> {
        if (!file.exists() || file.length() <= 16L) {
            throw IllegalArgumentException("ONNX model file is missing or empty")
        }
        val config = MemoryProfiler.recommendConfig(MemoryProfiler.captureSnapshot(context))
        val env = OrtEnvironment.getEnvironment()
        val opts = createLowMemorySessionOptions(config)
        var session: OrtSession? = null
        try {
            session = env.createSession(file.absolutePath, opts)
            val inputEntry = session.inputInfo.entries.firstOrNull()
                ?: throw OrtException("ONNX model has no input tensors")
            val tensorInfo = inputEntry.value.info as? TensorInfo
                ?: throw OrtException("ONNX input '${inputEntry.key}' is not a Tensor")
            if (session.outputInfo.isEmpty()) {
                throw OrtException("ONNX model has no output tensors")
            }
            return Pair(inputEntry.key, tensorInfo.shape)
        } finally {
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            try {
                opts.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun createLowMemorySessionOptions(config: LowMemoryInferenceConfig): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(config.intraOpThreads)
            setInterOpNumThreads(config.interOpThreads)
            setMemoryPatternOptimization(config.enableMemoryPattern)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }
    }

    /**
     * Performs genuine chunked ONNX stem separation with overlap-add reconstruction and
     * streaming temporary WAV file output. Never fabricates stems or switches to fake DSP.
     */
    suspend fun separateStems(
        audioUriString: String,
        projectId: Long = 0L,
        allowHeavyModelOverride: Boolean = false
    ): Result<SeparationResult> = withContext(Dispatchers.IO) {
        val spec = _selectedModel.value
        val modelFile = modelFileFor(spec)

        if (spec.availability == ModelAvailability.UNAVAILABLE) {
            val err = IllegalStateException(
                spec.unavailableReason ?: "${spec.displayName} is unavailable for Android ONNX separation."
            )
            _errorMessage.value = err.message
            _modelStatus.value = ModelStatus.ERROR
            _statusMessage.value = "Model unavailable (no fake output generated)"
            return@withContext Result.failure(err)
        }

        if (audioUriString.isBlank()) {
            val err = IllegalArgumentException("No audio track selected for separation")
            _errorMessage.value = err.message
            _modelStatus.value = ModelStatus.ERROR
            return@withContext Result.failure(err)
        }

        if (!modelFile.exists() || modelFile.length() <= 16L) {
            val err = IllegalStateException(
                "ONNX model '${spec.fileName}' is not installed. Download with consent or import a local .onnx file first."
            )
            _errorMessage.value = err.message
            _modelStatus.value = ModelStatus.NOT_DOWNLOADED
            return@withContext Result.failure(err)
        }

        val initialSnap = MemoryProfiler.captureSnapshot(context)
        val config = MemoryProfiler.recommendConfig(initialSnap)

        if (spec.compatibility == HardwareCompatibility.UNSUPPORTED_4GB && !config.allowHeavyDemucs && !allowHeavyModelOverride) {
            val err = IllegalStateException(
                "${spec.displayName} requires ~${spec.estimatedPeakRamMb} MB RAM and cannot run reliably on a ${initialSnap.deviceTotalMb} MB RAM device. Select a 4GB-compatible model instead."
            )
            _errorMessage.value = err.message
            _modelStatus.value = ModelStatus.ERROR
            return@withContext Result.failure(err)
        }

        cancelRequested.set(false)
        _errorMessage.value = null
        _modelStatus.value = ModelStatus.PROCESSING
        _progress.value = 0f
        _statusMessage.value = "Initializing ONNX Runtime CPU session (${config.chunkSeconds}s chunks)..."

        val startTimeMs = System.currentTimeMillis()
        val baselineUsedMb = initialSnap.combinedProcessUsedMb
        var afterModelLoadUsedMb = baselineUsedMb
        var peakUsedMb = baselineUsedMb
        var chunksProcessed = 0
        var oomRecovered = false

        val tempDir = File(context.cacheDir, "stems_tmp_${System.currentTimeMillis()}").apply { mkdirs() }
        val finalDir = File(context.filesDir, "stems/project_${projectId}_${System.currentTimeMillis()}").apply { mkdirs() }

        val stemNames = spec.outputStemOrder
        val tempWriters = LinkedHashMap<String, Pair<File, WavStreamWriter>>()
        val stitchers = LinkedHashMap<String, StereoOverlapAddStitcher>()

        var sessionOpts: OrtSession.SessionOptions? = null
        var session: OrtSession? = null

        try {
            val env = OrtEnvironment.getEnvironment()
            sessionOpts = createLowMemorySessionOptions(config)
            session = env.createSession(modelFile.absolutePath, sessionOpts)

            val afterLoadSnap = MemoryProfiler.captureSnapshot(context)
            afterModelLoadUsedMb = afterLoadSnap.combinedProcessUsedMb
            peakUsedMb = maxOf(peakUsedMb, afterModelLoadUsedMb)
            _memorySnapshot.value = afterLoadSnap

            val inputEntry = session.inputInfo.entries.firstOrNull()
                ?: throw OrtException("Model has no inputs")
            val inputName = inputEntry.key
            val inputShape = (inputEntry.value.info as? TensorInfo)?.shape
                ?: throw OrtException("Unsupported input tensor type")

            val sampleRate = StreamingAudioDecoder.TARGET_SAMPLE_RATE
            val chunkFrames = if (inputShape.size == 3 && inputShape[2] > 0L) {
                inputShape[2].toInt().coerceAtMost((config.chunkSeconds * sampleRate).toInt().coerceAtLeast(4096))
            } else {
                (config.chunkSeconds * sampleRate).toInt().coerceAtLeast(4096)
            }
            val overlapFrames = (config.overlapSeconds * sampleRate).toInt().coerceIn(512, chunkFrames / 3)
            val stepFrames = chunkFrames - overlapFrames

            for (stem in stemNames) {
                val tmpFile = File(tempDir, "${stem}.tmp.wav")
                tempWriters[stem] = Pair(tmpFile, WavStreamWriter(tmpFile, sampleRate, 2))
                stitchers[stem] = StereoOverlapAddStitcher(overlapFrames)
            }

            val stftProcessor = StftProcessor(fftSize = 4096, hopSize = 1024)
            // Single sliding window buffer in RAM: only ~1 chunk!
            val slidingWindow = FloatArray(chunkFrames * 2)
            var bufferedFrames = 0

            val jobContext = currentCoroutineContext()
            val audioUri = Uri.parse(audioUriString)
            _statusMessage.value = "Streaming audio & running chunked CPU inference..."

            StreamingAudioDecoder.streamStereoChunks(
                context = context,
                uri = audioUri,
                chunkFrames = stepFrames,
                isCancelled = { cancelRequested.get() },
                onChunk = { incomingStereo, incomingFrames, decodeProgress ->
                    jobContext.ensureActive()
                    if (cancelRequested.get()) {
                        throw CancellationException("Separation cancelled by user")
                    }

                    var incomingOffset = 0
                    while (incomingOffset < incomingFrames) {
                        jobContext.ensureActive()
                        if (cancelRequested.get()) {
                            throw CancellationException("Separation cancelled by user")
                        }

                        val copyFrames = min(incomingFrames - incomingOffset, chunkFrames - bufferedFrames)
                        System.arraycopy(incomingStereo, incomingOffset * 2, slidingWindow, bufferedFrames * 2, copyFrames * 2)
                        bufferedFrames += copyFrames
                        incomingOffset += copyFrames

                        if (bufferedFrames >= chunkFrames) {
                            runSingleChunkInference(
                                env = env,
                                session = session!!,
                                inputName = inputName,
                                inputShape = inputShape,
                                chunkStereo = slidingWindow,
                                frameCount = chunkFrames,
                                stemNames = stemNames,
                                stftProcessor = stftProcessor,
                                isLastChunk = false,
                                stitchers = stitchers,
                                writers = tempWriters
                            )
                            chunksProcessed++

                            // Shift overlap tail to beginning of sliding window
                            val remainFrames = bufferedFrames - stepFrames
                            if (remainFrames > 0) {
                                System.arraycopy(slidingWindow, stepFrames * 2, slidingWindow, 0, remainFrames * 2)
                            }
                            bufferedFrames = remainFrames

                            val snap = MemoryProfiler.captureSnapshot(context)
                            peakUsedMb = maxOf(peakUsedMb, snap.combinedProcessUsedMb)
                            _memorySnapshot.value = snap
                            _progress.value = decodeProgress.coerceIn(0.05f, 0.95f)
                            _statusMessage.value = "Chunk $chunksProcessed • ${(decodeProgress * 100).toInt()}% • RAM: ${snap.combinedProcessUsedMb} MB"
                        }
                    }
                }
            )

            if (cancelRequested.get()) {
                throw CancellationException("Separation cancelled by user")
            }

            // Process remaining tail frames if any
            if (bufferedFrames > 0) {
                runSingleChunkInference(
                    env = env,
                    session = session,
                    inputName = inputName,
                    inputShape = inputShape,
                    chunkStereo = slidingWindow,
                    frameCount = bufferedFrames,
                    stemNames = stemNames,
                    stftProcessor = stftProcessor,
                    isLastChunk = true,
                    stitchers = stitchers,
                    writers = tempWriters
                )
                chunksProcessed++
            }

            // Flush any remaining overlap tails and close WAV headers
            val finalFiles = LinkedHashMap<String, File>()
            for (stem in stemNames) {
                val (tmpFile, writer) = tempWriters.getValue(stem)
                stitchers.getValue(stem).flushRemainingTail()?.let { tail ->
                    writer.writeFloatSamples(tail)
                }
                writer.close()

                val destFile = File(finalDir, "${stem}.wav")
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                finalFiles[stem] = destFile
            }
            tempWriters.clear()

            val totalDurationMs = System.currentTimeMillis() - startTimeMs
            val finalSnap = MemoryProfiler.captureSnapshot(context)
            peakUsedMb = maxOf(peakUsedMb, finalSnap.combinedProcessUsedMb)
            _memorySnapshot.value = finalSnap

            val benchmark = InferenceBenchmark(
                modelId = spec.id,
                deviceTotalRamMb = initialSnap.deviceTotalMb,
                baselineUsedMb = baselineUsedMb,
                afterModelLoadUsedMb = afterModelLoadUsedMb,
                peakUsedMb = peakUsedMb,
                totalDurationMs = totalDurationMs,
                chunksProcessed = chunksProcessed,
                chunkSeconds = config.chunkSeconds,
                oomRecovered = false
            )
            _lastBenchmark.value = benchmark
            _progress.value = 1f
            _modelStatus.value = ModelStatus.COMPLETED
            _statusMessage.value = "Completed in ${totalDurationMs / 1000.0}s • Peak RAM: $peakUsedMb MB"

            Result.success(SeparationResult(finalFiles, benchmark, spec))
        } catch (ce: CancellationException) {
            _modelStatus.value = ModelStatus.CANCELLED
            _statusMessage.value = "Separation cancelled; temporary files cleaned up."
            cleanupTempFiles(tempWriters, tempDir, finalDir)
            Result.failure(ce)
        } catch (oom: OutOfMemoryError) {
            oomRecovered = true
            cleanupTempFiles(tempWriters, tempDir, finalDir)
            try { session?.close() } catch (_: Throwable) {}
            session = null
            System.gc()

            val totalDurationMs = System.currentTimeMillis() - startTimeMs
            _lastBenchmark.value = InferenceBenchmark(
                modelId = spec.id,
                deviceTotalRamMb = initialSnap.deviceTotalMb,
                baselineUsedMb = baselineUsedMb,
                afterModelLoadUsedMb = afterModelLoadUsedMb,
                peakUsedMb = peakUsedMb,
                totalDurationMs = totalDurationMs,
                chunksProcessed = chunksProcessed,
                chunkSeconds = config.chunkSeconds,
                oomRecovered = true
            )
            val msg = "OutOfMemoryError on ${initialSnap.deviceTotalMb} MB device while running ${spec.displayName}. Close background apps or switch to a 2-stem quantized model."
            _errorMessage.value = msg
            _modelStatus.value = ModelStatus.ERROR
            _statusMessage.value = "OOM recovered safely"
            Result.failure(IllegalStateException(msg, oom))
        } catch (e: Throwable) {
            cleanupTempFiles(tempWriters, tempDir, finalDir)
            val msg = e.localizedMessage ?: "ONNX inference failed (${e::class.java.simpleName})"
            _errorMessage.value = msg
            _modelStatus.value = ModelStatus.ERROR
            _statusMessage.value = "Inference failed (no fake output generated)"
            Result.failure(e)
        } finally {
            tempWriters.values.forEach { (_, w) -> try { w.close() } catch (_: Throwable) {} }
            try { session?.close() } catch (_: Throwable) {}
            try { sessionOpts?.close() } catch (_: Throwable) {}
            tempDir.deleteRecursively()
            refreshMemorySnapshot()
        }
    }

    private fun runSingleChunkInference(
        env: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        inputShape: LongArray,
        chunkStereo: FloatArray,
        frameCount: Int,
        stemNames: List<String>,
        stftProcessor: StftProcessor,
        isLastChunk: Boolean,
        stitchers: Map<String, StereoOverlapAddStitcher>,
        writers: Map<String, Pair<File, WavStreamWriter>>
    ) {
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            left[i] = chunkStereo[i * 2]
            right[i] = chunkStereo[i * 2 + 1]
        }

        var inputTensor: OnnxTensor? = null
        var ortResult: OrtSession.Result? = null
        try {
            if (inputShape.size == 3) {
                // Waveform-domain ONNX input: [batch=1, channels=2, samples]
                val targetSamples = if (inputShape[2] > 0L) inputShape[2].toInt() else frameCount
                val buf = FloatBuffer.allocate(2 * targetSamples)
                for (i in 0 until targetSamples) {
                    buf.put(if (i < frameCount) left[i] else 0f)
                }
                for (i in 0 until targetSamples) {
                    buf.put(if (i < frameCount) right[i] else 0f)
                }
                buf.rewind()
                inputTensor = OnnxTensor.createTensor(env, buf, longArrayOf(1L, 2L, targetSamples.toLong()))
                ortResult = session.run(mapOf(inputName to inputTensor))
                mapWaveformResultToStems(ortResult, frameCount, stemNames, isLastChunk, stitchers, writers)
            } else if (inputShape.size == 4) {
                // Spectrogram-domain ONNX input (e.g. MDX-Net [1, 4, F, T] or Spleeter [1, 2, F, T])
                val (lReal, lImag, numFrames) = stftProcessor.stft(left)
                val (rReal, rImag, _) = stftProcessor.stft(right)
                val freqBins = if (inputShape[2] > 0L) inputShape[2].toInt() else stftProcessor.numBins
                val timeFrames = if (inputShape[3] > 0L) inputShape[3].toInt() else numFrames
                val channelsDim = if (inputShape[1] > 0L) inputShape[1].toInt() else 4

                val tensorFloatCount = channelsDim * freqBins * timeFrames
                val buf = FloatBuffer.allocate(tensorFloatCount)
                val modelBins = min(freqBins, stftProcessor.numBins)
                val modelFrames = min(timeFrames, numFrames)

                if (channelsDim == 4) {
                    // [L_real, L_imag, R_real, R_imag]
                    fillSpectrogramChannel(buf, lReal, stftProcessor.numBins, modelBins, modelFrames, freqBins, timeFrames)
                    fillSpectrogramChannel(buf, lImag, stftProcessor.numBins, modelBins, modelFrames, freqBins, timeFrames)
                    fillSpectrogramChannel(buf, rReal, stftProcessor.numBins, modelBins, modelFrames, freqBins, timeFrames)
                    fillSpectrogramChannel(buf, rImag, stftProcessor.numBins, modelBins, modelFrames, freqBins, timeFrames)
                } else {
                    // Magnitude spectrogram [L_mag, R_mag]
                    val lMag = FloatArray(lReal.size) { idx ->
                        kotlin.math.sqrt(lReal[idx] * lReal[idx] + lImag[idx] * lImag[idx])
                    }
                    val rMag = FloatArray(rReal.size) { idx ->
                        kotlin.math.sqrt(rReal[idx] * rReal[idx] + rImag[idx] * rImag[idx])
                    }
                    fillSpectrogramChannel(buf, lMag, stftProcessor.numBins, modelBins, modelFrames, freqBins, timeFrames)
                    if (channelsDim >= 2) {
                        fillSpectrogramChannel(buf, rMag, stftProcessor.numBins, modelBins, modelFrames, freqBins, timeFrames)
                    }
                }
                buf.rewind()
                inputTensor = OnnxTensor.createTensor(
                    env,
                    buf,
                    longArrayOf(1L, channelsDim.toLong(), freqBins.toLong(), timeFrames.toLong())
                )
                ortResult = session.run(mapOf(inputName to inputTensor))
                mapSpectrogramResultToStems(
                    ortResult = ortResult,
                    lReal = lReal,
                    lImag = lImag,
                    rReal = rReal,
                    rImag = rImag,
                    numFrames = numFrames,
                    frameCount = frameCount,
                    stftProcessor = stftProcessor,
                    stemNames = stemNames,
                    isLastChunk = isLastChunk,
                    stitchers = stitchers,
                    writers = writers
                )
            } else {
                throw OrtException("Unsupported ONNX input tensor rank ${inputShape.size} (expected 3 or 4)")
            }
        } finally {
            try { inputTensor?.close() } catch (_: Throwable) {}
            try { ortResult?.close() } catch (_: Throwable) {}
        }
    }

    private fun fillSpectrogramChannel(
        dst: FloatBuffer,
        src: FloatArray,
        srcBins: Int,
        copyBins: Int,
        copyFrames: Int,
        dstBins: Int,
        dstFrames: Int
    ) {
        for (f in 0 until dstBins) {
            for (t in 0 until dstFrames) {
                val valFloat = if (f < copyBins && t < copyFrames) src[t * srcBins + f] else 0f
                dst.put(valFloat)
            }
        }
    }

    private fun mapWaveformResultToStems(
        ortResult: OrtSession.Result,
        frameCount: Int,
        stemNames: List<String>,
        isLastChunk: Boolean,
        stitchers: Map<String, StereoOverlapAddStitcher>,
        writers: Map<String, Pair<File, WavStreamWriter>>
    ) {
        val outputTensor = ortResult[0] as? OnnxTensor
            ?: throw OrtException("Expected OnnxTensor output from model")
        val shape = outputTensor.info.shape
        val floatBuf = outputTensor.floatBuffer

        // Support exact [1, stems, 2, N] multi-stem waveform tensors matching spec.outputStemOrder
        if (shape.size == 4 && shape[1] == stemNames.size.toLong() && shape[2] == 2L) {
            val outSamples = shape[3].toInt()
            val strideStem = 2 * outSamples
            for ((sIdx, stem) in stemNames.withIndex()) {
                val stereoOut = FloatArray(frameCount * 2)
                val base = sIdx * strideStem
                val copyN = min(frameCount, outSamples)
                for (i in 0 until copyN) {
                    stereoOut[i * 2] = floatBuf.get(base + i)
                    stereoOut[i * 2 + 1] = floatBuf.get(base + outSamples + i)
                }
                val emit = stitchers.getValue(stem).processChunk(stereoOut, frameCount, isLastChunk)
                writers.getValue(stem).second.writeFloatSamples(emit)
            }
        } else {
            throw OrtException(
                "Waveform output tensor shape ${shape.contentToString()} does not match expected [1, ${stemNames.size}, 2, N] for ${stemNames.joinToString()}"
            )
        }
    }

    private fun mapSpectrogramResultToStems(
        ortResult: OrtSession.Result,
        lReal: FloatArray,
        lImag: FloatArray,
        rReal: FloatArray,
        rImag: FloatArray,
        numFrames: Int,
        frameCount: Int,
        stftProcessor: StftProcessor,
        stemNames: List<String>,
        isLastChunk: Boolean,
        stitchers: Map<String, StereoOverlapAddStitcher>,
        writers: Map<String, Pair<File, WavStreamWriter>>
    ) {
        val outputTensor = ortResult[0] as? OnnxTensor
            ?: throw OrtException("Expected OnnxTensor output from model")
        val shape = outputTensor.info.shape
        val floatBuf = outputTensor.floatBuffer

        if (shape.size == 4 && shape[1] == 4L) {
            // 2-stem MDX-Net style output: [1, 4, F, T] -> primary stem complex STFT; secondary = mix - primary
            val outF = shape[2].toInt()
            val outT = shape[3].toInt()
            val bins = stftProcessor.numBins
            val primLReal = FloatArray(numFrames * bins)
            val primLImag = FloatArray(numFrames * bins)
            val primRReal = FloatArray(numFrames * bins)
            val primRImag = FloatArray(numFrames * bins)

            val copyF = min(outF, bins)
            val copyT = min(outT, numFrames)
            val chStride = outF * outT
            for (f in 0 until copyF) {
                for (t in 0 until copyT) {
                    val srcIdx = f * outT + t
                    val dstIdx = t * bins + f
                    primLReal[dstIdx] = floatBuf.get(srcIdx)
                    primLImag[dstIdx] = floatBuf.get(chStride + srcIdx)
                    primRReal[dstIdx] = floatBuf.get(2 * chStride + srcIdx)
                    primRImag[dstIdx] = floatBuf.get(3 * chStride + srcIdx)
                }
            }

            val primL = stftProcessor.istft(primLReal, primLImag, numFrames, frameCount)
            val primR = stftProcessor.istft(primRReal, primRImag, numFrames, frameCount)

            val primStereo = FloatArray(frameCount * 2)
            for (i in 0 until frameCount) {
                primStereo[i * 2] = primL[i]
                primStereo[i * 2 + 1] = primR[i]
            }
            val firstStem = stemNames.first()
            val emitPrim = stitchers.getValue(firstStem).processChunk(primStereo, frameCount, isLastChunk)
            writers.getValue(firstStem).second.writeFloatSamples(emitPrim)

            if (stemNames.size >= 2) {
                val resLReal = FloatArray(lReal.size) { i -> lReal[i] - primLReal[i] }
                val resLImag = FloatArray(lImag.size) { i -> lImag[i] - primLImag[i] }
                val resRReal = FloatArray(rReal.size) { i -> rReal[i] - primRReal[i] }
                val resRImag = FloatArray(rImag.size) { i -> rImag[i] - primRImag[i] }

                val secL = stftProcessor.istft(resLReal, resLImag, numFrames, frameCount)
                val secR = stftProcessor.istft(resRReal, resRImag, numFrames, frameCount)
                val secStereo = FloatArray(frameCount * 2)
                for (i in 0 until frameCount) {
                    secStereo[i * 2] = secL[i]
                    secStereo[i * 2 + 1] = secR[i]
                }
                val secondStem = stemNames[1]
                val emitSec = stitchers.getValue(secondStem).processChunk(secStereo, frameCount, isLastChunk)
                writers.getValue(secondStem).second.writeFloatSamples(emitSec)
            }
        } else if (ortResult.size() >= stemNames.size) {
            // Multi-output mask/spectrogram model (e.g. Spleeter 4-stem ONNX with 4 output tensors)
            for ((sIdx, stem) in stemNames.withIndex()) {
                val stemTensor = ortResult[sIdx] as? OnnxTensor
                    ?: throw OrtException("Output $sIdx is not an OnnxTensor")
                val sShape = stemTensor.info.shape
                val sBuf = stemTensor.floatBuffer
                val outF = sShape[sShape.size - 2].toInt()
                val outT = sShape[sShape.size - 1].toInt()
                val bins = stftProcessor.numBins
                val sLReal = lReal.copyOf()
                val sLImag = lImag.copyOf()
                val sRReal = rReal.copyOf()
                val sRImag = rImag.copyOf()

                val copyF = min(outF, bins)
                val copyT = min(outT, numFrames)
                for (f in 0 until copyF) {
                    for (t in 0 until copyT) {
                        val mask = sBuf.get(f * outT + t).coerceIn(0f, 1.5f)
                        val idx = t * bins + f
                        sLReal[idx] *= mask
                        sLImag[idx] *= mask
                        sRReal[idx] *= mask
                        sRImag[idx] *= mask
                    }
                }
                val recL = stftProcessor.istft(sLReal, sLImag, numFrames, frameCount)
                val recR = stftProcessor.istft(sRReal, sRImag, numFrames, frameCount)
                val stereo = FloatArray(frameCount * 2)
                for (i in 0 until frameCount) {
                    stereo[i * 2] = recL[i]
                    stereo[i * 2 + 1] = recR[i]
                }
                val emit = stitchers.getValue(stem).processChunk(stereo, frameCount, isLastChunk)
                writers.getValue(stem).second.writeFloatSamples(emit)
            }
        } else {
            throw OrtException("Unsupported spectrogram output tensor shape: ${shape.contentToString()}")
        }
    }

    private fun cleanupTempFiles(
        writers: MutableMap<String, Pair<File, WavStreamWriter>>,
        tempDir: File,
        finalDir: File
    ) {
        writers.values.forEach { (file, writer) ->
            try { writer.close() } catch (_: Throwable) {}
            try { file.delete() } catch (_: Throwable) {}
        }
        writers.clear()
        try { tempDir.deleteRecursively() } catch (_: Throwable) {}
        try { finalDir.deleteRecursively() } catch (_: Throwable) {}
    }
}
