package com.osei.aimusicstudio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Query("SELECT * FROM music_projects ORDER BY createdAt DESC")
    fun getAllProjects(): Flow<List<MusicProjectEntity>>

    @Query("SELECT * FROM music_projects WHERE id = :projectId")
    suspend fun getProjectById(projectId: Long): MusicProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: MusicProjectEntity): Long

    @Update
    suspend fun updateProject(project: MusicProjectEntity)

    @Query("DELETE FROM music_projects WHERE id = :projectId")
    suspend fun deleteProjectById(projectId: Long)
}
