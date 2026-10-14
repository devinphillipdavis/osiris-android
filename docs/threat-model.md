# OSIRIS Google Pixel Fold Android Platform — Threat Model & Security Controls

**Document Reference:** `OSIRIS-ANDROID-SPEC-001` (Section 6 & Suite D)

## Threat Matrix & Enforced Mitigations

| Threat ID | Vector | Mitigation Implemented | Acceptance Test |
|---|---|---|---|
| **TM-01** | Unauthorized or implicit command execution | `CapabilityGovernanceEngine.authorizeAndConsumeGrant` requires a valid HMAC-SHA256 signed `CapabilityGrant` before any execution adapter runs. | `AT-SEC-01` |
| **TM-02** | Expired or replayed capability tokens | Enforces strict `nowEpochMs <= expiresAtEpochMs`, single-use consumption tracking, and immediate revocation set. | `AT-SEC-02` |
| **TM-03** | Path traversal & sandbox escape (`../`) | Canonical `File.canonicalPath` prefix containment check against `grant.allowedDirectories`. | `AT-SEC-03` |
| **TM-04** | Plaintext credential/draft leakage on disk | `EncryptedDraftStore` encrypts offline drafts with AES-256-GCM (96-bit random IV, 128-bit auth tag); `android:allowBackup="false"`. | `AT-SEC-04` |
| **TM-05** | Embedded secrets in source or APK | Automated secret scan (`devops/signing/verify_provenance.py`) verifies zero embedded API keys, private keys, or tokens. | `AT-SEC-05` |
| **TM-06** | Malicious `.dna` payload or shell injection | `DnaLangL0Validator` rejects system call tokens (`os.system`, `Runtime.getRuntime().exec`, `ProcessBuilder`, `sh -c`) and `ProotCommandDispatcher` rejects shell metacharacters. | `AT-SEC-06` |
| **TM-07** | Evidence or artifact tampering | `EvidenceManifestVerifier` verifies constant-time SHA-256 digests of all build/execution artifacts. | `AT-SEC-07` |
| **TM-08** | Over-privileged Android manifest | `app/src/main/AndroidManifest.xml` restricts permissions to `INTERNET`, `RECORD_AUDIO`, and `POST_NOTIFICATIONS`. | `AT-SEC-08` |
| **TM-09** | Keyboard password logging or cross-app text capture | `PrivacyInputFilter` disables predictions, logging, and intent conversion on password/PIN fields and blocks cross-app capture without explicit user action. | `AT-IME-02`, `AT-IME-03` |
