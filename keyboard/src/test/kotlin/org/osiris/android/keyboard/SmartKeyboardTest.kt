package org.osiris.android.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.osiris.android.keyboard.devdeck.KeyboardFoldLayoutMode
import org.osiris.android.keyboard.ime.ImeCompositionSession
import org.osiris.android.keyboard.privacy.EditorFieldMetadata
import org.osiris.android.keyboard.privacy.PrivacyInputFilter
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode

class SmartKeyboardTest {

    @Test
    fun `AT-IME-01 standard text entry commits composing and deletion accurately`() {
        val session = ImeCompositionSession()
        session.setComposingText("osir")
        assertEquals("osir", session.getCurrentText())
        session.commitText("osiris::}{> ")
        assertEquals("osiris::}{> ", session.getCurrentText())
        session.commitText("/igniteX")
        session.deleteSurroundingText(beforeLength = 1, afterLength = 0)
        assertEquals("osiris::}{> /ignite", session.getCurrentText())
    }

    @Test
    fun `AT-IME-02 password and PIN field redaction disables predictions, logging, and intent conversion`() {
        val session = ImeCompositionSession()
        val passwordMeta = EditorFieldMetadata(
            hostPackageName = "com.bank.secure",
            inputTypeFlags = PrivacyInputFilter.TYPE_CLASS_TEXT or PrivacyInputFilter.TYPE_TEXT_VARIATION_PASSWORD,
            isPasswordOrPinVariation = true
        )
        val assessment = session.onStartInputField(passwordMeta)
        assertTrue(assessment.isRedactedSensitiveField)
        assertFalse(assessment.predictionsEnabled)
        assertFalse(assessment.telemetryLoggingEnabled)
        assertFalse(assessment.osirisIntentConversionAllowed)
        assertTrue(session.telemetryEvents.isEmpty())

        try {
            session.developerDeck.openIntentPreview(
                draftText = "SuperSecretPassword123",
                fieldMetadata = passwordMeta,
                explicitUserTap = true
            )
            fail("Expected ERR_PRIVACY_REDACTED_FIELD on password field")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_PRIVACY_REDACTED_FIELD, e.errorCode)
        }
    }

    @Test
    fun `AT-IME-03 cross-app isolation blocks background exfiltration when user has not explicitly tapped convert`() {
        val session = ImeCompositionSession()
        val thirdPartyMeta = EditorFieldMetadata(
            hostPackageName = "com.thirdparty.notes",
            inputTypeFlags = PrivacyInputFilter.TYPE_CLASS_TEXT,
            isPasswordOrPinVariation = false
        )
        val assessment = session.onStartInputField(thirdPartyMeta)
        assertTrue(assessment.crossAppExfiltrationBlocked)

        try {
            session.developerDeck.openIntentPreview(
                draftText = "Private notes in third party app",
                fieldMetadata = thirdPartyMeta,
                explicitUserTap = false
            )
            fail("Expected ERR_PRIVACY_REDACTED_FIELD when explicitUserTap is false")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_PRIVACY_REDACTED_FIELD, e.errorCode)
        }
    }

    @Test
    fun `AT-IME-04 explicit voice composition populates editable buffer and never auto-executes`() {
        val session = ImeCompositionSession()
        val sb = StringBuilder()

        // Without explicit mic tap, nothing is captured
        val blockedResult = session.voiceEngine.processDictationSegment("ignite system", sb)
        assertFalse(blockedResult.explicitActivationConfirmed)
        assertEquals("", sb.toString())

        // With explicit mic activation, text is placed in editable buffer and never auto-submitted
        session.voiceEngine.startExplicitVoiceSession()
        val activeResult = session.voiceEngine.processDictationSegment("/learn 50", sb)
        assertTrue(activeResult.explicitActivationConfirmed)
        assertTrue(activeResult.editableInBuffer)
        assertFalse(activeResult.autoSubmittedOrExecuted)
        assertEquals("/learn 50", sb.toString())
    }

    @Test
    fun `AT-IME-05 developer deck symbols and cursor navigation operate accurately`() {
        val session = ImeCompositionSession()
        val requiredSymbols = listOf(
            "{", "}", "[", "]", "(", ")", "|", "/", "-", "_", ":", ";", "$", "\"", "'", "=", "<", ">"
        )
        assertEquals(requiredSymbols, session.developerDeck.developerSymbols)

        for (sym in requiredSymbols) {
            session.commitText(sym)
        }
        assertEquals(requiredSymbols.joinToString(""), session.getCurrentText())
        val movedPos = session.moveCursor(-4)
        assertEquals(requiredSymbols.size - 4, movedPos)
    }

    @Test
    fun `AT-IME-06 intent preview gate requires explicit confirmation before dispatch`() {
        val session = ImeCompositionSession()
        val meta = session.activeFieldMetadata
        val preview = session.developerDeck.openIntentPreview(
            draftText = "/dna \"Synthesize H8 Lock\"",
            fieldMetadata = meta,
            explicitUserTap = true
        )
        assertTrue(preview.awaitingExplicitConfirmTap)
        assertFalse(preview.dispatchedToRuntime)

        val confirmed = session.developerDeck.confirmIntentPreview()
        assertFalse(confirmed.awaitingExplicitConfirmTap)
        assertTrue(confirmed.dispatchedToRuntime)
    }

    @Test
    fun `AT-IME-07 split-thumb layout switches between folded cover screen and unfolded inner display`() {
        val session = ImeCompositionSession()
        val foldedMode = session.developerDeck.updatePosture(isInnerDisplayUnfolded = false, screenWidthDp = 380)
        assertEquals(KeyboardFoldLayoutMode.COMPACT_SINGLE_HAND, foldedMode)

        val unfoldedMode = session.developerDeck.updatePosture(isInnerDisplayUnfolded = true, screenWidthDp = 840)
        assertEquals(KeyboardFoldLayoutMode.SPLIT_ERGONOMIC_UNFOLDED, unfoldedMode)
    }

    @Test
    fun `AT-PERF-03 keyboard key-press p95 latency is well below 25ms`() {
        val session = ImeCompositionSession()
        val latenciesMs = mutableListOf<Double>()
        repeat(200) { idx ->
            val ns = session.commitText((idx % 10).toString())
            latenciesMs.add(ns / 1_000_000.0)
        }
        val sorted = latenciesMs.sorted()
        val p95Ms = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
        assertTrue("Expected p95 key latency <= 25.0ms, got ${p95Ms}ms", p95Ms <= 25.0)
    }
}
