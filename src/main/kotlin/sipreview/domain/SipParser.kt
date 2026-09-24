package sipreview.domain

import sipreview.model.*

/**
 * Lenient RFC 3261 parser working on immutable bytes. The original bytes are stored separately
 * and never mutated. Values that cannot be normalized remain in `raw` with normalized=null and a
 * warning, never dropped.
 */
object SipParser {

    data class Parsed(val fact: ParseFact? = null, val errors: List<String> = emptyList(), val warnings: List<String> = emptyList())

    fun parse(rawId: String, bytes: ByteArray, paramsFingerprint: String?): Parsed {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val text = String(bytes, Charsets.UTF_8)
        val normalizedNewlines = text.replace("\r\n", "\n")
        if (!text.contains("\r\n")) warnings += "CRLF line endings not observed; LF accepted for case replay"
        val blocks = normalizedNewlines.split("\n\n", "\r\n\r\n", limit = 2)
        val headerBlock = blocks[0].trimEnd('\r')
        val body = if (blocks.size > 1) blocks[1] else ""
        val lines = headerBlock.split("\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) errors += "empty message: no start line"
        val startLine = lines.firstOrNull() ?: return Parsed(errors = errors, warnings = warnings)

        val headerLines = mutableListOf<String>()
        for (line in lines.drop(1)) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (headerLines.isNotEmpty()) headerLines[headerLines.lastIndex] = headerLines.last() + " " + line.trim()
                else warnings += "continuation line before any header"
            } else headerLines += line
        }

        val headers = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
        headerLines.forEachIndexed { idx, h ->
            val colon = h.indexOf(':')
            if (colon <= 0) {
                warnings += "unparseable header line kept as raw text: ${h.take(40)}"
            } else {
                val name = h.substring(0, colon).trim()
                val value = h.substring(colon + 1).trim()
                headers.getOrPut(name.lowercase()) { mutableListOf() }.add(value to idx)
            }
        }

        val isRequest = startLine.startsWith("SIP/2.0 ").not() && startLine.contains(" SIP/2.0")
        val isResponse = startLine.startsWith("SIP/2.0 ")
        if (!isRequest && !isResponse) errors += "malformed start line: $startLine"

        var method: String? = null
        var requestUri: String? = null
        var statusCode: Int? = null
        var reason: String? = null
        if (isRequest) {
            val parts = startLine.split(" ")
            if (parts.size >= 3 && parts[1] == "SIP/2.0") {
                method = parts[0]; requestUri = parts[1]
            } else errors += "malformed request line: $startLine"
        }
        if (isResponse) {
            val parts = startLine.split(" ")
            if (parts.size >= 2) {
                statusCode = parts[1].toIntOrNull() ?: run { errors += "non-numeric status code"; null }
                reason = parts.drop(2).joinToString(" ")
            }
        }

        val viaTop = headers["via"]?.firstOrNull()?.let { (raw, _) -> parseVia(raw, warnings) }
        val callId = headers["call-id"]?.firstOrNull()?.first
        val from = headers["from"]?.firstOrNull()?.first?.let { parseNameAddr(it) }
        val to = headers["to"]?.firstOrNull()?.first?.let { parseNameAddr(it) }
        val contact = headers["contact"]?.firstOrNull()?.first?.let { parseNameAddr(it) }
        val cseq = headers["cseq"]?.firstOrNull()?.first?.let {
            val sp = it.indexOf(' ')
            if (sp > 0) it.substring(0, sp).toLongOrNull() to it.substring(sp + 1).trim()
            else null.let { _ ->
                warnings += "unparseable CSeq kept raw: $it"; null to null
            }
        } ?: (null to null)
        val recordRoutes = headers["record-route"].orEmpty().map { (raw, idx) ->
            RawNorm(raw, extractUri(raw), rawId, idx)
        }

        if (isRequest && method != "CANCEL" && method != "ACK" && viaTop?.branch == null)
            warnings += "top Via has no branch parameter"
        if (callId == null) warnings += "missing Call-ID"
        if (cseq.first == null && isRequest) warnings += "missing numeric CSeq"

        val kind = when {
            isRequest -> "request"
            isResponse -> "response"
            else -> "unknown"
        }
        val fact = ParseFact(
            factId = "fact-" + Digest.short("$rawId|$ALGO_VERSION|${bytes.size}|$headerBlock"),
            rawId = rawId,
            kind = kind,
            method = method,
            requestUri = requestUri,
            statusCode = statusCode,
            reason = reason,
            viaTop = viaTop,
            callId = callId,
            from = from,
            to = to,
            cseqNumber = cseq.first,
            cseqMethod = cseq.second,
            recordRoutes = recordRoutes,
            contact = contact,
            headerBytes = headerBlock.toByteArray().size,
            bodyBytes = body.toByteArray().size,
            warnings = warnings,
            provenance = Derivation(
                algo = "rfc3261-lenient-parse",
                paramsFingerprint = paramsFingerprint,
                sourceRefs = listOf(rawId),
            ),
        )
        return Parsed(fact, errors, warnings)
    }

    private fun parseVia(raw: String, warnings: MutableList<String>): Via {
        val rest = raw.removePrefix("SIP/2.0/").trim()
        val semi = rest.indexOf(';')
        val sentBy = if (semi >= 0) rest.substring(0, semi).trim() else rest.trim()
        val transport = if (raw.startsWith("SIP/2.0/")) raw.removePrefix("SIP/2.0/").takeWhile { it != ' ' && it != ';' }.uppercase() else null
        val params = if (semi >= 0) rest.substring(semi + 1).split(";") else emptyList()
        val branch = params.firstOrNull { it.trim().startsWith("branch=") }?.substringAfter("branch=")?.trim()
        val rport = params.any { it.trim() == "rport" || it.trim().startsWith("rport=") }
        if (branch != null && !branch.startsWith("z9hG4bK"))
            warnings += "branch lacks RFC3261 magic cookie (legacy): $branch"
        return Via(raw = raw, transport = transport, sentBy = sentBy, branch = branch, rport = rport)
    }

    private val NAME_ADDR = Regex("""^\s*(?:"[^"]*"\s*)?<([^>]+)>(.*)$""")

    fun parseNameAddr(raw: String): NameAddr {
        val m = NAME_ADDR.matchEntire(raw)
        if (m != null) {
            val params = m.groupValues[2]
            val tag = Regex("tag=([^;\\s]+)").find(params)?.groupValues?.get(1)
            return NameAddr(raw, m.groupValues[1].trim(), tag)
        }
        val looseUri = raw.trim().split(";").firstOrNull()?.takeIf { it.contains(":") }
        val tag = Regex("tag=([^;\\s>]+)").find(raw)?.groupValues?.get(1)
        return NameAddr(raw, looseUri?.trim(), tag)
    }

    fun extractUri(raw: String): String? {
        val angle = Regex("<([^>]+)>").find(raw)
        if (angle != null) return angle.groupValues[1].trim()
        return raw.trim().split(";").firstOrNull()?.takeIf { it.contains(":") }?.trim()
    }
}
