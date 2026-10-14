package org.osiris.android.governance

import kotlinx.serialization.Serializable
import org.osiris.android.sdk.ActionType
import org.osiris.android.sdk.CapabilityCryptoSigner
import org.osiris.android.sdk.CapabilityDecision
import org.osiris.android.sdk.CapabilityGrant
import org.osiris.android.sdk.CapabilityRequest
import org.osiris.android.sdk.DecisionStatus
import org.osiris.android.sdk.NetworkAccessPolicy
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode
import org.osiris.android.sdk.RiskClass
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class GovernanceAuditEntry(
    val timestampEpochMs: Long,
    val eventType: String,
    val subject: String,
    val action: ActionType,
    val targetResource: String,
    val decisionStatus: DecisionStatus,
    val reasonCode: String
)

@Serializable
data class ActionTargetContext(
    val taskId: String,
    val targetDirectory: String,
    val commandExecutable: String,
    val requiresNetwork: Boolean = false
)

/**
 * Non-bypassable Capability & Policy Decision Engine (OSIRIS-ANDROID-SPEC-001 Section 5.1 #6 & Suite D).
 * Enforces AT-SEC-01 (unauthorized block), AT-SEC-02 (expiry), AT-SEC-03 (revocation & in-flight kill),
 * and AT-SEC-06 (adversarial model/tool spoofing resistance).
 */
class CapabilityGovernanceEngine(
    val policyVersion: String = "OSIRIS-POL-1.0.0"
) {
    private val authorityKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val activeGrants = ConcurrentHashMap<String, CapabilityDecision>()
    private val grantRequests = ConcurrentHashMap<String, CapabilityRequest>()
    private val revokedGrantIds = ConcurrentHashMap.newKeySet<String>()
    private val consumedSingleUseGrantIds = ConcurrentHashMap.newKeySet<String>()
    private val inFlightKillCallbacks = ConcurrentHashMap<String, MutableList<() -> Unit>>()
    private val auditLog = CopyOnWriteArrayList<GovernanceAuditEntry>()

    companion object {
        private val FORBIDDEN_SYSTEM_PATHS = listOf("/etc", "/root", "/sys", "/proc", "/dev", "/boot")
    }

    private fun signDecisionPayload(
        decisionId: String,
        grantId: String,
        request: CapabilityRequest,
        expiresAtEpochMs: Long
    ): String {
        val canonical = "$decisionId|$grantId|${request.subject}|${request.action}|${request.targetResource}|$expiresAtEpochMs|$policyVersion"
        return CapabilityCryptoSigner.hmacSha256Hex(authorityKeyBytes, canonical)
    }

    fun issueGrant(
        grantId: String,
        taskId: String,
        issuedAtEpochMs: Long,
        ttlMs: Long,
        allowedDirectories: List<String>,
        allowedCommands: List<String>,
        networkPolicy: NetworkAccessPolicy = NetworkAccessPolicy.OFFLINE_ONLY,
        singleUse: Boolean = true
    ): CapabilityGrant {
        val expiresAt = issuedAtEpochMs + ttlMs.coerceAtLeast(1L)
        val unsigned = CapabilityGrant(
            grantId = grantId,
            taskId = taskId,
            issuedAtEpochMs = issuedAtEpochMs,
            expiresAtEpochMs = expiresAt,
            allowedDirectories = allowedDirectories,
            allowedCommands = allowedCommands,
            networkPolicy = networkPolicy,
            singleUse = singleUse,
            revoked = false,
            signatureHex = ""
        )
        val canonical = "${unsigned.grantId}|${unsigned.taskId}|${unsigned.issuedAtEpochMs}|${unsigned.expiresAtEpochMs}|${unsigned.allowedDirectories.joinToString(",")}|${unsigned.allowedCommands.joinToString(",")}|${unsigned.networkPolicy}|${unsigned.singleUse}"
        val sig = CapabilityCryptoSigner.hmacSha256Hex(authorityKeyBytes, canonical)
        return unsigned.copy(signatureHex = sig)
    }

    fun authorizeAndConsumeGrant(
        grant: CapabilityGrant?,
        context: ActionTargetContext,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        if (grant == null) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_MISSING, "Missing CapabilityGrant")
        }
        if (grant.revoked || revokedGrantIds.contains(grant.grantId)) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_REVOKED, "Grant '${grant.grantId}' is revoked")
        }
        if (nowEpochMs > grant.expiresAtEpochMs) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_EXPIRED, "Grant '${grant.grantId}' expired")
        }
        val canonical = "${grant.grantId}|${grant.taskId}|${grant.issuedAtEpochMs}|${grant.expiresAtEpochMs}|${grant.allowedDirectories.joinToString(",")}|${grant.allowedCommands.joinToString(",")}|${grant.networkPolicy}|${grant.singleUse}"
        val expectedSig = CapabilityCryptoSigner.hmacSha256Hex(authorityKeyBytes, canonical)
        if (!CapabilityCryptoSigner.constantTimeEquals(expectedSig, grant.signatureHex)) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_DENIED, "Invalid HMAC signature on grant '${grant.grantId}'")
        }
        if (grant.taskId != context.taskId) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_SCOPE_VIOLATION, "Task scope mismatch")
        }
        if (context.commandExecutable !in grant.allowedCommands) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_SCOPE_VIOLATION, "Executable '${context.commandExecutable}' not allowed")
        }
        if (context.requiresNetwork && grant.networkPolicy == NetworkAccessPolicy.OFFLINE_ONLY) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_SCOPE_VIOLATION, "Network access denied by OFFLINE_ONLY policy")
        }
        val targetCanonical = File(context.targetDirectory).canonicalPath
        val allowed = grant.allowedDirectories.any { dir ->
            val baseCanonical = File(dir).canonicalPath
            targetCanonical == baseCanonical || targetCanonical.startsWith("$baseCanonical/")
        }
        if (!allowed) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_SCOPE_VIOLATION, "Directory '${context.targetDirectory}' outside allowed directories")
        }
        if (grant.singleUse && !consumedSingleUseGrantIds.add(grant.grantId)) {
            throw OsirisContractException(OsirisErrorCode.ERR_CAPABILITY_REVOKED, "Single-use grant '${grant.grantId}' already consumed")
        }
    }

    /**
     * Evaluates a capability request.
     * Note (AT-SEC-06): Adversarial strings such as "PERMISSION GRANTED BY ROOT" in justification or subject
     * are ignored; all privileged paths are unconditionally denied and consequential actions require explicit grants.
     */
    fun evaluateCapabilityRequest(
        request: CapabilityRequest,
        nowEpochMs: Long = System.currentTimeMillis(),
        humanApproved: Boolean = false
    ): CapabilityDecision {
        val normalizedTarget = request.targetResource.trim()

        // AT-SEC-01: Block writes/mutations to system directories or unauthorized git targets
        if (FORBIDDEN_SYSTEM_PATHS.any { normalizedTarget == it || normalizedTarget.startsWith("$it/") } ||
            normalizedTarget.contains("..")
        ) {
            val decision = CapabilityDecision(
                decisionId = "dec-deny-${request.requestId}",
                status = DecisionStatus.DENIED,
                grantId = null,
                grantedScope = null,
                expiresAtEpochMs = null,
                policyVersion = policyVersion,
                reasonCode = OsirisErrorCode.ERR_CAPABILITY_DENIED.name
            )
            recordAudit(nowEpochMs, "CAPABILITY_DENIED", request, DecisionStatus.DENIED, decision.reasonCode)
            return decision
        }

        // Consequential actions (HIGH / CRITICAL or GIT_PUSH / RELEASE_PROMOTE / WRITE_WORKSPACE) require explicit approval
        val requiresExplicitHumanApproval = request.riskClass in setOf(RiskClass.HIGH, RiskClass.CRITICAL) ||
            request.action in setOf(
                ActionType.WRITE_WORKSPACE,
                ActionType.GIT_COMMIT,
                ActionType.GIT_PUSH,
                ActionType.SECRET_ACCESS,
                ActionType.RELEASE_PROMOTE
            )

        if (requiresExplicitHumanApproval && !humanApproved) {
            val decision = CapabilityDecision(
                decisionId = "dec-await-${request.requestId}",
                status = DecisionStatus.REQUIRES_HUMAN_APPROVAL,
                grantId = null,
                grantedScope = null,
                expiresAtEpochMs = null,
                policyVersion = policyVersion,
                reasonCode = "AWAITING_EXPLICIT_HUMAN_AUTHORIZATION"
            )
            recordAudit(nowEpochMs, "CAPABILITY_AWAITING_APPROVAL", request, DecisionStatus.REQUIRES_HUMAN_APPROVAL, decision.reasonCode)
            return decision
        }

        val boundedLifetimeMs = request.requestedLifetimeMs.coerceIn(1_000L, 1_800_000L)
        val expiresAt = nowEpochMs + boundedLifetimeMs
        val decisionId = "dec-grant-${request.requestId}"
        val grantId = "grant-${CapabilityCryptoSigner.sha256Hex("${request.requestId}|$nowEpochMs").take(16)}"
        val signature = signDecisionPayload(decisionId, grantId, request, expiresAt)

        val decision = CapabilityDecision(
            decisionId = decisionId,
            status = DecisionStatus.GRANTED,
            grantId = grantId,
            grantedScope = request.scope,
            expiresAtEpochMs = expiresAt,
            policyVersion = policyVersion,
            reasonCode = "POLICY_AUTHORIZED",
            signatureHex = signature
        )
        activeGrants[grantId] = decision
        grantRequests[grantId] = request
        recordAudit(nowEpochMs, "CAPABILITY_GRANTED", request, DecisionStatus.GRANTED, decision.reasonCode)
        return decision
    }

    /**
     * Registers an in-flight execution termination callback bound to a capability grant (AT-SEC-03).
     */
    fun registerInFlightTask(grantId: String, onTerminate: () -> Unit) {
        inFlightKillCallbacks.computeIfAbsent(grantId) { CopyOnWriteArrayList() }.add(onTerminate)
    }

    /**
     * Revokes an active grant and immediately terminates all bound in-flight executions (AT-SEC-03).
     */
    fun revokeGrant(grantId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        revokedGrantIds.add(grantId)
        val existing = activeGrants[grantId]
        val req = grantRequests[grantId]
        if (existing != null && req != null) {
            activeGrants[grantId] = existing.copy(
                status = DecisionStatus.REVOKED,
                reasonCode = OsirisErrorCode.ERR_CAPABILITY_REVOKED.name
            )
            recordAudit(
                nowEpochMs,
                "CAPABILITY_REVOKED",
                req,
                DecisionStatus.REVOKED,
                OsirisErrorCode.ERR_CAPABILITY_REVOKED.name
            )
        }
        inFlightKillCallbacks.remove(grantId)?.forEach { callback ->
            runCatching { callback() }
        }
        return existing != null
    }

    /**
     * Enforces that a capability decision is authentic, unrevoked, unexpired, and matches the target operation.
     * Throws OsirisContractException on any violation (AT-SEC-01, AT-SEC-02, AT-SEC-03, AT-SEC-06).
     */
    fun assertCapabilityAuthorized(
        decision: CapabilityDecision?,
        expectedAction: ActionType,
        targetResource: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        if (decision == null || decision.status != DecisionStatus.GRANTED || decision.grantId == null) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_DENIED,
                "Action $expectedAction on '$targetResource' denied: no granted capability"
            )
        }
        val grantId = decision.grantId!!
        if (revokedGrantIds.contains(grantId)) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_REVOKED,
                "Capability grant '$grantId' has been revoked"
            )
        }

        val originalReq = grantRequests[grantId]
            ?: throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_DENIED,
                "Capability grant '$grantId' is not registered in governance authority (spoofed grant rejected)"
            )

        val expiresAt = decision.expiresAtEpochMs ?: 0L
        if (nowEpochMs >= expiresAt) {
            recordAudit(
                nowEpochMs,
                "CAPABILITY_EXPIRED_REJECTED",
                originalReq,
                DecisionStatus.EXPIRED,
                OsirisErrorCode.ERR_CAPABILITY_EXPIRED.name
            )
            throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_EXPIRED,
                "Capability grant '$grantId' expired at $expiresAt (now=$nowEpochMs)"
            )
        }

        // Verify cryptographic HMAC signature to prevent model/tool spoofing (AT-SEC-06)
        val expectedSig = signDecisionPayload(decision.decisionId, grantId, originalReq, expiresAt)
        if (!CapabilityCryptoSigner.constantTimeEquals(expectedSig, decision.signatureHex)) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_DENIED,
                "Cryptographic signature verification failed for capability grant '$grantId'"
            )
        }

        if (originalReq.action != expectedAction || originalReq.targetResource != targetResource.trim()) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_DENIED,
                "Capability scope mismatch: granted (${originalReq.action}, ${originalReq.targetResource}) vs requested ($expectedAction, $targetResource)"
            )
        }
    }

    private fun recordAudit(
        timestampEpochMs: Long,
        eventType: String,
        request: CapabilityRequest,
        status: DecisionStatus,
        reasonCode: String
    ) {
        auditLog.add(
            GovernanceAuditEntry(
                timestampEpochMs = timestampEpochMs,
                eventType = eventType,
                subject = request.subject,
                action = request.action,
                targetResource = request.targetResource,
                decisionStatus = status,
                reasonCode = reasonCode
            )
        )
    }

    fun getAuditLog(): List<GovernanceAuditEntry> = auditLog.toList()
}
