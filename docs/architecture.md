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

## 6. Specification Status and Normative Language

This continuation defines the proposed OSIRIS Android architecture. It does not assert that the components below already exist.

The governing principle is separation: probabilistic components propose; deterministic components authorize; constrained workers execute; the evidence service records.

**MUST** and **MUST NOT** define release requirements. **SHOULD** identifies a default that requires a documented justification to override. **MAY** identifies an optional feature.

Two existing artifacts inform this specification:

- `prompt_factory.txt` requires organism generation, Crucible evaluation, error-driven regeneration, and a maximum of five mutation retries per organism.
- `sprint_backlog_1789101602.json` records an artifact with status `AST_VERIFIED_AND_LOCKED`. That status alone does not establish runtime containment, authorization, or correctness.

All component names, schemas, and CIL layer assignments below are proposed contracts. Existing implementations that use different contracts require an explicit compatibility mapping.

## 7. System Boundary and Deployment Topology

### 7.1 Product Boundary

`osiris-android` MUST provide only:

- An OSIRIS command terminal.
- Explicitly supported 11D-CRSM operations.
- `dna::}{::lang` parsing, validation, evaluation, and organism-management workflows.
- Device-health policy inputs.
- Deterministic authorization and constrained effect dispatch.
- Local evidence inspection and verification.

The application MUST NOT expose an unrestricted shell, arbitrary plugin installation, background crawling, or an open-ended general-purpose agent loop as part of its default runtime.

A diagnostic build MAY expose additional developer functions, but those functions MUST be absent or disabled by an independently enforced release-build policy.

### 7.2 Logical Topology

```text
					 Explicit user interaction
								|
					+-----------v------------+
					| Android Terminal UI    |
					| REPL / structured view |
					+-----------+------------+
								| IntentEnvelope
		  +---------------------v---------------------+
		  | Proposal substrate                        |
		  |                                           |
		  | Language adapters -- Optional local model |
		  |           |                               |
		  |           +-- Proposal + artifact digest  |
		  +---------------------+---------------------+
								| Validated IPC
		  +---------------------v---------------------+
		  | Authority substrate                       |
		  |                                           |
		  | CIL policy -> authorization -> effect     |
		  | broker                                    |
		  |       ^                         |         |
		  | Health observations             |         |
		  | Evidence service <---------------+         |
		  +---------------------+---------------------+
								| Scoped execution grant
					+-----------v------------+
					| Constrained worker    |
					| No ambient authority  |
					+------------------------+
```

This diagram describes logical responsibilities, not permission to place all components in one unrestricted process.

### 7.3 Required Isolation Properties

The implementation MUST document the concrete mechanism enforcing each boundary.

| Boundary | Required property |
| --- | --- |
| UI -> authority | UI cannot directly invoke privileged effects. |
| Proposal -> authority | Generated output is treated as untrusted data. |
| Worker -> host | Worker receives only explicitly granted operations. |
| Worker -> evidence | Worker cannot rewrite authorization records. |
| Model -> policy | Model cannot update policy, trust roots, or capability definitions. |
| Artifact store -> execution | A stored artifact cannot execute without fresh authorization. |

A worker's possession of a filesystem path, artifact identifier, or successful test result MUST NOT itself convey execution authority.

## 8. Dual-Substrate Execution Contract

### 8.1 Proposal Substrate

The proposal substrate MAY:

- Parse user requests into candidate operations.
- Generate or transform organisms.
- Produce execution plans.
- Request capabilities.
- Run evaluations within separately authorized limits.
- Report uncertainty, diagnostics, and candidate results.

It MUST NOT:

- Issue its own grants.
- Modify the active policy.
- Supply authoritative user-consent assertions.
- Write protected system assets.
- Change an execution budget after authorization.
- Treat its own generated signatures or provenance claims as trusted.

The deterministic path MUST remain available for commands that do not need model inference.

### 8.2 Authority Substrate

The authority substrate MUST:

- Validate message structure and protocol version.
- Authenticate the requesting component where required.
- Bind authorization to the exact proposed operation.
- Evaluate the current policy and relevant state.
- Issue a bounded execution grant or a structured denial.
- Monitor execution through an independent supervisor.
- Record authorization and execution outcomes.

The authority substrate MUST NOT invoke a model to decide whether a mandatory security rule applies.

### 8.3 No Implicit Authority Transfer

The following transitions are distinct; registration does not imply authorization:

```text
Generated -> Parsed -> Validated -> Evaluated -> Registered
										  |
										  +-- does not imply authorization

Registered + Current policy + Explicit scope
					-> Authorized -> Dispatched
```

Artifact registration establishes identity and lifecycle status. It does not establish trust.

## 9. CIL L0-L6 Reference Contract

The following layer mapping is normative for this proposed specification. Existing implementations using different layer meanings require an explicit compatibility mapping.

| Layer | Responsibility | Required rejection conditions |
| --- | --- | --- |
| L0: Admission | Bound input size, protocol version, and message shape. | Oversized, malformed, or unsupported envelope. |
| L1: Identity and intent | Establish caller, request identity, consent scope, and replay status. | Unauthenticated caller where required; stale or replayed request. |
| L2: Language structure | Parse with the declared language and grammar version. | Syntax failure, unsupported construct, or parser budget exceeded. |
| L3: Semantics and capabilities | Resolve operations, types, host calls, and requested effects. | Undeclared host operation, forbidden capability, or ambiguous lowering. |
| L4: Resource and device policy | Evaluate execution budgets and required health observations. | Excess budget, stale required telemetry, or prohibited device state. |
| L5: Authorization and dispatch | Bind grant to artifact, policy, inputs, and target state. | Digest mismatch, expired grant, or changed preconditions. |
| L6: Reconciliation and evidence | Record outcome, reconcile effects, and finalize lifecycle state. | Missing receipt, uncertain effect outcome, or evidence failure. |

### 9.1 Layer Output

Every layer MUST return a structured decision:

```text
Decision {
	request_id
	layer
	outcome: ALLOW | DENY | DEFER
	reason_code
	policy_digest
	evaluated_input_digest
	evidence_reference
}
```

`DEFER` means execution has not been authorized. It MUST NOT trigger automatic fallback to a less restrictive path.

### 9.2 Check-to-Execution Binding

Validation and dispatch MUST refer to the same immutable artifact bytes.

If an artifact, input, policy, or relevant precondition changes after validation, the authorization MUST be invalidated or reevaluated according to an explicit rule.

A filename alone is not an adequate binding.

## 10. Language Adapters and Execution Representation

### 10.1 Adapter Interface

Each supported language or workflow MUST have a versioned adapter:

```text
LanguageAdapter {
	language_id
	grammar_version
	adapter_version

	parse(source, parse_budget) -> SyntaxTree
	validate(tree, language_policy) -> ValidationResult
	lower(tree) -> GovernedIR
	describe_effects(ir) -> EffectManifest
}
```

The adapter MUST NOT execute source as a side effect of parsing or validation.

### 10.2 Governed Intermediate Representation

Executable operations MUST be represented in a governed intermediate representation, or an equivalent explicitly validated execution format.

Each operation MUST identify:

- Operation type.
- Input and output types.
- Required capabilities.
- Resource-accounting behavior.
- Permitted host interactions.
- Failure behavior.

Unknown operations MUST be rejected.

If a language construct cannot be safely represented, the adapter MUST reject it rather than silently lowering it to unrestricted Python or shell execution.

### 10.3 Python Compatibility

If Python-generated organisms remain supported:

- Python MUST be treated as a separate execution backend.
- AST validation MUST NOT be treated as sufficient containment.
- Imports, reflection, dynamic code construction, and host access MUST have explicit policies.
- Unsupported Python behavior MUST fail closed.
- The backend MUST disclose its containment limitations.
- The `.dna` extension MUST identify an artifact format or language contract; it MUST NOT serve as a security classification.

## 11. Protocol Envelopes and Execution Grants

### 11.1 Intent Envelope

```text
IntentEnvelope {
	schema_version
	request_id
	session_id
	origin
	command
	language_id
	input_digest
	requested_capabilities[]
	requested_budget
	consent_reference?
	replay_protection
}
```

The receiving authority MUST establish caller identity from the authenticated transport or an equivalent trusted mechanism. It MUST NOT trust an `origin` field merely because it appears in the payload.

### 11.2 Proposal Envelope

```text
ProposalEnvelope {
	request_id
	proposal_id
	parent_proposal_id?
	artifact_digest
	adapter_version
	governed_ir_digest
	effect_manifest
	requested_budget
	generation_metadata?
}
```

Model names, confidence scores, and evaluation scores MAY be recorded as metadata. They MUST NOT override policy.

### 11.3 Execution Grant

```text
ExecutionGrant {
	grant_id
	request_id
	proposal_id
	artifact_digest
	governed_ir_digest
	input_digest
	policy_digest
	capability_scope[]
	resource_budget
	preconditions
	validity
	replay_policy
	authentication
}
```

A grant MUST be unusable for a different artifact, operation, input set, or capability scope.

Grant reuse MUST follow a defined replay policy. One-shot effect grants SHOULD be consumed atomically before dispatch.

## 12. Execution Lifecycle and Recovery

### 12.1 State Machine

```text
RECEIVED
   -> ADMITTED
   -> PROPOSED
   -> VALIDATED
   -> AUTHORIZED
   -> DISPATCHING
   -> RUNNING
   -> SUCCEEDED | FAILED | CANCELLED | TIMED_OUT | INDETERMINATE
```

Any pre-dispatch state MAY transition to `DENIED`.

`INDETERMINATE` means an effect may have occurred but its outcome is not yet established.

### 12.2 Dispatch Protocol

For effectful operations, the authority MUST:

1. Persist the authorization decision and dispatch identifier.
2. Recheck required preconditions.
3. Consume or reserve the applicable grant.
4. Dispatch through the effect broker.
5. Record the returned receipt or uncertain outcome.
6. Reconcile before permitting a conflicting retry.

The specification MUST NOT promise atomicity across a local journal and an external service unless the actual protocol provides it.

### 12.3 Restart Behavior

After process death or device restart:

- Previously running jobs MUST NOT be assumed successful.
- External effects MUST NOT be blindly retried.
- Outstanding dispatches MUST enter reconciliation.
- Missing evidence MUST remain visible as missing evidence.
- Recovery MUST NOT manufacture successful receipts.

For a future trading adapter, an uncertain order submission MUST be reconciled against venue or broker state before resubmission. Mobile process recovery must not become an order-duplication mechanism.

## 13. Resource Governor and Hardware Observations

### 13.1 Budget Model

Every job MUST have explicit limits for applicable resources:

```text
ResourceBudget {
	wall_time
	cpu_budget?
	memory_limit
	output_bytes
	artifact_bytes
	model_input_tokens?
	model_output_tokens?
	network_bytes?
	host_operation_count
	retry_count
}
```

Unsupported accounting fields MUST be reported as unsupported. They MUST NOT be represented as enforced limits.

A job MUST NOT increase its own budget.

### 13.2 Deadline Semantics

A proposed 300.0-second limit MUST be expressed as a configurable application policy.

The specification MUST define:

- When the deadline begins.
- Whether suspension counts toward it.
- Which monotonic clock governs it.
- How cancellation is requested.
- When forced termination occurs.
- What happens to partially completed effects.

A worker's cooperative timeout handler MUST NOT be the sole enforcement mechanism.

### 13.3 Observation Contract

```text
HealthObservation {
	signal
	source
	value
	unit
	observed_at
	freshness_limit
	availability
}
```

Temperature readings, thermal severity, battery state, and CPU observations MUST remain distinct signals.

Each operation class MUST declare which observations it requires. Missing optional telemetry MAY reduce observability; missing mandatory telemetry MUST prevent the operation.

### 13.4 Degraded Modes

| Mode | Permitted behavior |
| --- | --- |
| Normal | Operations within current policy. |
| Constrained | Reduced inference, concurrency, or execution budgets. |
| Read-only | Inspection and evidence verification; no protected effects. |
| Halted | No new execution; bounded recovery and diagnostics only. |

The governor MUST define entry, exit, and hysteresis rules. A model MUST NOT override a degraded-mode decision.

## 14. Evidence Journal and Artifact Integrity

### 14.1 Record Format

```text
JournalRecord {
	schema_version
	journal_id
	sequence
	event_id
	request_id?
	event_type
	actor
	policy_digest?
	artifact_digest?
	payload_digest
	previous_record_hash
	record_hash
	authentication?
	wall_timestamp
	monotonic_context
}
```

Hashing and authentication MUST use a documented canonical serialization and domain-separation scheme.

The record hash MUST exclude its own value. Authentication MUST identify exactly which bytes or digest it covers.

### 14.2 Evidence Guarantees

The implementation MUST state separately whether it provides:

- Record integrity.
- Chain continuity.
- Record authentication.
- Protected local persistence.
- Detection of truncation or rollback.
- Independent checkpoint verification.

A local hash chain alone MUST NOT be described as proving that no records were deleted or that an event was externally truthful.

A Merkle structure MAY be added for inclusion proofs, but MUST have its own construction and verification specification.

### 14.3 Failure Handling

If required authorization evidence cannot be durably recorded, new effectful work MUST be denied.

Failure to record an execution result MUST NOT erase the possibility that the effect occurred. Such operations MUST enter reconciliation.

Safety actions such as worker termination MUST NOT be blocked by an unavailable journal.

### 14.4 Sensitive Data

The journal SHOULD record digests and structured metadata rather than raw prompts, clipboard contents, credentials, or unrestricted exception traces.

Diagnostic detail MUST be redacted before any optional model request or export.

## 15. Terminal, Consent, and Network Behavior

### 15.1 Terminal Command Surface

An initial command namespace MAY include:

```text
osiris status
osiris policy inspect
osiris jobs list
osiris jobs cancel <job-id>

dna check <artifact>
dna evaluate <artifact>
dna register <artifact>

crsm inspect <model-id>
crsm evaluate <model-id>

journal verify
journal inspect <event-id>
```

These commands define proposed intent routes, not shell aliases.

File references MUST resolve through a scoped document or artifact interface. They MUST NOT authorize arbitrary path traversal.

### 15.2 Fold and Keyboard Behavior

Presentation changes MUST NOT change command meaning.

- Folding, rotation, and keyboard transitions MUST preserve drafts.
- Layout changes MUST NOT submit commands.
- Input focus changes MUST NOT constitute consent.
- Output text MUST NOT become executable input without an explicit user action.
- The fixed prompt MUST remain presentation data, not a secret or authentication token.

### 15.3 Clipboard and IME Integration

Clipboard import MUST require an explicit user action.

Any companion IME integration MUST define its own identity, permissions, message scope, and authentication contract. Merely sharing the device or loopback transport MUST NOT establish trust.

### 15.4 Network Profiles

The runtime SHOULD define explicit profiles:

| Profile | Behavior |
| --- | --- |
| Offline | No network-dependent operations. |
| Local-service | Only configured local endpoints. |
| Approved-remote | Only authorized endpoints and operation types. |

A failed local model request MUST NOT silently fall back to a cloud provider.

Each outbound request MUST pass the same policy boundary regardless of whether it originates from a model adapter, organism, diagnostic routine, or user command.

## 16. Bounded Organism-Factory Integration

The factory specification calls for regeneration from execution failures and caps mutation retries at five per organism. For Android, the factory MUST additionally enforce a session-wide budget.

### 16.1 Attempt Accounting

The proposed interpretation is:

| Attempt type | Maximum |
| --- | ---: |
| Initial candidate | 1 |
| Mutation retries | Up to 5 |
| Total per organism | 6 |

If the existing implementation interprets “five retries” differently, it MUST expose and version that behavior rather than silently changing counts.

### 16.2 Factory Lifecycle

```text
Backlog item
   -> Candidate generation
   -> Static validation
   -> Authorized Crucible evaluation
   -> Result classification
	   |-- accepted -> register candidate
	   |-- repairable -> retry within remaining budgets
	   |-- exhausted / prohibited -> stop and report
```

The factory MUST NOT dynamically import generated code into the trusted authority process.

A score such as `accuracy = 0.0` MUST NOT be the only failure signal. Results MUST distinguish at least:

- Parse failure.
- Policy rejection.
- Runtime failure.
- Timeout.
- Resource exhaustion.
- Evaluation failure.
- Infrastructure failure.

Infrastructure failures SHOULD NOT automatically trigger organism mutation.

### 16.3 Promotion Policy

Registration MUST NOT imply deployment.

Promotion to an executable release artifact MUST require a separate manifest containing:

- Artifact digest.
- Language and adapter versions.
- Evaluation-suite identity and results.
- Requested capabilities.
- Applicable policy version.
- Approval or release authority.

A candidate MUST NOT edit its own evaluation criteria or promotion requirements.

## 17. Release Evidence and Completion Criteria

Each security claim MUST map to an enforcement mechanism and an executable acceptance test.

| Claim | Required release evidence |
| --- | --- |
| Generated output cannot bypass policy | Adversarial bypass tests against every dispatch path. |
| Artifact validation binds execution | Mutation-between-check-and-dispatch test. |
| Grants cannot be replayed | Duplicate, stale, and cross-artifact request tests. |
| Time limits are enforced | Uncooperative-worker termination test. |
| Clipboard access is explicit | Background-access and consent-path tests. |
| No silent cloud fallback | Network-observation tests under adapter failure. |
| Evidence detects supported tampering | Modification, truncation, rollback, and key-scope tests. |
| Recovery avoids duplicate effects | Crash injection around every dispatch transition. |
| Protected policy is not self-modifying | Generated-code and compromised-worker write tests. |

The release manifest MUST identify the application build, policy digest, adapter versions, supported execution backends, and known limitations.

The first production milestone is complete only when one explicit user intent can traverse admission, validation, authorization, constrained execution, evidence recording, and restart recovery without an alternate ungoverned path.
