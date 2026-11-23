package com.osei.aimusicstudio.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MixerRepository(private val mixerDao: MixerDao) {
    val allProjectsWithTracks: Flow<List<MixerProjectWithTracks>> =
        mixerDao.getAllMixerProjectsWithTracks().map { list ->
            list.map { item ->
                item.copy(tracks = item.tracks.sortedWith(compareBy({ it.sortOrder }, { it.id })))
            }
        }

    suspend fun getProjectWithTracks(projectId: Long): MixerProjectWithTracks? {
        val raw = mixerDao.getMixerProjectWithTracks(projectId) ?: return null
        return raw.copy(tracks = raw.tracks.sortedWith(compareBy({ it.sortOrder }, { it.id })))
    }

    suspend fun createDefaultMixerProject(
        name: String = "Untitled Mix",
        linkedMusicProjectId: Long? = null,
        initialTracks: List<MixerTrackEntity>? = null
    ): Long {
        val now = System.currentTimeMillis()
        val projectId = mixerDao.insertMixerProject(
            MixerProjectEntity(
                name = name,
                masterVolume = 0.85f,
                linkedMusicProjectId = linkedMusicProjectId,
                createdAt = now,
                updatedAt = now
            )
        )
        val tracksToInsert = initialTracks?.mapIndexed { idx, t ->
            t.copy(id = 0L, mixerProjectId = projectId, sortOrder = idx)
        } ?: listOf(
            MixerTrackEntity(mixerProjectId = projectId, name = "Drums", role = TrackRole.DRUMS.name, sortOrder = 0),
            MixerTrackEntity(mixerProjectId = projectId, name = "Bass", role = TrackRole.BASS.name, sortOrder = 1),
            MixerTrackEntity(mixerProjectId = projectId, name = "Guitar", role = TrackRole.GUITAR.name, sortOrder = 2),
            MixerTrackEntity(mixerProjectId = projectId, name = "Strings", role = TrackRole.STRINGS.name, sortOrder = 3),
            MixerTrackEntity(mixerProjectId = projectId, name = "Keys", role = TrackRole.KEYS.name, sortOrder = 4),
            MixerTrackEntity(mixerProjectId = projectId, name = "Other", role = TrackRole.OTHER.name, sortOrder = 5)
        )
        mixerDao.insertTracks(tracksToInsert)
        return projectId
    }

    suspend fun updateProject(project: MixerProjectEntity) {
        mixerDao.updateMixerProject(project.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProject(projectId: Long) {
        mixerDao.deleteMixerProjectById(projectId)
    }

    suspend fun addTrack(track: MixerTrackEntity): Long {
        val id = mixerDao.insertTrack(track)
        touchProject(track.mixerProjectId)
        return id
    }

    suspend fun updateTrack(track: MixerTrackEntity) {
        mixerDao.updateTrack(track)
        touchProject(track.mixerProjectId)
    }

    suspend fun deleteTrack(track: MixerTrackEntity) {
        mixerDao.deleteTrackById(track.id)
        touchProject(track.mixerProjectId)
    }

    private suspend fun touchProject(projectId: Long) {
        val existing = mixerDao.getMixerProjectWithTracks(projectId)?.project ?: return
        mixerDao.updateMixerProject(existing.copy(updatedAt = System.currentTimeMillis()))
    }
}
