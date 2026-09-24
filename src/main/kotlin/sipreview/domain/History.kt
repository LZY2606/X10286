package sipreview.domain

import kotlinx.serialization.Serializable

@Serializable
enum class EventType {
    PROJECT_CREATED,
    SOURCE_BATCH,
    SOURCE_REVISION,
    PARAM_REVISION,
    DECISION,
    DECISION_UNDONE,
}

@Serializable
data class SourceLineage(
    /** Parent record id this revision replaces (null for first import). */
    val supersedesRecordId: String?,
    /** Parent event id in the event chain. */
    val parentEventId: String,
    val batchLabel: String,
)

@Serializable
data class EventEnvelope(
    val eventId: String,
    val seq: Int,
    val type: EventType,
    val atWallIso: String,
    /** Virtual-clock time for replay ordering, when applicable. */
    val atVirtualMs: Long? = null,
    val label: String,
    val parentEventId: String?,
    val payloadJson: String,
    /** Fingerprint of the effective source set + params after this event. */
    val sourceFingerprint: String,
    val paramsFingerprint: String,
)

/** Mutable tunable parameters of the reconstruction algorithms. */
@Serializable
data class AlgoParams(
    /** RFC 3261 Timer T1 in ms (virtual clock). Default 500. */
    val timerT1Ms: Long = 500L,
    /** RFC 3261 Timer B in ms. */
    val timerBMs: Long = 32_000L,
    val timerHMs: Long = 32_000L,
    val nonInviteTimerBms: Long = 32_000L,
    val notes: String? = null,
) {
    fun fingerprint(): String {
        val raw = "t1=$timerT1Ms;b=$timerBMs;h=$timerHMs;nb=$nonInviteTimerBms"
        return "fp_" + sha1(raw).take(12)
    }
}

@Serializable
data class ParamRevision(
    val newParams: AlgoParams,
    val reason: String,
)

@Serializable
data class SourceRevision(
    val record: ImportRecord,
    val supersedesRecordId: String,
    val reason: String,
)

@Serializable
enum class DecisionVerb { ACCEPT, REJECT, RESOLVE, PARAM_ADJUST }

@Serializable
data class DecisionPayload(
    val verb: DecisionVerb,
    val candidateId: String,
    val chosenObjectId: String? = null,
    val note: String,
    /** Id of the conflicting decision this supersedes, when resolving a conflict. */
    val conflictsWithEventId: String? = null,
)

@Serializable
data class UndoPayload(val undoneEventId: String, val reason: String)

@Serializable
data class ProjectInfo(
    val projectId: String,
    val name: String,
    val createdAtIso: String,
    val seed: Long,
)

@Serializable
data class EventView(
    val eventId: String,
    val seq: Int,
    val type: EventType,
    val atWallIso: String,
    val atVirtualMs: Long?,
    val label: String,
    val parentEventId: String?,
    val sourceFingerprint: String,
    val paramsFingerprint: String,
)

/** Immutable counterexample bundle: baseline + single-source mutations + retained verdicts. */
@Serializable
data class CounterexampleBaseline(
    val rawBytesByRecord: Map<String, String>,
    val viaBranchByRecord: Map<String, String?>,
    val callIdByRecord: Map<String, String?>,
    val fromTagByRecord: Map<String, String?>,
    val toTagByRecord: Map<String, String?>,
    val cseqByRecord: Map<String, String?>,
    val recordRouteByRecord: Map<String, List<String>>,
    val connectionGenerationByRecord: Map<String, String?>,
    val receivedAtByRecord: Map<String, TimeCoordinate>,
    val directionByRecord: Map<String, Direction>,
)

@Serializable
data class TimeCoordinate(val value: Long, val basis: String)

@Serializable
data class CounterexampleMutation(
    val id: String,
    val scenario: FindingClass,
    val directSourceRecordId: String,
    val fieldChanged: String,
    val before: String,
    val after: String,
    val affectedObjectIds: List<String>,
    val unaffectedObjectIds: List<String>,
    val impactContained: Boolean,
    val expectedBoundary: String,
    val adopted: Boolean,
    val note: String,
)

@Serializable
data class CounterexampleArchive(
    val projectId: String,
    val baselineFingerprint: String,
    val baseline: CounterexampleBaseline,
    val mutations: List<CounterexampleMutation>,
    val retainedJudgments: List<JudgmentEvent>,
)

internal fun sha1(text: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
