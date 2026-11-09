package dev.sonicbrief.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.dp
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
import dev.sonicbrief.app.ui.theme.SonicBriefTheme
import java.util.UUID

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val jobDetailViewModel: JobDetailViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        homeViewModel.notifySharedContent()
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
    val shareRequests by homeViewModel.shareRequests.collectAsStateWithLifecycle()

    // A share from YouTube/Bilibili/a file manager always lands on Home with the content ready.
    LaunchedEffect(shareRequests) {
        if (shareRequests > 0) navigateTop(nav, Routes.HOME)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = route != Routes.JOB,
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(120)),
            ) {
                FloatingNavBar(
                    currentRoute = route,
                    onNavigate = { navigateTop(nav, it) },
                )
            }
        },
    ) { padding: PaddingValues ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
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
                    job == null -> Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("This item no longer exists.") }
                    job.status == JobStatus.ACTIVE -> Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("${job.displayTitle}\n${job.stageDetail ?: "Working"} — ${job.progress}%") }
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

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

/** Detached, pill-shaped navigation bar that floats above the content. */
@Composable
private fun FloatingNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val items = listOf(
        NavItem(Routes.HOME, "Home", Icons.Filled.Home),
        NavItem(Routes.HISTORY, "Library", Icons.Filled.List),
        NavItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp, top = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                NavBarItem(item = item, selected = selected, onClick = { onNavigate(item.route) })
            }
        }
    }
}

@Composable
private fun NavBarItem(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                } else Modifier,
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .height(46.dp)
            .padding(horizontal = if (selected) 18.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(item.icon, contentDescription = item.label, tint = contentColor, modifier = Modifier.size(21.dp))
        if (selected) {
            Text(
                item.label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
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
