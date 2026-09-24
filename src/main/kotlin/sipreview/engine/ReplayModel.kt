package sipreview.engine

import sipreview.domain.Direction
import sipreview.domain.RawVersion
import sipreview.domain.SipParsed
import sipreview.domain.Transport

/** One ordered observation bound to its immutable raw version and parsed headers. */
data class Observed(
    val raw: RawVersion,
    val parsed: SipParsed
) {
    val id get() = raw.id
    val atMs get() = raw.receivedAtMillis
    val direction get() = raw.direction
    val transport get() = raw.transport
}

data class TimerFire(
    val name: String,
    val atMs: Long,
    val subjectId: String,
    val kind: String
)

data class Candidate(
    val id: String,
    val kind: String,
    val subjectId: String,
    val label: String,
    val status: String,
    val atMs: Long,
    val sources: List<String>,
    val payload: Map<String, Any?>,
    val rejectedReason: String? = null
)

data class ReplayResult(
    val observed: List<Observed>,
    val transactions: List<Map<String, Any?>>,
    val dialogs: List<Map<String, Any?>>,
    val timers: List<Map<String, Any?>>,
    val candidates: List<Candidate>,
    val events: List<Map<String, Any?>>,
    val connections: List<Map<String, Any?>>,
    val unknowns: List<Map<String, Any?>>
) {
    fun findByStableId(stableId: String): Map<String, Any?>? {
        return transactions.firstOrNull { it["id"] == stableId }
            ?: dialogs.firstOrNull { it["id"] == stableId }
            ?: candidates.firstOrNull { it.id == stableId }?.let {
                mapOf("id" to it.id, "kind" to it.kind, "label" to it.label,
                    "status" to it.status, "payload" to it.payload)
            }
            ?: timers.firstOrNull { it["id"] == stableId }
            ?: unknowns.firstOrNull { it["id"] == stableId }
    }
}

data class SnapshotInputs(
    val projectId: String,
    val runId: String,
    val raw: List<RawVersion>,
    val paramFingerprint: String,
    val params: TimerParams,
    val decisions: List<sipreview.domain.Decision>
)
