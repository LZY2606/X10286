package sipreview.sip

/**
 * Lenient RFC 3261 style parser.
 *
 * The parser never throws for ordinary protocol-level oddities: every problem is
 * recorded as a [Diag] while the original bytes remain untouched. Hard structural
 * failure (no recognizable start line) yields [ParsedMessage] == null and a
 * diagnostic with code "SIP-START-LINE".
 */
object SipParser {

    fun parse(bytes: ByteArray): ParseResult {
        val diags = mutableListOf<Diag>()
        val text = decode(bytes)
        // Locate the header/body boundary directly on the raw bytes so that
        // multi-byte UTF-8 sequences cannot corrupt the offset.
        val boundary = findHeaderEnd(bytes)
        val bodyBytes: ByteArray = if (boundary >= 0) {
            bytes.copyOfRange(minOf(boundary, bytes.size), bytes.size)
        } else ByteArray(0)
        val headerByteCount = if (boundary >= 0) boundary else bytes.size
        val head = if (boundary >= 0) String(bytes, 0, boundary, Charsets.ISO_8859_1) else text

        val lines = unfold(head)
        if (lines.isEmpty()) {
            return ParseResult(null, listOf(Diag("error", "SIP-EMPTY", "empty message", 0)))
        }
        val startLine = lines.first()
        val rest = lines.drop(1)

        val request = parseRequestLine(startLine)
        val response = if (request == null) parseStatusLine(startLine) else null
        if (request == null && response == null) {
            diags += Diag("error", "SIP-START-LINE", "not a SIP request or status line: ${startLine.take(80)}", 0)
            return ParseResult(null, diags)
        }

        val rawHeaders = mutableListOf<RawHeader>()
        for ((idx, line) in rest.withIndex()) {
            val colon = line.indexOf(':')
            if (colon <= 0) {
                diags += Diag("warn", "SIP-HEADER", "ignoring malformed header line: ${line.take(60)}", null)
                continue
            }
            rawHeaders += RawHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim(), idx)
        }

        val headers = rawHeaders.groupBy { it.name.lowercase() }
        fun all(name: String): List<String> = headers[name.lowercase()]?.map { it.value } ?: emptyList()
        fun one(name: String): String? = all(name).firstOrNull()

        val via = one("via")?.let { parseVia(it, diags) }
        if (via == null) diags += Diag("warn", "SIP-VIA-MISSING", "no parseable top Via header", null)
        val callId = one("call-id")?.trim()?.also {
            if (it.isBlank()) diags += Diag("warn", "SIP-CALLID", "empty Call-ID", null)
        }
        if (callId == null) diags += Diag("error", "SIP-CALLID-MISSING", "missing Call-ID", null))

        val from = one("from")?.let { parseNameAddr(it, "From", diags) }
        val to = one("to")?.let { parseNameAddr(it, "To", diags) }
        if (from == null) diags += Diag("error", "SIP-FROM-MISSING", "missing/unparseable From", null)
        if (to == null) diags += Diag("error", "SIP-TO-MISSING", "missing/unparseable To", null)

        val cseq = one("cseq")?.let { parseCSeq(it, diags) }
        if (cseq == null) diags += Diag("error", "SIP-CSEQ-MISSING", "missing/unparseable CSeq", null)

        val contentLength = one("content-length")?.trim()?.toLongOrNull()
        if (one("content-length") != null && contentLength == null) {
            diags += Diag("warn", "SIP-CONTENT-LENGTH", "non-numeric Content-Length kept raw", null)
        }
        if (contentLength != null && contentLength != bodyBytes.size.toLong()) {
            diags += Diag(
                "warn", "SIP-CONTENT-LENGTH-MISMATCH",
                "declared Content-Length=$contentLength but body is ${bodyBytes.size} bytes", null
            )
        }

        val msg = ParsedMessage(
            kind = if (request != null) "request" else "response",
            method = request?.method,
            requestUri = request?.uri,
            statusCode = response?.code,
            reasonPhrase = response?.reason,
            headerLines = rawHeaders.map { HeaderLine(it.name, it.value, it.order) },
            via = via,
            callId = callId,
            from = from,
            to = to,
            cSeq = cseq,
            recordRoutes = all("record-route"),
            routes = all("route"),
            contact = one("contact"),
            maxForwards = one("max-forwards")?.trim(),
            contentLengthRaw = one("content-length")?.trim(),
            bodyBytesB64 = Base64.encode(bodyBytes),
            bodyByteLength = bodyBytes.size,
        )
        return ParseResult(msg, diags)
    }

    private fun decode(bytes: ByteArray): String {
        val strict = bytes.toString(Charsets.UTF_8)
        return if (strict.contains('\uFFFD')) {
            // Preserve raw bytes in storage anyway; latin1 keeps a 1:1 mapping for offsets.
            String(bytes, Charsets.ISO_8859_1)
        } else strict
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        val crlf = byteIndexOf(bytes, byteArrayOf(0x0d, 0x0a, 0x0d, 0x0a))
        val lf = byteIndexOf(bytes, byteArrayOf(0x0a, 0x0a))
        return when {
            crlf < 0 -> if (lf >= 0) lf + 2 else -1
            lf < 0 -> crlf + 4
            crlf + 4 <= lf + 2 -> crlf + 4
            else -> lf + 2
        }
    }

    private fun byteIndexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun unfold(head: String): List<String> {
        val normalized = head.replace("\r\n[ \\t]".toRegex(), " ").replace("\n[ \\t]".toRegex(), " ")
        return normalized.split("\r\n|\n".toRegex()).filter { it.isNotBlank() }
    }

    private fun parseRequestLine(line: String): ReqLine? {
        val parts = line.split(' ').filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        if (parts[2].trim() != "SIP/2.0" && !parts[2].startsWith("SIP/2.0")) return null
        if (!parts[1].startsWith("sip:") && !parts[1].startsWith("sips:")) return null
        val method = parts[0]
        if (!method.matches("[A-Z][A-Z0-9-]*".toRegex())) return null
        return ReqLine(method, parts[1])
    }

    private fun parseStatusLine(line: String): StatusLine? {
        val parts = line.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        if (parts[0] != "SIP/2.0") return null
        val code = parts[1].toIntOrNull() ?: return null
        if (code !in 100..699) return null
        return StatusLine(code, parts.drop(2).joinToString(" "))
    }

    private fun splitSemiParams(s: String): Pair<String, List<Pair<String, String?>>> {
        val segments = splitRespectingQuotes(s, ';')
        val base = segments.first().trim()
        val params = segments.drop(1).map { seg ->
            val eq = seg.indexOf('=')
            if (eq < 0) seg.trim() to null else seg.substring(0, eq).trim() to seg.substring(eq + 1).trim()
        }
        return base to params
    }

    private fun splitRespectingQuotes(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                c == sep && !quoted -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    fun parseVia(raw: String, diags: MutableList<Diag>): ViaValue? {
        val (base, params) = splitSemiParams(raw)
        val parts = base.trim().split('/')
        if (parts.size < 3 || parts[0] != "SIP") {
            diags += Diag("error", "SIP-VIA", "malformed Via: ${raw.take(80)}", null)
            return null
        }
        val sentProtocol = parts.getOrNull(1) ?: ""
        val hostPort = parts.getOrNull(2)?.trim().orEmpty()
        val transport = parts.getOrNull(3)?.trim()?.uppercase() ?: ""
        val host: String
        val port: Int?
        val colon = hostPort.lastIndexOf(':')
        if (colon >= 0) {
            host = hostPort.substring(0, colon)
            port = hostPort.substring(colon + 1).toIntOrNull()?.also {
                if (it !in 1..65535) diags += Diag("warn", "SIP-VIA-PORT", "Via port out of range: $it", null)
            }
        } else {
            host = hostPort
            port = null
        }
        val branch = params.firstOrNull { it.first.equals("branch", ignoreCase = true) }?.second
        if (branch != null && !branch.startsWith("z9hG4bK")) {
            diags += Diag("warn", "SIP-VIA-BRANCH-MAGIC", "branch lacks z9hG4bK magic cookie: $branch", null)
        }
        if (branch == null) {
            diags += Diag("warn", "SIP-VIA-BRANCH-MISSING", "Via has no branch parameter", null)
        }
        return ViaValue(
            protocol = sentProtocol,
            transport = transport.ifEmpty { null },
            host = host.ifEmpty { null },
            port = port,
            branch = branch,
            params = params.associate { it.first.lowercase() to (it.second ?: "") },
            raw = raw,
        )
    }

    fun parseNameAddr(raw: String, where: String, diags: MutableList<Diag>): NameAddr? {
        val open = raw.indexOf('<')
        val close = raw.indexOf('>')
        if (open < 0 || close < open) {
            val (uri, params) = splitSemiParams(raw)
            if (!uri.startsWith("sip:") && !uri.startsWith("sips:")) {
                diags += Diag("error", "SIP-$where", "malformed name-addr: ${raw.take(80)}", null)
                return null
            }
            return NameAddr(uri, params.firstOrNull { it.first.equals("tag", true) }?.second, null)
        }
        val display = raw.substring(0, open).trim().ifEmpty { null }
        val uri = raw.substring(open + 1, close)
        val after = raw.substring(close + 1)
        val (_, params) = splitSemiParams(after)
        return NameAddr(uri, params.firstOrNull { it.first.equals("tag", true) }?.second, display)
    }

    private fun parseCSeq(raw: String, diags: MutableList<Diag>): CSeqValue? {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size != 2) {
            diags += Diag("error", "SIP-CSEQ", "malformed CSeq: $raw", null)
            return null
        }
        val num = parts[0].toLongOrNull()
        if (num == null || num < 0) {
            diags += Diag("error", "SIP-CSEQ-NUM", "non-numeric CSeq number: ${parts[0]}", null)
            return null
        }
        return CSeqValue(num, parts[1].uppercase())
    }
}

data class ParseResult(val message: ParsedMessage?, val diagnostics: List<Diag>)
data class Diag(val level: String, val code: String, val message: String, val offset: Int?)
private data class RawHeader(val name: String, val value: String, val order: Int)
private data class ReqLine(val method: String, val uri: String)
private data class StatusLine(val code: Int, val reason: String)

data class ParsedMessage(
    val kind: String,
    val method: String?,
    val requestUri: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val headerLines: List<HeaderLine>,
    val via: ViaValue?,
    val callId: String?,
    val from: NameAddr?,
    val to: NameAddr?,
    val cSeq: CSeqValue?,
    val recordRoutes: List<String>,
    val routes: List<String>,
    val contact: String?,
    val maxForwards: String?,
    val contentLengthRaw: String?,
    val bodyBytesB64: String,
    val bodyByteLength: Int,
)

data class HeaderLine(val name: String, val value: String, val order: Int)
data class ViaValue(
    val protocol: String,
    val transport: String?,
    val host: String?,
    val port: Int?,
    val branch: String?,
    val params: Map<String, String>,
    val raw: String,
)
data class NameAddr(val uri: String, val tag: String?, val displayName: String?)
data class CSeqValue(val number: Long, val method: String)
