package ai.droidlm.overlay

import ai.droidlm.execution.PendingConfirmation
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.relay.PlanPreview
import ai.droidlm.relay.PlanPreviewStep

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayStatusFormatterTest {
    @Test fun idlePromptsUserToSpeak() {
        assertEquals("Tap circle to speak", OverlayStatusFormatter.label(false, false, "", "", "Idle", ""))
        assertEquals("●", OverlayStatusFormatter.recordButton(false, "Idle"))
    }

    @Test fun startingShowsMicrophoneStartup() {
        assertEquals("Starting microphone...", OverlayStatusFormatter.label(true, false, "", "", "Idle", ""))
        assertEquals("■", OverlayStatusFormatter.recordButton(true, "Idle"))
    }

    @Test fun listeningHidesPartialTranscript() {
        val label = OverlayStatusFormatter.label(false, true, "open drive", "", "Idle", "")
        assertEquals("Listening...", label)
        assertEquals("■", OverlayStatusFormatter.recordButton(true, "Idle"))
    }

    @Test fun stoppingShowsProcessingSpeech() {
        val label = OverlayStatusFormatter.label(
            isStarting = false,
            isListening = false,
            partialTranscript = "",
            finalTranscript = "",
            executionStatus = "Idle",
            lastResult = "",
            isStopping = true
        )
        assertEquals("Processing speech...", label)
        assertEquals("■", OverlayStatusFormatter.recordButton(true, "Idle", isStopping = true))
    }

    @Test fun executingShowsCancelButton() {
        assertEquals("×", OverlayStatusFormatter.recordButton(false, "Executing OPEN_APP"))
    }

    @Test fun accessibilitySetupGivesActionableSteps() {
        assertEquals(
            "Turn on Accessibility to control apps",
            OverlayStatusFormatter.accessibilitySetupLabel()
        )
    }

    @Test fun accessibilitySetupAfterOpeningSettingsKeepsMessageShort() {
        assertEquals(
            "Turn on Accessibility to control apps",
            OverlayStatusFormatter.accessibilitySetupLabel(settingsOpened = true)
        )
    }

    @Test fun microphonePermissionMessageIsShort() {
        assertEquals(
            "Enable microphone permission to record",
            OverlayStatusFormatter.microphonePermissionLabel()
        )
    }

    @Test fun microphoneReadyMessageIsShort() {
        assertEquals(
            "Mic enabled. Tap record to speak",
            OverlayStatusFormatter.microphoneReadyLabel()
        )
    }


    @Test fun errorResultWinsOverFinalTranscript() {
        val label = OverlayStatusFormatter.label(
            isStarting = false,
            isListening = false,
            partialTranscript = "",
            finalTranscript = "open drive",
            executionStatus = "Error",
            lastResult = "OpenAI API key is required for GPT planning"
        )
        assertEquals("Planning key needed", label)
    }

    @Test fun compactPlanUsesSingleLineActionChain() {
        val plan = PlanPreview(
            model = "gpt-5.4-nano",
            summary = "Open Sheets and type a value",
            riskLevel = "LOW",
            requiresConfirmation = false,
            steps = listOf(
                PlanPreviewStep(1, DroidLmAction.OpenApp("Google Sheets", "com.google.android.apps.docs.editors.sheets", "open app"), "Open Google Sheets", "open app", false),
                PlanPreviewStep(2, DroidLmAction.Tap(10, 20, "tap plus"), "Tap plus", "tap plus", false),
                PlanPreviewStep(3, DroidLmAction.TypeText("hello", false, "type text"), "Type hello", "type text", false)
            )
        )

        assertEquals("Plan: Open Sheets > Tap plus > Type", OverlayStatusFormatter.compactPlan(plan))
    }

    @Test fun compactPlanShowsRiskAndRemainingSteps() {
        val plan = PlanPreview(
            model = "gpt-5.4-nano",
            summary = "Risky task",
            riskLevel = "HIGH",
            requiresConfirmation = true,
            steps = (1..6).map {
                PlanPreviewStep(it, DroidLmAction.NoOp("step"), "Step $it", "reason", it == 6)
            }
        )

        assertEquals("High risk: Step 1 > Step 2 > Step 3 +3", OverlayStatusFormatter.compactPlan(plan))
    }

    @Test fun compactPlanUsesStructuredActionOverRawActionCode() {
        val plan = PlanPreview(
            model = "gpt-5.4-nano",
            summary = "Open Drive",
            riskLevel = "LOW",
            requiresConfirmation = false,
            steps = listOf(
                PlanPreviewStep(1, DroidLmAction.OpenApp("Google Drive", "com.google.android.apps.docs", "open app"), "OPEN_APP", "open app", false)
            )
        )

        assertEquals("Plan: Open Drive", OverlayStatusFormatter.compactPlan(plan))
    }

    @Test fun fullPlanIncludesSummaryRiskAndStepsWithoutReasons() {
        val plan = PlanPreview(
            model = "gpt-5.4-nano",
            summary = "Open Sheets and type a value",
            riskLevel = "MEDIUM",
            requiresConfirmation = true,
            steps = listOf(
                PlanPreviewStep(1, DroidLmAction.OpenApp("Google Sheets", "com.google.android.apps.docs.editors.sheets", "open app"), "Open Google Sheets", "open app", false),
                PlanPreviewStep(2, DroidLmAction.TypeText("hello", false, "type in selected cell"), "Type hello", "type in selected cell", false)
            )
        )

        assertEquals(
            "Open Sheets and type a value\nSafety: MEDIUM; confirmation required\n1. Open Google Sheets\n2. Type text",
            OverlayStatusFormatter.fullPlan(plan)
        )
    }

    @Test fun confirmationDetailsOmitsBlankPrompt() {
        val pending = PendingConfirmation(
            id = "pending-1",
            transcript = "open google docs",
            actionLabel = "Open Google Docs in Play Store",
            reason = "The app is not installed.",
            prompt = ""
        )

        assertEquals(
            "You said: open google docs\nDroidLM wants to: Open Google Docs in Play Store\nWhy: The app is not installed.",
            OverlayStatusFormatter.confirmationDetails(pending)
        )
    }

    @Test fun confirmationDetailsIncludesCustomPromptWhenPresent() {
        val pending = PendingConfirmation(
            id = "pending-1",
            transcript = "open google docs",
            actionLabel = "Open Google Docs in Play Store",
            reason = "The app is not installed.",
            prompt = "Open Play Store for Google Docs?"
        )

        assertEquals(
            "You said: open google docs\nDroidLM wants to: Open Google Docs in Play Store\nWhy: The app is not installed.\nOpen Play Store for Google Docs?",
            OverlayStatusFormatter.confirmationDetails(pending)
        )
    }

    @Test fun overlayYStaysAboveBottomGestureArea() {
        val safeY = FloatingOverlayBounds.safeY(
            requestedY = 920,
            displayHeight = 1000,
            viewHeight = 72,
            bottomInset = 96,
            density = 1f
        )
        assertEquals(824, safeY)
    }

    @Test fun overlayYUsesMinimumGestureGuardWhenInsetIsMissing() {
        val safeY = FloatingOverlayBounds.safeY(
            requestedY = 920,
            displayHeight = 1000,
            viewHeight = 72,
            bottomInset = 0,
            density = 1f
        )
        assertEquals(872, safeY)
    }
}
