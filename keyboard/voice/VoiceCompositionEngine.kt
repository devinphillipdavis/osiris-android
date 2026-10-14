package org.osiris.android.keyboard.voice

import kotlinx.serialization.Serializable

@Serializable
data class VoiceTranscriptionResult(
    val transcriptText: String,
    val editableInBuffer: Boolean,
    val autoSubmittedOrExecuted: Boolean,
    val explicitActivationConfirmed: Boolean
)

/**
 * Explicit Voice Composition Engine (AT-IME-04).
 * Voice dictation requires an explicit user tap on the microphone control,
 * inserts editable text into the active composition buffer, and NEVER triggers
 * automatic command submission or execution.
 */
class VoiceCompositionEngine {
    var isMicrophoneActive: Boolean = false
        private set

    fun startExplicitVoiceSession() {
        isMicrophoneActive = true
    }

    fun stopVoiceSession() {
        isMicrophoneActive = false
    }

    fun processDictationSegment(
        rawUtterance: String,
        currentBuffer: StringBuilder
    ): VoiceTranscriptionResult {
        if (!isMicrophoneActive) {
            return VoiceTranscriptionResult(
                transcriptText = "",
                editableInBuffer = true,
                autoSubmittedOrExecuted = false,
                explicitActivationConfirmed = false
            )
        }
        val normalized = rawUtterance.trim()
        if (currentBuffer.isNotEmpty() && !currentBuffer.endsWith(" ")) {
            currentBuffer.append(" ")
        }
        currentBuffer.append(normalized)
        return VoiceTranscriptionResult(
            transcriptText = normalized,
            editableInBuffer = true,
            autoSubmittedOrExecuted = false,
            explicitActivationConfirmed = true
        )
    }
}
