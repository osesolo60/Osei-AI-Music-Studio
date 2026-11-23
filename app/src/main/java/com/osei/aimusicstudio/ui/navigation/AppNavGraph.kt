package com.osei.aimusicstudio.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.osei.aimusicstudio.ui.screens.*
import com.osei.aimusicstudio.ui.theme.*
import com.osei.aimusicstudio.ui.viewmodel.AudioEditorViewModel
import com.osei.aimusicstudio.ui.viewmodel.KaraokeViewModel
import com.osei.aimusicstudio.ui.viewmodel.MixerViewModel
import com.osei.aimusicstudio.ui.viewmodel.StudioViewModel

sealed class BottomNavItem(val route: String, val title: String, val icon: ImageVector) {
    object Home : BottomNavItem(Screen.Home.route, "Home", Icons.Default.Home)
    object MyMusic : BottomNavItem(Screen.MyMusic.route, "Library", Icons.Default.LibraryMusic)
    object AiTools : BottomNavItem(Screen.AiTools.route, "AI Tools", Icons.Default.AutoAwesome)
    object StudioMixer : BottomNavItem(Screen.StudioMixer.route, "Mixer", Icons.Default.Tune)
    object Profile : BottomNavItem(Screen.Profile.route, "Profile", Icons.Default.Person)
}

@Composable
fun AppNavGraph(
    viewModel: StudioViewModel,
    mixerViewModel: MixerViewModel,
    editorViewModel: AudioEditorViewModel,
    karaokeViewModel: KaraokeViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val projects by viewModel.projects.collectAsStateWithLifecycle()

    val bottomNavItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.MyMusic,
        BottomNavItem.AiTools,
        BottomNavItem.StudioMixer,
        BottomNavItem.Profile
    )

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = StudioBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = CardSurface,
                    contentColor = TextWhite
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.title) },
                            label = { Text(item.title) },
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = NeonBlue,
                                selectedTextColor = NeonBlue,
                                unselectedIconColor = TextGrey,
                                unselectedTextColor = TextGrey,
                                indicatorColor = CardSurface
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "splash",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("splash") {
                SplashScreen(onTimeout = {
                    navController.navigate("onboarding") {
                        popUpTo("splash") { inclusive = true }
                    }
                })
            }
            composable("onboarding") {
                OnboardingScreen(onFinish = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo("onboarding") { inclusive = true }
                    }
                })
            }
            composable(Screen.Home.route) {
                HomeScreen(projects = projects, onNavigate = { route -> navController.navigate(route) })
            }
            composable(Screen.MyMusic.route) {
                MyMusicScreen(
                    viewModel = viewModel,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(Screen.AiTools.route) {
                AiToolsScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable(Screen.StudioMixer.route) {
                StudioMixerScreen(
                    mixerViewModel = mixerViewModel,
                    studioViewModel = viewModel,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(Screen.Profile.route) {
                ProfileScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable("audio_import") {
                AudioImportScreen(
                    onImportSuccess = { project ->
                        viewModel.insertProject(project) {
                            navController.popBackStack()
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.VocalSeparation.route) {
                VocalSeparationScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.SongAnalysis.route) {
                SongAnalysisScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("vocal_remover") {
                VocalRemoverScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("stem_separator") {
                StemSeparatorScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenMixer = { navController.navigate(Screen.StudioMixer.route) }
                )
            }
            composable("music_analyzer") {
                MusicAnalyzerScreen(onBack = { navController.popBackStack() })
            }
            composable("audio_editor") {
                AudioEditorScreen(
                    editorViewModel = editorViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("karaoke") {
                KaraokeScreen(
                    karaokeViewModel = karaokeViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("ai_music_generator") {
                AiMusicGeneratorScreen(
                    onBack = { navController.popBackStack() },
                    onImportAudio = { navController.navigate("audio_import") },
                    onOpenEditor = { navController.navigate("audio_editor") }
                )
            }
            composable("ai_music_video") {
                AiMusicVideoScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.ProjectDetails.route,
                arguments = listOf(navArgument("projectId") { type = NavType.LongType })
            ) { backStackEntry ->
                val projectId = backStackEntry.arguments?.getLong("projectId") ?: 0L
                ProjectDetailsScreen(
                    projectId = projectId,
                    viewModel = viewModel,
                    onNavigate = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() },
                    onOpenAudioEditor = {
                        projects.firstOrNull { it.id == projectId }?.let { project ->
                            editorViewModel.openLibraryProjectAudio(project)
                        }
                        navController.navigate("audio_editor")
                    }
                )
            }
            composable("export") {
                ExportScreen(
                    mixerViewModel = mixerViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
