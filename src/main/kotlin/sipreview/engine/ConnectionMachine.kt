package sipreview.engine

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sipreview.model.*

class ConnectionMachine(val st: ReplayState, val sink: EventSink) {

    fun open(connId: String, generation: Int, transport: String, local: String?, remote: String?, atMs: Long, sourceUid: String) {
        val key = TxKeys.connectionKey(connId, generation)
        val prev = st.connections.values.filter { it.connectionId == connId && it.generation < generation }.maxByOrNull { it.generation }
        val c = MutableConnection(key).apply {
            this.connectionId = connId; this.generation = generation; this.transport = transport
            this.localEndpoint = local; this.remoteEndpoint = remote
            this.openedAtMs = atMs
            this.reusedAfter = prev?.uid(st.projectId)
        }
        st.connections[key] = c
        st.dep(key, sourceUid)
        sink.emit(if (prev != null) EventKind.CONN_REUSE else EventKind.CONN_OPENED, atMs,
            c.uid(st.projectId), "connection",
            basis = listOf(sourceUid),
            payload = buildJsonObject {
                put("connectionId", connId); put("generation", generation); put("transport", transport)
                put("local", local ?: ""); put("remote", remote ?: "")
                put("reopenedAfterConnUid", prev?.uid(st.projectId) ?: "")
                put("identityNote", "same connection id across reconnect is a new generation; message uids are never merged")
            })
    }

    fun close(connId: String, generation: Int, atMs: Long, sourceUid: String) {
        val key = TxKeys.connectionKey(connId, generation)
        val c = st.connections[key] ?: return
        c.closedAtMs = atMs
        st.dep(key, sourceUid)
        sink.emit(EventKind.CONN_CLOSED, atMs, c.uid(st.projectId), "connection",
            basis = listOf(sourceUid),
            payload = buildJsonObject { put("connectionId", connId); put("generation", generation) })
    }

    fun attach(msg: ParsedMessage) {
        val connId = msg.connectionId ?: return
        val gen = msg.connectionGeneration ?: 1
        val key = TxKeys.connectionKey(connId, gen)
        val c = st.connections.getOrPut(key) {
            val synthetic = MutableConnection(key).apply {
                this.connectionId = connId; this.generation = gen
                this.transport = msg.via.firstOrNull()?.transport ?: "UDP"
                this.openedAtMs = msg.receivedAtMs
            }
            sink.emit(EventKind.CONN_OPENED, msg.receivedAtMs, synthetic.uid(st.projectId), "connection",
                basis = listOf(msg.materialUid),
                payload = buildJsonObject {
                    put("connectionId", connId); put("generation", gen)
                    put("synthetic", true)
                    put("note", "no explicit CONN record; inferred from message metadata")
                })
            synthetic
        }
        c.messageUids += msg.materialUid
        st.dep(key, msg.materialUid)
    }
}
