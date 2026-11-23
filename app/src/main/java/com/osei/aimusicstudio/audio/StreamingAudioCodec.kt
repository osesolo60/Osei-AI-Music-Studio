package com.osei.aimusicstudio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

data class AudioMetadataInfo(
    val displayName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val mimeType: String
)

object SupportedAudioFormats {
    val SUPPORTED_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg")
    val SUPPORTED_MIME_PREFIXES = listOf(
        "audio/mpeg",
        "audio/mp3",
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/vnd.wave",
        "audio/mp4",
        "audio/m4a",
        "audio/x-m4a",
        "audio/aac",
        "audio/aac-adts",
        "audio/flac",
        "audio/x-flac",
        "audio/ogg",
        "application/ogg",
        "audio/vorbis",
        "audio/opus"
    )

    fun isSupportedMimeOrExtension(mimeType: String?, fileName: String?): Boolean {
        val normMime = mimeType?.lowercase()?.trim() ?: ""
        if (normMime.isNotEmpty() && SUPPORTED_MIME_PREFIXES.any { normMime.startsWith(it) }) {
            return true
        }
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.trim() ?: ""
        return ext in SUPPORTED_EXTENSIONS
    }
}

object AudioValidator {
    fun inspectAndValidate(context: Context, uri: Uri): Result<AudioMetadataInfo> {
        return try {
            var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "audio_track"
            var fileSizeBytes = 0L

            if (uri.scheme == "file") {
                val file = File(uri.path ?: return Result.failure(IllegalArgumentException("Invalid file path")))
                if (!file.exists() || !file.canRead()) {
                    return Result.failure(IllegalStateException("Audio file does not exist or cannot be read"))
                }
                displayName = file.name
                fileSizeBytes = file.length()
            } else {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1) displayName = cursor.getString(nameIdx) ?: displayName
                        if (sizeIdx != -1) fileSizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }

            if (fileSizeBytes <= 0L && uri.scheme == "file") {
                return Result.failure(IllegalArgumentException("Audio file is empty (0 bytes)"))
            }

            val resolverMime = if (uri.scheme == "content") context.contentResolver.getType(uri) else null

            // Fast check for direct WAV file (especially useful on local files & JVM unit tests)
            if (uri.scheme == "file") {
                val file = File(uri.path!!)
                val wavHeader = WavStreamReader.tryReadHeader(file)
                if (wavHeader != null) {
                    val durationMs = if (wavHeader.byteRate > 0) {
                        (wavHeader.dataSizeBytes * 1000L) / wavHeader.byteRate
                    } else 0L
                    if (durationMs <= 0L) {
                        return Result.failure(IllegalArgumentException("WAV file has zero audio duration"))
                    }
                    return Result.success(
                        AudioMetadataInfo(
                            displayName = displayName,
                            title = displayName.substringBeforeLast('.').ifBlank { displayName },
                            artist = "Local Audio",
                            durationMs = durationMs,
                            fileSizeBytes = fileSizeBytes,
                            sampleRate = wavHeader.sampleRate,
                            channelCount = wavHeader.channels,
                            mimeType = "audio/wav"
                        )
                    )
                }
            }

            // Use MediaExtractor + MediaMetadataRetriever for container/codec verification
            val extractor = MediaExtractor()
            var detectedMime = resolverMime ?: ""
            var sampleRate = 0
            var channelCount = 0
            var durationUs = 0L

            try {
                if (uri.scheme == "file") {
                    extractor.setDataSource(uri.path!!)
                } else {
                    extractor.setDataSource(context, uri, null)
                }
                var audioTrackFound = false
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackFound = true
                        detectedMime = mime
                        sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else 44100
                        channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else 2
                        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            format.getLong(MediaFormat.KEY_DURATION)
                        } else 0L
                        break
                    }
                }
                if (!audioTrackFound) {
                    return Result.failure(IllegalArgumentException("No decodable audio track found in file"))
                }
            } finally {
                extractor.release()
            }

            if (!SupportedAudioFormats.isSupportedMimeOrExtension(detectedMime, displayName)) {
                return Result.failure(
                    UnsupportedOperationException("Unsupported audio format ($detectedMime). Supported: MP3, WAV, M4A, AAC, FLAC, OGG.")
                )
            }

            var title = displayName.substringBeforeLast('.').ifBlank { displayName }
            var artist = "Unknown Artist"
            var durationMs = durationUs / 1000L

            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") {
                    retriever.setDataSource(uri.path!!)
                } else {
                    retriever.setDataSource(context, uri)
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let {
                    title = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let {
                    artist = it
                }
                if (durationMs <= 0L) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                        durationMs = it
                    }
                }
            } catch (_: Exception) {
                // Metadata tags are optional if MediaExtractor already validated the track
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }

            if (durationMs <= 0L) {
                return Result.failure(IllegalArgumentException("Corrupted audio file: duration is 0 ms"))
            }

            Result.success(
                AudioMetadataInfo(
                    displayName = displayName,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    fileSizeBytes = fileSizeBytes,
                    sampleRate = sampleRate.coerceAtLeast(8000),
                    channelCount = channelCount.coerceIn(1, 8),
                    mimeType = detectedMime.ifBlank { "audio/*" }
                )
            )
        } catch (se: SecurityException) {
            Result.failure(SecurityException("Permission denied accessing audio file", se))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to decode audio file: ${e.localizedMessage ?: "Corrupted or unsupported file"}", e))
        }
    }
}

data class WavHeaderInfo(
    val audioFormat: Int, // 1 = PCM int16, 3 = IEEE float32
    val channels: Int,
    val sampleRate: Int,
    val byteRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSizeBytes: Long
)

object WavStreamReader {
    fun tryReadHeader(file: File): WavHeaderInfo? {
        if (!file.exists() || file.length() < 44L) return null
        return FileInputStream(file).use { fis ->
            tryReadHeader(fis)
        }
    }

    fun tryReadHeader(inputStream: InputStream): WavHeaderInfo? {
        val dis = DataInputStream(BufferedInputStream(inputStream))
        val riff = ByteArray(4)
        if (dis.read(riff) != 4 || String(riff) != "RIFF") return null
        dis.skipBytes(4) // RIFF chunk size
        val wave = ByteArray(4)
        if (dis.read(wave) != 4 || String(wave) != "WAVE") return null

        var offset = 12L
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var byteRate = 0
        var bitsPerSample = 0
        var foundFmt = false

        val chunkHeader = ByteArray(8)
        while (dis.read(chunkHeader) == 8) {
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            offset += 8L

            if (chunkId == "fmt ") {
                if (chunkSize < 16) return null
                val fmtBytes = ByteArray(chunkSize.toInt())
                dis.readFully(fmtBytes)
                val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                audioFormat = bb.short.toInt() and 0xFFFF
                channels = bb.short.toInt() and 0xFFFF
                sampleRate = bb.int
                byteRate = bb.int
                bb.short // blockAlign
                bitsPerSample = bb.short.toInt() and 0xFFFF
                foundFmt = true
                offset += chunkSize
            } else if (chunkId == "data") {
                if (!foundFmt) return null
                if ((audioFormat != 1 && audioFormat != 3) || channels <= 0 || sampleRate <= 0) return null
                if (audioFormat == 1 && bitsPerSample != 16) return null
                if (audioFormat == 3 && bitsPerSample != 32) return null
                return WavHeaderInfo(
                    audioFormat = audioFormat,
                    channels = channels,
                    sampleRate = sampleRate,
                    byteRate = byteRate,
                    bitsPerSample = bitsPerSample,
                    dataOffset = offset,
                    dataSizeBytes = chunkSize
                )
            } else {
                val skipTotal = chunkSize + (chunkSize % 2L)
                var remaining = skipTotal
                while (remaining > 0L) {
                    val skipped = dis.skip(remaining)
                    if (skipped <= 0L) return null
                    remaining -= skipped
                }
                offset += skipTotal
            }
        }
        return null
    }
}

/**
 * Streams float32 stereo/mono samples directly to a 16-bit PCM WAV file on disk,
 * avoiding holding full audio stems in RAM.
 */
class WavStreamWriter(
    private val targetFile: File,
    private val sampleRate: Int = 44100,
    private val channels: Int = 2
) : Closeable {
    private val fos = FileOutputStream(targetFile)
    private val bos = BufferedOutputStream(fos, 32 * 1024)
    private var totalDataBytes: Long = 0L
    private var closed = false
    private var byteBuffer = ByteArray(8192)

    init {
        targetFile.parentFile?.mkdirs()
        // Placeholder 44-byte RIFF header, patched in close()
        bos.write(ByteArray(44))
    }

    /**
     * Writes interleaved float32 samples in [-1.0, 1.0].
     */
    fun writeFloatSamples(samples: FloatArray, sampleCount: Int = samples.size) {
        if (closed || sampleCount <= 0) return
        val neededBytes = sampleCount * 2
        if (byteBuffer.size < neededBytes) {
            byteBuffer = ByteArray(neededBytes)
        }
        var bIdx = 0
        for (i in 0 until sampleCount) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val pcm16 = (clamped * 32767.0f).toInt().toShort()
            byteBuffer[bIdx++] = (pcm16.toInt() and 0xFF).toByte()
            byteBuffer[bIdx++] = ((pcm16.toInt() ushr 8) and 0xFF).toByte()
        }
        bos.write(byteBuffer, 0, neededBytes)
        totalDataBytes += neededBytes
    }

    override fun close() {
        if (closed) return
        closed = true
        bos.flush()
        bos.close()

        // Patch RIFF header sizes atomically via RandomAccessFile
        RandomAccessFile(targetFile, "rw").use { raf ->
            val byteRate = sampleRate * channels * 2
            val blockAlign = channels * 2
            val riffSize = (36L + totalDataBytes).toInt()
            val dataSize = totalDataBytes.toInt()

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(riffSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM fmt chunk size
            header.putShort(1.toShort()) // AudioFormat = 1 (PCM)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(16.toShort()) // BitsPerSample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)

            raf.seek(0L)
            raf.write(header.array())
        }
    }
}

/**
 * Memory-bounded streaming decoder that reads any supported audio URI or File in fixed-size
 * stereo 44100 Hz float32 chunks without loading the entire file into RAM.
 */
object StreamingAudioDecoder {
    const val TARGET_SAMPLE_RATE = 44100
    const val TARGET_CHANNELS = 2

    fun streamStereoChunks(
        context: Context,
        uri: Uri,
        chunkFrames: Int,
        isCancelled: () -> Boolean = { false },
        onMetadata: (sampleRate: Int, channels: Int, estimatedTotalFrames: Long) -> Unit = { _, _, _ -> },
        onChunk: (stereoInterleaved: FloatArray, frameCount: Int, progress: Float) -> Unit
    ) {
        // Direct WAV fast path (also works in pure JVM / Robolectric unit tests!)
        if (uri.scheme == "file") {
            val file = File(uri.path ?: throw IllegalArgumentException("Invalid file URI"))
            val wavHeader = WavStreamReader.tryReadHeader(file)
            if (wavHeader != null) {
                streamWavFile(file, wavHeader, chunkFrames, isCancelled, onMetadata, onChunk)
                return
            }
        } else if (uri.scheme == "content") {
            // Check if content URI points to a WAV stream first
            val header = context.contentResolver.openInputStream(uri)?.use { WavStreamReader.tryReadHeader(it) }
            if (header != null) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    streamWavStream(stream, header, chunkFrames, isCancelled, onMetadata, onChunk)
                }
                return
            }
        }

        // General Android MediaExtractor + MediaCodec streaming path for MP3/M4A/AAC/FLAC/OGG
        streamViaMediaCodec(context, uri, chunkFrames, isCancelled, onMetadata, onChunk)
    }

    private fun streamWavFile(
        file: File,
        header: WavHeaderInfo,
        chunkFrames: Int,
        isCancelled: () -> Boolean,
        onMetadata: (Int, Int, Long) -> Unit,
        onChunk: (FloatArray, Int, Float) -> Unit
    ) {
        FileInputStream(file).use { fis ->
            streamWavStream(fis, header, chunkFrames, isCancelled, onMetadata, onChunk)
        }
    }

    private fun streamWavStream(
        inputStream: InputStream,
        header: WavHeaderInfo,
        chunkFrames: Int,
        isCancelled: () -> Boolean,
        onMetadata: (Int, Int, Long) -> Unit,
        onChunk: (FloatArray, Int, Float) -> Unit
    ) {
        val bis = BufferedInputStream(inputStream, 64 * 1024)
        var skipRemaining = header.dataOffset
        while (skipRemaining > 0L) {
            val skipped = bis.skip(skipRemaining)
            if (skipped <= 0L) break
            skipRemaining -= skipped
        }

        val bytesPerSample = header.bitsPerSample / 8
        val bytesPerSrcFrame = header.channels * bytesPerSample
        val totalSrcFrames = header.dataSizeBytes / bytesPerSrcFrame
        val estimatedTargetFrames = ((totalSrcFrames.toDouble() * TARGET_SAMPLE_RATE) / header.sampleRate).toLong()
        onMetadata(TARGET_SAMPLE_RATE, TARGET_CHANNELS, estimatedTargetFrames)

        val srcFramesPerRead = 4096
        val readBuf = ByteArray(srcFramesPerRead * bytesPerSrcFrame)
        val outChunk = FloatArray(chunkFrames * TARGET_CHANNELS)
        var outFrameIdx = 0
        var totalBytesRead = 0L

        // Resampler state
        val step = header.sampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        var srcBufferL = FloatArray(0)
        var srcBufferR = FloatArray(0)

        while (totalBytesRead < header.dataSizeBytes && !isCancelled()) {
            val toRead = min(readBuf.size.toLong(), header.dataSizeBytes - totalBytesRead).toInt()
            var readCount = 0
            while (readCount < toRead) {
                val n = bis.read(readBuf, readCount, toRead - readCount)
                if (n <= 0) break
                readCount += n
            }
            if (readCount <= 0) break
            totalBytesRead += readCount

            val framesRead = readCount / bytesPerSrcFrame
            if (srcBufferL.size < framesRead) {
                srcBufferL = FloatArray(framesRead)
                srcBufferR = FloatArray(framesRead)
            }

            val bb = ByteBuffer.wrap(readBuf, 0, framesRead * bytesPerSrcFrame).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until framesRead) {
                val l: Float
                val r: Float
                if (header.audioFormat == 1) {
                    l = bb.short / 32768.0f
                    r = if (header.channels >= 2) {
                        val right = bb.short / 32768.0f
                        for (c in 2 until header.channels) bb.short
                        right
                    } else l
                } else {
                    l = bb.float
                    r = if (header.channels >= 2) {
                        val right = bb.float
                        for (c in 2 until header.channels) bb.float
                        right
                    } else l
                }
                srcBufferL[f] = l
                srcBufferR[f] = r
            }

            if (header.sampleRate == TARGET_SAMPLE_RATE) {
                for (f in 0 until framesRead) {
                    outChunk[outFrameIdx * 2] = srcBufferL[f]
                    outChunk[outFrameIdx * 2 + 1] = srcBufferR[f]
                    outFrameIdx++
                    if (outFrameIdx == chunkFrames) {
                        val progress = (totalBytesRead.toFloat() / header.dataSizeBytes.toFloat()).coerceIn(0f, 1f)
                        onChunk(outChunk, chunkFrames, progress)
                        outFrameIdx = 0
                        if (isCancelled()) return
                    }
                }
            } else {
                var pos = 0.0
                while (pos < framesRead - 1) {
                    val idx0 = pos.toInt()
                    val frac = (pos - idx0).toFloat()
                    val l = srcBufferL[idx0] * (1f - frac) + srcBufferL[idx0 + 1] * frac
                    val r = srcBufferR[idx0] * (1f - frac) + srcBufferR[idx0 + 1] * frac
                    outChunk[outFrameIdx * 2] = l
                    outChunk[outFrameIdx * 2 + 1] = r
                    outFrameIdx++
                    if (outFrameIdx == chunkFrames) {
                        val progress = (totalBytesRead.toFloat() / header.dataSizeBytes.toFloat()).coerceIn(0f, 1f)
                        onChunk(outChunk, chunkFrames, progress)
                        outFrameIdx = 0
                        if (isCancelled()) return
                    }
                    pos += step
                }
            }
        }

        if (outFrameIdx > 0 && !isCancelled()) {
            onChunk(outChunk, outFrameIdx, 1.0f)
        }
    }

    private fun streamViaMediaCodec(
        context: Context,
        uri: Uri,
        chunkFrames: Int,
        isCancelled: () -> Boolean,
        onMetadata: (Int, Int, Long) -> Unit,
        onChunk: (FloatArray, Int, Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex == -1 || trackFormat == null) {
                throw IllegalArgumentException("No audio track found in media file")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            var srcSampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else TARGET_SAMPLE_RATE
            var srcChannels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else TARGET_CHANNELS

            val estTotalFrames = (durationUs * TARGET_SAMPLE_RATE) / 1_000_000L
            onMetadata(TARGET_SAMPLE_RATE, TARGET_CHANNELS, estTotalFrames)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            val outChunk = FloatArray(chunkFrames * TARGET_CHANNELS)
            var outFrameIdx = 0

            while (!outputDone && !isCancelled()) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val totalShorts = shortBuf.remaining()
                            val srcFrames = totalShorts / srcChannels.coerceAtLeast(1)

                            if (srcFrames > 0) {
                                val step = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
                                var pos = 0.0
                                while (pos < srcFrames) {
                                    val idx = pos.toInt().coerceAtMost(srcFrames - 1)
                                    val base = idx * srcChannels
                                    val l = shortBuf.get(base) / 32768.0f
                                    val r = if (srcChannels >= 2) shortBuf.get(base + 1) / 32768.0f else l

                                    outChunk[outFrameIdx * 2] = l
                                    outChunk[outFrameIdx * 2 + 1] = r
                                    outFrameIdx++

                                    if (outFrameIdx == chunkFrames) {
                                        val progress = if (durationUs > 0L) {
                                            (bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                        } else 0.5f
                                        onChunk(outChunk, chunkFrames, progress)
                                        outFrameIdx = 0
                                        if (isCancelled()) break
                                    }
                                    pos += step
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (outFrameIdx > 0 && !isCancelled()) {
                onChunk(outChunk, outFrameIdx, 1.0f)
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
