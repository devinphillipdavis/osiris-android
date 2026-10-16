package org.osiris.sovereign.demo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import org.osiris.sovereign.demo.governance.CilPipeline
import org.osiris.sovereign.demo.ledger.SovereignLedgerEngine
import org.osiris.sovereign.demo.substrate.DemoFacts
import org.osiris.sovereign.demo.substrate.SubstrateDetector
import java.io.File

class SovereignConsoleActivity : ComponentActivity() {

    private lateinit var ledgerEngine: SovereignLedgerEngine
    private lateinit var demoFacts: DemoFacts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ledgerFile = File(filesDir, "ledger.jsonl")
        ledgerEngine = SovereignLedgerEngine(ledgerFile)

        val factsJson = assets.open("demo_facts.json").bufferedReader().use { it.readText() }
        demoFacts = SubstrateDetector.loadFacts(factsJson)

        setContent {
            SovereignConsoleApp(ledgerEngine, demoFacts)
        }
    }
}

val TerminalBg = Color(0xFF0A0E14)
val TerminalGreen = Color(0xFF00FF66)
val TerminalAmber = Color(0xFFFFB000)
val TerminalRed = Color(0xFFFF3333)
val TerminalCard = Color(0xFF1B222D)

@Composable
fun SovereignConsoleApp(ledgerEngine: SovereignLedgerEngine, facts: DemoFacts) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("REPL", "INTENT LAB", "LEDGER", "SUBSTRATE", "ABOUT")

    Column(modifier = Modifier.fillMaxSize().background(TerminalBg)) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = TerminalCard,
            contentColor = TerminalGreen
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (selectedTab) {
                0 -> ReplScreen(facts)
                1 -> IntentLabScreen(ledgerEngine)
                2 -> LedgerViewerScreen(ledgerEngine)
                3 -> SubstratePanelScreen(facts)
                4 -> AboutScreen(facts)
            }
        }
    }
}

@Composable
fun ReplScreen(facts: DemoFacts) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("OSIRIS Sovereign Console (Demonstration)\nSpeaking gate is CLOSED (no conversational output).\nEnter proposal or intent below:\n") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = output,
            color = TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(facts.prompt_contract + " ", color = TerminalAmber, fontFamily = FontFamily.Monospace)
            BasicTextFieldValue(input) { input = it }
        }
    }
}

@Composable
fun BasicTextFieldValue(text: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onValueChange,
        textStyle = androidx.compose.ui.text.TextStyle(color = TerminalGreen, fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun IntentLabScreen(ledgerEngine: SovereignLedgerEngine) {
    var logText by remember { mutableStateOf("Intent Lab: Canned intent corpus ready.\nPress 'Run Intent 1' to begin.") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = logText,
            color = TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Button(
                onClick = {
                    val p = "exec('import os; os.system(\"cat /etc/shadow\")')"
                    val res = CilPipeline.evaluate(p)
                    ledgerEngine.appendEntry("intent_01", res.verdict.name, res.ruleApplied, p)
                    logText += "\n\n[INTENT 01: Adversarial]\nVerdict: ${res.verdict}\nRule: ${res.ruleApplied}\nReason: ${res.reason}"
                },
                colors = ButtonDefaults.buttonColors(containerColor = TerminalCard)
            ) {
                Text("Adversarial", color = TerminalAmber, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val p = "osiris::}{> /dna XY4.decouple(fidelity=0.99)"
                    val res = CilPipeline.evaluate(p)
                    ledgerEngine.appendEntry("intent_04", res.verdict.name, res.ruleApplied, p)
                    logText += "\n\n[INTENT 04: Valid XY4]\nVerdict: ${res.verdict}\nRule: ${res.ruleApplied}\nArtifact: genome-xy4-decouple.dna"
                },
                colors = ButtonDefaults.buttonColors(containerColor = TerminalCard)
            ) {
                Text("Valid XY4", color = TerminalGreen, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun LedgerViewerScreen(ledgerEngine: SovereignLedgerEngine) {
    var verification by remember { mutableStateOf(ledgerEngine.verifyIntegrity()) }
    val entries = remember { ledgerEngine.getEntries() }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalCard)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("STATUS: ${verification.status}", color = if (verification.status.name == "VERIFIED") TerminalGreen else TerminalRed, fontFamily = FontFamily.Monospace)
                Text("ENTRIES: ${verification.entryCount}", color = Color.White, fontFamily = FontFamily.Monospace)
                Text("MERKLE ROOT: ${verification.merkleRoot}", color = TerminalAmber, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(entries) { entry ->
                Text(
                    text = "#${entry.sequence} [${entry.verdict}] ${entry.ruleApplied} (hash: ${entry.eventHash.take(12)}...)",
                    color = if (entry.verdict == "ACCEPTED") TerminalGreen else TerminalAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        Button(
            onClick = { verification = ledgerEngine.verifyIntegrity() },
            colors = ButtonDefaults.buttonColors(containerColor = TerminalCard),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Verify Merkle Chain", color = TerminalGreen, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun SubstratePanelScreen(facts: DemoFacts) {
    val telemetry = remember { SubstrateDetector.inspectSubstrate(facts) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("SUBSTRATE TELEMETRY", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TerminalCard)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Node: ${telemetry.nodeClass}", color = Color.White, fontFamily = FontFamily.Monospace)
                Text("Arch: ${telemetry.architecture}", color = Color.White, fontFamily = FontFamily.Monospace)
                Text("Temporal Lock: ${telemetry.temporalLockSec}s (Enforced)", color = TerminalAmber, fontFamily = FontFamily.Monospace)
                Text("Thermal: ${telemetry.thermalState}", color = TerminalGreen, fontFamily = FontFamily.Monospace)
                Text("RAM Ceiling: ${telemetry.ramBudgetMb} MB", color = Color.White, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                Text("SPEAKING GATE: ${telemetry.speakingGateStatus}", color = TerminalRed, fontFamily = FontFamily.Monospace)
                Text("Metrics: ${telemetry.speakingGateMetrics}", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("Ceiling: ${telemetry.fidelityCeiling}", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("ARCHITECTURAL PLANES", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        facts.architecture_planes.forEach { plane ->
            Text("• $plane [ACTIVE]", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        facts.proposed_modules.forEach { mod ->
            Text("• $mod [PROPOSED]", color = TerminalAmber, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
fun AboutScreen(facts: DemoFacts) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("ABOUT OSIRIS SOVEREIGN CONSOLE", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TerminalCard)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("App: OSIRIS Sovereign Console (Demonstration)", color = Color.White, fontFamily = FontFamily.Monospace)
                Text("Package: ${facts.package_name}", color = Color.White, fontFamily = FontFamily.Monospace)
                Text("Built for: Pete Gordon / Google Developer Groups", color = TerminalAmber, fontFamily = FontFamily.Monospace)
                Text("Commit: 4c015ed (HEAD -> main)", color = Color.White, fontFamily = FontFamily.Monospace)
                Text("License: ${facts.license}", color = TerminalGreen, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                Text("CLAIMS FIREWALL STATEMENT:", color = TerminalAmber, fontFamily = FontFamily.Monospace)
                Text(
                    "This demonstration app proves deterministic execution governance, fail-closed interposition (CIL L0-L6), and cryptographic evidence chaining. It contains zero generative voice surfaces and zero unmediated model authority.",
                    color = Color.LightGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}
