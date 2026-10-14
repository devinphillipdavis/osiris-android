package org.osiris.android.keyboard.devdeck

import kotlinx.serialization.Serializable
import org.osiris.android.keyboard.privacy.EditorFieldMetadata
import org.osiris.android.keyboard.privacy.PrivacyInputFilter
import org.osiris.android.sdk.ContractSerializer
import org.osiris.android.sdk.ExecutionAdapterType
import org.osiris.android.sdk.OsirisTaskIntent

@Serializable
enum class KeyboardFoldLayoutMode {
    COMPACT_SINGLE_HAND,
    SPLIT_ERGONOMIC_UNFOLDED
}

@Serializable
data class IntentPreviewCardState(
    val previewIntent: OsirisTaskIntent,
    val awaitingExplicitConfirmTap: Boolean,
    val dispatchedToRuntime: Boolean
)

/**
 * Developer Deck & OSIRIS Intent Preview Gate (AT-IME-05, AT-IME-06, AT-IME-07).
 * Provides programming symbols, split-thumb unfolded layout for Pixel Fold,
 * and an explicit two-step preview-before-confirm gate for OSIRIS intents.
 */
class DeveloperDeck {

    val developerSymbols: List<String> = listOf(
        "{", "}", "[", "]", "(", ")", "|", "/", "-", "_", ":", ";", "$", "\"", "'", "=", "<", ">"
    )

    var currentLayoutMode: KeyboardFoldLayoutMode = KeyboardFoldLayoutMode.COMPACT_SINGLE_HAND
        private set

    private var activeIntentPreview: IntentPreviewCardState? = null

    fun updatePosture(isInnerDisplayUnfolded: Boolean, screenWidthDp: Int): KeyboardFoldLayoutMode {
        currentLayoutMode = if (isInnerDisplayUnfolded || screenWidthDp >= 600) {
            KeyboardFoldLayoutMode.SPLIT_ERGONOMIC_UNFOLDED
        } else {
            KeyboardFoldLayoutMode.COMPACT_SINGLE_HAND
        }
        return currentLayoutMode
    }

    /**
     * Step 1 of AT-IME-06: User explicitly taps "Convert to OSIRIS Intent".
     * Builds a validated OsirisTaskIntent and opens the non-executing Preview Card.
     * Nothing is dispatched until confirmDispatchedIntent() is explicitly called.
     */
    fun openIntentPreview(
        draftText: String,
        fieldMetadata: EditorFieldMetadata,
        explicitUserTap: Boolean,
        taskId: String = "task-ime-${System.currentTimeMillis()}",
        adapterTarget: ExecutionAdapterType = ExecutionAdapterType.LOCAL_TERMUX
    ): IntentPreviewCardState {
        PrivacyInputFilter.assertIntentConversionPermitted(fieldMetadata, explicitUserTap)
        val intent = OsirisTaskIntent(
            schemaVersion = "1.0",
            taskId = taskId,
            createdAtEpochMs = System.currentTimeMillis(),
            title = draftText.lineSequence().firstOrNull()?.take(60)?.ifBlank { "IME Task" } ?: "IME Task",
            promptBody = draftText,
            adapterTarget = adapterTarget,
            requestedCapabilities = listOf("exec:python3")
        )
        ContractSerializer.validateTaskIntent(intent)
        val preview = IntentPreviewCardState(
            previewIntent = intent,
            awaitingExplicitConfirmTap = true,
            dispatchedToRuntime = false
        )
        activeIntentPreview = preview
        return preview
    }

    /**
     * Step 2 of AT-IME-06: User taps explicit "Confirm & Send to OSIRIS" button on the Preview Card.
     */
    fun confirmIntentPreview(): IntentPreviewCardState {
        val current = activeIntentPreview
            ?: throw IllegalStateException("No active OSIRIS Intent Preview to confirm")
        val confirmed = current.copy(
            awaitingExplicitConfirmTap = false,
            dispatchedToRuntime = true
        )
        activeIntentPreview = confirmed
        return confirmed
    }

    fun getActivePreview(): IntentPreviewCardState? = activeIntentPreview
}
