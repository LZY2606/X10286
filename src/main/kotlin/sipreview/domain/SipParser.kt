package sipreview.domain

import java.util.Base64

object SipParser {
    const val ALGO_VERSION = "sip-parse-v1"

    /** 只依赖字节本身的解析；任何结构问题转成 diagnostic，不抛异常。 */
    fun parse(bytes: ByteArray): ParsedMessage {
        val diags = mutableListOf<Diagnostic>()
        val headerSep: Int = findHeaderBodySep(bytes)
        val headerBlock = if (headerSep < 0) bytes else bytes.copyOfRange(0, headerSep)
        val bodyLen = if (headerSep < 0) 0 else bytes.size - headerSep - sepLen(bytes, headerSep)
        val text = String(headerBlock, Charsets.UTF_8)
        val rawLines = text.split('\n').map { it.trimEnd('\r') }.filterIndexed { i, _ -> true }
        if (rawLines.isEmpty() || rawLines.first().isBlank()) {
            return ParsedMessage(StartLineKind.UNKNOWN, diagnostics = listOf(Diagnostic("EMPTY_MESSAGE", "无起始行")))
        }
        val startLine = rawLines.first()
        var kind = StartLineKind.UNKNOWN
        var method: String? = null
        var uri: String? = null
        var status: Int? = null
        var reason: String? = null
        var version: String? = null
        val parts = startLine.split(' ')
        if (parts.size >= 3 && parts[0].startsWith("SIP/")) {
            kind = StartLineKind.STATUS
            version = parts[0]
            status = parts[1].toIntOrNull() ?: run {
                diags += Diagnostic("BAD_STATUS", "状态码非数字: ${parts[1]}")
                null
            }
            reason = parts.drop(2).joinToString(" ")
        } else if (parts.size >= 3 && parts[2].startsWith("SIP/")) {
            kind = StartLineKind.REQUEST
            method = parts[0]
            uri = parts[1]
            version = parts[2]
        } else {
            diags += Diagnostic("BAD_START_LINE", "起始行既非请求也非响应: $startLine")
        }
        val headers = mutableListOf<HeaderView>()
        var ordinal = 0
        for (i in 1 until rawLines.size) {
            val line = rawLines[i]
            if (line.isBlank()) continue
            if (line[0].isWhitespace()) { diags += Diagnostic("OBS_FOLD", "折叠头不支持，按独立行处理", i); continue }
            val idx = line.indexOf(':')
            if (idx <= 0) { diags += Diagnostic("BAD_HEADER", "无头名分隔符: $line", i); continue }
            headers += HeaderView(line.substring(0, idx).trim(), line.substring(idx + 1).trim(), ordinal++)
        }
        val vias = headers.filter { it.name.equals("Via", true) || it.name.equals("v", true) }
        val viaBranches = vias.mapNotNull { hv -> branchParam(hv.rawValue) }
        if (vias.isNotEmpty() && viaBranches.isEmpty()) {
            diags += Diagnostic("NO_BRANCH", "Via 缺少 branch 参数", vias.first().ordinal)
        }
        val callId = headers.firstOrNull { it.name.equals("Call-ID", true) || it.name.equals("i", true) }?.rawValue
        if (callId == null) diags += Diagnostic("NO_CALLID", "缺少 Call-ID")
        val from = headers.firstOrNull { it.name.equals("From", true) || it.name.equals("f", true) }?.rawValue
        val to = headers.firstOrNull { it.name.equals("To", true) || it.name.equals("t", true) }?.rawValue
        val fromTag = from?.let { tagParam(it) }
        if (from != null && fromTag == null) diags += Diagnostic("NO_FROM_TAG", "From 无 tag（对 UAC 初始请求合法）")
        val toTag = to?.let { tagParam(it) }
        val cseq = headers.firstOrNull { it.name.equals("CSeq", true) }?.rawValue
        var cseqSeq: Long? = null
        var cseqMethod: String? = null
        if (cseq != null) {
            val cp = cseq.trim().split(Regex("\\s+"))
            cseqSeq = cp.getOrNull(0)?.toLongOrNull()
            if (cseqSeq == null) diags += Diagnostic("BAD_CSEQ", "CSeq 序号非数字: $cseq")
            cseqMethod = cp.getOrNull(1)
            if (cseqMethod == null) diags += Diagnostic("BAD_CSEQ", "CSeq 缺方法: $cseq")
        } else diags += Diagnostic("NO_CSEQ", "缺少 CSeq")
        val rr = headers.filter { it.name.equals("Record-Route", true) }.map { it.rawValue }
        val contact = headers.firstOrNull { it.name.equals("Contact", true) || it.name.equals("m", true) }?.rawValue
        val topVia = vias.firstOrNull()?.rawValue
        return ParsedMessage(
            kind = kind, method = method, requestUri = uri, statusCode = status, reasonPhrase = reason,
            version = version, viaBranches = viaBranches, viaTop = topVia, callId = callId,
            fromTag = fromTag, toTag = toTag, cseqSeq = cseqSeq, cseqMethod = cseqMethod,
            recordRoute = rr, contact = contact,
            topViaReceived = topVia?.let { vparam(it, "received") },
            topViaRport = topVia?.let { vparam(it, "rport") },
            headers = headers, bodyBytes = bodyLen, diagnostics = diags,
        )
    }

    private fun sepLen(b: ByteArray, at: Int): Int =
        if (at + 1 < b.size && b[at] == '\r'.code.toByte() && b[at + 1] == '\n'.code.toByte()) 2 else 1

    private fun findHeaderBodySep(b: ByteArray): Int {
        var i = 0
        while (i < b.size) {
            if (b[i] == '\r'.code.toByte() && i + 3 < b.size && b[i + 1] == '\n'.code.toByte() &&
                b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()) return i
            if (b[i] == '\n'.code.toByte() && i + 1 < b.size && b[i + 1] == '\n'.code.toByte()) return i
            i++
        }
        return -1
    }

    private fun headerParams(v: String): Map<String, String> {
        val angle = v.lastIndexOf('>')
        val tail = if (angle >= 0) v.substring(angle + 1) else v.substringAfter(';', "")
        if (!tail.contains(';') && angle < 0) return emptyMap()
        return tail.split(';').drop(1).mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq > 0) p.substring(0, eq).trim().lowercase() to p.substring(eq + 1).trim().trim('"') else null
        }.toMap()
    }

    private fun branchParam(v: String): String? = headerParams(v)["branch"]
    private fun tagParam(v: String): String? = headerParams(v)["tag"]
    private fun vparam(v: String, name: String): String? = headerParams(v)[name]

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
}
