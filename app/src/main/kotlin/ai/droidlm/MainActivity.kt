package ai.droidlm

import ai.droidlm.auth.AuthState
import ai.droidlm.auth.AllowlistState
import ai.droidlm.di.appGraph
import ai.droidlm.execution.PendingPlan
import ai.droidlm.execution.PlannerKeySetupRequest
import ai.droidlm.execution.PlannerSetupKind
import ai.droidlm.intent.ActionUiFormatter
import ai.droidlm.logs.ActionLogEntry
import ai.droidlm.ondevice.OnDevicePlanner
import ai.droidlm.settings.DroidLmSettings
import ai.droidlm.update.DebugUpdateUiState
import ai.droidlm.update.compactDebugVersionName

import ai.droidlm.ui.DroidLmViewModel
import ai.droidlm.ui.DroidLmViewModelFactory
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private const val MAX_DEBUG_ISSUE_DESCRIPTION_CHARS = 4_000

class MainActivity : ComponentActivity() {
    private val mainActivityDeps by lazy { applicationContext.appGraph().mainActivityDeps() }
    private val viewModel: DroidLmViewModel by viewModels {
        DroidLmViewModelFactory(application, mainActivityDeps.viewModelDeps)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DroidLMTheme { DroidLmScreen(viewModel) } }
        recordLifecycle("main_activity_created", mapOf("restoredState" to (savedInstanceState != null)))
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessibility()
        viewModel.refreshOverlayPermission()
        recordLifecycle("main_activity_resumed")
    }

    override fun onPause() {
        recordLifecycle("main_activity_paused")
        super.onPause()
    }

    override fun onDestroy() {
        recordLifecycle("main_activity_destroyed", mapOf("finishing" to isFinishing, "changingConfigurations" to isChangingConfigurations))
        super.onDestroy()
    }

    private fun recordLifecycle(event: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching { mainActivityDeps.speechDiagnosticsLogger.record(null, event, fields) }
    }
}

@Composable
private fun DroidLMTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = DroidLmColors.Accent,
        onPrimary = Color.White,
        secondary = DroidLmColors.TextMuted,
        tertiary = DroidLmColors.Danger,
        background = DroidLmColors.Background,
        surface = DroidLmColors.Surface,
        onSurface = DroidLmColors.Text
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
}

private object DroidLmColors {
    val Background = Color(0xFFF6F7F9)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceAlt = Color(0xFFEFF2F6)
    val Text = Color(0xFF121417)
    val TextMuted = Color(0xFF667085)
    val Accent = Color(0xFF2563EB)
    val Danger = Color(0xFFDC2626)
    val SuccessSurface = Color(0xFFEFF8F2)
    val WarningSurface = Color(0xFFFFF7E8)
}

@Composable
private fun DroidLmScreen(viewModel: DroidLmViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState(initial = DroidLmSettings())
    val authState by viewModel.authState.collectAsState()
    val allowlistState by viewModel.allowlistState.collectAsState()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsState()
    val listening by viewModel.listeningState.collectAsState()
    val execution by viewModel.executionState.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val overlayRunning by viewModel.overlayState.collectAsState()
    val overlayGranted by viewModel.overlayPermissionGranted.collectAsState()
    val pendingPlan by viewModel.pendingPlan.collectAsState()
    val plannerKeySetup by viewModel.plannerKeySetupRequest.collectAsState()
    val onDevicePlannerStatus by viewModel.onDevicePlannerStatus.collectAsState()
    val needsOnboarding = settings.onboardingCompletedVersion < DroidLmViewModel.ONBOARDING_VERSION || !authState.signedIn || !allowlistState.allowed

    var showSettings by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micGranted = granted }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notificationGranted = granted }
    var pendingOverlayStart by remember { mutableStateOf(false) }
    var overlayPermissionNeedsRetry by remember { mutableStateOf(false) }
    var showOverlayPermissionInstructions by remember { mutableStateOf(false) }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = viewModel.refreshOverlayPermission()
        if (granted) {
            overlayPermissionNeedsRetry = false
            if (pendingOverlayStart) viewModel.startOverlay()
        } else if (pendingOverlayStart) {
            overlayPermissionNeedsRetry = true
        }
        pendingOverlayStart = false
    }

    fun startListeningWithPermission() {
        when {
            !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !notificationGranted && Build.VERSION.SDK_INT >= 33 -> notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.startListening()
        }
    }

    fun pushToTalkWithPermission() {
        when {
            !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !notificationGranted && Build.VERSION.SDK_INT >= 33 -> notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.pushToTalk()
        }
    }

    fun requestOverlayPermission() {
        pendingOverlayStart = true
        overlayPermissionNeedsRetry = false
        showOverlayPermissionInstructions = true
    }

    fun openOverlayPermissionSettings() {
        pendingOverlayStart = true
        showOverlayPermissionInstructions = false
        overlayLauncher.launch(overlayPermissionIntent(context))
    }

    fun startOverlayWithPermission() {
        val granted = viewModel.refreshOverlayPermission()
        if (granted) {
            pendingOverlayStart = false
            overlayPermissionNeedsRetry = false
            viewModel.startOverlay()
        } else {
            requestOverlayPermission()
        }
    }

    LaunchedEffect(overlayGranted) {
        if (overlayGranted) {
            overlayPermissionNeedsRetry = false
            if (pendingOverlayStart) {
                pendingOverlayStart = false
                viewModel.startOverlay()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAccessibility()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(DroidLmColors.Background, DroidLmColors.SurfaceAlt)
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!showSettings) {
                item { Header() }
            }
            if (needsOnboarding) {
                item {
                    OnboardingPage(
                        settings = settings,
                        authState = authState,
                        allowlistState = allowlistState,
                        viewModel = viewModel,
                        plannerKeySetup = plannerKeySetup,
                        onDevicePlannerStatus = onDevicePlannerStatus,
                        accessibilityEnabled = accessibilityEnabled,
                        micGranted = micGranted,
                        notificationGranted = notificationGranted,
                        onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onRequestMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onSaveOpenAiKey = viewModel::saveOpenAiApiKey,
                        onClearOpenAiKey = viewModel::clearOpenAiApiKey,
                        onDismissPlannerKeySetup = viewModel::dismissPlannerKeySetup,
                        onSignInWithGoogle = { viewModel.signInWithGoogle(context) },
                        onSignInWithEmail = viewModel::signInWithEmail,
                        onCreateAccountWithEmail = viewModel::createAccountWithEmail,
                        onSendPasswordReset = viewModel::sendPasswordReset,
                        onDone = viewModel::completeOnboarding,
                        onOpenSettings = {
                            viewModel.completeOnboarding()
                            showSettings = true
                        }
                    )
                }
            } else if (showSettings) {
                item {
                    SettingsPage(
                        settings = settings,
                        authState = authState,
                        allowlistState = allowlistState,
                        viewModel = viewModel,
                        plannerKeySetup = plannerKeySetup,
                        onDevicePlannerStatus = onDevicePlannerStatus,
                        accessibilityEnabled = accessibilityEnabled,
                        micGranted = micGranted,
                        notificationGranted = notificationGranted,
                        onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onRequestMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onSaveOpenAiKey = viewModel::saveOpenAiApiKey,
                        onClearOpenAiKey = viewModel::clearOpenAiApiKey,
                        onDismissPlannerKeySetup = viewModel::dismissPlannerKeySetup,
                        onSignInWithGoogle = { viewModel.signInWithGoogle(context) },
                        onSignInWithEmail = viewModel::signInWithEmail,
                        onCreateAccountWithEmail = viewModel::createAccountWithEmail,
                        onSendPasswordReset = viewModel::sendPasswordReset,
                        onSignOut = viewModel::signOut,
                    )
                }
            } else {
                plannerKeySetup?.let { setup ->
                    item {
                        if (setup.kind == PlannerSetupKind.ON_DEVICE_MODEL) {
                            PrivacyPlannerSettingsCard(
                                plannerKeySetup = setup,
                                plannerStatus = onDevicePlannerStatus,
                                onDownload = viewModel::downloadPrivacyModel,
                                onPrepare = viewModel::preparePrivacyModel,
                                onDismiss = viewModel::dismissPlannerKeySetup
                            )
                        } else {
                            PlannerSettingsCard(
                                settings = settings,
                                plannerKeySetup = setup,
                                onSave = viewModel::saveOpenAiApiKey,
                                onClear = viewModel::clearOpenAiApiKey,
                                onCancel = viewModel::dismissPlannerKeySetup
                            )
                        }
                    }
                }
                pendingPlan?.let { plan ->
                    item {
                        PlanPreviewCard(
                            pendingPlan = plan,
                            onAcceptOnce = { viewModel.acceptPendingPlan(false) },
                            onAlwaysAcceptSafe = { viewModel.acceptPendingPlan(true) },
                            onReject = viewModel::rejectPendingPlan
                        )
                    }
                }
                pendingConfirmation?.let { pending ->
                    item {
                        ConfirmationCard(
                            transcript = pending.transcript,
                            action = pending.actionLabel,
                            reason = pending.reason,
                            prompt = pending.prompt,
                            onConfirm = viewModel::confirmPending,
                            onCancel = viewModel::rejectPending
                        )
                    }
                }

                item { ExecutionCard(execution.lastTranscript, execution.parsedAction, execution.status, execution.lastResult) }
                item {
                    MainControlRow(
                        listening = listening,
                        showingSettings = showSettings,
                        overlayRunning = overlayRunning,
                        onListeningToggle = { if (listening) viewModel.stopListening() else startListeningWithPermission() },
                        onPushToTalk = ::pushToTalkWithPermission,
                        onCancel = viewModel::cancelCurrentTask,
                        onSettings = { showSettings = !showSettings },
                        onOverlayToggle = { if (overlayRunning) viewModel.stopOverlay() else startOverlayWithPermission() }
                    )
                }
            }
        }
        if (showOverlayPermissionInstructions) {
            OverlayPermissionGuideDialog(
                onOpenSettings = ::openOverlayPermissionSettings,
                onDismiss = { showOverlayPermissionInstructions = false }
            )
        }


    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("DroidLM", fontSize = 38.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, color = DroidLmColors.Text)
    }
}

@Composable
private fun MainControlRow(
    listening: Boolean,
    showingSettings: Boolean,
    overlayRunning: Boolean,
    onListeningToggle: () -> Unit,
    onPushToTalk: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
    onOverlayToggle: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onListeningToggle) { Text(if (listening) "Stop Listening" else "Start Listening") }
        Button(onClick = onPushToTalk) { Text("Push to Talk") }
        Button(
            colors = ButtonDefaults.buttonColors(containerColor = DroidLmColors.Danger, contentColor = Color.White),
            onClick = onCancel
        ) { Text("Cancel") }
        Button(onClick = onOverlayToggle) { Text(if (overlayRunning) "Stop Floating Controls" else "Start Floating Controls") }
        OutlinedButton(onClick = onSettings) { Text(if (showingSettings) "Close Settings" else "Settings") }
    }
}

@Composable
private fun OnboardingPage(
    settings: DroidLmSettings,
    authState: AuthState,
    allowlistState: AllowlistState,
    viewModel: DroidLmViewModel,
    plannerKeySetup: PlannerKeySetupRequest?,
    onDevicePlannerStatus: OnDevicePlanner.Status,
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    notificationGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSaveOpenAiKey: (String) -> Unit,
    onClearOpenAiKey: () -> Unit,
    onDismissPlannerKeySetup: () -> Unit,
    onSignInWithGoogle: () -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onCreateAccountWithEmail: (String, String) -> Unit,
    onSendPasswordReset: (String) -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showPlannerSetupDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DroidCard {
            Text("Let's set up DroidLM", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 24.sp)
            Text(
                "Complete the essentials once. DroidLM handles speech recognition automatically; advanced planning can use either OpenAI or the local privacy-mode planner.",
                color = DroidLmColors.TextMuted
            )
        }
        AccountCard(
            title = "Account",
            description = "Sign in once so DroidLM can identify your device session and keep onboarding secure.",
            authState = authState,
            allowlistState = allowlistState,
            onSignInWithGoogle = onSignInWithGoogle,
            onSignInWithEmail = onSignInWithEmail,
            onCreateAccountWithEmail = onCreateAccountWithEmail,
            onSendPasswordReset = onSendPasswordReset,
        )
        SetupStatusCard(
            accessibilityEnabled = accessibilityEnabled,
            micGranted = micGranted,
            notificationGranted = notificationGranted,
            settings = settings,
            onOpenAccessibility = onOpenAccessibility,
            onRequestMicPermission = onRequestMicPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onOpenAiKey = if (settings.privacyModeEnabled) null else ({ showPlannerSetupDialog = true })
        )

        DroidCard {
            Text("Diagnostics", fontWeight = FontWeight.SemiBold)
            ToggleRow("Debug logging", settings.debugLoggingEnabled, viewModel::updateDebugLogging)
            Text("Optional, but useful for diagnosing voice, overlay, and automation issues. Exports are zipped for sharing.", color = DroidLmColors.TextMuted)
        }
        DroidCard {
            val accountReady = authState.signedIn && allowlistState.allowed
            Button(onClick = onDone, enabled = accountReady) { Text("Start using DroidLM") }
            OutlinedButton(onClick = onOpenSettings, enabled = accountReady) { Text("Review all settings") }
            Text(accountSetupHint(authState, allowlistState), color = DroidLmColors.TextMuted)
        }
    }

    if (showPlannerSetupDialog) {
        if (settings.privacyModeEnabled) {
            PrivacyModelDialog(
                plannerKeySetup = plannerKeySetup,
                plannerStatus = onDevicePlannerStatus,
                onDownload = viewModel::downloadPrivacyModel,
                onPrepare = viewModel::preparePrivacyModel,
                onDismiss = { showPlannerSetupDialog = false }
            )
        } else {
            OpenAiKeyDialog(
                settings = settings,
                plannerKeySetup = plannerKeySetup,
                onSave = onSaveOpenAiKey,
                onClear = onClearOpenAiKey,
                onCancel = onDismissPlannerKeySetup,
                onDismiss = { showPlannerSetupDialog = false }
            )
        }
    }
}


@Composable
private fun SettingsPage(
    settings: DroidLmSettings,
    authState: AuthState,
    allowlistState: AllowlistState,
    viewModel: DroidLmViewModel,
    plannerKeySetup: PlannerKeySetupRequest?,
    onDevicePlannerStatus: OnDevicePlanner.Status,
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    notificationGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSaveOpenAiKey: (String) -> Unit,
    onClearOpenAiKey: () -> Unit,
    onDismissPlannerKeySetup: () -> Unit,
    onSignInWithGoogle: () -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onCreateAccountWithEmail: (String, String) -> Unit,
    onSendPasswordReset: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    var showPlannerSetupDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 24.sp)
        AccountCard(
            title = "Account",
            description = "DroidLM uses this account to identify you across setup and cloud-backed features.",
            authState = authState,
            allowlistState = allowlistState,
            onSignInWithGoogle = onSignInWithGoogle,
            onSignInWithEmail = onSignInWithEmail,
            onCreateAccountWithEmail = onCreateAccountWithEmail,
            onSendPasswordReset = onSendPasswordReset,
            onSignOut = onSignOut
        )
        SetupStatusSection(
            accessibilityEnabled = accessibilityEnabled,
            micGranted = micGranted,
            notificationGranted = notificationGranted,
            settings = settings,
            onOpenAccessibility = onOpenAccessibility,
            onRequestMicPermission = onRequestMicPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onOpenAiKey = if (settings.privacyModeEnabled) null else ({ showPlannerSetupDialog = true })
        )
        SettingsSectionDivider()
        AssistantSettingsSection(settings, onDevicePlannerStatus, viewModel)
        VersionFooter()
    }

    if (showPlannerSetupDialog) {
        if (settings.privacyModeEnabled) {
            PrivacyModelDialog(
                plannerKeySetup = plannerKeySetup,
                plannerStatus = onDevicePlannerStatus,
                onDownload = viewModel::downloadPrivacyModel,
                onPrepare = viewModel::preparePrivacyModel,
                onDismiss = { showPlannerSetupDialog = false }
            )
        } else {
            OpenAiKeyDialog(
                settings = settings,
                plannerKeySetup = plannerKeySetup,
                onSave = onSaveOpenAiKey,
                onClear = onClearOpenAiKey,
                onCancel = onDismissPlannerKeySetup,
                onDismiss = { showPlannerSetupDialog = false }
            )
        }
    }
}

@Composable
private fun VersionFooter() {
    val context = LocalContext.current
    val versionName = remember(context.packageName) { appVersionName(context) }
    Text(
        "Version $versionName",
        modifier = Modifier.fillMaxWidth(),
        color = DroidLmColors.TextMuted,
        fontSize = 13.sp
    )
}

private enum class EmailAuthMode {
    SIGN_IN,
    CREATE
}

@Composable
private fun AccountCard(
    title: String,
    description: String,
    authState: AuthState,
    allowlistState: AllowlistState,
    onSignInWithGoogle: () -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onCreateAccountWithEmail: (String, String) -> Unit,
    onSendPasswordReset: (String) -> Unit,
    onSignOut: (() -> Unit)? = null
) {
    var showEmailDialog by remember { mutableStateOf(false) }

    LaunchedEffect(authState.signedIn) {
        if (authState.signedIn) showEmailDialog = false
    }

    DroidCard {
        Text(title, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
        Text(description, color = DroidLmColors.TextMuted)
        AccountStatus(authState, allowlistState)
        if (authState.signedIn) {
            onSignOut?.let {
                OutlinedButton(onClick = it, enabled = !authState.loading) { Text("Sign out") }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSignInWithGoogle,
                    enabled = authState.configured && !authState.loading
                ) { Text("Continue with Google") }
                OutlinedButton(
                    onClick = { showEmailDialog = true },
                    enabled = authState.configured && !authState.loading
                ) { Text("Continue with Email") }
            }
        }
        authState.message?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, color = DroidLmColors.TextMuted)
        }
    }

    if (showEmailDialog) {
        EmailPasswordDialog(
            authState = authState,
            onSignIn = onSignInWithEmail,
            onCreateAccount = onCreateAccountWithEmail,
            onSendPasswordReset = onSendPasswordReset,
            onDismiss = { showEmailDialog = false }
        )
    }
}

@Composable
private fun AccountStatus(authState: AuthState, allowlistState: AllowlistState) {
    val user = authState.user
    if (user == null) {
        Text(if (authState.configured) "Not signed in" else "Sign-in is not configured", fontWeight = FontWeight.SemiBold)
    } else {
        Text("Signed in as ${user.displayLabel}", fontWeight = FontWeight.SemiBold)
        user.email?.takeIf { it.isNotBlank() }?.let { email ->
            Text(email, color = DroidLmColors.TextMuted)
        }
        Text(accountSetupHint(authState, allowlistState), color = if (allowlistState.allowed) Color(0xFF166534) else DroidLmColors.TextMuted)
    }
}

private fun accountSetupHint(authState: AuthState, allowlistState: AllowlistState): String = when {
    !authState.signedIn -> "Sign in to continue."
    authState.user?.emailVerified == false -> "Verify your email address before using DroidLM."
    allowlistState.checking -> "Checking allowlist access..."
    allowlistState.allowed -> "Access approved."
    else -> allowlistState.message ?: "This account is not on the DroidLM allowlist."
}


@Composable
private fun EmailPasswordDialog(
    authState: AuthState,
    onSignIn: (String, String) -> Unit,
    onCreateAccount: (String, String) -> Unit,
    onSendPasswordReset: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(EmailAuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val creating = mode == EmailAuthMode.CREATE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "Create account" else "Sign in with email") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Email") }
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Password") }
                )
                if (creating) {
                    Text("Use at least 6 characters.", color = DroidLmColors.TextMuted)
                }
                TextButton(onClick = { mode = if (creating) EmailAuthMode.SIGN_IN else EmailAuthMode.CREATE }) {
                    Text(if (creating) "Already have an account? Sign in" else "Need an account? Create one")
                }
                TextButton(onClick = { onSendPasswordReset(email) }, enabled = !authState.loading) {
                    Text("Send password reset email")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (creating) onCreateAccount(email, password) else onSignIn(email, password)
                },
                enabled = !authState.loading
            ) { Text(if (creating) "Create account" else "Sign in") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SetupStatusCard(
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    notificationGranted: Boolean,
    settings: DroidLmSettings,
    onOpenAccessibility: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAiKey: (() -> Unit)? = null
) = DroidCard {
    SetupStatusSection(
        accessibilityEnabled = accessibilityEnabled,
        micGranted = micGranted,
        notificationGranted = notificationGranted,
        settings = settings,
        onOpenAccessibility = onOpenAccessibility,
        onRequestMicPermission = onRequestMicPermission,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenAiKey = onOpenAiKey
    )
}

@Composable
private fun SetupStatusSection(
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    notificationGranted: Boolean,
    settings: DroidLmSettings,
    onOpenAccessibility: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAiKey: (() -> Unit)? = null
) {
    val items = listOfNotNull(
        SetupStatusItem("Accessibility", accessibilityEnabled, onOpenAccessibility),
        SetupStatusItem("Microphone", micGranted, onRequestMicPermission),
        SetupStatusItem("Notifications", notificationGranted, onRequestNotificationPermission),
        onOpenAiKey?.let { SetupStatusItem("API Key", settings.openAiApiKeyConfigured, it) }
    )
    val enabledItems = items.filter { it.enabled }
    val missingItems = items.filterNot { it.enabled }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Setup status", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
        SetupStatusRow("Enabled", enabledItems)
        SetupStatusRow("Missing", missingItems)
    }
}

private data class SetupStatusItem(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun SetupStatusRow(label: String, items: List<SetupStatusItem>) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$label (${items.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) {
                    Text("None", color = DroidLmColors.TextMuted)
                } else {
                    items.forEach { item ->
                        AssistChip(onClick = item.onClick, label = { Text(item.label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DroidLmColors.TextMuted.copy(alpha = 0.18f))
    )
}

@Composable
private fun OverlayPermissionGuideDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Enable floating controls") },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android requires this one setting before DroidLM can show the floating record button over other apps.")
            Text("1. Tap Open Android settings.")
            Text("2. Find Allow display over other apps and turn it on.")
            Text("3. Return to DroidLM; the controls start automatically.")
        }
    },
    confirmButton = {
        Button(onClick = onOpenSettings) { Text("Open Android settings") }
    },
    dismissButton = {
        OutlinedButton(onClick = onDismiss) { Text("Not now") }
    }
)

@Composable
private fun AdvancedControlsCard(
    onOpenAccessibility: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onTestOcr: () -> Unit,
    onOpenOverlayPermission: () -> Unit
) = DroidCard {
    Text("Advanced controls", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpenAccessibility) { Text("Open Accessibility Settings") }
        OutlinedButton(onClick = onOpenAppSettings) { Text("Open App Settings") }
        OutlinedButton(onClick = onTestOcr) { Text("Test OCR") }
        OutlinedButton(onClick = onOpenOverlayPermission) { Text("Open Overlay Permission") }
    }
}

@Composable
private fun ConfirmationCard(
    transcript: String,
    action: String,
    reason: String,
    prompt: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) = DroidCard(container = DroidLmColors.WarningSurface) {
    Text("Confirmation required", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Transcript: $transcript")
    Text("Action: $action")
    Text("Reason: $reason")
    if (prompt.isNotBlank()) Text(prompt, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onConfirm) { Text("\u2713", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        OutlinedButton(onClick = onCancel) { Text("X", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun OpenAiKeyDialog(
    settings: DroidLmSettings,
    plannerKeySetup: PlannerKeySetupRequest?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenAI API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status: ${if (settings.openAiApiKeyConfigured) "Configured" else "Not configured"}")
                plannerKeySetup?.let { Text(it.message, color = DroidLmColors.TextMuted) }
                if (!settings.openAiApiKeyConfigured) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OpenAI API key") }
                    )
                }
            }
        },
        confirmButton = {
            if (!settings.openAiApiKeyConfigured) {
                Button(onClick = {
                    onSave(apiKey)
                    apiKey = ""
                    onDismiss()
                }) { Text("Save Key") }
            } else {
                OutlinedButton(onClick = onClear) { Text("Clear Key") }
            }
        },
        dismissButton = {
            if (!settings.openAiApiKeyConfigured) {
                OutlinedButton(onClick = {
                    onCancel()
                    onDismiss()
                }) { Text("Cancel") }
            } else {
                OutlinedButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}


@Composable
private fun PlannerSettingsCard(
    settings: DroidLmSettings,
    plannerKeySetup: PlannerKeySetupRequest?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit
) = DroidCard(container = DroidLmColors.WarningSurface) {
    var apiKey by remember { mutableStateOf("") }
    Text("OpenAI API key", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Status: ${if (settings.openAiApiKeyConfigured) "Configured" else "Not configured"}")
    plannerKeySetup?.let { Text(it.message, color = DroidLmColors.TextMuted) }
    if (!settings.openAiApiKeyConfigured) {
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("OpenAI API key") })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(apiKey); apiKey = "" }) { Text("Save Key") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    } else {
        OutlinedButton(onClick = onClear) { Text("Clear Key") }
    }
}

@Composable
private fun PrivacyModelDialog(
    plannerKeySetup: PlannerKeySetupRequest?,
    plannerStatus: OnDevicePlanner.Status,
    onDownload: () -> Unit,
    onPrepare: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("On-device planner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status: ${plannerStatusLabel(plannerStatus)}")
                DownloadProgressDetails(
                    progressFraction = plannerStatus.progressFraction,
                    progressLabel = plannerStatus.progressLabel
                )
                plannerKeySetup?.message?.takeIf { it.isNotBlank() }?.let { Text(it, color = DroidLmColors.TextMuted) }
                Text(
                    "Privacy mode uses a local Qwen3 1.7B model on supported flagship phones. Download size is about 1.8 GB.",
                    color = DroidLmColors.TextMuted
                )
            }
        },
        confirmButton = {
            when {
                plannerStatusShowsDownload(plannerStatus) -> Button(onClick = onDownload) { Text("Download Qwen3") }
                plannerStatusCanPrepare(plannerStatus) -> Button(onClick = onPrepare) { Text("Prepare") }
                else -> Button(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PrivacyPlannerSettingsCard(
    plannerKeySetup: PlannerKeySetupRequest?,
    plannerStatus: OnDevicePlanner.Status,
    onDownload: () -> Unit,
    onPrepare: () -> Unit,
    onDismiss: () -> Unit
) = DroidCard(container = DroidLmColors.WarningSurface) {
    Text("On-device planner", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Status: ${plannerStatusLabel(plannerStatus)}")
    DownloadProgressDetails(
        progressFraction = plannerStatus.progressFraction,
        progressLabel = plannerStatus.progressLabel
    )
    plannerKeySetup?.let { Text(it.message, color = DroidLmColors.TextMuted) }
    Text(
        "Privacy mode uses a local Qwen3 1.7B planner on supported flagship phones.",
        color = DroidLmColors.TextMuted
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            plannerStatusShowsDownload(plannerStatus) -> Button(onClick = onDownload) { Text("Download Qwen3") }
            plannerStatusCanPrepare(plannerStatus) -> Button(onClick = onPrepare) { Text("Prepare") }
        }
        OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

private fun plannerStatusLabel(status: OnDevicePlanner.Status): String = status.message

@Composable
private fun DownloadProgressDetails(
    progressFraction: Float?,
    progressLabel: String?,
    textColor: Color = DroidLmColors.TextMuted
) {
    if (progressFraction == null && progressLabel == null) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        progressFraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        progressLabel?.let { Text(it, color = textColor) }
    }
}

private fun plannerStatusShowsDownload(status: OnDevicePlanner.Status): Boolean = when (status.phase) {
    OnDevicePlanner.Status.Phase.NOT_DOWNLOADED,
    OnDevicePlanner.Status.Phase.ERROR -> true
    else -> false
}

private fun plannerStatusCanPrepare(status: OnDevicePlanner.Status): Boolean =
    status.phase == OnDevicePlanner.Status.Phase.DOWNLOADED


@Composable
private fun PlanPreviewCard(
    pendingPlan: PendingPlan,
    onAcceptOnce: () -> Unit,
    onAlwaysAcceptSafe: () -> Unit,
    onReject: () -> Unit
) = DroidCard(container = DroidLmColors.SuccessSurface) {
    val plan = pendingPlan.plan
    Text("Plan preview", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Transcript: ${pendingPlan.transcript}")
    Text("Risk: ${plan.riskLevel}")
    Text(plan.summary, fontWeight = FontWeight.SemiBold)
    plan.steps.forEach { step ->
        val actionLabel = ActionUiFormatter.full(step.action, step.actionLabel, step.reason)
        Text("${step.index}. $actionLabel")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onAcceptOnce) { Text("Accept Once") }
        if (plan.isSafe) Button(onClick = onAlwaysAcceptSafe) { Text("Always Accept Safe") }
        OutlinedButton(onClick = onReject) { Text("Reject") }
    }
}

@Composable
private fun ExecutionCard(transcript: String, action: String, status: String, result: String) = DroidCard {
    Text("Execution", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)
    Text("Status: $status")
    transcript.takeIf { it.isNotBlank() }?.let { Text("Last transcript: $it") }
    action.takeIf { it.isNotBlank() }?.let { Text("Parsed action: $it") }
    result.takeIf { it.isNotBlank() }?.let { Text("Result: $it") }
}



@Composable
private fun AssistantSettingsSection(
    settings: DroidLmSettings,
    onDevicePlannerStatus: OnDevicePlanner.Status,
    viewModel: DroidLmViewModel
) {
    val saveDebugLogsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let(viewModel::saveDebugLogsToUri)
    }
    val debugUpdateState by viewModel.debugUpdateState.collectAsState()
    val debugInstallPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.resumePendingDebugBuildInstall()
    }

    var showDebugShareDialog by remember { mutableStateOf(false) }
    var debugIssueDescription by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Assistant settings", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 20.sp)

        Text("Privacy", fontWeight = FontWeight.SemiBold)
        ToggleRow("Privacy mode", settings.privacyModeEnabled, viewModel::updatePrivacyMode)
        Text(
            "When enabled, DroidLM keeps advanced planning fully on-device with a local Qwen3 1.7B model on supported flagship phones.",
            color = DroidLmColors.TextMuted
        )
        Text("Local planner: ${plannerStatusLabel(onDevicePlannerStatus)}", color = DroidLmColors.TextMuted)
        DownloadProgressDetails(
            progressFraction = onDevicePlannerStatus.progressFraction,
            progressLabel = onDevicePlannerStatus.progressLabel,
            textColor = DroidLmColors.TextMuted
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plannerStatusShowsDownload(onDevicePlannerStatus)) {
                OutlinedButton(onClick = viewModel::downloadPrivacyModel) { Text("Download Qwen3") }
            }
            if (plannerStatusCanPrepare(onDevicePlannerStatus)) {
                OutlinedButton(onClick = viewModel::preparePrivacyModel) { Text("Prepare") }
            }
        }

        Text("Diagnostics", fontWeight = FontWeight.SemiBold)
        ToggleRow("Debug logging", settings.debugLoggingEnabled, viewModel::updateDebugLogging)
        Text(
            "When enabled, DroidLM keeps speech diagnostic events plus retained debug audio and screenshots.",
            color = DroidLmColors.TextMuted
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDebugShareDialog = true }) { Text("Upload") }
            OutlinedButton(onClick = { saveDebugLogsLauncher.launch(viewModel.debugLogsExportFileName()) }) { Text("Save") }
            OutlinedButton(onClick = viewModel::clearDebugLogs) { Text("Clear") }
        }
        if (showDebugShareDialog) {
            AlertDialog(
                onDismissRequest = { showDebugShareDialog = false },
                title = { Text("Describe the issue") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Add any details that would help diagnose what happened. This description will be saved inside the uploaded zip.",
                            color = DroidLmColors.TextMuted
                        )
                        OutlinedTextField(
                            value = debugIssueDescription,
                            onValueChange = { debugIssueDescription = it.take(MAX_DEBUG_ISSUE_DESCRIPTION_CHARS) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("What were you experiencing?") },
                            minLines = 4,
                            maxLines = 8
                        )
                        Text(
                            "${debugIssueDescription.length}/$MAX_DEBUG_ISSUE_DESCRIPTION_CHARS characters",
                            color = DroidLmColors.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.shareDebugLogs(debugIssueDescription)
                            showDebugShareDialog = false
                            debugIssueDescription = ""
                        }
                    ) { Text("Upload logs") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showDebugShareDialog = false }) { Text("Cancel") }
                }
            )
        }
        if (debugUpdateState.visible) {
            DebugBuildUpgradeSection(
                state = debugUpdateState,
                onUpgrade = viewModel::upgradeToLatestDebugBuild,
                onAllowInstall = {
                    debugInstallPermissionLauncher.launch(viewModel.debugBuildInstallPermissionIntent())
                }
            )
        }
    }
}

@Composable
private fun DebugBuildUpgradeSection(
    state: DebugUpdateUiState,
    onUpgrade: () -> Unit,
    onAllowInstall: () -> Unit
) {
    val buttonLabel = when {
        state.requiresInstallPermission -> "Allow Install"
        state.isBusy -> "Working..."
        else -> "Upgrade to Latest Debug Build"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !state.isBusy,
            onClick = if (state.requiresInstallPermission) onAllowInstall else onUpgrade,
            colors = ButtonDefaults.buttonColors(containerColor = DroidLmColors.Accent)
        ) {
            Text(buttonLabel)
        }
        state.statusMessage?.let { Text(it, color = DroidLmColors.TextMuted) }
        DownloadProgressDetails(
            progressFraction = state.progressFraction,
            progressLabel = state.progressLabel,
            textColor = DroidLmColors.TextMuted
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogRow(entry: ActionLogEntry) = DroidCard(container = DroidLmColors.Surface) {
    Text(entry.type.name, fontWeight = FontWeight.Bold, color = DroidLmColors.Accent)
    Text(entry.message)
    entry.details?.let { Text(it, color = DroidLmColors.TextMuted) }
}

@Composable
private fun DroidCard(container: Color = DroidLmColors.Surface, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun appVersionName(context: Context): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return compactDebugVersionName(packageInfo.versionName) ?: "unknown"
}

private fun overlayPermissionIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))


private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
