package org.osiris.android.sdk

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Normative entry point for OSIRIS Android runtime interactions (OSIRIS-ANDROID-SPEC-001 Section 7.1).
 * All operations return typed, versioned data contracts.
 */
interface OsirisClient {
    suspend fun submitIntent(request: IntentRequest): IntentReceipt
    suspend fun inspectExecution(executionId: String): ExecutionSnapshot
    suspend fun evaluate(executionId: String): EvaluationReport
    suspend fun getEvidence(executionId: String): EvidenceManifest
    suspend fun requestCapability(request: CapabilityRequest): CapabilityDecision
}

/**
 * Deterministic contract serializer & version validator enforcing AT-API-01 and AT-API-02.
 */
object ContractSerializer {
    val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    fun validateSchemaVersion(schemaVersion: String) {
        val parts = schemaVersion.trim().split(".")
        if ((parts.size != 2 && parts.size != 3) || parts.any { it.toIntOrNull() == null }) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_SCHEMA_MISMATCH,
                "Malformed schemaVersion '$schemaVersion'; expected MAJOR.MINOR or MAJOR.MINOR.PATCH"
            )
        }
        val major = parts[0].toInt()
        if (major != SUPPORTED_MAJOR_VERSION) {
            throw OsirisContractException(
                OsirisErrorCode.ERR_VERSION_UNSUPPORTED,
                "Unsupported schema major version $major in '$schemaVersion' (supported: $SUPPORTED_MAJOR_VERSION)"
            )
        }
    }

    fun validateIntentRequest(request: IntentRequest) {
        validateSchemaVersion(request.schemaVersion)
        if (request.requestId.isBlank()) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "requestId must not be blank")
        }
        if (request.idempotencyKey.isBlank()) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "idempotencyKey must not be blank")
        }
        if (request.naturalLanguageTask.isBlank()) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "naturalLanguageTask must not be blank")
        }
        if (request.createdAtEpochMs <= 0L) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "createdAtEpochMs must be positive")
        }
    }

    fun validateTaskIntent(intent: OsirisTaskIntent) {
        validateSchemaVersion(intent.schemaVersion)
        if (intent.taskId.isBlank()) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_VALIDATION, "taskId must not be blank")
        }
        if (intent.title.isBlank()) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_VALIDATION, "title must not be blank")
        }
        if (intent.promptBody.isBlank()) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_VALIDATION, "promptBody must not be blank")
        }
        if (intent.createdAtEpochMs <= 0L) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_VALIDATION, "createdAtEpochMs must be positive")
        }
    }

    fun encodeIntentRequest(request: IntentRequest): String {
        validateIntentRequest(request)
        return canonicalJson.encodeToString(request)
    }

    fun decodeIntentRequest(rawJson: String): IntentRequest {
        val parsedObj = try {
            canonicalJson.parseToJsonElement(rawJson) as? JsonObject
                ?: throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "Expected JSON object")
        } catch (e: OsirisContractException) {
            throw e
        } catch (e: Exception) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "Malformed JSON payload: ${e.message}")
        }

        val versionStr = parsedObj["schemaVersion"]?.jsonPrimitive?.content
            ?: throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "Missing mandatory field 'schemaVersion'")
        validateSchemaVersion(versionStr)

        val decoded = try {
            canonicalJson.decodeFromString<IntentRequest>(rawJson)
        } catch (e: SerializationException) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "Schema deserialization failed: ${e.message}")
        }
        validateIntentRequest(decoded)
        return decoded
    }

    fun encodeTaskIntent(intent: OsirisTaskIntent): String {
        validateTaskIntent(intent)
        return canonicalJson.encodeToString(intent)
    }

    fun decodeTaskIntent(rawJson: String): OsirisTaskIntent {
        val decoded = try {
            canonicalJson.decodeFromString<OsirisTaskIntent>(rawJson)
        } catch (e: SerializationException) {
            throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_VALIDATION, "TaskIntent deserialization failed: ${e.message}")
        }
        validateTaskIntent(decoded)
        return decoded
    }

    inline fun <reified T> encode(value: T): String = canonicalJson.encodeToString(value)
    inline fun <reified T> decode(rawJson: String): T = try {
        canonicalJson.decodeFromString<T>(rawJson)
    } catch (e: SerializationException) {
        throw OsirisContractException(OsirisErrorCode.ERR_SCHEMA_MISMATCH, "Schema mismatch: ${e.message}")
    }
}

/**
 * Deterministic Execution State Machine enforcing Section 7.3 & AT-API-04.
 */
class ExecutionStateMachine {
    private val states = ConcurrentHashMap<String, ExecutionState>()
    private val sequences = ConcurrentHashMap<String, Long>()
    private val illegalTransitionAuditLog = mutableListOf<String>()

    companion object {
        private val ALLOWED_TRANSITIONS: Map<ExecutionState, Set<ExecutionState>> = mapOf(
            ExecutionState.DRAFT to setOf(ExecutionState.VALIDATED, ExecutionState.REJECTED, ExecutionState.CANCELLED),
            ExecutionState.VALIDATED to setOf(ExecutionState.AWAITING_AUTHORIZATION, ExecutionState.QUEUED, ExecutionState.REJECTED),
            ExecutionState.AWAITING_AUTHORIZATION to setOf(ExecutionState.QUEUED, ExecutionState.REJECTED, ExecutionState.CANCELLED),
            ExecutionState.QUEUED to setOf(ExecutionState.RUNNING, ExecutionState.CANCELLED, ExecutionState.REJECTED),
            ExecutionState.RUNNING to setOf(
                ExecutionState.AWAITING_RECONNECT,
                ExecutionState.EVALUATING,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED,
                ExecutionState.EXPIRED
            ),
            ExecutionState.AWAITING_RECONNECT to setOf(
                ExecutionState.RUNNING,
                ExecutionState.EVALUATING,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED,
                ExecutionState.EXPIRED
            ),
            ExecutionState.EVALUATING to setOf(
                ExecutionState.COMPLETED,
                ExecutionState.FAILED,
                ExecutionState.INCONCLUSIVE
            ),
            // Terminal states permit zero outgoing transitions
            ExecutionState.COMPLETED to emptySet(),
            ExecutionState.FAILED to emptySet(),
            ExecutionState.REJECTED to emptySet(),
            ExecutionState.CANCELLED to emptySet(),
            ExecutionState.EXPIRED to emptySet(),
            ExecutionState.INCONCLUSIVE to emptySet()
        )

        fun requireValidTransition(from: ExecutionState, to: ExecutionState) {
            if (ALLOWED_TRANSITIONS[from]?.contains(to) != true) {
                throw OsirisContractException(
                    OsirisErrorCode.ERR_INVALID_STATE_TRANSITION,
                    "Illegal state transition: $from -> $to"
                )
            }
        }
    }

    @Synchronized
    fun initialize(executionId: String, initialState: ExecutionState = ExecutionState.DRAFT): ExecutionState {
        states[executionId] = initialState
        sequences[executionId] = 1L
        return initialState
    }

    @Synchronized
    fun currentState(executionId: String): ExecutionState? = states[executionId]

    @Synchronized
    fun currentSequence(executionId: String): Long = sequences[executionId] ?: 0L

    @Synchronized
    fun isAllowedTransition(from: ExecutionState, to: ExecutionState): Boolean {
        return ALLOWED_TRANSITIONS[from]?.contains(to) == true
    }

    @Synchronized
    fun transition(executionId: String, targetState: ExecutionState): ExecutionState {
        val current = states[executionId]
            ?: throw OsirisContractException(
                OsirisErrorCode.ERR_ILLEGAL_STATE_TRANSITION,
                "Unknown executionId '$executionId'"
            )
        if (!isAllowedTransition(current, targetState)) {
            val auditEntry = "ILLEGAL_TRANSITION: executionId=$executionId from=$current to=$targetState"
            illegalTransitionAuditLog.add(auditEntry)
            throw OsirisContractException(
                OsirisErrorCode.ERR_ILLEGAL_STATE_TRANSITION,
                "Illegal state transition for '$executionId': $current -> $targetState"
            )
        }
        states[executionId] = targetState
        sequences[executionId] = (sequences[executionId] ?: 1L) + 1L
        return targetState
    }

    @Synchronized
    fun getIllegalTransitionAuditLog(): List<String> = illegalTransitionAuditLog.toList()
}
