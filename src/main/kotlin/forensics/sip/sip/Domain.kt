package forensics.sip.sip

import kotlinx.serialization.Serializable

enum class Direction { SENT, RECEIVED }

enum class Transport { UDP, TCP, TLS, WS, WSS, UNKNOWN }

@Serializable
data class MessageRecord(
    val id: String,
    val importBatchId: String,
    val ordinal: Long,
    val direction: Direction,
    val transport: Transport,
    val connectionGeneration: Long,
    val connectionId: String,
    val receivedAtTicksUtc: Long,
    val timeUnit: String = "ns-utc-virtual",
    val rawText: String,
    val rawBytesBase64: String,
    val rawByteCount: Int,
    val sourceAddress: String? = null,
    val sourcePort: Int? = null,
    val sourceAddressBasis: String = "observer-socket",
    val declaredByInput: Boolean = false,
)

@Serializable
data class ConnectionEvent(
    val id: String,
    val importBatchId: String,
    val ordinal: Long,
    val kind: String, // OPEN, REUSED, CLOSED, RESET
    val transport: Transport,
    val generation: Long,
    val connectionId: String,
    val atTicksUtc: Long,
    val timeUnit: String = "ns-utc-virtual",
    val localAddress: String? = null,
    val remoteAddress: String? = null,
    val note: String? = null,
)

@Serializable
data class InputBatch(
    val id: String,
    val schemaVersion: String,
    val ordinal: Long,
    val parentBatchId: String?,
    val submittedAtTicksUtc: Long,
    val label: String,
    val messages: List<MessageRecord>,
    val connectionEvents: List<ConnectionEvent>,
    val rawInputSha256: String,
)

@Serializable
data class ImportRequest(
    val label: String,
    val parentBatchId: String? = null,
    val messages: List<MessageIn>,
    val connectionEvents: List<ConnectionIn> = emptyList(),
)

@Serializable
data class MessageIn(
    val id: String? = null,
    val direction: String,
    val transport: String = "UDP",
    val connectionGeneration: Long = 0,
    val connectionId: String? = null,
    val receivedAtTicksUtc: Long,
    val rawText: String,
    val rawBytesBase64: String? = null,
    val sourceAddress: String? = null,
    val sourcePort: Int? = null,
)

@Serializable
data class ConnectionIn(
    val id: String? = null,
    val kind: String,
    val transport: String = "TCP",
    val generation: Long,
    val connectionId: String,
    val atTicksUtc: Long,
    val localAddress: String? = null,
    val remoteAddress: String? = null,
    val note: String? = null,
)

@Serializable
data class ImportDiagnostic(
    val messageOrdinal: Long?,
    val severity: String, // ERROR, WARNING
    val code: String,
    val detail: String,
)

@Serializable
data class ImportReport(
    val batchId: String,
    val ordinal: Long,
    val parentBatchId: String?,
    val accepted: Boolean,
    val messageCount: Int,
    val connectionEventCount: Int,
    val diagnostics: List<ImportDiagnostic>,
    val rawInputSha256: String,
)
