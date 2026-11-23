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
import com.osei.aimusicstudio.audio.MixerTrackMixSpec
import com.osei.aimusicstudio.audio.MultitrackWavExporter
import com.osei.aimusicstudio.audio.PcmWavSeekReader
import com.osei.aimusicstudio.audio.SafeTempStorageCleanup
import com.osei.aimusicstudio.audio.StreamingAudioDspChain
import com.osei.aimusicstudio.audio.StreamingStereoResampler
import com.osei.aimusicstudio.audio.WavStreamReader
import com.osei.aimusicstudio.audio.WavStreamWriter
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.Phase5Repository
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

/**
 * Phase 5.1 verification tests using deterministic synthetic audio signals.
 * Note: Synthetic DSP tests verify deterministic signal math and stability,
 * not subjective real-world acoustic quality on physical hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase5_1VerificationRobolectricTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "phase51_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        File(context.filesDir, "editor_clips").deleteRecursively()
        File(context.filesDir, "karaoke_takes").deleteRecursively()
        File(context.cacheDir, "shared_exports").deleteRecursively()
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

    private fun readMonoLeft16BitWavSamples(wavFile: File): Pair<Int, FloatArray> {
        val header = WavStreamReader.tryReadHeader(wavFile)!!
        val bytesPerFrame = header.channels * (header.bitsPerSample / 8)
        val totalFrames = (header.dataSizeBytes / bytesPerFrame).toInt()
        val rawBytes = ByteArray(totalFrames * bytesPerFrame)
        java.io.RandomAccessFile(wavFile, "r").use { raf ->
            raf.seek(header.dataOffset)
            raf.readFully(rawBytes)
        }
        val out = FloatArray(totalFrames)
        for (i in 0 until totalFrames) {
            val idx = i * bytesPerFrame
            val lo = rawBytes[idx].toInt() and 0xFF
            val hi = rawBytes[idx + 1].toInt()
            val sample16 = (hi shl 8) or lo
            out[i] = sample16 / 32768.0f
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

    @Test
    fun testCompressorReducesDynamicRangeAndPreventsClipping() {
        val sampleRate = 44100
        val frames = sampleRate / 2 // 0.5s
        val chain = StreamingAudioDspChain(sampleRate)
        val compConfig = AudioEffectsConfig(
            compressorThresholdDb = -18f,
            compressorRatio = 4f
        )

        // Quiet sine (-30 dBFS, well below -18 dB threshold)
        val quietBuf = FloatArray(frames * 2)
        // Loud sine (-2 dBFS, well above -18 dB threshold)
        val loudBuf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val w = sin(2.0 * PI * 440.0 * i / sampleRate).toFloat()
            quietBuf[i * 2] = 0.03f * w
            quietBuf[i * 2 + 1] = 0.03f * w
            loudBuf[i * 2] = 0.80f * w
            loudBuf[i * 2 + 1] = 0.80f * w
        }

        val inputRatio = rms(FloatArray(frames) { loudBuf[it * 2] }) / rms(FloatArray(frames) { quietBuf[it * 2] })

        chain.processInPlace(quietBuf, frames, compConfig)
        chain.reset()
        chain.processInPlace(loudBuf, frames, compConfig)

        // Measure after attack settle (frames 5000..frames)
        val quietOutMono = FloatArray(frames - 5000) { quietBuf[(5000 + it) * 2] }
        val loudOutMono = FloatArray(frames - 5000) { loudBuf[(5000 + it) * 2] }
        val outputRatio = rms(loudOutMono) / rms(quietOutMono)

        assertTrue(
            "Compressor should compress dynamic ratio ($outputRatio vs raw $inputRatio)",
            outputRatio < inputRatio * 0.55
        )
        // Loud peaks above threshold must also be attenuated below raw 0.80 amplitude
        val loudPeak = loudOutMono.maxOf { abs(it) }
        assertTrue("Loud peak ($loudPeak) should be reduced by 4:1 compression", loudPeak < 0.65f)

        // Extreme input (+1.6f) must remain safely bounded by soft safety limiter within [-1, 1]
        chain.reset()
        val hotBuf = FloatArray(4096 * 2) { 1.6f }
        chain.processInPlace(hotBuf, 4096, compConfig.copy(volume = 1.5f))
        assertTrue("Safety limiter must bound all samples strictly <= 1.0", hotBuf.all { abs(it) <= 1.0f && it.isFinite() })
    }

    @Test
    fun testNoiseGateAttenuatesQuietAudioWhilePreservingLouderAudio() {
        val sampleRate = 44100
        val frames = sampleRate / 2
        val chain = StreamingAudioDspChain(sampleRate)
        val gateConfig = AudioEffectsConfig(noiseGateThresholdDb = -35f) // threshold ~ 0.0178 amp

        // 1) Quiet signal (-54 dBFS ~ 0.002 amplitude < threshold)
        val quietBuf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val s = (0.002 * sin(2.0 * PI * 440.0 * i / sampleRate)).toFloat()
            quietBuf[i * 2] = s
            quietBuf[i * 2 + 1] = s
        }
        val quietInRms = rms(FloatArray(frames - 4000) { quietBuf[(4000 + it) * 2] })
        chain.processInPlace(quietBuf, frames, gateConfig)
        val quietOutRms = rms(FloatArray(frames - 4000) { quietBuf[(4000 + it) * 2] })
        assertTrue(
            "Noise gate must heavily attenuate quiet signal below threshold (out=$quietOutRms, in=$quietInRms)",
            quietOutRms < quietInRms * 0.15
        )

        // 2) Louder signal (-10.5 dBFS ~ 0.30 amplitude > threshold)
        chain.reset()
        val loudBuf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val s = (0.30 * sin(2.0 * PI * 440.0 * i / sampleRate)).toFloat()
            loudBuf[i * 2] = s
            loudBuf[i * 2 + 1] = s
        }
        val loudInRms = rms(FloatArray(frames - 4000) { loudBuf[(4000 + it) * 2] })
        chain.processInPlace(loudBuf, frames, gateConfig)
        val loudOutRms = rms(FloatArray(frames - 4000) { loudBuf[(4000 + it) * 2] })
        assertTrue(
            "Noise gate must preserve audio above threshold (out=$loudOutRms, in=$loudInRms)",
            loudOutRms > loudInRms * 0.92
        )
    }

    @Test
    fun testDspStabilityOnSilenceAndNearZeroBuffers() {
        val sampleRate = 44100
        val frames = 8192
        val chain = StreamingAudioDspChain(sampleRate)
        val heavyConfig = AudioEffectsConfig(
            eqLowGainDb = 8f,
            eqMidGainDb = -6f,
            eqHighGainDb = 9f,
            bassBoostDb = 10f,
            reverbMix = 0.65f,
            delayMix = 0.55f,
            delayTimeMs = 180,
            delayFeedback = 0.55f,
            compressorThresholdDb = -24f,
            compressorRatio = 6f,
            noiseGateThresholdDb = -30f,
            volume = 1.4f,
            balance = 0.25f
        )

        val silence = FloatArray(frames * 2) { 0f }
        chain.processInPlace(silence, frames, heavyConfig)
        assertTrue("Silence must yield finite samples", silence.all { it.isFinite() })
        assertTrue("Silence must stay silent", silence.all { abs(it) < 1e-6f })

        chain.reset()
        val tiny = FloatArray(frames * 2) { idx -> if (idx % 2 == 0) 1e-9f else -1e-9f }
        chain.processInPlace(tiny, frames, heavyConfig)
        assertTrue("Near-zero samples must never produce NaN/Inf", tiny.all { it.isFinite() })
        assertTrue("Near-zero samples must stay strictly bounded near zero", tiny.all { abs(it) < 1e-4f })
    }

    @Test
    fun testStreamingDspMultiChunkRenderingClosesFilesCleanly() = runBlocking {
        // 1.5 seconds forces >15 streaming chunks
        val src = createSineWav("multi_chunk.wav", 440.0, 0.45f, 1.5f)
        val out = File(tempDir, "multi_chunk_dsp.wav")
        val cfg = AudioEffectsConfig(
            bassBoostDb = 4f,
            compressorThresholdDb = -20f,
            compressorRatio = 3f,
            noiseGateThresholdDb = -50f,
            reverbMix = 0.2f
        )
        val res = AudioEffectsRenderer.processAudioToFile(
            context = context,
            inputUri = Uri.fromFile(src),
            outputFile = out,
            effects = cfg
        )
        assertTrue("Streaming DSP processing should succeed", res.isSuccess)
        val hdr = WavStreamReader.tryReadHeader(out)!!
        val durMs = (hdr.dataSizeBytes * 1000L) / hdr.byteRate
        assertTrue("Expected ~1500ms output, got $durMs ms", abs(durMs - 1500L) <= 30L)
        assertTrue("Output file must be readable & unlocked after render", out.renameTo(File(tempDir, "renamed_ok.wav")))
    }

    @Test
    fun testAntiAliasingResamplerPreservesPassbandAndSuppressesHighFrequencyAliasing() = runBlocking {
        // 1) Passband 1 kHz sine exported 44.1 kHz -> 22.05 kHz preserves duration and energy cleanly
        val passbandSrc = createSineWav("passband_1khz.wav", 1000.0, 0.5f, 1.0f, 44100)
        val passbandOut = File(tempDir, "passband_22k.wav")
        val resPass = MultitrackWavExporter.exportMixToFile(
            context = context,
            tracks = listOf(
                MixerTrackMixSpec(1L, "Tone", Uri.fromFile(passbandSrc).toString(), 1000L, 1f, 0f, false, false, true, 0L)
            ),
            masterVolume = 1.0f,
            outputFile = passbandOut,
            targetSampleRateHz = 22050
        )
        assertTrue(resPass.isSuccess)
        val (sr22, sPass) = readMonoLeft16BitWavSamples(passbandOut)
        assertEquals(22050, sr22)
        val passDurMs = (sPass.size * 1000L) / sr22
        assertTrue("Expected ~1000ms at 22050 Hz, got $passDurMs ms", abs(passDurMs - 1000L) <= 25L)
        val passRms = rms(sPass, 500, sPass.size - 500)
        // Constant-power pan=0 applies cos(pi/4)=0.7071 to Left, so 0.5f sine -> 0.5 * 0.7071 * 0.7071 = 0.2500 RMS
        assertTrue("Passband 1 kHz RMS ($passRms) should stay near 0.250", passRms in 0.22..0.28)

        // 2) High-frequency 16 kHz tone (> 11.025 kHz Nyquist for 22.05 kHz output!)
        // Linear interpolation would alias 16 kHz loudly down to 6.05 kHz; Blackman-sinc FIR attenuates it strongly.
        val highFreqSrc = createSineWav("stopband_16khz.wav", 16000.0, 0.5f, 1.0f, 44100)
        val highFreqOut = File(tempDir, "stopband_22k.wav")
        val resStop = MultitrackWavExporter.exportMixToFile(
            context = context,
            tracks = listOf(
                MixerTrackMixSpec(2L, "High", Uri.fromFile(highFreqSrc).toString(), 1000L, 1f, 0f, false, false, true, 0L)
            ),
            masterVolume = 1.0f,
            outputFile = highFreqOut,
            targetSampleRateHz = 22050
        )
        assertTrue(resStop.isSuccess)
        val (srStop, sStop) = readMonoLeft16BitWavSamples(highFreqOut)
        assertEquals(22050, srStop)
        val stopRms = rms(sStop, 500, sStop.size - 500)
        assertTrue(
            "Anti-aliasing filter should attenuate 16 kHz stopband by >20 dB at 22.05 kHz (RMS=$stopRms)",
            stopRms < 0.035
        )

        // 3) StreamingStereoResampler 44.1 kHz -> 48 kHz upsample maintains exact expected duration & clean flush
        val upsampleOut = File(tempDir, "upsample_48k.wav")
        val resUp = MultitrackWavExporter.exportMixToFile(
            context = context,
            tracks = listOf(
                MixerTrackMixSpec(1L, "Tone", Uri.fromFile(passbandSrc).toString(), 1000L, 1f, 0f, false, false, true, 0L)
            ),
            masterVolume = 1.0f,
            outputFile = upsampleOut,
            targetSampleRateHz = 48000
        )
        assertTrue(resUp.isSuccess)
        val (sr48, sUp) = readMonoLeft16BitWavSamples(upsampleOut)
        assertEquals(48000, sr48)
        val upDurMs = (sUp.size * 1000L) / sr48
        assertTrue("Expected ~1000ms at 48000 Hz, got $upDurMs ms", abs(upDurMs - 1000L) <= 25L)
    }

    @Test
    fun testSafeTempFileCleanupRespectsProtectedAndActiveFiles() {
        val now = 2_000_000_000L
        val editorDir = File(context.filesDir, "editor_clips").apply { mkdirs() }
        val karaokeDir = File(context.filesDir, "karaoke_takes").apply { mkdirs() }
        val userImportDir = File(tempDir, "user_imports").apply { mkdirs() }
        val shareDir = File(context.cacheDir, "shared_exports").apply { mkdirs() }

        val protectedMerged = File(editorDir, "merged_protected.wav").apply {
            writeBytes(ByteArray(128))
            setLastModified(now - 60_000L)
        }
        val unusedOldMerged = File(editorDir, "merged_obsolete.wav").apply {
            writeBytes(ByteArray(128))
            setLastModified(now - 60_000L)
        }
        val brandNewMerged = File(editorDir, "merged_fresh.wav").apply {
            writeBytes(ByteArray(128))
            setLastModified(now - 500L)
        }
        val karaokeTake = File(karaokeDir, "take_1_123.wav").apply {
            writeBytes(ByteArray(128))
            setLastModified(now - 999_000L)
        }
        val userImport = File(userImportDir, "merged_user_song.wav").apply {
            writeBytes(ByteArray(128))
            setLastModified(now - 999_000L)
        }

        val cleanedMerged = SafeTempStorageCleanup.cleanUnusedEditorMergedFiles(
            context = context,
            protectedUris = setOf(Uri.fromFile(protectedMerged).toString()),
            minAgeMs = 5_000L,
            nowMs = now
        )
        assertEquals(1, cleanedMerged)
        assertTrue("Referenced merged WAV must NEVER be deleted", protectedMerged.exists())
        assertFalse("Unreferenced old merged WAV must be cleaned", unusedOldMerged.exists())
        assertTrue("Fresh merged WAV inside grace period must NOT be deleted", brandNewMerged.exists())
        assertTrue("Karaoke recording must NEVER be touched", karaokeTake.exists())
        assertTrue("User import must NEVER be touched", userImport.exists())

        // Abandoned temp files in cacheDir
        val abandonedTmp = File(context.cacheDir, "clip_trim_abandoned.wav").apply {
            writeBytes(ByteArray(64))
            setLastModified(now - 45 * 60_000L)
        }
        val activeRenderTmp = File(context.cacheDir, "mix_export_active.wav").apply {
            writeBytes(ByteArray(64))
            setLastModified(now - 45 * 60_000L)
        }
        val cleanedTmp = SafeTempStorageCleanup.cleanAbandonedTempRenderFiles(
            context = context,
            minAgeMs = 30 * 60_000L,
            nowMs = now,
            activeFiles = setOf(activeRenderTmp)
        )
        assertEquals(1, cleanedTmp)
        assertFalse("Old abandoned render file must be removed", abandonedTmp.exists())
        assertTrue("Active file must NOT be removed", activeRenderTmp.exists())
        activeRenderTmp.delete()

        // Shared exports cleanup
        val activeShare = File(shareDir, "active_share.wav").apply {
            writeBytes(ByteArray(64))
            setLastModified(now - 10_000L)
        }
        val expiredShare = File(shareDir, "old_share.wav").apply {
            writeBytes(ByteArray(64))
            setLastModified(now - 90 * 60_000L)
        }
        val cleanedShares = SafeTempStorageCleanup.cleanOldSharedExports(
            context = context,
            maxAgeMs = 60 * 60_000L,
            keepMostRecent = 5,
            nowMs = now,
            activeShareFile = activeShare
        )
        assertEquals(1, cleanedShares)
        assertTrue("Currently shared file must remain readable", activeShare.exists())
        assertFalse("Expired share file must be removed", expiredShare.exists())
    }

    @Test
    fun testRoomMigration4To5AndKaraokeTakeOffsetSync() = runBlocking {
        // 1) Build v4 SQLite DB containing a v4 karaoke_projects row without recordedTakeOffsetMs
        val dbFile = File(tempDir, "migration_4_5.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
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
                                `vocalUri` TEXT, `instrumentalUri` TEXT,
                                `drumsUri` TEXT, `bassUri` TEXT, `guitarUri` TEXT,
                                `stringsUri` TEXT, `keysUri` TEXT, `otherUri` TEXT,
                                `bpm` INTEGER NOT NULL, `musicalKey` TEXT NOT NULL,
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
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mixer_tracks_mixerProjectId` ON `mixer_tracks` (`mixerProjectId`)")
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
                                `sortOrder` INTEGER NOT NULL,
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
                                FOREIGN KEY(`editorProjectId`) REFERENCES `editor_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_editor_clips_editorProjectId` ON `editor_clips` (`editorProjectId`)")
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
                                `rawLrcText` TEXT NOT NULL,
                                `lyricsOffsetMs` INTEGER NOT NULL,
                                `instrumentalVolume` REAL NOT NULL,
                                `vocalReferenceVolume` REAL NOT NULL,
                                `recordedTakeVolume` REAL NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO `karaoke_projects` (
                                `id`, `title`, `instrumentalUri`, `instrumentalName`, `instrumentalDurationMs`,
                                `vocalReferenceUri`, `vocalReferenceName`, `recordedTakeUri`, `recordedTakeName`,
                                `rawLrcText`, `lyricsOffsetMs`, `instrumentalVolume`, `vocalReferenceVolume`,
                                `recordedTakeVolume`, `createdAt`, `updatedAt`
                            ) VALUES (
                                7, 'Existing v4 Session', NULL, '', 0, NULL, '', NULL, '', '[00:02.00] Hello', 150, 0.9, 0.3, 1.0, 1000, 1000
                            )
                            """.trimIndent()
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()

        val db5 = Room.databaseBuilder(context, MusicDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                MusicDatabase.MIGRATION_1_2,
                MusicDatabase.MIGRATION_2_3,
                MusicDatabase.MIGRATION_3_4,
                MusicDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()

        val repo = Phase5Repository(db5.phase5Dao())
        val existing = repo.getKaraokeProject(7L)!!
        assertEquals("Existing v4 Session", existing.title)
        assertEquals(150L, existing.lyricsOffsetMs)
        assertEquals(0L, existing.recordedTakeOffsetMs)

        repo.saveKaraokeProject(existing.copy(recordedTakeOffsetMs = -40L))
        db5.close()

        val reopened = Room.databaseBuilder(context, MusicDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                MusicDatabase.MIGRATION_1_2,
                MusicDatabase.MIGRATION_2_3,
                MusicDatabase.MIGRATION_3_4,
                MusicDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()
        val persisted = reopened.phase5Dao().getKaraokeProjectById(7L)!!
        assertEquals(-40L, persisted.recordedTakeOffsetMs)
        assertEquals(150L, persisted.lyricsOffsetMs)
        reopened.close()

        // 2) Verify offsetMs timing shift on recorded vocal track in WAV mix export (+60 ms delay)
        val takeWav = createSineWav("karaoke_take.wav", 440.0, 0.5f, 0.25f)
        val outMix = File(tempDir, "karaoke_sync_mix.wav")
        val mixRes = MultitrackWavExporter.exportMixToFile(
            context = context,
            tracks = listOf(
                MixerTrackMixSpec(
                    trackId = 3L,
                    name = "Vocal Recording",
                    audioUri = Uri.fromFile(takeWav).toString(),
                    durationMs = 250L,
                    volume = 1.0f,
                    pan = 0f,
                    isMuted = false,
                    isSolo = false,
                    isEnabled = true,
                    offsetMs = 60L
                )
            ),
            masterVolume = 1.0f,
            outputFile = outMix,
            targetSampleRateHz = 44100
        )
        assertTrue(mixRes.isSuccess)
        val (sr, samples) = readMonoLeft16BitWavSamples(outMix)
        assertEquals(44100, sr)
        // First 50 ms (~2205 frames) should be silent because vocal take starts at +60 ms
        val preOffsetPeak = (0 until 2200).maxOf { abs(samples[it]) }
        val postOffsetPeak = (3000 until samples.size).maxOf { abs(samples[it]) }
        assertTrue("Pre-offset samples must be silent ($preOffsetPeak)", preOffsetPeak < 0.005f)
        assertTrue("Post-offset samples must carry vocal signal ($postOffsetPeak)", postOffsetPeak > 0.35f)
    }
}
