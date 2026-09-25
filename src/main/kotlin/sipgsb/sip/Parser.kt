package sipgsb.sip

/** Minimal, strict-enough SIP message parser. Raw bytes are always preserved. */
object Parser {

    fun parse(
        raw: String,
        direction: Direction,
        transport: Transport,
        connGen: Int,
        recvMs: Long,
    ): ParseOutcome {
        val errors = mutableListOf<String>()
        val normalized = raw.replace("\r\n", "\n")
        val head = normalized.substringBefore("\n\n")
        val lines = head.split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return fail(raw, direction, transport, connGen, recvMs, listOf("empty message head"))
        }

        // Unfold folded header lines.
        val unfolded = mutableListOf<String>()
        for (line in lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] = unfolded.last() + " " + line.trim()
            } else {
                unfolded.add(line)
            }
        }

        val startLine = unfolded.first()
        var isRequest = false
        var method: String? = null
        var statusCode: Int? = null
        var reason: String? = null
        if (startLine.startsWith("SIP/2.0")) {
            val parts = startLine.split(" ", limit = 3)
            if (parts.size < 2 || parts[1].toIntOrNull() == null) {
                errors.add("malformed status line: '$startLine'")
            } else {
                statusCode = parts[1].toInt()
                reason = if (parts.size > 2) parts[2] else ""
            }
        } else {
            val parts = startLine.split(" ")
            if (parts.size != 3 || !parts[2].startsWith("SIP/2.0")) {
                errors.add("malformed request line: '$startLine'")
            } else {
                isRequest = true
                method = parts[0].uppercase()
            }
        }

        val headers = linkedMapOf<String, MutableList<String>>()
        for (line in unfolded.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) {
                errors.add("malformed header line: '$line'")
                continue
            }
            val name = canonicalHeader(line.substring(0, idx).trim())
            headers.getOrPut(name) { mutableListOf() }.add(line.substring(idx + 1).trim())
        }

        fun first(name: String): String? = headers[name]?.firstOrNull()

        val via = first("Via")
        if (via == null) errors.add("missing Via header")
        val branch = via?.let { v ->
            Regex("""(?i)(?:^|;)\s*branch=([^;\s]+)""").find(v)?.groupValues?.get(1)
        }
        if (via != null && branch == null) errors.add("Via header has no branch parameter")

        val callId = first("Call-ID")
        if (callId == null) errors.add("missing Call-ID header")

        val cseq = first("CSeq")
        var cseqNum: Long? = null
        var cseqMethod: String? = null
        if (cseq == null) {
            errors.add("missing CSeq header")
        } else {
            val m = Regex("""^\s*(\d+)\s+([A-Za-z]+)\s*$""").find(cseq)
            if (m == null) {
                errors.add("malformed CSeq header: '$cseq'")
            } else {
                cseqNum = m.groupValues[1].toLong()
                cseqMethod = m.groupValues[2].uppercase()
            }
        }
        if (isRequest && cseqMethod != null && method != null && cseqMethod != method) {
            errors.add("CSeq method '$cseqMethod' does not match request method '$method'")
        }

        fun tagOf(header: String?): String? =
            header?.let { Regex("""(?i)(?:^|;)\s*tag=([^;\s>]+)""").find(it)?.groupValues?.get(1) }

        val fromTag = tagOf(first("From"))
        val toTag = tagOf(first("To"))
        if (first("From") == null) errors.add("missing From header")
        if (first("To") == null) errors.add("missing To header")

        val recordRoutes = headers["Record-Route"].orEmpty()
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (errors.isNotEmpty()) {
            return fail(raw, direction, transport, connGen, recvMs, errors)
        }

        val msg = SipMessage(
            id = SipMessage.messageId(raw, direction, connGen, recvMs),
            raw = raw,
            direction = direction,
            transport = transport,
            connGen = connGen,
            recvMs = recvMs,
            isRequest = isRequest,
            method = method,
            statusCode = statusCode,
            reason = reason,
            branch = branch!!,
            callId = callId!!,
            fromTag = fromTag,
            toTag = toTag,
            cseqNum = cseqNum!!,
            cseqMethod = cseqMethod ?: method ?: "",
            recordRoutes = recordRoutes,
            rawSha = sha256(raw),
        )
        return ParseOutcome.Ok(msg)
    }

    private fun fail(
        raw: String, direction: Direction, transport: Transport, connGen: Int, recvMs: Long,
        errors: List<String>,
    ): ParseOutcome.Failed =
        ParseOutcome.Failed(ParseDiagnostic(raw, direction, transport, connGen, recvMs, errors))

    private fun canonicalHeader(name: String): String = when (name.lowercase()) {
        "via", "v" -> "Via"
        "call-id", "i" -> "Call-ID"
        "from", "f" -> "From"
        "to", "t" -> "To"
        "cseq" -> "CSeq"
        "record-route" -> "Record-Route"
        "route" -> "Route"
        "contact", "m" -> "Contact"
        else -> name.lowercase().split("-").joinToString("-") { n ->
            n.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it }
        }
    }
}
