package com.osei.aimusicstudio.data

import kotlinx.coroutines.flow.Flow

class MusicRepository(private val musicDao: MusicDao) {
    val allProjects: Flow<List<MusicProjectEntity>> = musicDao.getAllProjects()

    suspend fun getProjectById(id: Long): MusicProjectEntity? {
        return musicDao.getProjectById(id)
    }

    suspend fun insertProject(project: MusicProjectEntity): Long {
        return musicDao.insertProject(project)
    }

    suspend fun updateProject(project: MusicProjectEntity) {
        musicDao.updateProject(project)
    }

    suspend fun deleteProject(id: Long) {
        musicDao.deleteProjectById(id)
    }
}
