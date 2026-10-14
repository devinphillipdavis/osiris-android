package org.osiris.android.dnalang

import kotlinx.serialization.Serializable
import org.osiris.android.sdk.CapabilityCryptoSigner
import org.osiris.android.sdk.IntentRequest
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode
import org.osiris.android.sdk.ValidationDiagnostic
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Physical substrate invariants mandated by OSIRIS Sovereign Execution Contract.
 */
object SubstrateInvariants {
    const val THETA_LOCK_DEG: Double = 51.843
    const val GAMMA_FLOOR: Double = 0.0920
    const val LAMBDA_PHI_KG: Double = 2.176435e-8
    const val GOLDEN_RATIO_PHI: Double = 1.618033988749895
    const val NODE_ALPHA_HORIZON_SEC: Double = 300.0
    const val NODE_BETA_HORIZON_SEC: Double = 1800.0
}

@Serializable
data class DnaGenomeAst(
    val genomeId: String,
    val sourceIntentDigest: String,
    val h8ManifoldVector: List<Double>,
    val thetaLockDeg: Double = SubstrateInvariants.THETA_LOCK_DEG,
    val gammaCoherence: Double,
    val lambdaPhiKg: Double = SubstrateInvariants.LAMBDA_PHI_KG,
    val nonSubstitutionVerified: Boolean,
    val canonicalDnaSource: String,
    val diagnostics: List<ValidationDiagnostic> = emptyList()
)

@Serializable
data class H8VectorProjection(
    val components: List<Double>,
    val norm: Double
)

@Serializable
data class DnaSourceValidationResult(
    val valid: Boolean,
    val genomeDigestSha256: String
)

/**
 * Z3bra Intent Lock: deterministically projects natural language or DNA-Lang intent into H_8.
 */
object Z3braIntentLock {
    fun vectorizeToH8(intentText: String): List<Double> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(intentText.trim().toByteArray(StandardCharsets.UTF_8))
        val raw = DoubleArray(8) { idx ->
            val b0 = digest[idx * 4].toInt() and 0xFF
            val b1 = digest[idx * 4 + 1].toInt() and 0xFF
            val b2 = digest[idx * 4 + 2].toInt() and 0xFF
            val b3 = digest[idx * 4 + 3].toInt() and 0xFF
            val unsignedVal = ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3).toUInt().toDouble()
            (unsignedVal / UInt.MAX_VALUE.toDouble()) * 2.0 - 1.0
        }
        val norm = sqrt(raw.sumOf { it * it }).coerceAtLeast(1e-12)
        return raw.map { it / norm }
    }

    fun vectorizeIntentToH8(intentText: String): H8VectorProjection {
        val vec = vectorizeToH8(intentText)
        val norm = sqrt(vec.sumOf { it * it })
        return H8VectorProjection(components = vec, norm = norm)
    }
}

/**
 * L0 AST Firewall & DNA-Lang Validator (OSIRIS-ANDROID-SPEC-001 Section 5.1 #5 & AT-SEC-04).
 * Never executes raw .dna content implicitly; validates syntax, coherence bounds, and argument safety.
 */
class DnaLangL0Validator {

    companion object {
        private val FORBIDDEN_SYSCALL_PATTERNS = listOf(
            "os.system",
            "subprocess.popen",
            "subprocess.call",
            "subprocess.run",
            "runtime.getruntime()",
            "processbuilder",
            "exec(",
            "eval(",
            "/bin/sh",
            "/bin/bash"
        )

        private val SHELL_METACHAR_PATTERNS = listOf(
            "..",
            ";",
            "|",
            "&&",
            "||",
            "`",
            "$(",
            "rm -rf",
            "/etc/",
            "/root/",
            "/proc/",
            "/sys/"
        )

        fun validateDnaSource(
            dnaContent: String,
            observedCoherence: Double,
            substitutionInvariantVerified: Boolean
        ): DnaSourceValidationResult {
            val lower = dnaContent.lowercase()
            for (syscall in FORBIDDEN_SYSCALL_PATTERNS) {
                if (lower.contains(syscall)) {
                    throw OsirisContractException(
                        OsirisErrorCode.ERR_UNSAFE_DNA_AST,
                        "Forbidden syscall '$syscall' in DNA source"
                    )
                }
            }
            if (!substitutionInvariantVerified || observedCoherence > SubstrateInvariants.GAMMA_FLOOR) {
                throw OsirisContractException(
                    OsirisErrorCode.ERR_AST_VALIDATION_FAILED,
                    "DNA source failed coherence floor or substitution invariant check"
                )
            }
            return DnaSourceValidationResult(
                valid = true,
                genomeDigestSha256 = CapabilityCryptoSigner.sha256Hex(dnaContent)
            )
        }
    }

    fun validateParametersAndPaths(request: IntentRequest): List<ValidationDiagnostic> {
        val diagnostics = mutableListOf<ValidationDiagnostic>()

        // Check parameters for path traversal and shell metacharacter injection (AT-SEC-04)
        for ((key, value) in request.parameters) {
            val combined = "$key=$value".lowercase()
            for (forbidden in SHELL_METACHAR_PATTERNS) {
                if (combined.contains(forbidden)) {
                    throw OsirisContractException(
                        OsirisErrorCode.ERR_MALFORMED_ARGUMENT,
                        "Parameter '$key' contains forbidden traversal or injection token '$forbidden'"
                    )
                }
            }
            for (syscall in FORBIDDEN_SYSCALL_PATTERNS) {
                if (combined.contains(syscall)) {
                    throw OsirisContractException(
                        OsirisErrorCode.ERR_AST_VALIDATION_FAILED,
                        "Parameter '$key' contains forbidden L0 syscall pattern '$syscall'"
                    )
                }
            }
        }

        // Check context attachment paths
        for (attachment in request.contextAttachments) {
            val pathLower = attachment.pathOrUri.lowercase()
            if (pathLower.contains("..") || pathLower.startsWith("/etc") || pathLower.startsWith("/root")) {
                throw OsirisContractException(
                    OsirisErrorCode.ERR_MALFORMED_ARGUMENT,
                    "Context attachment path '${attachment.pathOrUri}' violates sandbox path policy"
                )
            }
        }

        // Check natural language / DNA task for L0 AST syscall leaks or shell injection
        val taskLower = request.naturalLanguageTask.lowercase()
        for (syscall in FORBIDDEN_SYSCALL_PATTERNS) {
            if (taskLower.contains(syscall)) {
                throw OsirisContractException(
                    OsirisErrorCode.ERR_AST_VALIDATION_FAILED,
                    "L0 AST Firewall blocked system call leak pattern '$syscall'"
                )
            }
        }
        if (taskLower.contains("..") || taskLower.contains("; rm -rf") || taskLower.contains("| sh")) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_MALFORMED_ARGUMENT,
                "L0 AST Firewall blocked shell traversal/injection in task body"
            )
        }

        // Verify optional substrate parameter overrides adhere to physical invariants
        request.parameters["gamma_floor"]?.toDoubleOrNull()?.let { gamma ->
            if (gamma < SubstrateInvariants.GAMMA_FLOOR) {
                throw OsirisContractException(
                    OsirisErrorCode.ERR_AST_VALIDATION_FAILED,
                    "Coherence gamma ($gamma) falls below mandatory floor (${SubstrateInvariants.GAMMA_FLOOR})"
                )
            }
        }
        request.parameters["theta_lock"]?.toDoubleOrNull()?.let { theta ->
            if (abs(theta - SubstrateInvariants.THETA_LOCK_DEG) > 1e-3) {
                diagnostics.add(
                    ValidationDiagnostic(
                        code = "WARN_THETA_DEVIATION",
                        severity = "WARNING",
                        message = "Requested theta ($theta) clamped to lock invariant ${SubstrateInvariants.THETA_LOCK_DEG}",
                        fieldPath = "parameters.theta_lock"
                    )
                )
            }
        }

        return diagnostics
    }

    fun compileIntentToGenome(request: IntentRequest): DnaGenomeAst {
        val diagnostics = validateParametersAndPaths(request)
        val digest = CapabilityCryptoSigner.sha256Hex(
            "${request.requestId}|${request.idempotencyKey}|${request.naturalLanguageTask}"
        )
        val h8 = Z3braIntentLock.vectorizeToH8(request.naturalLanguageTask)
        val gamma = request.parameters["gamma_floor"]?.toDoubleOrNull()
            ?: (SubstrateInvariants.GAMMA_FLOOR + 0.05)

        val canonicalDna = buildString {
            appendLine("genome::osiris_intent_${digest.take(12)} {")
            appendLine("  theta_lock: ${SubstrateInvariants.THETA_LOCK_DEG};")
            appendLine("  gamma_floor: ${SubstrateInvariants.GAMMA_FLOOR};")
            appendLine("  lambda_phi: ${SubstrateInvariants.LAMBDA_PHI_KG};")
            appendLine("  mode: \"${request.requestedMode}\";")
            appendLine("  intent_sha256: \"$digest\";")
            append("}")
        }

        return DnaGenomeAst(
            genomeId = "dna-${digest.take(16)}",
            sourceIntentDigest = digest,
            h8ManifoldVector = h8,
            thetaLockDeg = SubstrateInvariants.THETA_LOCK_DEG,
            gammaCoherence = gamma,
            lambdaPhiKg = SubstrateInvariants.LAMBDA_PHI_KG,
            nonSubstitutionVerified = true,
            canonicalDnaSource = canonicalDna,
            diagnostics = diagnostics
        )
    }
}
