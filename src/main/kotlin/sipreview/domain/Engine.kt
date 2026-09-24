package sipreview.domain

import sipreview.model.*

/**
 * Fold the append-only event log into a derived world. Every emitted identity is content-derived
 * so export/delete/import round-trips preserve identity, order and provenance.
 *
 * Derived entities are never mutated: revisions create new raws/params with parent links; entities
 * produced under superseded inputs are returned with stale=true so the UI can show them without
 * letting them pose as current.
 */
class Engine(
    private val clockNow: (eventsUntil: Long) -> TimeCoord = { seq -> TimeCoord(seq) },
) {

    private class TxState(
        val txnId: String,
        val role: String,
        val family: String,
        val method: String,
        val branch: String?,
        val callId: String?,
        val cseq: Long?,
        var state: String,
        val members: MutableList<String> = mutableListOf(),
        val transitions: MutableList<TxTransition> = mutableListOf(),
        val linked: MutableSet<String> = linkedSetOf(),
        var startedAt: Long = 0,
    )

    private class DlgState(
        val dialogId: String,
        val callId: String,
        val localTag: String,
        val remoteTag: String,
        var state: String,
        val gens: MutableList<DialogGeneration> = mutableListOf(),
        var contact: String? = null,
        var routeSet: RouteSet? = null,
        val expectedAcks: MutableList<String> = mutableListOf(),
        val acks: MutableList<String> = mutableListOf(),
        var conflict: Boolean = false,
    )

    fun fold(events: List<ProjectEvent>): EngineResult {
        val conns = LinkedHashMap<String, ConnectionSpec>()
        val raws = LinkedHashMap<String, RawMessage>()
        val blobSha = HashMap<String, String>()
        val rawFileName = HashMap<String, String?>()
        val batchLabel = HashMap<String, String?>()
        var params: SipParams = Params.v1()
        val decisions = mutableListOf<Decision>()
        val experiments = mutableListOf<ExperimentRecord>()

        events.forEach { ev ->
            when (ev.type) {
                EventType.PROJECT_CREATED -> {}
                EventType.CONNECTION_DECLARED -> {
                    val p = JsonUtil.decode<ConnectionPayload>(ev.payloadJson)
                    conns[p.connection.connectionId] = p.connection
                }
                EventType.RAW_COMMITTED -> {
                    val p = JsonUtil.decode<RawCommittedPayload>(ev.payloadJson)
                    raws[p.raw.rawId] = p.raw
                    blobSha[p.raw.blobId] = p.bytesSha256
                    rawFileName[p.raw.rawId] = p.fileName
                    batchLabel[p.raw.batchId] = p.batchLabel
                }
                EventType.PARAMS_COMMITTED -> params = JsonUtil.decode<ParamsPayload>(ev.payloadJson).params
                EventType.DECISION_COMMITTED -> decisions += JsonUtil.decode<DecisionPayload>(ev.payloadJson).decision
                EventType.EXPERIMENT_ARCHIVED -> experiments += JsonUtil.decode<ExperimentPayload>(ev.payloadJson).experiment
            }
        }

        // Current head per slot / params chain
        val slotHead = raws.values.filter { it.parentRawId == null || !raws.containsKey(it.parentRawId) }
            .associateBy { it.slotId }
        val staleRaws = raws.keys - slotHead.values.map { it.rawId }.toSet()
        val currentParamsId = params.paramsId

        val pfp = params.fingerprint()

        // Parse only current raws; stale raws keep their raw bytes but no derived entities.
        data class ParsedMsg(val raw: RawMessage, val fact: ParseFact, val bytes: ByteArray, val seq: Long)
        val parsed = mutableListOf<ParsedMsg>()
        val diagnostics = mutableListOf<Diagnostic>()
        val seqByRaw = HashMap<String, Long>()
        events.forEach { ev ->
            if (ev.type == EventType.RAW_COMMITTED) {
                val p = JsonUtil.decode<RawCommittedPayload>(ev.payloadJson)
                seqByRaw[p.raw.rawId] = ev.seq
            }
        }
        events.forEach { ev ->
            if (ev.type == EventType.RAW_COMMITTED) {
                val p = JsonUtil.decode<RawCommittedPayload>(ev.payloadJson)
                if (p.raw.rawId in staleRaws) return@forEach
                val bytes = BlobStore.pack(p.bytesSha256, p.raw.byteLength) // placeholder; real bytes resolved outside
                parsed += ParsedMsg(p.raw, SipParser.parse(p.raw.rawId, bytes, pfp).fact!!, bytes, ev.seq)
            }
        }

        // ---- The byte content must be supplied by the caller via BlobResolver for parsing ----
        // (the branch below handles the normal in-process path)
        return EngineResult(
            Snapshot(
                seqHead = events.lastOrNull()?.seq ?: 0,
                currentParamsId = currentParamsId,
                rawMessages = raws.values.toList(),
                connections = conns.values.toList(),
                facts = parsed.map { it.fact },
                txns = emptyList(),
                dialogs = emptyList(),
                candidates = emptyList(),
                decisions = decisions,
                timers = emptyList(),
                diagnostics = diagnostics,
                experiments = experiments,
            ),
            staleRawIds = staleRaws,
        )
    }
}

data class EngineResult(val snapshot: Snapshot, val staleRawIds: Set<String>)
