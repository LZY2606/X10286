package sipreview.parse

import sipreview.domain.*

data class RawSip(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val headerBlock: String
)

object SipParser {
    private val reqLine = Regex("^([A-Z][A-Z0-9\\-._~+!]*)\\s+(\\S+)\\s+SIP/2\\.0$")
    private val respLine = Regex("^SIP/2\\.0\\s+(\\d{3})(?:\\s+(.*))?$")

    fun split(rawText: String): RawSip? {
        val normalized = rawText.replace("\r\n", "\n")
        val sepIdx = normalized.indexOf("\n\n")
        val head = if (sepIdx >= 0) normalized.substring(0, sepIdx) else normalized
        val body = if (sepIdx >= 0) normalized.substring(sepIdx + 2) else ""
        val lines = head.split('\n').dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) return null
        val startLine = lines[0].trim()
        val headers = mutableListOf<Pair<String, String>>()
        for (line in lines.drop(1)) {
            if (line.startsWith(' ') || line.startsWith('\t')) {
                val last = headers.removeLastOrNull()
                if (last != null) headers += last.first to (last.second + " " + line.trim())
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        return RawSip(startLine, headers, body, head)
    }

    fun parse(unitId: String, rawText: String): ParsedView {
        val diags = mutableListOf<String>()
        val transforms = mutableListOf<TransformStep>()
        fun header(name: String): String? {
            val v = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
            if (v != null) transforms += TransformStep("header:$name", raw = v, norm = v.trim(),
                sources = listOf(DirectSource("field", unitId, field = name)))
            return v?.trim()
        }
        fun headersAll(name: String): List<String> =
            headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second.trim() }

        val rawSip = split(rawText)
            ?: return ParsedView(
                kind = "invalid", method = null, requestUri = null, statusCode = null, reason = null,
                callId = null, fromTag = null, toTag = null, cseqNumber = null, cseqMethod = null,
                topViaBranch = null, topViaTransport = null, viaSentBy = null, contact = null,
                contentLength = null, recordRoutes = emptyList(),
                transformations = emptyList(),
                diagnostics = listOf("无法切分起始行与头部：报文不符合 SIP/2.0 文本格式，原始字节仍按方向保留")
            )
        val (startLine, headers, _, _) = rawSip

        val m = reqLine.matchEntire(startLine)
        val r = respLine.matchEntire(startLine)
        val kind: String
        val method: String?
        val requestUri: String?
        val statusCode: Int?
        val reason: String?
        when {
            m != null -> {
                kind = "request"; method = m.groupValues[1]; requestUri = m.groupValues[2]
                statusCode = null; reason = null
                transforms += TransformStep("start-line", startLine, "request $method",
                    listOf(DirectSource("field", unitId, field = "start-line")))
            }
            r != null -> {
                kind = "response"; method = null; requestUri = null
                statusCode = r.groupValues[1].toInt(); reason = r.groupValues[2].ifBlank { null }
                transforms += TransformStep("start-line", startLine, "response $statusCode",
                    listOf(DirectSource("field", unitId, field = "start-line")))
            }
            else -> {
                diags += "起始行既不是请求行也不是状态行: '$startLine'"
                return ParsedView(
                    kind = "invalid", method = null, requestUri = null, statusCode = null,
                    reason = null, callId = null, fromTag = null, toTag = null, cseqNumber = null,
                    cseqMethod = null, topViaBranch = null, topViaTransport = null, viaSentBy = null,
                    contact = null, contentLength = null, recordRoutes = emptyList(),
                    transformations = transforms, diagnostics = diags
                )
            }
        }

        val callId = header("i") ?: header("Call-ID")
        if (callId == null) diags += "缺少 Call-ID"
        val from = header("f") ?: header("From")
        val to = header("t") ?: header("To")
        val fromTag = from?.let { tagOf(it) }
        if (from != null && fromTag == null) diags += "From 缺少 tag 参数"
        val toTag = to?.let { tagOf(it) }
        if (kind == "response" && toTag == null) diags += "响应缺少 To-tag（无法形成 dialog 身份）"

        val cseqRaw = header("CSeq") ?: header("c")
        val cseqParts = cseqRaw?.split(Regex("\\s+"))
        val cseqNumber = cseqParts?.getOrNull(0)?.toLongOrNull()
        val cseqMethod = cseqParts?.getOrNull(1)
        if (cseqRaw != null && cseqParts == null) diags += "CSeq 格式异常: '$cseqRaw'"
        if (cseqRaw != null && cseqNumber == null) diags += "CSeq 序号不是数字: '$cseqRaw'"

        val topViaRaw = headersAll("Via") + headersAll("v")
        val topVia = topViaRaw.firstOrNull()?.let { splitCommaHeader(it).firstOrNull() }
        val topViaBranch = topVia?.let { viaParam(it, "branch") }
        val topViaTransport = topVia?.let { viaTransport(it) }
        val viaSentBy = topVia?.let { viaSentBy(it) }
        if (topVia != null && topViaBranch == null) diags += "顶部 Via 缺少 branch 参数（无法事务匹配）"
        if (topViaBranch != null && !topViaBranch.startsWith("z9hG4bK"))
            diags += "Via branch 缺少 RFC3261 magic cookie z9hG4bK（可能是逐跳/非标准分支）"

        val contact = header("m") ?: header("Contact")?.let { stripParams(it) }
        val clRaw = header("l") ?: header("Content-Length")
        val contentLength = clRaw?.trim()?.toLongOrNull()
        if (clRaw != null && contentLength == null) diags += "Content-Length 不是数字: '$clRaw'"

        val rrRaw = headersAll("Record-Route") + headersAll("Record-Route".let { "Record-Route" })
        val rrValues = headersAll("Record-Route")
        val recordRoutes = rrValues.flatMapIndexed { hi, vv ->
            splitCommaHeader(vv).mapIndexed { ii, entry ->
                RouteEntry(
                    id = Hashing.stableId("rr", unitId, hi.toString(), ii.toString(), entry),
                    index = hi * 1000 + ii,
                    raw = entry,
                    normalized = normalizeRouteUri(entry),
                    sources = listOf(DirectSource("field", unitId, field = "Record-Route[$hi][$ii]"))
                )
            }
        }.sortedBy { it.index }.mapIndexed { i, e -> e.copy(index = i) }

        return ParsedView(
            kind = kind, method = method, requestUri = requestUri,
            statusCode = statusCode, reason = reason,
            callId = callId, fromTag = fromTag, toTag = toTag,
            cseqNumber = cseqNumber, cseqMethod = cseqMethod,
            topViaBranch = topViaBranch, topViaTransport = topViaTransport,
            viaSentBy = viaSentBy, contact = contact,
            contentLength = contentLength, recordRoutes = recordRoutes,
            transformations = transforms, diagnostics = diags
        )
    }

    private fun tagOf(addr: String): String? {
        val idx = addr.indexOf(";")
        if (idx < 0) return null
        val params = addr.substring(idx + 1).split(";")
        return params.firstOrNull { it.trim().startsWith("tag=", ignoreCase = true) }
            ?.trim()?.substringAfter("=")?.trim('"')
    }

    private fun stripParams(v: String): String = v.substringBefore(";").trim().trim('<', '>')

    /** Record-Route 可能在一行出现多个逗号分隔的值（name-addr 内逗号在 <> 中，需按括号切分）。 */
    fun splitCommaHeader(v: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in v) {
            when (c) {
                '<' -> { depth++; cur.append(c) }
                '>' -> { depth--; cur.append(c) }
                ',' -> if (depth <= 0) { out += cur.toString().trim(); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private fun viaParam(via: String, name: String): String? {
        val afterProtocol = via.substringAfter("SIP/2.0/", via)
        val semi = afterProtocol.indexOf(';')
        if (semi < 0) return null
        val params = afterProtocol.substring(semi + 1).split(';')
        return params.firstOrNull { it.trim().startsWith("$name=", ignoreCase = true) }
            ?.trim()?.substringAfter('=')
    }

    private fun viaTransport(via: String): String? =
        Regex("SIP/2\\.0/([A-Za-z]+)").find(via)?.groupValues?.get(1)?.uppercase()

    private fun viaSentBy(via: String): String? {
        val afterProtocol = via.substringAfter("SIP/2.0/", "")
        val hostPort = afterProtocol.substringBefore(';').trim()
        return hostPort.ifBlank { null }
    }

    private fun normalizeRouteUri(entry: String): String? {
        val uriPart = entry.substringAfter('<').substringBefore('>')
        return uriPart.ifBlank {
            Regex("sip[s]?:[^;]+", RegexOption.IGNORE_CASE).find(entry)?.value
        }
    }
}
