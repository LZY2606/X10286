package sipreview.sip

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import sipreview.model.Diag
import sipreview.model.ParsedMessage
import sipreview.model.ViaHeader
import java.util.Base64

object SipParser {

    private val COMPACT = mapOf(
        'v' to "via", 'f' to "from", 't' to "to", 'i' to "call-id",
        'm' to "contact", 'e' to "content-encoding", 'l' to "content-length",
        'c' to "content-type", 's' to "subject", 'k' to "supported",
        'r' to "refer-to", 'u' to "allow-events"
    )

    data class Raw(
        val startLine: String,
        val headerOrder: List<String>,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val rawBytes: ByteArray
    )

    fun splitWire(raw: ByteArray): Raw {
        val text = raw.toString(Charsets.UTF_8)
        val sep: Int = run {
            val idx = text.indexOf("\r\n\r\n")
            when {
                idx >= 0 -> idx + 4
                else -> {
                    val i2 = text.indexOf("\n\n")
                    if (i2 >= 0) i2 + 2 else -1
                }
            }
        }
        val headerBlock = if (sep >= 0) text.substring(0, sep) else text
        val body = if (sep >= 0) raw.copyOfRange(sep, raw.size) else ByteArray(0)
        val lines = headerBlock.split("\r\n", "\n").filter { it.isNotEmpty() }
        val order = mutableListOf<String>()
        val map = linkedMapOf<String, MutableList<String>>()
        var startLine = ""
        lines.forEachIndexed { idx, line ->
            if (idx == 0) { startLine = line.trim(); return@forEachIndexed }
            if (line[0] == ' ' || line[0] == '\t') {
                val last = order.lastOrNull()
                if (last != null) {
                    val cur = map[last]!!
                    cur[cur.lastIndex] = cur.last() + " " + line.trim()
                }
                return@forEachIndexed
            }
            val c = line.indexOf(':')
            if (c <= 0) return@forEachIndexed
            var name = line.substring(0, c).trim().lowercase()
            if (name.length == 1) name = COMPACT[name[0]] ?: name
            val value = line.substring(c + 1).trim()
            map.getOrPut(name) { mutableListOf() }.add(value)
            order.add(name)
        }
        return Raw(startLine, order, map, body, raw)
    }

    private fun paramFromHeader(value: String): Pair<String?, Map<String, String>> {
        val angle = value.indexOf('<')
        val semi = value.indexOf(';', startIndex = if (angle >= 0) angle else 0)
        if (semi < 0) return null to emptyMap()
        val params = linkedMapOf<String, String>()
        value.substring(semi + 1).split(';').forEach { p ->
            val eq = p.indexOf('=')
            if (eq > 0) {
                val k = p.substring(0, eq).trim().lowercase()
                var v = p.substring(eq + 1).trim()
                if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
                params[k] = v
            } else {
                params[p.trim().lowercase()] = ""
            }
        }
        return value.substring(0, semi) to params
    }

    private fun tagOf(value: String?): String? {
        if (value == null) return null
        return paramFromHeader(value).second["tag"]
    }

    private fun uriOf(value: String?): String? {
        if (value == null) return null
        val s = value.indexOf('<'); val e = value.indexOf('>')
        return if (s >= 0 && e > s) value.substring(s + 1, e) else value.substringBefore(';').trim()
    }

    private fun parseVia(raw: String, index: Int): ViaHeader {
        val body = raw.trim()
        val parts = body.split(Regex("\\s+"))
        val protocol = parts.getOrNull(0) ?: ""
        val sentBy = parts.getOrNull(1)
        val params = if (parts.size > 2) parts.drop(2).joinToString(" ") else ""
        var branch: String? = null; var rport: String? = null; var received: String? = null
        params.split(';').forEach { p ->
            val eq = p.indexOf('=')
            val k = (if (eq > 0) p.substring(0, eq) else p).trim().lowercase()
            val v = if (eq > 0) p.substring(eq + 1).trim() else ""
            when (k) { "branch" -> branch = v; "rport" -> rport = v.ifEmpty { "" }; "received" -> received = v }
        }
        val transport = protocol.split('/').getOrNull(2)?.uppercase()
        return ViaHeader(raw = body, transport = transport, sentBy = sentBy,
            branch = branch, rport = rport, received = received, viaIndex = index)
    }

    fun parse(materialUid: String, direction: String, rawBytes: ByteArray, connectionId: String? = null, connectionGeneration: Int? = null, receivedAtMs: Long? = null, rawHash: String = ""): ParsedMessage {
        val diags = mutableListOf<Diag>()
        val raw = splitWire(rawBytes)
        val startLine = raw.startLine
        val isRequest = startLine.startsWith("SIP/", ignoreCase = true).not()
        var method: String? = null; var statusCode: Int? = null; var reason: String? = null
        if (isRequest) {
            val sp = startLine.split(' ')
            method = sp.getOrNull(0)?.uppercase()
            if (method == null || sp.size < 3 || !sp.getOrNull(2).orEmpty().startsWith("SIP/")) {
                diags += Diag("BAD_REQUEST_LINE", "request line not 'METHOD SP URI SP SIP/2.0'", rawValue = startLine)
            }
            if (method != null && method !in setOf("INVITE", "ACK", "CANCEL", "BYE", "REGISTER", "OPTIONS", "PRACK", "UPDATE", "NOTIFY", "SUBSCRIBE", "PUBLISH", "MESSAGE", "REFER", "INFO")) {
                diags += Diag("UNKNOWN_METHOD", "method outside known set, retained verbatim", severity = "WARN", rawValue = method)
            }
        } else {
            val sp = startLine.split(Regex("\\s+"), 3)
            if (sp.size < 2 || !sp[0].startsWith("SIP/")) {
                diags += Diag("BAD_STATUS_LINE", "status line not 'SIP/2.0 SP code SP reason'", rawValue = startLine)
            }
            statusCode = sp.getOrNull(1)?.toIntOrNull()
            if (statusCode == null) diags += Diag("BAD_STATUS_CODE", "non-numeric status code", rawValue = sp.getOrNull(1))
            reason = sp.getOrNull(2)
        }

        val viaList = raw.headers["via"].orEmpty().mapIndexed { i, v -> parseVia(v, i) }
        if (viaList.isEmpty()) diags += Diag("MISSING_VIA", "no Via header present")
        val callId = raw.headers["call-id"]?.firstOrNull()?.also {
            if (it.isBlank()) diags += Diag("EMPTY_CALL_ID", "Call-ID present but empty")
        } ?: run { diags += Diag("MISSING_CALL_ID", "no Call-ID header present"); null }

        val from = raw.headers["from"]?.firstOrNull()
        val to = raw.headers["to"]?.firstOrNull()
        val cseqRaw = raw.headers["cseq"]?.firstOrNull()
        var cseqNum: Long? = null; var cseqMethod: String? = null
        if (cseqRaw == null) diags += Diag("MISSING_CSEQ", "no CSeq header present")
        else {
            val csp = cseqRaw.trim().split(Regex("\\s+"))
            cseqNum = csp.getOrNull(0)?.toLongOrNull()
            if (cseqNum == null) diags += Diag("BAD_CSEQ_NUMBER", "CSeq number not convertible, retained raw", rawValue = csp.getOrNull(0))
            cseqMethod = csp.getOrNull(1)?.uppercase()
            if (cseqMethod == null) diags += Diag("BAD_CSEQ_METHOD", "CSeq missing method", rawValue = cseqRaw)
        }

        val topBranch = viaList.firstOrNull()?.branch
        if (isRequest && topBranch != null && !topBranch.startsWith("z9hG4bK")) {
            diags += Diag("NON_RFC3261_BRANCH", "top Via branch lacks z9hG4bK magic cookie; matching kept on full branch", severity = "WARN", field = "via.branch", rawValue = topBranch)
        }
        if (isRequest && viaList.firstOrNull()?.branch == null) {
            diags += Diag("MISSING_BRANCH", "top Via has no branch parameter; synthesized key uses sent-by", severity = "WARN")
        }

        val declaredLen = raw.headers["content-length"]?.firstOrNull()?.trim()?.toLongOrNull()
        if (declaredLen != null && declaredLen != raw.body.size.toLong()) {
            diags += Diag("CONTENT_LENGTH_MISMATCH", "declared=$declaredLen actual=${raw.body.size}; both retained", severity = "WARN", field = "content-length", rawValue = declaredLen.toString())
        }
        val ct = raw.headers["content-type"]?.firstOrNull()
        val bodySdp = ct != null && ct.contains("sdp", ignoreCase = true)

        val headersJson = JsonObject(raw.headers.mapValues { (_, values) ->
            JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) })
        })

        return ParsedMessage(
            materialUid = materialUid,
            direction = direction,
            isRequest = isRequest,
            method = method,
            statusCode = statusCode,
            reasonPhrase = reason,
            startLine = startLine,
            headers = headersJson,
            via = viaList,
            callId = callId,
            fromTag = tagOf(from),
            toTag = tagOf(to),
            fromUri = uriOf(from),
            toUri = uriOf(to),
            cseqNumber = cseqNum,
            cseqMethod = cseqMethod,
            recordRoute = raw.headers["record-route"].orEmpty(),
            contact = raw.headers["contact"]?.firstOrNull()?.let { uriOf(it) ?: it },
            maxForwards = raw.headers["max-forwards"]?.firstOrNull()?.trim()?.toLongOrNull(),
            bodyLengthBytes = raw.body.size,
            bodySdp = bodySdp,
            topViaBranch = topBranch,
            connectionId = connectionId,
            connectionGeneration = connectionGeneration,
            receivedAtMs = receivedAtMs,
            rawHash = rawHash,
            diagnostics = diags
        )
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
