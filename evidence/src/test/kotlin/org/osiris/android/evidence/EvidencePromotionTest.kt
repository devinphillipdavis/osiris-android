package org.osiris.android.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.osiris.android.sdk.CapabilityCryptoSigner
import org.osiris.android.sdk.DeploymentAuthority
import org.osiris.android.sdk.EvaluationOutcome
import org.osiris.android.sdk.EvaluationReport
import org.osiris.android.sdk.EvidenceManifest
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode
import org.osiris.android.sdk.TestResult
import java.nio.charset.StandardCharsets

class EvidencePromotionTest {

    private fun validArtifactBytes(): ByteArray =
        "apk-binary-payload-v1".toByteArray(StandardCharsets.UTF_8)

    private fun validManifest(artifactBytes: ByteArray = validArtifactBytes()): EvidenceManifest =
        EvidenceManifest(
            schemaVersion = "1.0",
            executionId = "exec-rel-100",
            repositoryCommit = "9a711d8",
            genomeRevision = "gen-v4.5.3",
            policyRevision = "pol-2026.10",
            startedAtEpochMs = 1_760_000_000_000L,
            finishedAtEpochMs = 1_760_000_010_000L,
            artifactDigests = mapOf(
                "app-release.apk" to CapabilityCryptoSigner.sha256Hex(artifactBytes)
            ),
            approvalReceiptId = "rcpt-gate0-001",
            claimCeiling = "VERIFIED_LOCAL_BUILD_AND_TEST_SUITE_ONLY"
        )

    private fun passingEvaluationReport(executionId: String = "exec-rel-100"): EvaluationReport =
        EvaluationReport(
            schemaVersion = "1.0",
            executionId = executionId,
            evaluatorIdentity = "osiris-independent-evaluator",
            evaluatorVersion = "1.0.0",
            outcome = EvaluationOutcome.TESTED_PASS,
            testResults = listOf(
                TestResult("AT-BLD-01", "Build", EvaluationOutcome.TESTED_PASS, 120L, "OK")
            ),
            measuredMetrics = mapOf("wasserstein_2_phi" to 0.012),
            limitations = listOf("Automated unit/integration scope")
        )

    @Test
    fun `AT-EVD-01 manifest completeness rejects missing mandatory fields`() {
        val incomplete = validManifest().copy(genomeRevision = "   ")
        try {
            EvidenceManifestVerifier.verifyManifestCompleteness(incomplete)
            fail("Expected ERR_PROMOTION_BLOCKED for blank genomeRevision")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_PROMOTION_BLOCKED, e.errorCode)
        }
    }

    @Test
    fun `AT-EVD-02 and AT-SEC-07 tampered artifact digest is rejected with ERR_EVIDENCE_TAMPERED`() {
        val manifest = validManifest()
        val tamperedBytes = "apk-binary-payload-TAMPERED".toByteArray(StandardCharsets.UTF_8)
        try {
            EvidenceManifestVerifier.verifyArtifactDigests(
                manifest = manifest,
                actualArtifacts = mapOf("app-release.apk" to tamperedBytes)
            )
            fail("Expected ERR_EVIDENCE_TAMPERED when artifact bytes are mutated")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_EVIDENCE_TAMPERED, e.errorCode)
        }
    }

    @Test
    fun `AT-EVD-03 failing test blocks promotion with ERR_PROMOTION_BLOCKED`() {
        val gate = ReleasePromotionGate()
        val bytes = validArtifactBytes()
        val manifest = validManifest(bytes)
        val receipt = gate.signHumanApproval(
            receiptId = manifest.approvalReceiptId,
            approverIdentity = "lead-architect@osiris",
            approvedCommit = manifest.repositoryCommit,
            manifest = manifest,
            timestampEpochMs = 1_760_000_020_000L
        )
        val failingReport = passingEvaluationReport().copy(
            outcome = EvaluationOutcome.TESTED_FAIL,
            testResults = listOf(
                TestResult("AT-SEC-01", "Security", EvaluationOutcome.TESTED_FAIL, 40L, "Failed")
            )
        )
        val submission = ReleaseCandidateSubmission(
            manifest = manifest,
            evaluationReport = failingReport,
            actualArtifacts = mapOf("app-release.apk" to bytes),
            humanApprovalReceipt = receipt,
            branchProtectionChecksPassed = true,
            openHighOrCriticalVulnerabilities = 0,
            buildToolchainIdentity = "Gradle-9.1.0-AGP-9.0.1-JDK21",
            signingKeyFingerprint = "SHA256:OSIRIS-KEYSTORE-FP-01"
        )
        try {
            gate.evaluatePromotion(submission)
            fail("Expected ERR_PROMOTION_BLOCKED when a test fails")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_PROMOTION_BLOCKED, e.errorCode)
        }
    }

    @Test
    fun `AT-REL-01 human gate enforcement halts promotion until signed human approval is recorded`() {
        val gate = ReleasePromotionGate()
        val bytes = validArtifactBytes()
        val manifest = validManifest(bytes)
        val submissionWithoutReceipt = ReleaseCandidateSubmission(
            manifest = manifest,
            evaluationReport = passingEvaluationReport(),
            actualArtifacts = mapOf("app-release.apk" to bytes),
            humanApprovalReceipt = null,
            branchProtectionChecksPassed = true,
            openHighOrCriticalVulnerabilities = 0,
            buildToolchainIdentity = "Gradle-9.1.0-AGP-9.0.1-JDK21",
            signingKeyFingerprint = "SHA256:OSIRIS-KEYSTORE-FP-01"
        )
        try {
            gate.evaluatePromotion(submissionWithoutReceipt)
            fail("Expected Gate 0 halt without human approval receipt")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_PROMOTION_BLOCKED, e.errorCode)
        }

        // Now provide valid signed human receipt
        val validReceipt = gate.signHumanApproval(
            receiptId = manifest.approvalReceiptId,
            approverIdentity = "lead-architect@osiris",
            approvedCommit = manifest.repositoryCommit,
            manifest = manifest,
            timestampEpochMs = 1_760_000_020_000L
        )
        val decision = gate.evaluatePromotion(submissionWithoutReceipt.copy(humanApprovalReceipt = validReceipt))
        assertTrue(decision.promoted)
        assertEquals(DeploymentAuthority.AUTHORIZED, decision.deploymentAuthority)
    }

    @Test
    fun `AT-REL-02 branch protection enforcement blocks unauthorized merge to main`() {
        val gate = ReleasePromotionGate()
        val bytes = validArtifactBytes()
        val manifest = validManifest(bytes)
        val receipt = gate.signHumanApproval(
            receiptId = manifest.approvalReceiptId,
            approverIdentity = "lead-architect@osiris",
            approvedCommit = manifest.repositoryCommit,
            manifest = manifest,
            timestampEpochMs = 1_760_000_020_000L
        )
        val submission = ReleaseCandidateSubmission(
            manifest = manifest,
            evaluationReport = passingEvaluationReport(),
            actualArtifacts = mapOf("app-release.apk" to bytes),
            humanApprovalReceipt = receipt,
            branchProtectionChecksPassed = false,
            openHighOrCriticalVulnerabilities = 0,
            buildToolchainIdentity = "Gradle-9.1.0-AGP-9.0.1-JDK21",
            signingKeyFingerprint = "SHA256:OSIRIS-KEYSTORE-FP-01"
        )
        try {
            gate.evaluatePromotion(submission)
            fail("Expected ERR_PROMOTION_BLOCKED when branch protection fails")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_PROMOTION_BLOCKED, e.errorCode)
            assertTrue(gate.getAuditLog().any { it.contains("Unauthorized branch promotion") })
        }
    }

    @Test
    fun `AT-REL-03 provenance verification rejects candidate missing toolchain or signing key identity`() {
        val gate = ReleasePromotionGate()
        val bytes = validArtifactBytes()
        val manifest = validManifest(bytes)
        val receipt = gate.signHumanApproval(
            receiptId = manifest.approvalReceiptId,
            approverIdentity = "lead-architect@osiris",
            approvedCommit = manifest.repositoryCommit,
            manifest = manifest,
            timestampEpochMs = 1_760_000_020_000L
        )
        val submission = ReleaseCandidateSubmission(
            manifest = manifest,
            evaluationReport = passingEvaluationReport(),
            actualArtifacts = mapOf("app-release.apk" to bytes),
            humanApprovalReceipt = receipt,
            branchProtectionChecksPassed = true,
            openHighOrCriticalVulnerabilities = 0,
            buildToolchainIdentity = "",
            signingKeyFingerprint = ""
        )
        try {
            gate.evaluatePromotion(submission)
            fail("Expected ERR_PROMOTION_BLOCKED when provenance metadata is blank")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_PROMOTION_BLOCKED, e.errorCode)
        }
    }

    @Test
    fun `AT-REL-04 rollback verification restores previous verified artifact within 60s and emits receipt`() {
        val gate = ReleasePromotionGate()
        val prevBytes = "verified-release-v1.0.0".toByteArray(StandardCharsets.UTF_8)
        val rollbackReceipt = gate.triggerStagingRollback(
            failedReleaseId = "rel-v1.0.1-broken",
            previousVerifiedReleaseId = "rel-v1.0.0-verified",
            previousVerifiedArtifactBytes = prevBytes,
            nowEpochMs = 1_760_000_050_000L
        )
        assertEquals("rel-v1.0.0-verified", rollbackReceipt.restoredReleaseId)
        assertEquals(CapabilityCryptoSigner.sha256Hex(prevBytes), rollbackReceipt.restoredArtifactDigest)
        assertTrue(rollbackReceipt.elapsedDurationMs <= 60_000L)
    }

    @Test
    fun `DynamicEvidenceLedger maintains unbroken SHA-256 hash chain and Merkle root`() {
        val ledger = DynamicEvidenceLedger()
        ledger.appendEvent("exec-1", "IGNITE", "theta=51.843", 1000L)
        ledger.appendEvent("exec-1", "LEARN", "gen=50", 2000L)
        ledger.appendEvent("exec-1", "DNA", "genome=G1", 3000L)

        assertTrue(ledger.verifyLedgerIntegrity())
        assertEquals(64, ledger.computeMerkleRoot().length)
        assertEquals(3, ledger.getEvents().size)
    }
}
