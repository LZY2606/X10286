package sipgsb

/** Tolerant SIP message parser. Raw bytes are always preserved by the caller;
 *  unparseable messages stay in place with a diagnostic instead of being dropped. */
object SipParser {

    private val compact = mapOf(
        "f" to "from", "t" to "to", "i" to "call-id", "v" to "via",
        "m" to "contact", "l" to "content-length", "r" to "record-route"
    )

    class ParseException(msg: String) : Exception(msg)

    fun parse(raw: String): SipMessage {
        val normalized = raw.replace("\r\n", "\n")
        val head = normalized.substringBefore("\n\n")
        val lines = head.split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) throw ParseException("empty message")
        val startLine = lines[0].trim()

        val headers = LinkedHashMap<String, MutableList<String>>()
        var currentName: String? = null
        for (line in lines.drop(1)) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                val n = currentName ?: throw ParseException("header continuation without header")
                headers[n]!!.add(line.trim())
                continue
            }
            val idx = line.indexOf(':')
            if (idx <= 0) throw ParseException("malformed header line: ${line.trim()}")
            var name = line.substring(0, idx).trim().lowercase()
            name = compact[name] ?: name
            currentName = name
            headers.getOrPut(name) { mutableListOf() }.add(line.substring(idx + 1).trim())
        }

        val isRequest: Boolean
        var method: String? = null
        var requestUri: String? = null
        var statusCode: Int? = null
        var reason: String? = null
        if (startLine.startsWith("SIP/2.0")) {
            isRequest = false
            val parts = startLine.split(" ", limit = 3)
            if (parts.size < 2) throw ParseException("malformed status line")
            statusCode = parts[1].toIntOrNull() ?: throw ParseException("bad status code: ${parts[1]}")
            reason = if (parts.size > 2) parts[2] else ""
        } else {
            isRequest = true
            val parts = startLine.split(" ")
            if (parts.size < 3 || !parts.last().startsWith("SIP/2.0"))
                throw ParseException("malformed request line: $startLine")
            method = parts[0].uppercase()
            requestUri = parts[1]
        }

        fun first(name: String) = headers[name]?.firstOrNull()

        val via = first("via")
        val viaBranch = via?.let { v ->
            Regex("""branch=([^;\s]+)""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)
        }
        val viaSentBy = via?.let { v ->
            // "SIP/2.0/UDP host:port;branch=..."
            val m = Regex("""SIP/2\.0/\w+\s+([^;\s]+)""", RegexOption.IGNORE_CASE).find(v)
            m?.groupValues?.get(1)
        }
        val viaTransport = via?.let { v ->
            Regex("""SIP/2\.0/(\w+)""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)?.uppercase()
        }

        fun tagOf(header: String?): String? = header?.let {
            Regex("""tag=([^;\s>]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }

        val cseq = first("cseq")
        val cseqParts = cseq?.trim()?.split(Regex("""\s+"""), limit = 2)
        val cseqNum = cseqParts?.getOrNull(0)?.toLongOrNull()
        val cseqMethod = cseqParts?.getOrNull(1)
        if (cseq != null && cseqNum == null) throw ParseException("bad CSeq: $cseq")

        fun addrList(name: String): List<String> =
            headers[name]?.flatMap { splitTopLevel(it) }?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()

        return SipMessage(
            isRequest = isRequest,
            method = method,
            requestUri = requestUri,
            statusCode = statusCode,
            reasonPhrase = reason,
            viaBranch = viaBranch,
            viaSentBy = viaSentBy,
            viaTransport = viaTransport,
            callId = first("call-id"),
            fromTag = tagOf(first("from")),
            toTag = tagOf(first("to")),
            cseqNum = cseqNum,
            cseqMethod = cseqMethod,
            recordRoute = addrList("record-route"),
            route = addrList("route"),
            contact = first("contact"),
            startLine = startLine
        )
    }

    /** Split a header list on commas that are not inside <...> or quotes. */
    private fun splitTopLevel(value: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inQuote = false
        val sb = StringBuilder()
        for (ch in value) {
            when {
                ch == '"' -> { inQuote = !inQuote; sb.append(ch) }
                ch == '<' && !inQuote -> { depth++; sb.append(ch) }
                ch == '>' && !inQuote -> { depth--; sb.append(ch) }
                ch == ',' && depth == 0 && !inQuote -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }
}
