package org.osiris.android.app.approvals

import org.osiris.android.governance.CapabilityGovernanceEngine
import org.osiris.android.sdk.CapabilityGrant
import org.osiris.android.sdk.NetworkAccessPolicy

/**
 * Interactive Capability Grant & Gate 0 Approval Controller (Section 6.2, AT-SEC-01..03).
 */
class ApprovalGateController(
    private val governanceEngine: CapabilityGovernanceEngine = CapabilityGovernanceEngine()
) {
    fun approveTaskScopedGrant(
        taskId: String,
        allowedDirectories: List<String>,
        allowedCommands: List<String>,
        networkPolicy: NetworkAccessPolicy = NetworkAccessPolicy.OFFLINE_ONLY,
        ttlMs: Long = 300_000L,
        nowEpochMs: Long = System.currentTimeMillis()
    ): CapabilityGrant {
        return governanceEngine.issueGrant(
            grantId = "grant-$taskId-$nowEpochMs",
            taskId = taskId,
            issuedAtEpochMs = nowEpochMs,
            ttlMs = ttlMs,
            allowedDirectories = allowedDirectories,
            allowedCommands = allowedCommands,
            networkPolicy = networkPolicy,
            singleUse = true
        )
    }

    fun revokeGrantImmediately(grantId: String) {
        governanceEngine.revokeGrant(grantId)
    }
}
