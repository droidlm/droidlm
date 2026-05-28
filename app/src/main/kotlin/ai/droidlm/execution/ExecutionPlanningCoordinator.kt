package ai.droidlm.execution

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.context.ArtifactContextBuilder
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.ondevice.OnDevicePlanner
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalController
import ai.droidlm.prompts.PromptHistoryRepository
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayPlanRequest
import ai.droidlm.safety.SafetyClassifier
import ai.droidlm.safety.SafetyDecision
import ai.droidlm.settings.ExecutionMode
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal class ExecutionPlanningCoordinator(
    private val settingsRepository: SettingsRepository,
    private val openAiClient: OpenAiClient,
    private val onDevicePlanner: OnDevicePlanner,
    private val portalController: PortalController,
    private val appInventoryRepository: AppInventoryRepository,
    private val deviceContextAggregator: DeviceContextAggregator,
    private val logs: ActionLogRepository,
    private val diagnostics: SpeechDiagnosticsLogger,
    private val safetyClassifier: SafetyClassifier,
    private val promptHistoryRepository: PromptHistoryRepository,
    private val uiState: MutableStateFlow<ExecutionUiState>,
    private val pendingPlan: MutableStateFlow<PendingPlan?>,
    private val plannerKeySetupRequest: MutableStateFlow<PlannerKeySetupRequest?>,
    private val executionDiagnostics: ExecutionDiagnostics,
    private val cancellationResult: () -> ActionResult?,
    private val finish: (ActionResult) -> ActionResult,
    private val executeAction: suspend (DroidLmAction, String, Boolean, String?) -> ActionResult,
    private val requestConfirmation: suspend (String, DroidLmAction, String, String?, String?) -> Boolean,
    private val runAgentLoop: suspend (String, String?) -> ActionResult,
    private val runMobilerunTask: suspend (String) -> ActionResult,
    private val userFacingPlannerMessage: (RelayCallResult.Failure) -> String
) {
    suspend fun planTranscript(transcript: String, diagnosticSessionId: String? = null, recordPrompt: Boolean = true): ActionResult {
        val planningStartedAt = System.currentTimeMillis()
        val stripped = SpeechTextNormalizer.stripWakePhrase(transcript)
        if (executionDiagnostics.isAmbiguousOpenCommand(stripped)) {
            debugEvent(diagnosticSessionId, "ambiguous_open_command", mapOf("transcript" to stripped))
            return finish(ActionResult.fail("I only heard '${stripped}'. Please say which app to open, like 'open Google Sheets'.", "AMBIGUOUS_OPEN_APP"))
        }
        debugEvent(diagnosticSessionId, "voice_plan_started", mapOf("transcriptLength" to stripped.length, "hasSessionId" to (diagnosticSessionId != null)))
        if (recordPrompt) {
            promptHistoryRepository.record(stripped, "voice_prompt")
        }
        plannerKeySetupRequest.value = null
        uiState.value = uiState.value.copy(lastTranscript = stripped, status = "Planning", lastResult = "")
        if (recordPrompt) {
            logs.log(ActionLogType.TRANSCRIPTION_RESULT, stripped)
        }
        logs.log(ActionLogType.PLANNER_STARTED, "Planning started", "promptLength=${stripped.length}")
        val settingsStartedAt = System.currentTimeMillis()
        val settings = settingsRepository.settings.first()
        val settingsLoadMs = System.currentTimeMillis() - settingsStartedAt
        if (settings.privacyModeEnabled) {
            return planTranscriptOnDevice(stripped, diagnosticSessionId, settings.autoAcceptSafePlans, settingsLoadMs, planningStartedAt)
        }
        val artifactAgentLoopReason = if (settings.executionMode == ExecutionMode.AGENT_LOOP) {
            null
        } else {
            artifactAgentLoopReason(stripped, diagnosticSessionId)
        }
        if (settings.executionMode == ExecutionMode.AGENT_LOOP || artifactAgentLoopReason != null) {
            debugEvent(
                diagnosticSessionId,
                "planner_pipeline_handoff",
                mapOf(
                    "executionMode" to settings.executionMode.name,
                    "endpointMode" to "direct_openai_agent_loop",
                    "artifactAgentLoopReason" to artifactAgentLoopReason,
                    "settingsLoadMs" to settingsLoadMs,
                    "elapsedSincePlanStartMs" to (System.currentTimeMillis() - planningStartedAt)
                )
            )
            return runAgentLoop(stripped, diagnosticSessionId)
        }
        val apiKey = settingsRepository.getOpenAiApiKey().orEmpty()
        diagnostics.record(
            diagnosticSessionId,
            "planner_started",
            mapOf(
                "promptLength" to stripped.length,
                "openAiKeyConfigured" to apiKey.isNotBlank(),
                "model" to settings.openAiModel,
                "executionMode" to settings.executionMode.name,
                "endpointMode" to "direct_openai",
                "settingsLoadMs" to settingsLoadMs,
                "maxAutonomousSteps" to settings.maxAutonomousSteps,
                "autoAcceptSafePlans" to settings.autoAcceptSafePlans
            )
        )
        if (apiKey.isBlank()) {
            plannerKeySetupRequest.value = PlannerKeySetupRequest(
                kind = PlannerSetupKind.OPENAI_API_KEY,
                message = "This command needs planning. Add a cloud planning key to review and approve it on this device.",
                retryTranscript = stripped
            )
            diagnostics.record(diagnosticSessionId, "planner_key_missing")
            diagnostics.record(
                diagnosticSessionId,
                "planner_pipeline_finished",
                mapOf(
                    "success" to false,
                    "errorCode" to "OPENAI_API_KEY_MISSING",
                    "endpointMode" to "direct_openai",
                    "totalDurationMs" to (System.currentTimeMillis() - planningStartedAt),
                    "settingsLoadMs" to settingsLoadMs
                )
            )
            logs.log(ActionLogType.ERROR, "Planner OpenAI key is missing or unreadable", "OPENAI_API_KEY_MISSING")
            return finish(ActionResult.fail("This command needs planning. Add a cloud planning key to review it.", "OPENAI_API_KEY_MISSING"))
        }
        val portalStateStartedAt = System.currentTimeMillis()
        val stateResult = runCatching { portalController.getState() }
        val portalStateDurationMs = System.currentTimeMillis() - portalStateStartedAt
        stateResult
            .onSuccess { state -> debugEvent(diagnosticSessionId, "portal_state_collected", executionDiagnostics.portalStateFields(state) + mapOf("durationMs" to portalStateDurationMs)) }
            .onFailure { error -> debugEvent(diagnosticSessionId, "portal_state_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name, "durationMs" to portalStateDurationMs)) }
        val state = stateResult.getOrNull()
        val deviceContextStartedAt = System.currentTimeMillis()
        val deviceContextResult = runCatching { deviceContextAggregator.collect(stripped, state, diagnosticSessionId = diagnosticSessionId) }
        val deviceContextDurationMs = System.currentTimeMillis() - deviceContextStartedAt
        deviceContextResult
            .onSuccess { context ->
                debugEvent(
                    diagnosticSessionId,
                    "device_context_collected",
                    mapOf(
                        "packageCount" to context.packages.size,
                        "activePackage" to context.activeApp?.packageName,
                        "extraKeyCount" to context.extras.length(),
                        "durationMs" to deviceContextDurationMs
                    )
                )
            }
            .onFailure { error -> debugEvent(diagnosticSessionId, "device_context_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name, "durationMs" to deviceContextDurationMs)) }
        val deviceContext = deviceContextResult.getOrNull()
        val packageResolutionStartedAt = System.currentTimeMillis()
        val packages = deviceContext?.packages ?: runCatching { appInventoryRepository.getInstalledApps() }.getOrDefault(emptyList())
        val activeApp = deviceContext?.activeApp ?: runCatching { appInventoryRepository.activeAppFor(state) }.getOrNull()
        val packageResolutionDurationMs = System.currentTimeMillis() - packageResolutionStartedAt
        val request = RelayPlanRequest(stripped, state, packages, emptyList(), settings.maxAutonomousSteps, activeApp, deviceContext)
        diagnostics.record(
            diagnosticSessionId,
            "openai_plan_request_started",
            mapOf(
                "model" to settings.openAiModel,
                "endpointMode" to "direct_openai",
                "packageCount" to packages.size,
                "activePackage" to activeApp?.packageName,
                "portalStateDurationMs" to portalStateDurationMs,
                "deviceContextDurationMs" to deviceContextDurationMs,
                "packageResolutionDurationMs" to packageResolutionDurationMs,
                "elapsedSincePlanStartMs" to (System.currentTimeMillis() - planningStartedAt)
            )
        )
        val openAiStartedAt = System.currentTimeMillis()
        val result = openAiClient.planPreview(apiKey, settings.openAiModel, request)
        val openAiDurationMs = System.currentTimeMillis() - openAiStartedAt
        return when (result) {
            is RelayCallResult.Failure -> {
                val totalDurationMs = System.currentTimeMillis() - planningStartedAt
                diagnostics.record(diagnosticSessionId, "openai_plan_failed", mapOf("errorCode" to result.errorCode, "message" to result.message.take(240), "openAiDurationMs" to openAiDurationMs, "totalPlannerDurationMs" to totalDurationMs))
                diagnostics.record(
                    diagnosticSessionId,
                    "planner_pipeline_finished",
                    mapOf(
                        "success" to false,
                        "errorCode" to result.errorCode,
                        "endpointMode" to "direct_openai",
                        "totalDurationMs" to totalDurationMs,
                        "settingsLoadMs" to settingsLoadMs,
                        "portalStateDurationMs" to portalStateDurationMs,
                        "deviceContextDurationMs" to deviceContextDurationMs,
                        "packageResolutionDurationMs" to packageResolutionDurationMs,
                        "openAiDurationMs" to openAiDurationMs
                    )
                )
                logs.log(ActionLogType.ERROR, "GPT planning failed: ${result.message}", result.errorCode)
                finish(ActionResult.fail(userFacingPlannerMessage(result), result.errorCode))
            }
            is RelayCallResult.Success -> {
                val totalDurationMs = System.currentTimeMillis() - planningStartedAt
                val plan = result.value
                val pending = PendingPlan(stripped, plan, diagnosticSessionId)
                pendingPlan.value = pending
                uiState.value = uiState.value.copy(
                    parsedAction = "Plan: ${plan.steps.size} steps via ${plan.model}",
                    status = "Plan ready",
                    lastResult = plan.summary
                )
                diagnostics.record(
                    diagnosticSessionId,
                    "openai_plan_succeeded",
                    mapOf("model" to plan.model, "riskLevel" to plan.riskLevel, "stepCount" to plan.steps.size, "requiresConfirmation" to plan.requiresConfirmation, "openAiDurationMs" to openAiDurationMs, "totalPlannerDurationMs" to totalDurationMs)
                )
                diagnostics.record(
                    diagnosticSessionId,
                    "planner_pipeline_finished",
                    mapOf(
                        "success" to true,
                        "endpointMode" to "direct_openai",
                        "totalDurationMs" to totalDurationMs,
                        "settingsLoadMs" to settingsLoadMs,
                        "portalStateDurationMs" to portalStateDurationMs,
                        "deviceContextDurationMs" to deviceContextDurationMs,
                        "packageResolutionDurationMs" to packageResolutionDurationMs,
                        "openAiDurationMs" to openAiDurationMs,
                        "stepCount" to plan.steps.size,
                        "requiresConfirmation" to plan.requiresConfirmation
                    )
                )
                logs.log(ActionLogType.PLANNER_RESULT, "GPT plan ready: ${plan.summary}", "model=${plan.model}; risk=${plan.riskLevel}; steps=${plan.steps.size}")
                if (settings.autoAcceptSafePlans && plan.isSafe) {
                    logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "Auto-accepted safe plan")
                    diagnostics.record(diagnosticSessionId, "safe_plan_auto_accepted", mapOf("stepCount" to plan.steps.size))
                    executePlan(pending)
                } else {
                    ActionResult.ok("Plan ready for review")
                }
            }
        }
    }

    private suspend fun planTranscriptOnDevice(
        transcript: String,
        diagnosticSessionId: String?,
        autoAcceptSafePlans: Boolean,
        settingsLoadMs: Long,
        planningStartedAt: Long
    ): ActionResult {
        diagnostics.record(
            diagnosticSessionId,
            "planner_started",
            mapOf(
                "promptLength" to transcript.length,
                "endpointMode" to "on_device_qwen3",
                "settingsLoadMs" to settingsLoadMs
            )
        )
        logs.log(ActionLogType.PLANNER_STARTED, "On-device planning started", "promptLength=${transcript.length}")

        val portalStateStartedAt = System.currentTimeMillis()
        val stateResult = runCatching { portalController.getState() }
        val portalStateDurationMs = System.currentTimeMillis() - portalStateStartedAt
        val state = stateResult.getOrNull()

        val deviceContextStartedAt = System.currentTimeMillis()
        val deviceContextResult = runCatching { deviceContextAggregator.collect(transcript, state, diagnosticSessionId = diagnosticSessionId) }
        val deviceContextDurationMs = System.currentTimeMillis() - deviceContextStartedAt
        val deviceContext = deviceContextResult.getOrNull()

        val packageResolutionStartedAt = System.currentTimeMillis()
        val packages = deviceContext?.packages ?: runCatching { appInventoryRepository.getInstalledApps() }.getOrDefault(emptyList())
        val activeApp = deviceContext?.activeApp ?: runCatching { appInventoryRepository.activeAppFor(state) }.getOrNull()
        val packageResolutionDurationMs = System.currentTimeMillis() - packageResolutionStartedAt

        val request = RelayPlanRequest(transcript, state, packages, emptyList(), maxSteps = 4, activeApp = activeApp, deviceContext = deviceContext)
        val planningStarted = System.currentTimeMillis()
        val result = onDevicePlanner.planPreview(request)
        val localDurationMs = System.currentTimeMillis() - planningStarted

        return when (result) {
            is RelayCallResult.Failure -> {
                val totalDurationMs = System.currentTimeMillis() - planningStartedAt
                if (result.errorCode == OnDevicePlanner.ERROR_MODEL_MISSING || result.errorCode == OnDevicePlanner.ERROR_MODEL_UNSUPPORTED) {
                    plannerKeySetupRequest.value = PlannerKeySetupRequest(
                        kind = PlannerSetupKind.ON_DEVICE_MODEL,
                        message = onDevicePlanner.setupMessage(),
                        retryTranscript = transcript
                    )
                }
                diagnostics.record(
                    diagnosticSessionId,
                    "planner_pipeline_finished",
                    mapOf(
                        "success" to false,
                        "errorCode" to result.errorCode,
                        "endpointMode" to "on_device_qwen3",
                        "totalDurationMs" to totalDurationMs,
                        "settingsLoadMs" to settingsLoadMs,
                        "portalStateDurationMs" to portalStateDurationMs,
                        "deviceContextDurationMs" to deviceContextDurationMs,
                        "packageResolutionDurationMs" to packageResolutionDurationMs,
                        "localPlannerDurationMs" to localDurationMs
                    )
                )
                logs.log(ActionLogType.ERROR, "On-device planning failed: ${result.message}", result.errorCode)
                finish(ActionResult.fail(result.message, result.errorCode))
            }
            is RelayCallResult.Success -> {
                val totalDurationMs = System.currentTimeMillis() - planningStartedAt
                val plan = result.value
                val pending = PendingPlan(transcript, plan, diagnosticSessionId)
                pendingPlan.value = pending
                uiState.value = uiState.value.copy(
                    parsedAction = "Plan: ${plan.steps.size} steps via ${plan.model}",
                    status = "Plan ready",
                    lastResult = plan.summary
                )
                diagnostics.record(
                    diagnosticSessionId,
                    "planner_pipeline_finished",
                    mapOf(
                        "success" to true,
                        "endpointMode" to "on_device_qwen3",
                        "totalDurationMs" to totalDurationMs,
                        "settingsLoadMs" to settingsLoadMs,
                        "portalStateDurationMs" to portalStateDurationMs,
                        "deviceContextDurationMs" to deviceContextDurationMs,
                        "packageResolutionDurationMs" to packageResolutionDurationMs,
                        "localPlannerDurationMs" to localDurationMs,
                        "stepCount" to plan.steps.size,
                        "requiresConfirmation" to plan.requiresConfirmation
                    )
                )
                logs.log(ActionLogType.PLANNER_RESULT, "On-device plan ready: ${plan.summary}", "model=${plan.model}; risk=${plan.riskLevel}; steps=${plan.steps.size}")
                if (autoAcceptSafePlans && plan.isSafe) {
                    logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "Auto-accepted safe on-device plan")
                    executePlan(pending)
                } else {
                    ActionResult.ok("Plan ready for review")
                }
            }
        }
    }

    suspend fun acceptPendingPlan(alwaysAcceptSafePlans: Boolean): ActionResult {
        val pending = pendingPlan.value ?: return ActionResult.fail("No pending plan", "NO_PENDING_PLAN")
        debugEvent(pending.diagnosticSessionId, "pending_plan_accepted", mapOf("alwaysAcceptSafePlans" to alwaysAcceptSafePlans, "stepCount" to pending.plan.steps.size))
        if (alwaysAcceptSafePlans && pending.plan.isSafe) {
            settingsRepository.updateAutoAcceptSafePlans(true)
        }
        logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "User accepted GPT plan")
        return executePlan(pending)
    }

    fun rejectPendingPlan() {
        val pending = pendingPlan.value
        pendingPlan.value = null
        uiState.value = uiState.value.copy(status = "Plan rejected", lastResult = "Plan rejected")
        logs.log(ActionLogType.CONFIRMATION_REJECTED, "User rejected GPT plan")
        debugEvent(pending?.diagnosticSessionId, "pending_plan_rejected", mapOf("stepCount" to (pending?.plan?.steps?.size ?: 0)))
    }

    fun clearPlannerKeySetupRequest() {
        plannerKeySetupRequest.value = null
    }

    suspend fun retryPlannerKeySetupRequest(): ActionResult {
        val retry = plannerKeySetupRequest.value?.retryTranscript
            ?: return ActionResult.fail("No planner request to retry", "NO_PLANNER_RETRY")
        plannerKeySetupRequest.value = null
        return planTranscript(retry)
    }

    suspend fun handlePlanning(goal: String, diagnosticSessionId: String? = null): ActionResult {
        val settings = settingsRepository.settings.first()
        if (settings.privacyModeEnabled) {
            return when (settings.executionMode) {
                ExecutionMode.LOCAL_RULE_FIRST -> planTranscript(goal, diagnosticSessionId, recordPrompt = false)
                ExecutionMode.LOCAL_LLM_LOOP -> runLocalLlmLoop(goal, settings.maxAutonomousSteps, diagnosticSessionId)
                ExecutionMode.AGENT_LOOP -> runAgentLoop(goal, diagnosticSessionId)
                ExecutionMode.MOBILERUN_CLOUD_TASK -> finish(
                    ActionResult.fail(
                        "Privacy mode disables Mobilerun cloud tasks. Use local action loop or reviewed planning.",
                        "PRIVACY_MODE_CLOUD_DISABLED"
                    )
                )
            }
        }
        if (settings.executionMode == ExecutionMode.AGENT_LOOP) {
            return runAgentLoop(goal, diagnosticSessionId)
        }
        artifactAgentLoopReason(goal, diagnosticSessionId)?.let { reason ->
            debugEvent(diagnosticSessionId, "planner_agent_override", mapOf("reason" to reason, "executionMode" to settings.executionMode.name))
            return runAgentLoop(goal, diagnosticSessionId)
        }
        return when (settings.executionMode) {
            ExecutionMode.LOCAL_RULE_FIRST -> planTranscript(goal, diagnosticSessionId, recordPrompt = false)
            ExecutionMode.LOCAL_LLM_LOOP -> runLocalLlmLoop(goal, settings.maxAutonomousSteps, diagnosticSessionId)
            ExecutionMode.AGENT_LOOP -> runAgentLoop(goal, diagnosticSessionId)
            ExecutionMode.MOBILERUN_CLOUD_TASK -> runMobilerunTask(goal)
        }
    }

    private suspend fun executePlan(pending: PendingPlan): ActionResult {
        val settings = settingsRepository.settings.first()
        val sessionId = pending.diagnosticSessionId
        pendingPlan.value = null
        if (pending.plan.steps.isEmpty()) {
            debugEvent(sessionId, "plan_execute_failed", mapOf("reason" to "empty_plan"))
            return finish(ActionResult.fail("Planner returned no steps", "EMPTY_PLAN"))
        }
        val stepsToRun = pending.plan.steps.take(settings.maxAutonomousSteps)
        debugEvent(sessionId, "plan_execute_started", mapOf("stepCount" to pending.plan.steps.size, "maxSteps" to settings.maxAutonomousSteps, "summaryLength" to pending.plan.summary.length))
        uiState.value = uiState.value.copy(status = "Executing plan", parsedAction = "PLAN ${pending.plan.steps.size} steps")
        var last = ActionResult.ok("Started plan")
        for ((position, step) in stepsToRun.withIndex()) {
            cancellationResult()?.let { return finish(it) }
            val state = runCatching { portalController.getState() }.getOrNull()
            val safety = safetyClassifier.classify(pending.transcript, step.action, state, settings.sensitiveAppScreenshotDenylist)
            debugEvent(sessionId, "plan_step_ready", mapOf("index" to step.index, "action" to step.actionLabel, "requiresConfirmation" to step.requiresConfirmation))
            recordSafetyDecision(sessionId, "plan_step_${step.index}", safety, settings.requireRiskConfirmation)
            val confidencePolicy = ActionConfidencePolicy.evaluate(
                confidence = step.confidence,
                action = step.action,
                safetyRequiresConfirmation = safety.requiresConfirmation || safety.mandatoryConfirmation
            )
            if (!confidencePolicy.allowed) {
                val message = confidencePolicy.reason ?: "Planner confidence policy blocked ${step.actionLabel}"
                debugEvent(sessionId, "plan_step_confidence_blocked", mapOf("index" to step.index, "action" to step.actionLabel, "confidence" to step.confidence.name, "message" to message))
                return finish(ActionResult.fail(message, "PLAN_CONFIDENCE_BLOCKED"))
            }
            if (confidencePolicy.requiresConfirmation || (step.requiresConfirmation && settings.requireRiskConfirmation) || safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
                val confirmed = requestConfirmation(
                    pending.transcript,
                    step.action,
                    safety.reason ?: step.reason.ifBlank { "Planner marked this step as sensitive" },
                    sessionId,
                    null
                )
                if (!confirmed) return finish(ActionResult.fail("Plan cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
            }
            logs.log(ActionLogType.ACTION_STARTED, "Plan step ${step.index}: ${step.actionLabel}", step.reason)
            last = executeAction(step.action, pending.transcript, false, sessionId)
            debugEvent(sessionId, "plan_step_result", mapOf("index" to step.index, "action" to step.actionLabel, "success" to last.success, "message" to last.message, "errorCode" to last.errorCode))
            if (!last.success) return finish(last)
            if (step.action is DroidLmAction.NavigateToArtifactTarget && position < stepsToRun.lastIndex) {
                debugEvent(
                    sessionId,
                    "plan_handoff_after_artifact_navigation",
                    mapOf("index" to step.index, "remainingSteps" to (stepsToRun.lastIndex - position))
                )
                return runAgentLoop(pending.transcript, sessionId)
            }
        }
        debugEvent(sessionId, "plan_execute_succeeded", mapOf("stepCount" to stepsToRun.size, "lastMessage" to last.message))
        return finish(ActionResult.ok("Plan executed: ${pending.plan.summary}"))
    }

    private suspend fun runLocalLlmLoop(goal: String, maxSteps: Int, diagnosticSessionId: String? = null): ActionResult {
        val settings = settingsRepository.settings.first()
        val history = mutableListOf<String>()
        var lastResult = ActionResult.ok("Started local LLM loop")
        for (step in 1..maxSteps.coerceAtLeast(1)) {
            cancellationResult()?.let { return finish(it) }
            debugEvent(diagnosticSessionId, "local_loop_step_started", mapOf("step" to step, "maxSteps" to maxSteps, "historySize" to history.size, "endpointMode" to if (settings.privacyModeEnabled) "on_device_qwen3_action_loop" else "direct_openai"))
            uiState.value = uiState.value.copy(status = "Planning step $step/$maxSteps")
            val state = portalController.getState()
            val deviceContext = deviceContextAggregator.collect(goal, state, history, diagnosticSessionId)
            val packages = deviceContext.packages
            val activeApp = deviceContext.activeApp
            val request = RelayPlanRequest(goal, state, packages, history, maxSteps, activeApp, deviceContext)
            val planned = if (settings.privacyModeEnabled) {
                onDevicePlanner.planActionWithMetadata(request, step, maxSteps)
            } else {
                val apiKey = settingsRepository.getOpenAiApiKey().orEmpty()
                if (apiKey.isBlank()) {
                    plannerKeySetupRequest.value = PlannerKeySetupRequest(
                        kind = PlannerSetupKind.OPENAI_API_KEY,
                        message = "Cloud planning needs a planning key saved on this device.",
                        retryTranscript = goal
                    )
                    return finish(ActionResult.fail("Cloud planning needs a planning key", "OPENAI_API_KEY_MISSING"))
                }
                openAiClient.planActionWithMetadata(apiKey, settings.openAiModel, request)
            }
            when (planned) {
                is RelayCallResult.Failure -> {
                    if (settings.privacyModeEnabled && (planned.errorCode == OnDevicePlanner.ERROR_MODEL_MISSING || planned.errorCode == OnDevicePlanner.ERROR_MODEL_UNSUPPORTED)) {
                        plannerKeySetupRequest.value = PlannerKeySetupRequest(
                            kind = PlannerSetupKind.ON_DEVICE_MODEL,
                            message = onDevicePlanner.setupMessage(),
                            retryTranscript = goal
                        )
                    }
                    return finish(ActionResult.fail(if (settings.privacyModeEnabled) planned.message else userFacingPlannerMessage(planned), planned.errorCode))
                }
                is RelayCallResult.Success -> {
                    val plannedAction = planned.value
                    val action = plannedAction.action
                    logs.log(ActionLogType.PARSED_ACTION, action.displayName())
                    if (action == DroidLmAction.Done) return finish(ActionResult.ok("Task complete"))
                    val safety = safetyClassifier.classify(goal, action, state, settings.sensitiveAppScreenshotDenylist)
                    recordSafetyDecision(diagnosticSessionId, "local_loop_step_$step", safety, settings.requireRiskConfirmation)
                    val confidencePolicy = ActionConfidencePolicy.evaluate(
                        confidence = plannedAction.confidence,
                        action = action,
                        safetyRequiresConfirmation = safety.requiresConfirmation || safety.mandatoryConfirmation
                    )
                    if (!confidencePolicy.allowed) {
                        val message = confidencePolicy.reason ?: "Planner confidence policy blocked ${action.displayName()}"
                        debugEvent(diagnosticSessionId, "local_loop_confidence_blocked", mapOf("step" to step, "action" to action.displayName(), "confidence" to plannedAction.confidence.name, "message" to message))
                        history += "${action.displayName()} blocked by confidence policy: $message"
                        lastResult = ActionResult.fail(message, "PLAN_CONFIDENCE_BLOCKED")
                        continue
                    }
                    if (confidencePolicy.requiresConfirmation || safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
                        val confirmed = requestConfirmation(goal, action, safety.reason ?: confidencePolicy.reason ?: "This action is sensitive", diagnosticSessionId, null)
                        if (!confirmed) return finish(ActionResult.fail("Planner action cancelled", "CONFIRMATION_REJECTED"))
                    }
                    lastResult = executeAction(action, goal, false, diagnosticSessionId)
                    debugEvent(diagnosticSessionId, "local_loop_step_result", mapOf("step" to step, "action" to action.displayName(), "success" to lastResult.success, "errorCode" to lastResult.errorCode, "expectedResult" to plannedAction.expectedResult))
                    history += "${action.displayName()} -> ${lastResult.success}: ${lastResult.message}"
                    if (!lastResult.success || action is DroidLmAction.NoOp) return finish(lastResult)
                }
            }
        }
        return finish(ActionResult.fail("Reached max autonomous step limit ($maxSteps)", "MAX_STEPS_REACHED"))
    }

    private fun SafetyDecision.needsConfirmationPrompt(requireRiskConfirmation: Boolean): Boolean =
        requiresConfirmation && (requireRiskConfirmation || mandatoryConfirmation)

    private fun debugEvent(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        executionDiagnostics.debugEvent(sessionId, event, fields)
    }

    private fun recordSafetyDecision(sessionId: String?, source: String, safety: SafetyDecision, requireRiskConfirmation: Boolean) {
        executionDiagnostics.recordSafetyDecision(sessionId, source, safety, requireRiskConfirmation)
    }

    private suspend fun artifactAgentLoopReason(goal: String, diagnosticSessionId: String?): String? {
        val query = ArtifactContextBuilder.extractNavigationRequest(goal) ?: return null
        val state = runCatching { portalController.getState() }.getOrNull() ?: return null
        if (!ArtifactContextBuilder.supportsArtifactPackage(state.packageName)) return null
        val deviceContext = runCatching { deviceContextAggregator.collect(goal, state, diagnosticSessionId = diagnosticSessionId) }.getOrNull()
        val artifactContext = deviceContext?.extras?.optJSONObject("artifactContext")
        return if (ArtifactContextBuilder.hasMatchingTarget(artifactContext, query)) {
            "matching_artifact_target:$query"
        } else {
            "artifact_navigation_goal:$query"
        }
    }
}
