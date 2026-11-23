package com.osei.aimusicstudio

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.osei.aimusicstudio.audio.SongAudioAnalyzer
import com.osei.aimusicstudio.audio.WavStreamWriter
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.ui.screens.AiToolsScreen
import com.osei.aimusicstudio.ui.screens.HomeScreen
import com.osei.aimusicstudio.ui.screens.SongAnalysisScreen
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
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Robolectric + Compose UI tests for Vocal Separation and Song Analysis menu integration,
 * local key/BPM audio analysis, Room persistence, stem saving, playback, and scoped sharing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VocalsAndSongKeyRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        clearFileProviderCache()
        app = ApplicationProvider.getApplicationContext()
        tempDir = File(app.cacheDir, "vocals_key_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        File(app.filesDir, "saved_stems").deleteRecursively()
        File(app.cacheDir, "shared_exports").deleteRecursively()
        clearFileProviderCache()
    }

    private fun clearFileProviderCache() {
        runCatching {
            val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    private fun createPulsedChordWav(
        fileName: String,
        chordFreqsHz: DoubleArray,
        bpm: Double,
        durationSec: Float = 6.0f,
        sampleRate: Int = 44100
    ): File {
        val file = File(tempDir, fileName)
        val totalFrames = (durationSec * sampleRate).toInt()
        val beatPeriodSec = 60.0 / bpm
        val buf = FloatArray(totalFrames * 2)

        for (i in 0 until totalFrames) {
            val t = i.toDouble() / sampleRate.toDouble()
            val posInBeat = t % beatPeriodSec
            // Clear rhythmic decay envelope on every beat for onset autocorrelation
            val env = 0.15 + 0.85 * exp(-posInBeat / 0.09)
            var chordSample = 0.0
            for (f in chordFreqsHz) {
                chordSample += sin(2.0 * PI * f * t) + 0.35 * sin(4.0 * PI * f * t)
            }
            val normalized = ((chordSample / (chordFreqsHz.size * 1.4)) * env * 0.65).toFloat()
            buf[i * 2] = normalized
            buf[i * 2 + 1] = normalized
        }
        WavStreamWriter(file, sampleRate, 2).use { writer ->
            writer.writeFloatSamples(buf, buf.size)
        }
        return file
    }

    private fun createWhiteNoiseWav(fileName: String, durationSec: Float = 4.0f, sampleRate: Int = 44100): File {
        val file = File(tempDir, fileName)
        val totalFrames = (durationSec * sampleRate).toInt()
        val rng = Random(42L)
        val buf = FloatArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            val s = ((rng.nextFloat() * 2f - 1f) * 0.25f)
            buf[i * 2] = s
            buf[i * 2 + 1] = s
        }
        WavStreamWriter(file, sampleRate, 2).use { writer ->
            writer.writeFloatSamples(buf, buf.size)
        }
        return file
    }

    private fun createSilentWav(fileName: String, durationSec: Float = 3.0f, sampleRate: Int = 44100): File {
        val file = File(tempDir, fileName)
        val totalFrames = (durationSec * sampleRate).toInt()
        val buf = FloatArray(totalFrames * 2)
        WavStreamWriter(file, sampleRate, 2).use { writer ->
            writer.writeFloatSamples(buf, buf.size)
        }
        return file
    }

    @Test
    fun testMainMenuAndAiToolsNavigateToVocalSeparationAndSongAnalysis() {
        val navigatedRoutes = mutableListOf<String>()

        composeRule.setContent {
            OseiAiMusicStudioTheme {
                HomeScreen(
                    projects = emptyList(),
                    onNavigate = { route -> navigatedRoutes += route }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_vocal_separation_card")
            .performScrollTo()
            .assertExists()
            .performClick()
        composeRule.onNodeWithTag("home_song_analysis_card")
            .performScrollTo()
            .assertExists()
            .performClick()
        composeRule.waitForIdle()

        assertTrue(navigatedRoutes.contains("vocal_separation"))
        assertTrue(navigatedRoutes.contains("song_analysis"))
    }

    @Test
    fun testAiToolsSectionNavigatesToVocalSeparationAndSongAnalysis() {
        val navigatedRoutes = mutableListOf<String>()

        composeRule.setContent {
            OseiAiMusicStudioTheme {
                AiToolsScreen(onNavigate = { route -> navigatedRoutes += route })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_tools_vocal_separation_card")
            .performScrollTo()
            .assertExists()
            .performClick()
        composeRule.onNodeWithTag("ai_tools_song_analysis_card")
            .performScrollTo()
            .assertExists()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("vocal_separation", "song_analysis"), navigatedRoutes)
    }

    @Test
    fun testSongAudioAnalyzerDetectsKeysAndBpmAndRefusesLowConfidenceNoiseOrSilence() = runBlocking {
        // 1) A Minor (A3=220, C4=261.63, E4=329.63) @ 120 BPM
        val aMinorWav = createPulsedChordWav(
            fileName = "aminor_120.wav",
            chordFreqsHz = doubleArrayOf(220.0, 261.63, 329.63),
            bpm = 120.0
        )
        val aMinorRes = SongAudioAnalyzer.analyzeUri(app, Uri.fromFile(aMinorWav)).getOrThrow()
        assertEquals("A Minor", aMinorRes.detectedKey)
        assertTrue("Expected reliable key confidence, got ${aMinorRes.keyConfidence}", aMinorRes.keyConfidence >= SongAudioAnalyzer.MIN_KEY_CONFIDENCE)
        assertNotNull(aMinorRes.detectedBpm)
        assertTrue("Expected ~120 BPM, got ${aMinorRes.detectedBpm}", aMinorRes.detectedBpm!! in 118..122)
        assertTrue("Expected reliable BPM confidence, got ${aMinorRes.bpmConfidence}", aMinorRes.bpmConfidence >= SongAudioAnalyzer.MIN_BPM_CONFIDENCE)

        // 2) C Major (C4=261.63, E4=329.63, G4=392.00) @ 100 BPM
        val cMajorWav = createPulsedChordWav(
            fileName = "cmajor_100.wav",
            chordFreqsHz = doubleArrayOf(261.63, 329.63, 392.0),
            bpm = 100.0
        )
        val cMajorRes = SongAudioAnalyzer.analyzeUri(app, Uri.fromFile(cMajorWav)).getOrThrow()
        assertEquals("C Major", cMajorRes.detectedKey)
        assertNotNull(cMajorRes.detectedBpm)
        assertTrue("Expected ~100 BPM, got ${cMajorRes.detectedBpm}", cMajorRes.detectedBpm!! in 98..102)

        // 3) Silence -> must NOT invent key or BPM
        val silentWav = createSilentWav("silence.wav")
        val silentRes = SongAudioAnalyzer.analyzeUri(app, Uri.fromFile(silentWav)).getOrThrow()
        assertNull("Silence must produce null key", silentRes.detectedKey)
        assertNull("Silence must produce null BPM", silentRes.detectedBpm)
        assertEquals(0f, silentRes.keyConfidence, 0.001f)
        assertEquals(0f, silentRes.bpmConfidence, 0.001f)

        // 4) Unpitched white noise -> must NOT invent musical key or steady BPM
        val noiseWav = createWhiteNoiseWav("white_noise.wav")
        val noiseRes = SongAudioAnalyzer.analyzeUri(app, Uri.fromFile(noiseWav)).getOrThrow()
        assertNull("White noise must produce null key when chroma is flat", noiseRes.detectedKey)
        assertNull("White noise must produce null BPM when rhythmic autocorrelation is flat", noiseRes.detectedBpm)
    }

    @Test
    fun testVocalSeparationRequiresRealOnnxModelAndSupportsStemPlaybackSaveAndShare() {
        val songWav = createPulsedChordWav("vocal_song.wav", doubleArrayOf(261.63, 329.63, 392.0), 120.0, 2.0f)
        val vocalsStem = createPulsedChordWav("vocals_stem.wav", doubleArrayOf(329.63), 120.0, 1.5f)
        val instStem = createPulsedChordWav("inst_stem.wav", doubleArrayOf(261.63, 392.0), 120.0, 1.5f)

        val db = MusicDatabase.getDatabase(app)
        val projectId = runBlocking {
            db.musicDao().insertProject(
                MusicProjectEntity(
                    title = "Test Vocal Track",
                    artist = "Osei",
                    audioUri = Uri.fromFile(songWav).toString(),
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

        fun waitForMainCallback(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!predicate()) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (System.currentTimeMillis() > deadline) {
                    fail("Timed out waiting for ViewModel callback")
                }
                Thread.sleep(20)
            }
        }

        // Ensure fake/missing stems cannot be saved
        val missingFile = File(tempDir, "nonexistent_stem.wav")
        var fakeSaveSucceeded: Boolean? = null
        viewModel.saveSeparatedStemPermanently(project, "vocals", missingFile) { res ->
            fakeSaveSucceeded = res.isSuccess
        }
        waitForMainCallback { fakeSaveSucceeded != null }
        assertFalse("Missing WAV must fail permanent save", fakeSaveSucceeded!!)

        // Save genuine WAV stems & verify Room persistence:
        // stemsSeparated MUST stay false after only vocals, then become true once BOTH vocals & instrumental exist
        var savedProjAfterVocals: MusicProjectEntity? = null
        viewModel.saveSeparatedStemPermanently(project, "vocals", vocalsStem) { res ->
            savedProjAfterVocals = res.getOrNull()
        }
        waitForMainCallback { savedProjAfterVocals != null }
        assertFalse("stemsSeparated must remain false until BOTH vocals and instrumental exist", savedProjAfterVocals!!.stemsSeparated)
        assertNotNull(savedProjAfterVocals!!.vocalUri)

        var savedProjAfterInst: MusicProjectEntity? = null
        viewModel.saveSeparatedStemPermanently(savedProjAfterVocals!!, "instrumental", instStem) { res ->
            savedProjAfterInst = res.getOrNull()
        }
        waitForMainCallback { savedProjAfterInst != null }
        assertTrue("stemsSeparated must become true after BOTH genuine stems pass validation", savedProjAfterInst!!.stemsSeparated)
        assertNotNull(savedProjAfterInst!!.instrumentalUri)

        var shareIntent: Intent? = null
        viewModel.prepareShareIntentForWav(vocalsStem, "Test Vocal Track_Vocals") { res ->
            shareIntent = res.getOrNull()
        }
        waitForMainCallback { shareIntent != null }
        assertEquals(Intent.ACTION_CHOOSER, shareIntent!!.action)

        composeRule.waitForIdle()

        // Without .onnx weights installed, Extract & Remove remain disabled;
        // existing separated stems enable Playback, Save, Export & Share cleanly
        composeRule.onNodeWithTag("vocal_sep_extract_vocals_btn")
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("vocal_sep_remove_vocals_btn")
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag("vocal_sep_play_vocals_btn")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("vocal_sep_save_vocals_btn")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("vocal_sep_export_vocals_btn")
            .performScrollTo()
            .assertIsEnabled()
        composeRule.onNodeWithTag("vocal_sep_share_vocals_btn")
            .performScrollTo()
            .assertIsEnabled()

        composeRule.onNodeWithTag("vocal_sep_play_instrumental_btn")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("vocal_sep_save_instrumental_btn")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
    }

    @Test
    fun testSongAnalysisScreenDetectsKeyAndBpmAllowsManualCorrectionAndSavesToRoom() {
        val wavFile = createPulsedChordWav(
            fileName = "analysis_ui_aminor_120.wav",
            chordFreqsHz = doubleArrayOf(220.0, 261.63, 329.63),
            bpm = 120.0,
            durationSec = 5.0f
        )

        val db = MusicDatabase.getDatabase(app)
        val projectId = runBlocking {
            db.musicDao().insertProject(
                MusicProjectEntity(
                    title = "Analyze Me",
                    artist = "Osei",
                    audioUri = Uri.fromFile(wavFile).toString(),
                    durationMs = 5000L
                )
            )
        }
        val project = runBlocking { db.musicDao().getProjectById(projectId)!! }
        val viewModel = StudioViewModel(app)
        viewModel.selectProjectForTools(project)

        composeRule.setContent {
            OseiAiMusicStudioTheme {
                SongAnalysisScreen(viewModel = viewModel, onBack = {})
            }
        }
        composeRule.waitForIdle()

        // Run local key & BPM detection
        composeRule.onNodeWithTag("song_analysis_run_all_btn")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("song_analysis_key_value")
                    .assertTextContains("A Minor")
                true
            }.getOrDefault(false)
        }

        // Manual key correction -> pick "G Major"
        composeRule.onNodeWithTag("song_analysis_manual_key_btn")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("key_option_G_Major")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("song_analysis_key_value")
            .assertTextContains("G Major")
        composeRule.onNodeWithTag("song_analysis_key_confidence")
            .assertTextContains("Manually Corrected")

        // Save analysis results to Room
        composeRule.onNodeWithTag("song_analysis_save_btn")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            val saved = runBlocking { db.musicDao().getProjectById(projectId) }
            saved?.musicalKey == "G Major" && (saved.bpm in 118..122)
        }

        val persisted = runBlocking { db.musicDao().getProjectById(projectId)!! }
        assertEquals("G Major", persisted.musicalKey)
        assertTrue("Expected saved BPM near 120, got ${persisted.bpm}", persisted.bpm in 118..122)
    }
}
