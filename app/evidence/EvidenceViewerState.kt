package org.osiris.android.app.evidence

import kotlinx.serialization.Serializable
import org.osiris.android.sdk.DeploymentAuthority
import org.osiris.android.sdk.EvaluationOutcome
import org.osiris.android.sdk.EvidenceStatus
import org.osiris.android.sdk.ImplementationStatus
import org.osiris.android.sdk.NormativeStatusRecord

@Serializable
data class EvidenceStatusBadgeGroup(
    val implementationBadge: String,
    val evidenceBadge: String,
    val authorityBadge: String,
    val claimCeilingBanner: String,
    val isDeploymentAuthorized: Boolean
)

/**
 * Enforces AT-UI-06 (Evidence Status Badge Separation) and Section 2 Normative Orthogonality.
 * Passing unit/integration tests set evidence_status = TESTED_PASS, but NEVER automatically
 * promote deployment_authority to AUTHORIZED without an explicit signed human approval receipt.
 */
object EvidenceBadgePresenter {

    fun deriveNormativeRecordFromTestOutcome(
        implementationStatus: ImplementationStatus,
        evaluationOutcome: EvaluationOutcome,
        hasSignedHumanReleaseReceipt: Boolean,
        authorityRevoked: Boolean = false
    ): NormativeStatusRecord {
        val evStatus = when (evaluationOutcome) {
            EvaluationOutcome.TESTED_PASS -> EvidenceStatus.TESTED_PASS
            EvaluationOutcome.TESTED_FAIL -> EvidenceStatus.TESTED_FAIL
            EvaluationOutcome.INCONCLUSIVE -> EvidenceStatus.INCONCLUSIVE
            EvaluationOutcome.BLOCKED -> EvidenceStatus.NOT_TESTED
        }
        val authority = when {
            authorityRevoked -> DeploymentAuthority.REVOKED
            hasSignedHumanReleaseReceipt && evStatus == EvidenceStatus.TESTED_PASS ->
                DeploymentAuthority.AUTHORIZED
            implementationStatus == ImplementationStatus.IMPLEMENTED ->
                DeploymentAuthority.REQUIRES_APPROVAL
            else ->
                DeploymentAuthority.NONE
        }
        val ceiling = when (evStatus) {
            EvidenceStatus.TESTED_PASS ->
                "Verified behavior within automated test harness only; does not establish clinical/scientific efficacy or production deployment authorization."
            EvidenceStatus.TESTED_FAIL ->
                "Failing test assertions; promotion blocked."
            EvidenceStatus.INCONCLUSIVE ->
                "Evaluation inconclusive; promotion blocked."
            EvidenceStatus.NOT_TESTED ->
                "Not yet verified by automated test harness."
        }
        return NormativeStatusRecord(
            implementationStatus = implementationStatus,
            evidenceStatus = evStatus,
            deploymentAuthority = authority,
            claimCeiling = ceiling
        )
    }

    fun renderBadges(record: NormativeStatusRecord): EvidenceStatusBadgeGroup {
        return EvidenceStatusBadgeGroup(
            implementationBadge = "IMPL: ${record.implementationStatus.name}",
            evidenceBadge = "EVIDENCE: ${record.evidenceStatus.name}",
            authorityBadge = "AUTHORITY: ${record.deploymentAuthority.name}",
            claimCeilingBanner = "CLAIM CEILING: ${record.claimCeiling}",
            isDeploymentAuthorized = record.deploymentAuthority == DeploymentAuthority.AUTHORIZED
        )
    }
}
