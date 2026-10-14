# OSIRIS Google Pixel Fold Android Platform — Acceptance Test Traceability Matrix

**Document Reference:** `OSIRIS-ANDROID-SPEC-001` (Section 12, Suites A–G)

| Suite | Test IDs | Verification Module & Test Class |
|---|---|---|
| **Suite A: Build & SDK Contracts** | `AT-BLD-01..02`, `AT-API-01..04` | `:sdk` (`SdkContractTest`), `:execution` (`HybridExecutionTest`), `devops/signing/verify_provenance.py` |
| **Suite B: Pixel Fold Adaptive UI** | `AT-UI-01..06` | `:app` (`PixelFoldWorkspaceTest`) |
| **Suite C: OSIRIS Smart Keyboard** | `AT-IME-01..07` | `:keyboard` (`SmartKeyboardTest`) |
| **Suite D: Security & Governance** | `AT-SEC-01..08` | `:governance` (`GovernanceSecurityTest`), `:evidence` (`EvidencePromotionTest`), `:app` (`PixelFoldWorkspaceTest`), `devops/signing/verify_provenance.py` |
| **Suite E: Hybrid Execution** | `AT-HYB-01..06` | `:execution` (`HybridExecutionTest`), `:evaluation` (`EvaluationEngineTest`), `:runtime-bridge` (`RuntimeBridgeTest`) |
| **Suite F: Evidence & Release Gates** | `AT-EVD-01..04`, `AT-REL-01..04` | `:evidence` (`EvidencePromotionTest`), `:evaluation` (`EvaluationEngineTest`) |
| **Suite G: Performance & Thermal** | `AT-PERF-01..05` | `:app` (`PixelFoldWorkspaceTest`), `:keyboard` (`SmartKeyboardTest`), `:execution` (`HybridExecutionTest`) |
