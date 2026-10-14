package org.osiris.android.keyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import org.osiris.android.keyboard.devdeck.DeveloperDeck
import org.osiris.android.keyboard.privacy.EditorFieldMetadata
import org.osiris.android.keyboard.privacy.PrivacyAssessment
import org.osiris.android.keyboard.privacy.PrivacyInputFilter
import org.osiris.android.keyboard.voice.VoiceCompositionEngine

/**
 * Pure Kotlin testable IME Composition Controller backing OsirisSmartKeyboardService (AT-IME-01..07, AT-PERF-03).
 */
class ImeCompositionSession(
    val developerDeck: DeveloperDeck = DeveloperDeck(),
    val voiceEngine: VoiceCompositionEngine = VoiceCompositionEngine()
) {
    private val textBuffer = StringBuilder()
    private var composingStart: Int = -1
    private var composingEnd: Int = -1
    var cursorPosition: Int = 0
        private set

    var activeFieldMetadata: EditorFieldMetadata = EditorFieldMetadata(
        hostPackageName = "org.osiris.android",
        inputTypeFlags = PrivacyInputFilter.TYPE_CLASS_TEXT,
        isPasswordOrPinVariation = false
    )
        private set

    var currentPrivacyAssessment: PrivacyAssessment = PrivacyInputFilter.evaluateField(activeFieldMetadata)
        private set

    val telemetryEvents = mutableListOf<String>()

    fun onStartInputField(metadata: EditorFieldMetadata): PrivacyAssessment {
        activeFieldMetadata = metadata
        currentPrivacyAssessment = PrivacyInputFilter.evaluateField(metadata)
        return currentPrivacyAssessment
    }

    /**
     * Commits text into the active input connection buffer and returns key processing latency in nanoseconds.
     */
    fun commitText(text: String, newCursorPosition: Int = 1): Long {
        val startNs = System.nanoTime()
        if (composingStart >= 0 && composingEnd >= composingStart) {
            textBuffer.replace(composingStart, composingEnd, text)
            cursorPosition = composingStart + text.length
            composingStart = -1
            composingEnd = -1
        } else {
            val safePos = cursorPosition.coerceIn(0, textBuffer.length)
            textBuffer.insert(safePos, text)
            cursorPosition = (safePos + text.length).coerceIn(0, textBuffer.length)
        }
        if (newCursorPosition <= 0) {
            cursorPosition = (cursorPosition - text.length).coerceAtLeast(0)
        }
        return System.nanoTime() - startNs
    }

    fun setComposingText(text: String): Long {
        val startNs = System.nanoTime()
        if (currentPrivacyAssessment.isRedactedSensitiveField) {
            // Do not buffer composing predictions in password fields (AT-IME-02)
            return commitText(text, 1)
        }
        if (composingStart < 0) {
            composingStart = cursorPosition.coerceIn(0, textBuffer.length)
            composingEnd = composingStart
        }
        textBuffer.replace(composingStart, composingEnd, text)
        composingEnd = composingStart + text.length
        cursorPosition = composingEnd
        return System.nanoTime() - startNs
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        val startDelete = (cursorPosition - beforeLength).coerceAtLeast(0)
        val endDelete = (cursorPosition + afterLength).coerceAtMost(textBuffer.length)
        if (endDelete > startDelete) {
            textBuffer.delete(startDelete, endDelete)
            cursorPosition = startDelete
        }
        composingStart = -1
        composingEnd = -1
    }

    fun moveCursor(delta: Int): Int {
        cursorPosition = (cursorPosition + delta).coerceIn(0, textBuffer.length)
        return cursorPosition
    }

    fun getCurrentText(): String = textBuffer.toString()

    fun clearBuffer() {
        textBuffer.clear()
        composingStart = -1
        composingEnd = -1
        cursorPosition = 0
    }
}

/**
 * Android InputMethodService entry point for OSIRIS Smart Keyboard (Section 5.1 #2).
 */
class OsirisSmartKeyboardService : InputMethodService() {

    val session = ImeCompositionSession()

    override fun onCreateInputView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val pkg = attribute?.packageName ?: "unknown"
        val inputType = attribute?.inputType ?: PrivacyInputFilter.TYPE_CLASS_TEXT
        session.onStartInputField(
            EditorFieldMetadata(
                hostPackageName = pkg,
                inputTypeFlags = inputType,
                isPasswordOrPinVariation = PrivacyInputFilter.isSensitiveInputType(inputType)
            )
        )
    }
}
