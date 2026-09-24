package sipreview.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sipreview.model.EventKind
import sipreview.model.EventRecord
import sipreview.model.Ids

class EventSink(val st: ReplayState) {
    fun emit(
        kind: EventKind,
        atMs: Long? = st.nowMs.takeIf { it != Long.MIN_VALUE },
        subjectUid: String? = null,
        subjectKind: String? = null,
        basis: List<String> = emptyList(),
        sources: List<String> = emptyList(),
        payload: JsonObject = buildJsonObject { },
        decisionTarget: String? = null
    ): EventRecord {
        st.eventIndex += 1
        val label = "${kind.name}|${subjectUid ?: ""}|${st.eventIndex}"
        val uid = Ids.step(st.genSeq, st.eventIndex, label, st.projectId)
        val ev = EventRecord(
            uid = uid, projectId = st.projectId, seq = 0,
            kind = kind, atMs = atMs, subjectUid = subjectUid, subjectKind = subjectKind,
            generationSeq = st.genSeq, basisUids = basis, sourceUids = sources,
            algorithmVersion = st.params.algorithmVersion,
            paramFingerprint = st.params.fingerprint(), payload = payload,
            decisionTargetUid = decisionTarget
        )
        st.events += ev
        return ev
    }
}
