package com.osei.aimusicstudio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface Phase5Dao {
    // --- Audio Editor ---
    @Transaction
    @Query("SELECT * FROM editor_projects ORDER BY updatedAt DESC")
    fun getAllEditorProjectsWithClips(): Flow<List<EditorProjectWithClips>>

    @Transaction
    @Query("SELECT * FROM editor_projects WHERE id = :projectId LIMIT 1")
    suspend fun getEditorProjectWithClips(projectId: Long): EditorProjectWithClips?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEditorProject(project: EditorProjectEntity): Long

    @Update
    suspend fun updateEditorProject(project: EditorProjectEntity)

    @Query("DELETE FROM editor_projects WHERE id = :projectId")
    suspend fun deleteEditorProjectById(projectId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEditorClips(clips: List<EditorClipEntity>)

    @Query("DELETE FROM editor_clips WHERE editorProjectId = :projectId")
    suspend fun deleteClipsForEditorProject(projectId: Long)

    @Transaction
    suspend fun saveProjectAndClips(project: EditorProjectEntity, clips: List<EditorClipEntity>): Long {
        val projectId = if (project.id == 0L) {
            insertEditorProject(project)
        } else {
            updateEditorProject(project.copy(updatedAt = System.currentTimeMillis()))
            project.id
        }
        deleteClipsForEditorProject(projectId)
        if (clips.isNotEmpty()) {
            insertEditorClips(clips.map { it.copy(editorProjectId = projectId) })
        }
        return projectId
    }

    @Query("SELECT sourceUri FROM editor_clips")
    suspend fun getAllEditorClipSourceUris(): List<String>

    // --- Karaoke ---
    @Query("SELECT * FROM karaoke_projects ORDER BY updatedAt DESC")
    fun getAllKaraokeProjects(): Flow<List<KaraokeProjectEntity>>

    @Query("SELECT * FROM karaoke_projects WHERE id = :projectId LIMIT 1")
    suspend fun getKaraokeProjectById(projectId: Long): KaraokeProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKaraokeProject(project: KaraokeProjectEntity): Long

    @Update
    suspend fun updateKaraokeProject(project: KaraokeProjectEntity)

    @Query("DELETE FROM karaoke_projects WHERE id = :projectId")
    suspend fun deleteKaraokeProjectById(projectId: Long)
}
