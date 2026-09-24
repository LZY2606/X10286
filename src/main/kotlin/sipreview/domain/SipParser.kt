package sipreview.domain

import java.nio.charset.StandardCharsets

/**
 * Deliberately small, strict-enough RFC 3261 header parser. It never edits the input:
 * unparseable material stays in the raw row and shows up in [SipParsed.parseErrors].
 */
object SipParser {

    fun parse(raw: String): SipParsed {
        val errors = mutableListOf<String>()
        val normalized = raw.replace("\r\n", "\n")
        val headEnd = normalized.indexOf("\n\n")
        val head = if (headEnd >= 0) normalized.substring(0, headEnd) else normalized
        val lines = head.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return empty(errors + "empty message")
        }
        val startLine = lines.first().trim()
        var method: String? = null
        var statusCode: Int? = null
        var reason: String? = null
        val parts = startLine.split(' ')
        if (startLine.startsWith("SIP/2.0 ")) {
            val tail = parts.drop(1)
            statusCode = tail.firstOrNull()?.toIntOrNull()
            if (statusCode == null) errors += "malformed status line: $startLine"
            reason = tail.drop(1).joinToString(" ")
        } else {
            method = parts.firstOrNull()
            if (method == null || !method.first().isUpperCase() || parts.getOrNull(1)?.startsWith("sip:") != true) {
                errors += "malformed request line: $startLine"
            }
            if (parts.lastOrNull()?.startsWith("SIP/") != true) {
                errors += "request line missing SIP version: $startLine"
            }
        }

        // Unfold continuation lines.
        val unfolded = mutableListOf<String>()
        for (line in lines.drop(1)) {
            if ((line.startsWith(' ') || line.startsWith('\t')) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] = unfolded.last() + " " + line.trim()
            } else unfolded += line
        }

        val vias = mutableListOf<String>()
        var callId: String? = null
        var fromTag: String? = null
        var toTag: String? = null
        var cseqNumber: Long? = null
        var cseqMethod: String? = null
        val recordRoutes = mutableListOf<String>()
        var contact: String? = null
        var fromUri: String? = null
        var toUri: String? = null
        var contentLength: Int? = null

        for (line in unfolded) {
            val colon = line.indexOf(':')
            if (colon <= 0) {
                errors += "header without colon: ${line.take(40)}"
                continue
            }
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "via", "v" -> vias += value
                "call-id", "i" -> callId = value
                "from", "f" -> {
                    fromTag = parameter(value, "tag")
                    fromUri = angleUri(value) ?: value.substringBefore(';').trim()
                }
                "to", "t" -> {
                    toTag = parameter(value, "tag")
                    toUri = angleUri(value) ?: value.substringBefore(';').trim()
                }
                "cseq" -> {
                    val csp = value.split(Regex("\\s+"))
                    cseqNumber = csp.firstOrNull()?.toLongOrNull()
                    cseqMethod = csp.getOrNull(1)
                }
                "record-route" -> recordRoutes += value
                "contact", "m" -> contact = value
                "content-length", "l" -> contentLength = value.toIntOrNull()
            }
        }

        val topVia = vias.firstOrNull()
        val viaBranch = topVia?.let { parameter(it, "branch") }
        val rport = topVia?.let { parameter(it, "rport") }
        if (topVia == null) errors += "missing Via"
        if (callId == null) errors += "missing Call-ID"

        return SipParsed(
            method = method,
            statusCode = statusCode,
            reason = reason,
            viaBranch = viaBranch,
            viaTop = topVia,
            callId = callId,
            fromTag = fromTag,
            toTag = toTag,
            cseqNumber = cseqNumber,
            cseqMethod = cseqMethod,
            recordRoutes = recordRoutes,
            contact = contact,
            fromUri = fromUri,
            toUri = toUri,
            topViaRport = rport,
            contentLength = contentLength,
            parseErrors = errors
        )
    }

    private fun empty(errors: List<String>) = SipParsed(
        method = null, statusCode = null, reason = null, viaBranch = null, viaTop = null,
        callId = null, fromTag = null, toTag = null, cseqNumber = null, cseqMethod = null,
        recordRoutes = emptyList(), contact = null, fromUri = null, toUri = null,
        topViaRport = null, contentLength = null, parseErrors = errors
    )

    private fun parameter(headerValue: String, name: String): String? {
        for (p in headerValue.split(';')) {
            val eq = p.indexOf('=')
            if (eq > 0 && p.substring(0, eq).trim().equals(name, ignoreCase = true)) {
                return p.substring(eq + 1).trim()
            }
            if (eq < 0 && p.trim().equals(name, ignoreCase = true)) return ""
        }
        return null
    }

    private fun angleUri(value: String): String? {
        val lt = value.indexOf('<')
        val gt = value.indexOf('>')
        return if (lt >= 0 && gt > lt) value.substring(lt + 1, gt) else null
    }

    fun decode(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
}
