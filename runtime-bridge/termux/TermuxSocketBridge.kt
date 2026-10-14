package org.osiris.android.bridge.termux

import kotlinx.serialization.Serializable
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode
import java.io.File

@Serializable
enum class HardwareNodeRole {
    NODE_ALPHA_MOBILE_EDGE,
    NODE_BETA_COMPUTE_WORKSTATION
}

@Serializable
data class SubstrateHardwareProfile(
    val nodeRole: HardwareNodeRole,
    val cpuArchitecture: String,
    val simdAcceleration: String,
    val temporalHorizonSeconds: Double,
    val runtimeSocketPath: String,
    val unbufferedUtf8Enforced: Boolean
)

/**
 * Hardware-aware Termux IPC Bridge (OSIRIS-ANDROID-SPEC-001 Section 5.1 #4).
 * Dynamically inspects runtime architecture and environment without hardcoding paths.
 */
object DualSubstrateHardwareDetector {

    fun detectProfile(
        overrideArch: String? = null,
        overridePrefixEnv: String? = System.getenv("PREFIX")
    ): SubstrateHardwareProfile {
        val rawArch = (overrideArch ?: System.getProperty("os.arch") ?: "unknown").lowercase()
        val isArm64OrTermux = rawArch.contains("aarch64") ||
            rawArch.contains("arm64") ||
            (!overridePrefixEnv.isNullOrBlank() && overridePrefixEnv.contains("com.termux"))

        val dynamicHome = System.getProperty("user.home") ?: File(".").absolutePath
        val socketDir = if (!overridePrefixEnv.isNullOrBlank()) {
            File(overridePrefixEnv, "var/run/osiris").path
        } else {
            File(dynamicHome, ".osiris/run").path
        }

        return if (isArm64OrTermux) {
            SubstrateHardwareProfile(
                nodeRole = HardwareNodeRole.NODE_ALPHA_MOBILE_EDGE,
                cpuArchitecture = rawArch,
                simdAcceleration = "NEON_SIMD",
                temporalHorizonSeconds = 300.0,
                runtimeSocketPath = "$socketDir/osiris_alpha_ipc.sock",
                unbufferedUtf8Enforced = true
            )
        } else {
            SubstrateHardwareProfile(
                nodeRole = HardwareNodeRole.NODE_BETA_COMPUTE_WORKSTATION,
                cpuArchitecture = rawArch,
                simdAcceleration = "AVX2_AVX_VNNI",
                temporalHorizonSeconds = 1800.0,
                runtimeSocketPath = "$socketDir/osiris_beta_ipc.sock",
                unbufferedUtf8Enforced = true
            )
        }
    }
}

class TermuxSocketBridge(
    val profile: SubstrateHardwareProfile = DualSubstrateHardwareDetector.detectProfile()
) {
    fun buildTopologyEnvironment(baseEnv: Map<String, String> = emptyMap()): Map<String, String> {
        val env = baseEnv.toMutableMap()
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTHONIOENCODING"] = "utf-8"
        env["OSIRIS_NODE_ROLE"] = profile.nodeRole.name
        env["OSIRIS_TEMPORAL_HORIZON_SEC"] = profile.temporalHorizonSeconds.toString()
        env["OSIRIS_SIMD_ACCEL"] = profile.simdAcceleration
        return env
    }

    fun validateHorizonMillis(requestedTimeoutMs: Long): Long {
        val maxAllowedMs = (profile.temporalHorizonSeconds * 1000.0).toLong()
        if (requestedTimeoutMs <= 0L || requestedTimeoutMs > maxAllowedMs) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_CAPABILITY_SCOPE_VIOLATION,
                "Requested timeout ${requestedTimeoutMs}ms exceeds ${profile.nodeRole} temporal horizon (${maxAllowedMs}ms)"
            )
        }
        return requestedTimeoutMs
    }
}
