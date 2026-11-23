package com.osei.aimusicstudio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MixerDao {
    @Transaction
    @Query("SELECT * FROM mixer_projects ORDER BY updatedAt DESC")
    fun getAllMixerProjectsWithTracks(): Flow<List<MixerProjectWithTracks>>

    @Transaction
    @Query("SELECT * FROM mixer_projects WHERE id = :projectId")
    suspend fun getMixerProjectWithTracks(projectId: Long): MixerProjectWithTracks?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMixerProject(project: MixerProjectEntity): Long

    @Update
    suspend fun updateMixerProject(project: MixerProjectEntity)

    @Query("DELETE FROM mixer_projects WHERE id = :projectId")
    suspend fun deleteMixerProjectById(projectId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: MixerTrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<MixerTrackEntity>)

    @Update
    suspend fun updateTrack(track: MixerTrackEntity)

    @Query("DELETE FROM mixer_tracks WHERE id = :trackId")
    suspend fun deleteTrackById(trackId: Long)

    @Query("SELECT * FROM mixer_tracks WHERE mixerProjectId = :projectId ORDER BY sortOrder ASC, id ASC")
    suspend fun getTracksForProject(projectId: Long): List<MixerTrackEntity>
}
