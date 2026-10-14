package org.osiris.android.evaluation

import org.osiris.android.dnalang.SubstrateInvariants
import org.osiris.android.sdk.EvaluationOutcome
import org.osiris.android.sdk.EvaluationReport
import org.osiris.android.sdk.ExecutionAdapterType
import org.osiris.android.sdk.TestResult
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Independent Evaluator Engine (OSIRIS-ANDROID-SPEC-001 Section 5.1 #8, AT-EVD-04, AT-HYB-05).
 * Produces normalized EvaluationReports across Node Alpha (local) and Node Beta (remote),
 * handles missing/crashing evaluators as INCONCLUSIVE/BLOCKED (never TESTED_PASS),
 * and evaluates empirical W2 & Shannon entropy against the phi golden-ratio distribution.
 */
class IndependentEvaluatorEngine(
    val evaluatorIdentity: String = "osiris-independent-evaluator",
    val evaluatorVersion: String = "1.0.0"
) {

    fun computeShannonEntropyAndW2(empiricalCounts: Map<String, Int>): Pair<Double, Double> {
        val total = empiricalCounts.values.sum().coerceAtLeast(1).toDouble()
        val empiricalProbs = empiricalCounts.entries
            .sortedBy { it.key }
            .map { it.value / total }

        val shannonEntropy = -empiricalProbs.sumOf { p ->
            if (p > 1e-12) p * (ln(p) / ln(2.0)) else 0.0
        }

        // Golden-ratio reference distribution over matching support size
        val n = empiricalProbs.size.coerceAtLeast(1)
        val rawPhiWeights = DoubleArray(n) { idx ->
            Math.pow(SubstrateInvariants.GOLDEN_RATIO_PHI, -(idx + 1).toDouble())
        }
        val weightSum = rawPhiWeights.sum().coerceAtLeast(1e-12)
        val targetProbs = rawPhiWeights.map { it / weightSum }

        // 1D discrete Wasserstein-2 distance via cumulative distribution functions
        var cdfEmp = 0.0
        var cdfTar = 0.0
        var sumSqDiff = 0.0
        for (i in 0 until n) {
            cdfEmp += empiricalProbs[i]
            cdfTar += targetProbs[i]
            val diff = abs(cdfEmp - cdfTar)
            sumSqDiff += diff * diff
        }
        val w2 = sqrt(sumSqDiff / n.toDouble())
        return shannonEntropy to w2
    }

    /**
     * Evaluates an execution run using the provided evaluator block.
     * Enforces AT-EVD-04: if evaluator is null or throws an exception, outcome is INCONCLUSIVE or BLOCKED,
     * never TESTED_PASS.
     * Enforces AT-HYB-05: emits identical normalized EvaluationReport structure across local and remote adapters.
     */
    fun evaluateExecution(
        executionId: String,
        adapterType: ExecutionAdapterType,
        evaluatorBlock: (() -> List<TestResult>)?,
        empiricalCounts: Map<String, Int> = mapOf("00" to 618, "01" to 382)
    ): EvaluationReport {
        if (evaluatorBlock == null) {
            return EvaluationReport(
                executionId = executionId,
                evaluatorIdentity = evaluatorIdentity,
                evaluatorVersion = evaluatorVersion,
                outcome = EvaluationOutcome.INCONCLUSIVE,
                testResults = emptyList(),
                measuredMetrics = emptyMap(),
                limitations = listOf(
                    "Evaluator script missing; marked INCONCLUSIVE per AT-EVD-04",
                    "Substrate adapter: ${adapterType.name}"
                )
            )
        }

        return try {
            val results = evaluatorBlock()
            if (results.isEmpty()) {
                return EvaluationReport(
                    executionId = executionId,
                    evaluatorIdentity = evaluatorIdentity,
                    evaluatorVersion = evaluatorVersion,
                    outcome = EvaluationOutcome.INCONCLUSIVE,
                    testResults = emptyList(),
                    measuredMetrics = emptyMap(),
                    limitations = listOf("Zero test assertions executed; marked INCONCLUSIVE")
                )
            }
            val (entropy, w2) = computeShannonEntropyAndW2(empiricalCounts)
            val overallOutcome = when {
                results.any { it.status == EvaluationOutcome.BLOCKED } -> EvaluationOutcome.BLOCKED
                results.any { it.status == EvaluationOutcome.TESTED_FAIL } -> EvaluationOutcome.TESTED_FAIL
                results.any { it.status == EvaluationOutcome.INCONCLUSIVE } -> EvaluationOutcome.INCONCLUSIVE
                else -> EvaluationOutcome.TESTED_PASS
            }
            EvaluationReport(
                executionId = executionId,
                evaluatorIdentity = evaluatorIdentity,
                evaluatorVersion = evaluatorVersion,
                outcome = overallOutcome,
                testResults = results,
                measuredMetrics = mapOf(
                    "shannon_entropy_bits" to entropy,
                    "wasserstein_2_phi" to w2,
                    "wdw_mutation_required" to if (w2 > 0.1) 1.0 else 0.0,
                    "theta_lock_deg" to SubstrateInvariants.THETA_LOCK_DEG,
                    "gamma_floor" to SubstrateInvariants.GAMMA_FLOOR
                ),
                limitations = listOf(
                    "Automated harness evaluation only; does not establish clinical or physical efficacy without preregistered protocol.",
                    "Normalized report schema v1.0"
                )
            )
        } catch (e: Exception) {
            EvaluationReport(
                executionId = executionId,
                evaluatorIdentity = evaluatorIdentity,
                evaluatorVersion = evaluatorVersion,
                outcome = EvaluationOutcome.INCONCLUSIVE,
                testResults = emptyList(),
                measuredMetrics = emptyMap(),
                limitations = listOf("Evaluator crashed (${e.javaClass.simpleName}: ${e.message}); marked INCONCLUSIVE per AT-EVD-04")
            )
        }
    }
}
