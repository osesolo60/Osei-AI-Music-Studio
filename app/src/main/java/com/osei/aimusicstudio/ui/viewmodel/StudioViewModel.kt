package com.osei.aimusicstudio.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.osei.aimusicstudio.ai.SeparationModelCatalog
import com.osei.aimusicstudio.ai.StemSeparatorEngine
import com.osei.aimusicstudio.audio.AudioPlayerManager
import com.osei.aimusicstudio.audio.WaveformData
import com.osei.aimusicstudio.audio.WaveformGenerator
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.data.MusicRepository
import kotlinx.coroutines.Dispatchers
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

enum class ProjectSortOrder {
    DATE_DESC,
    NAME_ASC
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: MusicRepository
    val audioPlayerManager = AudioPlayerManager(application)
    val stemSeparatorEngine = StemSeparatorEngine(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(ProjectSortOrder.DATE_DESC)
    val sortOrder: StateFlow<ProjectSortOrder> = _sortOrder.asStateFlow()

    val projects: StateFlow<List<MusicProjectEntity>>

    private val _currentPlayingProject = MutableStateFlow<MusicProjectEntity?>(null)
    val currentPlayingProject: StateFlow<MusicProjectEntity?> = _currentPlayingProject.asStateFlow()

    private val _waveformData = MutableStateFlow<WaveformData?>(null)
    val waveformData: StateFlow<WaveformData?> = _waveformData.asStateFlow()

    private val _waveformError = MutableStateFlow<String?>(null)
    val waveformError: StateFlow<String?> = _waveformError.asStateFlow()

    private val _isWaveformLoading = MutableStateFlow(false)
    val isWaveformLoading: StateFlow<Boolean> = _isWaveformLoading.asStateFlow()

    private val _selectedProjectForTools = MutableStateFlow<MusicProjectEntity?>(null)
    val selectedProjectForTools: StateFlow<MusicProjectEntity?> = _selectedProjectForTools.asStateFlow()

    init {
        val musicDao = MusicDatabase.getDatabase(application).musicDao()
        repository = MusicRepository(musicDao)
        projects = combine(
            repository.allProjects,
            _searchQuery,
            _sortOrder
        ) { list, query, sort ->
            val filtered = if (query.isBlank()) {
                list
            } else {
                list.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true)
                }
            }
            when (sort) {
                ProjectSortOrder.DATE_DESC -> filtered.sortedByDescending { it.createdAt }
                ProjectSortOrder.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun insertProject(project: MusicProjectEntity, onInserted: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.insertProject(project)
            val saved = repository.getProjectById(id)
            if (saved != null) {
                _selectedProjectForTools.value = saved
            }
            onInserted(id)
        }
    }

    fun updateProject(project: MusicProjectEntity) {
        viewModelScope.launch {
            repository.updateProject(project)
            if (_currentPlayingProject.value?.id == project.id) {
                _currentPlayingProject.value = project
            }
            if (_selectedProjectForTools.value?.id == project.id) {
                _selectedProjectForTools.value = project
            }
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch {
            repository.deleteProject(id)
            if (_currentPlayingProject.value?.id == id) {
                audioPlayerManager.stop()
                _currentPlayingProject.value = null
                _waveformData.value = null
            }
            if (_selectedProjectForTools.value?.id == id) {
                _selectedProjectForTools.value = null
            }
        }
    }

    fun selectProjectForTools(project: MusicProjectEntity) {
        _selectedProjectForTools.value = project
    }

    fun playProject(project: MusicProjectEntity) {
        _currentPlayingProject.value = project
        _selectedProjectForTools.value = project
        audioPlayerManager.loadAndPlay(project.audioUri)
        loadWaveformForUri(project.audioUri)
    }

    fun loadWaveformForUri(uriString: String) {
        viewModelScope.launch {
            _isWaveformLoading.value = true
            _waveformError.value = null
            val context = getApplication<Application>().applicationContext
            val result = WaveformGenerator.generateWaveform(context, uriString, barsCount = 64)
            result.onSuccess { data ->
                _waveformData.value = data
            }.onFailure { err ->
                _waveformData.value = null
                _waveformError.value = err.localizedMessage ?: "Failed to decode waveform"
            }
            _isWaveformLoading.value = false
        }
    }

    private fun resolveValidStemFileFromUri(uriStr: String?): File? {
        if (uriStr.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val file = when {
            parsed.scheme == "file" && parsed.path != null -> File(parsed.path!!)
            uriStr.startsWith("/") -> File(uriStr)
            else -> null
        } ?: return null
        val stats = com.osei.aimusicstudio.ai.StemSeparatorEngine.validateStemWavFile(file).getOrNull()
        return if (stats != null && stats.hasNonZeroAudio) file else null
    }

    fun saveSeparatedStemsToProject(
        project: MusicProjectEntity,
        stemFiles: Map<String, File>,
        onResult: (Result<MusicProjectEntity>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val validationRes = withContext(Dispatchers.IO) {
                val vocalsFile = stemFiles["vocals"]
                val instrumentalFile = stemFiles["instrumental"]
                if (vocalsFile != null || instrumentalFile != null) {
                    val vStats = vocalsFile?.let {
                        com.osei.aimusicstudio.ai.StemSeparatorEngine.validateStemWavFile(it).getOrNull()
                    }
                    val iStats = instrumentalFile?.let {
                        com.osei.aimusicstudio.ai.StemSeparatorEngine.validateStemWavFile(it).getOrNull()
                    }
                    val vValid = vStats != null && vStats.hasNonZeroAudio
                    val iValid = iStats != null && iStats.hasNonZeroAudio
                    if (!vValid || !iValid) {
                        return@withContext Result.failure<Boolean>(
                            IllegalStateException("Both vocals and instrumental WAV outputs must pass non-empty audio validation before marking stemsSeparated=true")
                        )
                    }
                    Result.success(true)
                } else {
                    val allValid = stemFiles.isNotEmpty() && stemFiles.values.all {
                        val stats = com.osei.aimusicstudio.ai.StemSeparatorEngine.validateStemWavFile(it).getOrNull()
                        stats != null && stats.hasNonZeroAudio
                    }
                    if (!allValid) {
                        return@withContext Result.failure<Boolean>(
                            IllegalStateException("All separated stem WAV outputs must pass non-empty audio validation")
                        )
                    }
                    Result.success(true)
                }
            }

            validationRes.onSuccess {
                val updated = project.copy(
                    stemsSeparated = true,
                    vocalUri = stemFiles["vocals"]?.let { Uri.fromFile(it).toString() } ?: project.vocalUri,
                    instrumentalUri = stemFiles["instrumental"]?.let { Uri.fromFile(it).toString() } ?: project.instrumentalUri,
                    drumsUri = stemFiles["drums"]?.let { Uri.fromFile(it).toString() } ?: project.drumsUri,
                    bassUri = stemFiles["bass"]?.let { Uri.fromFile(it).toString() } ?: project.bassUri,
                    guitarUri = stemFiles["guitar"]?.let { Uri.fromFile(it).toString() } ?: project.guitarUri,
                    stringsUri = stemFiles["strings"]?.let { Uri.fromFile(it).toString() } ?: project.stringsUri,
                    keysUri = stemFiles["keys"]?.let { Uri.fromFile(it).toString() } ?: project.keysUri,
                    otherUri = stemFiles["other"]?.let { Uri.fromFile(it).toString() } ?: project.otherUri
                )
                repository.updateProject(updated)
                _selectedProjectForTools.value = updated
                if (_currentPlayingProject.value?.id == updated.id) {
                    _currentPlayingProject.value = updated
                }
                onResult(Result.success(updated))
            }.onFailure { err ->
                onResult(Result.failure(err))
            }
        }
    }

    fun importAudioUriAsProject(uri: Uri, onResult: (Result<MusicProjectEntity>) -> Unit) {
        viewModelScope.launch {
            val appContext = getApplication<Application>().applicationContext
            val validation = withContext(Dispatchers.IO) {
                com.osei.aimusicstudio.audio.AudioValidator.inspectAndValidate(appContext, uri)
            }
            validation.onSuccess { meta ->
                val project = MusicProjectEntity(
                    title = meta.title.ifBlank { meta.displayName },
                    artist = meta.artist.ifBlank { "Local Audio" },
                    audioUri = uri.toString(),
                    durationMs = meta.durationMs,
                    fileSizeMb = meta.fileSizeBytes / (1024f * 1024f)
                )
                val insertedId = repository.insertProject(project)
                val saved = repository.getProjectById(insertedId) ?: project.copy(id = insertedId)
                _selectedProjectForTools.value = saved
                onResult(Result.success(saved))
            }.onFailure { err ->
                onResult(Result.failure(err))
            }
        }
    }

    fun saveSeparatedStemPermanently(
        project: MusicProjectEntity,
        stemKey: String,
        sourceWavFile: File,
        onResult: (Result<MusicProjectEntity>) -> Unit
    ) {
        viewModelScope.launch {
            val copyRes = withContext(Dispatchers.IO) {
                try {
                    val stats = com.osei.aimusicstudio.ai.StemSeparatorEngine.validateStemWavFile(sourceWavFile)
                        .getOrElse { err ->
                            return@withContext Result.failure<Pair<File, Boolean>>(
                                IllegalStateException("Cannot save $stemKey: ${err.localizedMessage}")
                            )
                        }
                    if (!stats.hasNonZeroAudio) {
                        return@withContext Result.failure(
                            IllegalStateException("Cannot save $stemKey: separated WAV has zero audio amplitude")
                        )
                    }
                    val savedDir = File(getApplication<Application>().filesDir, "saved_stems").apply { mkdirs() }
                    val safeTitle = project.title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifEmpty { "song" }
                    val destFile = File(savedDir, "${safeTitle}_${project.id}_${stemKey}_${System.currentTimeMillis()}.wav")
                    sourceWavFile.copyTo(destFile, overwrite = true)

                    val savedUri = Uri.fromFile(destFile).toString()
                    val candidateVocalUri = if (stemKey == "vocals") savedUri else project.vocalUri
                    val candidateInstUri = if (stemKey == "instrumental") savedUri else project.instrumentalUri
                    val bothValidated = resolveValidStemFileFromUri(candidateVocalUri) != null &&
                        resolveValidStemFileFromUri(candidateInstUri) != null

                    Result.success(destFile to bothValidated)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            copyRes.onSuccess { (savedFile, bothValidated) ->
                val savedUri = Uri.fromFile(savedFile).toString()
                val updated = when (stemKey) {
                    "vocals" -> project.copy(stemsSeparated = bothValidated, vocalUri = savedUri)
                    "instrumental" -> project.copy(stemsSeparated = bothValidated, instrumentalUri = savedUri)
                    else -> project.copy(stemsSeparated = bothValidated)
                }
                repository.updateProject(updated)
                _selectedProjectForTools.value = updated
                if (_currentPlayingProject.value?.id == updated.id) {
                    _currentPlayingProject.value = updated
                }
                onResult(Result.success(updated))
            }.onFailure { err ->
                onResult(Result.failure(err))
            }
        }
    }

    fun prepareShareIntentForWav(
        sourceWavFile: File,
        displayTitle: String,
        onResult: (Result<android.content.Intent>) -> Unit
    ) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val res = withContext(Dispatchers.IO) {
                try {
                    if (!sourceWavFile.exists() || sourceWavFile.length() <= 44L) {
                        return@withContext Result.failure(IllegalStateException("Separated WAV file is missing"))
                    }
                    val sharedFile = com.osei.aimusicstudio.audio.AudioShareHelper.prepareShareFile(
                        context = app,
                        sourceFile = sourceWavFile,
                        shareDisplayName = displayTitle
                    )
                    val intent = com.osei.aimusicstudio.audio.AudioShareHelper.buildShareIntent(
                        context = app,
                        wavFile = sharedFile,
                        chooserTitle = "Share $displayTitle"
                    )
                    Result.success(intent)
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }
            onResult(res)
        }
    }

    fun saveSongAnalysisResults(
        project: MusicProjectEntity,
        bpm: Int?,
        musicalKey: String?,
        onResult: (Result<MusicProjectEntity>) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val updated = project.copy(
                    bpm = bpm ?: 0,
                    musicalKey = musicalKey?.trim().orEmpty()
                )
                repository.updateProject(updated)
                _selectedProjectForTools.value = updated
                if (_currentPlayingProject.value?.id == updated.id) {
                    _currentPlayingProject.value = updated
                }
                onResult(Result.success(updated))
            } catch (t: Throwable) {
                onResult(Result.failure(t))
            }
        }
    }

    suspend fun exportStemToUri(sourceFile: File, destUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            resolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open destination file for export"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun togglePlayPause() {
        audioPlayerManager.togglePlayPause()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: ProjectSortOrder) {
        _sortOrder.value = order
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayerManager.release()
    }
}
