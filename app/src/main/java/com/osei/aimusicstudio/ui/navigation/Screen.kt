package com.osei.aimusicstudio.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object MyMusic : Screen("my_music")
    object AiTools : Screen("ai_tools")
    object StudioMixer : Screen("studio_mixer")
    object Profile : Screen("profile")

    object AudioImport : Screen("audio_import")
    object VocalRemover : Screen("vocal_remover")
    object StemSeparator : Screen("stem_separator")
    object MusicAnalyzer : Screen("music_analyzer")
    object AudioEditor : Screen("audio_editor")
    object Karaoke : Screen("karaoke")
    object AiMusicGenerator : Screen("ai_music_generator")
    object AiMusicVideo : Screen("ai_music_video")
    object ProjectDetails : Screen("project_details/{projectId}") {
        fun createRoute(projectId: Long) = "project_details/$projectId"
    }
    object Export : Screen("export")
    object Settings : Screen("settings")
    object About : Screen("about")
}
