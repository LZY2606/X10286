package org.sipreview.domain

import kotlinx.serialization.Serializable

enum class Direction { C2S, S2C }

enum class Transport { UDP, TCP }

@Serializable
data class HeaderLine(val name: String, val value: String)

/**
 * An imported raw observation. The [rawText] is the exact submitted representation
 * (preserved byte-for-byte) and is never overwritten after commit. Parsed/normalized
 * fields are derived and kept separately from the original.
 */
@Serializable
data class RawObservation(
    val seq: Long,
    val rawText: String,
    val direction: Direction,
    val transport: Transport,
    val connectionGeneration: Int,
    val receivedAtMs: Long,
    val sourceLabel: String
) {
    val rawHash: String get() = Ids.hash(listOf("raw", rawText))
}

@Serializable
data class ParsedSip(
    val startLine: String,
    val isRequest: Boolean,
    val method: String? = null,
    val requestUri: String? = null,
    val statusCode: Int? = null,
    val reasonPhrase: String? = null,
    val viaBranch: String? = null,
    val viaTop: String? = null,
    val callId: String? = null,
    val fromTag: String? = null,
    val toTag: String? = null,
    val fromUri: String? = null,
    val toUri: String? = null,
    val cseqNumber: Long? = null,
    val cseqMethod: String? = null,
    val recordRoutes: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val contact: String? = null,
    val headers: List<HeaderLine> = emptyList(),
    val diagnostics: List<String> = emptyList()
)

class SipParseException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

object SipParser {

    fun parse(raw: String): ParsedSip {
        val errors = mutableListOf<String>()
        val normalizedLineEnding = if (raw.contains("\r\n")) "\r\n" else "\n"
        val unfolded = unfold(raw, normalizedLineEnding)
        val lines = unfolded.split(normalizedLineEnding).dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) errors += "empty message: no start line"

        val startLine = lines.firstOrNull() ?: ""
        val isRequest = startLine.startsWith("SIP/", ignoreCase = true).not() && startLine.contains(" SIP/")
        val isResponse = startLine.startsWith("SIP/", ignoreCase = true)

        var method: String? = null
        var requestUri: String? = null
        var statusCode: Int? = null
        var reasonPhrase: String? = null

        when {
            isRequest -> {
                val parts = startLine.split(" ")
                if (parts.size < 3 || !parts[2].startsWith("SIP/")) {
                    errors += "malformed request-line: '$startLine' (expected: METHOD URI SIP/2.0)"
                } else {
                    method = parts[0]
                    requestUri = parts[1]
                }
            }
            isResponse -> {
                val parts = startLine.split(" ")
                if (parts.size < 2 || parts[0].substringBefore("/").let { it.uppercase() != "SIP" }) {
                    errors += "malformed status-line: '$startLine' (expected: SIP/2.0 CODE REASON)"
                } else {
                    statusCode = parts[1].toIntOrNull()
                    if (statusCode == null) errors += "status-line has non-numeric code: '${parts[1]}'"
                    reasonPhrase = if (parts.size > 2) parts.drop(2).joinToString(" ") else ""
                }
            }
            else -> errors += "unrecognised start line: '$startLine'"
        }

        val headers = linkedMapOf<String, MutableList<String>>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) {
                errors += "line $i has no header colon: '${line.take(40)}'"
                continue
            }
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            headers.getOrPut(name.lowercase()) { mutableListOf() }.add(value)
        }

        fun first(name: String): String? = headers[name.lowercase()]?.firstOrNull()

        val viaTop = first("Via")
        val viaBranch = viaTop?.let { extractParam(it, "branch") }
        val callId = first("Call-ID") ?: first("i")
        if (callId == null) errors += "missing required header: Call-ID"
        if (viaTop == null) errors += "missing required header: Via"

        val from = first("From") ?: first("f")
        val to = first("To") ?: first("t")
        val fromTag = from?.let { extractParam(it, "tag") }
        val toTag = to?.let { extractParam(it, "tag") }
        val fromUri = from?.let { extractUri(it) }
        val toUri = to?.let { extractUri(it) }

        val cseqRaw = first("CSeq")
        var cseqNumber: Long? = null
        var cseqMethod: String? = null
        if (cseqRaw == null) {
            errors += "missing required header: CSeq"
        } else {
            val cparts = cseqRaw.trim().split(Regex("\\s+"))
            cseqNumber = cparts.getOrNull(0)?.toLongOrNull()
            if (cseqNumber == null) errors += "CSeq number is not an integer: '$cseqRaw'"
            cseqMethod = cparts.getOrNull(1)
            if (cseqMethod == null) errors += "CSeq missing method: '$cseqRaw'"
        }

        val recordRoutes = headers["record-route"] ?: emptyList()
        val routes = headers["route"] ?: emptyList()
        val contact = first("Contact") ?: first("m")

        val headerLines = mutableListOf<HeaderLine>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx > 0) headerLines += HeaderLine(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }

        val parsed = ParsedSip(
            startLine = startLine,
            isRequest = isRequest,
            method = method,
            requestUri = requestUri,
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            viaBranch = viaBranch,
            viaTop = viaTop,
            callId = callId,
            fromTag = fromTag,
            toTag = toTag,
            fromUri = fromUri,
            toUri = toUri,
            cseqNumber = cseqNumber,
            cseqMethod = cseqMethod,
            recordRoutes = recordRoutes,
            routes = routes,
            contact = contact,
            headers = headerLines,
            diagnostics = errors
        )
        if (errors.isNotEmpty()) throw SipParseException(errors)
        return parsed
    }

    /** Unfold header continuation lines (CRLF + SP/TAB per RFC 3261). */
    private fun unfold(raw: String, sep: String): String {
        val folded = raw.replace(Regex("$sep[ \\t]+"), " ")
        // Ensure body separator present; everything after blank line is body (kept in raw only).
        return folded
    }

    /** Extract a single header parameter such as branch / tag. */
    fun extractParam(value: String, name: String): String? {
        val semi = value.indexOf(';')
        if (semi < 0) return null
        val rest = value.substring(semi + 1)
        for (piece in rest.split(';')) {
            val eq = piece.indexOf('=')
            if (eq > 0 && piece.substring(0, eq).trim().equals(name, ignoreCase = true)) {
                return piece.substring(eq + 1).trim().trim('"')
            }
        }
        return null
    }

    /** Extract the SIP/SIPS URI out of a name-addr, e.g. "Alice" <sip:a@b>;tag=x. */
    fun extractUri(value: String): String? {
        val lt = value.indexOf('<')
        val gt = value.indexOf('>')
        if (lt >= 0 && gt > lt) return value.substring(lt + 1, gt)
        val bare = value.substringBefore(';').trim()
        return bare.ifBlank { null }
    }
}
