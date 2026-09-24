package org.sipreview.sip

import org.sipreview.model.ParsedMessage
import org.sipreview.model.ViaEntry
import java.security.MessageDigest
import java.util.Base64

object ByteUtils {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(s: String): ByteArray = Base64.getDecoder().normalize(s)
}

/**
 * Lenient SIP parser (RFC 3261). The raw bytes are preserved untouched by the
 * import layer; this parser is purely derived and reports diagnostics rather than
 * throwing. Values that cannot be converted stay null and get a diagnostic.
 */
object SipParser {

    fun parse(frameId: String, raw: ByteArray): ParsedMessage {
        val diags = mutableListOf<String>()

        val headerSplit = findHeaderBodySplit(raw)
        val headerBlock = if (headerSplit >= 0) raw.copyOfRange(0, headerSplit) else raw
        val body = if (headerSplit >= 0) {
            val sepLen = when {
                headerSplit + 3 < raw.size && raw[headerSplit] == 13.toByte() &&
                    raw[headerSplit + 1] == 10.toByte() && raw[headerSplit + 2] == 13.toByte() &&
                    raw[headerSplit + 3] == 10.toByte() -> 4
                headerSplit + 1 < raw.size && raw[headerSplit] == 10.toByte() &&
                    raw[headerSplit + 1] == 10.toByte() -> 2
                headerSplit + 1 < raw.size && raw[headerSplit] == 13.toByte() &&
                    raw[headerSplit + 1] == 10.toByte() -> 2
                else -> 0
            }
            if (sepLen > 0) raw.copyOfRange(headerSplit + sepLen, raw.size) else ByteArray(0)
        } else ByteArray(0)

        val headerText = headerBlock.toString(Charsets.UTF_8)
        val rawLines = headerText.split("\r\n", "\n").filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) {
            diags += "EMPTY_MESSAGE: no start line"
            return invalid(frameId, diags, body.size)
        }
        val startLine = rawLines.first()
        val isRequest = startLine.startsWith("SIP/", ignoreCase = true).not()

        var method: String? = null
        var requestUri: String? = null
        var statusCode: Int? = null
        var reason: String? = null

        if (isRequest) {
            val parts = startLine.split(" ")
            if (parts.size < 3 || !parts.last().startsWith("SIP/", ignoreCase = true)) {
                diags += "BAD_REQUEST_LINE: '$startLine'"
            } else {
                method = parts[0]
                requestUri = parts[1]
            }
        } else {
            val parts = startLine.split(Regex("\\s+"), 3)
            if (parts.size < 2 || !parts[0].startsWith("SIP/", ignoreCase = true)) {
                diags += "BAD_STATUS_LINE: '$startLine'"
            } else {
                statusCode = parts[1].toIntOrNull()
                if (statusCode == null) diags += "BAD_STATUS_CODE: '${parts[1]}'"
                reason = parts.getOrNull(2) ?: ""
            }
        }

        val headerMap = linkedMapOf<String, MutableList<String>>()
        for (line in rawLines.drop(1)) {
            if (line.startsWith(" ") || line.startsWith("\t")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) {
                diags += "BAD_HEADER_LINE: '$line'"
                continue
            }
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            headerMap.getOrPut(name.lowercase()) { mutableListOf() }.add(value)
        }

        val viaRaw = headerMap["via"].orEmpty()
        val vias = viaRaw.map { parseVia(it, diags) }
        val topBranch = vias.firstOrNull()?.branch

        val callId = headerMap["i"].firstOrNull() ?: headerMap["call-id"].firstOrNull()
        if (callId == null) diags += "MISSING_CALL_ID"

        val fromRaw = headerMap["f"].firstOrNull() ?: headerMap["from"].firstOrNull()
        val fromUri = fromRaw?.let { extractUri(it) }
        val fromTag = fromRaw?.let { extractParam(it, "tag") }
        if (fromRaw == null) diags += "MISSING_FROM"

        val toRaw = headerMap["t"].firstOrNull() ?: headerMap["to"].firstOrNull()
        val toUri = toRaw?.let { extractUri(it) }
        val toTag = toRaw?.let { extractParam(it, "tag") }
        if (toRaw == null) diags += "MISSING_TO"

        val cseqRaw = headerMap["cseq"].firstOrNull()
        var cseqNumber: Long? = null
        var cseqMethod: String? = null
        if (cseqRaw == null) {
            diags += "MISSING_CSEQ"
        } else {
            val trimmed = cseqRaw.trim()
            val sp = trimmed.indexOf(' ')
            if (sp <= 0) diags += "BAD_CSEQ: '$cseqRaw'"
            else {
                cseqNumber = trimmed.substring(0, sp).toLongOrNull()
                if (cseqNumber == null) diags += "BAD_CSEQ_NUMBER: '${trimmed.substring(0, sp)}'"
                cseqMethod = trimmed.substring(sp + 1).trim()
            }
        }

        // Record-Route may appear as multiple headers, each potentially comma-separated.
        val recordRoute = headerMap["record-route"].orEmpty()
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val contact = headerMap["m"].firstOrNull() ?: headerMap["contact"].firstOrNull()
        val maxForwards = (headerMap["max-forwards"].firstOrNull())?.toIntOrNull().also {
            if (headerMap["max-forwards"] != null && it == null) diags += "BAD_MAX_FORWARDS"
        }
        val contentLengthHeader = headerMap["l"].firstOrNull() ?: headerMap["content-length"].firstOrNull()
        val contentLength = contentLengthHeader?.toIntOrNull()
        if (contentLengthHeader != null && contentLength == null) {
            diags += "BAD_CONTENT_LENGTH: '$contentLengthHeader'"
        }
        if (contentLength != null && contentLength != body.size) {
            diags += "CONTENT_LENGTH_MISMATCH: header=$contentLength body=${body.size}"
        }

        val isValid = diags.none { it.startsWith("BAD_") || it.startsWith("EMPTY_") || it == "MISSING_CALL_ID" || it == "MISSING_CSEQ" || it == "MISSING_FROM" || it == "MISSING_TO" }

        return ParsedMessage(
            frameId = frameId,
            isValid = isValid,
            isRequest = isRequest,
            method = method,
            requestUri = requestUri,
            statusCode = statusCode,
            reasonPhrase = reason,
            via = vias,
            topBranch = topBranch,
            callId = callId,
            fromTag = fromTag,
            fromUri = fromUri,
            toTag = toTag,
            toUri = toUri,
            cseqNumber = cseqNumber,
            cseqMethod = cseqMethod,
            recordRoute = recordRoute,
            contact = contact,
            maxForwards = maxForwards,
            contentLength = contentLength,
            bodyLength = body.size,
            parseDiagnostics = diags,
        )
    }

    private fun invalid(frameId: String, diags: MutableList<String>, bodyLen: Int) = ParsedMessage(
        frameId = frameId,
        isValid = false,
        isRequest = false,
        method = null,
        requestUri = null,
        statusCode = null,
        reasonPhrase = null,
        via = emptyList(),
        topBranch = null,
        callId = null,
        fromTag = null,
        fromUri = null,
        toTag = null,
        toUri = null,
        cseqNumber = null,
        cseqMethod = null,
        recordRoute = emptyList(),
        contact = null,
        maxForwards = null,
        contentLength = null,
        bodyLength = bodyLen,
        parseDiagnostics = diags,
    )

    private fun findHeaderBodySplit(raw: ByteArray): Int {
        for (i in 0 until raw.size - 1) {
            if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte()) {
                if (i + 3 < raw.size && raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()) return i
                if (i + 2 < raw.size && raw[i + 2] == 10.toByte()) return i
            }
            if (raw[i] == 10.toByte() && i + 1 < raw.size && raw[i + 1] == 10.toByte()) return i
            if (raw[i] == 13.toByte() && i + 1 < raw.size && raw[i + 1] == 13.toByte()) return i
        }
        return -1
    }

    private fun parseVia(value: String, diags: MutableList<String>): ViaEntry {
        // e.g. SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1;rport=5060
        val firstPart = value.substringBefore(';').trim()
        val segs = firstPart.split(Regex("\\s+"), limit = 2)
        val protocol = segs.getOrNull(0) ?: ""
        val sentBy = segs.getOrNull(1)?.trim().orEmpty()
        if (!protocol.startsWith("SIP/", ignoreCase = true)) diags += "BAD_VIA_PROTOCOL: '$value'"
        return ViaEntry(
            raw = value,
            protocol = protocol.uppercase(),
            sentBy = sentBy,
            branch = extractParam(value, "branch"),
            rport = extractParam(value, "rport"),
            received = extractParam(value, "received"),
        )
    }

    private fun extractParam(headerValue: String, name: String): String? {
        val lower = headerValue.lowercase()
        var idx = lower.indexOf(";$name=")
        if (idx < 0 && lower.startsWith("$name=")) idx = -1
        val start = when {
            idx >= 0 -> idx + name.length + 2
            lower.startsWith("$name=") -> name.length + 1
            else -> {
                val i2 = lower.indexOf(" $name=")
                if (i2 >= 0) i2 + name.length + 2 else return null
            }
        }
        var end = start
        while (end < headerValue.length && headerValue[end] != ';' && headerValue[end] != ' ') end++
        val v = headerValue.substring(start, end).trim().removePrefix(">").removeSuffix(">")
        return v.ifEmpty { if (name == "rport") "" else null }
    }

    private fun extractUri(headerValue: String): String? {
        val lt = headerValue.indexOf('<')
        val gt = headerValue.indexOf('>')
        if (lt >= 0 && gt > lt) return headerValue.substring(lt + 1, gt)
        val semi = headerValue.indexOf(';')
        val raw = if (semi >= 0) headerValue.substring(0, semi) else headerValue
        return raw.trim().takeIf { it.contains("sip:", ignoreCase = true) }
    }
}
