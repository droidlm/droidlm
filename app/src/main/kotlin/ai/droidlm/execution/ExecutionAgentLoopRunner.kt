package ai.droidlm.execution

import ai.droidlm.agent.AgentBudgets
import ai.droidlm.agent.AgentDecisionStatus
import ai.droidlm.agent.AgentDoNotRepeat
import ai.droidlm.agent.AgentRecoveryCandidate
import ai.droidlm.agent.AgentRecoveryPolicy
import ai.droidlm.agent.AgentToolRegistry
import ai.droidlm.agent.AgentToolResult
import ai.droidlm.agent.AgentToolCall
import ai.droidlm.agent.AgentTurnRequest
import ai.droidlm.agent.AgentVerifier
import ai.droidlm.agent.ToolRisk
import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.context.GoogleWorkspaceContextUtils
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.displayName
import ai.droidlm.ondevice.OnDevicePlanner
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.safety.SafetyClassifier
import ai.droidlm.safety.SafetyDecision
import ai.droidlm.settings.DroidLmSettings
import ai.droidlm.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

internal class ExecutionAgentLoopRunner(
    private val settingsRepository: SettingsRepository,
    private val openAiClient: OpenAiClient,
    private val onDevicePlanner: OnDevicePlanner,
    private val portalController: PortalController,
    private val appInventoryRepository: AppInventoryRepository,
    private val deviceContextAggregator: DeviceContextAggregator,
    private val safetyClassifier: SafetyClassifier,
    private val agentToolRegistry: AgentToolRegistry,
    private val agentVerifier: AgentVerifier,
    private val agentRecoveryPolicy: AgentRecoveryPolicy,
    private val uiState: MutableStateFlow<ExecutionUiState>,
    private val plannerKeySetupRequest: MutableStateFlow<PlannerKeySetupRequest?>,
    private val executionDiagnostics: ExecutionDiagnostics,
    private val cancellationResult: () -> ActionResult?,
    private val finish: (ActionResult) -> ActionResult,
    private val executeAction: suspend (DroidLmAction, String, Boolean, String?) -> ActionResult,
    private val requestConfirmation: suspend (String, DroidLmAction, String, String?, String?) -> Boolean,
    private val userFacingPlannerMessage: (RelayCallResult.Failure) -> String
) {
    suspend fun run(goal: String, diagnosticSessionId: String?): ActionResult {
        val settings = settingsRepository.settings.first()
        val privacyModeEnabled = settings.privacyModeEnabled
        val activeState = runCatching { portalController.getState() }.getOrNull()
        val workspaceArtifactActive = activeState?.packageName in setOf(
            GoogleWorkspaceContextUtils.DOCS_PACKAGE,
            GoogleWorkspaceContextUtils.SHEETS_PACKAGE,
            GoogleWorkspaceContextUtils.DRIVE_PACKAGE
        )
        val budgets = AgentBudgets(
            maxTurns = if (workspaceArtifactActive) maxOf(settings.maxAgentTurns, 12) else settings.maxAgentTurns,
            maxToolCallsTotal = if (workspaceArtifactActive) maxOf(settings.maxAgentToolCalls, 16) else settings.maxAgentToolCalls,
            maxToolCallsPerTurn = if (privacyModeEnabled) 1 else if (workspaceArtifactActive) 4 else AgentBudgets.DEFAULT_MAX_TOOL_CALLS_PER_TURN,
            maxMutatingToolCallsPerTurn = if (privacyModeEnabled) 1 else if (workspaceArtifactActive) 3 else AgentBudgets.DEFAULT_MAX_MUTATING_TOOL_CALLS_PER_TURN
        ).normalized()
        val apiKey = if (privacyModeEnabled) "" else settingsRepository.getOpenAiApiKey().orEmpty()
        if (!privacyModeEnabled && apiKey.isBlank()) {
            plannerKeySetupRequest.value = PlannerKeySetupRequest(
                kind = PlannerSetupKind.OPENAI_API_KEY,
                message = "This command needs a cloud planning key saved on this device.",
                retryTranscript = goal
            )
            return finish(ActionResult.fail("Cloud planning needs a planning key", "OPENAI_API_KEY_MISSING"))
        }

        val startedAt = System.currentTimeMillis()
        val history = mutableListOf<String>()
        val toolResults = mutableListOf<AgentToolResult>()
        val callsByTool = mutableMapOf<String, Int>()
        val recoveryAttempts = mutableMapOf<String, Int>()
        var totalToolCalls = 0
        var consecutiveFailures = 0
        var lastResult = ActionResult.ok("Started agent loop")
        debugEvent(
            diagnosticSessionId,
            "agent_loop_started",
            mapOf(
                "endpointMode" to if (privacyModeEnabled) "on_device_qwen3_agent_loop" else "direct_openai_agent_loop",
                "maxTurns" to budgets.maxTurns,
                "maxToolCallsTotal" to budgets.maxToolCallsTotal,
                "maxToolCallsPerTurn" to budgets.maxToolCallsPerTurn,
                "maxMutatingToolCallsPerTurn" to budgets.maxMutatingToolCallsPerTurn,
                "maxRuntimeMs" to budgets.maxRuntimeMs
            )
        )

        for (turn in 1..budgets.maxTurns) {
            cancellationResult()?.let { return finish(it) }
            if (totalToolCalls >= budgets.maxToolCallsTotal) {
                return finish(ActionResult.fail("Agent reached total tool-call limit ${budgets.maxToolCallsTotal}", "AGENT_TOTAL_TOOL_LIMIT"))
            }
            if (System.currentTimeMillis() - startedAt > budgets.maxRuntimeMs) {
                return finish(ActionResult.fail("Agent stopped after ${budgets.maxRuntimeMs / 1000}s runtime limit", "AGENT_RUNTIME_LIMIT"))
            }
            uiState.value = uiState.value.copy(status = "Agent turn $turn/${budgets.maxTurns} (${totalToolCalls}/${budgets.maxToolCallsTotal} tools)")
            var currentState = runCatching { portalController.getState() }.getOrNull()
            val deviceContext = runCatching { deviceContextAggregator.collect(goal, currentState, history, diagnosticSessionId) }.getOrNull()
            val packages = deviceContext?.packages ?: runCatching { appInventoryRepository.getInstalledApps() }.getOrDefault(emptyList())
            val activeApp = deviceContext?.activeApp ?: runCatching { appInventoryRepository.activeAppFor(currentState) }.getOrNull()
            debugEvent(
                diagnosticSessionId,
                "agent_turn_started",
                mapOf("turn" to turn, "toolCalls" to totalToolCalls, "historySize" to history.size, "packageCount" to packages.size, "activePackage" to activeApp?.packageName)
            )
            val doNotRepeat = buildDoNotRepeat(toolResults)
            val request = AgentTurnRequest(
                goal = goal,
                turnIndex = turn,
                budgets = budgets,
                remainingToolCalls = budgets.maxToolCallsTotal - totalToolCalls,
                uiState = currentState,
                packages = packages,
                history = history.takeLast(12),
                activeApp = activeApp,
                deviceContext = deviceContext,
                lastResults = toolResults.takeLast(5),
                doNotRepeat = doNotRepeat
            )
            val decision = when (val agentResult = if (privacyModeEnabled) onDevicePlanner.nextAgentTurn(request) else openAiClient.nextAgentTurn(apiKey, settings.openAiModel, request)) {
                is RelayCallResult.Failure -> {
                    if (privacyModeEnabled && (agentResult.errorCode == OnDevicePlanner.ERROR_MODEL_MISSING || agentResult.errorCode == OnDevicePlanner.ERROR_MODEL_UNSUPPORTED)) {
                        plannerKeySetupRequest.value = PlannerKeySetupRequest(
                            kind = PlannerSetupKind.ON_DEVICE_MODEL,
                            message = onDevicePlanner.setupMessage(),
                            retryTranscript = goal
                        )
                    }
                    return finish(ActionResult.fail(if (privacyModeEnabled) agentResult.message else userFacingPlannerMessage(agentResult), agentResult.errorCode))
                }
                is RelayCallResult.Success -> agentResult.value
            }
            debugEvent(
                diagnosticSessionId,
                "agent_turn_decision",
                mapOf("turn" to turn, "status" to decision.status.name, "messageLength" to decision.message.length, "toolCallCount" to decision.toolCalls.size)
            )
            when (decision.status) {
                AgentDecisionStatus.DONE -> return finish(ActionResult.ok(decision.message.ifBlank { "Task complete" }))
                AgentDecisionStatus.ASK_USER -> return finish(ActionResult.fail(decision.message.ifBlank { "Please clarify the request" }, "AGENT_ASK_USER"))
                AgentDecisionStatus.NO_OP -> return finish(ActionResult.fail(decision.message.ifBlank { "Agent found no safe action" }, "AGENT_NO_OP"))
                AgentDecisionStatus.CALL_TOOLS -> Unit
            }
            if (decision.toolCalls.isEmpty()) {
                return finish(ActionResult.fail("Agent requested no tools", "AGENT_EMPTY_TOOL_CALLS"))
            }
            if (decision.toolCalls.size > budgets.maxToolCallsPerTurn) {
                return finish(ActionResult.fail("Agent requested ${decision.toolCalls.size} tools, over per-turn limit ${budgets.maxToolCallsPerTurn}", "AGENT_TURN_TOOL_LIMIT"))
            }
            if (totalToolCalls + decision.toolCalls.size > budgets.maxToolCallsTotal) {
                return finish(ActionResult.fail("Agent exceeded total tool-call limit ${budgets.maxToolCallsTotal}", "AGENT_TOTAL_TOOL_LIMIT"))
            }

            var mutatingCallsThisTurn = 0
            var shouldObserveAgain = false
            for (call in decision.toolCalls) {
                val beforeToolState = currentState
                val executionResult = agentToolRegistry.toExecution(call, beforeToolState, packages, callsByTool)
                if (executionResult.isFailure) {
                    val error = executionResult.exceptionOrNull()
                    debugEvent(diagnosticSessionId, "agent_tool_validation_failed", mapOf("turn" to turn, "tool" to call.name, "message" to error?.message))
                    history += "${call.name}[${call.id}] validation failed: ${error?.message}"
                    val recovery = agentRecoveryPolicy.recoverValidationFailure(call, error?.message)
                    if (recovery != null && canAttemptRecovery(recovery, recoveryAttempts) && totalToolCalls < budgets.maxToolCallsTotal) {
                        val recoveryResult = executeAgentRecovery(
                            goal = goal,
                            settings = settings,
                            beforeState = beforeToolState,
                            recovery = recovery,
                            diagnosticSessionId = diagnosticSessionId,
                            turn = turn,
                            failedCallId = call.id,
                            deviceContextExtras = deviceContext?.extras
                        )
                        totalToolCalls += 1
                        recoveryAttempts[recovery.key] = (recoveryAttempts[recovery.key] ?: 0) + 1
                        toolResults += recoveryResult
                        history += recoveryResult.summary()
                        lastResult = recoveryResult.result
                        debugEvent(diagnosticSessionId, "agent_validation_recovery_result", mapOf("turn" to turn, "tool" to call.name, "recoveryKey" to recovery.key, "success" to recoveryResult.result.success, "errorCode" to recoveryResult.result.errorCode))
                        if (lastResult.success) {
                            consecutiveFailures = 0
                        } else {
                            consecutiveFailures += 1
                            if (consecutiveFailures >= budgets.maxConsecutiveFailures) {
                                return finish(ActionResult.fail("Agent recovery failed after validation failure: ${lastResult.message}", "AGENT_RECOVERY_FAILED"))
                            }
                        }
                    } else {
                        consecutiveFailures += 1
                        if (consecutiveFailures >= budgets.maxConsecutiveFailures) {
                            return finish(ActionResult.fail("Invalid agent tool ${call.name}: ${error?.message}", "AGENT_TOOL_VALIDATION_FAILED"))
                        }
                    }
                    shouldObserveAgain = true
                    break
                }
                val execution = executionResult.getOrThrow()
                val repeat = doNotRepeat.firstOrNull { it.signature == signatureFor(call) }
                if (repeat != null) {
                    val message = "Agent attempted to repeat ${repeat.tool} on ${repeat.target}: ${repeat.reason}"
                    debugEvent(diagnosticSessionId, "agent_tool_repeat_blocked", mapOf("turn" to turn, "tool" to call.name, "target" to repeat.target, "reason" to repeat.reason))
                    history += "${call.name}[${call.id}] blocked: $message"
                    consecutiveFailures += 1
                    if (consecutiveFailures >= budgets.maxConsecutiveFailures) {
                        return finish(ActionResult.fail(message, "AGENT_REPEAT_BLOCKED"))
                    }
                    shouldObserveAgain = true
                    break
                }
                if (execution.spec.mutating) {
                    mutatingCallsThisTurn += 1
                    if (mutatingCallsThisTurn > budgets.maxMutatingToolCallsPerTurn) {
                        return finish(ActionResult.fail("Agent exceeded mutating tool-call limit ${budgets.maxMutatingToolCallsPerTurn} for one turn", "AGENT_MUTATING_TOOL_LIMIT"))
                    }
                }

                val safety = safetyClassifier.classify(goal, execution.action, beforeToolState, settings.sensitiveAppScreenshotDenylist)
                recordSafetyDecision(diagnosticSessionId, "agent_turn_${turn}_${call.id}", safety, settings.requireRiskConfirmation)
                if (safety.blocked) {
                    return finish(ActionResult.fail(safety.reason ?: "Agent action blocked by safety policy", "AGENT_SAFETY_BLOCKED"))
                }
                val confidencePolicy = ActionConfidencePolicy.evaluate(
                    confidence = call.confidence,
                    action = execution.action,
                    risk = execution.spec.risk,
                    mutating = execution.spec.mutating,
                    safetyRequiresConfirmation = safety.requiresConfirmation || safety.mandatoryConfirmation
                )
                if (!confidencePolicy.allowed) {
                    val message = confidencePolicy.reason ?: "Agent confidence policy blocked ${execution.spec.name}"
                    debugEvent(diagnosticSessionId, "agent_confidence_policy_blocked", mapOf("turn" to turn, "tool" to execution.spec.name, "confidence" to call.confidence.name, "message" to message))
                    history += "${execution.spec.name}[${call.id}] blocked by confidence policy: $message"
                    consecutiveFailures += 1
                    if (consecutiveFailures >= budgets.maxConsecutiveFailures) {
                        return finish(ActionResult.fail(message, "AGENT_CONFIDENCE_BLOCKED"))
                    }
                    shouldObserveAgain = true
                    break
                }
                val needsConfirmation = confidencePolicy.requiresConfirmation || safety.needsConfirmationPrompt(settings.requireRiskConfirmation) || agentToolNeedsConfirmation(execution.spec.risk, settings.requireRiskConfirmation)
                if (needsConfirmation) {
                    val confirmed = requestConfirmation(
                        goal,
                        execution.action,
                        safety.reason ?: "Agent requested ${execution.spec.risk.name.lowercase()} tool ${execution.spec.name}",
                        diagnosticSessionId,
                        null
                    )
                    if (!confirmed) return finish(ActionResult.fail("Agent action cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
                }

                val rawResult = executeAction(execution.action, goal, false, diagnosticSessionId)
                totalToolCalls += 1
                callsByTool[execution.spec.name] = (callsByTool[execution.spec.name] ?: 0) + 1
                val requiresFreshObservation = agentToolRegistry.isFreshObservationRequired(execution.action, execution.spec)
                val afterState = if (requiresFreshObservation || agentVerifier.needsFreshState(execution.action)) {
                    runCatching { portalController.getState() }.getOrNull()
                } else {
                    null
                }
                val verification = agentVerifier.verify(
                    action = execution.action,
                    actionResult = rawResult,
                    beforeState = beforeToolState,
                    afterState = afterState,
                    goal = goal,
                    deviceContextExtras = deviceContext?.extras
                )
                lastResult = if (rawResult.success && verification.failed) {
                    ActionResult.fail("Agent verification failed: ${verification.message}", "AGENT_VERIFICATION_FAILED")
                } else {
                    rawResult
                }
                val toolResult = AgentToolResult(
                    callId = call.id,
                    toolName = execution.spec.name,
                    result = lastResult,
                    mutating = execution.spec.mutating,
                    requiresFreshObservationAfter = requiresFreshObservation,
                    verification = verification,
                    target = targetFor(call, execution.action),
                    signature = signatureFor(call),
                    confidence = call.confidence,
                    expectedResult = call.expectedResult
                )
                toolResults += toolResult
                history += toolResult.summary()
                currentState = afterState ?: currentState
                debugEvent(
                    diagnosticSessionId,
                    "agent_tool_result",
                    mapOf(
                        "turn" to turn,
                        "tool" to execution.spec.name,
                        "callId" to call.id,
                        "success" to lastResult.success,
                        "errorCode" to lastResult.errorCode,
                        "freshObservation" to requiresFreshObservation,
                        "verificationStatus" to verification.status.name,
                        "verificationMessage" to verification.message,
                        "confidence" to call.confidence.name,
                        "expectedResult" to call.expectedResult
                    )
                )
                if (!lastResult.success) {
                    val recovery = agentRecoveryPolicy.recoverVerificationFailure(execution.action, verification)
                    if (recovery != null && canAttemptRecovery(recovery, recoveryAttempts) && totalToolCalls < budgets.maxToolCallsTotal) {
                        val recoveryResult = executeAgentRecovery(
                            goal = goal,
                            settings = settings,
                            beforeState = afterState ?: beforeToolState,
                            recovery = recovery,
                            diagnosticSessionId = diagnosticSessionId,
                            turn = turn,
                            failedCallId = call.id,
                            deviceContextExtras = deviceContext?.extras
                        )
                        totalToolCalls += 1
                        recoveryAttempts[recovery.key] = (recoveryAttempts[recovery.key] ?: 0) + 1
                        toolResults += recoveryResult
                        history += recoveryResult.summary()
                        lastResult = recoveryResult.result
                        debugEvent(diagnosticSessionId, "agent_verification_recovery_result", mapOf("turn" to turn, "tool" to execution.spec.name, "recoveryKey" to recovery.key, "success" to recoveryResult.result.success, "errorCode" to recoveryResult.result.errorCode))
                    }
                    if (lastResult.success) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures += 1
                        if (consecutiveFailures >= budgets.maxConsecutiveFailures) {
                            return finish(ActionResult.fail("Agent stopped after ${budgets.maxConsecutiveFailures} consecutive failures: ${lastResult.message}", "AGENT_REPEATED_FAILURE"))
                        }
                    }
                    shouldObserveAgain = true
                    break
                }
                consecutiveFailures = 0
                if (execution.action == DroidLmAction.Done) return finish(ActionResult.ok(lastResult.message))
                if (requiresFreshObservation) {
                    val continueWithinTurn = canContinueWithinSameArtifactTurn(
                        action = execution.action,
                        beforeState = beforeToolState,
                        afterState = currentState,
                        hasMoreCalls = decision.toolCalls.last() != call
                    )
                    if (!continueWithinTurn) {
                        if (decision.toolCalls.last() != call) {
                            debugEvent(diagnosticSessionId, "agent_turn_truncated_for_observation", mapOf("turn" to turn, "afterTool" to execution.spec.name))
                        }
                        shouldObserveAgain = true
                        break
                    }
                }
            }
            if (!shouldObserveAgain && lastResult.success && totalToolCalls >= budgets.maxToolCallsTotal) {
                return finish(ActionResult.fail("Agent reached total tool-call limit ${budgets.maxToolCallsTotal}", "AGENT_TOTAL_TOOL_LIMIT"))
            }
        }
        return finish(ActionResult.fail("Agent reached turn limit (${budgets.maxTurns})", "AGENT_MAX_TURNS"))
    }

    private fun buildDoNotRepeat(results: List<AgentToolResult>): List<AgentDoNotRepeat> = results
        .asReversed()
        .filter { result -> result.mutating && !result.signature.isNullOrBlank() && shouldAvoidRepeating(result) }
        .distinctBy { result -> result.signature }
        .take(8)
        .map { result ->
            AgentDoNotRepeat(
                tool = result.toolName,
                target = result.target ?: result.signature.orEmpty(),
                reason = doNotRepeatReason(result),
                signature = result.signature
            )
        }

    private fun shouldAvoidRepeating(result: AgentToolResult): Boolean {
        if (!result.result.success) return true
        val verificationMessage = result.verification?.message.orEmpty()
        return verificationMessage.contains("UI signature did not change", ignoreCase = true) ||
            verificationMessage.contains("verification failed", ignoreCase = true)
    }

    private fun doNotRepeatReason(result: AgentToolResult): String {
        if (!result.result.success) return result.result.message
        return result.verification?.message ?: "No observable result after mutating action"
    }

    private fun signatureFor(call: AgentToolCall): String {
        val args = call.args
        val target = listOf(
            args.optString("nodeId"),
            args.optString("targetNodeId"),
            args.optString("text"),
            args.optString("label"),
            args.optString("targetText"),
            args.optString("anchorText"),
            args.optString("packageName"),
            args.optString("appName"),
            args.optString("uri"),
            args.optString("url")
        ).firstOrNull { it.isNotBlank() }
            ?: listOf(args.optString("x"), args.optString("y")).filter { it.isNotBlank() }.joinToString(",").ifBlank { call.reason }
        return "${call.name}:${target.trim().lowercase()}"
    }

    private fun targetFor(call: AgentToolCall, action: DroidLmAction): String = when (action) {
        is DroidLmAction.OpenApp -> action.appName ?: action.packageName
        is DroidLmAction.OpenAppStoreListing -> action.appName ?: action.packageName
        is DroidLmAction.TapNode -> action.nodeId
        is DroidLmAction.FocusNode -> action.nodeId
        is DroidLmAction.TapText -> action.text
        is DroidLmAction.LongPressNode -> action.nodeId ?: action.text ?: call.reason
        is DroidLmAction.Scroll -> action.targetNodeId ?: action.untilText ?: action.direction.name
        is DroidLmAction.NavigateToArtifactTarget -> action.label
        is DroidLmAction.SetToggle -> action.nodeId ?: action.label ?: call.reason
        is DroidLmAction.ExpandCollapse -> action.nodeId ?: action.label ?: call.reason
        is DroidLmAction.SetSlider -> action.nodeId ?: action.label ?: call.reason
        is DroidLmAction.FindTextOnScreen -> action.text
        is DroidLmAction.SearchAccessibilityContent -> action.query ?: action.sectionLabel ?: action.exclude ?: call.reason
        is DroidLmAction.SwitchApp -> action.appName ?: action.packageName ?: call.reason
        is DroidLmAction.OpenUrl -> action.url
        is DroidLmAction.OpenDeepLink -> action.uri
        is DroidLmAction.PickFromChooser -> action.itemText
        is DroidLmAction.PickFile -> action.fileName
        is DroidLmAction.PickPhoto -> action.photoLabel
        is DroidLmAction.ShareToApp -> action.appName ?: action.packageName ?: call.reason
        is DroidLmAction.SetSelection -> action.nodeId ?: "${action.start}-${action.end}"
        is DroidLmAction.SetFullText -> action.nodeId ?: "current editable"
        is DroidLmAction.TapTextAnchor -> action.anchorText
        is DroidLmAction.VerifyTextChange -> action.expectedText
        is DroidLmAction.InsertTextAtAnchor -> action.anchorText
        is DroidLmAction.ReplaceTextRange -> action.targetText
        is DroidLmAction.ReplaceDocumentText -> action.targetText
        else -> call.reason.ifBlank { action.displayName() }
    }

    private fun canContinueWithinSameArtifactTurn(
        action: DroidLmAction,
        beforeState: PortalState?,
        afterState: PortalState?,
        hasMoreCalls: Boolean
    ): Boolean {
        if (!hasMoreCalls) return false
        val sameArtifactAction = when (action) {
            is DroidLmAction.NavigateToArtifactTarget,
            is DroidLmAction.ReplaceTextRange,
            is DroidLmAction.InsertTextAtAnchor,
            is DroidLmAction.ApplyDocumentEdits,
            is DroidLmAction.FindTextOnScreen -> true
            else -> false
        }
        if (!sameArtifactAction) return false
        val beforePackage = beforeState?.packageName ?: return false
        val afterPackage = afterState?.packageName ?: return false
        if (beforePackage != afterPackage) return false
        if (afterPackage !in setOf(
                GoogleWorkspaceContextUtils.DOCS_PACKAGE,
                GoogleWorkspaceContextUtils.SHEETS_PACKAGE,
                GoogleWorkspaceContextUtils.DRIVE_PACKAGE
            )
        ) return false
        return afterState.nodes.any { it.editable }
    }

    private fun canAttemptRecovery(recovery: AgentRecoveryCandidate, attempts: Map<String, Int>): Boolean =
        (attempts[recovery.key] ?: 0) < 1

    private suspend fun executeAgentRecovery(
        goal: String,
        settings: DroidLmSettings,
        beforeState: PortalState?,
        recovery: AgentRecoveryCandidate,
        diagnosticSessionId: String?,
        turn: Int,
        failedCallId: String,
        deviceContextExtras: JSONObject?
    ): AgentToolResult {
        debugEvent(
            diagnosticSessionId,
            "agent_recovery_started",
            mapOf("turn" to turn, "failedCallId" to failedCallId, "recoveryKey" to recovery.key, "action" to recovery.action.displayName(), "reason" to recovery.reason)
        )
        val safety = safetyClassifier.classify(goal, recovery.action, beforeState, settings.sensitiveAppScreenshotDenylist)
        recordSafetyDecision(diagnosticSessionId, "agent_recovery_${turn}_$failedCallId", safety, settings.requireRiskConfirmation)
        if (safety.blocked) {
            val result = ActionResult.fail(safety.reason ?: "Recovery action blocked by safety policy", "AGENT_RECOVERY_SAFETY_BLOCKED")
            return AgentToolResult("recovery_$failedCallId", "RECOVERY", result, mutating = true, requiresFreshObservationAfter = true)
        }
        val recoveryRisk = when (recovery.action) {
            is DroidLmAction.OpenAppStoreListing -> ToolRisk.INSTALL_OR_STORE
            is DroidLmAction.OpenUrl,
            is DroidLmAction.OpenDeepLink,
            is DroidLmAction.ShareToApp -> ToolRisk.EXTERNAL_SHARE
            else -> ToolRisk.SAFE_NAVIGATION
        }
        val needsConfirmation = safety.needsConfirmationPrompt(settings.requireRiskConfirmation) || agentToolNeedsConfirmation(recoveryRisk, settings.requireRiskConfirmation)
        if (needsConfirmation) {
            val confirmed = requestConfirmation(
                goal,
                recovery.action,
                safety.reason ?: recovery.reason,
                diagnosticSessionId,
                null
            )
            if (!confirmed) {
                val result = ActionResult.fail("Recovery action cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED")
                return AgentToolResult("recovery_$failedCallId", "RECOVERY", result, mutating = true, requiresFreshObservationAfter = true)
            }
        }
        val rawResult = executeAction(recovery.action, goal, false, diagnosticSessionId)
        val afterState = runCatching { portalController.getState() }.getOrNull()
        val verification = agentVerifier.verify(
            action = recovery.action,
            actionResult = rawResult,
            beforeState = beforeState,
            afterState = afterState,
            goal = goal,
            deviceContextExtras = deviceContextExtras
        )
        val result = if (rawResult.success && verification.failed) {
            ActionResult.fail("Recovery verification failed: ${verification.message}", "AGENT_RECOVERY_VERIFICATION_FAILED")
        } else {
            rawResult
        }
        debugEvent(
            diagnosticSessionId,
            "agent_recovery_result",
            mapOf("turn" to turn, "failedCallId" to failedCallId, "recoveryKey" to recovery.key, "success" to result.success, "errorCode" to result.errorCode, "verificationStatus" to verification.status.name)
        )
        return AgentToolResult("recovery_$failedCallId", "RECOVERY", result, mutating = true, requiresFreshObservationAfter = true, verification = verification)
    }

    private fun agentToolNeedsConfirmation(risk: ToolRisk, requireRiskConfirmation: Boolean): Boolean {
        if (!requireRiskConfirmation) return risk in setOf(ToolRisk.EXTERNAL_SHARE, ToolRisk.INSTALL_OR_STORE, ToolRisk.PERMISSION_OR_CREDENTIAL)
        return risk in setOf(ToolRisk.SENSITIVE, ToolRisk.EXTERNAL_SHARE, ToolRisk.INSTALL_OR_STORE, ToolRisk.PERMISSION_OR_CREDENTIAL)
    }

    private fun SafetyDecision.needsConfirmationPrompt(requireRiskConfirmation: Boolean): Boolean =
        requiresConfirmation && (requireRiskConfirmation || mandatoryConfirmation)

    private fun debugEvent(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        executionDiagnostics.debugEvent(sessionId, event, fields)
    }

    private fun recordSafetyDecision(sessionId: String?, source: String, safety: SafetyDecision, requireRiskConfirmation: Boolean) {
        executionDiagnostics.recordSafetyDecision(sessionId, source, safety, requireRiskConfirmation)
    }
}
