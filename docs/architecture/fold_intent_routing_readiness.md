# Fold-Aware Intent Routing Readiness Contract

**Status:** Proposed validation contract; not a tested release claim  
**Target:** OSIRIS Android split keyboard / IME, Pixel foldable class  
**Device baseline:** Must be established from device evidence. Do not assume Pixel 11 Pro Fold specifications or infer them from another model.

## Invariants

- No background clipboard polling or collection. Clipboard ingestion must follow an explicit user action and platform permission semantics.
- No model or remote provider has filesystem, subprocess, network, install, signing, or deployment authority by default.
- Intent proposals are typed data. A deterministic policy layer validates requested action and capability before side effects.
- Fold posture is an input to layout selection, not a security principal. Layout changes must not alter authorization.
- Local-first means offline tests pass and sensitive payloads are not sent to remote endpoints; evidence is required.
- Cross-node synchronization transfers data only. It never transfers authorization.
- Missing, stale, mismatched, or unverifiable policy/evidence blocks protected actions.

## Required device-level gates

| Gate | Evidence required | Missing evidence |
|---|---|---|
| Device baseline | Model, build fingerprint, Android API, posture signals | NOT_MEASURED |
| Split IME | Instrumented posture/layout/focus tests on inner and outer displays | NOT_MEASURED |
| Intent provenance | Tests for explicit user gesture, typed request, request digest, capability check | BLOCKED |
| Clipboard privacy | Tests proving no background collection and bounded retention | BLOCKED |
| Offline operation | Network-disabled end-to-end test with deterministic local response | NOT_MEASURED |
| Zero egress | Socket/packet capture, monitor scope, allowed-endpoint policy, digest | NOT_MEASURED |
| Keystore | API-level feature detection and device-backed key operation evidence | NOT_MEASURED |
| Accessibility | Tests for screen reader, focus order, target size, posture transitions | NOT_MEASURED |

## Release rule

A prose approval or green local unit test does not authorize installation, signing, deployment, or promotion. A protected action requires an independently verified SYS-021 grant bound to the exact payload digest and action scope. Until then, this contract is documentation only and no runtime behavior changes.
