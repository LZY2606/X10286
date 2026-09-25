package review

/** 时间量解析：必须带单位（ms/s），坐标基准为 clock-origin（epoch ms）。无法转换时保留原表示 + 诊断。 */
object Units {
    private val rel = Regex("^(-?\\d+(?:\\.\\d+)?)(ms|s)$")
    private val abs = Regex("^@(-?\\d+(?:\\.\\d+)?)(ms|s)$")

    fun parseTime(raw: String, originMs: Long): UnitValue {
        val m = rel.matchEntire(raw)
        if (m != null) {
            val num = m.groupValues[1].toDouble()
            val ms = if (m.groupValues[2] == "s") num * 1000.0 else num
            return UnitValue(raw, if (m.groupValues[2] == "s") "s" else "ms", "relative-to-clock-origin", ms + originMs, null)
        }
        val ma = abs.matchEntire(raw)
        if (ma != null) {
            val num = ma.groupValues[1].toDouble()
            val ms = if (ma.groupValues[2] == "s") num * 1000.0 else num
            return UnitValue(raw, if (ma.groupValues[2] == "s") else "ms", "epoch-unix", ms, null)
        }
        return UnitValue(raw, "unknown", "unknown", null, "时间必须是 <number>ms|s（相对 clock-origin）或 @<number>ms|s（epoch）")
    }
}

data class SipParsed(
    val kind: MessageKind?,
    val method: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val headers: List<Pair<String, String>>,
    val diagnostics: List<FormatDiagnostic>,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
    fun headers(name: String): List<String> = headers.filter { it.first.equals(name, true) }.map { it.second }
}

object SipParser {
    fun parse(bytes: ByteArray): SipParsed {
        val diags = mutableListOf<FormatDiagnostic>()
        val text = bytes.toString(Charsets.UTF_8)
        val rawLines = text.split("\r\n").dropLastWhile { it.isEmpty() }
        if (rawLines.isEmpty()) {
            return SipParsed(null, null, null, null, emptyList(), listOf(FormatDiagnostic(0, "EMPTY_MESSAGE", "空 SIP 报文", "")))
        }
        val start = rawLines.first()
        var kind: MessageKind? = null; var method: String? = null; var code: Int? = null; var reason: String? = null
        when {
            start.startsWith("SIP/2.0 ") -> {
                kind = MessageKind.RESPONSE
                val rest = start.removePrefix("SIP/2.0 ")
                val sp = rest.indexOf(' ')
                val codeTxt = if (sp < 0) rest else rest.substring(0, sp)
                code = codeTxt.toIntOrNull()
                if (code == null) diags += FormatDiagnostic(1, "BAD_STATUS_LINE", "状态码不可解析", start)
                reason = if (sp < 0) "" else rest.substring(sp + 1)
            }
            start.endsWith(" SIP/2.0") -> {
                kind = MessageKind.REQUEST
                method = start.removeSuffix(" SIP/2.0").substringBefore(' ')
                if (method.isNullOrBlank() || method!!.contains(' '))
                    diags += FormatDiagnostic(1, "BAD_REQUEST_LINE", "请求行不可解析", start)
            }
            else -> diags += FormatDiagnostic(1, "BAD_START_LINE", "既不是请求也不是响应起始行", start)
        }
        val headers = mutableListOf<Pair<String, String>>()
        for (idx in 1 until rawLines.size) {
            val line = rawLines[idx]
            if (line.isEmpty()) break
            if (line[0] == ' ' || line[0] == '\t') {
                if (headers.isEmpty()) diags += FormatDiagnostic(idx + 1, "BAD_HEADER", "折叠行前没有头字段", line)
                else { val last = headers.removeLast(); headers += last.first to (last.second + " " + line.trim()) }
                continue
            }
            val c = line.indexOf(':')
            if (c < 0) { diags += FormatDiagnostic(idx + 1, "BAD_HEADER", "缺少冒号", line); continue }
            headers += line.substring(0, c).trim() to line.substring(c + 1).trim()
        }
        if (kind != null && headerMissing(headers, "Via")) diags += FormatDiagnostic(0, "MISSING_VIA", "缺少 Via", "")
        if (kind != null && headerMissing(headers, "Call-ID")) diags += FormatDiagnostic(0, "MISSING_CALL_ID", "缺少 Call-ID", "")
        if (kind != null && headerMissing(headers, "CSeq")) diags += FormatDiagnostic(0, "MISSING_CSEQ", "缺少 CSeq", "")
        return SipParsed(kind, method, code, reason, headers, diags)
    }

    private fun headerMissing(headers: List<Pair<String, String>>, name: String) =
        headers.none { it.first.equals(name, true) }
}

object SipFields {
    private val BRANCH = Regex("(?i);\\s*branch\\s*=\\s*([^;]+)")
    private val PARAM = Regex(";\\s*([\\w.\\-]+)\\s*(?:=\\s*([^;]+))?")
    private val NAME_ADDR = Regex("^([^<]*)<([^>]+)>(.*)$")

    fun viaBranch(via: String?): String? = via?.let { BRANCH.find(it)?.groupValues?.get(1)?.trim() }
    fun viaParam(via: String?, name: String): String? = via?.let { v ->
        PARAM.findAll(v).firstOrNull { it.groupValues[1].equals(name, true) }?.groupValues?.get(2)?.trim()
    }
    fun tag(addr: String?): String? = addr?.let { PARAM.findAll(it).firstOrNull { p -> p.groupValues[1].equals("tag", true) }?.groupValues?.get(2)?.trim() }
    fun uriOf(addr: String?): String? {
        if (addr == null) return null
        val m = NAME_ADDR.find(addr) ?: return addr.substringBefore(';').trim().ifBlank { null }
        return m.groupValues[2]
    }
    fun cseq(v: String?): Pair<Long, String>? {
        if (v == null) return null
        val p = v.trim().split(Regex("\\s+"))
        if (p.size < 2) return null
        val n = p[0].toLongOrNull() ?: return null
        return n to p[1]
    }
}
