package com.osei.aimusicstudio

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.osei.aimusicstudio.ai.ModelAvailability
import com.osei.aimusicstudio.ai.ModelStatus
import com.osei.aimusicstudio.ai.SeparationModelCatalog
import com.osei.aimusicstudio.ai.StemMode
import com.osei.aimusicstudio.ai.StemSeparatorEngine
import com.osei.aimusicstudio.ai.StftProcessor
import com.osei.aimusicstudio.audio.WavStreamReader
import com.osei.aimusicstudio.audio.WavStreamWriter
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.ui.screens.VocalSeparationScreen
import com.osei.aimusicstudio.ui.theme.OseiAiMusicStudioTheme
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Phase 5.2.1 Robolectric verification tests for corrected Vocals vs. Instrumental UVR MDX-Net
 * model selection, mixed-radix centered-Hann STFT/iSTFT, SHA-256 & ONNX tensor checks,
 * real ONNX Runtime CPU inference, and dual-stem validation gating `stemsSeparated`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase5_2_1VocalModelRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        tempDir = File(app.cacheDir, "phase5_2_1_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        File(app.filesDir, "onnx_models").deleteRecursively()
        File(app.filesDir, "stems").deleteRecursively()
    }

    private fun createRealisticMusicMixWav(
        fileName: String,
        durationSec: Float = 6.5f,
        sampleRate: Int = 44100
    ): File {
        val file = File(tempDir, fileName)
        val totalFrames = (durationSec * sampleRate).toInt()
        val chunkFrames = 4096
        val buf = FloatArray(chunkFrames * 2)
        var frameIdx = 0

        WavStreamWriter(file, sampleRate, 2).use { writer ->
            while (frameIdx < totalFrames) {
                val n = minOf(chunkFrames, totalFrames - frameIdx)
                for (i in 0 until n) {
                    val t = (frameIdx + i).toDouble() / sampleRate.toDouble()
                    // Vocal melody (523-784 Hz with 5 Hz vibrato & phrase gating)
                    val phraseGate = if ((t % 2.0) < 1.55) 1.0 else 0.12
                    val vib = 1.0 + 0.008 * sin(2.0 * PI * 5.2 * t)
                    val f0 = when (((t / 1.5).toInt()) % 3) {
                        0 -> 523.25
                        1 -> 659.25
                        else -> 587.33
                    } * vib
                    val vocal = phraseGate * (
                        0.32 * sin(2.0 * PI * f0 * t) +
                            0.14 * sin(2.0 * PI * 2.0 * f0 * t) +
                            0.06 * sin(2.0 * PI * 3.0 * f0 * t)
                        )

                    // Bassline + panned accompaniment + kick impulse (120 BPM)
                    val beatPos = t % 0.5
                    val kick = 0.28 * exp(-beatPos / 0.045) * sin(2.0 * PI * (55.0 + 70.0 * exp(-beatPos / 0.02)) * t)
                    val bass = 0.24 * sin(2.0 * PI * 110.0 * t)
                    val chordL = 0.14 * sin(2.0 * PI * 261.63 * t) + 0.10 * sin(2.0 * PI * 329.63 * t)
                    val chordR = 0.14 * sin(2.0 * PI * 392.00 * t) + 0.10 * sin(2.0 * PI * 261.63 * t)

                    buf[i * 2] = (vocal + bass + kick + chordL).toFloat().coerceIn(-0.98f, 0.98f)
                    buf[i * 2 + 1] = (vocal + bass + kick + chordR).toFloat().coerceIn(-0.98f, 0.98f)
                }
                writer.writeFloatSamples(buf, n * 2)
                frameIdx += n
            }
        }
        return file
    }

    private fun createZeroSilentWav(fileName: String, durationSec: Float = 1.0f, sampleRate: Int = 44100): File {
        val file = File(tempDir, fileName)
        val totalFrames = (durationSec * sampleRate).toInt()
        val buf = FloatArray(totalFrames * 2)
        WavStreamWriter(file, sampleRate, 2).use { writer ->
            writer.writeFloatSamples(buf, buf.size)
        }
        return file
    }

    /**
     * Builds a minimal valid ONNX opset-13 protobuf graph implementing:
     * Y = X * scaleFactor (scalar Mul on input tensor [1, 4, 2560, 256])
     * Used strictly to test real OrtEnvironment CPU execution if external GitHub download is offline.
     */
    private fun buildMinimalUvrShapeOnnxModel(scaleFactor: Float = 0.5f): ByteArray {
        fun varint(v: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var n = v
            while (true) {
                if ((n and 0x7FL.inv()) == 0L) {
                    out.write(n.toInt())
                    break
                } else {
                    out.write(((n.toInt() and 0x7F) or 0x80))
                    n = n ushr 7
                }
            }
            return out.toByteArray()
        }

        fun tag(fieldNum: Int, wireType: Int): ByteArray = varint(((fieldNum shl 3) or wireType).toLong())
        fun bytesField(fieldNum: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(tag(fieldNum, 2))
            write(varint(payload.size.toLong()))
            write(payload)
        }.toByteArray()

        fun stringField(fieldNum: Int, s: String): ByteArray = bytesField(fieldNum, s.toByteArray(Charsets.UTF_8))
        fun int64Field(fieldNum: Int, v: Long): ByteArray = ByteArrayOutputStream().apply {
            write(tag(fieldNum, 0))
            write(varint(v))
        }.toByteArray()

        fun tensorTypeProto(elemType: Long, dims: LongArray): ByteArray {
            val shapeMsg = ByteArrayOutputStream().apply {
                for (d in dims) {
                    val dimMsg = int64Field(1, d) // Dimension.dim_value = 1
                    write(bytesField(1, dimMsg)) // TensorShapeProto.dim = 1
                }
            }.toByteArray()
            val tensorTypeMsg = ByteArrayOutputStream().apply {
                write(int64Field(1, elemType)) // elem_type = 1 (FLOAT)
                write(bytesField(2, shapeMsg)) // shape = 2
            }.toByteArray()
            return bytesField(1, tensorTypeMsg) // TypeProto.tensor_type = 1
        }

        fun valueInfo(name: String, dims: LongArray): ByteArray = ByteArrayOutputStream().apply {
            write(stringField(1, name))
            write(bytesField(2, tensorTypeProto(1L, dims)))
        }.toByteArray()

        fun floatScalarTensorProto(name: String, value: Float): ByteArray {
            val raw = java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(value)
                .array()
            return ByteArrayOutputStream().apply {
                write(int64Field(2, 1L)) // data_type = 1 (FLOAT)
                write(stringField(8, name)) // name = 8
                write(bytesField(9, raw)) // raw_data = 9
            }.toByteArray()
        }

        val dims = longArrayOf(1L, 4L, 2560L, 256L)
        val inputVi = valueInfo("input", dims)
        val outputVi = valueInfo("output", dims)
        val scaleInit = floatScalarTensorProto("scale", scaleFactor)
        val mulNode = ByteArrayOutputStream().apply {
            write(stringField(1, "input"))
            write(stringField(1, "scale"))
            write(stringField(2, "output"))
            write(stringField(4, "Mul"))
        }.toByteArray()

        val graphProto = ByteArrayOutputStream().apply {
            write(bytesField(1, mulNode)) // node = 1
            write(stringField(2, "uvr_shape_mul_test")) // name = 2
            write(bytesField(5, scaleInit)) // initializer = 5
            write(bytesField(11, inputVi)) // input = 11
            write(bytesField(12, outputVi)) // output = 12
        }.toByteArray()

        val opsetImport = ByteArrayOutputStream().apply {
            write(stringField(1, "")) // default ai.onnx domain
            write(int64Field(2, 13L)) // opset version 13
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(int64Field(1, 7L)) // ir_version = 7
            write(bytesField(8, opsetImport)) // opset_import = 8
            write(stringField(2, "OseiTest")) // producer_name = 2
            write(bytesField(7, graphProto)) // graph = 7
        }.toByteArray()
    }

    @Test
    fun testCatalogSelectsGenuineVocalInstrumentalModelsAndBlocksKara2Substitute() {
        val defaultModel = SeparationModelCatalog.defaultVocalInstrumentalModel()
        assertEquals("uvr_mdx_inst_hq_4", defaultModel.id)
        assertEquals("UVR-MDX-NET-Inst_HQ_4.onnx", defaultModel.fileName)
        assertEquals(StemMode.TWO_STEM_VOCAL_INST, defaultModel.stemMode)
        assertEquals(listOf("vocals", "instrumental"), defaultModel.outputStemOrder)
        assertEquals("instrumental", defaultModel.primaryStem)
        assertEquals(5120, defaultModel.nFft)
        assertEquals(1024, defaultModel.hopSize)
        assertEquals(2560, defaultModel.dimF)
        assertEquals(256, defaultModel.dimT)
        assertEquals(1.019f, defaultModel.compensate, 1e-4f)
        assertEquals("3c4b5b9b05090fdf238f38ba5046813982d50e2a652e9cb3324ea79720c3c9c8", defaultModel.expectedSha256)
        assertTrue(defaultModel.weightLicense.contains("MIT"))
        assertTrue(defaultModel.attributionNotice.contains("Anjok07"))
        assertTrue(defaultModel.attributionNotice.contains("KUIELab"))
        assertEquals(ModelAvailability.AVAILABLE, defaultModel.availability)

        // Evaluated alternatives Inst_HQ_3 and UVR_MDXNET_Main must carry verified specs too
        val hq3 = SeparationModelCatalog.findById("uvr_mdx_inst_hq_3")
        assertEquals("UVR-MDX-NET-Inst_HQ_3.onnx", hq3.fileName)
        assertEquals(6144, hq3.nFft)
        assertEquals(3072, hq3.dimF)
        assertEquals("instrumental", hq3.primaryStem)
        assertEquals("317554b07fe1ea5279a77f2b1520a41ea4b93432560c4ffd08792c30fddf9adc", hq3.expectedSha256)

        val main = SeparationModelCatalog.findById("uvr_mdx_main")
        assertEquals("UVR_MDXNET_Main.onnx", main.fileName)
        assertEquals(7680, main.nFft)
        assertEquals(3072, main.dimF)
        assertEquals("instrumental", main.primaryStem)
        assertEquals("8289784cda38543ff431add4070662813311a8cccfc0112ca82f76d9dba2b4ca", main.expectedSha256)

        // KARA_2 MUST NOT appear in vocalInstrumentalModels() or be usable for vocal/inst separation
        val allowedIds = SeparationModelCatalog.vocalInstrumentalModels().map { it.id }
        assertTrue(allowedIds.contains("uvr_mdx_inst_hq_4"))
        assertTrue(allowedIds.contains("uvr_mdx_inst_hq_3"))
        assertTrue(allowedIds.contains("uvr_mdx_main"))
        assertFalse("KARA_2 must never appear in vocalInstrumentalModels()", allowedIds.contains("mdx_kim_vocal_2_onnx"))

        val kara2 = SeparationModelCatalog.findById("mdx_kim_vocal_2_onnx")
        assertEquals(StemMode.KARAOKE_LEAD_BACKING, kara2.stemMode)
        assertEquals(ModelAvailability.UNAVAILABLE, kara2.availability)
    }

    @Test
    fun testMixedRadixCenteredHannStftRoundTripForAllUvrFftSizes() {
        val configs = listOf(
            Triple(5120, 1024, 2560), // UVR-MDX-NET-Inst_HQ_4
            Triple(6144, 1024, 3072), // UVR-MDX-NET-Inst_HQ_3
            Triple(7680, 1024, 3072)  // UVR_MDXNET_Main
        )

        val sampleLen = (256 - 1) * 1024 // Exact chunkFrames = 261,120 frames (~5.92s)
        val signal = FloatArray(sampleLen) { i ->
            (0.45 * sin(2.0 * PI * 440.0 * i / 44100.0) + 0.25 * sin(2.0 * PI * 1320.0 * i / 44100.0)).toFloat()
        }

        for ((nFft, hop, dimF) in configs) {
            val proc = StftProcessor(fftSize = nFft, hopSize = hop)
            assertTrue("numBins (${proc.numBins}) must be >= dimF ($dimF)", proc.numBins >= dimF)
            val (re, im, numFrames) = proc.stftCenteredHann(signal)
            assertEquals("Centered STFT frame count must match dim_t=256", 256, numFrames)

            val reconstructed = proc.istftCenteredHann(re, im, numFrames, sampleLen)
            assertEquals(sampleLen, reconstructed.size)

            var maxErr = 0f
            for (i in nFft until (sampleLen - nFft)) {
                val err = abs(signal[i] - reconstructed[i])
                if (err > maxErr) maxErr = err
            }
            assertTrue("Mixed-radix n_fft=$nFft centered-Hann round-trip error $maxErr too high", maxErr < 5e-4f)
        }
    }

    @Test
    fun testStemsSeparatedFlagStrictlyRequiresBothValidNonZeroWavOutputs() {
        val song = createRealisticMusicMixWav("strict_gate_song.wav", durationSec = 2.0f)
        val vocalsValid = createRealisticMusicMixWav("strict_vocals.wav", durationSec = 1.5f)
        val instValid = createRealisticMusicMixWav("strict_inst.wav", durationSec = 1.5f)
        val silentWav = createZeroSilentWav("strict_silent.wav", durationSec = 1.5f)
        val corruptWav = File(tempDir, "corrupt_stem.wav").apply { writeBytes(ByteArray(128) { 0x42 }) }

        val db = MusicDatabase.getDatabase(app)
        val projectId = runBlocking {
            db.musicDao().insertProject(
                MusicProjectEntity(
                    title = "Strict Gate Song",
                    artist = "Osei",
                    audioUri = Uri.fromFile(song).toString(),
                    durationMs = 2000L,
                    stemsSeparated = false
                )
            )
        }
        val baseProj = runBlocking { db.musicDao().getProjectById(projectId)!! }
        val viewModel = StudioViewModel(app)

        fun waitResult(block: ((Result<MusicProjectEntity>) -> Unit) -> Unit): Result<MusicProjectEntity> {
            var res: Result<MusicProjectEntity>? = null
            block { res = it }
            val deadline = System.currentTimeMillis() + 5000L
            while (res == null) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (System.currentTimeMillis() > deadline) fail("Timed out waiting for save callback")
                Thread.sleep(15)
            }
            return res ?: error("Missing callback result")
        }

        // 1. Missing instrumental -> MUST fail and keep stemsSeparated == false
        val onlyVocalsRes = waitResult { cb ->
            viewModel.saveSeparatedStemsToProject(baseProj, mapOf("vocals" to vocalsValid), cb)
        }
        assertTrue(onlyVocalsRes.isFailure)
        assertFalse(runBlocking { db.musicDao().getProjectById(projectId)!! }.stemsSeparated)

        // 2. Valid vocals + silent all-zero instrumental -> MUST fail and keep stemsSeparated == false
        val silentInstRes = waitResult { cb ->
            viewModel.saveSeparatedStemsToProject(baseProj, mapOf("vocals" to vocalsValid, "instrumental" to silentWav), cb)
        }
        assertTrue(silentInstRes.isFailure)
        assertFalse(runBlocking { db.musicDao().getProjectById(projectId)!! }.stemsSeparated)

        // 3. Valid vocals + corrupt instrumental -> MUST fail and keep stemsSeparated == false
        val corruptRes = waitResult { cb ->
            viewModel.saveSeparatedStemsToProject(baseProj, mapOf("vocals" to vocalsValid, "instrumental" to corruptWav), cb)
        }
        assertTrue(corruptRes.isFailure)
        assertFalse(runBlocking { db.musicDao().getProjectById(projectId)!! }.stemsSeparated)

        // 4. Both genuine non-zero WAV files -> succeeds and persists stemsSeparated == true
        val validBothRes = waitResult { cb ->
            viewModel.saveSeparatedStemsToProject(baseProj, mapOf("vocals" to vocalsValid, "instrumental" to instValid), cb)
        }
        assertTrue(validBothRes.isSuccess)
        val updated = runBlocking { db.musicDao().getProjectById(projectId)!! }
        assertTrue("Both valid non-zero outputs must set stemsSeparated=true", updated.stemsSeparated)
        assertNotNull(updated.vocalUri)
        assertNotNull(updated.instrumentalUri)
    }

    @Test
    fun testVocalSeparationUiShowsInstHq4SelectorAndAttribution() {
        val song = createRealisticMusicMixWav("ui_song.wav", durationSec = 2.0f)
        val db = MusicDatabase.getDatabase(app)
        val projectId = runBlocking {
            db.musicDao().insertProject(
                MusicProjectEntity(
                    title = "UI Check Song",
                    artist = "Osei",
                    audioUri = Uri.fromFile(song).toString(),
                    durationMs = 2000L
                )
            )
        }
        val project = runBlocking { db.musicDao().getProjectById(projectId)!! }
        val viewModel = StudioViewModel(app)
        viewModel.selectProjectForTools(project)

        composeRule.setContent {
            OseiAiMusicStudioTheme {
                VocalSeparationScreen(viewModel = viewModel, onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Default model must be UVR-MDX-NET-Inst_HQ_4 (NOT KARA_2)
        composeRule.onNodeWithTag("vocal_sep_model_name")
            .performScrollTo()
            .assertTextContains("UVR-MDX-NET-Inst_HQ_4", substring = true)

        composeRule.onNodeWithTag("vocal_sep_extract_vocals_btn")
            .performScrollTo()
            .assertIsNotEnabled()

        // Switching to Inst_HQ_3 or MDX_Main updates selected model specs
        composeRule.onNodeWithTag("model_chip_uvr_mdx_inst_hq_3")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vocal_sep_model_name")
            .assertTextContains("UVR-MDX-NET-Inst_HQ_3", substring = true)

        composeRule.onNodeWithTag("model_chip_uvr_mdx_main")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vocal_sep_model_name")
            .assertTextContains("UVR_MDXNET_Main", substring = true)
    }

    @Test
    fun testRealOnnxInferenceExecutionWithUvrPipeline(): Unit = runBlocking {
        val mixWav = createRealisticMusicMixWav("real_mix_6_5s.wav", durationSec = 6.5f)
        val engine = StemSeparatorEngine(app)
        val defaultSpec = SeparationModelCatalog.defaultVocalInstrumentalModel()
        engine.selectModel(defaultSpec)

        // Attempt consent-gated download of genuine UVR-MDX-NET-Inst_HQ_4.onnx from TRvlvr/model_repo
        val downloadRes = engine.downloadModelWithConsent()
        if (downloadRes.isSuccess) {
            val modelFile = downloadRes.getOrThrow()
            val sha = StemSeparatorEngine.computeFileSha256(modelFile)
            assertEquals(defaultSpec.expectedSha256, sha)
            assertTrue(modelFile.length() > 50_000_000L)

            val sepRes = engine.separateStems(Uri.fromFile(mixWav).toString(), projectId = 99L)
            assertTrue("Real UVR-MDX-NET-Inst_HQ_4 inference failed: ${sepRes.exceptionOrNull()}", sepRes.isSuccess)
            val result = sepRes.getOrThrow()
            val vocalsFile = result.stemFiles["vocals"]!!
            val instFile = result.stemFiles["instrumental"]!!
            val vStats = result.stemStats["vocals"]!!
            val iStats = result.stemStats["instrumental"]!!

            assertTrue(vStats.hasNonZeroAudio)
            assertTrue(iStats.hasNonZeroAudio)
            assertTrue(vStats.frameCount > 44100L * 5)
            assertTrue(iStats.frameCount > 44100L * 5)

            println(
                "REAL_UVR_INFERENCE_OK model=${defaultSpec.fileName} bytes=${modelFile.length()} " +
                    "sha256=$sha chunks=${result.benchmark.chunksProcessed} " +
                    "baselineRamMb=${result.benchmark.baselineUsedMb} afterLoadRamMb=${result.benchmark.afterModelLoadUsedMb} " +
                    "peakRamMb=${result.benchmark.peakUsedMb} elapsedMs=${result.benchmark.totalDurationMs} " +
                    "vocalsRmsDb=${vStats.rmsDbfs} instRmsDb=${iStats.rmsDbfs}"
            )
            // Verify outputs differ meaningfully (primary instrumental vs vocal subtraction)
            val vocalsHeader = WavStreamReader.tryReadHeader(vocalsFile)
            val instHeader = WavStreamReader.tryReadHeader(instFile)
            assertNotNull(vocalsHeader)
            assertNotNull(instHeader)
            assertEquals(44100, vocalsHeader!!.sampleRate)
            assertEquals(44100, instHeader!!.sampleRate)
            assertFalse(
                "Vocals and Instrumental stems must be distinct audio signals",
                vocalsFile.readBytes().contentEquals(instFile.readBytes())
            )
        } else {
            val reason = downloadRes.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message}" } ?: "unknown"
            println("REAL_UVR_MODEL_DOWNLOAD_BLOCKED reason=$reason")

            // Verify OrtEnvironment + centered STFT/iSTFT + OLA + WAV validation pipeline runs end-to-end
            // using a minimal [1,4,2560,256] ONNX Mul(scale=0.5) graph imported via StemSeparatorEngine
            val syntheticOnnx = File(tempDir, "uvr_shape_mul_test.onnx").apply {
                writeBytes(buildMinimalUvrShapeOnnxModel(scaleFactor = 0.5f))
            }
            val customSpec = SeparationModelCatalog.findById("local_custom_onnx").copy(
                primaryStem = "instrumental",
                nFft = 5120,
                hopSize = 1024,
                dimF = 2560,
                dimT = 256,
                compensate = 1.019f
            )
            engine.selectModel(customSpec)
            val importRes = engine.importLocalOnnxModel(Uri.fromFile(syntheticOnnx))
            assertTrue("ORT CPU import of minimal [1,4,2560,256] graph failed: ${importRes.exceptionOrNull()}", importRes.isSuccess)
            assertEquals(ModelStatus.READY, engine.modelStatus.value)

            val sepRes = engine.separateStems(Uri.fromFile(mixWav).toString(), projectId = 100L)
            assertTrue("ORT CPU pipeline failed: ${sepRes.exceptionOrNull()}", sepRes.isSuccess)
            val result = sepRes.getOrThrow()
            val vStats = result.stemStats["vocals"]!!
            val iStats = result.stemStats["instrumental"]!!
            assertTrue(vStats.hasNonZeroAudio)
            assertTrue(iStats.hasNonZeroAudio)
            assertEquals(ModelStatus.COMPLETED, engine.modelStatus.value)

            println(
                "ORT_CPU_PIPELINE_TEST_OK chunks=${result.benchmark.chunksProcessed} " +
                    "baselineRamMb=${result.benchmark.baselineUsedMb} peakRamMb=${result.benchmark.peakUsedMb} " +
                    "elapsedMs=${result.benchmark.totalDurationMs} " +
                    "vocalsRmsDb=${vStats.rmsDbfs} instRmsDb=${iStats.rmsDbfs}"
            )
        }
    }
}
