package ai.droidlm.ondevice

import ai.droidlm.agent.AgentDecision
import ai.droidlm.agent.AgentJsonParser
import ai.droidlm.agent.AgentToolRegistry
import ai.droidlm.agent.AgentTurnRequest
import ai.droidlm.download.copyStreamWithProgress
import ai.droidlm.download.formatDownloadProgress
import ai.droidlm.intent.DroidLmActionContract
import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.openai.PromptContextBudgeter
import ai.droidlm.relay.PlanPreview
import ai.droidlm.relay.PlannedAction
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayClient
import ai.droidlm.relay.RelayPlanRequest
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class OnDevicePlanner(
    private val context: Context,
    private val logs: ActionLogRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()
) {
    data class Status(
        val phase: Phase,
        val message: String,
        val downloadedBytes: Long? = null,
        val totalBytes: Long? = null,
        val progressFraction: Float? = null
    ) {
        val progressLabel: String?
            get() = if (downloadedBytes != null || totalBytes != null) {
                formatDownloadProgress(downloadedBytes = downloadedBytes ?: 0L, totalBytes = totalBytes)
            } else {
                null
            }

        enum class Phase {
            UNSUPPORTED,
            NOT_DOWNLOADED,
            DOWNLOADING,
            DOWNLOADED,
            LOADING,
            READY,
            ERROR
        }

        val ready: Boolean get() = phase == Phase.READY
    }

    private val engine by lazy { LlamaOnDeviceEngine(context) }
    private val relayParser = RelayClient()
    private val agentJsonParser = AgentJsonParser()
    private val plannerMutex = Mutex()
    private val plannerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val plannerRoot = File(context.filesDir, "ondevice-planner/qwen3-1.7b")
    private val modelFile = File(plannerRoot, MODEL_FILE_NAME)
    private val markerFile = File(plannerRoot, READY_MARKER_NAME)
    private var modelLoaded = false

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun refresh() {
        _status.value = initialStatus()
    }

    fun prepareIfPossible() {
        plannerScope.launch {
            runCatching { ensureReady() }
                .onFailure { error ->
                    val current = status.value
                    if (current.phase != Status.Phase.NOT_DOWNLOADED && current.phase != Status.Phase.UNSUPPORTED) {
                        _status.value = Status(Status.Phase.ERROR, error.message ?: "Could not prepare the local planner")
                    }
                }
        }
    }

    fun releaseModel() {
        plannerScope.launch {
            plannerMutex.withLock {
                if (modelLoaded) {
                    engine.unloadModel()
                    modelLoaded = false
                }
                _status.value = initialStatus()
            }
        }
    }

    suspend fun downloadModel() {
        plannerMutex.withLock {
            val unsupported = unsupportedStatus()
            if (unsupported != null) {
                _status.value = unsupported
                throw IOException(unsupported.message)
            }
            requireDownloadStorage()
            plannerRoot.mkdirs()
            val tempFile = File(plannerRoot, "$MODEL_FILE_NAME.download")
            tempFile.delete()
            val request = Request.Builder().url(MODEL_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Could not download the local planning model: HTTP ${response.code}")
                val body = response.body ?: throw IOException("Could not download the local planning model: empty response body")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: MODEL_BYTES
                _status.value = Status(Status.Phase.DOWNLOADING, "Downloading the local planning model...", 0L, totalBytes, 0f)
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        copyStreamWithProgress(
                            input = input,
                            output = output,
                            totalBytes = totalBytes,
                            onChunk = { buffer, bytesRead -> digest.update(buffer, 0, bytesRead) },
                            onProgress = { progress ->
                                _status.value = Status(
                                    phase = Status.Phase.DOWNLOADING,
                                    message = "Downloading the local planning model...",
                                    downloadedBytes = progress.downloadedBytes,
                                    totalBytes = progress.totalBytes,
                                    progressFraction = progress.progressFraction
                                )
                            }
                        )
                    }
                }
                val actualSha = digest.digest().joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }
                if (!actualSha.equals(MODEL_SHA256, ignoreCase = true)) {
                    tempFile.delete()
                    _status.value = Status(Status.Phase.ERROR, "Downloaded local planning model checksum mismatch")
                    throw IOException("Downloaded local planning model checksum mismatch")
                }
            }
            if (modelFile.exists()) modelFile.delete()
            require(tempFile.renameTo(modelFile)) { "Could not move the downloaded local planning model into place" }
            markerFile.writeText(MODEL_SHA256)
            modelLoaded = false
            _status.value = Status(Status.Phase.DOWNLOADED, "Local planning model downloaded. Preparing...")
        }
        ensureReady()
    }

    suspend fun planPreview(request: RelayPlanRequest): RelayCallResult<PlanPreview> {
        return runCatching {
            ensureReady()
            val promptContext = buildPromptContext(request)
            val rawResponse = engine.generateJson(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildPlanPreviewPrompt(request, promptContext),
                jsonSchema = PLAN_PREVIEW_JSON_SCHEMA.toString(),
                maxTokens = MAX_COMPLETION_TOKENS,
                temperature = TEMPERATURE,
                topK = TOP_K,
                topP = TOP_P,
                minP = MIN_P,
                presencePenalty = PRESENCE_PENALTY
            )
            relayParser.parsePlanPreviewJson(rawResponse).also { plan ->
                require(plan.steps.isNotEmpty()) { "On-device planner returned no steps" }
            }
        }.fold(
            onSuccess = { plan ->
                logs.log(ActionLogType.PLANNER_RESULT, "On-device plan ready: ${plan.summary}", "model=${plan.model}; risk=${plan.riskLevel}; steps=${plan.steps.size}")
                RelayCallResult.Success(plan)
            },
            onFailure = { error ->
                updateFailureStatus(error, "On-device planning failed")
                RelayCallResult.Failure(error.message ?: "On-device planning failed", errorCodeFor(error), error)
            }
        )
    }

    suspend fun planActionWithMetadata(
        request: RelayPlanRequest,
        step: Int,
        maxSteps: Int
    ): RelayCallResult<PlannedAction> {
        return runCatching {
            ensureReady()
            val promptContext = buildPromptContext(request)
            val rawResponse = engine.generateJson(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildActionPrompt(request, promptContext, step, maxSteps),
                jsonSchema = ACTION_JSON_SCHEMA.toString(),
                maxTokens = MAX_COMPLETION_TOKENS,
                temperature = TEMPERATURE,
                topK = TOP_K,
                topP = TOP_P,
                minP = MIN_P,
                presencePenalty = PRESENCE_PENALTY
            )
            relayParser.parsePlannedActionJson(rawResponse)
        }.fold(
            onSuccess = { plannedAction ->
                logs.log(
                    ActionLogType.PARSED_ACTION,
                    "On-device next action: ${plannedAction.action.displayName()}",
                    "confidence=${plannedAction.confidence.name}; expectedResult=${plannedAction.expectedResult.orEmpty().take(120)}"
                )
                RelayCallResult.Success(plannedAction)
            },
            onFailure = { error ->
                updateFailureStatus(error, "On-device action planning failed")
                RelayCallResult.Failure(error.message ?: "On-device action planning failed", errorCodeFor(error), error)
            }
        )
    }

    suspend fun nextAgentTurn(request: AgentTurnRequest): RelayCallResult<AgentDecision> {
        return runCatching {
            ensureReady()
            val promptContext = buildAgentPromptContext(request)
            val rawResponse = engine.generateJson(
                systemPrompt = AGENT_SYSTEM_PROMPT,
                userPrompt = buildAgentTurnPrompt(request, promptContext),
                jsonSchema = AGENT_DECISION_JSON_SCHEMA.toString(),
                maxTokens = MAX_COMPLETION_TOKENS,
                temperature = TEMPERATURE,
                topK = TOP_K,
                topP = TOP_P,
                minP = MIN_P,
                presencePenalty = PRESENCE_PENALTY
            )
            agentJsonParser.parseDecision(rawResponse).also { decision ->
                if (decision.status.name == "CALL_TOOLS") {
                    require(decision.toolCalls.isNotEmpty()) { "On-device agent requested no tools" }
                }
            }
        }.fold(
            onSuccess = { decision ->
                logs.log(
                    ActionLogType.PLANNER_RESULT,
                    "On-device agent turn: ${decision.status.name}",
                    "toolCalls=${decision.toolCalls.size}; message=${decision.message.take(120)}"
                )
                RelayCallResult.Success(decision)
            },
            onFailure = { error ->
                updateFailureStatus(error, "On-device agent planning failed")
                RelayCallResult.Failure(error.message ?: "On-device agent planning failed", errorCodeFor(error), error)
            }
        )
    }

    fun setupMessage(): String {
        val current = status.value
        return when (current.phase) {
            Status.Phase.UNSUPPORTED -> current.message
            Status.Phase.NOT_DOWNLOADED -> "Privacy mode needs the local planning model downloaded first."
            Status.Phase.DOWNLOADING -> "Privacy mode is downloading the local planning model. Keep DroidLM open until it finishes."
            Status.Phase.DOWNLOADED,
            Status.Phase.LOADING -> "Privacy mode is preparing local planning. Try again in a moment."
            Status.Phase.READY -> "Local planning is ready."
            Status.Phase.ERROR -> current.message
        }
    }

    private fun updateFailureStatus(error: Throwable, fallbackMessage: String) {
        _status.value = when (status.value.phase) {
            Status.Phase.NOT_DOWNLOADED,
            Status.Phase.UNSUPPORTED,
            Status.Phase.DOWNLOADING,
            Status.Phase.LOADING -> status.value
            else -> Status(Status.Phase.ERROR, error.message ?: fallbackMessage)
        }
    }

    private suspend fun ensureReady() {
        plannerMutex.withLock {
            val unsupported = unsupportedStatus()
            if (unsupported != null) {
                _status.value = unsupported
                throw IOException(unsupported.message)
            }
            if (!modelFile.isFile || !markerFile.isFile || markerFile.readText().trim() != MODEL_SHA256) {
                modelLoaded = false
                _status.value = Status(Status.Phase.NOT_DOWNLOADED, "Download the local planning model to use privacy mode")
                throw IOException("The local planning model is not downloaded yet")
            }
            if (modelLoaded) {
                _status.value = Status(Status.Phase.READY, "Local planning ready")
                return
            }
            _status.value = Status(Status.Phase.LOADING, "Preparing local planning")
            engine.ensureModelLoaded(modelFile.absolutePath, LOCAL_CONTEXT_SIZE)
            modelLoaded = true
            _status.value = Status(Status.Phase.READY, "Local planning ready")
            logs.log(ActionLogType.ACTION_RESULT, "Local planning model loaded", "contextSize=$LOCAL_CONTEXT_SIZE")
        }
    }

    private fun buildPromptContext(request: RelayPlanRequest): PromptContextBudgeter.BudgetedPromptContext =
        PromptContextBudgeter.build(
            goal = request.goal,
            activeApp = request.activeApp,
            deviceContext = request.deviceContext,
            uiState = request.uiState,
            packages = request.packages,
            history = request.history,
            targetContextTokens = LOCAL_PROMPT_CONTEXT_TOKENS
        )

    private fun buildAgentPromptContext(request: AgentTurnRequest): PromptContextBudgeter.BudgetedPromptContext =
        PromptContextBudgeter.build(
            goal = request.goal,
            activeApp = request.activeApp,
            deviceContext = request.deviceContext,
            uiState = request.uiState,
            packages = request.packages,
            history = request.history,
            lastResults = JSONArray(request.lastResults.map { it.toJson() }),
            doNotRepeat = JSONArray(request.doNotRepeat.map { it.toJson() }),
            targetContextTokens = LOCAL_PROMPT_CONTEXT_TOKENS
        )

    private fun buildPlanPreviewPrompt(
        request: RelayPlanRequest,
        promptContext: PromptContextBudgeter.BudgetedPromptContext
    ): String {
        return buildString {
            appendLine("Goal:")
            appendLine(request.goal)
            appendLine()
            appendLine("Return only JSON that matches the schema exactly.")
            appendLine("Keep the plan short, concrete, and safe.")
            appendLine("Prefer direct visible or node-backed actions over guessed coordinates.")
            appendLine("Use installed package names only when opening or switching apps.")
            appendLine("If the task appears risky, destructive, or privacy-sensitive, set requiresConfirmation to true and use a HIGH risk level.")
            appendLine("Supported actions: ${DroidLmActionContract.supportedActionsPrompt}")
            appendLine()
            appendLine("Prompt context JSON:")
            append(promptContext.json.toString())
        }
    }

    private fun buildActionPrompt(
        request: RelayPlanRequest,
        promptContext: PromptContextBudgeter.BudgetedPromptContext,
        step: Int,
        maxSteps: Int
    ): String {
        val recentHistory = JSONArray(request.history.takeLast(8))
        return buildString {
            appendLine("Goal:")
            appendLine(request.goal)
            appendLine()
            appendLine("Action loop step: $step of $maxSteps")
            appendLine("Return only JSON that matches the schema exactly.")
            appendLine("Choose the single best next Android action from the current visible state.")
            appendLine("If the goal is already complete, return action DONE.")
            appendLine("If there is no safe justified action from the visible state, return action NO_OP with a short reason.")
            appendLine("Do not repeat a failing action unless the history shows new evidence or the screen changed.")
            appendLine("Prefer visible text, node-backed actions, waiting, or scrolling over guessed coordinates.")
            appendLine("Use OPEN_APP or SWITCH_APP only with package names present in the prompt context.")
            appendLine("Supported actions: ${DroidLmActionContract.supportedActionsPrompt}")
            appendLine()
            appendLine("Recent loop history JSON:")
            appendLine(recentHistory.toString())
            appendLine()
            appendLine("Prompt context JSON:")
            append(promptContext.json.toString())
        }
    }

    private fun buildAgentTurnPrompt(
        request: AgentTurnRequest,
        promptContext: PromptContextBudgeter.BudgetedPromptContext
    ): String {
        return buildString {
            appendLine("Goal:")
            appendLine(request.goal)
            appendLine()
            appendLine("Agent turn: ${request.turnIndex} of ${request.budgets.maxTurns}")
            appendLine("Remaining tool calls: ${request.remainingToolCalls}")
            appendLine("Return only JSON that matches the schema exactly.")
            appendLine("Choose the single best next Android tool/action from the current visible state.")
            appendLine("Use DONE only when the observed UI or recent tool results show the goal is complete.")
            appendLine("Use NO_OP when no useful safe action is possible from the current state.")
            appendLine("Use ASK_CONFIRMATION when the user must confirm or clarify before a risky next step.")
            appendLine("Do not repeat a failed or no-delta action unless the observation changed or the strategy materially changed.")
            appendLine("Prefer node-backed tools and visible labels over guessed coordinates.")
            appendLine("Prefer one action per turn so DroidLM can observe fresh UI after execution.")
            appendLine("Supported actions: ${DroidLmActionContract.supportedActionsPrompt}")
            appendLine("Available tool specs JSON: ${toolSpecsJson()}")
            appendLine()
            appendLine("Prompt context JSON:")
            append(promptContext.json.toString())
        }
    }

    private fun initialStatus(): Status {
        val unsupported = unsupportedStatus()
        if (unsupported != null) return unsupported
        if (modelFile.isFile && markerFile.isFile && markerFile.readText().trim() == MODEL_SHA256) {
            return Status(Status.Phase.DOWNLOADED, "Local planning model downloaded. Enable privacy mode to prepare it.")
        }
        return Status(Status.Phase.NOT_DOWNLOADED, "Download the local planning model to use privacy mode")
    }

    private fun unsupportedStatus(): Status? {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            return Status(Status.Phase.UNSUPPORTED, "Privacy mode currently supports flagship arm64 Android phones only.")
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalMem = ActivityManager.MemoryInfo().also { info -> activityManager?.getMemoryInfo(info) }.totalMem
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Status(Status.Phase.UNSUPPORTED, "Privacy mode currently requires Android 13 or newer on flagship phones.")
        }
        if (totalMem < MIN_TOTAL_RAM_BYTES) {
            return Status(Status.Phase.UNSUPPORTED, "Privacy mode currently requires about 10 GB of RAM or more.")
        }
        return null
    }

    private fun requireDownloadStorage() {
        val availableBytes = StatFs(context.filesDir.absolutePath).availableBytes
        if (availableBytes < MIN_FREE_STORAGE_BYTES) {
            throw IOException("Privacy mode needs about 4.5 GB of free storage to download and prepare the local planning model.")
        }
    }

    private fun errorCodeFor(error: Throwable): String = when (error.message.orEmpty()) {
        "The local planning model is not downloaded yet" -> ERROR_MODEL_MISSING
        else -> when (status.value.phase) {
            Status.Phase.UNSUPPORTED -> ERROR_MODEL_UNSUPPORTED
            Status.Phase.NOT_DOWNLOADED, Status.Phase.DOWNLOADING -> ERROR_MODEL_MISSING
            else -> ERROR_MODEL_FAILURE
        }
    }

    companion object {
        const val ERROR_MODEL_MISSING = "ON_DEVICE_MODEL_MISSING"
        const val ERROR_MODEL_UNSUPPORTED = "ON_DEVICE_MODEL_UNSUPPORTED"
        const val ERROR_MODEL_FAILURE = "ON_DEVICE_MODEL_FAILURE"

        private const val MODEL_FILE_NAME = "Qwen3-1.7B-Q8_0.gguf"
        private const val READY_MARKER_NAME = ".qwen3-ready"
        private const val MODEL_URL = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf?download=1"
        private const val MODEL_SHA256 = "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a"
        private const val MODEL_BYTES = 1_834_426_016L
        private const val MIN_TOTAL_RAM_BYTES = 10L * 1024L * 1024L * 1024L
        private const val MIN_FREE_STORAGE_BYTES = 4_500_000_000L
        private const val LOCAL_CONTEXT_SIZE = 4_096
        private const val LOCAL_PROMPT_CONTEXT_TOKENS = 3_000
        private const val MAX_COMPLETION_TOKENS = 768
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 20
        private const val TOP_P = 0.8f
        private const val MIN_P = 0f
        private const val PRESENCE_PENALTY = 1.5f

        private val PLAN_PREVIEW_JSON_SCHEMA = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("model", boundedString(1, 64))
                .put("summary", boundedString(1, 280))
                .put("riskLevel", enumValues("LOW", "MEDIUM", "HIGH"))
                .put("requiresConfirmation", JSONObject().put("type", "boolean"))
                .put("steps", JSONObject()
                    .put("type", "array")
                    .put("minItems", 1)
                    .put("maxItems", 4)
                    .put("items", JSONObject()
                        .put("type", "object")
                        .put("properties", actionPropertiesSchema().put("index", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 4)))
                        .put("required", JSONArray(listOf("index", "action", "confidence", "reason", "requiresConfirmation", "expectedResult")))
                        .put("additionalProperties", false)
                    )
                )
            )
            .put("required", JSONArray(listOf("model", "summary", "riskLevel", "requiresConfirmation", "steps")))
            .put("additionalProperties", false)

        private val ACTION_JSON_SCHEMA = JSONObject()
            .put("type", "object")
            .put("properties", actionPropertiesSchema())
            .put("required", JSONArray(listOf("action", "confidence", "reason", "expectedResult")))
            .put("additionalProperties", false)

        private val AGENT_DECISION_JSON_SCHEMA = JSONObject()
            .put("type", "object")
            .put("properties", actionPropertiesSchema()
                .put("id", boundedString(1, 80))
                .put("confirmationPrompt", boundedString(1, 280))
            )
            .put("required", JSONArray(listOf("action", "confidence", "reason", "expectedResult")))
            .put("additionalProperties", false)

        private const val SYSTEM_PROMPT = """
You are DroidLM's on-device Android automation planner.
Return only JSON that matches the requested schema exactly.
Never use markdown, code fences, or explanatory prose.
Prefer the smallest safe plan that can succeed from the visible device state.
Never invent package names, node ids, coordinates, or visible labels.
Use OPEN_APP or SWITCH_APP only with installed package names present in the prompt context.
Prefer node-backed or visible text actions over guessed coordinates.
If the task is destructive, privacy-sensitive, payment-related, sharing-related, or otherwise risky, set requiresConfirmation to true and choose HIGH risk.
Keep plans concise and practical for the current screen.
"""

        private const val AGENT_SYSTEM_PROMPT = """
You are DroidLM's on-device Android agent runtime.
Return only JSON that matches the requested schema exactly.
Never use markdown, code fences, or explanatory prose.
Choose a single best next tool/action from the visible Android state.
Never invent package names, node ids, coordinates, visible labels, or success evidence.
Prefer one action per turn so DroidLM can observe fresh UI before another decision.
Use node-backed and visible-text actions before coordinate gestures.
Use OPEN_APP or SWITCH_APP only with installed package names present in the prompt context.
If the goal is already complete, return action DONE.
If there is no useful safe action, return action NO_OP.
If the user must confirm or clarify before a risky step, return action ASK_CONFIRMATION.
If the task is destructive, privacy-sensitive, payment-related, sharing-related, installation-related, or otherwise risky, set requiresConfirmation to true and choose HIGH confidence only when the target is explicit and visible.
Avoid repeating a failed or no-delta action unless the observation changed or the strategy materially changed.
Keep the next step concise, grounded, and practical for the current screen.
"""


        private fun boundedString(minLength: Int, maxLength: Int): JSONObject = JSONObject()
            .put("type", "string")
            .put("minLength", minLength)
            .put("maxLength", maxLength)

        private fun enumValues(vararg values: String): JSONObject = JSONObject()
            .put("type", "string")
            .put("enum", JSONArray(values.toList()))

        private fun integerRange(minimum: Int, maximum: Int): JSONObject = JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)

        private fun toolSpecsJson(): JSONArray = JSONArray(
            AgentToolRegistry.defaultSpecs().map { spec ->
                JSONObject()
                    .put("name", spec.name)
                    .put("risk", spec.risk.name)
                    .put("mutating", spec.mutating)
                    .put("requiresFreshObservationAfter", spec.requiresFreshObservationAfter)
                    .put("maxCallsPerRun", spec.maxCallsPerRun)
            }
        )

        private fun actionPropertiesSchema(): JSONObject = JSONObject()
            .put("action", enumValues(*DroidLmActionContract.supportedActions.toTypedArray()))
            .put("confidence", enumValues("HIGH", "MEDIUM", "LOW"))
            .put("reason", boundedString(1, 280))
            .put("requiresConfirmation", JSONObject().put("type", "boolean"))
            .put("expectedResult", boundedString(1, 280))
            .put("packageName", boundedString(1, 180))
            .put("appName", boundedString(1, 180))
            .put("x", integerRange(0, 10_000))
            .put("y", integerRange(0, 10_000))
            .put("startX", integerRange(0, 10_000))
            .put("startY", integerRange(0, 10_000))
            .put("endX", integerRange(0, 10_000))
            .put("endY", integerRange(0, 10_000))
            .put("durationMs", integerRange(0, 60_000))
            .put("nodeId", boundedString(1, 240))
            .put("direction", boundedString(1, 32))
            .put("amount", boundedString(1, 32))
            .put("untilText", boundedString(1, 240))
            .put("text", boundedString(1, 280))
            .put("label", boundedString(1, 280))
            .put("role", boundedString(1, 80))
            .put("containerNodeId", boundedString(1, 240))
            .put("buttonText", boundedString(1, 160))
            .put("menu", boundedString(1, 80))
            .put("imeAction", boundedString(1, 80))
            .put("kind", boundedString(1, 80))
            .put("value", JSONObject().put("type", "boolean"))
            .put("expanded", JSONObject().put("type", "boolean"))
            .put("url", boundedString(1, 512))
            .put("mimeType", boundedString(1, 120))
            .put("anchorText", boundedString(1, 280))
            .put("targetText", boundedString(1, 280))
            .put("replacementText", boundedString(1, 280))
            .put("insertText", boundedString(1, 280))
            .put("sectionLabel", boundedString(1, 180))
            .put("message", boundedString(1, 280))
    }
}
