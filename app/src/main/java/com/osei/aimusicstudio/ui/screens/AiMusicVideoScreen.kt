package com.osei.aimusicstudio.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.osei.aimusicstudio.ui.theme.*

enum class VideoExportStatus(val label: String) {
    UNAVAILABLE("Unavailable")
}

@Composable
fun AiMusicVideoScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val context = LocalContext.current
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMimeType by remember { mutableStateOf<String?>(null) }

    val isVideoSelected = remember(selectedMediaUri, selectedMimeType) {
        val uri = selectedMediaUri ?: return@remember false
        val mime = selectedMimeType.orEmpty()
        mime.startsWith("video/") || uri.toString().lowercase().let {
            it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".webm") || it.endsWith(".mov")
        }
    }

    val exoPlayer = remember(isVideoSelected) {
        if (isVideoSelected) {
            ExoPlayer.Builder(context).build()
        } else {
            null
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    LaunchedEffect(selectedMediaUri, exoPlayer) {
        val uri = selectedMediaUri
        if (uri != null && exoPlayer != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
        }
    }

    // Zero-permission Android Photo Picker (compliant with Google Play policy)
    val visualMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedMediaUri = uri
            selectedMimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        }
    }

    val exportStatus = VideoExportStatus.UNAVAILABLE

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
                    onClick = {
                        exoPlayer?.pause()
                        onBack()
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Music Video Preview",
                    color = TextWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFF6B6B).copy(alpha = 0.18f),
                    modifier = Modifier.testTag("ai_video_status_badge")
                ) {
                    Text(
                        text = "Export: ${exportStatus.label}",
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
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.MovieCreation,
                            contentDescription = null,
                            tint = NeonPurple,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local Media Picker & Preview",
                                color = TextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Select a local video or cover image using Android Photo Picker (no storage permission needed).",
                                color = TextGrey,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                visualMediaPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("ai_video_pick_media_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = StudioBackground, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pick Video / Image", color = StudioBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        if (selectedMediaUri != null) {
                            OutlinedButton(
                                onClick = {
                                    exoPlayer?.stop()
                                    selectedMediaUri = null
                                    selectedMimeType = null
                                },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("ai_video_clear_media_btn")
                            ) {
                                Text("Clear", color = TextGrey, fontSize = 12.sp)
                            }
                        }
                    }

                    if (selectedMediaUri != null) {
                        Text(
                            text = "Selected: ${selectedMediaUri?.lastPathSegment ?: selectedMediaUri.toString()} (${selectedMimeType ?: "local media"})",
                            color = NeonBlue,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isVideoSelected && exoPlayer != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = exoPlayer
                                        useController = true
                                    }
                                },
                                update = { view ->
                                    view.player = exoPlayer
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .testTag("ai_video_player_preview")
                            )
                        } else {
                            AsyncImage(
                                model = selectedMediaUri,
                                contentDescription = "Selected cover image preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .testTag("ai_video_image_preview")
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(StudioBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No local video or image selected",
                                color = TextGrey,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Honest unavailability notice for video rendering/export
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_video_unavailable_banner"),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF2B1C2E),
                border = BorderStroke(1.dp, Color(0xFFFFB703).copy(alpha = 0.65f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFB703))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Video Export: Unavailable",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = "Music video rendering/export is unavailable because no local video encoding and audio-visual muxing pipeline (MediaCodec + MediaMuxer frame compositor) is implemented yet, and paid cloud rendering APIs are not used.",
                        color = TextGrey,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { /* Disabled: no local MediaCodec/MediaMuxer video renderer implemented */ },
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("ai_video_export_btn"),
            colors = ButtonDefaults.buttonColors(
                containerColor = NeonPurple,
                disabledContainerColor = CardSurface,
                disabledContentColor = TextGrey
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "Export Music Video (Unavailable)",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}
