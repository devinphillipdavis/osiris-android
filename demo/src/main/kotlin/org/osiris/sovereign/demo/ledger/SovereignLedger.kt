package org.osiris.sovereign.demo.ledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.osiris.android.sdk.CapabilityCryptoSigner
import java.io.File
import java.nio.charset.StandardCharsets

@Serializable
data class SovereignLedgerEntry(
    val sequence: Long,
    val timestampEpochMs: Long,
    val intentId: String,
    val verdict: String,
    val ruleApplied: String,
    val payloadSha256: String,
    val previousEventHash: String,
    val eventHash: String
)

enum class LedgerVerificationStatus {
    VERIFIED,
    DAMAGED,
    FORKED
}

data class LedgerVerificationReport(
    val status: LedgerVerificationStatus,
    val failureIndex: Int? = null,
    val entryCount: Int,
    val merkleRoot: String,
    val details: String
)

data class SovereignReceipt(
    val chainHeadHash: String,
    val merkleRoot: String,
    val entryCount: Int,
    val deviceModel: String,
    val buildSha: String,
    val status: String,
    val timestampEpochMs: Long
)

class SovereignLedgerEngine(private val ledgerFile: File) {

    private val lock = Any()
    private val entries = mutableListOf<SovereignLedgerEntry>()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            entries.clear()
            if (ledgerFile.exists()) {
                val lines = ledgerFile.readLines(StandardCharsets.UTF_8)
                for (line in lines) {
                    if (line.isBlank()) continue
                    try {
                        val entry = Json.decodeFromString<SovereignLedgerEntry>(line.trim())
                        entries.add(entry)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun appendEntry(
        intentId: String,
        verdict: String,
        ruleApplied: String,
        payload: String,
        timestampEpochMs: Long = System.currentTimeMillis()
    ): SovereignLedgerEntry {
        synchronized(lock) {
            val seq = (entries.size + 1).toLong()
            val prevHash = entries.lastOrNull()?.eventHash ?: "0".repeat(64)
            val payloadHash = CapabilityCryptoSigner.sha256Hex(payload)
            val canonicalString = "{\"seq\":$seq,\"ts\":$timestampEpochMs,\"id\":\"$intentId\",\"verdict\":\"$verdict\",\"rule\":\"$ruleApplied\",\"payloadHash\":\"$payloadHash\",\"prevHash\":\"$prevHash\"}"
            val eventHash = CapabilityCryptoSigner.sha256Hex(canonicalString)

            val entry = SovereignLedgerEntry(
                sequence = seq,
                timestampEpochMs = timestampEpochMs,
                intentId = intentId,
                verdict = verdict,
                ruleApplied = ruleApplied,
                payloadSha256 = payloadHash,
                previousEventHash = prevHash,
                eventHash = eventHash
            )
            entries.add(entry)
            val jsonLine = Json.encodeToString(SovereignLedgerEntry.serializer(), entry) + "\n"
            ledgerFile.appendText(jsonLine, StandardCharsets.UTF_8)
            return entry
        }
    }

    fun computeMerkleRoot(): String {
        synchronized(lock) {
            if (entries.isEmpty()) return "0".repeat(64)
            var currentLayer = entries.map { it.eventHash }
            while (currentLayer.size > 1) {
                val nextLayer = mutableListOf<String>()
                var i = 0
                while (i < currentLayer.size) {
                    val left = currentLayer[i]
                    val right = if (i + 1 < currentLayer.size) currentLayer[i + 1] else left
                    nextLayer.add(CapabilityCryptoSigner.sha256Hex(left + right))
                    i += 2
                }
                currentLayer = nextLayer
            }
            return currentLayer.first()
        }
    }

    fun verifyIntegrity(): LedgerVerificationReport {
        synchronized(lock) {
            if (entries.isEmpty()) {
                return LedgerVerificationReport(
                    status = LedgerVerificationStatus.VERIFIED,
                    entryCount = 0,
                    merkleRoot = "0".repeat(64),
                    details = "Ledger is empty and clean"
                )
            }
            var prevHash = "0".repeat(64)
            for ((index, entry) in entries.withIndex()) {
                val seq = (index + 1).toLong()
                if (entry.sequence != seq) {
                    return LedgerVerificationReport(
                        status = LedgerVerificationStatus.FORKED,
                        failureIndex = index,
                        entryCount = entries.size,
                        merkleRoot = computeMerkleRoot(),
                        details = "Sequence discontinuity at index $index: expected $seq but got ${entry.sequence}"
                    )
                }
                if (entry.previousEventHash != prevHash) {
                    return LedgerVerificationReport(
                        status = LedgerVerificationStatus.FORKED,
                        failureIndex = index,
                        entryCount = entries.size,
                        merkleRoot = computeMerkleRoot(),
                        details = "Previous hash mismatch at index $index"
                    )
                }
                val canonicalString = "{\"seq\":$seq,\"ts\":${entry.timestampEpochMs},\"id\":\"${entry.intentId}\",\"verdict\":\"${entry.verdict}\",\"rule\":\"${entry.ruleApplied}\",\"payloadHash\":\"${entry.payloadSha256}\",\"prevHash\":\"$prevHash\"}"
                val expectedEventHash = CapabilityCryptoSigner.sha256Hex(canonicalString)
                if (entry.eventHash != expectedEventHash) {
                    return LedgerVerificationReport(
                        status = LedgerVerificationStatus.DAMAGED,
                        failureIndex = index,
                        entryCount = entries.size,
                        merkleRoot = computeMerkleRoot(),
                        details = "Cryptographic tampering detected at entry $index: hash mismatch"
                    )
                }
                prevHash = entry.eventHash
            }
            return LedgerVerificationReport(
                status = LedgerVerificationStatus.VERIFIED,
                entryCount = entries.size,
                merkleRoot = computeMerkleRoot(),
                details = "All ${entries.size} entries verified unbroken"
            )
        }
    }

    fun exportReceipt(deviceModel: String = "Google Pixel 10 Pro Fold (rango)", buildSha: String = "4c015ed"): SovereignReceipt {
        val verification = verifyIntegrity()
        if (verification.status != LedgerVerificationStatus.VERIFIED) {
            throw IllegalStateException("Receipt export blocked: ledger state is ${verification.status} at index ${verification.failureIndex}")
        }
        val headHash = entries.lastOrNull()?.eventHash ?: "0".repeat(64)
        return SovereignReceipt(
            chainHeadHash = headHash,
            merkleRoot = verification.merkleRoot,
            entryCount = entries.size,
            deviceModel = deviceModel,
            buildSha = buildSha,
            status = "VERIFIED",
            timestampEpochMs = System.currentTimeMillis()
        )
    }

    fun getEntries(): List<SovereignLedgerEntry> = synchronized(lock) { entries.toList() }
}
