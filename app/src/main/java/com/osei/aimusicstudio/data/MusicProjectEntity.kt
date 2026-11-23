package com.osei.aimusicstudio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "music_projects")
data class MusicProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val artist: String,
    val audioUri: String,
    val durationMs: Long = 0L,
    val fileSizeMb: Float = 0f,
    val stemsSeparated: Boolean = false,
    val vocalUri: String? = null,
    val instrumentalUri: String? = null,
    val drumsUri: String? = null,
    val bassUri: String? = null,
    val guitarUri: String? = null,
    val stringsUri: String? = null,
    val keysUri: String? = null,
    val otherUri: String? = null,
    val bpm: Int = 0,
    val musicalKey: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
