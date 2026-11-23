package com.osei.aimusicstudio.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.osei.aimusicstudio.audio.AudioShareHelper
import com.osei.aimusicstudio.audio.AudioValidator
import com.osei.aimusicstudio.audio.ExportQualityPreset
import com.osei.aimusicstudio.audio.LrcParser
import com.osei.aimusicstudio.audio.LyricLine
import com.osei.aimusicstudio.audio.MixerTrackMixSpec
import com.osei.aimusicstudio.audio.MultitrackAudioEngine
import com.osei.aimusicstudio.audio.MultitrackWavExporter
import com.osei.aimusicstudio.audio.SafeTempStorageCleanup
import com.osei.aimusicstudio.audio.WavMicrophoneRecorder
import com.osei.aimusicstudio.data.KaraokeProjectEntity
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.Phase5Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class KaraokeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = MusicDatabase.getDatabase(application)
    private val repository = Phase5Repository(db.phase5Dao())

    val audioEngine = MultitrackAudioEngine(application)
    val micRecorder = WavMicrophoneRecorder()

    val allSessions: StateFlow<List<KaraokeProjectEntity>> =
        repository.allKaraokeProjects.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _currentSession = MutableStateFlow<KaraokeProjectEntity?>(null)
    val currentSession: StateFlow<KaraokeProjectEntity?> = _currentSession.asStateFlow()

    private val _lyricLines = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyricLines: StateFlow<List<LyricLine>> = _lyricLines.asStateFlow()

    val activeLyricIndex: StateFlow<Int> = combine(
        lyricLines,
        audioEngine.positionMs,
        currentSession
    ) { lines, posMs, session ->
        LrcParser.findActiveLyricIndex(lines, posMs, session?.lyricsOffsetMs ?: 0L)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = -1
    )

    private val _selectedExportQuality = MutableStateFlow(ExportQualityPreset.WAV_44K)
    val selectedExportQuality: StateFlow<ExportQualityPreset> = _selectedExportQuality.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val cancelExportFlag = AtomicBoolean(false)
    private var syncJob: Job? = null
    private var initialized = false

    init {
        viewModelScope.launch {
            allSessions.collect { list ->
                if (list.isEmpty() && !initialized) {
                    initialized = true
                    val id = repository.createDefaultKaraokeProject("Karaoke Session 1")
                    repository.getKaraokeProject(id)?.let { selectSessionEntity(it) }
                } else if (_currentSession.value == null && list.isNotEmpty()) {
                    selectSessionEntity(list.first())
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            SafeTempStorageCleanup.cleanAbandonedTempRenderFiles(getApplication())
        }
    }

    private fun selectSessionEntity(entity: KaraokeProjectEntity) {
        audioEngine.stop()
        _currentSession.value = entity
        _lyricLines.value = LrcParser.parse(entity.rawLrcText)
        syncTracksToEngine(entity)
    }

    fun selectSession(sessionId: Long) {
        viewModelScope.launch {
            repository.getKaraokeProject(sessionId)?.let { selectSessionEntity(it) }
        }
    }

    fun createNewSession(title: String) {
        val clean = title.trim().ifEmpty { "Karaoke Session ${allSessions.value.size + 1}" }
        viewModelScope.launch {
            val id = repository.createDefaultKaraokeProject(clean)
            repository.getKaraokeProject(id)?.let { selectSessionEntity(it) }
            _statusMessage.value = "Created karaoke session '$clean'"
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            audioEngine.stop()
            repository.deleteKaraokeProject(sessionId)
            if (_currentSession.value?.id == sessionId) {
                _currentSession.value = null
            }
            _statusMessage.value = "Deleted karaoke session"
        }
    }

    fun importInstrumental(uri: Uri) {
        importAudioSlot(uri, isVocalReference = false)
    }

    fun importVocalReference(uri: Uri) {
        importAudioSlot(uri, isVocalReference = true)
    }

    private fun importAudioSlot(uri: Uri, isVocalReference: Boolean) {
        val current = _currentSession.value ?: return
        val appCtx = getApplication<Application>()
        viewModelScope.launch {
            _uiError.value = null
            _statusMessage.value = if (isVocalReference) "Validating vocal guide audio..." else "Validating instrumental audio..."
            try {
                appCtx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }

            val res = withContext(Dispatchers.IO) {
                AudioValidator.inspectAndValidate(appCtx, uri)
            }

            res.onSuccess { meta ->
                val updated = if (isVocalReference) {
                    current.copy(
                        vocalReferenceUri = uri.toString(),
                        vocalReferenceName = meta.displayName
                    )
                } else {
                    current.copy(
                        instrumentalUri = uri.toString(),
                        instrumentalName = meta.displayName,
                        instrumentalDurationMs = meta.durationMs
                    )
                }
                updateAndPersistSession(updated)
                _statusMessage.value = "Loaded '${meta.displayName}'"
            }.onFailure { err ->
                _uiError.value = "Audio import failed: ${err.localizedMessage ?: "Unsupported file"}"
                _statusMessage.value = null
            }
        }
    }

    fun importLyricsFile(uri: Uri) {
        val appCtx = getApplication<Application>()
        viewModelScope.launch {
            val readRes = withContext(Dispatchers.IO) {
                try {
                    appCtx.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = ByteArray(512 * 1024)
                        val count = stream.read(bytes)
                        if (count <= 0) "" else String(bytes, 0, count, Charsets.UTF_8)
                    } ?: throw IllegalStateException("Unable to read lyrics file")
                } catch (t: Throwable) {
                    throw t
                }
            }
            updateRawLyricsText(readRes)
            _statusMessage.value = "Loaded lyrics (${_lyricLines.value.size} lines)"
        }
    }

    fun updateRawLyricsText(rawText: String) {
        val current = _currentSession.value ?: return
        val parsed = LrcParser.parse(rawText)
        _lyricLines.value = parsed
        val updated = current.copy(rawLrcText = rawText)
        _currentSession.value = updated
        viewModelScope.launch {
            repository.saveKaraokeProject(updated)
        }
    }

    fun stampLyricLineToCurrentTime(lineIndex: Int) {
        val lines = _lyricLines.value.toMutableList()
        if (lineIndex !in lines.indices) return
        val effectiveStamp = (audioEngine.positionMs.value - (_currentSession.value?.lyricsOffsetMs ?: 0L))
            .coerceAtLeast(0L)
        lines[lineIndex] = lines[lineIndex].copy(timestampMs = effectiveStamp)
        commitEditedLyricLines(lines)
        _statusMessage.value = "Timed line ${lineIndex + 1} at ${LrcParser.formatShortTime(effectiveStamp)}"
    }

    fun nudgeLyricLineTiming(lineIndex: Int, deltaMs: Long) {
        val lines = _lyricLines.value.toMutableList()
        if (lineIndex !in lines.indices) return
        val base = if (lines[lineIndex].timestampMs < 0L) 0L else lines[lineIndex].timestampMs
        val updatedTs = (base + deltaMs).coerceAtLeast(0L)
        lines[lineIndex] = lines[lineIndex].copy(timestampMs = updatedTs)
        commitEditedLyricLines(lines)
    }

    private fun commitEditedLyricLines(lines: List<LyricLine>) {
        val sorted = if (lines.all { it.timestampMs >= 0L }) lines.sortedBy { it.timestampMs } else lines
        _lyricLines.value = sorted
        val serialized = LrcParser.formatToLrc(sorted)
        val current = _currentSession.value ?: return
        val updated = current.copy(rawLrcText = serialized)
        _currentSession.value = updated
        viewModelScope.launch {
            repository.saveKaraokeProject(updated)
        }
    }

    fun adjustGlobalLyricsOffset(deltaMs: Long) {
        val current = _currentSession.value ?: return
        val updated = current.copy(lyricsOffsetMs = (current.lyricsOffsetMs + deltaMs).coerceIn(-30_000L, 30_000L))
        _currentSession.value = updated
        viewModelScope.launch { repository.saveKaraokeProject(updated) }
    }

    fun resetGlobalLyricsOffset() {
        val current = _currentSession.value ?: return
        val updated = current.copy(lyricsOffsetMs = 0L)
        _currentSession.value = updated
        viewModelScope.launch { repository.saveKaraokeProject(updated) }
    }

    fun updateVolumes(
        instrumentalVol: Float? = null,
        vocalRefVol: Float? = null,
        recordedTakeVol: Float? = null
    ) {
        val current = _currentSession.value ?: return
        val updated = current.copy(
            instrumentalVolume = instrumentalVol?.coerceIn(0f, 1.5f) ?: current.instrumentalVolume,
            vocalReferenceVolume = vocalRefVol?.coerceIn(0f, 1.5f) ?: current.vocalReferenceVolume,
            recordedTakeVolume = recordedTakeVol?.coerceIn(0f, 1.5f) ?: current.recordedTakeVolume
        )
        _currentSession.value = updated
        audioEngine.updateMixState(buildMixSpecs(updated), 1.0f)
        viewModelScope.launch { repository.saveKaraokeProject(updated) }
    }

    fun startVocalRecording(playInstrumental: Boolean) {
        val current = _currentSession.value ?: return
        if (micRecorder.isRecording.value) return
        _uiError.value = null

        val appCtx = getApplication<Application>()
        val takeDir = File(appCtx.filesDir, "karaoke_takes").apply { mkdirs() }
        val takeFile = File(takeDir, "take_${current.id}_${System.currentTimeMillis()}.wav")

        if (playInstrumental && !current.instrumentalUri.isNullOrBlank()) {
            audioEngine.seekTo(0L)
            audioEngine.play()
        } else {
            audioEngine.pause()
        }

        _statusMessage.value = "Recording microphone audio to WAV..."
        micRecorder.startRecording(takeFile) { res ->
            viewModelScope.launch {
                audioEngine.pause()
                res.onSuccess { savedFile ->
                    val latest = _currentSession.value ?: current
                    val updated = latest.copy(
                        recordedTakeUri = Uri.fromFile(savedFile).toString(),
                        recordedTakeName = savedFile.name
                    )
                    updateAndPersistSession(updated)
                    _statusMessage.value = "Saved vocal take (${savedFile.length() / 1024} KB)"
                }.onFailure { err ->
                    _uiError.value = err.localizedMessage ?: "Vocal recording failed"
                    _statusMessage.value = null
                }
            }
        }
    }

    fun stopVocalRecording() {
        micRecorder.stopRecording()
        audioEngine.pause()
    }

    fun clearRecordedTake() {
        val current = _currentSession.value ?: return
        val updated = current.copy(
            recordedTakeUri = null,
            recordedTakeName = "",
            recordedTakeOffsetMs = 0L
        )
        updateAndPersistSession(updated)
        _statusMessage.value = "Cleared recorded vocal take"
    }

    fun adjustRecordedTakeOffset(deltaMs: Long) {
        val current = _currentSession.value ?: return
        val nextOffset = (current.recordedTakeOffsetMs + deltaMs).coerceIn(-2_000L, 2_000L)
        if (nextOffset == current.recordedTakeOffsetMs) return
        val updated = current.copy(recordedTakeOffsetMs = nextOffset)
        _currentSession.value = updated
        audioEngine.updateMixState(buildMixSpecs(updated), 1.0f)
        viewModelScope.launch { repository.saveKaraokeProject(updated) }
    }

    fun resetRecordedTakeOffset() {
        val current = _currentSession.value ?: return
        if (current.recordedTakeOffsetMs == 0L) return
        val updated = current.copy(recordedTakeOffsetMs = 0L)
        _currentSession.value = updated
        audioEngine.updateMixState(buildMixSpecs(updated), 1.0f)
        viewModelScope.launch { repository.saveKaraokeProject(updated) }
    }

    fun togglePlayPause() {
        if (audioEngine.isPlaying.value) {
            audioEngine.pause()
        } else {
            audioEngine.play()
        }
    }

    fun stopPlayback() {
        audioEngine.stop()
    }

    fun seekTo(positionMs: Long) {
        audioEngine.seekTo(positionMs)
    }

    fun saveSessionLocally() {
        val current = _currentSession.value ?: return
        viewModelScope.launch {
            repository.saveKaraokeProject(current)
            _statusMessage.value = "Saved karaoke session '${current.title}' in Room"
        }
    }

    fun setExportQuality(preset: ExportQualityPreset) {
        _selectedExportQuality.value = preset
    }

    fun exportKaraokeMixToWavUri(
        destUri: Uri,
        quality: ExportQualityPreset = _selectedExportQuality.value
    ) {
        val current = _currentSession.value ?: return
        val specs = buildMixSpecs(current)
        if (specs.none { !it.audioUri.isNullOrBlank() && it.volume > 0f }) {
            _uiError.value = "Load an instrumental or record a vocal take before exporting."
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Exporting Karaoke WAV (${quality.sampleRateHz} Hz)..."
            cancelExportFlag.set(false)

            val appCtx = getApplication<Application>()
            val tmpFile = File(appCtx.cacheDir, "karaoke_export_${System.currentTimeMillis()}.wav")

            val res = MultitrackWavExporter.exportMixToFile(
                context = appCtx,
                tracks = specs,
                masterVolume = 1.0f,
                outputFile = tmpFile,
                targetSampleRateHz = quality.sampleRateHz,
                isCancelled = { cancelExportFlag.get() },
                onProgress = { _exportProgress.value = it }
            )

            res.onSuccess { rendered ->
                val writeRes = withContext(Dispatchers.IO) {
                    try {
                        appCtx.contentResolver.openOutputStream(destUri)?.use { out ->
                            FileInputStream(rendered).use { input ->
                                val buf = ByteArray(32 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                                out.flush()
                            }
                        } ?: throw IllegalStateException("Cannot write WAV destination")
                        Result.success(rendered.length())
                    } catch (t: Throwable) {
                        Result.failure(t)
                    } finally {
                        rendered.delete()
                    }
                }
                writeRes.onSuccess { bytes ->
                    _statusMessage.value = "Exported Karaoke mix (${bytes / 1024} KB)"
                }.onFailure { err ->
                    _uiError.value = "Karaoke export write failed: ${err.localizedMessage}"
                    _statusMessage.value = null
                }
            }.onFailure { err ->
                tmpFile.delete()
                _uiError.value = err.localizedMessage ?: "Karaoke export failed"
                _statusMessage.value = null
            }

            _isExporting.value = false
        }
    }

    fun shareKaraokeMixWav(
        quality: ExportQualityPreset = _selectedExportQuality.value,
        onShareIntentReady: (Intent) -> Unit
    ) {
        val current = _currentSession.value ?: return
        val specs = buildMixSpecs(current)
        if (specs.none { !it.audioUri.isNullOrBlank() && it.volume > 0f }) {
            _uiError.value = "Load audio or record a vocal take first."
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Preparing Karaoke WAV to share..."
            cancelExportFlag.set(false)

            val appCtx = getApplication<Application>()
            val tmpFile = File(appCtx.cacheDir, "karaoke_share_${System.currentTimeMillis()}.wav")

            val res = MultitrackWavExporter.exportMixToFile(
                context = appCtx,
                tracks = specs,
                masterVolume = 1.0f,
                outputFile = tmpFile,
                targetSampleRateHz = quality.sampleRateHz,
                isCancelled = { cancelExportFlag.get() },
                onProgress = { _exportProgress.value = it }
            )

            res.onSuccess { rendered ->
                val shareFile = withContext(Dispatchers.IO) {
                    val prepared = AudioShareHelper.prepareShareFile(appCtx, rendered, "${current.title}_karaoke.wav")
                    rendered.delete()
                    prepared
                }
                _statusMessage.value = "Ready to share ${shareFile.name}"
                onShareIntentReady(AudioShareHelper.buildShareIntent(appCtx, shareFile, "Share Karaoke WAV"))
            }.onFailure { err ->
                tmpFile.delete()
                _uiError.value = err.localizedMessage ?: "Share preparation failed"
                _statusMessage.value = null
            }

            _isExporting.value = false
        }
    }

    fun cancelExport() {
        cancelExportFlag.set(true)
    }

    fun reportUiError(msg: String) {
        _uiError.value = msg
    }

    fun clearNotices() {
        _uiError.value = null
        _statusMessage.value = null
        audioEngine.clearError()
        micRecorder.clearError()
    }

    private fun updateAndPersistSession(updated: KaraokeProjectEntity) {
        _currentSession.value = updated
        syncTracksToEngine(updated)
        viewModelScope.launch {
            repository.saveKaraokeProject(updated)
        }
    }

    private fun syncTracksToEngine(entity: KaraokeProjectEntity) {
        val specs = buildMixSpecs(entity)
        audioEngine.updateMixState(specs, 1.0f)
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            audioEngine.syncTrackReaders(specs)
        }
    }

    private fun buildMixSpecs(entity: KaraokeProjectEntity): List<MixerTrackMixSpec> = listOf(
        MixerTrackMixSpec(
            trackId = 1L,
            name = "Instrumental",
            audioUri = entity.instrumentalUri,
            durationMs = entity.instrumentalDurationMs,
            volume = entity.instrumentalVolume,
            pan = 0f,
            isMuted = false,
            isSolo = false,
            isEnabled = !entity.instrumentalUri.isNullOrBlank(),
            offsetMs = 0L
        ),
        MixerTrackMixSpec(
            trackId = 2L,
            name = "Vocal Guide",
            audioUri = entity.vocalReferenceUri,
            durationMs = 0L,
            volume = entity.vocalReferenceVolume,
            pan = 0f,
            isMuted = entity.vocalReferenceVolume <= 0.01f,
            isSolo = false,
            isEnabled = !entity.vocalReferenceUri.isNullOrBlank(),
            offsetMs = 0L
        ),
        MixerTrackMixSpec(
            trackId = 3L,
            name = "Vocal Recording",
            audioUri = entity.recordedTakeUri,
            durationMs = 0L,
            volume = entity.recordedTakeVolume,
            pan = 0f,
            isMuted = false,
            isSolo = false,
            isEnabled = !entity.recordedTakeUri.isNullOrBlank(),
            offsetMs = entity.recordedTakeOffsetMs
        )
    )

    override fun onCleared() {
        super.onCleared()
        micRecorder.release()
        audioEngine.release()
        SafeTempStorageCleanup.cleanAbandonedTempRenderFiles(getApplication())
    }
}
