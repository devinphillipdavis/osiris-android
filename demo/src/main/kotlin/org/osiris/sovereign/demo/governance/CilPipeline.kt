package org.osiris.sovereign.demo.governance

import org.osiris.android.sdk.CapabilityCryptoSigner

enum class CilVerdict {
    ACCEPTED,
    REJECTED
}

enum class IntentClass {
    READ,
    WRITE,
    DESTRUCTIVE,
    SLASH_COMMAND
}

data class CilEvaluationResult(
    val verdict: CilVerdict,
    val ruleApplied: String,
    val reason: String,
    val riskScore: Double,
    val transformedPayload: String,
    val allocatedTimeoutSec: Double,
    val allocatedMemoryMb: Int,
    val intentClass: IntentClass
)

object CilPipeline {

    private val DENYLIST_PATTERNS = listOf(
        "exec",
        "eval",
        "compile",
        "__import__",
        "os.system",
        "subprocess",
        "shell=True",
        "/etc/shadow",
        "/etc/passwd"
    )

    private val GOVERNANCE_IMMUTABLE_FILES = setOf(
        "policy.json",
        "cloudbuild.yaml",
        "ledger.jsonl"
    )

    /**
     * Deterministic port of CIL L0-L6 interposition pipeline.
     */
    fun evaluate(rawPayload: String, nowEpochMs: Long = System.currentTimeMillis()): CilEvaluationResult {
        // --- Layer 0: Syntax Sanity ---
        if (rawPayload.contains("\u0000")) {
            return CilEvaluationResult(
                verdict = CilVerdict.REJECTED,
                ruleApplied = "CIL_L0_NULL_BYTE",
                reason = "Payload rejected by CIL L0: null byte detected",
                riskScore = 1.0,
                transformedPayload = rawPayload,
                allocatedTimeoutSec = 0.0,
                allocatedMemoryMb = 0,
                intentClass = IntentClass.DESTRUCTIVE
            )
        }

        // Bracket balance check (strip prompt contract prefix 'osiris::}{>' if present)
        val codeToScan = if (rawPayload.startsWith("osiris::}{>")) {
            rawPayload.removePrefix("osiris::}{>").trim()
        } else {
            rawPayload
        }
        val brackets = mapOf('(' to ')', '{' to '}', '[' to ']')
        val stack = ArrayDeque<Char>()
        for (ch in codeToScan) {
            if (ch in brackets.keys) stack.addLast(ch)
            else if (ch in brackets.values) {
                if (stack.isEmpty() || brackets[stack.removeLast()] != ch) {
                    return CilEvaluationResult(
                        verdict = CilVerdict.REJECTED,
                        ruleApplied = "CIL_L0_MALFORMED_SYNTAX",
                        reason = "Payload rejected by CIL L0: unbalanced delimiter '$ch'",
                        riskScore = 0.9,
                        transformedPayload = rawPayload,
                        allocatedTimeoutSec = 0.0,
                        allocatedMemoryMb = 0,
                        intentClass = IntentClass.DESTRUCTIVE
                    )
                }
            }
        }
        if (stack.isNotEmpty()) {
            return CilEvaluationResult(
                verdict = CilVerdict.REJECTED,
                ruleApplied = "CIL_L0_MALFORMED_SYNTAX",
                reason = "Payload rejected by CIL L0: unclosed delimiter",
                riskScore = 0.9,
                transformedPayload = rawPayload,
                allocatedTimeoutSec = 0.0,
                allocatedMemoryMb = 0,
                intentClass = IntentClass.DESTRUCTIVE
            )
        }

        // --- Denylist Rules Engine ---
        for (pattern in DENYLIST_PATTERNS) {
            if (rawPayload.contains(pattern)) {
                return CilEvaluationResult(
                    verdict = CilVerdict.REJECTED,
                    ruleApplied = "CIL_L0_DENYLIST",
                    reason = "Denied by CIL L0 syntax & denylist rules: '$pattern' prohibited",
                    riskScore = 1.0,
                    transformedPayload = rawPayload,
                    allocatedTimeoutSec = 0.0,
                    allocatedMemoryMb = 0,
                    intentClass = IntentClass.DESTRUCTIVE
                )
            }
        }

        // --- Layer 1: Intent Classification ---
        val intentClass = when {
            rawPayload.startsWith("osiris::}{> /") -> IntentClass.SLASH_COMMAND
            rawPayload.contains("open(") && (rawPayload.contains("'w'") || rawPayload.contains("\"w\"")) -> IntentClass.WRITE
            rawPayload.contains("rm ") || rawPayload.contains("delete") -> IntentClass.DESTRUCTIVE
            else -> IntentClass.READ
        }

        // --- Layer 2: Path Binding (chroot to sandbox & immutable governance guard) ---
        for (imm in GOVERNANCE_IMMUTABLE_FILES) {
            if (rawPayload.contains(imm) && intentClass in setOf(IntentClass.WRITE, IntentClass.DESTRUCTIVE)) {
                return CilEvaluationResult(
                    verdict = CilVerdict.REJECTED,
                    ruleApplied = "CIL_L2_IMMUTABLE_ARTIFACT",
                    reason = "Denied by CIL L2/L3: $imm is an immutable governance artifact",
                    riskScore = 1.0,
                    transformedPayload = rawPayload,
                    allocatedTimeoutSec = 0.0,
                    allocatedMemoryMb = 0,
                    intentClass = intentClass
                )
            }
        }

        if (rawPayload.contains("/etc/") || rawPayload.contains("../") || rawPayload.contains("..\\")) {
            return CilEvaluationResult(
                verdict = CilVerdict.REJECTED,
                ruleApplied = "CIL_L2_PATH_CHROOT",
                reason = "Denied by CIL L2: path traversal outside demo sandbox boundary",
                riskScore = 0.95,
                transformedPayload = rawPayload,
                allocatedTimeoutSec = 0.0,
                allocatedMemoryMb = 0,
                intentClass = intentClass
            )
        }

        // --- Layer 3: Risk Scoring ---
        val riskScore = when (intentClass) {
            IntentClass.DESTRUCTIVE -> 0.90
            IntentClass.WRITE -> 0.60
            IntentClass.SLASH_COMMAND -> 0.10
            IntentClass.READ -> 0.05
        }

        // --- Layer 4: Quota Synthesis ---
        val timeoutSec = 300.0 // Alpha-class horizon
        val memoryMb = 256

        // --- Layer 5: Deterministic Rewrite Map ---
        var transformed = rawPayload
        if (transformed.contains("rm -rf")) {
            transformed = transformed.replace("rm -rf", "trash-put")
        }

        // --- Layer 6: Dispatch & Telemetry ---
        return CilEvaluationResult(
            verdict = CilVerdict.ACCEPTED,
            ruleApplied = "CIL_L6_DISPATCH",
            reason = "Validated by CIL L0-L5. Dispatched within granted capability envelope",
            riskScore = riskScore,
            transformedPayload = transformed,
            allocatedTimeoutSec = timeoutSec,
            allocatedMemoryMb = memoryMb,
            intentClass = intentClass
        )
    }
}
