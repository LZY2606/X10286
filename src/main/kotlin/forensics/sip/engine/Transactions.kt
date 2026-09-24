package forensics.sip.engine

import forensics.sip.sip.CSeq
import forensics.sip.sip.Direction
import forensics.sip.sip.MessageRecord
import forensics.sip.sip.ParsedSip
import forensics.sip.sip.Transport
import kotlinx.serialization.Serializable

enum class TxRole { CLIENT, SERVER }

enum class TxPhase {
    PROPOSED,
    TRYING,
    PROCEEDING,
    COMPLETED,
    CONFIRMED,
    TERMINATED,
    CANCEL_PROCEEDING,
    CANCEL_COMPLETED,
    UNKNOWN,
}

@Serializable
data class TxObservation(
    val atTicksUtc: Long,
    val phase: TxPhase,
    val messageId: String?,
    val event: String,
    val timerName: String? = null,
    val timerDurationMs: Long? = null,
    val note: String? = null,
    val candidates: List<String> = emptyList(),
    val rejectedReasons: List<String> = emptyList(),
)

@Serializable
data class TransactionView(
    val id: String,
    val role: TxRole,
    val invite: Boolean,
    val branch: String,
    val callId: String,
    val cseq: Long,
    val method: String,
    val topVia: String,
    val transport: Transport,
    val phase: TxPhase,
    val sourceMessageIds: List<String>,
    val observations: List<TxObservation>,
    val matchedMessageIds: List<String>,
    val timers: List<TimerView>,
    val uncertainty: List<String>,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,
    val derivedFromRun: Long,
    val stale: Boolean = false,
) {
    fun finalResponseCode(): Int? = observations.lastOrNull { it.event == "RESPONSE" }
        ?.let { obs -> matchedMessageIds.mapNotNull { null }.let { null } }
}

@Serializable
data class TimerView(
    val name: String,
    val armedAtTicksUtc: Long,
    val durationMs: Long,
    val firesAtTicksUtc: Long,
    val elapsedMs: Long,
    val state: String, // ARMED, FIRED, CANCELLED
    val reason: String,
    val sourceMessageIds: List<String>,
)

data class IndexedMessage(
    val record: MessageRecord,
    val parsed: ParsedSip,
    val parseDiags: List<String>,
)

class TransactionReconstructor(private val params: EngineParams) {

    fun reconstruct(messages: List<IndexedMessage>, runId: Long): List<TransactionView> {
        val byId = messages.associateBy { it.record.id }
        val txs = linkedMapOf<String, TxBuilder>()

        fun builderFor(msg: IndexedMessage, create: Boolean): TxBuilder? {
            val p = msg.parsed
            val direction = msg.record.direction
            val isRequest = p.method != null
            val branch = p.vias.firstOrNull()?.branch
            val callId = p.callId
            val cseq = p.cseq
            if (branch == null || callId == null || cseq == null) return null
            val role = if (direction == Direction.SENT) TxRole.CLIENT else TxRole.SERVER
            val isAck = isRequest && p.method == "ACK"
            val isCancel = isRequest && p.method == "CANCEL"
            val methodKey = when {
                isAck && role == TxRole.CLIENT -> "INVITE"
                else -> p.method ?: cseq.method
            }
            val key = listOf(role, branch, callId, cseq.seq, methodKey).joinToString("|")
            return if (create) txs.getOrPut(key) {
                TxBuilder(key, role, methodKey == "INVITE", branch, callId, cseq.seq, methodKey,
                    p.vias.first().raw, msg.record.transport, runId, params)
            } else txs[key]
        }

        for (msg in messages.sortedWith(compareBy({ it.record.receivedAtTicksUtc }, { it.record.ordinal }))) {
            val p = msg.parsed
            val isRequest = p.method != null
            val builder = builderFor(msg, create = isRequest)
            if (builder == null) {
                if (!isRequest) {
                    // orphan response: keep uncertainty visible at message level, not as a fabricated tx
                    continue
                }
                continue
            }
            builder.matched += msg.record.id
            val t = msg.record.receivedAtTicksUtc
            when {
                isRequest -> handleRequest(builder, msg, txs, byId)
                p.statusCode != null -> handleResponse(builder, msg, t)
            }
        }

        return txs.values.map { it.build() }
    }

    private fun handleRequest(b: TxBuilder, msg: IndexedMessage, all: Map<String, TxBuilder>, byId: Map<String, IndexedMessage>) {
        val p = msg.parsed
        val t = msg.record.receivedAtTicksUtc
        when (p.method) {
            "ACK" -> {
                b.obs(t, TxPhase.CONFIRMED, msg.record.id, "ACK_MATCH",
                    candidates = b.matched,
                    rejectedReasons = if (b.role == TxRole.SERVER) listOf("server-side 2xx ACK belongs to confirmed dialog, not a standalone transaction") else emptyList(),
                    note = if (b.role == TxRole.CLIENT) "2xx ACK has its own transaction key space when branch differs" else null)
                if (b.role == TxRole.CLIENT && b.invite && b.phase == TxPhase.COMPLETED) {
                    b.phase = TxPhase.CONFIRMED
                    b.armTimer("TIMER_D", params.timerDMs, t, "post-2xx ACK wait", msg.record.id)
                }
            }
            "CANCEL" -> {
                if (b.phase == TxPhase.COMPLETED || b.phase == TxPhase.CONFIRMED) {
                    b.uncertain += "CANCEL race: original INVITE already completed; CANCEL has no transaction to cancel"
                    b.obs(t, TxPhase.TERMINATED, msg.record.id, "CANCEL_REJECTED",
                        rejectedReasons = listOf("original transaction ${b.phase} before CANCEL"))
                } else {
                    b.obs(t, b.phase, msg.record.id, "CANCEL_SENT", note = "CANCEL is its own transaction and shares branch with INVITE")
                    b.cancelRequested = true
                }
            }
            else -> {
                b.obs(t, TxPhase.PROCEEDING, msg.record.id, "REQUEST")
                b.phase = TxPhase.PROCEEDING
                armRequestTimers(b, t, msg.record.id)
            }
        }
    }

    private fun armRequestTimers(b: TxBuilder, t: Long, msgId: String) {
        when {
            b.role == TxRole.CLIENT && b.invite -> {
                b.armTimer("TIMER_A", params.timerAMs, t, "INVITE UDP retransmission", msgId)
                b.armTimer("TIMER_B", params.timerBMs, t, "transaction timeout", msgId)
            }
            b.role == TxRole.CLIENT -> {
                b.armTimer("TIMER_E", params.timerEMs, t, "non-INVITE UDP retransmission", msgId)
                b.armTimer("TIMER_F", params.timerFMs, t, "non-INVITE timeout", msgId)
            }
            b.role == TxRole.SERVER && b.invite -> {
                b.armTimer("TIMER_G", params.timerGMs, t, "INVITE server retransmission after 2xx", msgId)
                b.armTimer("TIMER_H", params.timerHMs, t, "INVITE server timeout", msgId)
                b.armTimer("TIMER_I", params.timerIMs, t, "wait ACK (reliable)", msgId)
            }
            b.role == TxRole.SERVER -> {
                b.armTimer("TIMER_J", params.timerJMs, t, "non-INVITE completed retransmit", msgId)
            }
        }
    }

    private fun handleResponse(b: TxBuilder, msg: IndexedMessage, t: Long) {
        val code = msg.parsed.statusCode ?: return
        when {
            code in 100..199 -> {
                if (b.phase in setOf(TxPhase.PROPOSED, TxPhase.TRYING)) b.phase = TxPhase.PROCEEDING
                b.obs(t, b.phase, msg.record.id, "PROVISIONAL_RESPONSE", code = code)
                b.cancelTimer("TIMER_A"); b.cancelTimer("TIMER_E")
                if (b.role == TxRole.SERVER) {
                    b.armTimer("TIMER_G", params.timerGMs, t, "INVITE server retransmission", msg.record.id)
                }
            }
            code in 200..299 -> {
                if (b.invite) {
                    if (b.cancelRequested) {
                        b.uncertain += "LATE_2XX race: 2xx arrived after CANCEL; response still creates dialog, CANCEL cannot recall it"
                        b.obs(t, TxPhase.COMPLETED, msg.record.id, "LATE_FINAL_RESPONSE", code = code,
                            candidates = listOf("accept-late-2xx", "reject-late-2xx"),
                            rejectedReasons = listOf("CANCEL only suppresses non-2xx answers; a 2xx cannot be recalled"))
                    } else {
                        b.obs(t, TxPhase.COMPLETED, msg.record.id, "FINAL_RESPONSE", code = code)
                    }
                    b.phase = TxPhase.COMPLETED
                    b.cancelTimer("TIMER_B"); b.cancelTimer("TIMER_H")
                    if (b.role == TxRole.SERVER) {
                        b.cancelTimer("TIMER_G")
                        b.armTimer("TIMER_I", params.timerIMs, t, "wait ACK", msg.record.id)
                    }
                } else {
                    b.phase = TxPhase.COMPLETED
                    b.obs(t, TxPhase.COMPLETED, msg.record.id, "FINAL_RESPONSE", code = code)
                    b.cancelTimer("TIMER_F")
                    armCompleted(b, t, msg.record.id)
                }
            }
            code in 300..699 -> {
                b.phase = TxPhase.COMPLETED
                b.obs(t, TxPhase.COMPLETED, msg.record.id, "FINAL_RESPONSE", code = code)
                b.cancelTimer("TIMER_A"); b.cancelTimer("TIMER_B")
                b.cancelTimer("TIMER_E"); b.cancelTimer("TIMER_F")
                b.cancelTimer("TIMER_H")
                armCompleted(b, t, msg.record.id)
            }
        }
    }

    private fun armCompleted(b: TxBuilder, t: Long, msgId: String) {
        val reliable = b.transport != Transport.UDP
        if (b.role == TxRole.CLIENT) {
            b.armTimer(if (b.invite) "TIMER_D" else "TIMER_K",
                if (reliable) 0 else if (b.invite) params.timerDMs else params.timerKMs,
                t, "completed wait", msgId)
        } else {
            b.armTimer(if (b.invite) "TIMER_H" else "TIMER_J",
                if (reliable) 0 else if (b.invite) params.timerHMs else params.timerJMs,
                t, "completed server wait", msgId)
        }
    }
}

private class TxBuilder(
    val id: String,
    val role: TxRole,
    val invite: Boolean,
    val branch: String,
    val callId: String,
    val cseq: Long,
    val method: String,
    val topVia: String,
    val transport: Transport,
    val runId: Long,
    val params: EngineParams,
) {
    var phase: TxPhase = TxPhase.PROPOSED
    val source = linkedSetOf<String>()
    val observations = mutableListOf<TxObservation>()
    val matched = mutableListOf<String>()
    val timers = linkedMapOf<String, TimerView>()
    val uncertainty = mutableListOf<String>()
    var cancelRequested = false

    fun obs(t: Long, ph: TxPhase, msgId: String?, event: String,
            timerName: String? = null, timerDurationMs: Long? = null,
            note: String? = null, code: Int? = null,
            candidates: List<String> = emptyList(), rejectedReasons: List<String> = emptyList()) {
        phase = ph
        observations += TxObservation(t, ph, msgId, event + (code?.let { ":$it" } ?: ""),
            timerName, timerDurationMs, note, candidates, rejectedReasons)
        if (msgId != null) source += msgId
    }

    fun armTimer(name: String, durationMs: Long, now: Long, reason: String, msgId: String) {
        timers[name] = TimerView(name, now, durationMs, now + durationMs, 0,
            if (durationMs == 0L) "FIRED" else "ARMED", reason, listOf(msgId))
    }

    fun cancelTimer(name: String) {
        val cur = timers[name] ?: return
        timers[name] = cur.copy(state = "CANCELLED")
    }

    fun build(): TransactionView = TransactionView(
        id, role, invite, branch, callId, cseq, method, topVia, transport, phase,
        source.toList(), observations, matched, timers.values.toList(), uncertainty,
        params.algorithmVersion, params.fingerprint(), source.toList(), runId,
    )
}
