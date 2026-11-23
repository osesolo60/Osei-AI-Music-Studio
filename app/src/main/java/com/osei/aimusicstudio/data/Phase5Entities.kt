package com.osei.aimusicstudio.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.osei.aimusicstudio.audio.AudioEffectsConfig
import com.osei.aimusicstudio.audio.TimelineClipSpec
import com.osei.aimusicstudio.audio.VocalTransformConfig

@Entity(tableName = "editor_projects")
data class EditorProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "editor_clips",
    foreignKeys = [
        ForeignKey(
            entity = EditorProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["editorProjectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["editorProjectId"])]
)
data class EditorClipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val editorProjectId: Long,
    val clipUid: Long,
    val name: String,
    val sourceUri: String,
    val totalSourceDurationMs: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val eqLowGainDb: Float = 0f,
    val eqMidGainDb: Float = 0f,
    val eqHighGainDb: Float = 0f,
    val bassBoostDb: Float = 0f,
    val reverbMix: Float = 0f,
    val delayMix: Float = 0f,
    val delayTimeMs: Int = 240,
    val delayFeedback: Float = 0.35f,
    val compressorThresholdDb: Float = 0f,
    val compressorRatio: Float = 1f,
    val noiseGateThresholdDb: Float = -70f,
    val volume: Float = 1.0f,
    val balance: Float = 0.0f,
    val pitchSemitones: Float = 0f,
    val tempoRatio: Float = 1.0f,
    val preservePitchOnTempoChange: Boolean = true,
    val sortOrder: Int = 0
)

data class EditorProjectWithClips(
    @Embedded val project: EditorProjectEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "editorProjectId"
    )
    val rawClips: List<EditorClipEntity>
) {
    val clips: List<EditorClipEntity>
        get() = rawClips.sortedWith(compareBy<EditorClipEntity> { it.timelineStartMs }.thenBy { it.sortOrder })
}

fun EditorClipEntity.toTimelineSpec(): TimelineClipSpec = TimelineClipSpec(
    clipId = clipUid,
    name = name,
    sourceUri = sourceUri,
    totalSourceDurationMs = totalSourceDurationMs,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    timelineStartMs = timelineStartMs,
    effectsConfig = AudioEffectsConfig(
        eqLowGainDb = eqLowGainDb,
        eqMidGainDb = eqMidGainDb,
        eqHighGainDb = eqHighGainDb,
        bassBoostDb = bassBoostDb,
        reverbMix = reverbMix,
        delayMix = delayMix,
        delayTimeMs = delayTimeMs,
        delayFeedback = delayFeedback,
        compressorThresholdDb = compressorThresholdDb,
        compressorRatio = compressorRatio,
        noiseGateThresholdDb = noiseGateThresholdDb,
        volume = volume,
        balance = balance
    ),
    vocalConfig = VocalTransformConfig(
        pitchSemitones = pitchSemitones,
        tempoRatio = tempoRatio,
        preservePitchOnTempoChange = preservePitchOnTempoChange
    )
)

fun TimelineClipSpec.toEditorClipEntity(editorProjectId: Long, sortOrder: Int): EditorClipEntity = EditorClipEntity(
    editorProjectId = editorProjectId,
    clipUid = clipId,
    name = name,
    sourceUri = sourceUri,
    totalSourceDurationMs = totalSourceDurationMs,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    timelineStartMs = timelineStartMs,
    eqLowGainDb = effectsConfig.eqLowGainDb,
    eqMidGainDb = effectsConfig.eqMidGainDb,
    eqHighGainDb = effectsConfig.eqHighGainDb,
    bassBoostDb = effectsConfig.bassBoostDb,
    reverbMix = effectsConfig.reverbMix,
    delayMix = effectsConfig.delayMix,
    delayTimeMs = effectsConfig.delayTimeMs,
    delayFeedback = effectsConfig.delayFeedback,
    compressorThresholdDb = effectsConfig.compressorThresholdDb,
    compressorRatio = effectsConfig.compressorRatio,
    noiseGateThresholdDb = effectsConfig.noiseGateThresholdDb,
    volume = effectsConfig.volume,
    balance = effectsConfig.balance,
    pitchSemitones = vocalConfig.pitchSemitones,
    tempoRatio = vocalConfig.tempoRatio,
    preservePitchOnTempoChange = vocalConfig.preservePitchOnTempoChange,
    sortOrder = sortOrder
)

@Entity(tableName = "karaoke_projects")
data class KaraokeProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val instrumentalUri: String? = null,
    val instrumentalName: String = "",
    val instrumentalDurationMs: Long = 0L,
    val vocalReferenceUri: String? = null,
    val vocalReferenceName: String = "",
    val recordedTakeUri: String? = null,
    val recordedTakeName: String = "",
    val recordedTakeOffsetMs: Long = 0L,
    val instrumentalVolume: Float = 0.85f,
    val vocalReferenceVolume: Float = 0.35f,
    val recordedTakeVolume: Float = 1.0f,
    val rawLrcText: String = "",
    val lyricsOffsetMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
