package com.osei.aimusicstudio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MusicProjectEntity::class,
        MixerProjectEntity::class,
        MixerTrackEntity::class,
        EditorProjectEntity::class,
        EditorClipEntity::class,
        KaraokeProjectEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun mixerDao(): MixerDao
    abstract fun phase5Dao(): Phase5Dao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mixer_projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `masterVolume` REAL NOT NULL,
                        `linkedMusicProjectId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mixer_tracks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `mixerProjectId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `audioUri` TEXT,
                        `displayFileName` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `volume` REAL NOT NULL,
                        `pan` REAL NOT NULL,
                        `isMuted` INTEGER NOT NULL,
                        `isSolo` INTEGER NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `offsetMs` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        FOREIGN KEY(`mixerProjectId`) REFERENCES `mixer_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mixer_tracks_mixerProjectId` ON `mixer_tracks` (`mixerProjectId`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `music_projects` ADD COLUMN `guitarUri` TEXT")
                db.execSQL("ALTER TABLE `music_projects` ADD COLUMN `stringsUri` TEXT")
                db.execSQL("ALTER TABLE `music_projects` ADD COLUMN `keysUri` TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `editor_projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `editor_clips` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `editorProjectId` INTEGER NOT NULL,
                        `clipUid` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `sourceUri` TEXT NOT NULL,
                        `totalSourceDurationMs` INTEGER NOT NULL,
                        `sourceStartMs` INTEGER NOT NULL,
                        `sourceEndMs` INTEGER NOT NULL,
                        `timelineStartMs` INTEGER NOT NULL,
                        `eqLowGainDb` REAL NOT NULL,
                        `eqMidGainDb` REAL NOT NULL,
                        `eqHighGainDb` REAL NOT NULL,
                        `bassBoostDb` REAL NOT NULL,
                        `reverbMix` REAL NOT NULL,
                        `delayMix` REAL NOT NULL,
                        `delayTimeMs` INTEGER NOT NULL,
                        `delayFeedback` REAL NOT NULL,
                        `compressorThresholdDb` REAL NOT NULL,
                        `compressorRatio` REAL NOT NULL,
                        `noiseGateThresholdDb` REAL NOT NULL,
                        `volume` REAL NOT NULL,
                        `balance` REAL NOT NULL,
                        `pitchSemitones` REAL NOT NULL,
                        `tempoRatio` REAL NOT NULL,
                        `preservePitchOnTempoChange` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        FOREIGN KEY(`editorProjectId`) REFERENCES `editor_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_editor_clips_editorProjectId` ON `editor_clips` (`editorProjectId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `karaoke_projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `instrumentalUri` TEXT,
                        `instrumentalName` TEXT NOT NULL,
                        `instrumentalDurationMs` INTEGER NOT NULL,
                        `vocalReferenceUri` TEXT,
                        `vocalReferenceName` TEXT NOT NULL,
                        `recordedTakeUri` TEXT,
                        `recordedTakeName` TEXT NOT NULL,
                        `instrumentalVolume` REAL NOT NULL,
                        `vocalReferenceVolume` REAL NOT NULL,
                        `recordedTakeVolume` REAL NOT NULL,
                        `rawLrcText` TEXT NOT NULL,
                        `lyricsOffsetMs` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `karaoke_projects` ADD COLUMN `recordedTakeOffsetMs` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "osei_music_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
