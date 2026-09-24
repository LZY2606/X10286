package forensics.sip.engine

import forensics.sip.sip.Direction
import forensics.sip.sip.Identity
import forensics.sip.sip.IndexedMessage
import forensics.sip.sip.Transport
import kotlinx.serialization.Serializable

@Serializable
data class RetransmitGroup(
    val id: String,
    val role: String, // REQUEST_UDP / RESPONSE_UDP / ATTEMPT_TCP / NON_MATCH
    val callId: String,
    val branch: String,
    val method: String,
    val direction: Direction,
    val memberMessageIds: List<String>,
    val gapsVirtualMs: List<Long>,
    val connectionGenerations: List<Long>,
    val connectionIds: List<String>,
    val sameConnectionReused: Boolean,
    val note: String,
    val directSources: List<String>,
    val algorithmVersion: String,
    val paramFingerprint: String,
)

@Serializable
data class ConnectionLineage(
    val connectionId: String,
    val transport: Transport,
    val generations: List<GenerationView>,
    val messageIds: List<String>,
    val identityWarning: String?,
)

@Serializable
data class GenerationView(
    val generation: Long,
    val eventIds: List<String>,
    val messageIds: List<String>,
    val openedAtTicksUtc: Long?,
    val closedAtTicksUtc: Long?,
)

class RetransmissionAnalyzer(private val params: EngineParams) {
    fun analyze(messages: List<IndexedMessage>): Pair<List<RetransmitGroup>, List<ConnectionLineage>> {
        val groups = linkedMapOf<String, MutableList<IndexedMessage>>()
        for (msg in messages) {
            val p = msg.parsed
            val branch = p.vias.firstOrNull()?.branch ?: continue
            val callId = p.callId ?: continue
            val isRequest = p.method != null
            val methodOrStatus = if (isRequest) p.method!! else "S${p.statusCode ?: 0}"
            // ACK for 2xx uses a distinct branch; only same-branch same-method retransmits match.
            val key = listOf(
                msg.record.direction.name,
                callId,
                branch,
                p.cseq?.seq?.toString() ?: "?",
                methodOrStatus,
                p.statusCode?.toString() ?: "REQ",
            ).joinToString("|")
            groups.getOrPut(key) { mutableListOf() }.add(msg)
        }
        val out = mutableListOf<RetransmitGroup>()
        for ((key, members0) in groups) {
            val members = members0.sortedWith(compareBy({ it.record.receivedAtTicksUtc }, { it.record.ordinal }))
            val first = members.first()
            val sameConn = members.map { it.record.connectionId }.distinct().size == 1
            val gens = members.map { it.record.connectionGeneration }.distinct().sorted()
            val role = when {
                first.record.transport == Transport.UDP && members.size > 1 ->
                    if (first.parsed.method != null) "REQUEST_UDP" else "RESPONSE_UDP"
                first.record.transport != Transport.UDP && members.size > 1 && gens.size > 1 -> "ATTEMPT_TCP"
                members.size > 1 -> "NON_MATCH"
                else -> continue
            }
            if (role == "NON_MATCH") {
                out += RetransmitGroup(
                    id = "rt:" + Identity.sha256Hex(key).take(16),
                    role = role,
                    callId = first.parsed.callId!!,
                    branch = first.parsed.vias.first().branch!!,
                    method = first.parsed.method ?: ("STATUS-${first.parsed.statusCode}"),
                    direction = first.record.direction,
                    memberMessageIds = members.map { it.record.id },
                    gapsVirtualMs = gaps(members),
                    connectionGenerations = gens,
                    connectionIds = members.map { it.record.connectionId }.distinct(),
                    sameConnectionReused = sameConn,
                    note = "identity match across distinct TCP generations is NOT merged: message identity survives connection reuse",
                    directSources = members.map { it.record.id },
                    algorithmVersion = params.algorithmVersion,
                    paramFingerprint = params.fingerprint(),
                )
            } else {
                val gaps = gaps(members)
                val note = when (role) {
                    "REQUEST_UDP", "RESPONSE_UDP" -> "UDP duplicate on virtual clock: gapsMs=$gaps; explanation uses recorded ticks, not wall clock"
                    else -> "TCP reconnect: same logical identity on a new connection generation; bytes remain distinct messages"
                }
                out += RetransmitGroup(
                    id = "rt:" + Identity.sha256Hex(key).take(16),
                    role = role,
                    callId = first.parsed.callId!!,
                    branch = first.parsed.vias.first().branch!!,
                    method = first.parsed.method ?: ("STATUS-${first.parsed.statusCode}"),
                    direction = first.record.direction,
                    memberMessageIds = members.map { it.record.id },
                    gapsVirtualMs = gaps,
                    connectionGenerations = gens,
                    connectionIds = members.map { it.record.connectionId }.distinct(),
                    sameConnectionReused = sameConn,
                    note = note,
                    directSources = members.map { it.record.id },
                    algorithmVersion = params.algorithmVersion,
                    paramFingerprint = params.fingerprint(),
                )
            }
        }
        val lineage = connections(messages)
        return out.sortedBy { it.memberMessageIds.first() } to lineage
    }

    private fun gaps(members: List<IndexedMessage>): List<Long> {
        val t = members.map { it.record.receivedAtTicksUtc }
        return t.zipWithNext { a, b -> (b - a) / 1_000_000 }
    }

    private fun connections(messages: List<IndexedMessage>): List<ConnectionLineage> {
        val byConn = messages.groupBy { it.record.connectionId }
        return byConn.map { (connId, msgs0) ->
            val msgs = msgs0.sortedBy { it.record.receivedAtTicksUtc }
            val gens = msgs.groupBy { it.record.connectionGeneration }.map { (gen, gm) ->
                GenerationView(
                    generation = gen,
                    eventIds = emptyList(),
                    messageIds = gm.map { it.record.id },
                    openedAtTicksUtc = gm.minOf { it.record.receivedAtTicksUtc },
                    closedAtTicksUtc = gm.maxOf { it.record.receivedAtTicksUtc },
                )
            }.sortedBy { it.generation }
            val warning = if (gens.size > 1)
                "connection reused across ${gens.size} generations: transport reuse does not erase message identity"
            else null
            ConnectionLineage(connId, msgs.first().record.transport, gens,
                msgs.map { it.record.id }, warning)
        }.sortedBy { it.connectionId }
    }
}
