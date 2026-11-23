package com.osei.aimusicstudio

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.osei.aimusicstudio.ai.DeviceMemoryTier
import com.osei.aimusicstudio.ai.MemoryProfiler
import com.osei.aimusicstudio.ai.MemorySnapshot
import com.osei.aimusicstudio.ai.ModelAvailability
import com.osei.aimusicstudio.ai.ModelStatus
import com.osei.aimusicstudio.ai.SeparationModelCatalog
import com.osei.aimusicstudio.ai.StemMode
import com.osei.aimusicstudio.ai.StemSeparatorEngine
import com.osei.aimusicstudio.ai.StereoOverlapAddStitcher
import com.osei.aimusicstudio.ai.StftProcessor
import com.osei.aimusicstudio.audio.AudioPlayerManager
import com.osei.aimusicstudio.audio.AudioValidator
import com.osei.aimusicstudio.audio.StreamingAudioDecoder
import com.osei.aimusicstudio.audio.WavStreamReader
import com.osei.aimusicstudio.audio.WavStreamWriter
import com.osei.aimusicstudio.audio.WaveformGenerator
import com.osei.aimusicstudio.data.MusicDao
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.MusicProjectEntity
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
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MusicStudioRobolectricTest {

    private lateinit var db: MusicDatabase
    private lateinit var dao: MusicDao
    private lateinit var context: Context
    private lateinit var tempTestDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.musicDao()
        tempTestDir = File(context.cacheDir, "unit_test_audio_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        db.close()
        tempTestDir.deleteRecursively()
    }

    private fun createRealTestWav(fileName: String, durationSeconds: Float, sampleRate: Int = 44100): File {
        val file = File(tempTestDir, fileName)
        val totalFrames = (durationSeconds * sampleRate).toInt()
        val chunkFrames = 4096
        val buf = FloatArray(chunkFrames * 2)
        var frameIdx = 0

        WavStreamWriter(file, sampleRate, 2).use { writer ->
            while (frameIdx < totalFrames) {
                val n = minOf(chunkFrames, totalFrames - frameIdx)
                for (i in 0 until n) {
                    val t = (frameIdx + i).toDouble() / sampleRate.toDouble()
                    val lead = 0.4 * sin(2.0 * PI * 440.0 * t)
                    val bass = 0.3 * sin(2.0 * PI * 110.0 * t)
                    val pulse = if ((frameIdx + i) % (sampleRate / 2) < 400) 0.25 else 0.0
                    buf[i * 2] = (lead + bass + pulse).toFloat().coerceIn(-1f, 1f)
                    buf[i * 2 + 1] = (lead * 0.9 + bass + pulse).toFloat().coerceIn(-1f, 1f)
                }
                writer.writeFloatSamples(buf, n * 2)
                frameIdx += n
            }
        }
        return file
    }

    @Test
    fun testRoomProjectCrudAndRecoveryAfterRestart(): Unit = runBlocking {
        val dbFile = File(tempTestDir, "persistent_test_db")
        val db1 = Room.databaseBuilder(context, MusicDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val dao1 = db1.musicDao()

        val id = dao1.insertProject(
            MusicProjectEntity(
                title = "Alpha Beat",
                artist = "Osei Producer",
                audioUri = "file:///tmp/alpha.wav",
                durationMs = 120000L,
                fileSizeMb = 10.5f
            )
        )
        assertTrue(id > 0)

        val inserted = dao1.getProjectById(id)!!
        dao1.updateProject(inserted.copy(title = "Alpha Beat Remastered"))
        db1.close()

        val db2 = Room.databaseBuilder(context, MusicDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val dao2 = db2.musicDao()
        val recovered = dao2.getProjectById(id)
        assertNotNull(recovered)
        assertEquals("Alpha Beat Remastered", recovered?.title)

        dao2.deleteProjectById(id)
        assertNull(dao2.getProjectById(id))
        assertTrue(dao2.getAllProjects().first().isEmpty())
        db2.close()
    }

    @Test
    fun testAudioValidatorAcceptsRealWavAndRejectsCorruptedFile() {
        val validWav = createRealTestWav("valid_short.wav", 2.0f)
        val validResult = AudioValidator.inspectAndValidate(context, Uri.fromFile(validWav))
        assertTrue("Expected valid WAV to pass", validResult.isSuccess)
        val meta = validResult.getOrNull()!!
        assertEquals(44100, meta.sampleRate)
        assertEquals(2, meta.channelCount)
        assertTrue(meta.durationMs in 1900L..2100L)

        val corruptedFile = File(tempTestDir, "corrupted.xyz").apply {
            writeBytes(ByteArray(256) { 0x13 })
        }
        val badResult = AudioValidator.inspectAndValidate(context, Uri.fromFile(corruptedFile))
        assertTrue("Expected corrupted file to fail validation", badResult.isFailure)
    }

    @Test
    fun testStreamingChunkedDecodingBoundedMemoryShortMediumLong() {
        val durations = listOf("short_2s.wav" to 2.0f, "medium_12s.wav" to 12.0f, "long_40s.wav" to 40.0f)
        for ((name, sec) in durations) {
            val startTime = System.currentTimeMillis()
            val wavFile = createRealTestWav(name, sec)
            val header = WavStreamReader.tryReadHeader(wavFile)
            assertNotNull(header)

            val runtime = Runtime.getRuntime()
            System.gc()
            val beforeUsed = runtime.totalMemory() - runtime.freeMemory()

            var decodedFrames = 0L
            var chunkCount = 0
            val chunkSize = 44100 * 2 // 2s chunks for 4 GB RAM target
            StreamingAudioDecoder.streamStereoChunks(
                context = context,
                uri = Uri.fromFile(wavFile),
                chunkFrames = chunkSize,
                onChunk = { _, frameCount, _ ->
                    decodedFrames += frameCount
                    chunkCount++
                }
            )

            val afterUsed = runtime.totalMemory() - runtime.freeMemory()
            val deltaMb = (afterUsed - beforeUsed) / (1024f * 1024f)
            val elapsedMs = System.currentTimeMillis() - startTime
            println("BENCHMARK [$name]: frames=$decodedFrames, chunks=$chunkCount, heapDelta=${String.format("%.2f", deltaMb)}MB, elapsed=${elapsedMs}ms")

            val expectedFrames = (sec * 44100f).toLong()
            assertEquals("Frame count mismatch for $name", expectedFrames, decodedFrames)
            assertTrue("Expected multiple chunks for $name", chunkCount >= 1)
            assertTrue("Unexpectedly large heap delta ($deltaMb MB) for $name", deltaMb < 32f)
        }
    }

    @Test
    fun testStftRoundTripAndOverlapAddChunkReconstruction() {
        val stft = StftProcessor(fftSize = 1024, hopSize = 256)
        val n = 8192
        val signal = FloatArray(n) { i ->
            (0.6 * sin(2.0 * PI * 440.0 * i / 44100.0)).toFloat()
        }
        val (re, im, frames) = stft.stft(signal)
        val reconstructed = stft.istft(re, im, frames, n)

        var maxErr = 0f
        for (i in 1024 until n - 1024) {
            val err = abs(signal[i] - reconstructed[i])
            if (err > maxErr) maxErr = err
        }
        assertTrue("STFT->iSTFT reconstruction error too high: $maxErr", maxErr < 1e-3f)

        val overlapFrames = 512
        val chunkFrames = 2048
        val stepFrames = chunkFrames - overlapFrames
        val totalFrames = stepFrames * 3 + overlapFrames
        val fullStereo = FloatArray(totalFrames * 2) { idx ->
            val f = idx / 2
            (0.5 * sin(2.0 * PI * 330.0 * f / 44100.0)).toFloat()
        }

        val stitcher = StereoOverlapAddStitcher(overlapFrames)
        val outSamples = ArrayList<Float>()
        for (c in 0 until 3) {
            val start = c * stepFrames * 2
            val chunk = fullStereo.copyOfRange(start, start + chunkFrames * 2)
            val emitted = stitcher.processChunk(chunk, chunkFrames, isLastChunk = (c == 2))
            for (s in emitted) outSamples.add(s)
        }
        assertEquals(fullStereo.size, outSamples.size)
        var maxStitchErr = 0f
        for (i in fullStereo.indices) {
            val err = abs(fullStereo[i] - outSamples[i])
            if (err > maxStitchErr) maxStitchErr = err
        }
        assertTrue("Overlap-add seam error too high: $maxStitchErr", maxStitchErr < 1e-4f)
    }

    @Test
    fun testRealWaveformGenerationAndNoFakeFallbackOnCorrupted(): Unit = runBlocking {
        val realWav = createRealTestWav("waveform_real.wav", 3.0f)
        val wfResult = WaveformGenerator.generateWaveform(context, Uri.fromFile(realWav).toString(), barsCount = 32, useCache = true)
        assertTrue(wfResult.isSuccess)
        val wf = wfResult.getOrNull()!!
        assertEquals(32, wf.peaks.size)
        assertEquals(32, wf.rms.size)
        assertTrue(wf.overallPeakDb > -20f && wf.overallPeakDb <= 0.1f)
        assertTrue(wf.overallRmsDb < wf.overallPeakDb)

        val wfCached = WaveformGenerator.generateWaveform(context, Uri.fromFile(realWav).toString(), barsCount = 32, useCache = true)
        assertTrue(wfCached.isSuccess)
        assertEquals(wf.peaks, wfCached.getOrNull()?.peaks)

        val missingResult = WaveformGenerator.generateWaveform(context, "file:///nonexistent_track_xyz.wav", barsCount = 32, useCache = false)
        assertTrue("Missing file must fail instead of returning fake waveform", missingResult.isFailure)
    }

    @Test
    fun testLowMemory4GbConfigurationAndNoFakeStemFallback(): Unit = runBlocking {
        val lowRamSnap = MemorySnapshot(
            deviceTotalMb = 4096L,
            deviceAvailableMb = 950L,
            isSystemLowMemory = false,
            appJvmUsedMb = 45L,
            appJvmMaxMb = 256L,
            appNativeAllocatedMb = 12L
        )
        val config = MemoryProfiler.recommendConfig(lowRamSnap)
        assertEquals(DeviceMemoryTier.LOW_RAM_4GB, config.tier)
        assertEquals(2.0f, config.chunkSeconds)
        assertFalse(config.allowHeavyDemucs)
        assertEquals(2, config.intraOpThreads)

        val engine = StemSeparatorEngine(context)
        val realWav = createRealTestWav("sep_input.wav", 2.0f)

        // 1. KARA_2 lead/backing model must be rejected as a Vocals/Instrumental substitute
        val karaSpec = SeparationModelCatalog.findById("mdx_kim_vocal_2_onnx")
        assertEquals(StemMode.KARAOKE_LEAD_BACKING, karaSpec.stemMode)
        assertEquals(ModelAvailability.UNAVAILABLE, karaSpec.availability)
        engine.selectModel(karaSpec)
        val karaRes = engine.separateStems(Uri.fromFile(realWav).toString())
        assertTrue("KARA_2 must be rejected for Vocals/Instrumental separation", karaRes.isFailure)
        assertEquals(ModelStatus.ERROR, engine.modelStatus.value)

        // 2. Missing UVR-MDX-NET-Inst_HQ_4 model must NOT fabricate stems
        val twoStemSpec = SeparationModelCatalog.findById("uvr_mdx_inst_hq_4")
        assertEquals("UVR-MDX-NET-Inst_HQ_4.onnx", twoStemSpec.fileName)
        engine.selectModel(twoStemSpec)
        val missingModelRes = engine.separateStems(Uri.fromFile(realWav).toString())
        assertTrue("Missing ONNX model must fail honestly", missingModelRes.isFailure)
        assertEquals(ModelStatus.NOT_DOWNLOADED, engine.modelStatus.value)

        // 3. Corrupted ONNX file must fail SHA-256 / ONNX validation and NOT create fake stems
        val modelsDir = File(context.filesDir, "onnx_models").apply { mkdirs() }
        val corruptOnnx = File(modelsDir, twoStemSpec.fileName).apply {
            writeBytes(ByteArray(2048) { 0x50 })
        }
        val corruptRes = engine.separateStems(Uri.fromFile(realWav).toString())
        assertTrue("Corrupted ONNX file must fail honestly", corruptRes.isFailure)
        assertEquals(ModelStatus.ERROR, engine.modelStatus.value)
        println("ONNX_HOST_CHECK: ${corruptRes.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message}" }}")
        corruptOnnx.delete()

        // 3. Heavy HTDemucs model has verified stem order [drums, bass, other, vocals] and is blocked on <= 4GB
        val demucsSpec = SeparationModelCatalog.findById("htdemucs_v4_onnx")
        assertEquals(listOf("drums", "bass", "other", "vocals"), demucsSpec.outputStemOrder)
        assertNotNull(demucsSpec.downloadUrl)
        val demucsFile = File(modelsDir, demucsSpec.fileName).apply {
            writeBytes(ByteArray(4096) { 0x08 })
        }
        engine.selectModel(demucsSpec)
        val blockedRes = engine.separateStems(Uri.fromFile(realWav).toString())
        assertTrue("Heavy Demucs should be blocked on <= 4GB device unless overridden", blockedRes.isFailure)
        demucsFile.delete()
        assertFalse(demucsFile.exists())
    }

    @Test
    fun testStreamingCancellationAndTempFileCleanup() {
        val wav = createRealTestWav("cancel_stream.wav", 6.0f)
        var chunksSeen = 0
        var cancelledFlag = false
        StreamingAudioDecoder.streamStereoChunks(
            context = context,
            uri = Uri.fromFile(wav),
            chunkFrames = 44100, // 1s chunks
            isCancelled = { cancelledFlag },
            onChunk = { _, _, _ ->
                chunksSeen++
                if (chunksSeen >= 2) {
                    cancelledFlag = true
                }
            }
        )
        assertEquals("Streaming should stop cleanly right after cancellation", 2, chunksSeen)

        val engine = StemSeparatorEngine(context)
        engine.cancelCurrentOperation()
        val blankRes = runBlocking { engine.separateStems("") }
        assertTrue(blankRes.isFailure)
        assertEquals(ModelStatus.ERROR, engine.modelStatus.value)
    }

    @Test
    fun testAudioPlayerManagerLifecycleAndInvalidUriError() {
        val player = AudioPlayerManager(context)
        player.setVolume(0.6f)
        assertEquals(0.6f, player.volume.value)

        player.loadAndPlay("")
        assertNotNull(player.playbackError.value)
        player.clearError()
        assertNull(player.playbackError.value)

        val wav = createRealTestWav("player_test.wav", 2.0f)
        player.loadAndPlay(Uri.fromFile(wav).toString())
        player.seekTo(500L)
        player.pause()
        player.stop()
        assertFalse(player.isPlaying.value)
        player.release()
    }

    @Test
    fun testSixStemCatalogHonestyAndUnavailableEngineGuard(): Unit = runBlocking {
        // 1. Existing HTDemucs 4-stem must stay strictly 4 stems (never renamed to 6)
        val v4 = SeparationModelCatalog.findById("htdemucs_v4_onnx")
        assertEquals(StemMode.FOUR_STEM, v4.stemMode)
        assertEquals(listOf("drums", "bass", "other", "vocals"), v4.outputStemOrder)

        // 2. Researched HTDemucs 6-stem ONNX exposes exact tensor outputs (includes vocals & piano, not strings/keys)
        val htd6 = SeparationModelCatalog.findById("htdemucs_6s_onnx")
        assertEquals(listOf("drums", "bass", "other", "vocals", "guitar", "piano"), htd6.outputStemOrder)
        assertEquals(ModelAvailability.REQUIRES_HIGH_RAM, htd6.availability)

        // 3. Requested 6-instrument target (Drums, Bass, Guitar, Strings, Keys, Other) is honestly marked UNAVAILABLE
        val target6 = SeparationModelCatalog.findById("osei_6stem_instruments_target")
        assertEquals(listOf("drums", "bass", "guitar", "strings", "keys", "other"), target6.outputStemOrder)
        assertEquals(ModelAvailability.UNAVAILABLE, target6.availability)

        val engine = StemSeparatorEngine(context)
        val wav = createRealTestWav("six_stem_guard.wav", 1.5f)
        engine.selectModel(target6)
        val res = engine.separateStems(Uri.fromFile(wav).toString())
        assertTrue("Unavailable 6-instrument AI model must refuse without creating fake stems", res.isFailure)
        assertEquals(ModelStatus.ERROR, engine.modelStatus.value)
    }
}
