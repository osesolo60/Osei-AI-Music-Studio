package com.osei.aimusicstudio.data

import com.osei.aimusicstudio.audio.TimelineClipSpec
import kotlinx.coroutines.flow.Flow

class Phase5Repository(private val dao: Phase5Dao) {
    val allEditorProjects: Flow<List<EditorProjectWithClips>> = dao.getAllEditorProjectsWithClips()
    val allKaraokeProjects: Flow<List<KaraokeProjectEntity>> = dao.getAllKaraokeProjects()

    suspend fun getEditorProject(projectId: Long): EditorProjectWithClips? =
        dao.getEditorProjectWithClips(projectId)

    suspend fun createDefaultEditorProject(name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertEditorProject(EditorProjectEntity(name = name, createdAt = now, updatedAt = now))
    }

    suspend fun saveEditorClips(project: EditorProjectEntity, specs: List<TimelineClipSpec>): Long {
        val entities = specs.mapIndexed { idx, spec ->
            spec.toEditorClipEntity(editorProjectId = project.id, sortOrder = idx)
        }
        return dao.saveProjectAndClips(project, entities)
    }

    suspend fun deleteEditorProject(projectId: Long) = dao.deleteEditorProjectById(projectId)

    suspend fun createDefaultKaraokeProject(title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertKaraokeProject(KaraokeProjectEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun getKaraokeProject(projectId: Long): KaraokeProjectEntity? =
        dao.getKaraokeProjectById(projectId)

    suspend fun saveKaraokeProject(project: KaraokeProjectEntity): Long {
        return if (project.id == 0L) {
            dao.insertKaraokeProject(project)
        } else {
            val updated = project.copy(updatedAt = System.currentTimeMillis())
            dao.updateKaraokeProject(updated)
            updated.id
        }
    }

    suspend fun deleteKaraokeProject(projectId: Long) = dao.deleteKaraokeProjectById(projectId)
}
