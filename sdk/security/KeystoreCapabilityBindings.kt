package org.osiris.android.sdk

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic digest and capability signature helper (ADR-AND-004 / Section 7.2).
 * Never embeds static secrets; keys are generated ephemerally or backed by Android Keystore at runtime.
 */
object CapabilityCryptoSigner {
    fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(StandardCharsets.UTF_8))

    fun hmacSha256Hex(secretKeyBytes: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKeyBytes, "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }
}
