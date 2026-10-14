package org.osiris.android.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdkContractTest {

    @Test
    fun `AT-API-01 Contract Serialization round-trip byte parity`() {
        val req = IntentRequest(
            schemaVersion = "1.0.0",
            requestId = "req-fold-001",
            idempotencyKey = "idem-fold-001",
            naturalLanguageTask = "intent::verify_crsm_substrate(theta=51.843)",
            contextAttachments = listOf(
                ContextRef(
                    type = "DNA_GENOME",
                    pathOrUri = "genomes/crsm_v1.dna",
                    sha256Digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                )
            ),
            requestedMode = ExecutionMode.LOCAL_FIRST,
            parameters = mapOf("theta_lock" to "51.843", "gamma_floor" to "0.0920"),
            createdAtEpochMs = 1791720000000L
        )
        val json1 = ContractSerializer.encodeIntentRequest(req)
        val decoded = ContractSerializer.decodeIntentRequest(json1)
        val json2 = ContractSerializer.encodeIntentRequest(decoded)

        assertEquals(req, decoded)
        assertEquals(json1, json2)

        val manifest = EvidenceManifest(
            schemaVersion = "1.0.0",
            executionId = "exec-001",
            repositoryCommit = "9a711d8",
            genomeRevision = "dna-rev-42",
            policyRevision = "pol-v1.0",
            mode = ExecutionMode.LOCAL_FIRST,
            startedAtEpochMs = 1791720000000L,
            finishedAtEpochMs = 1791720001500L,
            artifactDigests = mapOf("ledger.json" to CapabilityCryptoSigner.sha256Hex("ledger-content")),
            approvalReceiptId = "rcpt-human-001",
            claimCeiling = "Unit harness verification only"
        )
        val manifestJson1 = ContractSerializer.encode(manifest)
        val decodedManifest = ContractSerializer.decode<EvidenceManifest>(manifestJson1)
        val manifestJson2 = ContractSerializer.encode(decodedManifest)
        assertEquals(manifest, decodedManifest)
        assertEquals(manifestJson1, manifestJson2)
    }

    @Test
    fun `AT-API-02 Invalid Input Rejection for malformed schemas and unsupported major versions`() {
        val unsupportedVersionJson = """
            {"schemaVersion":"2.0.0","requestId":"r1","idempotencyKey":"k1","naturalLanguageTask":"test","createdAtEpochMs":1000}
        """.trimIndent()
        try {
            ContractSerializer.decodeIntentRequest(unsupportedVersionJson)
            fail("Expected ERR_VERSION_UNSUPPORTED")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_VERSION_UNSUPPORTED, e.errorCode)
        }

        val malformedVersionJson = """
            {"schemaVersion":"v1-beta","requestId":"r1","idempotencyKey":"k1","naturalLanguageTask":"test","createdAtEpochMs":1000}
        """.trimIndent()
        try {
            ContractSerializer.decodeIntentRequest(malformedVersionJson)
            fail("Expected ERR_SCHEMA_MISMATCH")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_SCHEMA_MISMATCH, e.errorCode)
        }

        val missingFieldsJson = """{"schemaVersion":"1.0.0","requestId":""}"""
        try {
            ContractSerializer.decodeIntentRequest(missingFieldsJson)
            fail("Expected ERR_SCHEMA_MISMATCH")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_SCHEMA_MISMATCH, e.errorCode)
        }
    }

    @Test
    fun `AT-API-04 State Machine Invariants reject 100 illegal state transitions and preserve state`() {
        val sm = ExecutionStateMachine()
        val illegalPairs = mutableListOf<Pair<ExecutionState, ExecutionState>>()
        for (from in ExecutionState.entries) {
            for (to in ExecutionState.entries) {
                if (!sm.isAllowedTransition(from, to)) {
                    illegalPairs.add(from to to)
                }
            }
        }
        assertTrue("Expected at least 100 illegal transitions", illegalPairs.size >= 100)

        val testedPairs = illegalPairs.take(100)
        testedPairs.forEachIndexed { idx, (fromState, illegalTarget) ->
            val execId = "exec-sm-$idx"
            sm.initialize(execId, fromState)
            val seqBefore = sm.currentSequence(execId)
            try {
                sm.transition(execId, illegalTarget)
                fail("Illegal transition $fromState -> $illegalTarget should have been rejected")
            } catch (e: OsirisContractException) {
                assertEquals(OsirisErrorCode.ERR_ILLEGAL_STATE_TRANSITION, e.errorCode)
            }
            // State and sequence must be preserved unchanged
            assertEquals(fromState, sm.currentState(execId))
            assertEquals(seqBefore, sm.currentSequence(execId))
        }
        assertEquals(100, sm.getIllegalTransitionAuditLog().size)
    }
}
