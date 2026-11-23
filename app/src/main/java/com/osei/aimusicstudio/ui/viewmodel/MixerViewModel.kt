package com.osei.aimusicstudio.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.osei.aimusicstudio.ai.MemoryProfiler
import com.osei.aimusicstudio.ai.MemorySnapshot
import com.osei.aimusicstudio.audio.AudioShareHelper
import com.osei.aimusicstudio.audio.AudioValidator
import com.osei.aimusicstudio.audio.ExportQualityPreset
import com.osei.aimusicstudio.audio.MixerTrackMixSpec
import com.osei.aimusicstudio.audio.MultitrackAudioEngine
import com.osei.aimusicstudio.audio.MultitrackWavExporter
import com.osei.aimusicstudio.audio.SafeTempStorageCleanup
import com.osei.aimusicstudio.audio.WaveformData
import com.osei.aimusicstudio.audio.WaveformGenerator
import com.osei.aimusicstudio.data.MixerProjectWithTracks
import com.osei.aimusicstudio.data.MixerRepository
import com.osei.aimusicstudio.data.MixerTrackEntity
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.data.TrackRole
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

class MixerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = MusicDatabase.getDatabase(application)
    private val mixerRepository = MixerRepository(db.mixerDao())
    val audioEngine = MultitrackAudioEngine(application)

    val allMixerProjects: StateFlow<List<MixerProjectWithTracks>> =
        mixerRepository.allProjectsWithTracks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _selectedProjectId = MutableStateFlow<Long?>(null)
    val selectedProjectId: StateFlow<Long?> = _selectedProjectId.asStateFlow()

    val currentMixerProject: StateFlow<MixerProjectWithTracks?> =
        combine(allMixerProjects, _selectedProjectId) { projects, selectedId ->
            projects.firstOrNull { it.project.id == selectedId } ?: projects.firstOrNull()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _trackWaveforms = MutableStateFlow<Map<Long, WaveformData>>(emptyMap())
    val trackWaveforms: StateFlow<Map<Long, WaveformData>> = _trackWaveforms.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private val _selectedExportQuality = MutableStateFlow(ExportQualityPreset.WAV_44K)
    val selectedExportQuality: StateFlow<ExportQualityPreset> = _selectedExportQuality.asStateFlow()

    private val _memorySnapshot = MutableStateFlow(MemoryProfiler.captureSnapshot(application))
    val memorySnapshot: StateFlow<MemorySnapshot> = _memorySnapshot.asStateFlow()

    private val exportCancelFlag = AtomicBoolean(false)
    private var syncJob: Job? = null
    private var initializedDefault = false

    init {
        viewModelScope.launch {
            allMixerProjects.collect { list ->
                if (list.isEmpty() && !initializedDefault) {
                    initializedDefault = true
                    val defaultId = mixerRepository.createDefaultMixerProject("Studio Mix 1")
                    _selectedProjectId.value = defaultId
                } else if (_selectedProjectId.value == null && list.isNotEmpty()) {
                    _selectedProjectId.value = list.first().project.id
                }
            }
        }

        viewModelScope.launch {
            currentMixerProject.collect { projectWithTracks ->
                if (projectWithTracks != null) {
                    val specs = projectWithTracks.tracks.map { it.toMixSpec() }
                    audioEngine.updateMixState(specs, projectWithTracks.project.masterVolume)
                    syncJob?.cancel()
                    syncJob = viewModelScope.launch {
                        audioEngine.syncTrackReaders(specs)
                        loadMissingWaveforms(projectWithTracks.tracks)
                        refreshMemorySnapshot()
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            SafeTempStorageCleanup.cleanAbandonedTempRenderFiles(getApplication())
        }
    }

    fun refreshMemorySnapshot() {
        _memorySnapshot.value = MemoryProfiler.captureSnapshot(getApplication())
    }

    fun selectMixerProject(projectId: Long) {
        audioEngine.stop()
        _selectedProjectId.value = projectId
        _statusMessage.value = null
        _uiError.value = null
    }

    fun createNewMixerProject(name: String) {
        val cleanName = name.trim().ifEmpty { "Studio Mix ${allMixerProjects.value.size + 1}" }
        viewModelScope.launch {
            audioEngine.stop()
            val id = mixerRepository.createDefaultMixerProject(cleanName)
            _selectedProjectId.value = id
            _statusMessage.value = "Created '$cleanName'"
            _uiError.value = null
        }
    }

    fun renameCurrentProject(newName: String) {
        val clean = newName.trim()
        val current = currentMixerProject.value?.project ?: return
        if (clean.isEmpty()) return
        viewModelScope.launch {
            mixerRepository.updateProject(current.copy(name = clean))
            _statusMessage.value = "Saved as '$clean'"
        }
    }

    fun saveCurrentProjectSnapshot() {
        val current = currentMixerProject.value?.project ?: return
        viewModelScope.launch {
            mixerRepository.updateProject(current)
            _statusMessage.value = "Mixer project '${current.name}' saved to Room"
        }
    }

    fun deleteMixerProject(projectId: Long) {
        viewModelScope.launch {
            audioEngine.stop()
            val target = allMixerProjects.value.firstOrNull { it.project.id == projectId }
            target?.tracks?.forEach { track ->
                audioEngine.releaseTrackReader(track.id, track.audioUri)
            }
            mixerRepository.deleteProject(projectId)
            if (_selectedProjectId.value == projectId) {
                _selectedProjectId.value = null
            }
            _statusMessage.value = "Deleted mixer project"
        }
    }

    fun loadExistingStemsFromMusicProject(musicProject: MusicProjectEntity) {
        viewModelScope.launch {
            val candidates = listOfNotNull(
                musicProject.drumsUri?.let { TrackRole.DRUMS to it },
                musicProject.bassUri?.let { TrackRole.BASS to it },
                musicProject.guitarUri?.let { TrackRole.GUITAR to it },
                musicProject.stringsUri?.let { TrackRole.STRINGS to it },
                musicProject.keysUri?.let { TrackRole.KEYS to it },
                musicProject.otherUri?.let { TrackRole.OTHER to it },
                musicProject.vocalUri?.let { TrackRole.VOCALS to it }
            ).filter { (_, uriStr) -> isUriReadable(uriStr) }

            if (candidates.isEmpty()) {
                _uiError.value =
                    "No verified stem files found on disk for '${musicProject.title}'. Load audio files manually or run AI separation first."
                return@launch
            }

            audioEngine.stop()
            val current = currentMixerProject.value
            val targetProjectId = current?.project?.id ?: mixerRepository.createDefaultMixerProject(
                name = "${musicProject.title} Mix",
                linkedMusicProjectId = musicProject.id
            )

            val tracks = current?.tracks ?: mixerRepository.getProjectWithTracks(targetProjectId)?.tracks.orEmpty()
            val appCtx = getApplication<Application>()
            var loadedCount = 0

            for ((role, uriStr) in candidates) {
                val matchingTrack = tracks.firstOrNull { it.role == role.name }
                val meta = withContext(Dispatchers.IO) {
                    AudioValidator.inspectAndValidate(appCtx, Uri.parse(uriStr)).getOrNull()
                } ?: continue

                if (matchingTrack != null) {
                    mixerRepository.updateTrack(
                        matchingTrack.copy(
                            audioUri = uriStr,
                            displayFileName = meta.displayName,
                            durationMs = meta.durationMs,
                            isEnabled = true
                        )
                    )
                    loadedCount++
                }
            }

            if (loadedCount == 0) {
                _uiError.value = "Could not read stem audio files for '${musicProject.title}'."
            } else {
                _uiError.value = null
                _statusMessage.value = "Loaded $loadedCount existing stem tracks from '${musicProject.title}'"
            }
        }
    }

    private fun isUriReadable(uriStr: String): Boolean {
        return try {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return false)
                f.exists() && f.length() > 44L
            } else {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { true } ?: false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun importOrReplaceTrackAudio(trackId: Long, uri: Uri) {
        val current = currentMixerProject.value ?: return
        val targetTrack = current.tracks.firstOrNull { it.id == trackId } ?: return
        val appCtx = getApplication<Application>()

        viewModelScope.launch {
            _uiError.value = null
            _statusMessage.value = "Validating audio for ${targetTrack.name}..."
            try {
                appCtx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }

            val validation = withContext(Dispatchers.IO) {
                AudioValidator.inspectAndValidate(appCtx, uri)
            }

            validation.onSuccess { meta ->
                if (targetTrack.audioUri != null && targetTrack.audioUri != uri.toString()) {
                    audioEngine.releaseTrackReader(targetTrack.id, targetTrack.audioUri)
                    _trackWaveforms.value = _trackWaveforms.value - targetTrack.id
                }
                val updated = targetTrack.copy(
                    audioUri = uri.toString(),
                    displayFileName = meta.displayName,
                    durationMs = meta.durationMs,
                    isEnabled = true
                )
                mixerRepository.updateTrack(updated)
                _statusMessage.value = "Loaded '${meta.displayName}' into ${targetTrack.name}"
            }.onFailure { err ->
                _uiError.value = "Cannot load track '${targetTrack.name}': ${err.localizedMessage ?: "Unsupported or corrupted file"}"
                _statusMessage.value = null
            }
        }
    }

    fun addTrack(name: String) {
        val current = currentMixerProject.value ?: return
        val cleanName = name.trim().ifEmpty { "Track ${current.tracks.size + 1}" }
        val nextSort = (current.tracks.maxOfOrNull { it.sortOrder } ?: -1) + 1
        viewModelScope.launch {
            mixerRepository.addTrack(
                MixerTrackEntity(
                    mixerProjectId = current.project.id,
                    name = cleanName,
                    role = TrackRole.CUSTOM.name,
                    sortOrder = nextSort
                )
            )
            _statusMessage.value = "Added track '$cleanName'"
        }
    }

    fun removeTrack(track: MixerTrackEntity) {
        viewModelScope.launch {
            audioEngine.releaseTrackReader(track.id, track.audioUri)
            _trackWaveforms.value = _trackWaveforms.value - track.id
            mixerRepository.deleteTrack(track)
            _statusMessage.value = "Removed track '${track.name}'"
            refreshMemorySnapshot()
        }
    }

    fun updateMasterVolume(volume: Float) {
        val current = currentMixerProject.value?.project ?: return
        val clamped = volume.coerceIn(0f, 1.5f)
        val specs = currentMixerProject.value?.tracks?.map { it.toMixSpec() }.orEmpty()
        audioEngine.updateMixState(specs, clamped)
        viewModelScope.launch {
            mixerRepository.updateProject(current.copy(masterVolume = clamped))
        }
    }

    fun updateTrackVolume(trackId: Long, volume: Float) {
        updateTrackField(trackId) { it.copy(volume = volume.coerceIn(0f, 1.5f)) }
    }

    fun updateTrackPan(trackId: Long, pan: Float) {
        updateTrackField(trackId) { it.copy(pan = pan.coerceIn(-1f, 1f)) }
    }

    fun toggleTrackMute(trackId: Long) {
        updateTrackField(trackId) { it.copy(isMuted = !it.isMuted) }
    }

    fun toggleTrackSolo(trackId: Long) {
        updateTrackField(trackId) { it.copy(isSolo = !it.isSolo) }
    }

    fun toggleTrackEnabled(trackId: Long) {
        updateTrackField(trackId) { it.copy(isEnabled = !it.isEnabled) }
    }

    fun nudgeTrackOffset(trackId: Long, deltaMs: Long) {
        updateTrackField(trackId) {
            it.copy(offsetMs = (it.offsetMs + deltaMs).coerceIn(-30_000L, 120_000L))
        }
    }

    fun resetTrackOffset(trackId: Long) {
        updateTrackField(trackId) { it.copy(offsetMs = 0L) }
    }

    private fun updateTrackField(trackId: Long, transform: (MixerTrackEntity) -> MixerTrackEntity) {
        val projectWithTracks = currentMixerProject.value ?: return
        val track = projectWithTracks.tracks.firstOrNull { it.id == trackId } ?: return
        val updated = transform(track)
        val updatedSpecs = projectWithTracks.tracks.map { if (it.id == trackId) updated.toMixSpec() else it.toMixSpec() }
        audioEngine.updateMixState(updatedSpecs, projectWithTracks.project.masterVolume)
        viewModelScope.launch {
            mixerRepository.updateTrack(updated)
        }
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

    fun skipBy(deltaMs: Long) {
        val next = audioEngine.positionMs.value + deltaMs
        audioEngine.seekTo(next)
    }

    fun setExportQuality(preset: ExportQualityPreset) {
        _selectedExportQuality.value = preset
    }

    fun exportMixToWavUri(
        destUri: Uri,
        quality: ExportQualityPreset = _selectedExportQuality.value
    ) {
        val projectWithTracks = currentMixerProject.value
        if (projectWithTracks == null) {
            _uiError.value = "No active mixer project to export."
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Mixing tracks & writing ${quality.sampleRateHz} Hz WAV..."
            exportCancelFlag.set(false)

            val appCtx = getApplication<Application>()
            val tmpMixFile = File(appCtx.cacheDir, "mix_export_${System.currentTimeMillis()}.wav")
            val specs = projectWithTracks.tracks.map { it.toMixSpec() }

            val mixResult = MultitrackWavExporter.exportMixToFile(
                context = appCtx,
                tracks = specs,
                masterVolume = projectWithTracks.project.masterVolume,
                outputFile = tmpMixFile,
                targetSampleRateHz = quality.sampleRateHz,
                isCancelled = { exportCancelFlag.get() },
                onProgress = { p -> _exportProgress.value = p }
            )

            mixResult.onSuccess { renderedFile ->
                val copyRes = withContext(Dispatchers.IO) {
                    try {
                        appCtx.contentResolver.openOutputStream(destUri)?.use { out ->
                            FileInputStream(renderedFile).use { input ->
                                val buf = ByteArray(32 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                                out.flush()
                            }
                        } ?: throw IllegalStateException("Unable to open destination WAV output stream")
                        Result.success(renderedFile.length())
                    } catch (t: Throwable) {
                        Result.failure(t)
                    } finally {
                        renderedFile.delete()
                    }
                }
                copyRes.onSuccess { bytes ->
                    _statusMessage.value = "Exported mixed ${quality.sampleRateHz} Hz WAV (${bytes / 1024} KB)"
                }.onFailure { err ->
                    _uiError.value = "WAV export write failed: ${err.localizedMessage}"
                    _statusMessage.value = null
                }
            }.onFailure { err ->
                tmpMixFile.delete()
                _uiError.value = err.localizedMessage ?: "Mix export failed"
                _statusMessage.value = null
            }

            _isExporting.value = false
            refreshMemorySnapshot()
        }
    }

    fun exportSingleTrackToWavUri(
        trackId: Long,
        destUri: Uri,
        quality: ExportQualityPreset = _selectedExportQuality.value
    ) {
        val projectWithTracks = currentMixerProject.value
        val track = projectWithTracks?.tracks?.firstOrNull { it.id == trackId }
        if (track == null) {
            _uiError.value = "Track not found for export."
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Rendering '${track.name}' track as ${quality.sampleRateHz} Hz WAV..."
            exportCancelFlag.set(false)

            val appCtx = getApplication<Application>()
            val tmpTrackFile = File(appCtx.cacheDir, "track_export_${track.id}_${System.currentTimeMillis()}.wav")

            val renderResult = MultitrackWavExporter.exportSingleTrackToFile(
                context = appCtx,
                track = track.toMixSpec(),
                outputFile = tmpTrackFile,
                targetSampleRateHz = quality.sampleRateHz,
                isCancelled = { exportCancelFlag.get() },
                onProgress = { p -> _exportProgress.value = p }
            )

            renderResult.onSuccess { renderedFile ->
                val copyRes = withContext(Dispatchers.IO) {
                    try {
                        appCtx.contentResolver.openOutputStream(destUri)?.use { out ->
                            FileInputStream(renderedFile).use { input ->
                                val buf = ByteArray(32 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                                out.flush()
                            }
                        } ?: throw IllegalStateException("Unable to open destination WAV output stream")
                        Result.success(renderedFile.length())
                    } catch (t: Throwable) {
                        Result.failure(t)
                    } finally {
                        renderedFile.delete()
                    }
                }
                copyRes.onSuccess { bytes ->
                    _statusMessage.value = "Exported '${track.name}' WAV (${bytes / 1024} KB)"
                }.onFailure { err ->
                    _uiError.value = "Track WAV export write failed: ${err.localizedMessage}"
                    _statusMessage.value = null
                }
            }.onFailure { err ->
                tmpTrackFile.delete()
                _uiError.value = err.localizedMessage ?: "Track export failed"
                _statusMessage.value = null
            }

            _isExporting.value = false
            refreshMemorySnapshot()
        }
    }

    fun shareMixWav(
        quality: ExportQualityPreset = _selectedExportQuality.value,
        onShareIntentReady: (Intent) -> Unit
    ) {
        val projectWithTracks = currentMixerProject.value
        if (projectWithTracks == null) {
            _uiError.value = "No active mixer project to share."
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Preparing ${quality.sampleRateHz} Hz WAV to share..."
            exportCancelFlag.set(false)

            val appCtx = getApplication<Application>()
            val tmpMixFile = File(appCtx.cacheDir, "share_mix_${System.currentTimeMillis()}.wav")
            val specs = projectWithTracks.tracks.map { it.toMixSpec() }

            val mixResult = MultitrackWavExporter.exportMixToFile(
                context = appCtx,
                tracks = specs,
                masterVolume = projectWithTracks.project.masterVolume,
                outputFile = tmpMixFile,
                targetSampleRateHz = quality.sampleRateHz,
                isCancelled = { exportCancelFlag.get() },
                onProgress = { p -> _exportProgress.value = p }
            )

            mixResult.onSuccess { renderedFile ->
                val shareFile = withContext(Dispatchers.IO) {
                    val safeName = projectWithTracks.project.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    val prepared = AudioShareHelper.prepareShareFile(appCtx, renderedFile, "${safeName}_mix.wav")
                    renderedFile.delete()
                    prepared
                }
                _statusMessage.value = "Ready to share ${shareFile.name}"
                onShareIntentReady(AudioShareHelper.buildShareIntent(appCtx, shareFile, "Share Mixdown WAV"))
            }.onFailure { err ->
                tmpMixFile.delete()
                _uiError.value = err.localizedMessage ?: "Failed to prepare WAV for sharing"
                _statusMessage.value = null
            }

            _isExporting.value = false
            refreshMemorySnapshot()
        }
    }

    fun shareSingleTrackWav(
        trackId: Long,
        quality: ExportQualityPreset = _selectedExportQuality.value,
        onShareIntentReady: (Intent) -> Unit
    ) {
        val projectWithTracks = currentMixerProject.value
        val track = projectWithTracks?.tracks?.firstOrNull { it.id == trackId }
        if (track == null) {
            _uiError.value = "Track not found for sharing."
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Preparing '${track.name}' WAV to share..."
            exportCancelFlag.set(false)

            val appCtx = getApplication<Application>()
            val tmpFile = File(appCtx.cacheDir, "share_track_${track.id}_${System.currentTimeMillis()}.wav")

            val res = MultitrackWavExporter.exportSingleTrackToFile(
                context = appCtx,
                track = track.toMixSpec(),
                outputFile = tmpFile,
                targetSampleRateHz = quality.sampleRateHz,
                isCancelled = { exportCancelFlag.get() },
                onProgress = { p -> _exportProgress.value = p }
            )

            res.onSuccess { renderedFile ->
                val shareFile = withContext(Dispatchers.IO) {
                    val safeTrack = track.name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
                    val prepared = AudioShareHelper.prepareShareFile(appCtx, renderedFile, "${safeTrack}.wav")
                    renderedFile.delete()
                    prepared
                }
                _statusMessage.value = "Ready to share ${shareFile.name}"
                onShareIntentReady(AudioShareHelper.buildShareIntent(appCtx, shareFile, "Share Track WAV"))
            }.onFailure { err ->
                tmpFile.delete()
                _uiError.value = err.localizedMessage ?: "Failed to prepare track WAV for sharing"
                _statusMessage.value = null
            }

            _isExporting.value = false
            refreshMemorySnapshot()
        }
    }

    fun cancelExport() {
        exportCancelFlag.set(true)
    }

    fun handleLowMemory() {
        audioEngine.handleLowMemory()
        _trackWaveforms.value = emptyMap()
        refreshMemorySnapshot()
    }

    fun clearNotices() {
        _uiError.value = null
        _statusMessage.value = null
        audioEngine.clearError()
    }

    private suspend fun loadMissingWaveforms(tracks: List<MixerTrackEntity>) {
        val appCtx = getApplication<Application>()
        val currentMap = _trackWaveforms.value.toMutableMap()
        var changed = false
        for (track in tracks) {
            val uri = track.audioUri
            if (uri.isNullOrBlank()) {
                if (currentMap.remove(track.id) != null) changed = true
                continue
            }
            if (!currentMap.containsKey(track.id)) {
                val res = WaveformGenerator.generateWaveform(appCtx, uri, barsCount = 48, useCache = true)
                res.onSuccess { wf ->
                    currentMap[track.id] = wf
                    changed = true
                }
            }
        }
        val activeIds = tracks.map { it.id }.toSet()
        currentMap.keys.retainAll(activeIds)
        if (changed) {
            _trackWaveforms.value = currentMap.toMap()
        }
    }

    private fun MixerTrackEntity.toMixSpec() = MixerTrackMixSpec(
        trackId = id,
        name = name,
        audioUri = audioUri,
        durationMs = durationMs,
        volume = volume,
        pan = pan,
        isMuted = isMuted,
        isSolo = isSolo,
        isEnabled = isEnabled,
        offsetMs = offsetMs
    )

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
        SafeTempStorageCleanup.cleanAbandonedTempRenderFiles(getApplication())
    }
}
