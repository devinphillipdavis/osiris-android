# OSIRIS Google Pixel Fold Android Platform — Architecture

**Document Reference:** `OSIRIS-ANDROID-SPEC-001`

## 1. Multi-Module Topology

The repository implements the 9-module architecture specified in Section 11 of `OSIRIS-ANDROID-SPEC-001`:

1. **`:sdk`** (`sdk/`): Versioned data contracts (`OsirisTaskIntent`, `CapabilityGrant`, `ExecutionStateRecord`, `EvaluationReport`, `EvidenceManifest`, `NormativeStatusRecord`), strict JSON schema serializer (`ignoreUnknownKeys = false`), state machine transition validator, and HMAC-SHA256 / SHA-256 cryptographic primitives.
2. **`:dnalang-adapter`** (`dnalang-adapter/`): L0 AST Firewall (`DnaLangL0Validator`), physical substrate constants ($\theta_{\text{lock}} = 51.843^\circ$, $\Gamma_{\text{floor}} = 0.0920$, $\Lambda_\Phi = 2.176435 \times 10^{-8}\text{ kg}$), and deterministic $\mathcal{H}_8$ Z3bra Intent Lock vectorizer.
3. **`:governance`** (`governance/`): Least-privilege `CapabilityGovernanceEngine` enforcing cryptographic grant verification, TTL expiry, single-use replay rejection, immediate revocation, and canonical path traversal prevention.
4. **`:evidence`** (`evidence/`): `DynamicEvidenceLedger` (SHA-256 hash chain + Merkle root), `EvidenceManifestVerifier`, and `ReleasePromotionGate` (Gate 0 human authorization, branch protection, provenance verification, and <60s staging rollback).
5. **`:evaluation`** (`evaluation/`): `IndependentEvaluatorEngine` producing normalized `EvaluationReport` records across local and remote substrates, computing empirical Shannon entropy and 1D Wasserstein-2 ($W_2$) distance against the golden-ratio $\varphi$ distribution, and marking missing/crashing evaluators as `INCONCLUSIVE`.
6. **`:runtime-bridge`** (`runtime-bridge/`): `DualSubstrateHardwareDetector` (`Node Alpha` 300.0s NEON SIMD vs. `Node Beta` 1800.0s AVX2/AVX-VNNI without hardcoded paths), `TermuxSocketBridge`, and `ProotCommandDispatcher` (allowlisted binaries, zero shell metacharacters).
7. **`:execution`** (`execution/`): `LocalExecutionAdapter`, `RemoteExecutionAdapter`, AES-256-GCM `EncryptedDraftStore`, `BackpressureLogStreamBuffer`, and `HybridExecutionOrchestrator`.
8. **`:keyboard`** (`keyboard/`): `OsirisSmartKeyboardService` (`InputMethodService`), `ImeCompositionSession`, `PrivacyInputFilter`, `VoiceCompositionEngine`, and `DeveloperDeck` with two-step Intent Preview Gate.
9. **`:app`** (`app/`): Pixel Fold adaptive UI (`PixelFoldPostureController`), zero-loss fold/unfold continuity (`TaskJournalContinuityController`), normative badge separation (`EvidenceBadgePresenter`), and `ApprovalGateController`.
