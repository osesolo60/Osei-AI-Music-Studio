package com.osei.aimusicstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osei.aimusicstudio.ui.theme.*

@Composable
fun AiToolsScreen(onNavigate: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Professional AI Suite",
                color = TextWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Advanced audio separation, analysis & generation tools",
                color = TextGrey,
                fontSize = 14.sp
            )
        }

        item {
            AiToolItemCard(
                title = "AI Vocal Remover",
                description = "Extract high-quality instrumental and vocal tracks from any song.",
                icon = Icons.Default.MicOff,
                onClick = { onNavigate("vocal_remover") }
            )
        }

        item {
            AiToolItemCard(
                title = "AI Stem Separator (4 & 6 Stems)",
                description = "4-stem neural separation & 6-instrument model research status.",
                icon = Icons.Default.GraphicEq,
                onClick = { onNavigate("stem_separator") }
            )
        }

        item {
            AiToolItemCard(
                title = "Music Analyzer",
                description = "Detect exact BPM, musical keys, chords, and frequency waveforms.",
                icon = Icons.Default.Analytics,
                onClick = { onNavigate("music_analyzer") }
            )
        }

        item {
            AiToolItemCard(
                title = "AI Music Generator",
                description = "Create original royalty-free tracks using generative text prompts.",
                icon = Icons.Default.AutoAwesome,
                onClick = { onNavigate("ai_music_generator") }
            )
        }

        item {
            AiToolItemCard(
                title = "AI Music Video Generator",
                description = "Synchronize audio tracks with dynamic visuals and video timelines.",
                icon = Icons.Default.MovieCreation,
                onClick = { onNavigate("ai_music_video") }
            )
        }

        item {
            AiToolItemCard(
                title = "Karaoke Studio",
                description = "Practice singing with instrumental backing tracks and live recording.",
                icon = Icons.Default.MusicNote,
                onClick = { onNavigate("karaoke") }
            )
        }

        item {
            AiToolItemCard(
                title = "Audio Editor",
                description = "Trim, split, merge clips, apply DSP effects, and adjust pitch or tempo.",
                icon = Icons.Default.ContentCut,
                modifier = Modifier.testTag("ai_tools_audio_editor_card"),
                onClick = { onNavigate("audio_editor") }
            )
        }
    }
}

@Composable
fun AiToolItemCard(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = CardSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(14.dp),
                color = NeonBlue.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = TextGrey,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextGrey)
        }
    }
}
