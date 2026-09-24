package sipatlas

/** Direction of a message on the wire, as recorded by the capturing operator. */
enum class Direction { C2S, S2C }

class SipParseException(message: String) : Exception(message)

/**
 * A parsed SIP message. The original raw bytes are always retained in [raw];
 * every field here is a derived, normalized view produced by the parser
 * (algorithm version [PARSER_ALGO_VERSION]).
 */
data class SipMessage(
    val isRequest: Boolean,
    val method: String?,
    val requestUri: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val viaBranch: String?,
    val viaSentBy: String?,
    val viaTransport: String?,
    val callId: String?,
    val fromTag: String?,
    val toTag: String?,
    val cseqNum: Long?,
    val cseqMethod: String?,
    val recordRoutes: List<String>,
    val routes: List<String>,
    val contact: String?,
    val raw: String,
) {
    /** Effective method: request method, or CSeq method for responses. */
    val effectiveMethod: String? get() = if (isRequest) method else cseqMethod

    /** Dialog identifier tuple (call-id, local tag, remote tag) seen from the UAC side. */
    fun dialogKeyUacSide(): Triple<String?, String?, String?> =
        Triple(callId, fromTag, toTag)

    companion object {
        const val PARSER_ALGO_VERSION = "sip-parse/1.1.0"
        const val MAGIC_COOKIE = "z9hG4bK"

        private val COMPACT = mapOf(
            "v" to "via", "i" to "call-id", "f" to "from", "t" to "to",
            "m" to "contact", "l" to "content-length", "k" to "supported",
            "c" to "content-type", "e" to "content-encoding", "s" to "subject",
        )

        fun parse(raw: String): SipMessage {
            val normalized = raw.replace("\r\n", "\n")
            val headEnd = normalized.indexOf("\n\n").let { if (it < 0) normalized.length else it }
            val head = normalized.substring(0, headEnd)
            val lines = head.split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) throw SipParseException("empty message head")

            val startLine = lines[0].trim()
            var isRequest = false
            var method: String? = null
            var requestUri: String? = null
            var statusCode: Int? = null
            var reasonPhrase: String? = null

            when {
                startLine.startsWith("SIP/2.0") -> {
                    val rest = startLine.removePrefix("SIP/2.0").trim()
                    val sp = rest.indexOf(' ')
                    val codeStr = if (sp < 0) rest else rest.substring(0, sp)
                    statusCode = codeStr.toIntOrNull()
                        ?: throw SipParseException("malformed status code in '$startLine'")
                    if (statusCode !in 100..699) throw SipParseException("status code out of range: $statusCode")
                    reasonPhrase = if (sp < 0) "" else rest.substring(sp + 1).trim()
                }
                else -> {
                    val parts = startLine.split(" ")
                    if (parts.size < 3) throw SipParseException("malformed request line '$startLine'")
                    if (!parts.last().startsWith("SIP/2.0"))
                        throw SipParseException("missing SIP/2.0 version in '$startLine'")
                    isRequest = true
                    method = parts[0].uppercase()
                    if (!method.all { it.isLetter() }) throw SipParseException("malformed method '$method'")
                    requestUri = parts[1]
                }
            }

            // headers, with line folding support
            val headers = LinkedHashMap<String, MutableList<String>>()
            var currentName: String? = null
            for (i in 1 until lines.size) {
                val line = lines[i]
                if ((line.startsWith(" ") || line.startsWith("\t")) && currentName != null) {
                    val list = headers[currentName]!!
                    list[list.size - 1] = list.last() + " " + line.trim()
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0) throw SipParseException("malformed header line '$line'")
                var name = line.substring(0, colon).trim().lowercase()
                name = COMPACT[name] ?: name
                val value = line.substring(colon + 1).trim()
                headers.getOrPut(name) { mutableListOf() }.add(value)
                currentName = name
            }

            fun first(name: String): String? = headers[name]?.firstOrNull()

            val via = first("via") ?: throw SipParseException("missing required Via header")
            val viaParams = parseParams(via)
            val viaBranch = viaParams.second["branch"]
            val viaMain = via.substringBefore(';').trim()
            val viaTokens = viaMain.split(Regex("\\s+"))
            val viaTransport = viaTokens.getOrNull(1)?.substringAfter("SIP/2.0/")?.uppercase()
                ?: viaTokens.getOrNull(0)?.substringAfter("SIP/2.0/")?.uppercase()
            val viaSentBy = viaTokens.lastOrNull()

            val callId = first("call-id") ?: throw SipParseException("missing required Call-ID header")
            val cseq = first("cseq") ?: throw SipParseException("missing required CSeq header")
            val cseqParts = cseq.split(Regex("\\s+"), limit = 2)
            val cseqNum = cseqParts[0].toLongOrNull()
                ?: throw SipParseException("malformed CSeq number in '$cseq'")
            val cseqMethod = cseqParts.getOrNull(1)?.uppercase()
                ?: throw SipParseException("missing CSeq method in '$cseq'")
            if (isRequest && cseqMethod != method)
                throw SipParseException("CSeq method '$cseqMethod' does not match request method '$method'")

            val from = first("from") ?: throw SipParseException("missing required From header")
            val to = first("to") ?: throw SipParseException("missing required To header")
            val fromTag = parseParams(from).second["tag"]
            val toTag = parseParams(to).second["tag"]

            fun headerList(name: String): List<String> =
                (headers[name] ?: emptyList()).flatMap { splitHeaderList(it) }

            return SipMessage(
                isRequest = isRequest,
                method = method,
                requestUri = requestUri,
                statusCode = statusCode,
                reasonPhrase = reasonPhrase,
                viaBranch = viaBranch,
                viaSentBy = viaSentBy,
                viaTransport = viaTransport,
                callId = callId,
                fromTag = fromTag,
                toTag = toTag,
                cseqNum = cseqNum,
                cseqMethod = cseqMethod,
                recordRoutes = headerList("record-route"),
                routes = headerList("route"),
                contact = first("contact")?.substringBefore(';')?.trim()?.trim('<', '>'),
                raw = raw,
            )
        }

        /** Splits a header value on commas that are not inside quotes or angle brackets. */
        fun splitHeaderList(value: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuote = false
            var angleDepth = 0
            for (ch in value) {
                when {
                    ch == '"' -> { inQuote = !inQuote; sb.append(ch) }
                    ch == '<' && !inQuote -> { angleDepth++; sb.append(ch) }
                    ch == '>' && !inQuote -> { angleDepth--; sb.append(ch) }
                    ch == ',' && !inQuote && angleDepth == 0 -> {
                        if (sb.isNotBlank()) out.add(sb.toString().trim())
                        sb.clear()
                    }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotBlank()) out.add(sb.toString().trim())
            return out
        }

        /** Parses "main;param=value;param2" into main value and param map. */
        fun parseParams(value: String): Pair<String, Map<String, String>> {
            val parts = value.split(';')
            val params = LinkedHashMap<String, String>()
            for (i in 1 until parts.size) {
                val eq = parts[i].indexOf('=')
                if (eq < 0) params[parts[i].trim().lowercase()] = ""
                else params[parts[i].substring(0, eq).trim().lowercase()] =
                    parts[i].substring(eq + 1).trim().trim('"')
            }
            return parts[0].trim() to params
        }
    }
}
