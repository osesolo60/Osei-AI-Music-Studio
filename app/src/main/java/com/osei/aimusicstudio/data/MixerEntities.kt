package com.osei.aimusicstudio.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class TrackRole(val defaultLabel: String) {
    DRUMS("Drums"),
    BASS("Bass"),
    GUITAR("Guitar"),
    STRINGS("Strings"),
    KEYS("Keys"),
    OTHER("Other"),
    VOCALS("Vocals"),
    CUSTOM("Custom Track")
}

@Entity(tableName = "mixer_projects")
data class MixerProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val masterVolume: Float = 0.85f,
    val linkedMusicProjectId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "mixer_tracks",
    foreignKeys = [
        ForeignKey(
            entity = MixerProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["mixerProjectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["mixerProjectId"])]
)
data class MixerTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val mixerProjectId: Long,
    val name: String,
    val role: String = TrackRole.CUSTOM.name,
    val audioUri: String? = null,
    val displayFileName: String = "No audio loaded",
    val durationMs: Long = 0L,
    val volume: Float = 0.85f,
    val pan: Float = 0.0f,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val isEnabled: Boolean = true,
    val offsetMs: Long = 0L,
    val sortOrder: Int = 0
)

data class MixerProjectWithTracks(
    @Embedded val project: MixerProjectEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "mixerProjectId"
    )
    val tracks: List<MixerTrackEntity>
)
