package siprecon.sip

/** A parsed SIP message. Raw bytes are always preserved; derived fields never overwrite them. */
data class SipMessage(
    val isRequest: Boolean,
    val method: String?,          // request method, or method from CSeq for responses
    val statusCode: Int?,         // responses only
    val reasonPhrase: String?,
    val requestUri: String?,
    val viaBranch: String,        // top Via branch (may be "")
    val viaTransport: String,     // UDP / TCP / ...
    val viaSentBy: String,
    val callId: String,
    val fromTag: String,
    val toTag: String,
    val cseqNum: Long,
    val cseqMethod: String,
    val recordRoute: List<String>,
    val contact: String?,
    val headers: List<Pair<String, String>>,
    val raw: String,
)

class SipParseException(val lineNo: Int, message: String) : Exception("line $lineNo: $message")

object SipParser {
    private val REQUEST_LINE = Regex("""^([A-Z][A-Z0-9_-]*)\s+(\S+)\s+SIP/2\.0\s*$""")
    private val STATUS_LINE = Regex("""^SIP/2\.0\s+(\d{3})\s*(.*)$""")

    fun parse(raw: String): SipMessage {
        val text = raw.replace("\r\n", "\n")
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0].isBlank()) throw SipParseException(1, "empty start line")
        val start = lines[0].trim()
        val reqMatch = REQUEST_LINE.matchEntire(start)
        val stMatch = STATUS_LINE.matchEntire(start)
        if (reqMatch == null && stMatch == null)
            throw SipParseException(1, "bad start line: '$start' (expected 'METHOD uri SIP/2.0' or 'SIP/2.0 nnn reason')")

        val headers = mutableListOf<Pair<String, String>>()
        var i = 1
        while (i < lines.size) {
            val ln = lines[i]
            if (ln.isBlank()) break
            if ((ln.startsWith(" ") || ln.startsWith("\t")) && headers.isNotEmpty()) {
                val last = headers.removeLast()
                headers.add(last.first to (last.second + " " + ln.trim()))
                i++; continue
            }
            val colon = ln.indexOf(':')
            if (colon <= 0) throw SipParseException(i + 1, "malformed header: '$ln'")
            headers.add(ln.substring(0, colon).trim() to ln.substring(colon + 1).trim())
            i++
        }

        fun all(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }
        fun one(name: String) = all(name).firstOrNull()

        val via = one("Via") ?: one("v")
        if (via == null) throw SipParseException(1, "missing required Via header")
        val viaTransport = Regex("""SIP/2\.0/([A-Za-z]+)""").find(via)?.groupValues?.get(1)?.uppercase()
            ?: throw SipParseException(1, "Via has no transport: '$via'")
        val viaBranch = Regex(""";branch=([^;\s]+)""").find(via)?.groupValues?.get(1) ?: ""
        val viaSentBy = via.removePrefix("SIP/2.0/").substringAfter('/').split(';')[0].trim()

        val callId = one("Call-ID") ?: one("i")
        if (callId == null) throw SipParseException(1, "missing required Call-ID header")

        fun tagOf(h: String?) = h?.let { Regex(""";tag=([^;\s>]+)""").find(it)?.groupValues?.get(1) } ?: ""
        val fromTag = tagOf(one("From") ?: one("f"))
        if (one("From") == null && one("f") == null) throw SipParseException(1, "missing required From header")
        val toTag = tagOf(one("To") ?: one("t"))

        val cseq = one("CSeq") ?: throw SipParseException(1, "missing required CSeq header")
        val cseqParts = cseq.split(Regex("""\s+"""), limit = 2)
        val cseqNum = cseqParts[0].toLongOrNull()
            ?: throw SipParseException(1, "CSeq number not numeric: '$cseq'")
        val cseqMethod = cseqParts.getOrElse(1) { "" }.uppercase()

        val recordRoute = (all("Record-Route")).flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        val contact = one("Contact") ?: one("m")

        return SipMessage(
            isRequest = reqMatch != null,
            method = reqMatch?.groupValues?.get(1) ?: cseqMethod.ifEmpty { null },
            statusCode = stMatch?.groupValues?.get(1)?.toInt(),
            reasonPhrase = stMatch?.groupValues?.get(2)?.ifBlank { null },
            requestUri = reqMatch?.groupValues?.get(2),
            viaBranch = viaBranch,
            viaTransport = viaTransport,
            viaSentBy = viaSentBy,
            callId = callId,
            fromTag = fromTag,
            toTag = toTag,
            cseqNum = cseqNum,
            cseqMethod = cseqMethod,
            recordRoute = recordRoute,
            contact = contact,
            headers = headers,
            raw = raw,
        )
    }
}
