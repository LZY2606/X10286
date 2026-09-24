package sipviz

/** 一条按方向保存的 SIP 消息：原报文字节 + 解析出的关键头域 + 连接代次 + 接收时间(虚拟 tick)。 */
data class SipMessage(
    val id: String,
    val version: Int,
    val direction: Direction,
    val raw: String,
    val isRequest: Boolean,
    val method: String?,          // 请求方法；响应为 null
    val statusCode: Int?,         // 响应码；请求为 null
    val reason: String?,
    val uri: String?,
    val viaBranch: String?,
    val callId: String,
    val fromTag: String?,
    val toTag: String?,
    val cseqNum: Long,
    val cseqMethod: String,
    val recordRoute: List<String>,
    val route: List<String>,
    val contact: String?,
    val connId: String,
    val connGen: Int,
    val recvTick: Long,
) {
    /** 消息身份摘要：连接复用/重连不会改变它。 */
    val identity: String get() = "$viaBranch|$callId|$fromTag|$toTag|$cseqNum $cseqMethod"
    val rawDigest: String get() = sha256(raw).take(16)
}

sealed class ParseResult {
    data class Ok(val headers: ParsedHeaders) : ParseResult()
    data class FormatError(val diagnostics: List<String>) : ParseResult()
}

data class ParsedHeaders(
    val isRequest: Boolean,
    val method: String?, val uri: String?,
    val statusCode: Int?, val reason: String?,
    val viaBranch: String?, val callId: String?,
    val fromTag: String?, val toTag: String?,
    val cseqNum: Long?, val cseqMethod: String?,
    val recordRoute: List<String>, val route: List<String>,
    val contact: String?,
)

object SipParser {
    private val REQUEST_RE = Regex("^([A-Z]+)\\s+(\\S+)\\s+SIP/2\\.0\\s*$")
    private val RESPONSE_RE = Regex("^SIP/2\\.0\\s+(\\d{3})\\s*(.*)$")

    fun parse(raw: String): ParseResult {
        val diag = mutableListOf<String>()
        val lines = raw.replace("\r\n", "\n").split("\n")
        if (lines.isEmpty() || lines[0].isBlank()) return ParseResult.FormatError(listOf("empty start line"))
        val start = lines[0].trim()
        var isRequest = false
        var method: String? = null; var uri: String? = null
        var status: Int? = null; var reason: String? = null
        val rm = REQUEST_RE.matchEntire(start)
        val sm = RESPONSE_RE.matchEntire(start)
        when {
            rm != null -> { isRequest = true; method = rm.groupValues[1]; uri = rm.groupValues[2] }
            sm != null -> { status = sm.groupValues[1].toInt(); reason = sm.groupValues[2].trim() }
            else -> diag.add("unparseable start line: '$start'")
        }
        // 头域解析（支持续行折叠）
        val headers = mutableMapOf<String, MutableList<String>>()
        var last: String? = null
        for (i in 1 until lines.size) {
            val ln = lines[i]
            if (ln.isEmpty()) break
            if ((ln.startsWith(" ") || ln.startsWith("\t")) && last != null) {
                headers[last]!!.set(headers[last]!!.size - 1, headers[last]!!.last() + " " + ln.trim())
                continue
            }
            val idx = ln.indexOf(':')
            if (idx <= 0) { diag.add("malformed header line: '$ln'"); last = null; continue }
            val name = canonical(ln.substring(0, idx).trim())
            val value = ln.substring(idx + 1).trim()
            headers.getOrPut(name) { mutableListOf() }.add(value)
            last = name
        }
        fun all(n: String) = headers[n] ?: emptyList()
        fun one(n: String) = all(n).firstOrNull()

        val via = one("Via")
        val branch = via?.let { Regex("branch=([^;\\s]+)").find(it)?.groupValues?.get(1) }
        if (via == null) diag.add("missing Via header")
        else if (branch == null) diag.add("Via header without branch parameter")
        val callId = one("Call-ID") ?: one("i")
        if (callId == null) diag.add("missing Call-ID header")
        val from = one("From") ?: one("f")
        val to = one("To") ?: one("t")
        if (from == null) diag.add("missing From header")
        if (to == null) diag.add("missing To header")
        val fromTag = from?.let { Regex("tag=([^;>\\s]+)").find(it)?.groupValues?.get(1) }
        val toTag = to?.let { Regex("tag=([^;>\\s]+)").find(it)?.groupValues?.get(1) }
        if (isRequest && fromTag == null) diag.add("request From header without tag")
        val cseq = one("CSeq")
        var cseqNum: Long? = null; var cseqMethod: String? = null
        if (cseq == null) diag.add("missing CSeq header")
        else {
            val m = Regex("^(\\d+)\\s+([A-Z]+)$").matchEntire(cseq.trim())
            if (m == null) diag.add("malformed CSeq: '$cseq'")
            else { cseqNum = m.groupValues[1].toLong(); cseqMethod = m.groupValues[2] }
        }
        if (diag.isNotEmpty()) return ParseResult.FormatError(diag)
        return ParseResult.Ok(
            ParsedHeaders(
                isRequest, method, uri, status, reason, branch, callId, fromTag, toTag,
                cseqNum, cseqMethod,
                all("Record-Route").flatMap { splitRoutes(it) },
                all("Route").flatMap { splitRoutes(it) },
                one("Contact") ?: one("m"),
            )
        )
    }

    private fun splitRoutes(v: String): List<String> =
        v.split(",").map { it.trim() }.filter { it.startsWith("<") || it.startsWith("sip:") }

    private fun canonical(n: String): String = when (n.lowercase()) {
        "via", "v" -> "Via"; "call-id", "i" -> "Call-ID"; "from", "f" -> "From"
        "to", "t" -> "To"; "cseq" -> "CSeq"; "record-route" -> "Record-Route"
        "route" -> "Route"; "contact", "m" -> "Contact"
        else -> n
    }
}
