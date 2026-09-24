package dev.sipflow.core

/**
 * SIP 报文解析。只做轻量、可解释的解析：
 *  - 保留原报文字节（RawSipMessage.rawBytes）
 *  - 派生 ParsedSipMessage，解析失败给出明确诊断而不丢弃原文
 * 按 RFC 3261 解析 start line / 头部；正文以 Content-Length 为准（样例均为无正文或 SDP 占位）。
 */
object SipParser {

    class SipFormatException(message: String) : Exception(message)

    fun parse(raw: String): ParsedSipMessage {
        // 统一切分：保留 CRLF 原样用于字节长度，解析时同时容忍 LF
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val firstNl = normalized.indexOf('\n')
        if (firstNl < 0) throw SipFormatException("missing start line: no line terminator")
        val startLine = normalized.substring(0, firstNl).trim()
        if (startLine.isEmpty()) throw SipFormatException("empty start line")

        val isRequest = startLine.startsWith("SIP/", ignoreCase = true).not()
        var method: String? = null
        var requestUri: String? = null
        var statusCode: Int? = null
        var reasonPhrase: String? = null

        if (isRequest) {
            val parts = startLine.split(Regex("\\s+"))
            if (parts.size < 3 || !parts[2].startsWith("SIP/", ignoreCase = true)) {
                throw SipFormatException("malformed request line: '$startLine' (expected METHOD Request-URI SIP/2.0)")
            }
            method = parts[0]
            requestUri = parts[1]
        } else {
            val parts = startLine.split(Regex("\\s+"), limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("SIP/", ignoreCase = true)) {
                throw SipFormatException("malformed status line: '$startLine'")
            }
            statusCode = parts[1].toIntOrNull()
                ?: throw SipFormatException("status code is not an integer: '${parts[1]}'")
            reasonPhrase = parts.getOrNull(2) ?: ""
        }

        val headerSection: String
        val body: String
        val sep = normalized.indexOf("\n\n")
        if (sep >= 0) {
            headerSection = normalized.substring(firstNl + 1, sep)
            body = normalized.substring(sep + 2)
        } else {
            headerSection = normalized.substring(firstNl + 1)
            body = ""
        }

        val headers = mutableListOf<HeaderLine>()
        val logicalLines = mutableListOf<String>()
        for (line in headerSection.split('\n')) {
            if (line.isEmpty()) continue
            if ((line.startsWith(' ') || line.startsWith('\t')) && logicalLines.isNotEmpty()) {
                logicalLines[logicalLines.lastIndex] = logicalLines.last() + " " + line.trim()
            } else {
                logicalLines.add(line)
            }
        }
        for (line in logicalLines) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw SipFormatException("malformed header line (no colon): '$line'")
            val rawName = line.substring(0, colon)
            val value = line.substring(colon + 1).trim()
            headers.add(HeaderLine(rawName.lowercase().replace("-", ""), rawName.trim(), value))
        }

        fun h(name: String): String? = headers.lastOrNull { it.name == name }?.value
        fun all(name: String): List<String> = headers.filter { it.name == name }.map { it.value }

        val topVia = all("via").firstOrNull()
        val viaBranch = topVia?.let { extractBranch(it) }
        val cseq = h("cseq")
        var cseqNumber: Long? = null
        var cseqMethod: String? = null
        if (cseq != null) {
            val m = Regex("""^\s*(\d+)\s+(\S+)""").find(cseq)
                ?: throw SipFormatException("malformed CSeq: '$cseq'")
            cseqNumber = m.groupValues[1].toLong()
            cseqMethod = m.groupValues[2]
        }
        val from = h("from")
        val to = h("to")

        return ParsedSipMessage(
            isRequest = isRequest,
            method = method,
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            requestUri = requestUri,
            viaBranch = viaBranch,
            topVia = topVia,
            callId = h("callid")?.trim(),
            cseqNumber = cseqNumber,
            cseqMethod = cseqMethod,
            fromTag = from?.let { tagParam(it) },
            toTag = to?.let { tagParam(it) },
            fromUri = from?.let { uriPart(it) },
            toUri = to?.let { uriPart(it) },
            recordRoutes = all("recordroute"),
            contact = h("contact"),
            hasContentLength = h("contentlength") != null,
            headers = headers,
            body = body
        )
    }

    private fun extractBranch(via: String): String? {
        val m = Regex("""branch\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(via)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun tagParam(fieldValue: String): String? {
        val m = Regex("""tag\s*=\s*("?)([^";]+)\1""", RegexOption.IGNORE_CASE).find(fieldValue)
        return m?.groupValues?.get(2)?.trim()
    }

    private fun uriPart(fieldValue: String): String {
        val lt = fieldValue.indexOf('<')
        val gt = fieldValue.indexOf('>')
        return if (lt >= 0 && gt > lt) fieldValue.substring(lt + 1, gt) else fieldValue.substringBefore(';').trim()
    }
}
