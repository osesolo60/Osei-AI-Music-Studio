package com.osei.aimusicstudio

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.osei.aimusicstudio.audio.MixerTrackMixSpec
import com.osei.aimusicstudio.audio.MultitrackAudioEngine
import com.osei.aimusicstudio.audio.MultitrackWavExporter
import com.osei.aimusicstudio.audio.PcmWavSeekReader
import com.osei.aimusicstudio.audio.TrackGainCalculator
import com.osei.aimusicstudio.audio.WavStreamReader
import com.osei.aimusicstudio.audio.WavStreamWriter
import com.osei.aimusicstudio.data.MixerRepository
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.TrackRole
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
import kotlin.math.max
import kotlin.math.sin

/**
 * Phase 4 Robolectric tests using synthetic deterministic WAV audio fixtures
 * (distinct from unverified live-device neural stem separation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultitrackMixerRobolectricTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "mixer_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createSyntheticToneWav(
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

    private fun readAllStereoSamples(wavFile: File): FloatArray {
        val header = WavStreamReader.tryReadHeader(wavFile)!!
        PcmWavSeekReader(wavFile, header).use { reader ->
            val out = FloatArray(reader.totalFrames.toInt() * 2)
            reader.accumulateIntoMix(
                timelineFrame = 0L,
                offsetFrames = 0L,
                frameCount = reader.totalFrames.toInt(),
                leftGain = 1.0f,
                rightGain = 1.0f,
                mixInterleaved = out
            )
            return out
        }
    }

    @Test
    fun testVolumeMuteSoloPanAndMasterGainMath() {
        val t1 = MixerTrackMixSpec(1L, "Vocals", "file:///v.wav", 1000L, volume = 1.0f, pan = -1.0f, isMuted = false, isSolo = false, isEnabled = true, offsetMs = 0L)
        val t2 = MixerTrackMixSpec(2L, "Drums", "file:///d.wav", 1000L, volume = 0.5f, pan = 1.0f, isMuted = false, isSolo = false, isEnabled = true, offsetMs = 0L)
        val t3 = MixerTrackMixSpec(3L, "Bass", "file:///b.wav", 1000L, volume = 1.0f, pan = 0.0f, isMuted = true, isSolo = false, isEnabled = true, offsetMs = 0L)

        val normal = TrackGainCalculator.computeAllGains(listOf(t1, t2, t3), masterVolume = 1.0f)
        // t1 full Left
        assertEquals(1.0f, normal[1L]!!.left, 1e-4f)
        assertEquals(0.0f, normal[1L]!!.right, 1e-4f)
        // t2 full Right at 0.5 volume
        assertEquals(0.0f, normal[2L]!!.left, 1e-4f)
        assertEquals(0.5f, normal[2L]!!.right, 1e-4f)
        // t3 muted
        assertFalse(normal[3L]!!.isAudible)

        // Solo on t2 silences t1
        val soloed = TrackGainCalculator.computeAllGains(
            listOf(t1, t2.copy(isSolo = true), t3),
            masterVolume = 0.5f
        )
        assertFalse(soloed[1L]!!.isAudible)
        assertTrue(soloed[2L]!!.isAudible)
        assertEquals(0.25f, soloed[2L]!!.right, 1e-4f)

        // Disabled track produces zero gain
        val disabled = TrackGainCalculator.computeAllGains(
            listOf(t1.copy(isEnabled = false), t2),
            masterVolume = 1.0f
        )
        assertFalse(disabled[1L]!!.isAudible)
        assertTrue(disabled[2L]!!.isAudible)
    }

    @Test
    fun testExportedWavCombinesRealSignalsWithPanMuteMasterAndSyncOffset(): Unit = runBlocking {
        val vocalsWav = createSyntheticToneWav("vocals_440.wav", freqHz = 440.0, amplitude = 0.4f, durationSec = 1.0f)
        val drumsWav = createSyntheticToneWav("drums_880.wav", freqHz = 880.0, amplitude = 0.3f, durationSec = 0.5f)
        val bassWav = createSyntheticToneWav("bass_110.wav", freqHz = 110.0, amplitude = 0.5f, durationSec = 1.0f)

        val tracks = listOf(
            // Vocals panned hard Left
            MixerTrackMixSpec(1L, "Vocals", Uri.fromFile(vocalsWav).toString(), 1000L, volume = 1.0f, pan = -1.0f, isMuted = false, isSolo = false, isEnabled = true, offsetMs = 0L),
            // Drums delayed by +500 ms and panned hard Right -> total timeline = 1000ms
            MixerTrackMixSpec(2L, "Drums", Uri.fromFile(drumsWav).toString(), 500L, volume = 1.0f, pan = 1.0f, isMuted = false, isSolo = false, isEnabled = true, offsetMs = 500L),
            // Bass muted -> must NOT appear in mix
            MixerTrackMixSpec(3L, "Bass", Uri.fromFile(bassWav).toString(), 1000L, volume = 1.0f, pan = 0.0f, isMuted = true, isSolo = false, isEnabled = true, offsetMs = 0L)
        )

        val exportFull = File(tempDir, "mix_full.wav")
        val resFull = MultitrackWavExporter.exportMixToFile(context, tracks, masterVolume = 1.0f, outputFile = exportFull)
        assertTrue("Expected WAV mix export to succeed", resFull.isSuccess)
        assertTrue(exportFull.exists() && exportFull.length() > 44L)

        val header = WavStreamReader.tryReadHeader(exportFull)!!
        assertEquals(44100, header.sampleRate)
        assertEquals(2, header.channels)
        assertEquals(16, header.bitsPerSample)

        val mixedSamples = readAllStereoSamples(exportFull)
        assertEquals(44100 * 2, mixedSamples.size)

        // First half (0..450ms): Left has ~0.4 amplitude vocals, Right must be silent (Drums starts at 500ms)
        var maxLeftFirstHalf = 0f
        var maxRightFirstHalf = 0f
        for (f in 0 until 20000) {
            maxLeftFirstHalf = max(maxLeftFirstHalf, abs(mixedSamples[f * 2]))
            maxRightFirstHalf = max(maxRightFirstHalf, abs(mixedSamples[f * 2 + 1]))
        }
        assertTrue("Left channel should contain Vocals (~0.4), got $maxLeftFirstHalf", maxLeftFirstHalf in 0.35f..0.45f)
        assertTrue("Right channel should be silent before 500ms offset, got $maxRightFirstHalf", maxRightFirstHalf < 0.005f)

        // Second half (550ms..950ms): Right channel has Drums (~0.3) synced at 500ms offset
        var maxRightSecondHalf = 0f
        for (f in 24255 until 41895) {
            maxRightSecondHalf = max(maxRightSecondHalf, abs(mixedSamples[f * 2 + 1]))
        }
        assertTrue("Right channel should contain Drums (~0.3) after 500ms offset, got $maxRightSecondHalf", maxRightSecondHalf in 0.25f..0.35f)

        // Verify master volume scaling (0.25x)
        val exportQuarter = File(tempDir, "mix_quarter.wav")
        val resQuarter = MultitrackWavExporter.exportMixToFile(context, tracks, masterVolume = 0.25f, outputFile = exportQuarter)
        assertTrue(resQuarter.isSuccess)
        val quarterSamples = readAllStereoSamples(exportQuarter)
        var maxLeftQuarter = 0f
        for (f in 0 until 20000) {
            maxLeftQuarter = max(maxLeftQuarter, abs(quarterSamples[f * 2]))
        }
        assertTrue("Master 0.25x should scale Left ~0.10, got $maxLeftQuarter", maxLeftQuarter in 0.08f..0.12f)

        // Exporting when all tracks are muted must fail cleanly instead of generating silence
        val allMuted = tracks.map { it.copy(isMuted = true) }
        val exportEmpty = File(tempDir, "mix_empty.wav")
        val resMuted = MultitrackWavExporter.exportMixToFile(context, allMuted, masterVolume = 1.0f, outputFile = exportEmpty)
        assertTrue("All-muted export must fail honestly", resMuted.isFailure)
        assertFalse(exportEmpty.exists())
    }

    @Test
    fun testRoomMixerProjectPersistenceAndReopen(): Unit = runBlocking {
        val dbFile = File(tempDir, "mixer_persist.db")
        val db1 = Room.databaseBuilder(context, MusicDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                MusicDatabase.MIGRATION_1_2,
                MusicDatabase.MIGRATION_2_3,
                MusicDatabase.MIGRATION_3_4,
                MusicDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()
        val repo1 = MixerRepository(db1.mixerDao())

        val projId = repo1.createDefaultMixerProject("Afrobeat 6-Stem Remix")
        val initial = repo1.getProjectWithTracks(projId)!!
        assertEquals(6, initial.tracks.size)
        assertEquals(
            listOf("Drums", "Bass", "Guitar", "Strings", "Keys", "Other"),
            initial.tracks.map { it.name }
        )

        repo1.updateProject(initial.project.copy(masterVolume = 0.65f))
        val strings = initial.tracks.first { it.role == TrackRole.STRINGS.name }
        repo1.updateTrack(
            strings.copy(
                audioUri = "file:///tmp/strings.wav",
                displayFileName = "strings.wav",
                durationMs = 15000L,
                volume = 1.2f,
                pan = -0.5f,
                isSolo = true,
                offsetMs = 200L
            )
        )
        db1.close()

        val db2 = Room.databaseBuilder(context, MusicDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                MusicDatabase.MIGRATION_1_2,
                MusicDatabase.MIGRATION_2_3,
                MusicDatabase.MIGRATION_3_4,
                MusicDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()
        val repo2 = MixerRepository(db2.mixerDao())
        val reopened = repo2.allProjectsWithTracks.first().first { it.project.id == projId }

        assertEquals("Afrobeat 6-Stem Remix", reopened.project.name)
        assertEquals(6, reopened.tracks.size)
        assertEquals(0.65f, reopened.project.masterVolume, 1e-4f)
        val restoredStrings = reopened.tracks.first { it.role == TrackRole.STRINGS.name }
        assertEquals("strings.wav", restoredStrings.displayFileName)
        assertEquals(1.2f, restoredStrings.volume, 1e-4f)
        assertEquals(-0.5f, restoredStrings.pan, 1e-4f)
        assertTrue(restoredStrings.isSolo)
        assertEquals(200L, restoredStrings.offsetMs)
        db2.close()
    }

    @Test
    fun testIndividualTrackWavExportAndSixTrackMix(): Unit = runBlocking {
        val drums = createSyntheticToneWav("drums.wav", 120.0, 0.25f, 0.5f)
        val bass = createSyntheticToneWav("bass.wav", 65.0, 0.25f, 0.5f)
        val guitar = createSyntheticToneWav("guitar.wav", 330.0, 0.20f, 0.5f)
        val strings = createSyntheticToneWav("strings.wav", 520.0, 0.20f, 0.5f)
        val keys = createSyntheticToneWav("keys.wav", 440.0, 0.40f, 0.5f)
        val other = createSyntheticToneWav("other.wav", 660.0, 0.15f, 0.5f)

        val specs = listOf(
            MixerTrackMixSpec(1L, "Drums", Uri.fromFile(drums).toString(), 500L, 1.0f, 0.0f, false, false, true, 0L),
            MixerTrackMixSpec(2L, "Bass", Uri.fromFile(bass).toString(), 500L, 1.0f, 0.0f, false, false, true, 0L),
            MixerTrackMixSpec(3L, "Guitar", Uri.fromFile(guitar).toString(), 500L, 1.0f, -0.5f, false, false, true, 0L),
            MixerTrackMixSpec(4L, "Strings", Uri.fromFile(strings).toString(), 500L, 1.0f, 0.5f, false, false, true, 0L),
            MixerTrackMixSpec(5L, "Keys", Uri.fromFile(keys).toString(), 500L, 1.0f, 1.0f, true, false, true, 0L),
            MixerTrackMixSpec(6L, "Other", Uri.fromFile(other).toString(), 500L, 1.0f, 0.0f, false, false, true, 0L)
        )

        // Export individual Keys track even though it is muted in global mix (panned hard Right, 0.40 amplitude)
        val singleOut = File(tempDir, "keys_solo_export.wav")
        val singleRes = MultitrackWavExporter.exportSingleTrackToFile(context, specs[4], singleOut)
        assertTrue("Individual track WAV export should succeed", singleRes.isSuccess)
        val keysSamples = readAllStereoSamples(singleOut)
        var maxL = 0f
        var maxR = 0f
        for (i in 0 until keysSamples.size / 2) {
            maxL = max(maxL, abs(keysSamples[i * 2]))
            maxR = max(maxR, abs(keysSamples[i * 2 + 1]))
        }
        assertTrue("Hard-right Keys export should be silent on Left ($maxL)", maxL < 0.005f)
        assertTrue("Hard-right Keys export should carry ~0.40 on Right ($maxR)", maxR in 0.35f..0.45f)

        // Export combined 6-track mix WAV
        val mixOut = File(tempDir, "six_track_mix.wav")
        val mixRes = MultitrackWavExporter.exportMixToFile(context, specs, masterVolume = 1.0f, outputFile = mixOut)
        assertTrue("Complete 6-track WAV mix export should succeed", mixRes.isSuccess)
        val mixHeader = WavStreamReader.tryReadHeader(mixOut)!!
        assertEquals(44100, mixHeader.sampleRate)
        assertEquals(2, mixHeader.channels)
        assertEquals(16, mixHeader.bitsPerSample)
    }

    @Test
    fun testTrackReplacementRemovalResourceCleanupAndLowMemoryHandling(): Unit = runBlocking {
        val wavA = createSyntheticToneWav("track_a.wav", 330.0, 0.5f, 1.5f)
        val wavB = createSyntheticToneWav("track_b.wav", 660.0, 0.5f, 2.0f)

        val engine = MultitrackAudioEngine(context)
        val t1 = MixerTrackMixSpec(10L, "Vocals", Uri.fromFile(wavA).toString(), 1500L, 1f, 0f, false, false, true, 0L)
        val t2 = MixerTrackMixSpec(11L, "Drums", Uri.fromFile(wavB).toString(), 2000L, 1f, 0f, false, false, true, 0L)

        engine.syncTrackReaders(listOf(t1, t2))
        assertEquals(2, engine.activeReaderCount.value)
        assertEquals(2000L, engine.totalDurationMs.value)

        // Replace t1 audio and remove t2
        val t1Replaced = t1.copy(audioUri = Uri.fromFile(wavB).toString(), durationMs = 2000L)
        engine.syncTrackReaders(listOf(t1Replaced))
        assertEquals(1, engine.activeReaderCount.value)
        assertTrue(10L in engine.preparedTrackIds.value)
        assertFalse(11L in engine.preparedTrackIds.value)

        // Simulate low-memory trigger
        engine.handleLowMemory()
        assertEquals(0, engine.activeReaderCount.value)
        assertNotNull(engine.lowMemoryNotice.value)

        // Corrupted track fails cleanly without crashing
        val badFile = File(tempDir, "corrupt_track.mp3").apply { writeBytes(ByteArray(128) { 0x42 }) }
        val tBad = MixerTrackMixSpec(12L, "Broken", Uri.fromFile(badFile).toString(), 1000L, 1f, 0f, false, false, true, 0L)
        engine.syncTrackReaders(listOf(tBad))
        assertNotNull(engine.errorMessage.value)

        engine.release()
    }
}
