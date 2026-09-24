package sipreview.parse

import sipreview.domain.*

object SipParser {

    fun parse(recordId: String, direction: Direction, atMs: Long, connectionId: String?, rawHex: String): ParsedMessage {
        val bytes = hexToBytes(rawHex)
        val diagnostics = mutableListOf<String>()
        if (bytes.isNotEmpty() && bytes.any { (it.toInt() and 0xFF) > 127 }) {
            diagnostics += "NON_ASCII_BYTES_PRESERVED"
        }
        val text = String(bytes, Charsets.UTF_8)
        if (!text.contains("\r\n")) diagnostics += "NO_CRLF_LINE_ENDINGS"

        val lines = text.split("\r\n", "\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return ParsedMessage(
                recordId = recordId, direction = direction, atMs = atMs, connectionId = connectionId,
                rawHex = rawHex, byteLength = bytes.size, isRequest = false,
                diagnostics = diagnostics + "EMPTY_MESSAGE",
            )
        }
        val startLine = lines.first()
        val isRequest = startLine.startsWith("SIP/", ignoreCase = true).not()
        var method: String? = null
        var requestUri: String? = null
        var statusCode: Int? = null
        var reason: String? = null
        if (isRequest) {
            val parts = startLine.split(" ")
            if (parts.size >= 3 && parts[2].startsWith("SIP/")) {
                method = parts[0]; requestUri = parts[1]
            } else diagnostics += "MALFORMED_REQUEST_LINE"
        } else {
            val parts = startLine.split(" ", limit = 3)
            if (parts.size >= 2 && parts[0].startsWith("SIP/")) {
                statusCode = parts[1].toIntOrNull()
                if (statusCode == null) diagnostics += "NON_NUMERIC_STATUS_CODE"
                reason = parts.getOrNull(2)
            } else diagnostics += "MALFORMED_STATUS_LINE"
        }

        val headerLines = mutableListOf<HeaderLine>()
        for (line in lines.drop(1)) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                headerLines.lastOrNull()?.let { headerLines[headerLines.lastIndex] = it.copy(rawValue = it.rawValue + " " + line.trim()) }
                continue
            }
            val idx = line.indexOf(':')
            if (idx <= 0) { diagnostics += "MALFORMED_HEADER:$line"; continue }
            headerLines += HeaderLine(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }

        fun header(name: String): String? =
            headerLines.firstOrNull { it.name.equals(name, ignoreCase = true) }?.rawValue

        fun headers(name: String): List<String> =
            headerLines.filter { it.name.equals(name, ignoreCase = true) }.map { it.rawValue }

        val callId = header("Call-ID")
        val cseqRaw = header("CSeq")
        var cseqNumber: Long? = null
        var cseqMethod: String? = null
        if (cseqRaw != null) {
            val m = Regex("""^\s*(\d+)\s+(\S+)""").find(cseqRaw)
            if (m != null) { cseqNumber = m.groupValues[1].toLong(); cseqMethod = m.groupValues[2] }
            else diagnostics += "UNPARSEABLE_CSEQ:$cseqRaw"
        }
        val fromRaw = header("From") ?: header("f")
        val toRaw = header("To") ?: header("t")
        val fromTag = fromRaw?.let { tagParam(it) }
        val toTag = toRaw?.let { tagParam(it) }
        if (fromRaw != null && fromTag == null) diagnostics += "FROM_WITHOUT_TAG"
        if (fromRaw == null) diagnostics += "MISSING_FROM"
        if (toRaw == null) diagnostics += "MISSING_TO"
        if (callId == null) diagnostics += "MISSING_CALL_ID"
        if (cseqRaw == null) diagnostics += "MISSING_CSEQ"

        val vias = headers("Via").plus(headers("v"))
        val viaTop = vias.firstOrNull()
        val viaBranch = viaTop?.let { viaBranch(it) }
        if (viaTop == null) diagnostics += "MISSING_VIA" else if (viaBranch == null) diagnostics += "VIA_WITHOUT_BRANCH"

        val recordRoutes = headers("Record-Route")
        val contact = header("Contact")

        return ParsedMessage(
            recordId = recordId, direction = direction, atMs = atMs, connectionId = connectionId,
            rawHex = rawHex, byteLength = bytes.size,
            isRequest = isRequest, method = method ?: cseqMethod, requestUri = requestUri,
            statusCode = statusCode, reasonPhrase = reason,
            callId = callId, cseqNumber = cseqNumber, cseqMethod = cseqMethod,
            fromTag = fromTag, toTag = toTag,
            fromUri = fromRaw?.let { addrUri(it) }, toUri = toRaw?.let { addrUri(it) },
            viaBranch = viaBranch, viaTop = viaTop,
            recordRoutes = recordRoutes, contact = contact,
            headers = headerLines, diagnostics = diagnostics,
        )
    }

    fun tagParam(headerValue: String): String? =
        Regex("""(?i);\s*tag\s*=\s*([^;]+)""").find(headerValue)?.groupValues?.get(1)?.trim()

    fun addrUri(headerValue: String): String? {
        val start = headerValue.indexOf('<')
        val end = headerValue.indexOf('>')
        return if (start in 0 until end) headerValue.substring(start + 1, end)
        else headerValue.substringBefore(';').trim().takeIf { it.startsWith("sip:", true) }
    }

    fun viaBranch(via: String): String? {
        val m = Regex("""(?i)branch\s*=\s*([^;]+)""").find(via)
        return m?.groupValues?.get(1)?.trim()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "HEX_ODD_LENGTH" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
