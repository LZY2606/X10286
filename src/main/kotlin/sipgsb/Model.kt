package sipgsb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JsonFmt = Json { encodeDefaults = true; prettyPrint = false }

@Serializable
enum class Direction { C2S, S2C }

@Serializable
data class Params(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val t4Ms: Long = 5000,
    val note: String = "",
) {
    fun fingerprint(): String = sha1("t1=$t1Ms,t2=$t2Ms,t4=$t4Ms").take(12)
}

@Serializable
data class SipInput(
    val id: String,
    val version: Int,
    val parentId: String?,
    val direction: Direction,
    val transport: String,
    val connectionId: String,
    val connGeneration: Int,
    val recvTimeMs: Long,
    val raw: String,
    val createdEvent: Long,
)

@Serializable
data class Decision(
    val id: String,
    val eventSeq: Long,
    val kind: String,
    val targetId: String,
    val rationale: String,
    val revertedEvent: Long? = null,
)

@Serializable
data class EventRow(
    val seq: Long,
    val kind: String,
    val payload: String,
    val note: String,
)

@Serializable
data class ParamsRow(
    val id: String,
    val eventSeq: Long,
    val params: Params,
    val note: String,
)

@Serializable
data class ResultRow(
    val id: String,
    val kind: String,
    val subjectId: String,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val sourceIds: List<String>,
    val payload: String,
    val stale: Boolean,
    val createdEvent: Long,
    val supersededEvent: Long?,
)

@Serializable
data class ExportBundle(
    val format: String,
    val inputs: List<SipInput>,
    val params: List<ParamsRow>,
    val decisions: List<Decision>,
    val events: List<EventRow>,
)
