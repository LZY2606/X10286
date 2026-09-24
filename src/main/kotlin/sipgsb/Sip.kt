package sipgsb

import kotlinx.serialization.Serializable

@Serializable
data class NameAddr(val raw: String, val uri: String, val tag: String?)

@Serializable
data class Via(val raw: String, val transport: String, val sentBy: String, val branch: String?)

@Serializable
data class CSeq(val num: Long, val method: String)

@Serializable
data class SipMessage(
    val raw: String,
    val isRequest: Boolean,
    val method: String?,
    val requestUri: String?,
    val statusCode: Int?,
    val reasonPhrase: String?,
    val vias: List<Via>,
    val callId: String?,
    val from: NameAddr?,
    val to: NameAddr?,
    val cseq: CSeq?,
    val recordRoutes: List<String>,
    val contacts: List<String>,
    val diagnostics: List<String>,
) {
    val topBranch: String? get() = vias.firstOrNull()?.branch
    val fromTag: String? get() = from?.tag
    val toTag: String? get() = to?.tag
}

object SipParser {
    private val compactForms = mapOf(
        "f" to "From", "t" to "To", "i" to "Call-ID", "v" to "Via",
        "m" to "Contact", "l" to "Content-Length", "k" to "Supported",
        "c" to "Content-Type", "e" to "Content-Encoding", "r" to "Refer-To",
    )

    fun splitHeaderList(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var angle = 0
        var quote = false
        for (c in s) {
            when {
                quote -> { sb.append(c); if (c == '"') quote = false }
                c == '"' -> { quote = true; sb.append(c) }
                c == '<' -> { angle++; sb.append(c) }
                c == '>' -> { if (angle > 0) angle--; sb.append(c) }
                c == ',' && angle == 0 -> { if (sb.isNotBlank()) out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }

    private fun parseParams(s: String): Map<String, String?> {
        val out = linkedMapOf<String, String?>()
        val parts = s.split(";")
        for (i in 1 until parts.size) {
            val p = parts[i].trim()
            val eq = p.indexOf('=')
            if (eq < 0) out[p.lowercase()] = null
            else out[p.substring(0, eq).trim().lowercase()] = p.substring(eq + 1).trim().trim('"')
        }
        return out
    }

    fun parseVia(raw: String): Via {
        val head = raw.substringBefore(';').trim()
        val tokens = head.split(Regex("\\s+"))
        val transport = tokens.firstOrNull()
            ?.substringAfter("SIP/2.0/", "")?.uppercase() ?: ""
        val sentBy = tokens.getOrNull(1) ?: ""
        val branch = parseParams(raw.substringAfter(';', ";"))["branch"]
        return Via(raw, transport, sentBy, branch)
    }

    fun parseNameAddr(raw: String): NameAddr {
        val uri = if (raw.contains('<')) raw.substringAfter('<').substringBefore('>')
        else raw.substringBefore(';').trim()
        val tag = parseParams(raw.substringAfter('>', ";"))["tag"]
        return NameAddr(raw, uri, tag)
    }

    fun parse(raw: String): SipMessage {
        val diagnostics = mutableListOf<String>()
        val norm = raw.replace("\r\n", "\n")
        val head = norm.substringBefore("\n\n")
        val rawLines = head.split("\n").filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        for (ln in rawLines) {
            if ((ln.startsWith(" ") || ln.startsWith("\t")) && lines.isNotEmpty())
                lines[lines.lastIndex] = lines.last() + " " + ln.trim()
            else lines += ln
        }
        var isRequest = false
        var method: String? = null
        var requestUri: String? = null
        var statusCode: Int? = null
        var reasonPhrase: String? = null
        val startLine = lines.firstOrNull()?.trim() ?: ""
        if (startLine.isEmpty()) {
            diagnostics += "bad-start-line: empty"
        } else if (startLine.startsWith("SIP/2.0")) {
            val rest = startLine.removePrefix("SIP/2.0").trim()
            val sp = rest.indexOf(' ')
            val codeStr = if (sp < 0) rest else rest.substring(0, sp)
            statusCode = codeStr.toIntOrNull()
            reasonPhrase = if (sp < 0) "" else rest.substring(sp + 1).trim()
            if (statusCode == null || statusCode !in 100..699)
                diagnostics += "bad-status-line: $startLine"
        } else {
            val parts = startLine.split(Regex("\\s+"))
            if (parts.size >= 3 && parts.last().startsWith("SIP/2.0")) {
                isRequest = true
                method = parts[0].uppercase()
                requestUri = parts[1]
            } else diagnostics += "bad-start-line: $startLine"
        }
        val headers = mutableListOf<Pair<String, String>>()
        for (ln in lines.drop(1)) {
            val idx = ln.indexOf(':')
            if (idx <= 0) { diagnostics += "bad-header-line: ${ln.trim()}"; continue }
            var name = ln.substring(0, idx).trim()
            name = compactForms[name.lowercase()] ?: name
            val canonical = headers.map { it.first }.firstOrNull { it.equals(name, true) } ?: name
            headers += canonical to ln.substring(idx + 1).trim()
        }
        fun all(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }
        fun first(name: String) = all(name).firstOrNull()

        val vias = all("Via").flatMap { splitHeaderList(it) }.map { parseVia(it) }
        if (vias.isEmpty()) diagnostics += "missing-via"
        val callId = first("Call-ID")
        if (callId == null) diagnostics += "missing-call-id"
        val cseqRaw = first("CSeq")
        var cseq: CSeq? = null
        if (cseqRaw == null) {
            diagnostics += "missing-cseq"
        } else {
            val parts = cseqRaw.trim().split(Regex("\\s+"))
            val num = parts.firstOrNull()?.toLongOrNull()
            if (num == null || parts.size < 2) diagnostics += "bad-cseq: $cseqRaw"
            else cseq = CSeq(num, parts[1].uppercase())
        }
        val from = first("From")?.let { parseNameAddr(it) }
        val to = first("To")?.let { parseNameAddr(it) }
        if (from == null) diagnostics += "missing-from"
        if (to == null) diagnostics += "missing-to"
        val recordRoutes = all("Record-Route").flatMap { splitHeaderList(it) }
        val contacts = all("Contact").flatMap { splitHeaderList(it) }
        return SipMessage(
            raw = raw, isRequest = isRequest, method = method, requestUri = requestUri,
            statusCode = statusCode, reasonPhrase = reasonPhrase, vias = vias,
            callId = callId, from = from, to = to, cseq = cseq,
            recordRoutes = recordRoutes, contacts = contacts, diagnostics = diagnostics,
        )
    }
}
