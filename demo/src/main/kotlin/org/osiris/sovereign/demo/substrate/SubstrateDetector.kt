package org.osiris.sovereign.demo.substrate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DemoFacts(
    val speaking_gate_status: String,
    val speaking_gate_held_out_loss_bpb: Double,
    val speaking_gate_unigram_baseline_bpb: Double,
    val speaking_gate_measurement: String,
    val w8l_incident_summary: String,
    val fidelity_ceiling: String,
    val alpha_temporal_lock_sec: Double,
    val beta_temporal_lock_sec: Double,
    val architecture_planes: List<String>,
    val proposed_modules: List<String>,
    val package_name: String,
    val min_sdk: Int,
    val target_sdk: Int,
    val license: String,
    val prompt_contract: String
)

data class SubstrateTelemetry(
    val nodeClass: String,
    val architecture: String,
    val temporalLockSec: Double,
    val thermalState: String,
    val ramBudgetMb: Int,
    val speakingGateStatus: String,
    val speakingGateMetrics: String,
    val w8lSummary: String,
    val fidelityCeiling: String
)

object SubstrateDetector {

    fun loadFacts(factsJson: String): DemoFacts {
        return Json.decodeFromString(DemoFacts.serializer(), factsJson)
    }

    fun inspectSubstrate(facts: DemoFacts, isArm64: Boolean = true): SubstrateTelemetry {
        val nodeClass = if (isArm64) "Node Alpha (Edge Mobile)" else "Node Beta (Workstation)"
        val timeoutSec = if (isArm64) facts.alpha_temporal_lock_sec else facts.beta_temporal_lock_sec
        return SubstrateTelemetry(
            nodeClass = nodeClass,
            architecture = if (isArm64) "aarch64 (ARM64 Google Tensor G5)" else "x86_64",
            temporalLockSec = timeoutSec,
            thermalState = "NORMAL (28.4°C)",
            ramBudgetMb = 256,
            speakingGateStatus = facts.speaking_gate_status,
            speakingGateMetrics = facts.speaking_gate_measurement,
            w8lSummary = facts.w8l_incident_summary,
            fidelityCeiling = facts.fidelity_ceiling
        )
    }
}
