package sipreview.domain

/** Direction of an observed SIP message on the wire. */
enum class Direction { INGRESS, EGRESS }

/** Transport family captured for a connection generation. */
enum class Transport { UDP, TCP, TLS }

/**
 * Immutable raw observation, exactly as imported. The byte representation is never rewritten;
 * later corrections are expressed as a new [RawVersion] pointing at a parent version.
 */
data class RawVersion(
    val id: String,
    val ordinal: Long,
    val projectId: String,
    val batchId: String,
    val direction: Direction,
    val transport: Transport,
    val connectionId: String,
    val connectionGeneration: Int,
    val localAddress: String,
    val remoteAddress: String,
    val receivedAtMillis: Long,
    val clockSource: String,
    val rawBytesBase64: String,
    val rawText: String,
    val label: String?,
    val parentVersionId: String?,
    val superseded: Boolean,
    val submittedAtMillis: Long,
    val note: String?
) {
    val current: Boolean get() = !superseded
}

/** One source batch (copy-paste sample) submitted atomically. */
data class SourceBatch(
    val id: String,
    val projectId: String,
    val name: String,
    val formatVersion: String,
    val submittedAtMillis: Long,
    val itemCount: Int,
    val parseDiagnostics: List<String>
)

/** Timer/algorithm parameters. Fingerprints make every derived value traceable to exact params. */
data class ParameterSet(
    val id: String,
    val projectId: String,
    val name: String,
    val values: Map<String, String>,
    val fingerprint: String,
    val parentId: String?,
    val createdAtMillis: Long,
    val note: String?
) {
    companion object {
        const val ALGORITHM_VERSION = "sip-replay-1.0.0"
    }
}

/** A human adjudication. Revisions and conflicts are appended, never overwritten. */
data class Decision(
    val id: String,
    val projectId: String,
    val targetId: String,
    val targetKind: String,
    val verdict: String,
    val rationale: String,
    val conflictsWith: String?,
    val createdAtMillis: Long,
    val supersededBy: String?
)

/**
 * A derived row: candidate, rejected candidate, timer firing, dialog, route set, ...
 * Provenance chain (direct sources, parameter fingerprint, algorithm version) is mandatory.
 */
data class DerivedRow(
    val id: String,
    val projectId: String,
    val runId: String,
    val kind: String,
    val status: String,
    val subjectId: String,
    val label: String,
    val payloadJson: String,
    val directSources: List<String>,
    val parentDerivedId: String?,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val createdAtMillis: Long,
    val stale: Boolean,
    val staleReason: String?
)

/** Machine-readable domain event (state transition, timer, provenance judgement). */
data class DomainEvent(
    val id: Long,
    val projectId: String,
    val runId: String,
    val seq: Long,
    val atMillis: Long,
    val type: String,
    val subjectId: String,
    val payloadJson: String,
    val sources: List<String>,
    val decisionRef: String?
)

/** One computation run over a snapshot; replaying runs reconstructs historical conclusions. */
data class RunRecord(
    val id: String,
    val projectId: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val rawHeadVersion: String?,
    val paramId: String,
    val decisionHeadId: String?,
    val eventCount: Long,
    val note: String?
)

/** Counterexample archive entry: baseline + one direct-source mutation, adopted and rejected. */
data class ArchiveEntry(
    val id: String,
    val projectId: String,
    val scenario: String,
    val title: String,
    val baselineId: String,
    val mutatedSourceId: String,
    val mutation: String,
    val adoptedResultJson: String,
    val rejectedResultsJson: String,
    val judgementEventsJson: String,
    val blastRadiusJson: String,
    val createdAtMillis: Long
)

data class Project(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val headParamId: String,
    val headRawVersionId: String?,
    val headDecisionId: String?,
    val description: String?
)

/** Parsed SIP headers (derived during import validation but original bytes stay authoritative). */
data class SipParsed(
    val method: String?,
    val statusCode: Int?,
    val reason: String?,
    val viaBranch: String?,
    val viaTop: String?,
    val callId: String?,
    val fromTag: String?,
    val toTag: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val recordRoutes: List<String>,
    val contact: String?,
    val fromUri: String?,
    val toUri: String?,
    val topViaRport: String?,
    val contentLength: Int?,
    val parseErrors: List<String>
) {
    val isRequest: Boolean get() = method != null
    val isResponse: Boolean get() = statusCode != null
}
