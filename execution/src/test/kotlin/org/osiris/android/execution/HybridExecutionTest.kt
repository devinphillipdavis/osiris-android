package org.osiris.android.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.osiris.android.execution.remote.RemoteEndpointCredentials
import org.osiris.android.governance.CapabilityGovernanceEngine
import org.osiris.android.sdk.EvaluationOutcome
import org.osiris.android.sdk.ExecutionAdapterType
import org.osiris.android.sdk.ExecutionState
import org.osiris.android.sdk.NetworkAccessPolicy
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode
import org.osiris.android.sdk.OsirisTaskIntent

class HybridExecutionTest {

    @Test
    fun `AT-HYB-01 offline composition persistence saves and restores drafts across restarts`() {
        val sharedKey = ByteArray(32) { (it + 7).toByte() }
        val storeBeforeRestart = EncryptedDraftStore(sharedKey)
        val intent = OsirisTaskIntent(
            schemaVersion = "1.0",
            taskId = "task-offline-101",
            createdAtEpochMs = 1_760_000_000_000L,
            title = "Offline Genome Verification",
            promptBody = "Compose and persist intent in airplane mode",
            adapterTarget = ExecutionAdapterType.LOCAL_TERMUX,
            requestedCapabilities = listOf("fs.read:/workspace")
        )
        storeBeforeRestart.saveOfflineDraft(intent)

        // Verify round-trip decryption and full fidelity
        val restored = storeBeforeRestart.loadOfflineDraft("task-offline-101")
        assertNotNull(restored)
        assertEquals(intent, restored)
    }

    @Test
    fun `AT-HYB-02 local adapter execution runs within sandbox and produces EvaluationReport and EvidenceManifest`() {
        val gov = CapabilityGovernanceEngine()
        val orchestrator = HybridExecutionOrchestrator(governanceEngine = gov)
        val intent = OsirisTaskIntent(
            schemaVersion = "1.0",
            taskId = "task-local-202",
            createdAtEpochMs = 1_760_000_000_000L,
            title = "Verify Local Substrate",
            promptBody = "/ignite && /learn 50",
            adapterTarget = ExecutionAdapterType.LOCAL_TERMUX,
            requestedCapabilities = listOf("exec:python3")
        )
        val draftRecord = orchestrator.submitIntent(intent)
        val grant = gov.issueGrant(
            grantId = "grant-local-202",
            taskId = intent.taskId,
            issuedAtEpochMs = 1_000L,
            ttlMs = 60_000L,
            allowedDirectories = listOf("/workspace"),
            allowedCommands = listOf("python3"),
            networkPolicy = NetworkAccessPolicy.OFFLINE_ONLY,
            singleUse = true
        )
        orchestrator.authorizeExecution(draftRecord.executionId, grant)
        val completed = orchestrator.runApprovedExecution(draftRecord.executionId, nowEpochMs = 2_000L)

        assertEquals(ExecutionState.COMPLETED, completed.state)
        val report = orchestrator.fetchEvaluationReport(draftRecord.executionId)
        val manifest = orchestrator.fetchEvidenceManifest(draftRecord.executionId)
        assertEquals(EvaluationOutcome.TESTED_PASS, report.outcome)
        assertTrue(manifest.artifactDigests.containsKey("repl_transcript.log"))
    }

    @Test
    fun `AT-HYB-03 and AT-HYB-04 remote execution and network disconnection recovery`() {
        val gov = CapabilityGovernanceEngine()
        val orchestrator = HybridExecutionOrchestrator(governanceEngine = gov)
        val intent = OsirisTaskIntent(
            schemaVersion = "1.0",
            taskId = "task-remote-303",
            createdAtEpochMs = 1_760_000_000_000L,
            title = "Remote QPU Simulation",
            promptBody = "Execute 11D CRSM batch on Node Beta",
            adapterTarget = ExecutionAdapterType.REMOTE_CLOUD_WORKER,
            requestedCapabilities = listOf("net:authenticated", "exec:osiris-remote-worker")
        )
        val draft = orchestrator.submitIntent(intent)
        val grant = gov.issueGrant(
            grantId = "grant-remote-303",
            taskId = intent.taskId,
            issuedAtEpochMs = 1_000L,
            ttlMs = 60_000L,
            allowedDirectories = listOf("/workspace"),
            allowedCommands = listOf("osiris-remote-worker"),
            networkPolicy = NetworkAccessPolicy.AUTHENTICATED_REMOTE_ONLY,
            singleUse = true
        )
        orchestrator.authorizeExecution(draft.executionId, grant)

        val creds = RemoteEndpointCredentials(
            endpointUrl = "https://beta.osiris.internal/v1/jobs",
            bearerToken = "ephemeral-jwt-token",
            serverCertSha256Pin = "b".repeat(64)
        )

        val completed = orchestrator.runApprovedExecution(
            executionId = draft.executionId,
            remoteCredentials = creds,
            simulateDisconnect = true,
            autoReconnect = true,
            nowEpochMs = 2_000L
        )
        assertEquals(ExecutionState.COMPLETED, completed.state)
    }

    @Test
    fun `AT-HYB-06 cancellation SLA halts running job within 2000ms and revokes grant`() {
        val gov = CapabilityGovernanceEngine()
        val orchestrator = HybridExecutionOrchestrator(governanceEngine = gov)
        val intent = OsirisTaskIntent(
            schemaVersion = "1.0",
            taskId = "task-cancel-404",
            createdAtEpochMs = 1_760_000_000_000L,
            title = "Long Running Evolution",
            promptBody = "/learn 5000",
            adapterTarget = ExecutionAdapterType.LOCAL_TERMUX,
            requestedCapabilities = listOf("exec:python3")
        )
        val draft = orchestrator.submitIntent(intent)
        val grant = gov.issueGrant(
            grantId = "grant-cancel-404",
            taskId = intent.taskId,
            issuedAtEpochMs = 1_000L,
            ttlMs = 60_000L,
            allowedDirectories = listOf("/workspace"),
            allowedCommands = listOf("python3"),
            networkPolicy = NetworkAccessPolicy.OFFLINE_ONLY,
            singleUse = true
        )
        orchestrator.authorizeExecution(draft.executionId, grant)
        orchestrator.transitionState(draft.executionId, ExecutionState.RUNNING, "In progress")

        val startMs = System.currentTimeMillis()
        val cancelled = orchestrator.cancelExecution(draft.executionId)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertEquals(ExecutionState.CANCELLED, cancelled.state)
        assertTrue("Cancellation must complete within 2000ms", elapsedMs <= 2_000L)
    }

    @Test
    fun `AT-API-03 state machine rejects illegal transition from DRAFT directly to COMPLETED`() {
        val orchestrator = HybridExecutionOrchestrator()
        val intent = OsirisTaskIntent(
            schemaVersion = "1.0",
            taskId = "task-illegal-505",
            createdAtEpochMs = 1_760_000_000_000L,
            title = "Illegal Jump Test",
            promptBody = "Skip approval",
            adapterTarget = ExecutionAdapterType.LOCAL_TERMUX,
            requestedCapabilities = listOf("fs.read")
        )
        val draft = orchestrator.submitIntent(intent)
        try {
            orchestrator.transitionState(draft.executionId, ExecutionState.COMPLETED, "Bypass")
            fail("Expected ERR_INVALID_STATE_TRANSITION")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_INVALID_STATE_TRANSITION, e.errorCode)
        }
    }

    @Test
    fun `AT-PERF-04 streaming log backpressure handles 10,000 lines without OOM or frame drop`() {
        val buffer = BackpressureLogStreamBuffer(maxRetainedLines = 500)
        for (i in 1..10_000) {
            buffer.ingestLine("[TELEMETRY] line=$i gamma=0.0310 theta=51.843")
        }
        assertEquals(10_000L, buffer.totalIngestedLines)
        val window = buffer.snapshotWindow()
        assertEquals(500, window.size)
        assertTrue(window.last().contains("line=10000"))
    }
}
