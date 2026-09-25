package review

/**
 * 导入格式（可复制的小型样例，CAPTURE/v1）：
 *
 *   CAPTURE/1
 *   clock-origin: 2026-09-25T10:00:00Z
 *   # 注释
 *   conn gen=1 proto=udp local=10.0.0.1:5060 remote=10.0.0.2:5060 opened=0ms
 *   msg dir=ingress conn=1 at=0ms
 *   <<<
 *   INVITE sip:bob@example.com SIP/2.0
 *   Via: SIP/2.0/UDP 10.0.0.2:5060;branch=z9hG4bK776asdhds
 *   ...
 *   >>>
 *   close gen=1 at=5000ms kind=close
 *
 * 所有行号诊断均从 1 开始；任意格式错误整体拒绝（显式列出错误），不产生半成品数据。
 * 时间量必须携带单位（ms/s）与 clock-origin 坐标基准；无法转换的值保留原位并附诊断。
 */
data class ParsedCapture(
    val clockOriginRaw: String?,
    val messages: List<ParsedMessageInput>,
    val connections: List<ParsedConnectionInput>,
    val diagnostics: List<FormatDiagnostic>,
)

data class ParsedConnectionInput(
    val line: Int,
    val generation: Int,
    val transport: Transport,
    val local: String,
    val remote: String,
    val openedAt: String,
    val closedAt: String? = null,
    val closeKind: ConnectionEventKind? = null,
)

data class ParsedMessageInput(
    val line: Int,
    val direction: Direction,
    val connectionGeneration: Int,
    val atRaw: String,
    val rawBytes: ByteArray,
)

object CaptureParser {

    private val HEADER_KEYS = setOf("dir", "conn", "at", "proto", "local", "remote", "opened", "kind", "gen")

    fun parse(text: String): ParsedCapture {
        val diags = mutableListOf<FormatDiagnostic>()
        val lines = text.split("\n")
        var clockOrigin: String? = null
        var sawHeader = false
        val msgs = mutableListOf<ParsedMessageInput>()
        val conns = mutableListOf<ParsedConnectionInput>()
        val opensByGen = mutableMapOf<Int, ParsedConnectionInput>()
        var i = 0
        while (i < lines.size) {
            val lineNo = i + 1
            val raw = lines[i].trimEnd('\r')
            val t = raw.trim()
            when {
                t.isEmpty() || t.startsWith("#") -> Unit
                !sawHeader && t.startsWith("CAPTURE/") -> {
                    if (t != "CAPTURE/1") diags += FormatDiagnostic(lineNo, "UNSUPPORTED_VERSION", "仅支持 CAPTURE/1", t)
                    sawHeader = true
                }
                !sawHeader -> diags += FormatDiagnostic(lineNo, "MISSING_HEADER", "首行必须是 CAPTURE/1", t)
                t.startsWith("clock-origin:") -> clockOrigin = t.removePrefix("clock-origin:").trim()
                t.startsWith("conn ") || t.startsWith("close ") -> parseConnLine(t, lineNo, diags)?.let { c ->
                    if (t.startsWith("conn ")) {
                        if (opensByGen.containsKey(c.generation))
                            diags += FormatDiagnostic(lineNo, "DUP_CONNECTION_GENERATION", "连接代次 ${c.generation} 重复打开", t)
                        opensByGen[c.generation] = c
                        conns += c
                    } else {
                        val open = opensByGen[c.generation]
                        if (open == null) diags += FormatDiagnostic(lineNo, "CLOSE_WITHOUT_OPEN", "代次 ${c.generation} 没有 conn 打开记录", t)
                        else conns += open.copy(closedAt = c.openedAt, closeKind = c.closeKind)
                    }
                }
                t.startsWith("msg ") -> {
                    val parsed = parseMsgHeader(t, lineNo, diags)
                    if (parsed != null) {
                        // 期望下一非空行是 <<<
                        var j = i + 1
                        while (j < lines.size && lines[j].trim().isEmpty()) j++
                        if (j >= lines.size || lines[j].trim() != "<<<") {
                            diags += FormatDiagnostic(lineNo, "MISSING_RAW_BEGIN", "msg 后必须紧跟独占一行的 <<<", t)
                            i = j
                        } else {
                            val body = mutableListOf<String>()
                            var k = j + 1
                            var closed = false
                            while (k < lines.size) {
                                if (lines[k].trimEnd('\r') == ">>>") { closed = true; break }
                                body += lines[k].trimEnd('\r')
                                k++
                            }
                            if (!closed) diags += FormatDiagnostic(j + 1, "UNCLOSED_RAW", "缺少结束行 >>>", "<<<")
                            val rawBytes = body.joinToString("\r\n").plus("\r\n").toByteArray(Charsets.UTF_8)
                            msgs += ParsedMessageInput(lineNo, parsed.first, parsed.third, parsed.second, rawBytes)
                            i = k
                        }
                    }
               
}
                else -> diags += FormatDiagnostic(lineNo, "UNRECOGNIZED_LINE", "无法识别的行（允许：conn/close/msg/clock-origin/注释）", t)
            }
            i++
        }
        if (!sawHeader && clockOrigin == null && diags.none { it.code == "MISSING_HEADER" })
            diags += FormatDiagnostic(1, "EMPTY_CAPTURE", "空输入", "")
        // 顺序与引用检查
        msgs.forEach { m ->
            if (opensByGen.containsKey(m.connectionGeneration).not())
                diags += FormatDiagnostic(m.line, "UNKNOWN_CONNECTION_GENERATION", "报文引用了未打开的连接代次 ${m.connectionGeneration}", "conn=${m.connectionGeneration}")
        }
        return ParsedCapture(clockOrigin, msgs, conns, diags.sortedBy { it.line })
    }

    private fun kv(t: String): Map<String, String> =
        t.split(Regex("\\s+")).drop(1).mapNotNull { tok ->
            val idx = tok.indexOf('=')
            if (idx <= 0) null else tok.substring(0, idx) to tok.substring(idx + 1)
        }.toMap()

    private fun parseConnLine(t: String, line: Int, diags: MutableList<FormatDiagnostic>): ParsedConnectionInput? {
        val m = kv(t)
        fun req(key: String): String? = m[key].also {
            if (it == null) diags += FormatDiagnostic(line, "MISSING_FIELD", "缺少字段 $key", t)
        }
        val gen = req("gen")?.toIntOrNull().also {
            if (m["gen"] != null && it == null) diags += FormatDiagnostic(line, "BAD_INTEGER", "gen 必须是整数", t)
        }
        val proto = req("proto")?.let { p ->
            runCatching { Transport.valueOf(p.uppercase()) }.getOrElse {
                diags += FormatDiagnostic(line, "BAD_TRANSPORT", "proto 必须是 udp/tcp/tls", t); null
            }
        }
        val local = req("local"); val remote = req("remote"); val opened = req("opened")
        val kind = if (t.startsWith("close ")) m["kind"]?.let { k ->
            runCatching { if (k == "close") ConnectionEventKind.CLOSE else ConnectionEventKind.valueOf(k.uppercase()) }
                .getOrElse { diags += FormatDiagnostic(line, "BAD_CLOSE_KIND", "kind 必须是 close/reset", t); null }
        } ?: ConnectionEventKind.CLOSE else null
        if (gen == null || proto == null || local == null || remote == null || opened == null) return null
        m.keys.filter { it !in HEADER_KEYS }.forEach {
            diags += FormatDiagnostic(line, "UNKNOWN_FIELD", "未知字段 $it", t)
        }
        return ParsedConnectionInput(line, gen, proto, local, remote, opened, closeKind = kind)
    }

    private fun parseMsgHeader(t: String, line: Int, diags: MutableList<FormatDiagnostic>): Triple<Direction, Int, String>? {
        val m = kv(t)
        val dir = m["dir"]?.let {
            when (it.lowercase()) {
                "ingress", "in" -> Direction.INGRESS
                "egress", "out" -> Direction.EGRESS
                else -> { diags += FormatDiagnostic(line, "BAD_DIRECTION", "dir 必须是 ingress/egress", t); null }
            }
        } ?: run { diags += FormatDiagnostic(line, "MISSING_FIELD", "缺少字段 dir", t); null }
        val conn = m["conn"]?.toIntOrNull().also {
            if (m["conn"] != null && it == null) diags += FormatDiagnostic(line, "BAD_INTEGER", "conn 必须是整数", t)
        } ?: run { diags += FormatDiagnostic(line, "MISSING_FIELD", "缺少整数 conn", t); null }
        val at = m["at"] ?: run { diags += FormatDiagnostic(line, "MISSING_FIELD", "缺少字段 at（带单位，如 120ms）", t); null }
        m.keys.filter { it !in HEADER_KEYS }.forEach { diags += FormatDiagnostic(line, "UNKNOWN_FIELD", "未知字段 $it", t) }
        if (dir == null || conn == null || at == null) return null
        return Triple(dir, conn, at)
    }
}
