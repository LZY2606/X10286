package sipgsb.sip

/** Strict-ish SIP message parser. Keeps raw representations; normalization happens in the engine. */
object Parser {

    private val compact = mapOf(
        "v" to "via", "i" to "call-id", "f" to "from", "t" to "to",
        "m" to "contact", "l" to "content-length", "r" to "record-route",
    )

    fun parse(raw: String): ParseOutcome {
        val normalized = raw.replace("\r\n", "\n")
        val sep = normalized.indexOf("\n\n")
        if (sep < 0) return ParseOutcome.Err("missing blank line between headers and body")
        val head = normalized.substring(0, sep)
        val lines = head.split("\n")
        if (lines.isEmpty() || lines[0].isBlank()) return ParseOutcome.Err("empty start line")

        // unfold continuation lines
        val unfolded = mutableListOf<String>()
        for (line in lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] = unfolded.last() + " " + line.trim()
            } else unfolded.add(line)
        }

        val startLine = unfolded[0].trim()
        var isRequest = false
        var method: String? = null
        var uri: String? = null
        var statusCode: Int? = null
        var statusText: String? = null
        when {
            startLine.startsWith("SIP/2.0 ") || startLine == "SIP/2.0" -> {
                val rest = startLine.removePrefix("SIP/2.0").trim()
                val sp = rest.indexOf(' ')
                val codeStr = if (sp < 0) rest else rest.substring(0, sp)
                statusCode = codeStr.toIntOrNull()
                    ?: return ParseOutcome.Err("bad status code in start line: '$codeStr'")
                statusText = if (sp < 0) "" else rest.substring(sp + 1).trim()
            }
            else -> {
                val parts = startLine.split(" ")
                if (parts.size < 3) return ParseOutcome.Err("bad request line: '$startLine'")
                if (!parts.last().startsWith("SIP/2.0"))
                    return ParseOutcome.Err("bad SIP version in request line: '$startLine'")
                isRequest = true
                method = parts[0]
                uri = parts.subList(1, parts.size - 1).joinToString(" ")
            }
        }

        val headers = linkedMapOf<String, MutableList<String>>()
        for (line in unfolded.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) return ParseOutcome.Err("malformed header line: '$line'")
            var name = line.substring(0, colon).trim().lowercase()
            name = compact[name] ?: name
            val value = line.substring(colon + 1).trim()
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }

        fun first(name: String): String? = headers[name]?.firstOrNull()

        val via = first("via") ?: return ParseOutcome.Err("missing required header: Via")
        val branch = run {
            val m = Regex(""";\s*branch\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE).find(via)
            m?.groupValues?.get(1) ?: return ParseOutcome.Err("Via header has no branch parameter")
        }
        val viaTransport = run {
            val m = Regex("""SIP/2\.0/([A-Za-z]+)""").find(via)
            (m?.groupValues?.get(1) ?: "UDP").uppercase()
        }
        val viaSentBy = via.removePrefix("SIP/2.0").removePrefix("/").substringAfter(" ").trim()
            .split(";").first().trim()
        val callId = first("call-id") ?: return ParseOutcome.Err("missing required header: Call-ID")
        val cseq = first("cseq") ?: return ParseOutcome.Err("missing required header: CSeq")
        val cseqParts = cseq.split(Regex("""\s+"""), limit = 2)
        if (cseqParts.size != 2) return ParseOutcome.Err("malformed CSeq: '$cseq'")
        val cseqNum = cseqParts[0].toLongOrNull()
            ?: return ParseOutcome.Err("malformed CSeq number: '${cseqParts[0]}'")
        val cseqMethod = cseqParts[1].trim().uppercase()
        val from = first("from") ?: return ParseOutcome.Err("missing required header: From")
        val to = first("to") ?: return ParseOutcome.Err("missing required header: To")
        fun tagOf(v: String): String? =
            Regex(""";\s*tag\s*=\s*([^;\s>]+)""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)
        val recordRoutes = headers["record-route"]?.flatMap { splitRouteHeader(it) } ?: emptyList()

        if (isRequest && method != cseqMethod)
            return ParseOutcome.Err("CSeq method '$cseqMethod' does not match request method '$method'")

        return ParseOutcome.Ok(
            SipMessage(
                isRequest = isRequest, method = method, uri = uri,
                statusCode = statusCode, statusText = statusText,
                branch = branch, viaSentBy = viaSentBy, viaTransport = viaTransport,
                callId = callId, fromTag = tagOf(from), toTag = tagOf(to),
                cseqNum = cseqNum, cseqMethod = cseqMethod,
                recordRoutes = recordRoutes, contact = first("contact"),
                headers = headers.mapValues { it.value.toList() },
            )
        )
    }

    /** Split a Record-Route header value on commas that are not inside <> or quotes. */
    fun splitRouteHeader(value: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inQuote = false
        var cur = StringBuilder()
        for (c in value) {
            when {
                c == '"' -> inQuote = !inQuote
                c == '<' && !inQuote -> depth++
                c == '>' && !inQuote -> depth--
                c == ',' && depth == 0 && !inQuote -> { out.add(cur.toString().trim()); cur = StringBuilder(); continue }
            }
            cur.append(c)
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    /** Parse the bulk import envelope format: blocks introduced by '=== message'. */
    fun parseBulk(text: String): List<Pair<Envelope?, String?>> {
        val results = mutableListOf<Pair<Envelope?, String?>>()
        val blocks = text.split(Regex("""^===\s*message\s*$""", RegexOption.MULTILINE))
        for ((idx, block) in blocks.withIndex()) {
            if (block.isBlank()) continue
            val normalized = block.replace("\r\n", "\n")
            val sep = normalized.indexOf("\n\n")
            if (sep < 0) {
                results.add(null to "block ${idx}: missing blank line between envelope meta and SIP message")
                continue
            }
            val meta = normalized.substring(0, sep).trim()
            val raw = normalized.substring(sep + 2).trimEnd()
            var direction: Direction? = null
            var conn = 1
            var at: Long? = null
            var transport = "udp"
            var metaError: String? = null
            for (line in meta.split("\n")) {
                if (line.isBlank()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) { metaError = "bad meta line: '$line'"; break }
                when (line.substring(0, colon).trim().lowercase()) {
                    "direction" -> direction = when (line.substring(colon + 1).trim().lowercase()) {
                        "c2s" -> Direction.C2S; "s2c" -> Direction.S2C
                        else -> { metaError = "bad direction: '$line'"; null }
                    }
                    "conn" -> conn = line.substring(colon + 1).trim().toIntOrNull()
                        ?: run { metaError = "bad conn epoch: '$line'"; 1 }
                    "at" -> at = line.substring(colon + 1).trim().toLongOrNull()
                        ?: run { metaError = "bad receive time: '$line'"; 0 }
                    "transport" -> transport = line.substring(colon + 1).trim().lowercase()
                    else -> { metaError = "unknown meta key: '$line'"; break }
                }
            }
            when {
                metaError != null -> results.add(null to "block $idx: $metaError")
                direction == null -> results.add(null to "block $idx: missing 'direction' meta")
                at == null -> results.add(null to "block $idx: missing 'at' (virtual receive time, ms) meta")
                else -> results.add(Envelope(direction!!, conn, at!!, transport, raw) to null)
            }
        }
        return results
    }
}
