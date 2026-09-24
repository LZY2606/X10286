package siprecon

/** 方向：双向 SIP 消息按方向保存。 */
enum class Direction { C2S, S2C }

/** 解析后的规范化字段（派生层）；原始字节始终保留在 MessageRecord.rawBytes。 */
data class ParsedMessage(
    val isRequest: Boolean,
    val method: String?,          // 请求方法；响应时为 CSeq 方法
    val statusCode: Int?,
    val reasonPhrase: String?,
    val viaBranch: String?,
    val viaTransport: String?,    // UDP / TCP，原始表示保留在 rawBytes
    val callId: String?,
    val fromTag: String?,
    val toTag: String?,
    val cseqNum: Long?,
    val cseqMethod: String?,
    val recordRoutes: List<String>,
    val routes: List<String>,
    val contact: String?,
)

/** 原始输入：一经提交不可覆盖；修订以 parentId 指向旧版本。 */
data class MessageRecord(
    val id: String,
    val version: Int,
    val parentId: String?,
    val direction: Direction,
    val connGeneration: Int,      // 连接代次
    val arrivalMs: Long,          // 虚拟时钟接收时间
    val rawBytes: String,         // 原报文字节
    val parsed: ParsedMessage?,
    val parseError: String?,
    val batch: String,            // 导入批次 / 案例步骤
    val seq: Long,                // 稳定顺序
)

data class ConnectionRecord(
    val id: String,
    val generation: Int,
    val transport: String,        // UDP / TCP（原始表示）
    val peer: String,
    val openedMs: Long,
    val closedMs: Long?,
)

data class ClockRecord(
    val id: String,
    val label: String,
    val virtualMs: Long,
    val note: String,
)

enum class TxKind { INVITE_CLIENT, INVITE_SERVER, NONINVITE_CLIENT, NONINVITE_SERVER }

data class Transaction(
    val id: String,
    val runId: String,
    val kind: TxKind,
    val tkey: String,             // branch|method（ACK 归并见引擎）
    val state: String,
    val messageIds: List<String>,
    val branch: String,
    val method: String,
)

enum class DialogState { EARLY, CONFIRMED, TERMINATED }

data class Dialog(
    val id: String,
    val runId: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val state: DialogState,
    val routeSet: List<String>,   // 来自 Record-Route
    val establishedBy: String,    // message id
    val notes: String,
)

enum class CandidateStatus { PENDING, ACCEPTED, REJECTED }

/** 候选与拒绝原因是一等数据。 */
data class Candidate(
    val id: String,
    val runId: String,
    val kind: String,             // TAG_CONFLICT_MERGE / TAG_CONFLICT_SPLIT / CANCEL_RACE_OUTCOME ...
    val payload: String,          // JSON 描述
    val status: CandidateStatus,
    val reasons: List<String>,
)

/** 定时器决定（基于虚拟时钟）。 */
data class TimerDecision(
    val id: String,
    val runId: String,
    val timer: String,            // A/B/D/E/F/G/H/I/K
    val fireMs: Long,
    val basis: String,            // 依据（虚拟时钟读数与消息身份）
    val effect: String,
)

/** 机器可读取的判断事件。 */
data class Judgment(
    val id: String,
    val runId: String,
    val type: String,             // FORKING_MULTI_EARLY_DIALOG / CANCEL_ACK_DISTINCT_TRANSACTIONS /
                                  // UDP_RETRANSMIT_VIRTUAL_CLOCK / CONNECTION_REUSE_PRESERVES_IDENTITY /
                                  // LATE_200_RETRANSMIT / CANCEL_RACE / TAG_CONFLICT / NON_2XX_ACK_IN_TRANSACTION
    val payload: String,          // JSON
)

data class RunInfo(
    val id: String,
    val seq: Long,
    val algoVersion: String,
    val paramsFp: String,
    val inputFp: String,
    val createdMs: Long,
    val note: String,
    val current: Boolean,
)

data class EventRecord(
    val id: String,
    val seq: Long,
    val runId: String?,
    val type: String,             // IMPORT / REVISE / RUN / PARAM / JUDGMENT / DECISION / UNDO / EXPORT / IMPORT_ARCHIVE
    val payload: String,
    val createdMs: Long,
)

/** 引擎参数：任何派生值都带参数指纹。 */
data class Params(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val retxTolerance: Double = 0.30,
    val cancelRaceWindowMs: Long = 500,
) {
    fun fingerprint(): String = "t1=$t1Ms;t2=$t2Ms;rtol=$retxTolerance;crw=$cancelRaceWindowMs"
    fun timerBMs() = 64 * t1Ms
}

const val ALGO_VERSION = "sip-recon/1.0.0"
