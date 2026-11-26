package com.osei.aimusicstudio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osei.aimusicstudio.ui.theme.*
import java.io.File

enum class AiGenerationStatus(val label: String) {
    UNAVAILABLE("Unavailable"),
    CHECKING("Checking"),
    QUEUED("Queued"),
    GENERATING("Generating"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

data class AiMusicGeneratorUiState(
    val prompt: String = "",
    val status: AiGenerationStatus = AiGenerationStatus.UNAVAILABLE,
    val statusReason: String =
        "Online AI music generation is unavailable: no authorized free (GH₵0) remote generation provider or 4 GB-compatible on-device generative model is configured.",
    val generatedAudioPath: String? = null
) {
    val canGenerate: Boolean
        get() = status != AiGenerationStatus.UNAVAILABLE &&
            status != AiGenerationStatus.CHECKING &&
            status != AiGenerationStatus.QUEUED &&
            status != AiGenerationStatus.GENERATING

    val hasVerifiedAudioOutput: Boolean
        get() = status == AiGenerationStatus.COMPLETED &&
            !generatedAudioPath.isNullOrBlank() &&
            File(generatedAudioPath).let { it.exists() && it.length() > 0L }
}

object AiMusicGeneratorStateGuard {
    fun resolveInitialState(hasAuthorizedFreeProvider: Boolean = false): AiMusicGeneratorUiState {
        return if (hasAuthorizedFreeProvider) {
            AiMusicGeneratorUiState(
                status = AiGenerationStatus.CHECKING,
                statusReason = "Checking configured provider availability..."
            )
        } else {
            AiMusicGeneratorUiState()
        }
    }

    fun applyCompletedResult(
        current: AiMusicGeneratorUiState,
        audioFilePath: String?
    ): AiMusicGeneratorUiState {
        val validFile = !audioFilePath.isNullOrBlank() &&
            File(audioFilePath).let { it.exists() && it.length() > 0L }
        return if (validFile) {
            current.copy(
                status = AiGenerationStatus.COMPLETED,
                generatedAudioPath = audioFilePath,
                statusReason = "Generated audio verified on disk."
            )
        } else {
            current.copy(
                status = AiGenerationStatus.FAILED,
                generatedAudioPath = null,
                statusReason = "Generation failed: no verified audio file was produced."
            )
        }
    }
}

@Composable
fun AiMusicGeneratorScreen(
    onBack: () -> Unit,
    onImportAudio: () -> Unit = {},
    onOpenEditor: () -> Unit = {}
) {
    BackHandler { onBack() }

    var uiState by remember {
        mutableStateOf(AiMusicGeneratorStateGuard.resolveInitialState(hasAuthorizedFreeProvider = false))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI Music Generator",
                    color = TextWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFF6B6B).copy(alpha = 0.18f),
                    modifier = Modifier.testTag("ai_music_status_badge")
                ) {
                    Text(
                        text = uiState.status.label,
                        color = Color(0xFFFF6B6B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardSurface
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = NeonBlue,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Generative AI Composition",
                        color = TextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Text-to-music generation requires an authorized free remote service or large generative model weights.",
                        color = TextGrey,
                        fontSize = 13.sp
                    )
                }
            }

            // Honest unavailability notice
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_music_unavailable_banner"),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF2B1C2E),
                border = BorderStroke(1.dp, Color(0xFFFFB703).copy(alpha = 0.65f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFB703))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Status: ${uiState.status.label}",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = uiState.statusReason,
                        color = TextGrey,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        text = "Verified GH₵0 / 4 GB RAM audit:\n" +
                            "• Google Lyria (Gemini API / Vertex AI): Not on free tier; requires paid Cloud Billing / Blaze.\n" +
                            "• ElevenLabs Music API (/v1/music): Programmatic API requires a paid plan.\n" +
                            "• Hugging Face Inference: Free legacy serverless endpoint retired; audio models require PRO/paid credits.\n" +
                            "• Suno / Udio: No official public free developer API.\n" +
                            "• Mubert / Beatoven / Soundraw / Replicate: Metered or paid developer API plans.\n" +
                            "• On-device MusicGen / Stable Audio Open Small: ~400–900 MB weights & ~1.6–2.4 GB peak RAM exceed 4 GB Android phone limits.",
                        color = TextGrey,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                    Text(
                        text = "This app does not simulate progress or fabricate placeholder tracks. You can import local audio or compose using the Audio Editor and 6-Track Mixer offline.",
                        color = TextGrey,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = { uiState = uiState.copy(prompt = it) },
                enabled = uiState.canGenerate,
                label = { Text("Music Prompt (Disabled — no GH₵0 provider available)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_music_prompt_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = TextGrey,
                    disabledBorderColor = TextGrey.copy(alpha = 0.4f),
                    focusedLabelColor = NeonBlue,
                    unfocusedLabelColor = TextGrey,
                    disabledLabelColor = TextGrey.copy(alpha = 0.7f),
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    disabledTextColor = TextGrey
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onImportAudio,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_music_import_audio_btn"),
                    border = BorderStroke(1.dp, NeonBlue)
                ) {
                    Icon(Icons.Default.LibraryAdd, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import Audio", color = NeonBlue, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onOpenEditor,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_music_open_editor_btn"),
                    border = BorderStroke(1.dp, NeonPurple)
                ) {
                    Icon(Icons.Default.ContentCut, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Audio Editor", color = NeonPurple, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { /* Disabled when status == UNAVAILABLE; requires real free provider */ },
            enabled = uiState.canGenerate,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("ai_music_generate_btn"),
            colors = ButtonDefaults.buttonColors(
                containerColor = NeonBlue,
                disabledContainerColor = CardSurface,
                disabledContentColor = TextGrey
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "Generate AI Track (${uiState.status.label})",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}
