package com.osei.aimusicstudio

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.osei.aimusicstudio.audio.AudioEffectsConfig
import com.osei.aimusicstudio.audio.AudioEffectsRenderer
import com.osei.aimusicstudio.audio.EditorHistorySnapshot
import com.osei.aimusicstudio.audio.ExportQualityPreset
import com.osei.aimusicstudio.audio.LrcParser
import com.osei.aimusicstudio.audio.MixerTrackMixSpec
import com.osei.aimusicstudio.audio.MultitrackWavExporter
import com.osei.aimusicstudio.audio.PcmWavSeekReader
import com.osei.aimusicstudio.audio.SchroederStereoReverb
import com.osei.aimusicstudio.audio.StereoDelayLine
import com.osei.aimusicstudio.audio.TimelineAudioRenderer
import com.osei.aimusicstudio.audio.TimelineClipOperations
import com.osei.aimusicstudio.audio.TimelineClipSpec
import com.osei.aimusicstudio.audio.TimelineHistoryManager
import com.osei.aimusicstudio.audio.VocalTransformConfig
import com.osei.aimusicstudio.audio.WavStreamReader
import com.osei.aimusicstudio.audio.WavStreamWriter
import com.osei.aimusicstudio.data.KaraokeProjectEntity
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.Phase5Repository
import com.osei.aimusicstudio.data.toTimelineSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase5StudioRobolectricTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "phase5_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createSineWav(
        fileName: String,
        freqHz: Double,
        amplitude: Float,
        durationSec: Float,
        sampleRate: Int = 44100
    ): File {
        val file = File(tempDir, fileName)
        val totalFrames = (durationSec * sampleRate).toInt()
        val buf = FloatArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            val s = (amplitude * sin(2.0 * PI * freqHz * i.toDouble() / sampleRate.toDouble())).toFloat()
            buf[i * 2] = s
            buf[i * 2 + 1] = s
        }
        WavStreamWriter(file, sampleRate, 2).use { writer ->
            writer.writeFloatSamples(buf, buf.size)
        }
        return file
    }

    private fun readMonoLeftSamples(wavFile: File): Pair<Int, FloatArray> {
        val header = WavStreamReader.tryReadHeader(wavFile)!!
        val bytesPerFrame = header.channels * (header.bitsPerSample / 8)
        val totalFrames = (header.dataSizeBytes / bytesPerFrame).toInt()
        val out = FloatArray(totalFrames)
        if (header.sampleRate == 44100) {
            PcmWavSeekReader(wavFile, header).use { reader ->
                val stereo = FloatArray(totalFrames * 2)
                reader.accumulateIntoMix(0L, 0L, totalFrames, 1f, 1f, stereo)
                for (i in 0 until totalFrames) out[i] = stereo[i * 2]
            }
        }
        return header.sampleRate to out
    }

    private fun rms(samples: FloatArray, startIdx: Int = 0, endIdx: Int = samples.size): Double {
        val s = startIdx.coerceIn(0, samples.size)
        val e = endIdx.coerceIn(s, samples.size)
        if (e <= s) return 0.0
        var sumSq = 0.0
        for (i in s until e) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / (e - s))
    }

    private fun estimateFrequencyViaZeroCrossings(samples: FloatArray, sampleRate: Int): Double {
        val start = (samples.size * 0.2).toInt()
        val end = (samples.size * 0.8).toInt()
        var crossings = 0
        for (i in (start + 1) until end) {
            if (samples[i - 1] < 0f && samples[i] >= 0f) {
                crossings++
            }
        }
        val windowSec = (end - start).toDouble() / sampleRate.toDouble()
        return if (windowSec > 0.0) crossings / windowSec else 0.0
    }

    @Test
    fun testAudioTrimmingProducesExpectedDuration() = runBlocking {
        val srcWav = createSineWav("src_2s.wav", 440.0, 0.5f, 2.0f)
        val clip = TimelineClipSpec(
            clipId = 10L,
            name = "TrimTest",
            sourceUri = Uri.fromFile(srcWav).toString(),
            totalSourceDurationMs = 2000L,
            sourceStartMs = 0L,
            sourceEndMs = 2000L
        )
        val trimmed = TimelineClipOperations.trimClip(listOf(clip), 10L, 500L, 1500L).first()
        assertEquals(1000L, trimmed.sourceTrimmedDurationMs)

        val outWav = File(tempDir, "trimmed_out.wav")
        val res = TimelineAudioRenderer.renderSingleClipToFile(context, trimmed, outWav)
        assertTrue(res.isSuccess)

        val hdr = WavStreamReader.tryReadHeader(outWav)!!
        val durationMs = (hdr.dataSizeBytes * 1000L) / hdr.byteRate
        assertTrue("Expected ~1000ms, got $durationMs", abs(durationMs - 1000L) <= 25L)
    }

    @Test
    fun testSplitMoveAndSequentialMergeClips() = runBlocking {
        val srcWav = createSineWav("split_src.wav", 330.0, 0.4f, 2.0f)
        val clip = TimelineClipSpec(
            clipId = 1L,
            name = "TrackA",
            sourceUri = Uri.fromFile(srcWav).toString(),
            totalSourceDurationMs = 2000L,
            sourceStartMs = 0L,
            sourceEndMs = 2000L,
            timelineStartMs = 0L
        )

        val splitRes = TimelineClipOperations.splitClipAtTimelineMs(
            clips = listOf(clip),
            clipId = 1L,
            splitTimelineMs = 800L,
            secondHalfClipId = 2L
        )
        assertTrue(splitRes.isSuccess)
        val parts = splitRes.getOrThrow()
        assertEquals(2, parts.size)
        assertEquals(800L, parts[0].sourceTrimmedDurationMs)
        assertEquals(1200L, parts[1].sourceTrimmedDurationMs)

        val moved = TimelineClipOperations.moveClipTimelineStart(parts, 2L, 1500L)
        assertEquals(1500L, moved.first { it.clipId == 2L }.timelineStartMs)

        val mergedFile = File(tempDir, "merged_seq.wav")
        val mergeRes = TimelineAudioRenderer.mergeClipsSequentiallyToFile(context, moved, mergedFile)
        assertTrue(mergeRes.isSuccess)

        val hdr = WavStreamReader.tryReadHeader(mergedFile)!!
        val mergedDurMs = (hdr.dataSizeBytes * 1000L) / hdr.byteRate
        assertTrue("Expected ~2000ms merged WAV, got $mergedDurMs", abs(mergedDurMs - 2000L) <= 30L)
    }

    @Test
    fun testEqualizerAndBassBoostAlterBandEnergy() = runBlocking {
        val bassTone = createSineWav("bass_90hz.wav", 90.0, 0.25f, 0.8f)
        val trebleTone = createSineWav("treble_6khz.wav", 6000.0, 0.25f, 0.8f)

        val bassOutBoosted = File(tempDir, "bass_boosted.wav")
        val trebleOutCut = File(tempDir, "treble_cut.wav")

        val effects = AudioEffectsConfig(
            bassBoostDb = 10f,
            eqHighGainDb = -10f
        )

        assertTrue(
            AudioEffectsRenderer.processAudioToFile(
                context, Uri.fromFile(bassTone), bassOutBoosted, effects
            ).isSuccess
        )
        assertTrue(
            AudioEffectsRenderer.processAudioToFile(
                context, Uri.fromFile(trebleTone), trebleOutCut, effects
            ).isSuccess
        )

        val (_, bassInSamples) = readMonoLeftSamples(bassTone)
        val (_, bassOutSamples) = readMonoLeftSamples(bassOutBoosted)
        val (_, trebleInSamples) = readMonoLeftSamples(trebleTone)
        val (_, trebleOutSamples) = readMonoLeftSamples(trebleOutCut)

        assertTrue("Bass boost must increase 90 Hz RMS", rms(bassOutSamples) > rms(bassInSamples) * 1.4)
        assertTrue("High shelf cut must attenuate 6 kHz RMS", rms(trebleOutSamples) < rms(trebleInSamples) * 0.65)
    }

    @Test
    fun testReverbAndDelayProduceDelayedEchoAndTail() {
        val sampleRate = 44100
        val frames = sampleRate // 1.0 sec
        val delayBuf = FloatArray(frames * 2)
        // Impulse at t = 0.05s (frame 2205)
        delayBuf[2205 * 2] = 0.8f
        delayBuf[2205 * 2 + 1] = 0.8f

        val delay = StereoDelayLine(sampleRate)
        delay.processInPlace(delayBuf, frames, mix = 0.6f, timeMs = 250, feedback = 0.4f)

        // Expected first echo at 2205 + 11025 = 13230 frames
        val echoSample = abs(delayBuf[13230 * 2])
        assertTrue("Expected audible echo near 250ms delay, got $echoSample", echoSample > 0.25f)

        val reverbBuf = FloatArray(frames * 2)
        for (i in 1000 until 1500) {
            reverbBuf[i * 2] = 0.7f
            reverbBuf[i * 2 + 1] = 0.7f
        }
        val reverb = SchroederStereoReverb(sampleRate)
        reverb.processInPlace(reverbBuf, frames, wetMix = 0.6f)

        // Tail region well after impulse (frames 10000..25000) must contain non-zero reverb decay
        var tailEnergy = 0.0
        for (i in 10000 until 25000) {
            tailEnergy += abs(reverbBuf[i * 2].toDouble())
        }
        assertTrue("Expected reverb tail energy > 1.0, got $tailEnergy", tailEnergy > 1.0)
    }

    @Test
    fun testPitchAndTempoWithPreservePitchToggle() = runBlocking {
        val tone440 = createSineWav("tone_440.wav", 440.0, 0.5f, 1.5f)

        // 1) 2.0x Tempo WITH preservePitch = true -> duration halves, pitch remains ~440 Hz
        val fastPreserved = File(tempDir, "fast_preserved.wav")
        assertTrue(
            AudioEffectsRenderer.processAudioToFile(
                context = context,
                inputUri = Uri.fromFile(tone440),
                outputFile = fastPreserved,
                effects = AudioEffectsConfig(),
                vocalTransform = VocalTransformConfig(pitchSemitones = 0f, tempoRatio = 2.0f, preservePitchOnTempoChange = true)
            ).isSuccess
        )
        val (sr1, s1) = readMonoLeftSamples(fastPreserved)
        val dur1 = (s1.size * 1000L) / sr1
        val freq1 = estimateFrequencyViaZeroCrossings(s1, sr1)
        assertTrue("Expected ~750ms at 2x speed, got $dur1 ms", dur1 in 600L..900L)
        assertTrue("Expected preserved pitch near 440 Hz, got $freq1 Hz", abs(freq1 - 440.0) < 45.0)

        // 2) 2.0x Tempo WITH preservePitch = false (varispeed) -> duration halves, pitch doubles (~880 Hz)
        val fastVarispeed = File(tempDir, "fast_varispeed.wav")
        assertTrue(
            AudioEffectsRenderer.processAudioToFile(
                context = context,
                inputUri = Uri.fromFile(tone440),
                outputFile = fastVarispeed,
                effects = AudioEffectsConfig(),
                vocalTransform = VocalTransformConfig(pitchSemitones = 0f, tempoRatio = 2.0f, preservePitchOnTempoChange = false)
            ).isSuccess
        )
        val (sr2, s2) = readMonoLeftSamples(fastVarispeed)
        val freq2 = estimateFrequencyViaZeroCrossings(s2, sr2)
        assertTrue("Expected varispeed pitch near 880 Hz, got $freq2 Hz", abs(freq2 - 880.0) < 60.0)

        // 3) +12 semitones pitch shift at 1.0x tempo -> duration stays ~1.5s, pitch ~880 Hz
        val octaveUp = File(tempDir, "octave_up.wav")
        assertTrue(
            AudioEffectsRenderer.processAudioToFile(
                context = context,
                inputUri = Uri.fromFile(tone440),
                outputFile = octaveUp,
                effects = AudioEffectsConfig(),
                vocalTransform = VocalTransformConfig(pitchSemitones = 12f, tempoRatio = 1.0f, preservePitchOnTempoChange = true)
            ).isSuccess
        )
        val (sr3, s3) = readMonoLeftSamples(octaveUp)
        val dur3 = (s3.size * 1000L) / sr3
        val freq3 = estimateFrequencyViaZeroCrossings(s3, sr3)
        assertTrue("Expected ~1500ms at 1x tempo, got $dur3 ms", dur3 in 1300L..1700L)
        assertTrue("Expected +12st pitch near 880 Hz, got $freq3 Hz", abs(freq3 - 880.0) < 70.0)
    }

    @Test
    fun testUndoRedoRoomPersistenceAndMigration3To4() = runBlocking {
        // Test Undo/Redo stack
        val history = TimelineHistoryManager<EditorHistorySnapshot>()
        val c1 = TimelineClipSpec(1L, "Clip 1", "file:///a.wav", 1000L, 0L, 1000L, 0L)
        val c2 = TimelineClipSpec(2L, "Clip 2", "file:///b.wav", 1000L, 0L, 1000L, 1000L)

        val state0 = EditorHistorySnapshot(listOf(c1), 1L)
        history.recordForUndo(state0)
        val state1 = EditorHistorySnapshot(listOf(c1, c2), 2L)

        val undone = history.undo(state1)!!
        assertEquals(1, undone.clips.size)
        assertTrue(history.canRedo)

        val redone = history.redo(undone)!!
        assertEquals(2, redone.clips.size)
        assertEquals(2L, redone.selectedClipId)

        // Test MIGRATION_3_4 on an existing v3 SQLite schema
        val dbFile = File(tempDir, "migration_test.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `music_projects` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `title` TEXT NOT NULL,
                                `artist` TEXT NOT NULL,
                                `audioUri` TEXT NOT NULL,
                                `durationMs` INTEGER NOT NULL,
                                `fileSizeMb` REAL NOT NULL,
                                `stemsSeparated` INTEGER NOT NULL,
                                `vocalUri` TEXT,
                                `instrumentalUri` TEXT,
                                `drumsUri` TEXT,
                                `bassUri` TEXT,
                                `guitarUri` TEXT,
                                `stringsUri` TEXT,
                                `keysUri` TEXT,
                                `otherUri` TEXT,
                                `bpm` INTEGER NOT NULL,
                                `musicalKey` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
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
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()

        val migratedDb = Room.databaseBuilder(context, MusicDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                MusicDatabase.MIGRATION_1_2,
                MusicDatabase.MIGRATION_2_3,
                MusicDatabase.MIGRATION_3_4,
                MusicDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()

        try {
            val repo = Phase5Repository(migratedDb.phase5Dao())
            val projId = repo.createDefaultEditorProject("Saved Session")
            val clipWithDsp = c1.copy(
                effectsConfig = AudioEffectsConfig(eqLowGainDb = 4f, reverbMix = 0.3f),
                vocalConfig = VocalTransformConfig(pitchSemitones = 3f, tempoRatio = 1.25f)
            )
            val savedProj = repo.getEditorProject(projId)!!.project
            repo.saveEditorClips(savedProj, listOf(clipWithDsp))

            val reloaded = repo.getEditorProject(projId)!!
            assertEquals(1, reloaded.clips.size)
            val reloadedSpec = reloaded.clips.first().toTimelineSpec()
            assertEquals(4f, reloadedSpec.effectsConfig.eqLowGainDb, 0.01f)
            assertEquals(3f, reloadedSpec.vocalConfig.pitchSemitones, 0.01f)
            assertEquals(1.25f, reloadedSpec.vocalConfig.tempoRatio, 0.01f)

            val kId = repo.saveKaraokeProject(
                KaraokeProjectEntity(
                    title = "My Karaoke",
                    rawLrcText = "[00:01.50] First line",
                    lyricsOffsetMs = 250L
                )
            )
            val loadedK = repo.allKaraokeProjects.first().first { it.id == kId }
            assertEquals("[00:01.50] First line", loadedK.rawLrcText)
            assertEquals(250L, loadedK.lyricsOffsetMs)
        } finally {
            migratedDb.close()
        }
    }

    @Test
    fun testExportQualitySampleRatePresetsAndTempCleanup() = runBlocking {
        val tone = createSineWav("export_src.wav", 440.0, 0.4f, 0.5f)
        val specs = listOf(
            MixerTrackMixSpec(1L, "Track1", Uri.fromFile(tone).toString(), 500L, 1.0f, 0f, false, false, true, 0L)
        )

        for (preset in ExportQualityPreset.entries) {
            val outFile = File(tempDir, "out_${preset.sampleRateHz}.wav")
            val res = MultitrackWavExporter.exportMixToFile(
                context = context,
                tracks = specs,
                masterVolume = 1.0f,
                outputFile = outFile,
                targetSampleRateHz = preset.sampleRateHz
            )
            assertTrue("Export failed for ${preset.label}", res.isSuccess)
            val header = WavStreamReader.tryReadHeader(outFile)!!
            assertEquals(preset.sampleRateHz, header.sampleRate)
            assertEquals(2, header.channels)
            assertEquals(16, header.bitsPerSample)
            assertTrue(header.dataSizeBytes > 0L)
        }

        val leftOverTmps = tempDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("No lingering .tmp export files should remain", leftOverTmps.isEmpty())
    }

    @Test
    fun testLrcParsingAndManualTimingSync() {
        val rawLrc = """
            [ti:Test Song]
            [00:01.00] Line One
            [00:03.50] Line Two
            [00:06.00] Line Three
        """.trimIndent()

        val lines = LrcParser.parse(rawLrc)
        assertEquals(3, lines.size)
        assertEquals(1000L, lines[0].timestampMs)
        assertEquals(3500L, lines[1].timestampMs)

        assertEquals(-1, LrcParser.findActiveLyricIndex(lines, 500L))
        assertEquals(0, LrcParser.findActiveLyricIndex(lines, 1500L))
        assertEquals(1, LrcParser.findActiveLyricIndex(lines, 4000L))
        // Offset shifts lyric display earlier/later manually
        assertEquals(0, LrcParser.findActiveLyricIndex(lines, 3600L, globalOffsetMs = 500L))
    }
}
