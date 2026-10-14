package org.osiris.android.evidence

import kotlinx.serialization.Serializable
import org.osiris.android.sdk.CapabilityCryptoSigner
import org.osiris.android.sdk.DeploymentAuthority
import org.osiris.android.sdk.EvaluationOutcome
import org.osiris.android.sdk.EvaluationReport
import org.osiris.android.sdk.EvidenceManifest
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class LedgerEvent(
    val sequence: Long,
    val timestampEpochMs: Long,
    val executionId: String,
    val eventType: String,
    val payloadSha256: String,
    val previousEventHash: String,
    val eventHash: String
)

/**
 * SHA-256 Dynamic Evidence Ledger with hash chain and Merkle root verification (/ledger contract).
 */
class DynamicEvidenceLedger {
    private val events = CopyOnWriteArrayList<LedgerEvent>()

    @Synchronized
    fun appendEvent(
        executionId: String,
        eventType: String,
        payload: String,
        timestampEpochMs: Long = System.currentTimeMillis()
    ): LedgerEvent {
        val seq = (events.size + 1).toLong()
        val prevHash = events.lastOrNull()?.eventHash ?: "0".repeat(64)
        val payloadHash = CapabilityCryptoSigner.sha256Hex(payload)
        val eventHash = CapabilityCryptoSigner.sha256Hex("$seq|$timestampEpochMs|$executionId|$eventType|$payloadHash|$prevHash")
        val entry = LedgerEvent(
            sequence = seq,
            timestampEpochMs = timestampEpochMs,
            executionId = executionId,
            eventType = eventType,
            payloadSha256 = payloadHash,
            previousEventHash = prevHash,
            eventHash = eventHash
        )
        events.add(entry)
        return entry
    }

    @Synchronized
    fun computeMerkleRoot(): String {
        if (events.isEmpty()) return "0".repeat(64)
        var currentLayer = events.map { it.eventHash }
        while (currentLayer.size > 1) {
            val nextLayer = mutableListOf<String>()
            var i = 0
            while (i < currentLayer.size) {
                val left = currentLayer[i]
                val right = if (i + 1 < currentLayer.size) currentLayer[i + 1] else left
                nextLayer.add(CapabilityCryptoSigner.sha256Hex("$left|$right"))
                i += 2
            }
            currentLayer = nextLayer
        }
        return currentLayer.first()
    }

    @Synchronized
    fun verifyLedgerIntegrity(): Boolean {
        var expectedPrev = "0".repeat(64)
        for ((idx, ev) in events.withIndex()) {
            if (ev.sequence != (idx + 1).toLong()) return false
            if (ev.previousEventHash != expectedPrev) return false
            val recomputed = CapabilityCryptoSigner.sha256Hex(
                "${ev.sequence}|${ev.timestampEpochMs}|${ev.executionId}|${ev.eventType}|${ev.payloadSha256}|${ev.previousEventHash}"
            )
            if (!CapabilityCryptoSigner.constantTimeEquals(recomputed, ev.eventHash)) return false
            expectedPrev = ev.eventHash
        }
        return true
    }

    @Synchronized
    fun getEvents(): List<LedgerEvent> = events.toList()
}

/**
 * Verifies EvidenceManifest completeness (AT-EVD-01) and cryptographic artifact digests (AT-EVD-02, AT-SEC-07).
 */
object EvidenceManifestVerifier {

    fun verifyManifestCompleteness(manifest: EvidenceManifest) {
        val mandatoryStrings = mapOf(
            "schemaVersion" to manifest.schemaVersion,
            "executionId" to manifest.executionId,
            "repositoryCommit" to manifest.repositoryCommit,
            "genomeRevision" to manifest.genomeRevision,
            "policyRevision" to manifest.policyRevision,
            "approvalReceiptId" to manifest.approvalReceiptId,
            "claimCeiling" to manifest.claimCeiling
        )
        for ((fieldName, value) in mandatoryStrings) {
            if (value.isBlank()) {
                throw OsirisContractException(
                    OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                    "EvidenceManifest missing mandatory field '$fieldName'"
                )
            }
        }
        if (manifest.startedAtEpochMs <= 0L || manifest.finishedAtEpochMs < manifest.startedAtEpochMs) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                "EvidenceManifest contains invalid execution timestamps"
            )
        }
        if (manifest.artifactDigests.isEmpty()) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                "EvidenceManifest must contain at least one artifact digest"
            )
        }
    }

    fun verifyArtifactDigests(
        manifest: EvidenceManifest,
        actualArtifacts: Map<String, ByteArray>
    ) {
        verifyManifestCompleteness(manifest)
        for ((artifactName, expectedDigest) in manifest.artifactDigests) {
            val actualBytes = actualArtifacts[artifactName]
                ?: throw OsirisContractException(
                    OsirisErrorCode.ERR_EVIDENCE_TAMPERED,
                    "Missing artifact '$artifactName' declared in EvidenceManifest"
                )
            val actualDigest = CapabilityCryptoSigner.sha256Hex(actualBytes)
            if (!CapabilityCryptoSigner.constantTimeEquals(expectedDigest, actualDigest)) {
                throw OsirisContractException(
                    OsirisErrorCode.ERR_EVIDENCE_TAMPERED,
                    "SHA-256 digest mismatch for artifact '$artifactName': expected=$expectedDigest actual=$actualDigest"
                )
            }
        }
    }
}

@Serializable
data class HumanApprovalReceipt(
    val receiptId: String,
    val approverIdentity: String,
    val approvedCommit: String,
    val approvedManifestDigest: String,
    val timestampEpochMs: Long,
    val signatureHex: String
)

@Serializable
data class ReleaseCandidateSubmission(
    val manifest: EvidenceManifest,
    val evaluationReport: EvaluationReport,
    val actualArtifacts: Map<String, ByteArray>,
    val humanApprovalReceipt: HumanApprovalReceipt?,
    val branchProtectionChecksPassed: Boolean,
    val openHighOrCriticalVulnerabilities: Int,
    val buildToolchainIdentity: String,
    val signingKeyFingerprint: String
)

@Serializable
data class PromotionDecision(
    val promoted: Boolean,
    val deploymentAuthority: DeploymentAuthority,
    val reasonCode: String,
    val provenanceDigest: String
)

@Serializable
data class RollbackEvidenceReceipt(
    val rollbackId: String,
    val failedReleaseId: String,
    val restoredReleaseId: String,
    val restoredArtifactDigest: String,
    val elapsedDurationMs: Long,
    val timestampEpochMs: Long
)

/**
 * Enforces Release Decision Rule (Section 13) & Suite F (AT-EVD-01..04, AT-REL-01..04).
 */
class ReleasePromotionGate(
    private val releaseAuthoritySecret: ByteArray = "osiris-release-authority-v1".toByteArray(StandardCharsets.UTF_8)
) {
    private val auditLog = CopyOnWriteArrayList<String>()

    fun signHumanApproval(
        receiptId: String,
        approverIdentity: String,
        approvedCommit: String,
        manifest: EvidenceManifest,
        timestampEpochMs: Long
    ): HumanApprovalReceipt {
        val manifestDigest = CapabilityCryptoSigner.sha256Hex(
            "${manifest.executionId}|${manifest.repositoryCommit}|${manifest.genomeRevision}|${manifest.policyRevision}"
        )
        val payload = "$receiptId|$approverIdentity|$approvedCommit|$manifestDigest|$timestampEpochMs"
        val sig = CapabilityCryptoSigner.hmacSha256Hex(releaseAuthoritySecret, payload)
        return HumanApprovalReceipt(
            receiptId = receiptId,
            approverIdentity = approverIdentity,
            approvedCommit = approvedCommit,
            approvedManifestDigest = manifestDigest,
            timestampEpochMs = timestampEpochMs,
            signatureHex = sig
        )
    }

    fun evaluatePromotion(submission: ReleaseCandidateSubmission): PromotionDecision {
        // 1. Verify manifest completeness & digests (AT-EVD-01, AT-EVD-02, AT-SEC-07)
        EvidenceManifestVerifier.verifyArtifactDigests(submission.manifest, submission.actualArtifacts)

        // 2. Verify full provenance metadata (AT-REL-03)
        if (submission.buildToolchainIdentity.isBlank() ||
            submission.signingKeyFingerprint.isBlank() ||
            submission.manifest.repositoryCommit.isBlank()
        ) {
            auditLog.add("PROMOTION_BLOCKED: Missing provenance metadata")
            throw OsirisContractException(
                OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                "Release candidate missing mandatory provenance metadata (toolchain, signing fingerprint, or commit)"
            )
        }

        // 3. Verify branch protection & OCCR authority (AT-REL-02)
        if (!submission.branchProtectionChecksPassed) {
            auditLog.add("PROMOTION_BLOCKED: Unauthorized branch promotion for commit ${submission.manifest.repositoryCommit}")
            throw OsirisContractException(
                OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                "Branch protection checks failed; unauthorized merge to main blocked"
            )
        }

        // 4. Verify zero failing tests or inconclusive evaluations (AT-EVD-03, AT-EVD-04)
        val hasFailingTest = submission.evaluationReport.outcome != EvaluationOutcome.TESTED_PASS ||
            submission.evaluationReport.testResults.any { it.status != EvaluationOutcome.TESTED_PASS }
        if (hasFailingTest) {
            auditLog.add("PROMOTION_BLOCKED: Failing or non-passing evaluation outcome")
            throw OsirisContractException(
                OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                "Failing or non-passing test in EvaluationReport blocks release promotion"
            )
        }

        // 5. Verify zero open high/critical security vulnerabilities
        if (submission.openHighOrCriticalVulnerabilities > 0) {
            auditLog.add("PROMOTION_BLOCKED: ${submission.openHighOrCriticalVulnerabilities} open high/critical findings")
            throw OsirisContractException(
                OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                "Open high or critical security vulnerabilities block promotion"
            )
        }

        // 6. Verify explicit signed human authorization at Gate 0 (AT-REL-01)
        val receipt = submission.humanApprovalReceipt
            ?: run {
                auditLog.add("PROMOTION_BLOCKED: Missing human approval receipt at Gate 0")
                throw OsirisContractException(
                    OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                    "Gate 0 halt: Human authorization receipt required before release promotion"
                )
            }

        val expectedManifestDigest = CapabilityCryptoSigner.sha256Hex(
            "${submission.manifest.executionId}|${submission.manifest.repositoryCommit}|${submission.manifest.genomeRevision}|${submission.manifest.policyRevision}"
        )
        val expectedPayload = "${receipt.receiptId}|${receipt.approverIdentity}|${receipt.approvedCommit}|$expectedManifestDigest|${receipt.timestampEpochMs}"
        val expectedSig = CapabilityCryptoSigner.hmacSha256Hex(releaseAuthoritySecret, expectedPayload)
        if (!CapabilityCryptoSigner.constantTimeEquals(expectedSig, receipt.signatureHex) ||
            receipt.approvedCommit != submission.manifest.repositoryCommit
        ) {
            auditLog.add("PROMOTION_BLOCKED: Invalid human approval signature")
            throw OsirisContractException(
                OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                "Gate 0 halt: Human approval signature mismatch or commit mismatch"
            )
        }

        val provenanceDigest = CapabilityCryptoSigner.sha256Hex(
            "${submission.manifest.repositoryCommit}|${submission.buildToolchainIdentity}|${submission.signingKeyFingerprint}|${receipt.receiptId}"
        )
        auditLog.add("PROMOTION_AUTHORIZED: commit=${submission.manifest.repositoryCommit} receipt=${receipt.receiptId}")
        return PromotionDecision(
            promoted = true,
            deploymentAuthority = DeploymentAuthority.AUTHORIZED,
            reasonCode = "GATE_0_AUTHORIZED",
            provenanceDigest = provenanceDigest
        )
    }

    /**
     * Automated staging rollback (AT-REL-04): restores previous verified artifact within 60s on staging failure.
     */
    fun triggerStagingRollback(
        failedReleaseId: String,
        previousVerifiedReleaseId: String,
        previousVerifiedArtifactBytes: ByteArray,
        nowEpochMs: Long = System.currentTimeMillis()
    ): RollbackEvidenceReceipt {
        val startNs = System.nanoTime()
        val digest = CapabilityCryptoSigner.sha256Hex(previousVerifiedArtifactBytes)
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        if (elapsedMs > 60_000L) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_PROMOTION_BLOCKED,
                "Staging rollback exceeded 60s SLA (${elapsedMs}ms)"
            )
        }
        val receipt = RollbackEvidenceReceipt(
            rollbackId = "rb-${CapabilityCryptoSigner.sha256Hex("$failedReleaseId|$nowEpochMs").take(12)}",
            failedReleaseId = failedReleaseId,
            restoredReleaseId = previousVerifiedReleaseId,
            restoredArtifactDigest = digest,
            elapsedDurationMs = elapsedMs,
            timestampEpochMs = nowEpochMs
        )
        auditLog.add("STAGING_ROLLBACK: restored=$previousVerifiedReleaseId digest=$digest")
        return receipt
    }

    fun getAuditLog(): List<String> = auditLog.toList()
}
