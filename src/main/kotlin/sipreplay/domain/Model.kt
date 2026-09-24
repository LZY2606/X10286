package sipreplay.domain

import kotlinx.serialization.Serializable

/**
 * 虚拟时钟：所有重放时间都以 epoch 毫秒表示，但时间推进只来自输入里记录的接收时间，
 * 重放本身不读取墙上时钟（除展示用途）。单位：ms（毫秒，单调参考 epochVirtualMs）。
 */
@Serializable
@JvmInline
value class VirtualInstant(val epochMs: Long) : Comparable<VirtualInstant> {
    override fun compareTo(other: VirtualInstant): Int = epochMs.compareTo(other.epochMs)
    operator fun minus(other: VirtualInstant): Long = epochMs - other.epochMs
    operator fun plus(deltaMs: Long): VirtualInstant = VirtualInstant(epochMs + deltaMs)
}

/**
 * 带单位/坐标基准的领域量。导入时保留 [rawText]（原始表示），派生层才尝试规范化。
 * 无法转换时 [valueMs] 为 null 且保留 [diagnostic]，原值留在原位，绝不为画图丢弃。
 */
@Serializable
data class Quantified(
    val rawText: String,
    val unit: String,
    val valueMs: Long?,
    val basis: String,
    val diagnostic: String? = null,
) {
    companion object {
        fun duration(rawText: String, basis: String = "virtual-clock-epoch-ms"): Quantified {
            val t = rawText.trim()
            val n = t.toLongOrNull()
            return when {
                n != null -> Quantified(t, "ms", n, basis)
                t.endsWith("ms", true) -> t.dropLast(2).trim().toLongOrNull()
                    ?.let { Quantified(t, "ms", it, basis) }
                    ?: Quantified(t, "ms", null, basis, "unparseable-ms-duration")
                t.endsWith("s", true) -> t.dropLast(1).trim().toLongOrNull()
                    ?.let { Quantified(t, "s", it * 1000L, basis) }
                    ?: Quantified(t, "s", null, basis, "unparseable-s-duration")
                else -> Quantified(t, "unknown", null, basis, "unknown-unit-kept-raw")
            }
        }
    }
}

/** 消息方向：从被观测终端视角的入/出站。 */
@Serializable
enum class Direction { INBOUND, OUTBOUND }

/** 传输协议族。 */
@Serializable
enum class Transport { UDP, TCP, TLS, SCTP, UNKNOWN }

/**
 * 连接代次：同一 5 元组在 TCP 重连后会产生新代次；UDP 以流（flow）为代次。
 * 连接复用不能抹掉消息身份：消息仍各自身份独立，只是归属同一 connectionGenerationId。
 */
@Serializable
data class ConnectionGeneration(
    val connectionGenerationId: String,
    val transport: Transport,
    val localEndpoint: String,
    val remoteEndpoint: String,
    val openedAt: VirtualInstant,
    val closedAt: VirtualInstant? = null,
    val reconnectOf: String? = null,
    val sourceBatchId: String,
)

/**
 * 不可变原始消息。一经提交不可覆盖；后续修订以 [RawInputRevision] 新版本+父版本表达。
 * 原报文字节按 base64 保存（[rawBytesBase64]），同时保留导入时声明的编码长度与接收时间。
 */
@Serializable
data class RawMessage(
    val messageId: String,
    val revisionId: String,
    val connectionGenerationId: String,
    val direction: Direction,
    val receivedAt: VirtualInstant,
    val ingestSeq: Long,
    val rawBytesBase64: String,
    val rawByteLength: Int,
    val sourceBatchId: String,
    val sourceLabel: String,
    val overrideObservedAt: VirtualInstant? = null,
)

/** 原始输入修订链：新修订指向父版本；旧版本永不被覆盖。 */
@Serializable
data class RawInputRevision(
    val revisionId: String,
    val parentRevisionId: String?,
    val targetMessageId: String?,
    val submittedAt: VirtualInstant,
    val note: String,
    val sourceBatchId: String,
)

/** 输入批次（一次导入）。 */
@Serializable
data class InputBatch(
    val batchId: String,
    val submittedAt: VirtualInstant,
    val formatVersion: String,
    val note: String,
)

/** SIP 起始行。 */
@Serializable
data class StartLine(
    val kind: String, // "request" | "status"
    val method: String? = null,
    val requestUri: String? = null,
    val statusCode: Int? = null,
    val reasonPhrase: String? = null,
    val sipVersion: String = "SIP/2.0",
    val raw: String,
)

/** 单个头域行（重复头全部保留，原始大小写保留）。 */
@Serializable
data class HeaderLine(val name: String, val rawName: String, val value: String)

/** 解析后的 SIP 报文（派生层）。解析失败也保留原字节并给诊断。 */
@Serializable
data class ParsedSip(
    val messageId: String,
    val revisionId: String,
    val startLine: StartLine?,
    val headers: List<HeaderLine>,
    val bodyBytesBase64: String?,
    val parseDiagnostics: List<String>,
    val algoVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,
) {
    fun headerValues(name: String): List<String> =
        headers.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }
    fun firstHeader(name: String): String? = headerValues(name).firstOrNull()
}

/** Via 单跳的结构化视图（保留原始文本）。 */
@Serializable
data class ViaHop(
    val raw: String,
    val protocol: String,
    val transport: String,
    val sentBy: String,
    val branch: String?,
    val rport: String?,
    val received: String?,
    val otherParams: Map<String, String>,
)

/** Name-addr（tag 等参数单独拆出，原始值保留）。 */
@Serializable
data class NameAddr(
    val raw: String,
    val uri: String,
    val displayName: String?,
    val tag: String?,
    val params: Map<String, String>,
)
