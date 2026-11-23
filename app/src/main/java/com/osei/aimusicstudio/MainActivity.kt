package com.osei.aimusicstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.osei.aimusicstudio.ui.navigation.AppNavGraph
import com.osei.aimusicstudio.ui.theme.OseiAiMusicStudioTheme
import com.osei.aimusicstudio.ui.viewmodel.AudioEditorViewModel
import com.osei.aimusicstudio.ui.viewmodel.KaraokeViewModel
import com.osei.aimusicstudio.ui.viewmodel.MixerViewModel
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OseiAiMusicStudioTheme {
                val studioViewModel: StudioViewModel = viewModel()
                val mixerViewModel: MixerViewModel = viewModel()
                val editorViewModel: AudioEditorViewModel = viewModel()
                val karaokeViewModel: KaraokeViewModel = viewModel()
                AppNavGraph(
                    viewModel = studioViewModel,
                    mixerViewModel = mixerViewModel,
                    editorViewModel = editorViewModel,
                    karaokeViewModel = karaokeViewModel
                )
            }
        }
    }
}
