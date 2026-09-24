package sipreview.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Direction { IN, OUT }

@Serializable
enum class Transport { UDP, TCP, TLS }

@Serializable
data class Endpoint(val host: String, val port: Int?) {
    override fun toString(): String = if (port != null) "$host:$port" else host
}

@Serializable
data class ConnectionGen(
    val id: String,
    val transport: Transport,
    val local: Endpoint?,
    val remote: Endpoint?,
    val generation: Int,
    val openedTick: Long?,
    val closedTick: Long?,
    val note: String? = null
)

@Serializable
data class ClockMark(
    val tick: Long,
    val isoText: String? = null,
    val note: String? = null
)

/**
 * Immutable original record. Raw bytes are preserved exactly as submitted,
 * together with the original textual time representation and the coordinate
 * base (tick, unit). Once committed a record can never be overwritten; a
 * revision creates a new record with a new [recordId].
 */
@Serializable
data class RawMessage(
    val logicalId: String,
    val recordId: String,
    val ordinal: Int,
    val direction: Direction,
    val connectionId: String,
    val atTick: Long,
    val atTickText: String?,
    val receivedAtTick: Long,
    val receivedAtText: String?,
    val wallIso: String?,
    val rawText: String?,
    val rawBase64: String,
    val rawLength: Int,
    val diagnostics: List<String> = emptyList()
)

@Serializable
data class InputManifest(
    val versionId: String,
    val parentVersionId: String? = null,
    val label: String,
    val baseEpochMs: Long? = null,
    val timeUnit: String = "ms",
    val timerDefaults: Map<String, String> = emptyMap(),
    val connections: List<ConnectionGen> = emptyList(),
    val clocks: List<ClockMark> = emptyList(),
    val messages: List<RawMessage> = emptyList()
)

@Serializable
sealed class StartLine {
    @Serializable
    data class Request(val method: String, val requestUri: String, val sipVersion: String) : StartLine()
    @Serializable
    data class Status(val sipVersion: String, val statusCode: Int, val reason: String) : StartLine()
}

@Serializable
data class Via(
    val protocol: String,
    val sentByHost: String,
    val sentByPort: Int?,
    val branch: String?,
    val rport: String?,
    val raw: String
)

@Serializable
data class CSeqRef(val number: Long, val method: String, val raw: String)

@Serializable
data class SipUri(
    val raw: String,
    val scheme: String?,
    val user: String?,
    val host: String?,
    val port: Int?,
    val params: Map<String, String?>
)

@Serializable
data class ParsedMessage(
    val recordId: String,
    val startLine: StartLine?,
    val via: List<Via>,
    val callId: String?,
    val fromRaw: String?,
    val fromTag: String?,
    val toRaw: String?,
    val toTag: String?,
    val cseq: CSeqRef?,
    val recordRoute: List<String>,
    val contact: List<String>,
    val headers: Map<String, List<String>>,
    val diagnostics: List<String>
) {
    val method: String? get() = (startLine as? StartLine.Request)?.method
    val statusCode: Int? get() = (startLine as? StartLine.Status)?.statusCode
    val topBranch: String? get() = via.firstOrNull()?.branch
}

@Serializable
data class ImportIssue(val path: String, val message: String, val severity: String = "ERROR")

@Serializable
data class ImportReport(
    val committed: Boolean,
    val versionId: String?,
    val acceptedMessages: List<String>,
    val rejectedMessages: List<String>,
    val issues: List<ImportIssue>,
    val diagnostics: List<String>
)

@Serializable
data class ProjectMeta(
    val projectId: String,
    val name: String,
    val seed: Long,
    val timeUnit: String = "ms",
    val baseEpochMs: Long? = null,
    val uiState: String? = null
)
