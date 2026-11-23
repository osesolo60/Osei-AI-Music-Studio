package com.osei.aimusicstudio.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

data class LyricLine(
    val id: Long,
    val timestampMs: Long, // -1L indicates an untimed line awaiting manual timing
    val text: String
)

object LrcParser {
    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")

    fun parse(rawText: String): List<LyricLine> {
        if (rawText.isBlank()) return emptyList()
        val parsed = mutableListOf<LyricLine>()
        var nextId = 1L

        rawText.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) {
                // Skip non-lyric LRC metadata tags like [ar:...], [ti:...], [al:...]
                if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                    parsed.add(LyricLine(id = nextId++, timestampMs = -1L, text = trimmed))
                }
            } else {
                val lyricContent = trimmed.replace(TIMESTAMP_REGEX, "").trim()
                if (lyricContent.isEmpty()) return@forEach
                matches.forEach { m ->
                    val mins = m.groupValues[1].toLongOrNull() ?: 0L
                    val secs = m.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = m.groupValues[3]
                    val msPart = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    val totalMs = mins * 60_000L + secs * 1000L + msPart
                    parsed.add(LyricLine(id = nextId++, timestampMs = totalMs, text = lyricContent))
                }
            }
        }

        // Preserve timed order first if mixed; untimed lines keep their order
        val allTimed = parsed.all { it.timestampMs >= 0L }
        return if (allTimed) parsed.sortedBy { it.timestampMs } else parsed
    }

    fun formatToLrc(lines: List<LyricLine>): String {
        return lines.joinToString("\n") { line ->
            if (line.timestampMs < 0L) {
                line.text
            } else {
                val mins = line.timestampMs / 60_000L
                val secs = (line.timestampMs % 60_000L) / 1000L
                val centis = (line.timestampMs % 1000L) / 10L
                String.format("[%02d:%02d.%02d] %s", mins, secs, centis, line.text)
            }
        }
    }

    fun formatShortTime(timestampMs: Long): String {
        if (timestampMs < 0L) return "--:--.--"
        val mins = timestampMs / 60_000L
        val secs = (timestampMs % 60_000L) / 1000L
        val centis = (timestampMs % 1000L) / 10L
        return String.format("%02d:%02d.%02d", mins, secs, centis)
    }

    fun findActiveLyricIndex(
        lines: List<LyricLine>,
        playbackPositionMs: Long,
        globalOffsetMs: Long = 0L
    ): Int {
        if (lines.isEmpty()) return -1
        val effectiveTime = playbackPositionMs - globalOffsetMs
        var activeIdx = -1
        var bestTimestamp = Long.MIN_VALUE
        for ((idx, line) in lines.withIndex()) {
            if (line.timestampMs >= 0L && line.timestampMs <= effectiveTime && line.timestampMs >= bestTimestamp) {
                bestTimestamp = line.timestampMs
                activeIdx = idx
            }
        }
        return activeIdx
    }
}

/**
 * Streaming 16-bit PCM WAV microphone recorder for Karaoke takes on 4 GB RAM devices.
 * Streams microphone buffers directly to disk via WavStreamWriter without accumulating RAM buffers.
 */
class WavMicrophoneRecorder {
    companion object {
        const val SAMPLE_RATE = 44100
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    private val stopFlag = AtomicBoolean(false)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedDurationMs = MutableStateFlow(0L)
    val recordedDurationMs: StateFlow<Long> = _recordedDurationMs.asStateFlow()

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording(
        outputWavFile: File,
        onRecordingFinished: (Result<File>) -> Unit
    ) {
        if (_isRecording.value) return
        _errorMessage.value = null
        _recordedDurationMs.value = 0L
        _inputLevel.value = 0f
        stopFlag.set(false)

        recordJob = scope.launch {
            var recorder: AudioRecord? = null
            var framesWritten = 0L
            try {
                val minBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBytes <= 0) {
                    throw IllegalStateException("AudioRecord hardware unavailable on this device ($minBytes)")
                }
                val bufBytes = max(minBytes * 2, 4096)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone failed to initialize (AudioRecord not initialized)")
                }

                val shortBuf = ShortArray(bufBytes / 2)
                val stereoFloatBuf = FloatArray((bufBytes / 2) * 2)

                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Microphone failed to enter recording state")
                }

                _isRecording.value = true

                // Write standard 44.1 kHz stereo WAV so it mixes directly with MultitrackAudioEngine
                WavStreamWriter(outputWavFile, SAMPLE_RATE, 2).use { writer ->
                    while (!stopFlag.get()) {
                        currentCoroutineContext().ensureActive()
                        val readShorts = recorder.read(shortBuf, 0, shortBuf.size)
                        if (readShorts < 0) {
                            throw IllegalStateException("Microphone read failed ($readShorts)")
                        }
                        if (readShorts > 0) {
                            var peak = 0f
                            for (i in 0 until readShorts) {
                                val f = (shortBuf[i] / 32768.0f).coerceIn(-1.0f, 1.0f)
                                val a = abs(f)
                                if (a > peak) peak = a
                                stereoFloatBuf[i * 2] = f
                                stereoFloatBuf[i * 2 + 1] = f
                            }
                            writer.writeFloatSamples(stereoFloatBuf, readShorts * 2)
                            framesWritten += readShorts
                            _inputLevel.value = peak
                            _recordedDurationMs.value = (framesWritten * 1000L) / SAMPLE_RATE
                        }
                    }
                }

                _isRecording.value = false
                _inputLevel.value = 0f
                if (framesWritten <= 0L) {
                    outputWavFile.delete()
                    onRecordingFinished(Result.failure(IllegalStateException("No audio frames captured from microphone")))
                } else {
                    onRecordingFinished(Result.success(outputWavFile))
                }
            } catch (ce: CancellationException) {
                _isRecording.value = false
                _inputLevel.value = 0f
                if (framesWritten <= 0L) outputWavFile.delete()
                onRecordingFinished(Result.failure(ce))
            } catch (se: SecurityException) {
                _isRecording.value = false
                _inputLevel.value = 0f
                outputWavFile.delete()
                val msg = "Microphone permission (RECORD_AUDIO) denied"
                _errorMessage.value = msg
                onRecordingFinished(Result.failure(SecurityException(msg, se)))
            } catch (oom: OutOfMemoryError) {
                _isRecording.value = false
                _inputLevel.value = 0f
                outputWavFile.delete()
                System.gc()
                val msg = "Low memory stopped microphone recording"
                _errorMessage.value = msg
                onRecordingFinished(Result.failure(IllegalStateException(msg, oom)))
            } catch (t: Throwable) {
                _isRecording.value = false
                _inputLevel.value = 0f
                outputWavFile.delete()
                val msg = t.localizedMessage ?: "Microphone recording failed"
                _errorMessage.value = msg
                onRecordingFinished(Result.failure(t))
            } finally {
                try {
                    if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Throwable) {
                }
                try {
                    recorder?.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun stopRecording() {
        stopFlag.set(true)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun release() {
        stopFlag.set(true)
        recordJob?.cancel()
        scope.cancel()
    }
}
