package sipgsb

class SipParseException(message: String) : Exception(message)

data class SipMessage(
    val isRequest: Boolean,
    val method: String?,
    val requestUri: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val headers: List<Pair<String, String>>,
    val body: String,
    val raw: String,
) {
    fun headers(name: String): List<String> {
        val target = expandCompact(name.lowercase())
        return headers.filter { expandCompact(it.first.lowercase()) == target }.map { it.second.trim() }
    }

    fun header(name: String): String? = headers(name).firstOrNull()

    val viaBranch: String? get() {
        val via = header("via") ?: return null
        val m = Regex("""(?i)(?:^|;)\s*branch\s*=\s*([^\s;]+)""").find(via)
        return m?.groupValues?.get(1)
    }

    val viaTransport: String get() {
        val via = header("via") ?: return "UDP"
        val m = Regex("""(?i)SIP/2\.0/([A-Za-z]+)""").find(via)
        return m?.groupValues?.get(1)?.uppercase() ?: "UDP"
    }

    val callId: String? get() = header("call-id")

    private fun tagOf(headerName: String): String? {
        val v = header(headerName) ?: return null
        val m = Regex("""(?i)(?:^|;)\s*tag\s*=\s*([^\s;>]+)""").find(v)
        return m?.groupValues?.get(1)
    }

    val fromTag: String? get() = tagOf("from")
    val toTag: String? get() = tagOf("to")

    val cseqNum: Long? get() {
        val v = header("cseq") ?: return null
        return v.trim().split(Regex("""\s+""")).firstOrNull()?.toLongOrNull()
    }

    val cseqMethod: String? get() {
        val v = header("cseq") ?: return null
        val parts = v.trim().split(Regex("""\s+"""))
        return if (parts.size >= 2) parts[1].uppercase() else null
    }

    val recordRoutes: List<String> get() = headers("record-route")
    val routes: List<String> get() = headers("route")

    /** Stable message identity: transport-independent, survives connection reuse/reconnect. */
    val identity: String get() = listOf(
        viaBranch ?: "-", cseqNum?.toString() ?: "-", cseqMethod ?: (method ?: statusCode?.toString() ?: "-"),
        callId ?: "-", fromTag ?: "-", toTag ?: "-", bodyHash()
    ).joinToString("|")

    fun bodyHash(): String = sha1(body)

    val displayName: String get() =
        if (isRequest) "$method ${requestUri ?: ""}".trim()
        else "${statusCode ?: "?"} ${reasonPhrase ?: ""}".trim()

    companion object {
        private val compact = mapOf(
            "v" to "via", "f" to "from", "t" to "to", "i" to "call-id",
            "m" to "contact", "l" to "content-length", "c" to "content-type",
        )
        fun expandCompact(name: String): String = compact[name] ?: name
    }
}

fun parseSip(raw: String): SipMessage {
    if (raw.isBlank()) throw SipParseException("empty message")
    val sep = when {
        raw.contains("\r\n\r\n") -> "\r\n\r\n"
        raw.contains("\n\n") -> "\n\n"
        else -> throw SipParseException("missing header/body separator (blank line)")
    }
    val head = raw.substringBefore(sep)
    val body = raw.substringAfter(sep, "")
    val lines = head.split(Regex("\r\n|\n"))
    if (lines.isEmpty()) throw SipParseException("missing start line")
    val startLine = lines[0].trim()
    if (startLine.isEmpty()) throw SipParseException("empty start line")

    var isRequest = false
    var method: String? = null
    var requestUri: String? = null
    var statusCode: Int? = null
    var reasonPhrase: String? = null

    if (startLine.startsWith("SIP/2.0")) {
        val rest = startLine.removePrefix("SIP/2.0").trim()
        val code = rest.split(Regex("""\s+"""), limit = 2).firstOrNull()?.toIntOrNull()
            ?: throw SipParseException("malformed status line: '$startLine'")
        if (code < 100 || code > 699) throw SipParseException("status code out of range: $code")
        statusCode = code
        reasonPhrase = rest.split(Regex("""\s+"""), limit = 2).getOrNull(1) ?: ""
    } else {
        val parts = startLine.split(Regex("""\s+"""))
        if (parts.size < 3) throw SipParseException("malformed request line: '$startLine'")
        if (!parts.last().uppercase().startsWith("SIP/2.0"))
            throw SipParseException("request line missing SIP/2.0 version: '$startLine'")
        method = parts[0].uppercase()
        if (!method.matches(Regex("[A-Z]+"))) throw SipParseException("invalid method: '$method'")
        requestUri = parts[1]
        isRequest = true
    }

    val headers = mutableListOf<Pair<String, String>>()
    for (i in 1 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) continue
        if (line[0] == ' ' || line[0] == '\t') {
            if (headers.isEmpty()) throw SipParseException("header continuation without header at line ${i + 1}")
            val last = headers.removeLast()
            headers.add(last.first to (last.second + " " + line.trim()))
            continue
        }
        val colon = line.indexOf(':')
        if (colon <= 0) throw SipParseException("malformed header line ${i + 1}: '$line'")
        val name = line.substring(0, colon).trim()
        if (!name.matches(Regex("[A-Za-z0-9-]+"))) throw SipParseException("invalid header name at line ${i + 1}: '$name'")
        headers.add(name to line.substring(colon + 1).trim())
    }

    val msg = SipMessage(isRequest, method, requestUri, statusCode, reasonPhrase, headers, body, raw)
    if (msg.header("via") == null) throw SipParseException("missing Via header")
    if (msg.callId == null) throw SipParseException("missing Call-ID header")
    if (msg.header("cseq") == null) throw SipParseException("missing CSeq header")
    if (msg.cseqNum == null) throw SipParseException("malformed CSeq: '${msg.header("cseq")}'")
    if (isRequest && msg.cseqMethod != null && msg.cseqMethod != method)
        throw SipParseException("CSeq method '${msg.cseqMethod}' does not match request method '$method'")
    return msg
}

fun sha1(s: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
