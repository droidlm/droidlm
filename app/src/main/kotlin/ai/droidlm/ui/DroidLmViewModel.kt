package ai.droidlm.ui

import ai.droidlm.auth.AuthState
import ai.droidlm.auth.AuthTokenResult
import ai.droidlm.auth.AllowlistState
import ai.droidlm.diagnostics.DebugLogUploadEndpoint
import ai.droidlm.update.DebugBuildPreparationResult
import ai.droidlm.update.DebugUpdatePhase
import ai.droidlm.update.DebugUpdateUiState
import ai.droidlm.update.PreparedDebugBuild
import ai.droidlm.logs.ActionLogType
import ai.droidlm.ondevice.OnDevicePlanner
import ai.droidlm.overlay.FloatingControlOverlayService
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.runtime.OverlayNoticeKind
import ai.droidlm.settings.TranscriptionProvider
import ai.droidlm.settings.WakeWordProvider
import ai.droidlm.voice.WakeWordForegroundService
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DroidLmViewModel(
    application: Application,
    private val deps: DroidLmViewModelDeps
) : AndroidViewModel(application) {
    private val app = application
    val settings = deps.settingsRepository.settings
    val authState = deps.authRepository.authState
    val allowlistState = deps.allowlistRepository.accessState
    val logs = deps.actionLogRepository.logs
    val executionState = deps.executor.uiState
    val pendingConfirmation = deps.executor.pendingConfirmation
    val listeningState = deps.listeningRuntime.isRunning
    val speechRecognitionState = deps.speechRecognitionController.state
    val pendingPlan = deps.executor.pendingPlan
    val plannerKeySetupRequest = deps.executor.plannerKeySetupRequest
    val overlayState = deps.overlayRuntime.isRunning
    val onDevicePlannerStatus = deps.onDevicePlanner.status

    private val _overlayPermissionGranted = MutableStateFlow(Settings.canDrawOverlays(app))
    val overlayPermissionGranted: StateFlow<Boolean> = _overlayPermissionGranted.asStateFlow()


    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    private val _debugUpdateState = MutableStateFlow(DebugUpdateUiState())
    val debugUpdateState: StateFlow<DebugUpdateUiState> = _debugUpdateState.asStateFlow()
    private var pendingDebugBuild: PreparedDebugBuild? = null

    fun refreshAccessibility() {
        viewModelScope.launch { _accessibilityEnabled.value = deps.portalController.isAccessibilityEnabled() }
    }


    fun refreshOverlayPermission(): Boolean {
        val granted = Settings.canDrawOverlays(app)
        _overlayPermissionGranted.value = granted
        return granted
    }


    fun upgradeToLatestDebugBuild() {
        if (!deps.debugBuildUpdater.isSupported) return
        viewModelScope.launch {
            pendingDebugBuild = null
            _debugUpdateState.value = DebugUpdateUiState(
                phase = DebugUpdatePhase.CHECKING,
                statusMessage = "Checking GitHub for the latest debug build..."
            )
            when (val result = deps.debugBuildUpdater.prepareLatestInstall { progress ->
                _debugUpdateState.value = DebugUpdateUiState(
                    phase = DebugUpdatePhase.DOWNLOADING,
                    statusMessage = "Downloading the latest debug build...",
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes,
                    progressFraction = progress.progressFraction
                )
            }) {
                is DebugBuildPreparationResult.ReadyToInstall -> continueDebugBuildInstall(result.build)
                is DebugBuildPreparationResult.AlreadyLatest -> {
                    _debugUpdateState.value = DebugUpdateUiState(
                        phase = DebugUpdatePhase.ALREADY_LATEST,
                        statusMessage = "Already on the latest published debug build (${result.availableVersionName}).",
                        availableVersionName = result.availableVersionName
                    )
                    deps.actionLogRepository.log(
                        ActionLogType.ACTION_RESULT,
                        "Already on latest published debug build",
                        result.availableVersionName
                    )
                }
                is DebugBuildPreparationResult.Failure -> {
                    _debugUpdateState.value = DebugUpdateUiState(
                        phase = DebugUpdatePhase.ERROR,
                        statusMessage = result.message
                    )
                    deps.actionLogRepository.log(
                        ActionLogType.ERROR,
                        "Debug build upgrade failed: ${result.message}",
                        result.errorCode
                    )
                }
            }
        }
    }

    fun debugBuildInstallPermissionIntent(): Intent = deps.debugBuildUpdater.installPermissionIntent()

    fun resumePendingDebugBuildInstall() {
        val build = pendingDebugBuild ?: return
        continueDebugBuildInstall(build)
    }

    private fun continueDebugBuildInstall(build: PreparedDebugBuild) {
        pendingDebugBuild = build
        if (!deps.debugBuildUpdater.canRequestPackageInstalls()) {
            _debugUpdateState.value = DebugUpdateUiState(
                phase = DebugUpdatePhase.AWAITING_INSTALL_PERMISSION,
                statusMessage = "Downloaded ${build.versionName}. Allow DroidLM to install unknown apps to continue.",
                availableVersionName = build.versionName
            )
            deps.actionLogRepository.log(
                ActionLogType.ACTION_RESULT,
                "Downloaded debug build and waiting for install permission",
                build.versionName
            )
            return
        }

        _debugUpdateState.value = DebugUpdateUiState(
            phase = DebugUpdatePhase.OPENING_INSTALLER,
            statusMessage = "Opening Android package installer for ${build.versionName}...",
            availableVersionName = build.versionName
        )
        runCatching { deps.debugBuildUpdater.launchInstaller(build) }
            .onSuccess {
                pendingDebugBuild = null
                _debugUpdateState.value = DebugUpdateUiState(
                    statusMessage = "Installer opened for ${build.versionName}. Finish the upgrade in Android's package installer.",
                    availableVersionName = build.versionName
                )
                deps.actionLogRepository.log(
                    ActionLogType.ACTION_RESULT,
                    "Opened installer for debug build ${build.versionName}",
                    build.tagName
                )
            }
            .onFailure { error ->
                _debugUpdateState.value = DebugUpdateUiState(
                    phase = DebugUpdatePhase.ERROR,
                    statusMessage = "Could not open Android package installer: ${error.message}",
                    availableVersionName = build.versionName
                )
                deps.actionLogRepository.log(
                    ActionLogType.ERROR,
                    "Could not open debug build installer: ${error.message}"
                )
            }
    }
    fun startListening() {
        ContextCompat.startForegroundService(
            app,
            WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_START_LISTENING)
        )
    }

    fun stopListening() {
        app.startService(WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_STOP_LISTENING))
    }

    fun pushToTalk() {
        val sessionId = deps.speechDiagnosticsLogger.startSession("main_push_to_talk")
        ContextCompat.startForegroundService(
            app,
            WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_PUSH_TO_TALK, sessionId)
        )
    }

    fun startOverlay() {
        app.startService(FloatingControlOverlayService.intent(app, FloatingControlOverlayService.ACTION_SHOW))
    }

    fun stopOverlay() {
        app.startService(FloatingControlOverlayService.intent(app, FloatingControlOverlayService.ACTION_STOP))
    }

    fun acceptPendingPlan(alwaysAcceptSafePlans: Boolean) {
        viewModelScope.launch { deps.executor.acceptPendingPlan(alwaysAcceptSafePlans) }
    }

    fun rejectPendingPlan() {
        deps.executor.rejectPendingPlan()
    }

    fun dismissPlannerKeySetup() {
        deps.executor.clearPlannerKeySetupRequest()
    }

    fun saveOpenAiApiKey(apiKey: String) {
        viewModelScope.launch {
            deps.settingsRepository.saveOpenAiApiKey(apiKey)
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Cloud planning key saved on this device")
            deps.executor.retryPlannerKeySetupRequest()
        }
    }

    fun clearOpenAiApiKey() {
        viewModelScope.launch {
            deps.settingsRepository.clearOpenAiApiKey()
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Cloud planning key cleared from this device")
        }
    }

    fun signInWithGoogle(context: Context) = viewModelScope.launch { deps.authRepository.signInWithGoogle(context) }

    fun signInWithEmail(email: String, password: String) = viewModelScope.launch {
        deps.authRepository.signInWithEmail(email, password)
    }

    fun createAccountWithEmail(email: String, password: String) = viewModelScope.launch {
        deps.authRepository.createAccountWithEmail(email, password)
    }

    fun sendPasswordReset(email: String) = viewModelScope.launch {
        deps.authRepository.sendPasswordReset(email)
    }

    fun signOut() = deps.authRepository.signOut()

    fun cancelCurrentTask() {
        deps.executor.cancelActive()
        app.startService(WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_CANCEL))
    }

    fun executeTextCommand(command: String) {
        viewModelScope.launch { deps.executor.executeTranscript(command) }
    }


    fun testOcr() {
        viewModelScope.launch {
            val screenshot = deps.portalController.takeScreenshot()
            if (!screenshot.success || screenshot.bitmap == null) {
                deps.actionLogRepository.log(ActionLogType.ERROR, "OCR test failed: ${screenshot.message}", screenshot.errorCode)
                return@launch
            }
            deps.debugLogStore.retainScreenshot(screenshot.bitmap, "ocr-test")
            runCatching { deps.ocrEngine.recognize(screenshot.bitmap) }
                .onSuccess { deps.actionLogRepository.log(ActionLogType.OCR_RESULT, "OCR test detected ${it.lines.size} lines") }
                .onFailure { deps.actionLogRepository.log(ActionLogType.ERROR, "OCR test failed: ${it.message}") }
        }
    }

    fun confirmPending() = deps.executor.respondToConfirmation(true)
    fun rejectPending() = deps.executor.respondToConfirmation(false)

    fun updateOpenAiModel(value: String) = viewModelScope.launch { deps.settingsRepository.updateOpenAiModel(value) }
    fun updatePrivacyMode(value: Boolean) = viewModelScope.launch {
        deps.settingsRepository.updatePrivacyModeEnabled(value)
        if (value) {
            deps.settingsRepository.updateCloudScreenshotAnalysisEnabled(false)
            deps.onDevicePlanner.prepareIfPossible()
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Privacy mode enabled", "Cloud screenshot analysis disabled")
        } else {
            deps.onDevicePlanner.releaseModel()
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Privacy mode disabled")
        }
    }
    fun updateWakeWordProvider(provider: WakeWordProvider) = viewModelScope.launch { deps.settingsRepository.updateWakeWordProvider(provider) }
    fun updateTranscriptionProvider(provider: TranscriptionProvider) = viewModelScope.launch { deps.settingsRepository.updateTranscriptionProvider(provider) }
    fun updatePreferOfflineSpeech(value: Boolean) = viewModelScope.launch { deps.settingsRepository.updatePreferOfflineSpeechRecognition(value) }
    fun updateShowPartialSpeech(value: Boolean) = viewModelScope.launch { deps.settingsRepository.updateShowPartialSpeechRecognition(value) }
    fun updateRiskConfirmation(value: Boolean) = viewModelScope.launch { deps.settingsRepository.updateRequireRiskConfirmation(value) }
    fun updateOnDeviceOcr(value: Boolean) = viewModelScope.launch { deps.settingsRepository.updateOnDeviceOcrEnabled(value) }
    fun updateCloudVision(value: Boolean) = viewModelScope.launch {
        val currentSettings = deps.settingsRepository.settings.first()
        if (currentSettings.privacyModeEnabled && value) {
            deps.settingsRepository.updateCloudScreenshotAnalysisEnabled(false)
            deps.actionLogRepository.log(ActionLogType.ERROR, "Privacy mode blocks cloud screenshot analysis")
        } else {
            deps.settingsRepository.updateCloudScreenshotAnalysisEnabled(value)
        }
    }
    fun downloadPrivacyModel() = viewModelScope.launch {
        runCatching {
            deps.onDevicePlanner.downloadModel()
        }.onSuccess {
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Downloaded the local planning model")
            deps.executor.retryPlannerKeySetupRequest()
        }.onFailure { error ->
            deps.actionLogRepository.log(ActionLogType.ERROR, "Could not download the local planning model: ${error.message}")
        }
    }
    fun preparePrivacyModel() {
        deps.onDevicePlanner.prepareIfPossible()
    }
    fun updateDebugLogging(value: Boolean) = viewModelScope.launch {
        deps.settingsRepository.updateDebugLoggingEnabled(value)
        deps.speechDiagnosticsLogger.setEnabled(value)
        if (value) {
            deps.debugLogStore.recordEvent("setting_enabled", mapOf("source" to "settings_ui"))
            deps.actionLogRepository.log(
                ActionLogType.ACTION_RESULT,
                "Troubleshooting logs enabled",
                "Exports may include spoken text, screenshots, audio, and speech-recognition state."
            )
        } else {
            deps.speechDiagnosticsLogger.clear()
            deps.debugLogStore.clear()
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Troubleshooting logs disabled and cleared")
        }
    }

    fun debugLogsExportFileName(): String = deps.debugLogStore.exportFileName()

    fun saveDebugLogsToUri(uri: Uri) = viewModelScope.launch {
        deps.debugLogStore.recordEvent("save_requested", mapOf("uriScheme" to uri.scheme))
        val file = withContext(Dispatchers.IO) { deps.debugLogStore.createBundle() }
        if (file == null) {
            deps.debugLogStore.recordEvent("save_empty")
            deps.actionLogRepository.log(ActionLogType.ERROR, "No debug logs to save")
            return@launch
        }
        runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not open destination file")
            }
        }.onSuccess {
            deps.debugLogStore.recordEvent("save_succeeded", mapOf("zipName" to file.name, "zipBytes" to file.length()))
            deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Saved debug logs")
        }.onFailure { error ->
            deps.debugLogStore.recordEvent("save_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name, "zipName" to file.name, "zipBytes" to file.length()))
            deps.actionLogRepository.log(ActionLogType.ERROR, "Could not save debug logs: ${error.message}")
        }
    }

    fun shareDebugLogs(issueDescription: String) = viewModelScope.launch {
        deps.debugLogStore.recordEvent("upload_requested", mapOf("issueDescriptionLength" to issueDescription.trim().length))
        deps.overlayRuntime.showNotice(
            title = "Uploading debug logs",
            details = "Checking signed-in access before preparing the bundle.",
            kind = OverlayNoticeKind.INFO
        )

        val authSnapshot = deps.authRepository.authState.value
        val allowlistSnapshot = deps.allowlistRepository.accessState.value
        val authMetadata = debugLogUploadAuthMetadata(authSnapshot, allowlistSnapshot)
        deps.debugLogStore.recordEvent("upload_auth_preflight", authMetadata)
        debugLogUploadAuthBlock(authSnapshot, allowlistSnapshot)?.let { (message, errorCode) ->
            failDebugLogUpload(
                message = message,
                errorCode = errorCode,
                eventMetadata = authMetadata + mapOf("reason" to "auth_preflight_blocked")
            )
            return@launch
        }

        val firebaseIdToken = resolveDebugLogUploadToken() ?: return@launch
        val file = withContext(Dispatchers.IO) { deps.debugLogStore.createBundle(issueDescription) }
        if (file == null) {
            deps.debugLogStore.recordEvent("upload_empty")
            deps.actionLogRepository.log(ActionLogType.ERROR, "No debug logs to upload")
            deps.overlayRuntime.showNotice(
                title = "Debug log upload failed",
                details = "No debug logs are available to upload.",
                kind = OverlayNoticeKind.ERROR
            )
            return@launch
        }

        val uploadUrl = DebugLogUploadEndpoint.url()
        if (uploadUrl.isBlank()) {
            deps.debugLogStore.recordEvent(
                "upload_failed",
                mapOf("reason" to "missing_upload_url", "zipName" to file.name, "zipBytes" to file.length())
            )
            deps.actionLogRepository.log(
                ActionLogType.ERROR,
                "Debug log upload endpoint is not configured",
                "NO_DEBUG_LOG_UPLOAD_URL"
            )
            deps.overlayRuntime.showNotice(
                title = "Debug log upload failed",
                details = "Debug log upload endpoint is not configured.",
                kind = OverlayNoticeKind.ERROR
            )
            return@launch
        }

        val result = deps.relayClient.uploadDebugLogsToUrl(uploadUrl, file, app.packageName, appVersionName(), firebaseIdToken)
        when (result) {
            is RelayCallResult.Success -> {
                val upload = result.value
                deps.debugLogStore.recordEvent(
                    "upload_succeeded",
                    mapOf(
                        "zipName" to file.name,
                        "zipBytes" to file.length(),
                        "objectName" to upload.objectName,
                        "uploadedBytes" to upload.sizeBytes,
                        "uploadEndpointConfigured" to true
                    )
                )
                deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Uploaded debug logs")
                deps.overlayRuntime.showNotice(
                    title = "Debug logs uploaded",
                    kind = OverlayNoticeKind.SUCCESS
                )
            }
            is RelayCallResult.Failure -> {
                deps.debugLogStore.recordEvent(
                    "upload_failed",
                    mapOf(
                        "message" to result.message,
                        "errorCode" to result.errorCode,
                        "zipName" to file.name,
                        "zipBytes" to file.length(),
                        "uploadEndpointConfigured" to uploadUrl.isNotBlank()
                    )
                )
                deps.actionLogRepository.log(ActionLogType.ERROR, "Could not upload debug logs: ${result.message}", result.errorCode)
                deps.overlayRuntime.showNotice(
                    title = "Debug log upload failed",
                    details = result.message,
                    kind = OverlayNoticeKind.ERROR
                )
            }
        }
    }

    private suspend fun resolveDebugLogUploadToken(): String? {
        val first = deps.authRepository.currentIdTokenResult(forceRefresh = false)
        deps.debugLogStore.recordEvent("upload_token_preflight", debugLogUploadTokenMetadata("cached", false, first))
        if (first is AuthTokenResult.Success) return first.token

        val refreshed = deps.authRepository.currentIdTokenResult(forceRefresh = true)
        deps.debugLogStore.recordEvent("upload_token_preflight", debugLogUploadTokenMetadata("refreshed", true, refreshed))
        if (refreshed is AuthTokenResult.Success) return refreshed.token

        val message = when (refreshed) {
            AuthTokenResult.AuthNotConfigured -> "Firebase Auth is not configured."
            AuthTokenResult.NoCurrentUser -> "Sign in again before uploading debug logs."
            is AuthTokenResult.Failure -> "Could not refresh signed-in session: ${refreshed.message}"
            is AuthTokenResult.Success -> "Signed-in session is ready."
        }
        val errorCode = when (refreshed) {
            AuthTokenResult.AuthNotConfigured -> "AUTH_NOT_CONFIGURED"
            AuthTokenResult.NoCurrentUser -> "AUTH_TOKEN_MISSING"
            is AuthTokenResult.Failure -> refreshed.errorCode ?: "AUTH_TOKEN_REFRESH_FAILED"
            is AuthTokenResult.Success -> null
        }
        failDebugLogUpload(
            message = message,
            errorCode = errorCode,
            eventMetadata = debugLogUploadTokenMetadata("failed", true, refreshed) + mapOf("reason" to "token_unavailable")
        )
        return null
    }

    private fun debugLogUploadAuthBlock(auth: AuthState, allowlist: AllowlistState): Pair<String, String>? = when {
        !auth.configured -> "Firebase Auth is not configured." to "AUTH_NOT_CONFIGURED"
        !auth.ready || auth.loading -> "Sign-in state is still loading. Try again in a moment." to "AUTH_NOT_READY"
        !auth.signedIn -> "Sign in before uploading debug logs." to "AUTH_TOKEN_MISSING"
        auth.user?.emailVerified == false -> "Verify your email address before uploading debug logs." to "AUTH_EMAIL_UNVERIFIED"
        allowlist.checking -> "DroidLM access is still being verified. Try again in a moment." to "ALLOWLIST_CHECKING"
        !allowlist.allowed -> (allowlist.message ?: "DroidLM is still verifying access. Try again in a moment.") to (allowlist.errorCode ?: "AUTH_NOT_ALLOWLISTED")
        else -> null
    }

    private fun failDebugLogUpload(message: String, errorCode: String?, eventMetadata: Map<String, Any?>) {
        deps.debugLogStore.recordEvent(
            "upload_failed",
            eventMetadata + mapOf("message" to message, "errorCode" to errorCode)
        )
        deps.actionLogRepository.log(ActionLogType.ERROR, "Could not upload debug logs: $message", errorCode)
        deps.overlayRuntime.showNotice(
            title = "Debug log upload failed",
            details = message,
            kind = OverlayNoticeKind.ERROR
        )
    }

    private fun debugLogUploadAuthMetadata(auth: AuthState, allowlist: AllowlistState): Map<String, Any?> = mapOf(
        "authConfigured" to auth.configured,
        "authReady" to auth.ready,
        "authLoading" to auth.loading,
        "signedIn" to auth.signedIn,
        "uidPresent" to (auth.user?.uid?.isNotBlank() == true),
        "emailPresent" to (!auth.user?.email.isNullOrBlank()),
        "emailVerified" to auth.user?.emailVerified,
        "allowlistConfigured" to allowlist.configured,
        "allowlistChecking" to allowlist.checking,
        "allowlisted" to allowlist.allowed,
        "allowlistErrorCode" to allowlist.errorCode,
        "appPackage" to app.packageName,
        "appVersion" to appVersionName()
    )

    private fun debugLogUploadTokenMetadata(attempt: String, forceRefresh: Boolean, result: AuthTokenResult): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "attempt" to attempt,
            "forceRefresh" to forceRefresh,
            "tokenAvailable" to (result is AuthTokenResult.Success)
        )
        when (result) {
            is AuthTokenResult.Success -> base["result"] = "success"
            AuthTokenResult.NoCurrentUser -> base["result"] = "no_current_user"
            AuthTokenResult.AuthNotConfigured -> base["result"] = "auth_not_configured"
            is AuthTokenResult.Failure -> {
                base["result"] = "failure"
                base["errorClass"] = result.errorClass
                base["errorCode"] = result.errorCode
                base["message"] = result.message
            }
        }
        return base
    }


    fun clearDebugLogs() {
        deps.debugLogStore.recordEvent("clear_requested", mapOf("source" to "settings_ui"))
        deps.speechDiagnosticsLogger.clear()
        viewModelScope.launch { deps.debugLogStore.clear() }
    }

    fun completeOnboarding() = viewModelScope.launch {
        if (!deps.authRepository.authState.value.signedIn || !deps.allowlistRepository.accessState.value.allowed) {
            deps.actionLogRepository.log(ActionLogType.ERROR, "Onboarding requires approved sign-in before completion")
            return@launch
        }
        deps.settingsRepository.updateOnboardingCompletedVersion(ONBOARDING_VERSION)
        deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Onboarding completed")
    }

    fun checkSpeechSetup(preferOffline: Boolean) {
        deps.speechRecognitionController.checkSpeechSetup(preferOffline)
    }

    fun openSpeechRecognitionSettings() {
        val options = listOf(
            "voice_input_settings" to Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            "input_method_settings" to Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
            "app_settings" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
        )
        val target = options.firstOrNull { (_, intent) -> canOpen(intent) }
        if (target == null) {
            deps.actionLogRepository.log(ActionLogType.ERROR, "Could not open Android speech settings on this device")
            return
        }
        openSettingsIntent(target.first, target.second)
    }

    fun openRecognizerAppSettings() {
        val packageName = voiceRecognitionServicePackageName()
        if (packageName == null) {
            deps.actionLogRepository.log(ActionLogType.ERROR, "No Android speech recognizer app was reported by the device")
            openSpeechRecognitionSettings()
            return
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        if (!canOpen(intent)) {
            deps.actionLogRepository.log(ActionLogType.ERROR, "Could not open settings for speech recognizer app $packageName")
            openSpeechRecognitionSettings()
            return
        }
        openSettingsIntent("recognizer_app_settings", intent, mapOf("recognizerPackage" to packageName))
    }

    private fun openSettingsIntent(label: String, intent: Intent, fields: Map<String, Any?> = emptyMap()) {
        val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        deps.speechDiagnosticsLogger.record(
            null,
            "speech_settings_opened",
            mapOf("target" to label, "action" to launchIntent.action, "data" to launchIntent.dataString) + fields
        )
        runCatching { app.startActivity(launchIntent) }
            .onSuccess { deps.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Opened Android speech settings") }
            .onFailure { error -> deps.actionLogRepository.log(ActionLogType.ERROR, "Could not open Android speech settings: ${error.message}") }
    }

    private fun canOpen(intent: Intent): Boolean = intent.resolveActivity(app.packageManager) != null

    private fun voiceRecognitionServicePackageName(): String? {
        val component = Settings.Secure.getString(app.contentResolver, "voice_recognition_service").orEmpty()
        return ComponentName.unflattenFromString(component)?.packageName
            ?: component.substringBefore('/').takeIf { it.isNotBlank() }
    }
    fun updateMobilerunDeviceId(value: String) = viewModelScope.launch { deps.settingsRepository.updateMobilerunDeviceId(value) }
    fun saveMobilerunApiKey(value: String) = viewModelScope.launch { deps.settingsRepository.saveMobilerunApiKey(value) }
    fun savePicovoiceAccessKey(value: String) = viewModelScope.launch { deps.settingsRepository.savePicovoiceAccessKey(value) }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String? = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull()

    companion object {
        const val ONBOARDING_VERSION = 2
    }

}
