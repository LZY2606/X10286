package review.engine

import review.*

/** 一次重放的输入（来自截至 basisEventId 的全部事件）。 */
data class EngineInput(
    val params: EngineParams,
    val messages: List<MessageRecord>,
    val connections: List<ConnectionRecord>,
    val decisions: List<Decision>,
)

data class Decision(
    val targetType: String,
    val targetId: String,
    val decision: DecisionKind,
    val reason: String,
    val eventSeq: Long,
    val revoked: Boolean = false,
)

data class EngineOutput(
    val transactions: List<TransactionRecord>,
    val dialogs: List<DialogRecord>,
    val candidates: List<CandidateRecord>,
    val timers: List<TimerDecisionRecord>,
    val retransmits: List<RetransmitLink>,
    val diagnostics: List<FormatDiagnostic>,
)

/**
 * 确定性重放引擎：固定 seed（无随机性），同输入必产生同身份、同顺序、同来源的输出。
 * 覆盖 RFC 3261 client/server transaction、forking early dialog、CANCEL/ACK 归属、
 * 非 2xx ACK、UDP 重传（虚拟时钟）、TCP 重连（连接代次）、tag 冲突。
 */
object ReplayEngine {
    const val ALGORITHM_VERSION: String = "sip-fsm-1.0.0"

    fun run(input: EngineInput, basisEventId: String): EngineOutput {
        val ctx = EngineContext(input, basisEventId)
        val ordered = input.messages.sortedWith(
            compareBy<MessageRecord> { it.receivedAt.value ?: Double.MAX_VALUE }.thenBy { it.seq }
        )
        ordered.forEach { ctx.deliver(it) }
        return ctx.finish()
    }
}
