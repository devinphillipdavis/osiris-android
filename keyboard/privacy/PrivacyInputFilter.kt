package org.osiris.android.keyboard.privacy

import kotlinx.serialization.Serializable
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode

@Serializable
data class EditorFieldMetadata(
    val hostPackageName: String,
    val inputTypeFlags: Int,
    val isPasswordOrPinVariation: Boolean,
    val isNoPersonalizedLearning: Boolean = false
)

@Serializable
data class PrivacyAssessment(
    val isRedactedSensitiveField: Boolean,
    val predictionsEnabled: Boolean,
    val telemetryLoggingEnabled: Boolean,
    val osirisIntentConversionAllowed: Boolean,
    val crossAppExfiltrationBlocked: Boolean
)

/**
 * Privacy Input Filter (AT-IME-02, AT-IME-03, Section 6.3).
 * Enforces zero keystroke logging and zero intent conversion on password/PIN fields,
 * and strictly blocks background exfiltration of non-OSIRIS host application text.
 */
object PrivacyInputFilter {

    // Android InputType password variation bitmasks (matching android.text.InputType)
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_CLASS_NUMBER = 0x00000002
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    private val trustedOsirisPackages = setOf(
        "org.osiris.android",
        "com.example.osirisandroid"
    )

    fun isSensitiveInputType(inputType: Int): Boolean {
        val clazz = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        if (clazz == TYPE_CLASS_TEXT) {
            if (variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return true
            }
        }
        if (clazz == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD) {
            return true
        }
        return false
    }

    fun evaluateField(metadata: EditorFieldMetadata): PrivacyAssessment {
        val sensitive = metadata.isPasswordOrPinVariation || isSensitiveInputType(metadata.inputTypeFlags)
        val isTrustedHost = metadata.hostPackageName in trustedOsirisPackages
        return if (sensitive) {
            PrivacyAssessment(
                isRedactedSensitiveField = true,
                predictionsEnabled = false,
                telemetryLoggingEnabled = false,
                osirisIntentConversionAllowed = false,
                crossAppExfiltrationBlocked = true
            )
        } else {
            PrivacyAssessment(
                isRedactedSensitiveField = false,
                predictionsEnabled = !metadata.isNoPersonalizedLearning,
                telemetryLoggingEnabled = false, // Never log raw keystrokes to disk
                osirisIntentConversionAllowed = true, // Only when user explicitly taps "Send to OSIRIS"
                crossAppExfiltrationBlocked = !isTrustedHost
            )
        }
    }

    fun assertIntentConversionPermitted(
        metadata: EditorFieldMetadata,
        explicitUserActionTriggered: Boolean
    ) {
        val assessment = evaluateField(metadata)
        if (assessment.isRedactedSensitiveField || !assessment.osirisIntentConversionAllowed) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_PRIVACY_REDACTED_FIELD,
                "Cannot convert or log text from password/PIN/sensitive input field"
            )
        }
        if (!explicitUserActionTriggered) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_PRIVACY_REDACTED_FIELD,
                "Cross-app text capture blocked: explicit user tap on 'Convert to OSIRIS Intent' required (AT-IME-03)"
            )
        }
    }
}
