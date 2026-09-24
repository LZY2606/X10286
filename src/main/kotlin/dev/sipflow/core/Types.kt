package dev.sipflow.core

import kotlinx.serialization.Serializable

/**
 * 稳定身份（stable identity）。
 *
 * 所有进入系统的对象都携带一个确定型字符串 id：
 *  - 原始输入类对象：id = 内容指纹（对规范化字节做 SHA-256），天然不可覆盖、导入/导出保持。
 *  - 追加型历史事件：id = seq + parent 指纹链，重放后仍一致。
 * 每个显示值都带 stable id，见各 DTO 中的 `id` 字段。
 */
typealias StableId = String

@Serializable
enum class Direction { INGRESS, EGRESS }

@Serializable
enum class Transport { UDP, TCP, TLS }

/** 时间点（带坐标基准的虚拟时钟值）。 */
@Serializable
data class ClockPoint(
    /** 单调虚拟时钟毫秒数（ms since capture epoch），来源为导入文本，不使用墙钟。 */
    val virtualMs: Long,
    /** 原始文本表示（保留原样，规范化发生在派生层）。 */
    val raw: String
)

/** 传输连接代次（connection generation）：同一 4/5 元组重连后代次递增。 */
@Serializable
data class ConnectionGen(
    val id: StableId,
    val name: String,
    val transport: Transport,
    val local: String,
    val remote: String,
    /** 代次：0 为首连，TCP/TLS 重连后 +1。 */
    val generation: Int,
    val openedAt: ClockPoint?,
    val closedAt: ClockPoint?
)

/** 传输层事件（连接生命周期），与 SIP 消息分离保存。 */
@Serializable
data class TransportEvent(
    val id: StableId,
    val connection: StableId,
    val generation: Int,
    val kind: TransportEventKind,
    val at: ClockPoint
)

@Serializable
enum class TransportEventKind { OPEN, CLOSE, RESET }

/** 原始 SIP 消息：按方向保存原报文字节与解析面。解析面是可重算派生值，原始字节不可变。 */
@Serializable
data class RawSipMessage(
    val id: StableId,
    val ordinal: Int,
    val direction: Direction,
    val connection: StableId,
    val generation: Int,
    val receivedAt: ClockPoint,
    /** 原始报文字节（按提交时的字节，CRLF 保留为可见表示）。 */
    val rawBytes: String,
    val byteLength: Int,
    val parsed: ParsedSipMessage?,
    val parseError: String?
)

@Serializable
data class ParsedSipMessage(
    val isRequest: Boolean,
    val method: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val requestUri: String?,
    val viaBranch: String?,
    val topVia: String?,
    val callId: String?,
    val cseqNumber: Long?,
    val cseqMethod: String?,
    val fromTag: String?,
    val toTag: String?,
    val fromUri: String?,
    val toUri: String?,
    val recordRoutes: List<String>,
    val contact: String?,
    val hasContentLength: Boolean,
    /** 完整头部（保留原表示与顺序），key 为规范化后的 header 名。 */
    val headers: List<HeaderLine>,
    val body: String
)

@Serializable
data class HeaderLine(val name: String, val rawName: String, val value: String)

/** 项目（一个复查案例）。 */
@Serializable
data class Project(
    val id: StableId,
    val name: String,
    val createdAtVirtualMs: Long,
    val description: String = ""
)
