package com.osei.aimusicstudio.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.osei.aimusicstudio.ai.MemoryProfiler
import com.osei.aimusicstudio.ai.MemorySnapshot
import com.osei.aimusicstudio.audio.AudioEffectsConfig
import com.osei.aimusicstudio.audio.AudioPlayerManager
import com.osei.aimusicstudio.audio.AudioShareHelper
import com.osei.aimusicstudio.audio.AudioValidator
import com.osei.aimusicstudio.audio.EditorHistorySnapshot
import com.osei.aimusicstudio.audio.ExportQualityPreset
import com.osei.aimusicstudio.audio.SafeTempStorageCleanup
import com.osei.aimusicstudio.audio.TimelineAudioRenderer
import com.osei.aimusicstudio.audio.TimelineClipOperations
import com.osei.aimusicstudio.audio.TimelineClipSpec
import com.osei.aimusicstudio.audio.TimelineHistoryManager
import com.osei.aimusicstudio.audio.VocalTransformConfig
import com.osei.aimusicstudio.data.EditorProjectEntity
import com.osei.aimusicstudio.data.EditorProjectWithClips
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.data.Phase5Repository
import com.osei.aimusicstudio.data.toTimelineSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val db = MusicDatabase.getDatabase(application)
    private val repository = Phase5Repository(db.phase5Dao())
    val previewPlayer = AudioPlayerManager(application)

    val allProjects: StateFlow<List<EditorProjectWithClips>> =
        repository.allEditorProjects.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val history = TimelineHistoryManager<EditorHistorySnapshot>(maxDepth = 30)
    private val nextTempClipId = AtomicLong(System.currentTimeMillis())

    private val _currentProject = MutableStateFlow<EditorProjectEntity?>(null)
    val currentProject: StateFlow<EditorProjectEntity?> = _currentProject.asStateFlow()

    private val _clips = MutableStateFlow<List<TimelineClipSpec>>(emptyList())
    val clips: StateFlow<List<TimelineClipSpec>> = _clips.asStateFlow()

    private val _selectedClipId = MutableStateFlow<Long?>(null)
    val selectedClipId: StateFlow<Long?> = _selectedClipId.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _selectedExportQuality = MutableStateFlow(ExportQualityPreset.WAV_44K)
    val selectedExportQuality: StateFlow<ExportQualityPreset> = _selectedExportQuality.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _renderProgress = MutableStateFlow(0f)
    val renderProgress: StateFlow<Float> = _renderProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val _memorySnapshot = MutableStateFlow(MemoryProfiler.captureSnapshot(application))
    val memorySnapshot: StateFlow<MemorySnapshot> = _memorySnapshot.asStateFlow()

    private val cancelFlag = AtomicBoolean(false)
    private var activeRenderJob: Job? = null
    private var initialized = false

    init {
        viewModelScope.launch {
            allProjects.collect { list ->
                if (list.isEmpty() && !initialized) {
                    initialized = true
                    val id = repository.createDefaultEditorProject("Audio Edit 1")
                    loadProjectIntoEditor(id)
                } else if (_currentProject.value == null && list.isNotEmpty()) {
                    loadProjectWithClips(list.first())
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runSafeTempCleanup()
        }
    }

    private suspend fun loadProjectIntoEditor(projectId: Long) {
        val full = repository.getEditorProject(projectId) ?: return
        loadProjectWithClips(full)
    }

    private fun loadProjectWithClips(projectWithClips: EditorProjectWithClips) {
        previewPlayer.stop()
        history.clear()
        _currentProject.value = projectWithClips.project
        val specs = projectWithClips.clips.map { it.toTimelineSpec() }
        _clips.value = specs
        _selectedClipId.value = specs.firstOrNull()?.clipId
        _playheadMs.value = 0L
        _hasUnsavedChanges.value = false
        syncHistoryFlags()
        refreshMemorySnapshot()
    }

    fun selectProject(projectId: Long) {
        viewModelScope.launch {
            loadProjectIntoEditor(projectId)
            _statusMessage.value = null
            _uiError.value = null
        }
    }

    fun createNewProject(name: String) {
        val clean = name.trim().ifEmpty { "Audio Edit ${allProjects.value.size + 1}" }
        viewModelScope.launch {
            val id = repository.createDefaultEditorProject(clean)
            loadProjectIntoEditor(id)
            _statusMessage.value = "Created editor project '$clean'"
            _uiError.value = null
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch {
            previewPlayer.stop()
            repository.deleteEditorProject(projectId)
            if (_currentProject.value?.id == projectId) {
                _currentProject.value = null
                _clips.value = emptyList()
                _selectedClipId.value = null
            }
            _statusMessage.value = "Deleted editor project"
        }
    }

    fun importAudioClip(uri: Uri) {
        val appCtx = getApplication<Application>()
        viewModelScope.launch {
            _uiError.value = null
            _statusMessage.value = "Inspecting audio file..."
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
                pushUndoSnapshot()
                val startAtMs = (_clips.value.maxOfOrNull { it.timelineEndMs } ?: 0L)
                val newClip = TimelineClipSpec(
                    clipId = nextTempClipId.incrementAndGet(),
                    name = meta.title.ifBlank { meta.displayName },
                    sourceUri = uri.toString(),
                    totalSourceDurationMs = meta.durationMs,
                    sourceStartMs = 0L,
                    sourceEndMs = meta.durationMs,
                    timelineStartMs = startAtMs
                )
                _clips.value = _clips.value + newClip
                _selectedClipId.value = newClip.clipId
                _hasUnsavedChanges.value = true
                _statusMessage.value = "Added clip '${newClip.name}' (${meta.durationMs / 1000.0}s)"
            }.onFailure { err ->
                _uiError.value = "Cannot import audio: ${err.localizedMessage ?: "Unsupported file"}"
                _statusMessage.value = null
            }
        }
    }

    /**
     * Opens a Library project's audio directly in the Audio Editor while preserving
     * any existing editor project and clips on the timeline.
     */
    fun openLibraryProjectAudio(project: MusicProjectEntity) {
        val appCtx = getApplication<Application>()
        viewModelScope.launch {
            _uiError.value = null
            if (_currentProject.value == null) {
                val existing = repository.allEditorProjects.first().firstOrNull()
                if (existing != null) {
                    loadProjectWithClips(existing)
                } else {
                    initialized = true
                    val id = repository.createDefaultEditorProject("Audio Edit 1")
                    loadProjectIntoEditor(id)
                }
            }

            val existingClip = _clips.value.firstOrNull { it.sourceUri == project.audioUri }
            if (existingClip != null) {
                _selectedClipId.value = existingClip.clipId
                _playheadMs.value = existingClip.timelineStartMs
                _statusMessage.value = "Selected '${project.title}' in Audio Editor"
                return@launch
            }

            val parsedUri = try {
                Uri.parse(project.audioUri)
            } catch (_: Throwable) {
                _uiError.value = "Invalid audio URI for '${project.title}'"
                return@launch
            }

            _statusMessage.value = "Opening '${project.title}' in Audio Editor..."
            val res = withContext(Dispatchers.IO) {
                AudioValidator.inspectAndValidate(appCtx, parsedUri)
            }

            res.onSuccess { meta ->
                pushUndoSnapshot()
                val startAtMs = (_clips.value.maxOfOrNull { it.timelineEndMs } ?: 0L)
                val newClip = TimelineClipSpec(
                    clipId = nextTempClipId.incrementAndGet(),
                    name = project.title.ifBlank { meta.displayName },
                    sourceUri = project.audioUri,
                    totalSourceDurationMs = meta.durationMs,
                    sourceStartMs = 0L,
                    sourceEndMs = meta.durationMs,
                    timelineStartMs = startAtMs
                )
                _clips.value = _clips.value + newClip
                _selectedClipId.value = newClip.clipId
                _playheadMs.value = startAtMs
                _hasUnsavedChanges.value = true
                _statusMessage.value = "Opened '${newClip.name}' in Audio Editor"
            }.onFailure { err ->
                _uiError.value = "Cannot open '${project.title}' in Audio Editor: ${err.localizedMessage ?: "Audio file unreadable"}"
                _statusMessage.value = null
            }
        }
    }

    fun selectClip(clipId: Long) {
        _selectedClipId.value = clipId
        val clip = _clips.value.firstOrNull { it.clipId == clipId } ?: return
        _playheadMs.value = (clip.timelineStartMs + clip.outputDurationMs / 2L).coerceAtLeast(0L)
    }

    fun setPlayheadMs(positionMs: Long) {
        val maxEnd = (_clips.value.maxOfOrNull { it.timelineEndMs } ?: 0L).coerceAtLeast(0L)
        _playheadMs.value = positionMs.coerceIn(0L, maxEnd)
    }

    fun applyTrimToSelected(newStartMs: Long, newEndMs: Long) {
        val tid = _selectedClipId.value ?: return
        val existing = _clips.value.firstOrNull { it.clipId == tid } ?: return
        if (existing.sourceStartMs == newStartMs && existing.sourceEndMs == newEndMs) return
        pushUndoSnapshot()
        _clips.value = TimelineClipOperations.trimClip(_clips.value, tid, newStartMs, newEndMs)
        _hasUnsavedChanges.value = true
        _statusMessage.value = "Trimmed '${existing.name}'"
    }

    fun splitSelectedClipAtPlayhead() {
        val tid = _selectedClipId.value ?: run {
            _uiError.value = "Select a clip on the timeline first."
            return
        }
        val target = _clips.value.firstOrNull { it.clipId == tid } ?: return
        val splitPoint = _playheadMs.value.coerceIn(target.timelineStartMs, target.timelineEndMs)
        val splitRes = TimelineClipOperations.splitClipAtTimelineMs(
            clips = _clips.value,
            clipId = tid,
            splitTimelineMs = splitPoint,
            secondHalfClipId = nextTempClipId.incrementAndGet()
        )
        splitRes.onSuccess { updated ->
            pushUndoSnapshot()
            _clips.value = updated
            _hasUnsavedChanges.value = true
            _uiError.value = null
            _statusMessage.value = "Split '${target.name}' at ${splitPoint} ms"
        }.onFailure { err ->
            _uiError.value = err.localizedMessage ?: "Cannot split at current playhead"
        }
    }

    fun moveSelectedClipBy(deltaMs: Long) {
        val tid = _selectedClipId.value ?: return
        val target = _clips.value.firstOrNull { it.clipId == tid } ?: return
        val nextStart = (target.timelineStartMs + deltaMs).coerceAtLeast(0L)
        if (nextStart == target.timelineStartMs) return
        pushUndoSnapshot()
        _clips.value = TimelineClipOperations.moveClipTimelineStart(_clips.value, tid, nextStart)
        _hasUnsavedChanges.value = true
        _statusMessage.value = "Moved '${target.name}' to ${nextStart / 1000.0}s"
    }

    fun deleteSelectedClip() {
        val tid = _selectedClipId.value ?: return
        val target = _clips.value.firstOrNull { it.clipId == tid } ?: return
        pushUndoSnapshot()
        val remaining = TimelineClipOperations.deleteClip(_clips.value, tid)
        _clips.value = remaining
        _selectedClipId.value = remaining.firstOrNull()?.clipId
        _hasUnsavedChanges.value = true
        _statusMessage.value = "Deleted clip '${target.name}' (tap Undo to restore)"
    }

    fun mergeTimelineClipsSequentially() {
        val currentClips = _clips.value
        if (currentClips.size < 2) {
            _uiError.value = "Load or split at least 2 clips on the timeline before merging."
            return
        }

        activeRenderJob?.cancel()
        activeRenderJob = viewModelScope.launch {
            _isBusy.value = true
            _renderProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Merging ${currentClips.size} clips sequentially to disk WAV..."
            cancelFlag.set(false)

            val appCtx = getApplication<Application>()
            val mergeDir = File(appCtx.filesDir, "editor_clips").apply { mkdirs() }
            val mergedFile = File(mergeDir, "merged_${System.currentTimeMillis()}.wav")

            val result = TimelineAudioRenderer.mergeClipsSequentiallyToFile(
                context = appCtx,
                clips = currentClips,
                outputFile = mergedFile,
                targetSampleRateHz = 44100,
                isCancelled = { cancelFlag.get() },
                onProgress = { _renderProgress.value = it }
            )

            result.onSuccess { outFile ->
                val meta = withContext(Dispatchers.IO) {
                    AudioValidator.inspectAndValidate(appCtx, Uri.fromFile(outFile)).getOrNull()
                }
                val durMs = meta?.durationMs ?: currentClips.sumOf { it.outputDurationMs }
                val earliestStart = currentClips.minOf { it.timelineStartMs }
                pushUndoSnapshot()
                val mergedClip = TimelineClipSpec(
                    clipId = nextTempClipId.incrementAndGet(),
                    name = "Merged (${currentClips.size} clips)",
                    sourceUri = Uri.fromFile(outFile).toString(),
                    totalSourceDurationMs = durMs,
                    sourceStartMs = 0L,
                    sourceEndMs = durMs,
                    timelineStartMs = earliestStart
                )
                _clips.value = listOf(mergedClip)
                _selectedClipId.value = mergedClip.clipId
                _hasUnsavedChanges.value = true
                _statusMessage.value = "Merged ${currentClips.size} clips (${durMs / 1000.0}s)"
            }.onFailure { err ->
                mergedFile.delete()
                _uiError.value = err.localizedMessage ?: "Merge failed"
                _statusMessage.value = null
            }

            _isBusy.value = false
            refreshMemorySnapshot()
        }
    }

    fun updateSelectedEffects(newEffects: AudioEffectsConfig, recordUndo: Boolean = false) {
        val tid = _selectedClipId.value ?: return
        if (recordUndo) pushUndoSnapshot()
        _clips.value = _clips.value.map { c ->
            if (c.clipId == tid) c.copy(effectsConfig = newEffects) else c
        }
        _hasUnsavedChanges.value = true
    }

    fun updateSelectedVocalTransform(newVocal: VocalTransformConfig, recordUndo: Boolean = false) {
        val tid = _selectedClipId.value ?: return
        if (recordUndo) pushUndoSnapshot()
        _clips.value = _clips.value.map { c ->
            if (c.clipId == tid) c.copy(vocalConfig = newVocal) else c
        }
        _hasUnsavedChanges.value = true
    }

    fun recordUndoPointBeforeEdit() {
        pushUndoSnapshot()
    }

    fun undo() {
        val current = EditorHistorySnapshot(_clips.value, _selectedClipId.value)
        val prev = history.undo(current) ?: return
        _clips.value = prev.clips
        _selectedClipId.value = prev.selectedClipId ?: prev.clips.firstOrNull()?.clipId
        _hasUnsavedChanges.value = true
        syncHistoryFlags()
        _statusMessage.value = "Undid last edit"
    }

    fun redo() {
        val current = EditorHistorySnapshot(_clips.value, _selectedClipId.value)
        val next = history.redo(current) ?: return
        _clips.value = next.clips
        _selectedClipId.value = next.selectedClipId ?: next.clips.firstOrNull()?.clipId
        _hasUnsavedChanges.value = true
        syncHistoryFlags()
        _statusMessage.value = "Redid edit"
    }

    fun saveProjectLocally() {
        val proj = _currentProject.value ?: return
        viewModelScope.launch {
            repository.saveEditorClips(proj, _clips.value)
            _hasUnsavedChanges.value = false
            _statusMessage.value = "Saved '${proj.name}' (${_clips.value.size} clips) to local Room database"
            withContext(Dispatchers.IO) {
                runSafeTempCleanup()
            }
        }
    }

    fun previewSelectedClip() {
        val tid = _selectedClipId.value
        val clip = _clips.value.firstOrNull { it.clipId == tid }
        if (clip == null) {
            _uiError.value = "Import or select a clip first to preview."
            return
        }
        renderAndPlayPreview(isFullTimeline = false, clip = clip)
    }

    fun previewFullTimeline() {
        if (_clips.value.isEmpty()) {
            _uiError.value = "Import at least one audio clip first."
            return
        }
        renderAndPlayPreview(isFullTimeline = true, clip = null)
    }

    private fun renderAndPlayPreview(isFullTimeline: Boolean, clip: TimelineClipSpec?) {
        activeRenderJob?.cancel()
        previewPlayer.stop()
        activeRenderJob = viewModelScope.launch {
            _isBusy.value = true
            _renderProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = if (isFullTimeline) {
                "Rendering timeline DSP preview..."
            } else {
                "Rendering '${clip?.name}' DSP & vocal preview..."
            }
            cancelFlag.set(false)

            val appCtx = getApplication<Application>()
            val previewFile = File(appCtx.cacheDir, "editor_preview_active.wav")

            val result = if (isFullTimeline) {
                TimelineAudioRenderer.renderTimelineToFile(
                    context = appCtx,
                    clips = _clips.value,
                    outputFile = previewFile,
                    targetSampleRateHz = 44100,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { _renderProgress.value = it }
                )
            } else {
                TimelineAudioRenderer.renderSingleClipToFile(
                    context = appCtx,
                    clip = clip!!,
                    outputFile = previewFile,
                    targetSampleRateHz = 44100,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { _renderProgress.value = it }
                )
            }

            result.onSuccess { wavFile ->
                _statusMessage.value = "Playing rendered preview"
                previewPlayer.loadAndPlay(Uri.fromFile(wavFile).toString())
            }.onFailure { err ->
                _uiError.value = err.localizedMessage ?: "Preview render failed"
                _statusMessage.value = null
            }

            _isBusy.value = false
            refreshMemorySnapshot()
        }
    }

    fun setExportQuality(preset: ExportQualityPreset) {
        _selectedExportQuality.value = preset
    }

    fun exportSelectedClipToWavUri(destUri: Uri, quality: ExportQualityPreset = _selectedExportQuality.value) {
        val clip = _clips.value.firstOrNull { it.clipId == _selectedClipId.value }
        if (clip == null) {
            _uiError.value = "Select a clip first to export."
            return
        }
        exportWavToUriInternal(destUri, quality, selectedClip = clip)
    }

    fun exportTimelineToWavUri(destUri: Uri, quality: ExportQualityPreset = _selectedExportQuality.value) {
        if (_clips.value.isEmpty()) {
            _uiError.value = "Timeline is empty."
            return
        }
        exportWavToUriInternal(destUri, quality, selectedClip = null)
    }

    private fun exportWavToUriInternal(
        destUri: Uri,
        quality: ExportQualityPreset,
        selectedClip: TimelineClipSpec?
    ) {
        activeRenderJob?.cancel()
        activeRenderJob = viewModelScope.launch {
            _isBusy.value = true
            _renderProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Exporting ${quality.sampleRateHz} Hz WAV..."
            cancelFlag.set(false)

            val appCtx = getApplication<Application>()
            val tmpOut = File(appCtx.cacheDir, "editor_export_${System.currentTimeMillis()}.wav")

            val res = if (selectedClip != null) {
                TimelineAudioRenderer.renderSingleClipToFile(
                    context = appCtx,
                    clip = selectedClip,
                    outputFile = tmpOut,
                    targetSampleRateHz = quality.sampleRateHz,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { _renderProgress.value = it }
                )
            } else {
                TimelineAudioRenderer.renderTimelineToFile(
                    context = appCtx,
                    clips = _clips.value,
                    outputFile = tmpOut,
                    targetSampleRateHz = quality.sampleRateHz,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { _renderProgress.value = it }
                )
            }

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
                        } ?: throw IllegalStateException("Unable to open WAV destination URI")
                        Result.success(rendered.length())
                    } catch (t: Throwable) {
                        Result.failure(t)
                    } finally {
                        rendered.delete()
                    }
                }
                writeRes.onSuccess { bytes ->
                    _statusMessage.value = "Exported ${quality.sampleRateHz} Hz WAV (${bytes / 1024} KB)"
                }.onFailure { err ->
                    _uiError.value = "Export write error: ${err.localizedMessage}"
                    _statusMessage.value = null
                }
            }.onFailure { err ->
                tmpOut.delete()
                _uiError.value = err.localizedMessage ?: "Export failed"
                _statusMessage.value = null
            }

            _isBusy.value = false
            refreshMemorySnapshot()
        }
    }

    fun shareRenderedAudio(
        exportSingleClipOnly: Boolean,
        quality: ExportQualityPreset = _selectedExportQuality.value,
        onShareIntentReady: (Intent) -> Unit
    ) {
        val clip = if (exportSingleClipOnly) _clips.value.firstOrNull { it.clipId == _selectedClipId.value } else null
        if (exportSingleClipOnly && clip == null) {
            _uiError.value = "Select a clip first to share."
            return
        }
        if (!exportSingleClipOnly && _clips.value.isEmpty()) {
            _uiError.value = "Timeline is empty."
            return
        }

        activeRenderJob?.cancel()
        activeRenderJob = viewModelScope.launch {
            _isBusy.value = true
            _renderProgress.value = 0f
            _uiError.value = null
            _statusMessage.value = "Preparing WAV for sharing..."
            cancelFlag.set(false)

            val appCtx = getApplication<Application>()
            val tmpOut = File(appCtx.cacheDir, "editor_share_${System.currentTimeMillis()}.wav")

            val res = if (clip != null) {
                TimelineAudioRenderer.renderSingleClipToFile(
                    context = appCtx,
                    clip = clip,
                    outputFile = tmpOut,
                    targetSampleRateHz = quality.sampleRateHz,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { _renderProgress.value = it }
                )
            } else {
                TimelineAudioRenderer.renderTimelineToFile(
                    context = appCtx,
                    clips = _clips.value,
                    outputFile = tmpOut,
                    targetSampleRateHz = quality.sampleRateHz,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { _renderProgress.value = it }
                )
            }

            res.onSuccess { rendered ->
                val shareFile = withContext(Dispatchers.IO) {
                    val baseName = clip?.name ?: _currentProject.value?.name ?: "osei_edit"
                    val prepared = AudioShareHelper.prepareShareFile(appCtx, rendered, "${baseName}.wav")
                    rendered.delete()
                    prepared
                }
                _statusMessage.value = "Ready to share ${shareFile.name}"
                onShareIntentReady(AudioShareHelper.buildShareIntent(appCtx, shareFile, "Share Edited WAV"))
            }.onFailure { err ->
                tmpOut.delete()
                _uiError.value = err.localizedMessage ?: "Share preparation failed"
                _statusMessage.value = null
            }

            _isBusy.value = false
            refreshMemorySnapshot()
        }
    }

    fun cancelActiveRender() {
        cancelFlag.set(true)
        activeRenderJob?.cancel()
    }

    fun clearNotices() {
        _uiError.value = null
        _statusMessage.value = null
        previewPlayer.clearError()
    }

    fun refreshMemorySnapshot() {
        _memorySnapshot.value = MemoryProfiler.captureSnapshot(getApplication())
    }

    private fun pushUndoSnapshot() {
        history.recordForUndo(EditorHistorySnapshot(_clips.value, _selectedClipId.value))
        syncHistoryFlags()
    }

    private fun syncHistoryFlags() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    suspend fun runSafeTempCleanup() {
        val appCtx = getApplication<Application>()
        try {
            val dbUris = db.phase5Dao().getAllEditorClipSourceUris()
            val activeUris = _clips.value.map { it.sourceUri }
            val historyUris = history.allSnapshots().flatMap { snap -> snap.clips.map { it.sourceUri } }
            val protectedUris = (dbUris + activeUris + historyUris).toSet()
            SafeTempStorageCleanup.cleanUnusedEditorMergedFiles(appCtx, protectedUris)
            SafeTempStorageCleanup.cleanAbandonedTempRenderFiles(appCtx)
        } catch (_: Throwable) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeRenderJob?.cancel()
        previewPlayer.release()
        try {
            val appCtx = getApplication<Application>()
            File(appCtx.cacheDir, "editor_preview_active.wav").delete()
            SafeTempStorageCleanup.cleanAbandonedTempRenderFiles(appCtx)
        } catch (_: Throwable) {
        }
    }
}
