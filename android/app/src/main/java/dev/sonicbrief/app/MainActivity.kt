package dev.sonicbrief.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.sonicbrief.app.domain.model.JobStatus
import dev.sonicbrief.app.ui.history.HistoryScreen
import dev.sonicbrief.app.ui.home.HomeScreen
import dev.sonicbrief.app.ui.home.HomeViewModel
import dev.sonicbrief.app.ui.result.JobDetailScreen
import dev.sonicbrief.app.ui.result.JobDetailViewModel
import dev.sonicbrief.app.ui.settings.SettingsScreen
import dev.sonicbrief.app.ui.settings.SettingsViewModel
import java.util.UUID

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val jobDetailViewModel: JobDetailViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            SonicBriefTheme {
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                SonicBriefApp(homeViewModel, settingsViewModel, jobDetailViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** Handles "Share → SonicBrief" from YouTube/Bilibili/etc. and from file managers. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        if (type.startsWith("audio/") || type.startsWith("video/")) {
            @Suppress("DEPRECATION")
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri != null) {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                homeViewModel.setPendingFile(uri, name)
            }
        } else {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(homeViewModel::prefillSharedText)
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val JOB = "job/{id}"
    fun job(id: UUID) = "job/$id"
}

@Composable
fun SonicBriefTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8AB4F8))
        else -> lightColorScheme(primary = Color(0xFF1A5FD0))
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun SonicBriefApp(
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    jobDetailViewModel: JobDetailViewModel,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val jobs by homeViewModel.jobs.collectAsStateWithLifecycle()
    val hasKey by homeViewModel.hasApiKey.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val detailState by jobDetailViewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            if (route != Routes.JOB) {
                NavigationBar {
                    listOf(Routes.HOME to "Home", Routes.HISTORY to "History", Routes.SETTINGS to "Settings").forEach { (target, label) ->
                        NavigationBarItem(
                            selected = route == target,
                            onClick = { navigateTop(nav, target) },
                            icon = { Text(label.take(1)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding: PaddingValues ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    state = homeState,
                    jobs = jobs,
                    hasApiKey = hasKey,
                    onUrlChange = homeViewModel::onUrlChange,
                    onPickFile = homeViewModel::setPendingFile,
                    onClearFile = homeViewModel::clearPendingFile,
                    onTranscriptLanguageChange = homeViewModel::onTranscriptLanguageChange,
                    onSummaryLanguageChange = homeViewModel::onSummaryLanguageChange,
                    onTemplateChange = homeViewModel::onTemplateChange,
                    onInstructionsChange = homeViewModel::onInstructionsChange,
                    onStart = homeViewModel::start,
                    onCancel = { homeViewModel.cancel(it.id) },
                    onRetry = homeViewModel::retry,
                    onOpenJob = { nav.navigate(Routes.job(it.id)) },
                    onOpenSettings = { navigateTop(nav, Routes.SETTINGS) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    jobs = jobs,
                    onOpenJob = { nav.navigate(Routes.job(it.id)) },
                    onRename = { job, title -> homeViewModel.rename(job.id, title) },
                    onDelete = homeViewModel::delete,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    state = settingsState,
                    onKeyInputChange = settingsViewModel::onKeyInputChange,
                    onSaveKey = settingsViewModel::saveKey,
                    onTestKey = settingsViewModel::testSavedKey,
                    onRemoveKey = settingsViewModel::removeKey,
                    onModelInputChange = settingsViewModel::onModelInputChange,
                    onSaveModel = settingsViewModel::saveModel,
                    onImportCookies = settingsViewModel::importCookies,
                    onRemoveCookies = settingsViewModel::removeCookies,
                )
            }
            composable(Routes.JOB) { entry ->
                val id = entry.arguments?.getString("id")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val job = jobs.firstOrNull { it.id == id }
                when {
                    job == null -> Box { Text("This item no longer exists.") }
                    job.status == JobStatus.ACTIVE -> Box(Modifier.padding(24.dp)) {
                        Text("${job.displayTitle}\n${job.stageDetail ?: "Working"} — ${job.progress}%")
                    }
                    else -> {
                        LaunchedEffect(job.id, job.status, job.updatedAt) { jobDetailViewModel.openJob(job) }
                        JobDetailScreen(
                            state = detailState,
                            onBack = { nav.popBackStack() },
                            onRefresh = jobDetailViewModel::refresh,
                            onSearchQueryChange = jobDetailViewModel::updateSearchQuery,
                            onPreviousSearchMatch = jobDetailViewModel::previousSearchMatch,
                            onNextSearchMatch = jobDetailViewModel::nextSearchMatch,
                            onSelectTranscript = jobDetailViewModel::selectTranscript,
                            onSegmentChange = jobDetailViewModel::updateSegment,
                            onDiscardEdits = jobDetailViewModel::discardEdits,
                            onSaveRevision = jobDetailViewModel::saveRevision,
                            onSummaryLanguageChange = jobDetailViewModel::updateSummaryLanguage,
                            onSummaryLanguageModeChange = jobDetailViewModel::updateSummaryLanguageMode,
                            onSummaryTemplateChange = jobDetailViewModel::updateSummaryTemplate,
                            onSummaryInstructionsChange = jobDetailViewModel::updateSummaryInstructions,
                            onRegenerateSummary = jobDetailViewModel::regenerateSummary,
                        )
                    }
                }
            }
        }
    }
}

private fun navigateTop(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
