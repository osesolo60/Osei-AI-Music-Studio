package com.osei.aimusicstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osei.aimusicstudio.data.MusicProjectEntity
import com.osei.aimusicstudio.ui.components.WaveformCanvas
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.ProjectSortOrder
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel

@Composable
fun MyMusicScreen(
    viewModel: StudioViewModel,
    onNavigate: (String) -> Unit
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val currentProject by viewModel.currentPlayingProject.collectAsStateWithLifecycle()
    val isPlaying by viewModel.audioPlayerManager.isPlaying.collectAsStateWithLifecycle()
    val currentPos by viewModel.audioPlayerManager.currentPosition.collectAsStateWithLifecycle()
    val totalDur by viewModel.audioPlayerManager.duration.collectAsStateWithLifecycle()
    val playbackError by viewModel.audioPlayerManager.playbackError.collectAsStateWithLifecycle()
    val waveformData by viewModel.waveformData.collectAsStateWithLifecycle()

    var projectToRename by remember { mutableStateOf<MusicProjectEntity?>(null) }
    var newTitleInput by remember { mutableStateOf("") }
    var projectToDelete by remember { mutableStateOf<MusicProjectEntity?>(null) }

    Scaffold(
        containerColor = StudioBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigate("audio_import") },
                containerColor = NeonPurple,
                contentColor = TextWhite,
                modifier = Modifier.testTag("fab_import_music")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import Music")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Music Library",
                    color = TextWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                FilterChip(
                    selected = sortOrder == ProjectSortOrder.NAME_ASC,
                    onClick = {
                        viewModel.setSortOrder(
                            if (sortOrder == ProjectSortOrder.DATE_DESC) ProjectSortOrder.NAME_ASC
                            else ProjectSortOrder.DATE_DESC
                        )
                    },
                    label = {
                        Text(
                            text = if (sortOrder == ProjectSortOrder.DATE_DESC) "Sort: Recent" else "Sort: A-Z",
                            fontSize = 12.sp
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search projects...", color = TextGrey) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NeonBlue) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_projects_input"),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = TextGrey.copy(alpha = 0.5f),
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite
                )
            )

            // Active Playback & Real Waveform Bar
            currentProject?.let { active ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = DarkNavy
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = active.title, color = TextWhite, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(
                                    text = "${formatDuration(currentPos)} / ${formatDuration(totalDur.coerceAtLeast(active.durationMs))}",
                                    color = NeonBlue,
                                    fontSize = 12.sp
                                )
                            }
                            Row {
                                IconButton(onClick = { viewModel.togglePlayPause() }) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = NeonPurple
                                    )
                                }
                                IconButton(onClick = { viewModel.audioPlayerManager.stop() }) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = TextGrey)
                                }
                            }
                        }

                        val dur = totalDur.coerceAtLeast(active.durationMs).coerceAtLeast(1L)
                        val progress = (currentPos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)

                        waveformData?.let { wf ->
                            WaveformCanvas(
                                waveformData = wf,
                                progressFraction = progress,
                                height = 56.dp,
                                onSeekFraction = { frac ->
                                    viewModel.audioPlayerManager.seekTo((frac * dur).toLong())
                                }
                            )
                        }

                        if (playbackError != null) {
                            Text(text = playbackError!!, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                        }
                    }
                }
            }

            if (projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = TextGrey, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "No projects found", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Import audio to start producing", color = TextGrey, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectLibraryCard(
                            project = project,
                            isCurrent = currentProject?.id == project.id && isPlaying,
                            onPlay = {
                                if (currentProject?.id == project.id) {
                                    viewModel.togglePlayPause()
                                } else {
                                    viewModel.playProject(project)
                                }
                            },
                            onOpen = {
                                viewModel.selectProjectForTools(project)
                                onNavigate("project_details/${project.id}")
                            },
                            onRename = {
                                projectToRename = project
                                newTitleInput = project.title
                            },
                            onDelete = { projectToDelete = project }
                        )
                    }
                }
            }
        }
    }

    if (projectToRename != null) {
        AlertDialog(
            onDismissRequest = { projectToRename = null },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(
                    value = newTitleInput,
                    onValueChange = { newTitleInput = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = newTitleInput.trim()
                    if (trimmed.isNotEmpty()) {
                        projectToRename?.let {
                            viewModel.updateProject(it.copy(title = trimmed))
                        }
                    }
                    projectToRename = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (projectToDelete != null) {
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Delete Project") },
            text = { Text("Are you sure you want to delete '${projectToDelete?.title}'?") },
            confirmButton = {
                TextButton(onClick = {
                    projectToDelete?.let { viewModel.deleteProject(it.id) }
                    projectToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProjectLibraryCard(
    project: MusicProjectEntity,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = CardSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .size(48.dp)
                    .background(NeonPurple.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            ) {
                Icon(
                    if (isCurrent) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isCurrent) "Pause" else "Play",
                    tint = NeonPurple
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.title,
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${project.artist} • ${String.format("%.2f", project.fileSizeMb)} MB • ${formatDuration(project.durationMs)}",
                    color = TextGrey,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = TextGrey)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.8f))
            }
        }
    }
}
