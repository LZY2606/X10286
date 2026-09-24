package sipreview.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 直接来源：任何派生值都能回到具体记录/参数/决定与算法版本。 */
@Serializable
data class Provenance(
    val algoVersion: String,
    val paramFingerprint: String,
    val sources: List<String> = emptyList(),   // RawRecord.id / Decision.id
    val paramRevisionId: String,
    val step: String,                          // 计算步骤名
)

@Serializable
data class Candidate(
    val id: String,
    val candidateType: String,                 // transaction | dialog | route_set | ownership
    val summary: String,
    val status: String,                        // proposed | accepted | rejected
    val confidence: String,                    // certain | ambiguous
    val rejectReason: String? = null,
    val rejectedByDecision: String? = null,
    val sources: List<String> = emptyList(),
)

/** 事务某一时刻的状态证据；status 是状态机状态，unknown 表示输入不足以唯一决定。 */
@Serializable
data class TransactionState(
    val id: String,                            // 稳定身份 tx:<branch>:<cseqMethod>
    val branch: String,
    val method: String,
    val role: String,                          // client | server（视角）
    val state: String,
    val candidates: List<Candidate> = emptyList(),
    val sources: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class TimerEvent(
    val id: String,
    val txId: String,
    val name: String,                          // Timer A/B/E/F/G/H/K/I/J
    val fireAtNanos: Long?,                    // 虚拟时钟；null 表示无法唯一决定
    val armedAtNanos: Long,
    val durationMs: Long?,                     // 携带单位基准：毫秒
    val basis: String,                         // RFC 依据
    val state: String,                         // armed | fired | cancelled | unknown
    val sources: List<String>,
)

@Serializable
data class RetransmitEvent(
    val id: String,
    val originalId: String,
    val retransId: String,
    val gapMs: Long,
    val explained: Boolean,                    // 虚拟时钟下是否落在期望重传点
    val basis: String,
)

@Serializable
data class DialogNode(
    val id: String,                            // 稳定身份
    val callId: String,
    val phase: String,                         // early | confirmed
    val localTag: String?,
    val remoteTag: String,
    val localTarget: String?,
    val remoteTarget: String?,
    val cseq: Long?,
    val routeSet: List<String>,
    val parentId: String?,                     // 谱系：confirmed -> early
    val sources: List<String>,
    val candidates: List<Candidate> = emptyList(),
    val note: String = "",
)

@Serializable
data class OwnershipClaim(
    val id: String,
    val messageId: String,
    val claimedBy: List<String>,               // 候选事务 id
    val resolved: String?,
    val basis: String,
    val rejected: List<Pair<String, String>> = emptyList(),
)

@Serializable
data class ConnectionEra(
    val connectionId: String,
    val generation: Long,
    val transport: String,
    val openedAtNanos: Long?,
    val closedAtNanos: Long?,
    val recordIds: List<String>,
    val note: String = "",
)

@Serializable
data class EngineRun(
    val runId: String,
    val projectId: String,
    val paramRevisionId: String,
    val inputRecordIds: List<String>,         // 本次使用的输入版本集合（最新修订）
    val transactions: List<TransactionState>,
    val dialogs: List<DialogNode>,
    val timers: List<TimerEvent>,
    val retransmits: List<RetransmitEvent>,
    val ownership: List<OwnershipClaim>,
    val connections: List<ConnectionEra>,
    val candidates: List<Candidate>,
    val staleness: JsonObject? = null,        // 局部输入变化 -> 过期对象映射
)
