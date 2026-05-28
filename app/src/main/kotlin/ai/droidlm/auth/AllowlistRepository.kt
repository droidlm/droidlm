package ai.droidlm.auth

import ai.droidlm.BuildConfig
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.relay.AllowlistCheckResponse
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


data class AllowlistState(
    val configured: Boolean = true,
    val checking: Boolean = false,
    val allowed: Boolean = false,
    val email: String? = null,
    val message: String? = null,
    val errorCode: String? = null
) {
    val ready: Boolean
        get() = !checking
}

class AllowlistRepository(
    private val authRepository: AuthRepository,
    private val relayClient: RelayClient,
    private val logs: ActionLogRepository,
    private val endpointProvider: () -> String = { BuildConfig.ALLOWLIST_CHECK_URL.trim() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _accessState = MutableStateFlow(AllowlistState(checking = true, message = "Checking access..."))
    val accessState: StateFlow<AllowlistState> = _accessState.asStateFlow()

    init {
        scope.launch {
            authRepository.authState.collectLatest { state -> evaluate(state, forceRefresh = false) }
        }
    }

    fun refresh() {
        scope.launch {
            authRepository.reloadCurrentUser()
            evaluate(authRepository.authState.value, forceRefresh = true)
        }
    }

    suspend fun ensureAllowed(forceRefresh: Boolean = false): Boolean {
        evaluate(authRepository.authState.value, forceRefresh)
        return _accessState.value.allowed
    }

    private suspend fun evaluate(authState: AuthState, forceRefresh: Boolean) {
        val user = authState.user
        if (!authState.configured) {
            _accessState.value = AllowlistState(configured = false, message = "Firebase Auth is not configured.")
            return
        }
        if (user == null) {
            _accessState.value = AllowlistState(message = "Sign in to verify access.")
            return
        }
        if (!user.emailVerified) {
            _accessState.value = AllowlistState(
                email = user.email,
                message = "Verify your email address before using DroidLM.",
                errorCode = "AUTH_EMAIL_UNVERIFIED"
            )
            return
        }
        val endpoint = endpointProvider()
        if (endpoint.isBlank()) {
            _accessState.value = AllowlistState(
                configured = false,
                email = user.email,
                message = "DroidLM access verification is not configured.",
                errorCode = "ALLOWLIST_URL_MISSING"
            )
            return
        }

        _accessState.value = AllowlistState(checking = true, email = user.email, message = "Checking access...")
        when (val result = relayClient.checkAllowlist(endpoint, forceRefresh)) {
            is RelayCallResult.Success -> applyResult(user.email, result.value)
            is RelayCallResult.Failure -> {
                if (!forceRefresh && result.shouldRetryWithFreshToken()) {
                    logs.log(ActionLogType.ACTION_RESULT, "Refreshing signed-in access after allowlist auth failure", result.errorCode)
                    authRepository.reloadCurrentUser()
                    evaluate(authRepository.authState.value, forceRefresh = true)
                    return
                }
                _accessState.value = AllowlistState(
                    email = user.email,
                    message = result.message.ifBlank { "Could not verify allowlist access." },
                    errorCode = result.errorCode
                )
                logs.log(ActionLogType.ERROR, "Allowlist check failed: ${result.message}", result.errorCode)
            }
        }
    }

    private fun RelayCallResult.Failure.shouldRetryWithFreshToken(): Boolean {
        val code = errorCode.orEmpty()
        return code == "HTTP_401" ||
            code == "HTTP_403" ||
            code.startsWith("AUTH_TOKEN") ||
            code == "AUTH_SESSION_EXPIRED"
    }

    private fun applyResult(userEmail: String?, response: AllowlistCheckResponse) {
        _accessState.value = AllowlistState(
            allowed = response.allowed,
            email = response.email ?: userEmail,
            message = if (response.allowed) "Access approved." else response.message ?: "This account is not on the DroidLM allowlist.",
            errorCode = response.errorCode
        )
        logs.log(
            if (response.allowed) ActionLogType.ACTION_RESULT else ActionLogType.ERROR,
            if (response.allowed) "Allowlist access approved" else "Allowlist access denied",
            response.errorCode
        )
    }
}
