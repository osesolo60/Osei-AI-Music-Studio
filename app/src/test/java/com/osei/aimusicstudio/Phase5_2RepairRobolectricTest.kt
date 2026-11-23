package com.osei.aimusicstudio

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.osei.aimusicstudio.ai.ModelAvailability
import com.osei.aimusicstudio.ai.SeparationModelCatalog
import com.osei.aimusicstudio.data.MusicDatabase
import com.osei.aimusicstudio.data.TrackRole
import com.osei.aimusicstudio.ui.screens.AiGenerationStatus
import com.osei.aimusicstudio.ui.screens.AiMusicGeneratorScreen
import com.osei.aimusicstudio.ui.screens.AiMusicGeneratorStateGuard
import com.osei.aimusicstudio.ui.screens.AiMusicVideoScreen
import com.osei.aimusicstudio.ui.screens.StemSeparatorScreen
import com.osei.aimusicstudio.ui.theme.OseiAiMusicStudioTheme
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 5.2 UI & state verification tests using Robolectric + Compose UI Test.
 * Note: These tests verify Compose UI states, button enablement, and state guards;
 * they do not test remote network providers or physical device MediaCodec playback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase5_2RepairRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun testAiMusicGeneratorDisplaysUnavailableAndDisablesGenerateButton() {
        var importClicked = false
        var editorClicked = false

        composeRule.setContent {
            OseiAiMusicStudioTheme {
                AiMusicGeneratorScreen(
                    onBack = {},
                    onImportAudio = { importClicked = true },
                    onOpenEditor = { editorClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_music_status_badge").assertExists()
        composeRule.onNodeWithText("Unavailable").assertExists()
        composeRule.onNodeWithTag("ai_music_unavailable_banner").assertExists()
        composeRule.onNodeWithTag("ai_music_generate_btn")
            .assertExists()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag("ai_music_import_audio_btn")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("ai_music_open_editor_btn")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()

        assertTrue("Import audio shortcut should invoke navigation callback", importClicked)
        assertTrue("Open editor shortcut should invoke navigation callback", editorClicked)
    }

    @Test
    fun testAiMusicGeneratorStateGuardRejectsFakeCompletedOutputs() {
        val initial = AiMusicGeneratorStateGuard.resolveInitialState(hasAuthorizedFreeProvider = false)
        assertEquals(AiGenerationStatus.UNAVAILABLE, initial.status)
        assertFalse(initial.canGenerate)
        assertFalse(initial.hasVerifiedAudioOutput)

        // Attempting to mark completed with null or missing file must fail honestly
        val nullResult = AiMusicGeneratorStateGuard.applyCompletedResult(initial, null)
        assertEquals(AiGenerationStatus.FAILED, nullResult.status)
        assertFalse(nullResult.hasVerifiedAudioOutput)

        val missingResult = AiMusicGeneratorStateGuard.applyCompletedResult(initial, "/nonexistent/fake_track.wav")
        assertEquals(AiGenerationStatus.FAILED, missingResult.status)
        assertFalse(missingResult.hasVerifiedAudioOutput)

        // Only a real non-empty audio file on disk can satisfy verified COMPLETED output
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tempWav = File(app.cacheDir, "verified_gen_${System.currentTimeMillis()}.wav").apply {
            writeBytes(ByteArray(64))
        }
        try {
            val verified = AiMusicGeneratorStateGuard.applyCompletedResult(initial, tempWav.absolutePath)
            assertEquals(AiGenerationStatus.COMPLETED, verified.status)
            assertTrue(verified.hasVerifiedAudioOutput)
        } finally {
            tempWav.delete()
        }
    }

    @Test
    fun testAiMusicVideoDisablesUnavailableExportAndKeepsLocalPickerEnabled() {
        composeRule.setContent {
            OseiAiMusicStudioTheme {
                AiMusicVideoScreen(onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_video_status_badge").assertExists()
        composeRule.onNodeWithTag("ai_video_unavailable_banner").assertExists()
        composeRule.onNodeWithTag("ai_video_export_btn")
            .assertExists()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("ai_video_pick_media_btn")
            .assertExists()
            .assertIsEnabled()
    }

    @Test
    fun testSixInstrumentAiSeparationRemainsUnavailableInUiAndCatalog() {
        val target6Stem = SeparationModelCatalog.findById("osei_6stem_instruments_target")
        assertEquals(ModelAvailability.UNAVAILABLE, target6Stem.availability)
        assertEquals(
            listOf("drums", "bass", "guitar", "strings", "keys", "other"),
            target6Stem.outputStemOrder
        )

        val htdemucs6s = SeparationModelCatalog.findById("htdemucs_6s_onnx")
        assertEquals(
            listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
            htdemucs6s.outputStemOrder
        )

        // Six-track mixer defaults remain preserved with the 6 instrument tracks and no fake AI audio
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = MusicDatabase.getDatabase(app)
        val repo = com.osei.aimusicstudio.data.MixerRepository(db.mixerDao())
        val createdId = kotlinx.coroutines.runBlocking {
            repo.createDefaultMixerProject("Phase 5.2 Verify Mix")
        }
        val projectWithTracks = kotlinx.coroutines.runBlocking {
            repo.getProjectWithTracks(createdId)!!
        }
        assertEquals(
            listOf("Drums", "Bass", "Guitar", "Strings", "Keys", "Other"),
            projectWithTracks.tracks.map { it.name }
        )
        assertTrue(projectWithTracks.tracks.all { it.audioUri == null })

        val studioViewModel = StudioViewModel(app)
        try {
            composeRule.setContent {
                OseiAiMusicStudioTheme {
                    StemSeparatorScreen(
                        viewModel = studioViewModel,
                        onBack = {},
                        onOpenMixer = {}
                    )
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("tab_6_stem_models").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("process_6_stems_button")
                .assertExists()
                .assertIsNotEnabled()
            composeRule.onNodeWithTag("open_six_track_mixer_from_ai_btn")
                .assertExists()
                .assertIsEnabled()
        } finally {
            // Leave shared MusicDatabase singleton open for remaining tests in the sandbox
        }
    }
}
