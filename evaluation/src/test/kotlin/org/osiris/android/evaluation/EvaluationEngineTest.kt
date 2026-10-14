package org.osiris.android.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osiris.android.sdk.EvaluationOutcome
import org.osiris.android.sdk.ExecutionAdapterType
import org.osiris.android.sdk.TestResult

class EvaluationEngineTest {

    private val engine = IndependentEvaluatorEngine()

    @Test
    fun `AT-EVD-04 missing evaluator marks outcome INCONCLUSIVE and never TESTED_PASS`() {
        val report = engine.evaluateExecution(
            executionId = "exec-missing-eval",
            adapterType = ExecutionAdapterType.LOCAL_TERMUX,
            evaluatorBlock = null
        )
        assertEquals(EvaluationOutcome.INCONCLUSIVE, report.outcome)
        assertNotEquals(EvaluationOutcome.TESTED_PASS, report.outcome)
    }

    @Test
    fun `AT-EVD-04 crashing evaluator marks outcome INCONCLUSIVE and never TESTED_PASS`() {
        val report = engine.evaluateExecution(
            executionId = "exec-crash-eval",
            adapterType = ExecutionAdapterType.LOCAL_TERMUX,
            evaluatorBlock = {
                throw IllegalStateException("Simulated evaluator process crash")
            }
        )
        assertEquals(EvaluationOutcome.INCONCLUSIVE, report.outcome)
        assertNotEquals(EvaluationOutcome.TESTED_PASS, report.outcome)
        assertTrue(report.limitations.first().contains("Evaluator crashed"))
    }

    @Test
    fun `AT-HYB-05 normalized evaluation reports across local and remote execution adapters`() {
        val testAssertion = listOf(
            TestResult("AT-HYB-05", "Parity", EvaluationOutcome.TESTED_PASS, 25L, "Normalized")
        )
        val localReport = engine.evaluateExecution(
            executionId = "exec-parity-1",
            adapterType = ExecutionAdapterType.LOCAL_TERMUX,
            evaluatorBlock = { testAssertion }
        )
        val remoteReport = engine.evaluateExecution(
            executionId = "exec-parity-1",
            adapterType = ExecutionAdapterType.REMOTE_CLOUD_WORKER,
            evaluatorBlock = { testAssertion }
        )

        assertEquals(localReport.schemaVersion, remoteReport.schemaVersion)
        assertEquals(localReport.outcome, remoteReport.outcome)
        assertEquals(localReport.testResults, remoteReport.testResults)
        assertEquals(localReport.measuredMetrics.keys, remoteReport.measuredMetrics.keys)
    }
}
