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

    fun saveSeparatedStemsToProject(project: MusicProjectEntity, stemFiles: Map<String, File>) {
        viewModelScope.launch {
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
