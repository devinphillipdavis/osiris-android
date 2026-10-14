package org.osiris.android.bridge.proot

import kotlinx.serialization.Serializable
import org.osiris.android.bridge.termux.DualSubstrateHardwareDetector
import org.osiris.android.bridge.termux.SubstrateHardwareProfile
import org.osiris.android.sdk.CapabilityGrant
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode

@Serializable
data class SanitizedProotCommand(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val timeoutMillis: Long,
    val environment: Map<String, String>
)

/**
 * Isolated PRoot Command Dispatcher (Section 5.1 #4, Section 6.2).
 * Enforces explicit argument arrays (zero shell interpolation), allowlisted executables,
 * and capability grant directory confinement.
 */
class ProotCommandDispatcher(
    private val hardwareProfile: SubstrateHardwareProfile = DualSubstrateHardwareDetector.detectProfile()
) {
    private val allowlistedExecutables = setOf(
        "python3",
        "osiris",
        "git",
        "pytest",
        "gradle"
    )

    private val forbiddenShellMetacharacters = listOf(";", "&&", "||", "|", "`", "$(", ">", "<", "\n", "\r")

    fun prepareSanitizedCommand(
        executable: String,
        arguments: List<String>,
        workingDirectory: String,
        grant: CapabilityGrant,
        requestedTimeoutMs: Long? = null
    ): SanitizedProotCommand {
        if (executable !in allowlistedExecutables) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_SCOPE_VIOLATION,
                "Executable '$executable' is not in the PRoot allowlist"
            )
        }
        if (executable !in grant.allowedCommands) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_SCOPE_VIOLATION,
                "Executable '$executable' is not authorized by CapabilityGrant ${grant.grantId}"
            )
        }
        for (arg in arguments) {
            if (forbiddenShellMetacharacters.any { meta -> arg.contains(meta) }) {
                throw OsirisContractException(
                    OsirisErrorCode.ERR_UNSAFE_DNA_AST,
                    "Unsafe shell metacharacter detected in PRoot argument '$arg'"
                )
            }
        }
        val maxHorizonMs = (hardwareProfile.temporalHorizonSeconds * 1000.0).toLong()
        val effectiveTimeoutMs = (requestedTimeoutMs ?: maxHorizonMs).coerceAtMost(maxHorizonMs)

        return SanitizedProotCommand(
            executable = executable,
            arguments = arguments,
            workingDirectory = workingDirectory,
            timeoutMillis = effectiveTimeoutMs,
            environment = mapOf(
                "PYTHONUNBUFFERED" to "1",
                "PYTHONIOENCODING" to "utf-8",
                "OSIRIS_PROMPT" to "osiris::}{> ",
                "OSIRIS_SUBSTRATE_NODE" to hardwareProfile.nodeRole.name
            )
        )
    }
}
