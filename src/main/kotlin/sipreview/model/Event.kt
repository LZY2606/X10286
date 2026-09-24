package sipreview.model

import kotlinx.serialization.Serializable

/**
 * The append-only project log. Nothing derived is ever overwritten: revisions arrive as new
 * events with explicit parent links. seq values are assigned in commit order and are used for
 * timeline seeking and stale invalidation.
 */
@Serializable
data class ProjectEvent(
    val seq: Long,
    val eventId: String,
    val type: String,
    val at: TimeCoord,
    val parentEventId: String? = null,
    val payloadJson: String,
    val payloadSha256: String,
)

object EventType {
    const val PROJECT_CREATED = "project-created"
    const val CONNECTION_DECLARED = "connection-declared"
    const val RAW_COMMITTED = "raw-committed"
    const val PARAMS_COMMITTED = "params-committed"
    const val DECISION_COMMITTED = "decision-committed"
    const val EXPERIMENT_ARCHIVED = "experiment-archived"
}

@Serializable
data class ProjectMeta(val projectId: String, val name: String, val createdAt: TimeCoord)

@Serializable
data class RawCommittedPayload(
    val raw: RawMessage,
    val fileName: String?,
    val bytesSha256: String,
    val batchId: String,
    val batchLabel: String?,
)

@Serializable
data class ConnectionPayload(val connection: ConnectionSpec)

@Serializable
data class ParamsPayload(val params: SipParams)

@Serializable
data class DecisionPayload(val decision: Decision)

@Serializable
data class ExperimentPayload(val experiment: ExperimentRecord)
