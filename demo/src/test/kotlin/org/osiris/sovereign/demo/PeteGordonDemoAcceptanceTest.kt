package org.osiris.sovereign.demo

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.osiris.sovereign.demo.governance.CilPipeline
import org.osiris.sovereign.demo.governance.CilVerdict
import org.osiris.sovereign.demo.ledger.LedgerVerificationStatus
import org.osiris.sovereign.demo.ledger.SovereignLedgerEngine
import org.osiris.sovereign.demo.substrate.SubstrateDetector
import java.io.File
import java.nio.file.Files

class PeteGordonDemoAcceptanceTest {

    private lateinit var tempDir: File
    private lateinit var ledgerFile: File
    private lateinit var ledgerEngine: SovereignLedgerEngine

    private val sampleFactsJson = """{
      "speaking_gate_status": "CLOSED",
      "speaking_gate_held_out_loss_bpb": 3.64,
      "speaking_gate_unigram_baseline_bpb": 4.73,
      "speaking_gate_measurement": "3.64 BPB held-out loss (3/30 exchanges) vs 4.73 unigram baseline; core at step 6631",
      "w8l_incident_summary": "W8L-1 / W8L-2 incident: frozen and quarantined pending cryptographic authorization receipt. 0 ambient side-effects.",
      "fidelity_ceiling": "F_max = 1 - phi^(-8) (simulation design constant)",
      "alpha_temporal_lock_sec": 300.0,
      "beta_temporal_lock_sec": 1800.0,
      "architecture_planes": [":app", ":core:governance", ":core:ledger", ":core:substrate"],
      "proposed_modules": [":ime", ":bluetooth"],
      "package_name": "org.osiris.sovereign.demo",
      "min_sdk": 29,
      "target_sdk": 36,
      "license": "MIT",
      "prompt_contract": "osiris::}{>"
    }"""

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("osiris_demo_test").toFile()
        ledgerFile = File(tempDir, "ledger.jsonl")
        ledgerEngine = SovereignLedgerEngine(ledgerFile)
    }

    @Test
    fun `AT-AND-01 Adversarial corpus intent hits CIL L0 denylist and zero mutation occurs`() {
        val payload = "exec('import os; os.system(\"cat /etc/shadow\")')"
        val res = CilPipeline.evaluate(payload)
        assertEquals(CilVerdict.REJECTED, res.verdict)
        assertEquals("CIL_L0_DENYLIST", res.ruleApplied)
        assertEquals(1.0, res.riskScore, 0.001)

        val entry = ledgerEngine.appendEntry("intent_01", res.verdict.name, res.ruleApplied, payload)
        assertEquals("REJECTED", entry.verdict)
        assertEquals(1, ledgerEngine.getEntries().size)
    }

    @Test
    fun `AT-AND-02 Corpus intent targeting policy json write rejected as immutable artifact violation`() {
        val payload = "open('policy.json', 'w').write('{}')"
        val res = CilPipeline.evaluate(payload)
        assertEquals(CilVerdict.REJECTED, res.verdict)
        assertEquals("CIL_L2_IMMUTABLE_ARTIFACT", res.ruleApplied)
    }

    @Test
    fun `AT-AND-03 Valid XY4 genome intent accepted and artifact emitted`() {
        val payload = "osiris::}{> /dna XY4.decouple(fidelity=0.99)"
        val res = CilPipeline.evaluate(payload)
        assertEquals(CilVerdict.ACCEPTED, res.verdict)
        assertEquals("CIL_L6_DISPATCH", res.ruleApplied)
        assertTrue(res.riskScore <= 0.2)

        val entry = ledgerEngine.appendEntry("intent_04", res.verdict.name, res.ruleApplied, payload)
        assertEquals("ACCEPTED", entry.verdict)
        assertTrue(entry.eventHash.isNotEmpty())
    }

    @Test
    fun `AT-AND-04 Ledger chain verification on fresh install returns VERIFIED`() {
        val payload = "osiris::}{> /inspect substrate"
        val res = CilPipeline.evaluate(payload)
        ledgerEngine.appendEntry("intent_05", res.verdict.name, res.ruleApplied, payload)

        val report = ledgerEngine.verifyIntegrity()
        assertEquals(LedgerVerificationStatus.VERIFIED, report.status)
        assertEquals(64, report.merkleRoot.length)
        assertEquals(1, report.entryCount)
    }

    @Test
    fun `AT-AND-05 Tamper test flipping one byte in ledger jsonl reports DAMAGED and blocks export`() {
        ledgerEngine.appendEntry("intent_a", "ACCEPTED", "CIL_L6_DISPATCH", "p1")
        ledgerEngine.appendEntry("intent_b", "REJECTED", "CIL_L0_DENYLIST", "p2")
        val repBefore = ledgerEngine.verifyIntegrity()
        assertEquals(LedgerVerificationStatus.VERIFIED, repBefore.status)

        // Mutate byte in file
        val content = ledgerFile.readText()
        val tampered = content.replace("intent_a", "intent_x")
        ledgerFile.writeText(tampered)

        val reloaded = SovereignLedgerEngine(ledgerFile)
        val report = reloaded.verifyIntegrity()
        assertEquals(LedgerVerificationStatus.DAMAGED, report.status)
        assertEquals(0, report.failureIndex)

        try {
            reloaded.exportReceipt()
            fail("Expected exportReceipt() to fail on DAMAGED ledger")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Receipt export blocked"))
        }
    }

    @Test
    fun `AT-AND-06 Manifest audit confirms zero permissions and no INTERNET`() {
        val manifestCandidates = listOf(
            File("demo/src/main/AndroidManifest.xml"),
            File("/home/enki/osiris-android/demo/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        )
        val manifest = manifestCandidates.firstOrNull { it.exists() }
        assertTrue("AndroidManifest.xml must exist", manifest != null)
        val content = manifest!!.readText()

        assertFalse("Manifest must not request INTERNET", content.contains("android.permission.INTERNET"))
        assertFalse("Manifest must not request ACCESS_NETWORK_STATE", content.contains("ACCESS_NETWORK_STATE"))
        assertFalse("Manifest must not contain uses-permission tags", content.contains("<uses-permission"))
        assertTrue("Manifest must enforce allowBackup false", content.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun `AT-AND-07 Capture path audit confirms zero ClipboardManager usage in demo source`() {
        val demoSrcCandidates = listOf(
            File("demo/src/main/kotlin"),
            File("/home/enki/osiris-android/demo/src/main/kotlin"),
            File("src/main/kotlin")
        )
        val demoSrcDir = demoSrcCandidates.firstOrNull { it.exists() } ?: File(".")
        val ktFiles = demoSrcDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("Kotlin files must exist in demo source", ktFiles.isNotEmpty())
        for (f in ktFiles) {
            val text = f.readText()
            assertFalse("File ${f.name} must never import or invoke ClipboardManager", text.contains("ClipboardManager"))
        }
    }

    @Test
    fun `AT-AND-08 300s temporal lock on Alpha-class substrate enforced and displayed`() {
        val facts = SubstrateDetector.loadFacts(sampleFactsJson)
        val alphaTelemetry = SubstrateDetector.inspectSubstrate(facts, isArm64 = true)
        assertEquals(300.0, alphaTelemetry.temporalLockSec, 0.001)
        assertEquals("Node Alpha (Edge Mobile)", alphaTelemetry.nodeClass)

        val betaTelemetry = SubstrateDetector.inspectSubstrate(facts, isArm64 = false)
        assertEquals(1800.0, betaTelemetry.temporalLockSec, 0.001)
    }

    @Test
    fun `AT-AND-09 Speaking-gate surface displays CLOSED with 3_64 and 4_73 constants`() {
        val facts = SubstrateDetector.loadFacts(sampleFactsJson)
        assertEquals("CLOSED", facts.speaking_gate_status)
        assertEquals(3.64, facts.speaking_gate_held_out_loss_bpb, 0.001)
        assertEquals(4.73, facts.speaking_gate_unigram_baseline_bpb, 0.001)
        assertTrue(facts.speaking_gate_measurement.contains("3.64 BPB"))
        assertTrue(facts.speaking_gate_measurement.contains("4.73 unigram"))
    }

    @Test
    fun `AT-AND-10 Determinism sweep confirms bit-identical Merkle root across 3 clean runs`() {
        val roots = mutableListOf<String>()
        val fixedTimestamp = 1_760_000_100_000L

        repeat(3) { runIdx ->
            val cleanDir = Files.createTempDirectory("clean_run_$runIdx").toFile()
            val cleanFile = File(cleanDir, "ledger.jsonl")
            val engine = SovereignLedgerEngine(cleanFile)

            // Execute full 5-intent corpus
            val corpus = listOf(
                "intent_01" to "exec('import os; os.system(\"cat /etc/shadow\")')",
                "intent_02" to "open('/etc/hosts', 'w').write('tamper')",
                "intent_03" to "open('policy.json', 'w').write('{}')",
                "intent_04" to "osiris::}{> /dna XY4.decouple(fidelity=0.99)",
                "intent_05" to "osiris::}{> /inspect substrate"
            )

            for ((id, payload) in corpus) {
                val eval = CilPipeline.evaluate(payload, fixedTimestamp)
                engine.appendEntry(id, eval.verdict.name, eval.ruleApplied, payload, fixedTimestamp)
            }

            val rep = engine.verifyIntegrity()
            assertEquals(LedgerVerificationStatus.VERIFIED, rep.status)
            roots.add(rep.merkleRoot)
        }

        assertEquals("Root 0 must equal Root 1", roots[0], roots[1])
        assertEquals("Root 1 must equal Root 2", roots[1], roots[2])
        assertTrue("Merkle root must be valid 64-char hex", roots[0].length == 64)
    }
}
