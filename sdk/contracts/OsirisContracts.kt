package org.osiris.android.sdk

import kotlinx.serialization.Serializable

const val CURRENT_SCHEMA_VERSION = "1.0.0"
const val SUPPORTED_MAJOR_VERSION = 1

@Serializable
enum class ImplementationStatus {
    NOT_IMPLEMENTED,
    IN_PROGRESS,
    IMPLEMENTED,
    BLOCKED
}

@Serializable
enum class EvidenceStatus {
    NOT_TESTED,
    TESTED_PASS,
    TESTED_FAIL,
    INCONCLUSIVE
}

@Serializable
enum class DeploymentAuthority {
    NONE,
    REQUIRES_APPROVAL,
    AUTHORIZED,
    REVOKED
}

@Serializable
data class NormativeStatusBlock(
    val implementationStatus: ImplementationStatus = ImplementationStatus.IMPLEMENTED,
    val evidenceStatus: EvidenceStatus = EvidenceStatus.NOT_TESTED,
    val deploymentAuthority: DeploymentAuthority = DeploymentAuthority.REQUIRES_APPROVAL,
    val claimCeiling: String = "Automated verification only; no operational or scientific efficacy claimed without human release authorization."
)

typealias NormativeStatusRecord = NormativeStatusBlock

@Serializable
enum class ExecutionMode {
    LOCAL_FIRST,
    REMOTE_WORKSTATION,
    HYBRID_AUTO
}

@Serializable
enum class ReceiptStatus {
    ACCEPTED,
    DUPLICATE_IDEMPOTENT,
    REJECTED
}

@Serializable
enum class ExecutionState {
    DRAFT,
    VALIDATED,
    REJECTED,
    AWAITING_AUTHORIZATION,
    QUEUED,
    RUNNING,
    AWAITING_RECONNECT,
    EVALUATING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    INCONCLUSIVE
}

@Serializable
enum class ExecutionAdapterType {
    NODE_ALPHA_LOCAL,
    NODE_BETA_REMOTE;

    companion object {
        val LOCAL_TERMUX: ExecutionAdapterType get() = NODE_ALPHA_LOCAL
        val REMOTE_CLOUD_WORKER: ExecutionAdapterType get() = NODE_BETA_REMOTE
    }
}

@Serializable
enum class NetworkAccessPolicy {
    OFFLINE_ONLY,
    AUTHENTICATED_REMOTE_ONLY
}

@Serializable
enum class ActionType {
    READ_WORKSPACE,
    WRITE_WORKSPACE,
    EXECUTE_LOCAL,
    EXECUTE_REMOTE,
    GIT_COMMIT,
    GIT_PUSH,
    NETWORK_DISPATCH,
    SECRET_ACCESS,
    RELEASE_PROMOTE
}

@Serializable
enum class Scope {
    SINGLE_TASK,
    SESSION_BOUNDED,
    REPOSITORY_SCOPED
}

@Serializable
enum class RiskClass {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

@Serializable
enum class DecisionStatus {
    GRANTED,
    DENIED,
    EXPIRED,
    REVOKED,
    REQUIRES_HUMAN_APPROVAL
}

@Serializable
enum class EvaluationOutcome {
    TESTED_PASS,
    TESTED_FAIL,
    INCONCLUSIVE,
    BLOCKED
}

@Serializable
enum class OsirisErrorCode {
    ERR_SCHEMA_MISMATCH,
    ERR_SCHEMA_VALIDATION,
    ERR_VERSION_UNSUPPORTED,
    ERR_MALFORMED_ARGUMENT,
    ERR_CAPABILITY_DENIED,
    ERR_CAPABILITY_MISSING,
    ERR_CAPABILITY_EXPIRED,
    ERR_CAPABILITY_REVOKED,
    ERR_CAPABILITY_SCOPE_VIOLATION,
    ERR_ILLEGAL_STATE_TRANSITION,
    ERR_INVALID_STATE_TRANSITION,
    ERR_AST_VALIDATION_FAILED,
    ERR_UNSAFE_DNA_AST,
    ERR_PRIVACY_REDACTED_FIELD,
    ERR_AUTH_UNAUTHORIZED,
    ERR_REMOTE_AUTH_FAILED,
    ERR_EXECUTION_TIMEOUT,
    ERR_EVIDENCE_TAMPERED,
    ERR_PROMOTION_BLOCKED
}

class OsirisContractException(
    val errorCode: OsirisErrorCode,
    override val message: String
) : IllegalArgumentException("[$errorCode] $message")

@Serializable
data class ContextRef(
    val type: String,
    val pathOrUri: String,
    val sha256Digest: String
)

@Serializable
data class ValidationDiagnostic(
    val code: String,
    val severity: String,
    val message: String,
    val fieldPath: String = ""
)

@Serializable
data class IntentRequest(
    val schemaVersion: String = CURRENT_SCHEMA_VERSION,
    val requestId: String,
    val idempotencyKey: String,
    val naturalLanguageTask: String,
    val contextAttachments: List<ContextRef> = emptyList(),
    val requestedMode: ExecutionMode = ExecutionMode.LOCAL_FIRST,
    val parameters: Map<String, String> = emptyMap(),
    val createdAtEpochMs: Long
)

@Serializable
data class OsirisTaskIntent(
    val schemaVersion: String = "1.0",
    val taskId: String,
    val createdAtEpochMs: Long,
    val title: String,
    val promptBody: String,
    val adapterTarget: ExecutionAdapterType = ExecutionAdapterType.NODE_ALPHA_LOCAL,
    val requestedCapabilities: List<String> = emptyList()
)

@Serializable
data class CapabilityGrant(
    val grantId: String,
    val taskId: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val allowedDirectories: List<String>,
    val allowedCommands: List<String>,
    val networkPolicy: NetworkAccessPolicy = NetworkAccessPolicy.OFFLINE_ONLY,
    val singleUse: Boolean = true,
    val revoked: Boolean = false,
    val signatureHex: String
)

@Serializable
data class ExecutionStateRecord(
    val executionId: String,
    val taskId: String,
    val state: ExecutionState,
    val updatedAtEpochMs: Long,
    val detailMessage: String
)

@Serializable
data class IntentReceipt(
    val schemaVersion: String = CURRENT_SCHEMA_VERSION,
    val executionId: String,
    val status: ReceiptStatus,
    val requestDigestSha256: String,
    val diagnostics: List<ValidationDiagnostic> = emptyList(),
    val timestampEpochMs: Long
)

@Serializable
data class ExecutionSnapshot(
    val executionId: String,
    val state: ExecutionState,
    val adapter: ExecutionAdapterType,
    val currentSequence: Long,
    val exitCode: Int? = null,
    val recentLogLines: List<String> = emptyList(),
    val updatedAtEpochMs: Long
)

@Serializable
data class CapabilityRequest(
    val requestId: String,
    val subject: String,
    val action: ActionType,
    val targetResource: String,
    val scope: Scope,
    val requestedLifetimeMs: Long,
    val riskClass: RiskClass,
    val justification: String
)

@Serializable
data class CapabilityDecision(
    val decisionId: String,
    val status: DecisionStatus,
    val grantId: String? = null,
    val grantedScope: Scope? = null,
    val expiresAtEpochMs: Long? = null,
    val policyVersion: String,
    val reasonCode: String,
    val signatureHex: String = ""
)

@Serializable
data class TestResult(
    val testId: String,
    val title: String,
    val status: EvaluationOutcome,
    val durationMs: Long,
    val message: String = ""
) {
    constructor(
        testId: String,
        suiteName: String,
        status: EvaluationOutcome,
        durationMs: Long,
        summary: String,
        unusedMarker: Boolean = true
    ) : this(testId = testId, title = suiteName, status = status, durationMs = durationMs, message = summary)
}

@Serializable
data class EvaluationReport(
    val schemaVersion: String = "1.0",
    val executionId: String,
    val evaluatorIdentity: String,
    val evaluatorVersion: String,
    val outcome: EvaluationOutcome,
    val testResults: List<TestResult>,
    val measuredMetrics: Map<String, Double>,
    val limitations: List<String>
)

@Serializable
data class EvidenceManifest(
    val schemaVersion: String = CURRENT_SCHEMA_VERSION,
    val executionId: String,
    val repositoryCommit: String,
    val genomeRevision: String,
    val policyRevision: String,
    val mode: ExecutionMode = ExecutionMode.LOCAL_FIRST,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val artifactDigests: Map<String, String>,
    val approvalReceiptId: String,
    val claimCeiling: String,
    val normativeStatus: NormativeStatusBlock = NormativeStatusBlock()
)
