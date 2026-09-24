package forensics.sip.sip

import kotlinx.serialization.Serializable

@Serializable
data class GenericParam(val key: String, val value: String?)

@Serializable
data class SipHeader(
    val name: String,
    val rawValue: String,
    val params: Map<String, String?> = emptyMap(),
)

@Serializable
data class ViaEntry(
    val raw: String,
    val protocol: String,
    val sentBy: String,
    val branch: String?,
    val rport: String?,
    val params: Map<String, String?>,
)

@Serializable
data class CSeq(val seq: Long, val method: String)

@Serializable
data class NameAddr(val uri: String, val raw: String, val tag: String?)

@Serializable
data class ParsedSip(
    val method: String?,
    val statusCode: Int?,
    val reason: String?,
    val requestUri: String?,
    val headers: List<SipHeader>,
    val vias: List<ViaEntry>,
    val cseq: CSeq?,
    val callId: String?,
    val from: NameAddr?,
    val to: NameAddr?,
    val recordRoute: List<String>,
    val contact: String?,
    val contentLength: Int?,
    val bodyStart: Int,
    val hasBody: Boolean,
) {
    fun header(name: String): SipHeader? = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }
    fun headers(name: String): List<SipHeader> = headers.filter { it.name.equals(name, ignoreCase = true) }
}

object SipParser {
    private val GENERIC_PARAM_SKIP = setOf("branch", "rport", "tag")

    fun parse(raw: String): ParseResult {
        val crlf = raw.indexOf("\r\n\r\n")
        val lf = raw.indexOf("\n\n")
        val headEnd: Int
        val bodyStart: Int
        when {
            crlf >= 0 -> { headEnd = crlf; bodyStart = crlf + 4 }
            lf >= 0 -> { headEnd = lf; bodyStart = lf + 2 }
            else -> { headEnd = raw.length; bodyStart = raw.length }
        }
        val headSection = raw.substring(0, headEnd)
        val lines = unfold(headSection)
        if (lines.isEmpty()) return ParseResult(null, listOf(ParseDiag("ERROR","EMPTY_INPUT","empty message: no start line")))
        val diags = mutableListOf<String>()
        val first = lines.first()
        val method: String?
        val statusCode: Int?
        val reason: String?
        val requestUri: String?
        if (first.startsWith("SIP/2.0/")) {
            val parts = first.split(Regex("\\s+"), 3)
            if (parts.size < 2 || parts[1].toIntOrNull() == null) {
                diags += ParseDiag("ERROR","MALFORMED",malformed status line: '$first')
            }
            method = null
            statusCode = parts.getOrNull(1)?.toIntOrNull()
            reason = parts.getOrNull(2)
            requestUri = null
        } else {
            val parts = first.split(Regex("\\s+"))
            if (parts.size != 3 || !parts[2].startsWith("SIP/2.0")) {
                diags += ParseDiag("ERROR","MALFORMED",malformed request line: '$first')
            }
            method = parts.getOrNull(0)
            requestUri = parts.getOrNull(1)
            statusCode = null
            reason = null
        }
        val headers = mutableListOf<SipHeader>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) {
                diags += ParseDiag("ERROR","MALFORMED",malformed header line: '$line')
                continue
            }
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isEmpty()) diags += ParseDiag("ERROR","MALFORMED",empty header name in line: '$line')
            headers += SipHeader(name, value, parseGenericParams(value).first)
        }
        val via = headers.filter { it.name.equals("Via", true) }.map { viaOf(it.rawValue, diags) }
        val cseqHeader = headers.firstOrNull { it.name.equals("CSeq", true) }?.rawValue
        val cseq = cseqHeader?.let {
            val p = it.split(Regex("\\s+"))
            val seq = p.getOrNull(0)?.toLongOrNull()
            val m = p.getOrNull(1)
            if (seq == null || m == null) diags += ParseDiag("ERROR","MALFORMED",malformed CSeq: '$it')
            if (seq != null && m != null) CSeq(seq, m) else null
        }
        val callId = headers.firstOrNull { it.name.equals("Call-ID", true) }?.rawValue?.trim()
        if (callId.isNullOrEmpty()) diags += ParseDiag("ERROR","MALFORMED",missing Call-ID)
        val from = nameAddr(headers.firstOrNull { it.name.equals("From", true) }?.rawValue, "From", diags)
        val to = nameAddr(headers.firstOrNull { it.name.equals("To", true) }?.rawValue, "To", diags)
        if (from == null) diags += ParseDiag("ERROR","MALFORMED",missing From)
        val rr = headers.filter { it.name.equals("Record-Route", true) }.map { it.rawValue }
        val contact = headers.firstOrNull { it.name.equals("Contact", true) }?.rawValue
        val clHeader = headers.firstOrNull { it.name.equals("Content-Length", true) }?.rawValue?.trim()
        val contentLength = clHeader?.toIntOrNull().also {
            if (clHeader != null && it == null) diags += ParseDiag("ERROR","MALFORMED",malformed Content-Length: '$clHeader')
        }
        if (via.isEmpty()) diags += ParseDiag("ERROR","MALFORMED",missing Via)
        if (via.flatMap { listOfNotNull(it.branch) }.distinct().size < via.size) {
            diags += ParseDiag("ERROR","MALFORMED",multiple Via entries without branch parameter)
        }
        val parsed = ParsedSip(
            method, statusCode, reason, requestUri, headers, via, cseq, callId, from, to,
            rr, contact, contentLength, bodyStart, bodyStart < raw.length,
        )
        return ParseResult(parsed, diags)
    }

    private fun unfold(head: String): List<String> =
        head.split("\r\n", "\n").filter { it.isNotBlank() }
            .fold(mutableListOf()) { acc, line ->
                if ((line.firstOrNull() == ' ' || line.firstOrNull() == '\t') && acc.isNotEmpty()) {
                    acc[acc.lastIndex] = acc.last() + " " + line.trim()
                } else acc += line
                acc
            }

    internal fun splitSemi(value: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var depth = 0
        for (c in value) {
            when {
                c == '"' -> { inQuotes = !inQuotes; cur.append(c) }
                !inQuotes && c == '<' -> { depth++; cur.append(c) }
                !inQuotes && c == '>' -> { depth--; cur.append(c) }
                !inQuotes && depth == 0 && c == ';' -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    internal fun parseGenericParams(value: String): Pair<Map<String, String?>, String> {
        val parts = splitSemi(value)
        val base = parts.first().trim()
        val params = linkedMapOf<String, String?>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            val key = (if (eq >= 0) p.substring(0, eq) else p).trim().lowercase()
            val v = if (eq >= 0) stripQuotes(p.substring(eq + 1).trim()) else null
            if (key !in GENERIC_PARAM_SKIP) params[key] = v
        }
        return params to base
    }

    private fun stripQuotes(v: String) =
        if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1) else v

    private fun viaOf(raw: String, diags: MutableList<String>): ViaEntry {
        val parts = splitSemi(raw)
        val head = parts.firstOrNull()?.trim().orEmpty()
        val hp = head.split(Regex("\\s+"))
        val protocol = hp.getOrNull(0) ?: ""
        val sentBy = hp.getOrNull(1) ?: ""
        if (!protocol.startsWith("SIP/2.0/")) diags += ParseDiag("ERROR","MALFORMED",malformed Via protocol: '$raw')
        if (sentBy.isBlank()) diags += ParseDiag("ERROR","MALFORMED",malformed Via sent-by: '$raw')
        val params = linkedMapOf<String, String?>()
        var branch: String? = null
        var rport: String? = null
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            val key = (if (eq >= 0) p.substring(0, eq) else p).trim().lowercase()
            val value = if (eq >= 0) stripQuotes(p.substring(eq + 1).trim()) else null
            params[key] = value
            when (key) { "branch" -> branch = value; "rport" -> rport = value }
        }
        if (branch == null) diags += ParseDiag("ERROR","MALFORMED",Via missing branch parameter: '$raw')
        else if (!branch.startsWith("z9hG4bK")) diags += ParseDiag("ERROR","MALFORMED",non-RFC3261 branch (magic cookie missing): '$branch')
        return ViaEntry(raw, protocol, sentBy, branch, rport, params)
    }

    private fun nameAddr(raw: String?, label: String, diags: MutableList<String>): NameAddr? {
        if (raw == null) return null
        val semi = splitSemi(raw)
        val main = semi.first().trim()
        var tag: String? = null
        for (p in semi.drop(1)) {
            val eq = p.indexOf('=')
            val key = (if (eq >= 0) p.substring(0, eq) else p).trim().lowercase()
            if (key == "tag") tag = eq.takeIf { it >= 0 }?.let { stripQuotes(p.substring(it + 1).trim()) }
        }
        val uri = when {
            main.contains('<') -> main.substringAfter('<').substringBefore('>')
            main.contains(':') -> main.substringBefore(';').trim()
            else -> { diags += ParseDiag("ERROR","MALFORMED",malformed $label name-addr: '$raw'); "" }
        }
        if (tag != null && tag.isEmpty()) diags += ParseDiag("ERROR","MALFORMED",empty tag parameter in $label)
        return NameAddr(uri, main, tag)
    }
}

data class ParseDiag(val severity: String, val code: String, val detail: String)

data class ParseResult(val message: ParsedSip?, val diagnostics: List<ParseDiag>)
