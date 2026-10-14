package org.osiris.android.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.osiris.android.bridge.proot.ProotCommandDispatcher
import org.osiris.android.bridge.termux.DualSubstrateHardwareDetector
import org.osiris.android.bridge.termux.HardwareNodeRole
import org.osiris.android.sdk.CapabilityGrant
import org.osiris.android.sdk.NetworkAccessPolicy
import org.osiris.android.sdk.OsirisContractException
import org.osiris.android.sdk.OsirisErrorCode

class RuntimeBridgeTest {

    @Test
    fun `DualSubstrateHardwareDetector assigns 300s to Node Alpha and 1800s to Node Beta`() {
        val alphaProfile = DualSubstrateHardwareDetector.detectProfile(
            overrideArch = "aarch64",
            overridePrefixEnv = "/data/data/com.termux/files/usr"
        )
        assertEquals(HardwareNodeRole.NODE_ALPHA_MOBILE_EDGE, alphaProfile.nodeRole)
        assertEquals(300.0, alphaProfile.temporalHorizonSeconds, 1e-6)
        assertEquals("NEON_SIMD", alphaProfile.simdAcceleration)

        val betaProfile = DualSubstrateHardwareDetector.detectProfile(
            overrideArch = "x86_64",
            overridePrefixEnv = null
        )
        assertEquals(HardwareNodeRole.NODE_BETA_COMPUTE_WORKSTATION, betaProfile.nodeRole)
        assertEquals(1800.0, betaProfile.temporalHorizonSeconds, 1e-6)
        assertEquals("AVX2_AVX_VNNI", betaProfile.simdAcceleration)
    }

    @Test
    fun `ProotCommandDispatcher rejects shell injection metacharacters`() {
        val dispatcher = ProotCommandDispatcher()
        val grant = CapabilityGrant(
            grantId = "grant-proot-1",
            taskId = "task-1",
            issuedAtEpochMs = 1000L,
            expiresAtEpochMs = 2000L,
            allowedDirectories = listOf("/workspace"),
            allowedCommands = listOf("python3"),
            networkPolicy = NetworkAccessPolicy.OFFLINE_ONLY,
            singleUse = true,
            revoked = false,
            signatureHex = "a".repeat(64)
        )

        val valid = dispatcher.prepareSanitizedCommand(
            executable = "python3",
            arguments = listOf("-m", "osiris_cli.osiris_repl"),
            workingDirectory = "/workspace",
            grant = grant
        )
        assertEquals("osiris::}{> ", valid.environment["OSIRIS_PROMPT"])
        assertEquals("1", valid.environment["PYTHONUNBUFFERED"])

        try {
            dispatcher.prepareSanitizedCommand(
                executable = "python3",
                arguments = listOf("-c", "import os; os.system('id'); && rm -rf /"),
                workingDirectory = "/workspace",
                grant = grant
            )
            fail("Expected ERR_UNSAFE_DNA_AST for shell metacharacter injection")
        } catch (e: OsirisContractException) {
            assertEquals(OsirisErrorCode.ERR_UNSAFE_DNA_AST, e.errorCode)
        }
    }
}
