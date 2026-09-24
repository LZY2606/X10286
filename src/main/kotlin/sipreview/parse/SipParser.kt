package sipreview.parse

import sipreview.domain.CSeqRef
import sipreview.domain.ParsedMessage
import sipreview.domain.SipUri
import sipreview.domain.StartLine
import sipreview.domain.Via

object SipParser {

    fun parse(recordId: String, rawBytes: ByteArray): ParsedMessage {
        val diagnostics = mutableListOf<String>()
        val text = decode(rawBytes, diagnostics)
        val headerSection = cutHeaderSection(text, diagnostics)
        val lines = unfold(headerSection).split(Regex("\\r?\\n")).filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            diagnostics.add("MESSAGE_EMPTY: no start line and no headers")
            return empty(recordId, diagnostics)
        }
        val startLine = parseStartLine(lines.first(), diagnostics)
        val rawHeaders = linkedMapOf<String, MutableList<String>>()
        for (idx in 1 until lines.size) {
            val line = lines[idx]
            val colon = line.indexOf(':')
            if (colon <= 0) {
                diagnostics.add("HEADER_NO_COLON at line ${idx + 1}: ${line.take(40)}")
                continue
            }
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            rawHeaders.getOrPut(lower(name)) { mutableListOf() }.add(value)
        }
        val viaList = rawHeaders["via"].orEmpty().map { parseVia(it, diagnostics) }
        val callId = rawHeaders["i"]?.firstOrNull() ?: rawHeaders["call-id"]?.firstOrNull()
        if (callId.isNullOrBlank()) diagnostics.add("MISSING_CALL_ID")
        val fromRaw = rawHeaders["f"]?.firstOrNull() ?: rawHeaders["from"]?.firstOrNull()
        val toRaw = rawHeaders["t"]?.firstOrNull() ?: rawHeaders["to"]?.firstOrNull()
        val cseqRaw = rawHeaders["cseq"]?.firstOrNull()
        val cseq = cseqRaw?.let { parseCSeq(it, diagnostics) }
        if (cseq == null) diagnostics.add("MISSING_OR_BAD_CSEQ")
        return ParsedMessage(
            recordId = recordId,
            startLine = startLine,
            via = viaList,
            callId = callId?.takeIf { it.isNotBlank() },
            fromRaw = fromRaw,
            fromTag = fromRaw?.let { parameter(it, "tag") },
            toRaw = toRaw,
            toTag = toRaw?.let { parameter(it, "tag") },
            cseq = cseq,
            recordRoute = rawHeaders["record-route"].orEmpty(),
            contact = (rawHeaders["m"] ?: rawHeaders["contact"]).orEmpty(),
            headers = rawHeaders,
            diagnostics = diagnostics
        )
    }

    private fun decode(bytes: ByteArray, diagnostics: MutableList<String>): String {
        val text = bytes.toString(Charsets.UTF_8)
        if (bytes.any { (it.toInt() and 0xFF) > 0x7F }) {
            diagnostics.add("RAW_CONTAINS_NON_ASCII_BYTES: decoded as UTF-8 for header parsing; original bytes retained")
        }
        return text
    }

    private fun cutHeaderSection(text: String, diagnostics: MutableList<String>): String {
        val crlf = text.indexOf("\r\n\r\n")
        val lf = text.indexOf("\n\n")
        val idx = when {
            crlf >= 0 -> crlf
            lf >= 0 -> lf
            else -> -1
        }
        if (idx < 0) return text.also {
            if (!text.contains("\r\n")) diagnostics.add("LINE_SEPARATORS: CRLF not found; tolerated LF-only parsing")
        }
        return text.substring(0, idx)
    }

    private fun unfold(section: String): String =
        section.replace(Regex("\\r?\\n[ \\t]+"), " ")

    private fun parseStartLine(line: String, diagnostics: MutableList<String>): StartLine? {
        val parts = line.split(Regex(" +"))
        if (parts.size < 2) {
            diagnostics.add("BAD_START_LINE: ${line.take(60)}")
            return null
        }
        return if (parts[0].startsWith("SIP/", ignoreCase = true)) {
            val code = parts.getOrNull(1)?.toIntOrNull()
            if (code == null || code !in 100..699) {
                diagnostics.add("BAD_STATUS_LINE: ${line.take(60)}")
                null
            } else StartLine.Status(parts[0], code, parts.drop(2).joinToString(" "))
        } else {
            if (parts.size < 3 || !parts[2].startsWith("SIP/", ignoreCase = true)) {
                diagnostics.add("BAD_REQUEST_LINE: ${line.take(60)}")
                null
            } else StartLine.Request(parts[0], parts[1], parts[2])
        }
    }

    fun parseVia(raw: String, diagnostics: MutableList<String>): Via {
        val slash = raw.indexOf('/')
        val secondSlash = raw.indexOf('/', slash + 1)
        val sp = raw.indexOfAny(charArrayOf(' ', '\t'), secondSlash + 1)
        val sentByEnd = raw.indexOfAny(charArrayOf(';', ','), sp + 1).let { if (it < 0) raw.length else it }
        val protocol = if (slash in 0..<secondSlash && sp > secondSlash) {
            raw.substring(slash + 1, secondSlash)
        } else {
            diagnostics.add("BAD_VIA_PROTOCOL: ${raw.take(40)}"); "?"
        }
        val sentBy = if (sp > 0) raw.substring(sp + 1, sentByEnd).trim() else ""
        val colon = sentBy.lastIndexOf(':')
        val (host, port) = if (colon > 0) {
            sentBy.substring(0, colon) to (sentBy.substring(colon + 1).toIntOrNull()
                ?: run { diagnostics.add("BAD_VIA_PORT: $sentBy"); null })
        } else sentBy to null
        val paramSection = if (sentByEnd < raw.length) raw.substring(sentByEnd + 1) else ""
        val branch = parameter(paramSection, "branch")
        val rport = parameter(paramSection, "rport")
        return Via(protocol.uppercase(), host.trim(), port, branch, rport, raw.trim())
    }

    private fun parseCSeq(raw: String, diagnostics: MutableList<String>): CSeqRef? {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size != 2) {
            diagnostics.add("BAD_CSEQ: $raw"); return null
        }
        val n = parts[0].toLongOrNull()
        if (n == null || n < 0) {
            diagnostics.add("BAD_CSEQ_NUMBER: $raw"); return null
        }
        return CSeqRef(n, parts[1].uppercase(), raw.trim())
    }

    /** Extract ;param=value or bare ;param from a SIP header/URI fragment. */
    fun parameter(fragment: String, name: String): String? {
        val escaped = Regex.escape(name)
        val re = Regex(";\\s*$escaped\\s*(?:=\\s*([^;]*))?", RegexOption.IGNORE_CASE)
        val m = re.find(fragment) ?: return null
        return m.groupValues[1].trim().ifEmpty { "" }
    }

    fun parseSipUri(raw: String): SipUri {
        val trimmed = raw.trim().trim('<', '>')
        val schemeMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(trimmed)
        val scheme = schemeMatch?.value?.removeSuffix(":")?.lowercase()
        val rest = if (schemeMatch != null) trimmed.substring(schemeMatch.range.last + 1) else trimmed
        val hostPartEnd = rest.indexOfAny(charArrayOf(';', '?')).let { if (it < 0) rest.length else it }
        val hostPart = rest.substring(0, hostPartEnd)
        val at = hostPart.lastIndexOf('@')
        val user = if (at >= 0) hostPart.substring(0, at) else null
        val hp = if (at >= 0) hostPart.substring(at + 1) else hostPart
        val colon = hp.lastIndexOf(':')
        val port: Int?
        val host: String?
        if (colon > 0 && hp.substring(colon + 1).all { it.isDigit() }) {
            host = hp.substring(0, colon)
            port = hp.substring(colon + 1).toInt()
        } else {
            host = hp.ifBlank { null }
            port = null
        }
        val params = linkedMapOf<String, String?>()
        if (hostPartEnd < rest.length) {
            rest.substring(hostPartEnd + 1).trimStart(';').split(';').forEach { p ->
                if (p.isNotBlank()) {
                    val eq = p.indexOf('=')
                    if (eq >= 0) params[p.substring(0, eq).trim().lowercase()] = p.substring(eq + 1).trim()
                    else params[p.trim().lowercase()] = null
                }
            }
        }
        return SipUri(raw.trim(), scheme, user, host, port, params)
    }

    private fun lower(s: String) = s.trim().lowercase()

    private fun empty(recordId: String, diagnostics: MutableList<String>) = ParsedMessage(
        recordId = recordId,
        startLine = null,
        via = emptyList(),
        callId = null,
        fromRaw = null,
        fromTag = null,
        toRaw = null,
        toTag = null,
        cseq = null,
        recordRoute = emptyList(),
        contact = emptyList(),
        headers = emptyMap(),
        diagnostics = diagnostics
    )
}
