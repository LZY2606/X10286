package sipatlas

/** A parsed SIP message. The original raw bytes are always preserved alongside. */
data class SipMessage(
    val isRequest: Boolean,
    val method: String?,          // request method; for responses the CSeq method
    val statusCode: Int?,         // responses only
    val reason: String?,          // responses only
    val requestUri: String?,      // requests only
    val headers: Map<String, List<String>>, // lower-cased header names, in order
    val raw: String,
) {
    fun header(name: String): String? = headers[name]?.firstOrNull()
    fun headersAll(name: String): List<String> = headers[name] ?: emptyList()

    val callId: String? get() = header("call-id")

    data class CSeq(val num: Long, val method: String)
    val cseq: CSeq? get() = header("cseq")?.trim()?.split(Regex("\\s+"), limit = 2)?.let {
        val n = it[0].toLongOrNull() ?: return null
        CSeq(n, if (it.size > 1) it[1].uppercase() else "")
    }
    /** Effective method: request line method, or CSeq method for responses. */
    val effectiveMethod: String? get() = if (isRequest) method else cseq?.method

    data class Via(val transport: String?, val sentBy: String?, val branch: String?, val raw: String)
    val topVia: Via? get() = headersAll("via").firstOrNull()?.let { parseVia(it) }

    val viaBranch: String? get() = topVia?.branch
    val viaSentBy: String? get() = topVia?.sentBy
    val viaTransport: String? get() = topVia?.transport

    val fromTag: String? get() = tagParam(header("from"))
    val toTag: String? get() = tagParam(header("to"))

    val recordRoutes: List<String> get() = headersAll("record-route").flatMap { splitCommas(it) }
    val routes: List<String> get() = headersAll("route").flatMap { splitCommas(it) }
    val contacts: List<String> get() = headersAll("contact").flatMap { splitCommas(it) }

    companion object {
        private val COMPACT = mapOf(
            "i" to "call-id", "f" to "from", "t" to "to", "v" to "via",
            "m" to "contact", "l" to "content-length", "k" to "supported",
            "c" to "content-type", "o" to "event", "r" to "refer-to",
            "s" to "subject", "u" to "allow-events", "e" to "content-encoding",
        )

        fun parse(raw: String): SipMessage {
            val sep = raw.indexOf("\r\n\r\n").takeIf { it >= 0 }
                ?: raw.indexOf("\n\n").takeIf { it >= 0 } ?: raw.length
            val head = raw.substring(0, sep)
            val lines = head.split(Regex("\r\n|\n"))
            require(lines.isNotEmpty() && lines[0].isNotBlank()) { "empty start line" }
            val start = lines[0].trim()

            // unfold continuation lines, then split name: value
            val unfolded = mutableListOf<String>()
            for (line in lines.drop(1)) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                    unfolded[unfolded.lastIndex] = unfolded.last() + " " + line.trim()
                } else if (line.isNotBlank()) unfolded.add(line)
            }
            val headers = linkedMapOf<String, MutableList<String>>()
            for (line in unfolded) {
                val colon = line.indexOf(':')
                require(colon > 0) { "malformed header line: $line" }
                var name = line.substring(0, colon).trim().lowercase()
                name = COMPACT[name] ?: name
                headers.getOrPut(name) { mutableListOf() }.add(line.substring(colon + 1).trim())
            }

            return if (start.startsWith("SIP/2.0")) {
                val rest = start.removePrefix("SIP/2.0").trim()
                val sp = rest.indexOf(' ')
                val code = (if (sp < 0) rest else rest.substring(0, sp)).toIntOrNull()
                    ?: throw IllegalArgumentException("bad status line: $start")
                SipMessage(false, null, code, if (sp < 0) "" else rest.substring(sp + 1),
                    null, headers.mapValues { it.value.toList() }, raw)
                    .let { m -> m.copy(method = m.cseq?.method) }
            } else {
                val parts = start.split(Regex("\\s+"))
                require(parts.size >= 3 && parts.last().startsWith("SIP/2.0")) { "bad request line: $start" }
                SipMessage(true, parts[0].uppercase(), null, null, parts[1],
                    headers.mapValues { it.value.toList() }, raw)
            }
        }

        fun parseVia(value: String): Via {
            val first = splitCommas(value).first()
            val semi = first.split(';')
            val viaPart = semi[0].trim() // SIP/2.0/UDP host:port
            val tokens = viaPart.split(Regex("\\s+"))
            val transport = tokens.getOrNull(0)?.substringAfterLast('/')?.uppercase()
            val sentBy = tokens.getOrNull(1)
            val branch = semi.drop(1).map { it.trim() }
                .firstOrNull { it.lowercase().startsWith("branch=") }?.substringAfter('=')
            return Via(transport, sentBy, branch, value)
        }

        fun tagParam(headerValue: String?): String? = headerValue
            ?.split(';')?.drop(1)?.map { it.trim() }
            ?.firstOrNull { it.lowercase().startsWith("tag=") }?.substringAfter('=')

        /** Split a header value on commas that are not inside quotes or angle brackets. */
        fun splitCommas(value: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0; var inQuote = false; var cur = StringBuilder()
            for (c in value) {
                when {
                    c == '"' -> { inQuote = !inQuote; cur.append(c) }
                    c == '<' && !inQuote -> { depth++; cur.append(c) }
                    c == '>' && !inQuote -> { depth--; cur.append(c) }
                    c == ',' && !inQuote && depth == 0 -> { out.add(cur.toString().trim()); cur = StringBuilder() }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotBlank()) out.add(cur.toString().trim())
            return out
        }
    }
}
