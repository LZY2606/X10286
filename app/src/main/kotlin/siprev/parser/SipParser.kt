package siprev.parser

import siprev.model.CSeq
import siprev.model.NameAddr
import siprev.model.SipMessage
import siprev.model.ViaHeader

/**
 * Tolerant SIP message parser. Never throws on malformed input; instead it
 * records parse errors and returns whatever structure could be recovered, so
 * that raw bytes stay in place with diagnostics attached.
 */
object SipParser {

    private val tokenChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-.!%*_+`'~".toSet()

    fun parse(raw: String): SipMessage {
        val errors = mutableListOf<String>()
        val normalized = raw.replace("\r\n", "\n")
        val headEnd = normalized.indexOf("\n\n")
        val head: String
        val body: String
        if (headEnd >= 0) {
            head = normalized.substring(0, headEnd)
            body = normalized.substring(headEnd + 2)
        } else {
            head = normalized
            body = ""
            if (normalized.isNotBlank()) errors.add("missing header/body separator (empty line)")
        }
        val lines = head.split("\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            errors.add("empty message")
            return SipMessage(isRequest = false, parseErrors = errors)
        }

        // unfold continuation lines
        val unfolded = mutableListOf<String>()
        for (line in lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] = unfolded.last() + " " + line.trim()
            } else {
                unfolded.add(line)
            }
        }

        val startLine = unfolded.first().trim()
        var isRequest = false
        var method: String? = null
        var requestUri: String? = null
        var statusCode: Int? = null
        var reasonPhrase: String? = null
        if (startLine.startsWith("SIP/2.0")) {
            val rest = startLine.removePrefix("SIP/2.0").trim()
            val sp = rest.indexOf(' ')
            val codeText = if (sp >= 0) rest.substring(0, sp) else rest
            statusCode = codeText.toIntOrNull()
            if (statusCode == null) errors.add("malformed status line: '$startLine'")
            reasonPhrase = if (sp >= 0) rest.substring(sp + 1).trim() else ""
        } else {
            val parts = startLine.split(" ")
            if (parts.size >= 3 && parts.last().startsWith("SIP/2.0")) {
                isRequest = true
                method = parts[0].uppercase()
                requestUri = parts.subList(1, parts.size - 1).joinToString(" ")
                if (method.any { it !in tokenChars }) errors.add("suspicious method token: '$method'")
            } else {
                errors.add("unrecognized start line: '$startLine'")
            }
        }

        val headers = linkedMapOf<String, MutableList<String>>()
        for (line in unfolded.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) {
                errors.add("malformed header line: '$line'")
                continue
            }
            val name = canonicalHeaderName(line.substring(0, colon).trim())
            val value = line.substring(colon + 1).trim()
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }

        val vias = headers["via"].orEmpty().flatMap { splitTopLevel(it, ',') }.mapNotNull {
            parseVia(it.trim(), errors)
        }
        val from = headers["from"]?.firstOrNull()?.let { parseNameAddr(it, errors) }
        val to = headers["to"]?.firstOrNull()?.let { parseNameAddr(it, errors) }
        val callId = headers["call-id"]?.firstOrNull()?.trim()
        if (callId == null) errors.add("missing Call-ID")
        val cseq = headers["cseq"]?.firstOrNull()?.let { parseCSeq(it, errors) }
        if (cseq == null) errors.add("missing or malformed CSeq")
        val recordRoutes = headers["record-route"].orEmpty()
            .flatMap { splitTopLevel(it, ',') }.map { parseNameAddr(it.trim(), errors) }
        val routes = headers["route"].orEmpty()
            .flatMap { splitTopLevel(it, ',') }.map { parseNameAddr(it.trim(), errors) }
        val contacts = headers["contact"].orEmpty()
            .flatMap { splitTopLevel(it, ',') }.map { parseNameAddr(it.trim(), errors) }
        val maxForwards = headers["max-forwards"]?.firstOrNull()?.trim()?.toIntOrNull()
        val contentLength = headers["content-length"]?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        if (headers.containsKey("content-length") && contentLength == 0 &&
            headers["content-length"]!!.first().trim() != "0"
        ) {
            errors.add("malformed Content-Length")
        }
        if (from == null) errors.add("missing or malformed From")
        if (to == null) errors.add("missing or malformed To")
        if (vias.isEmpty()) errors.add("missing Via")

        return SipMessage(
            isRequest = isRequest,
            method = method,
            requestUri = requestUri,
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            vias = vias,
            from = from,
            to = to,
            callId = callId,
            cseq = cseq,
            recordRoutes = recordRoutes,
            routes = routes,
            contacts = contacts,
            maxForwards = maxForwards,
            contentLength = contentLength,
            headers = headers.mapValues { it.value.toList() },
            body = body,
            parseErrors = errors
        )
    }

    private fun canonicalHeaderName(raw: String): String {
        val compact = mapOf(
            "v" to "via", "f" to "from", "t" to "to", "i" to "call-id",
            "m" to "contact", "l" to "content-length"
        )
        val lower = raw.lowercase()
        return compact[lower] ?: lower
    }

    /** Split on a delimiter that is not inside <> or quotes. */
    fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inQuotes = false
        var current = StringBuilder()
        for (ch in text) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == '<' && !inQuotes -> depth++
                ch == '>' && !inQuotes -> depth--
                ch == delimiter && !inQuotes && depth == 0 -> {
                    out.add(current.toString())
                    current = StringBuilder()
                    continue
                }
            }
            current.append(ch)
        }
        out.add(current.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseVia(text: String, errors: MutableList<String>): ViaHeader? {
        // Via: SIP/2.0/UDP host:port;branch=z9hG4bK...;rport
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2) {
            errors.add("malformed Via: '$text'")
            return null
        }
        val protoParts = parts[0].split("/")
        val transport = if (protoParts.size >= 3) protoParts[2].uppercase() else {
            errors.add("malformed Via protocol: '${parts[0]}'")
            "UDP"
        }
        val segments = splitTopLevel(parts[1], ';')
        val hostPort = segments.first()
        val colon = hostPort.lastIndexOf(':')
        val host: String
        val port: Int?
        if (colon > 0 && hostPort.substring(colon + 1).toIntOrNull() != null) {
            host = hostPort.substring(0, colon)
            port = hostPort.substring(colon + 1).toIntOrNull()
        } else {
            host = hostPort
            port = null
        }
        val params = linkedMapOf<String, String>()
        for (segment in segments.drop(1)) {
            val eq = segment.indexOf('=')
            if (eq >= 0) params[segment.substring(0, eq).trim().lowercase()] =
                segment.substring(eq + 1).trim()
            else params[segment.trim().lowercase()] = ""
        }
        return ViaHeader(transport, host, port, params["branch"], params)
    }

    fun parseNameAddr(text: String, errors: MutableList<String>): NameAddr {
        val trimmed = text.trim()
        val params = linkedMapOf<String, String>()
        var display: String? = null
        var uri: String
        if (trimmed.contains('<')) {
            val lt = trimmed.indexOf('<')
            val gt = trimmed.indexOf('>', lt)
            if (gt < 0) {
                errors.add("malformed name-addr (unclosed '<'): '$trimmed'")
                uri = trimmed
            } else {
                display = trimmed.substring(0, lt).trim().removeSurrounding("\"").ifEmpty { null }
                uri = trimmed.substring(lt + 1, gt).trim()
                val rest = trimmed.substring(gt + 1)
                for (segment in rest.split(';')) {
                    val s = segment.trim()
                    if (s.isEmpty()) continue
                    val eq = s.indexOf('=')
                    if (eq >= 0) params[s.substring(0, eq).trim().lowercase()] =
                        s.substring(eq + 1).trim().removeSurrounding("\"")
                    else params[s.lowercase()] = ""
                }
            }
        } else {
            // addr-spec possibly with params: sip:a@b;tag=x
            val segments = trimmed.split(';')
            uri = segments.first().trim()
            for (segment in segments.drop(1)) {
                val s = segment.trim()
                if (s.isEmpty()) continue
                val eq = s.indexOf('=')
                if (eq >= 0) params[s.substring(0, eq).trim().lowercase()] =
                    s.substring(eq + 1).trim().removeSurrounding("\"")
                else params[s.lowercase()] = ""
            }
        }
        if (!uri.startsWith("sip:", ignoreCase = true) && !uri.startsWith("sips:", ignoreCase = true)) {
            errors.add("non-SIP URI in name-addr: '$uri'")
        }
        return NameAddr(display, uri, params)
    }

    private fun parseCSeq(text: String, errors: MutableList<String>): CSeq? {
        val parts = text.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.size != 2) {
            errors.add("malformed CSeq: '$text'")
            return null
        }
        val number = parts[0].toLongOrNull()
        if (number == null) {
            errors.add("malformed CSeq number: '${parts[0]}'")
            return null
        }
        return CSeq(number, parts[1].uppercase())
    }
}
