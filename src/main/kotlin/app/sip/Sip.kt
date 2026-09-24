package app.sip

/** A parsed SIP message. [raw] always preserves the original bytes (ISO-8859-1 text). */
data class SipMessage(
    val raw: String,
    val isRequest: Boolean,
    val method: String?,
    val uri: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    /** Original header order and original (possibly compact) field names. */
    val headers: List<Pair<String, String>>,
) {
    fun headersOf(name: String): List<String> {
        val want = compactName(name)
        return headers.filter { compactName(it.first) == want }.map { it.second.trim() }
    }

    fun header(name: String): String? = headersOf(name).firstOrNull()

    val viaBranch: String? get() = header("Via")?.let { v ->
        Regex(""";\s*branch\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)
    }
    val viaTransport: String? get() = header("Via")?.let { v ->
        Regex("""SIP/2\.0/([A-Za-z]+)""").find(v)?.groupValues?.get(1)?.uppercase()
    }
    val viaSentBy: String? get() = header("Via")?.let { v ->
        Regex("""SIP/2\.0/[A-Za-z]+\s+([^;\s]+)""").find(v)?.groupValues?.get(1)
    }
    val callId: String? get() = header("Call-ID")
    val fromTag: String? get() = header("From")?.let { tagOf(it) }
    val toTag: String? get() = header("To")?.let { tagOf(it) }
    val cseqNum: Long? get() = header("CSeq")?.trim()?.split(Regex("""\s+"""))?.firstOrNull()?.toLongOrNull()
    val cseqMethod: String? get() = header("CSeq")?.trim()?.split(Regex("""\s+"""))?.getOrNull(1)?.uppercase()
    val recordRoutes: List<String> get() = headersOf("Record-Route").flatMap { splitUris(it) }
    val contacts: List<String> get() = headersOf("Contact").flatMap { splitUris(it) }

    /** Stable content identity: independent of connection generation or receive time. */
    fun fingerprint(): String = Sip.sha1(
        listOf(
            if (isRequest) "REQ:$method:$uri" else "RES:$statusCode",
            viaBranch ?: "", callId ?: "", fromTag ?: "", toTag ?: "",
            "${cseqNum ?: -1}:${cseqMethod ?: ""}", bodyOf(raw).trim()
        ).joinToString("|")
    )

    companion object {
        private fun tagOf(addr: String): String? =
            Regex(""";\s*tag\s*=\s*([^;\s>]+)""", RegexOption.IGNORE_CASE).find(addr)?.groupValues?.get(1)

        private fun splitUris(v: String): List<String> =
            v.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun bodyOf(raw: String): String {
            val i = raw.indexOf("\r\n\r\n")
            return if (i >= 0) raw.substring(i + 4) else ""
        }

        private fun compactName(name: String): String = when (name.lowercase()) {
            "f", "from" -> "from"
            "t", "to" -> "to"
            "i", "call-id" -> "call-id"
            "v", "via" -> "via"
            "m", "contact" -> "contact"
            "l", "content-length" -> "content-length"
            else -> name.lowercase()
        }
    }
}

class SipParseException(message: String) : Exception(message)

object Sip {
    fun sha1(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
    }

    /** Parse one raw SIP message; throws [SipParseException] with an explicit diagnostic. */
    fun parse(raw: String): SipMessage {
        if (raw.isBlank()) throw SipParseException("empty message block")
        val norm = raw.replace("\r\n", "\n")
        val headEnd = norm.indexOf("\n\n").let { if (it < 0) norm.length else it }
        val headLines = norm.substring(0, headEnd).split("\n").filter { it.isNotBlank() }
        if (headLines.isEmpty()) throw SipParseException("missing start line")
        val start = headLines[0].trim()

        val isRequest: Boolean
        var method: String? = null
        var uri: String? = null
        var statusCode: Int? = null
        var reason: String? = null
        if (start.startsWith("SIP/2.0")) {
            isRequest = false
            val parts = start.split(Regex("""\s+"""), limit = 3)
            if (parts.size < 2) throw SipParseException("malformed status line: '$start'")
            statusCode = parts[1].toIntOrNull()
                ?: throw SipParseException("non-numeric status code in: '$start'")
            if (statusCode !in 100..699) throw SipParseException("status code out of range: $statusCode")
            reason = parts.getOrNull(2) ?: ""
        } else {
            isRequest = true
            val parts = start.split(Regex("""\s+"""))
            if (parts.size != 3 || parts[2] != "SIP/2.0")
                throw SipParseException("malformed request line: '$start'")
            method = parts[0].uppercase()
            uri = parts[1]
        }

        val headers = mutableListOf<Pair<String, String>>()
        var lastIdx = -1
        for (line in headLines.drop(1)) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (lastIdx < 0) throw SipParseException("header continuation without a header: '$line'")
                headers[lastIdx] = headers[lastIdx].copy(second = headers[lastIdx].second + " " + line.trim())
            } else {
                val c = line.indexOf(':')
                if (c <= 0) throw SipParseException("malformed header line (no colon): '$line'")
                headers.add(line.substring(0, c).trim() to line.substring(c + 1).trim())
                lastIdx = headers.size - 1
            }
        }
        val msg = SipMessage(raw, isRequest, method, uri, statusCode, reason, headers)
        if (msg.header("Via") == null) throw SipParseException("missing required Via header")
        if (msg.callId == null) throw SipParseException("missing required Call-ID header")
        if (msg.header("CSeq") == null) throw SipParseException("missing required CSeq header")
        if (msg.cseqNum == null) throw SipParseException("CSeq number not numeric: '${msg.header("CSeq")}'")
        if (msg.header("From") == null) throw SipParseException("missing required From header")
        if (msg.header("To") == null) throw SipParseException("missing required To header")
        if (isRequest && msg.viaBranch == null) throw SipParseException("request Via missing branch parameter")
        return msg
    }
}
