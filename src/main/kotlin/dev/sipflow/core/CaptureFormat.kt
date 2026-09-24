package dev.sipflow.core

/**
 * 离线捕获包格式（.sipflow，纯文本、可复制粘贴）。
 *
 * ```
 * SIPFLOW/1.0
 * project: SIP 对话谱：事务分支、路由集与重传时间线
 * description: ...
 * clock-unit: ms
 *
 * CONNECTION c1 udp 10.0.0.1:5060 10.0.0.2:5060 gen=0
 * OPEN c1 at=0
 *
 * MSG direction=ingress conn=c1 gen=0 at=12
 * <<<SIP
 * INVITE sip:b@example.com SIP/2.0
 * Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK77
 * ...
 * >>>
 * ```
 *
 * 设计约束：
 *  - 所有领域量携带单位或坐标基准（clock-unit、gen、地址对）。
 *  - 原始表示保留在 raw 字段；规范化在派生层。
 *  - 无法解析的行产生 CaptureFormatException（明确格式错误），不会静默丢弃。
 */
object CaptureFormat {

    class CaptureFormatException(message: String, val line: Int = -1) : Exception(
        if (line >= 0) "format error at line $line: $message" else "format error: $message"
    )

    data class CaptureBundle(
        val projectName: String,
        val description: String,
        val clockUnit: String,
        val connections: List<ConnectionSpec>,
        val transportEvents: List<TransportEventSpec>,
        val messages: List<MessageSpec>
    )

    data class ConnectionSpec(
        val name: String,
        val transport: Transport,
        val local: String,
        val remote: String,
        val generation: Int
    )

    data class TransportEventSpec(
        val kind: TransportEventKind,
        val connection: String,
        val atRaw: String,
        val atMs: Long
    )

    data class MessageSpec(
        val ordinal: Int,
        val direction: Direction,
        val connection: String,
        val generation: Int,
        val atRaw: String,
        val atMs: Long,
        val rawSip: String
    )

    fun parse(text: String): CaptureBundle {
        val rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        if (rawLines.firstOrNull { it.isNotBlank() }?.trim() != "SIPFLOW/1.0") {
            throw CaptureFormatException("first non-empty line must be 'SIPFLOW/1.0'")
        }

        var projectName = "imported"
        var description = ""
        var clockUnit = "ms"
        val connections = linkedMapOf<String, ConnectionSpec>()
        val transportEvents = mutableListOf<TransportEventSpec>()
        val messages = mutableListOf<MessageSpec>()

        var i = 0
        var ordinal = 0
        while (i < rawLines.size) {
            val lineNumber = i + 1
            val line = rawLines[i]
            val t = line.trim()
            when {
                t.isEmpty() || t.startsWith("#") -> Unit
                t.startsWith("project:", ignoreCase = true) -> projectName = t.substringAfter(":").trim()
                t.startsWith("description:", ignoreCase = true) -> description = t.substringAfter(":").trim()
                t.startsWith("clock-unit:", ignoreCase = true) -> clockUnit = t.substringAfter(":").trim()
                t.startsWith("CONNECTION", ignoreCase = true) -> {
                    val spec = parseConnection(t, lineNumber)
                    if (connections.containsKey(spec.name)) {
                        throw CaptureFormatException("duplicate CONNECTION '${spec.name}'", lineNumber)
                    }
                    connections[spec.name] = spec
                }
                t.startsWith("OPEN", ignoreCase = true) || t.startsWith("CLOSE", ignoreCase = true) ||
                    t.startsWith("RESET", ignoreCase = true) -> {
                    transportEvents.add(parseTransportEvent(t, lineNumber))
                }
                t.startsWith("MSG", ignoreCase = true) -> {
                    val head = parseMsgHeader(t, lineNumber)
                    if (i + 1 >= rawLines.size || rawLines[i + 1].trim() != "<<<SIP") {
                        throw CaptureFormatException("MSG block must be followed by '<<<SIP'", lineNumber)
                    }
                    val bodyLines = mutableListOf<String>()
                    var j = i + 2
                    var closed = false
                    while (j < rawLines.size) {
                        if (rawLines[j].trim() == ">>>") { closed = true; break }
                        bodyLines.add(rawLines[j])
                        j++
                    }
                    if (!closed) throw CaptureFormatException("MSG block missing closing '>>>'", lineNumber)
                    // 保留报文原有换行；首行前/末行后的空白行去除，正文内部不动。
                    val rawSip = bodyLines.joinToString("\n").trim('\n')
                    ordinal++
                    messages.add(
                        MessageSpec(ordinal, head.direction, head.connection, head.generation,
                            head.atRaw, head.atMs, rawSip)
                    )
                    i = j
                }
                else -> throw CaptureFormatException("unrecognized directive '$t'", lineNumber)
            }
            i++
        }

        for (ev in transportEvents) {
            if (!connections.containsKey(ev.connection))
                throw CaptureFormatException("transport event references unknown connection '${ev.connection}'")
        }
        for (m in messages) {
            if (!connections.containsKey(m.connection))
                throw CaptureFormatException("MSG references unknown connection '${m.connection}'")
            if (m.rawSip.isBlank())
                throw CaptureFormatException("MSG #${m.ordinal} has empty SIP body")
        }

        return CaptureBundle(projectName, description, clockUnit,
            connections.values.toList(), transportEvents, messages)
    }

    private data class MsgHead(val direction: Direction, val connection: String, val generation: Int,
                               val atRaw: String, val atMs: Long)

    private fun parseMsgHeader(t: String, line: Int): MsgHead {
        val attrs = parseAttrs(t.substringAfter("MSG"), line)
        val dir = when ((attrs["direction"] ?: errorAttr("direction", line)).lowercase()) {
            "ingress", "in", "recv", "received" -> Direction.INGRESS
            "egress", "out", "send", "sent" -> Direction.EGRESS
            else -> throw CaptureFormatException("direction must be ingress|egress", line)
        }
        val conn = attrs["connection"] ?: attrs["conn"] ?: errorAttr("connection", line)
        val gen = (attrs["generation"] ?: attrs["gen"] ?: "0").toIntOrNull()
            ?: throw CaptureFormatException("generation must be an integer", line)
        val atRaw = attrs["at"] ?: errorAttr("at", line)
        val atMs = parseClock(atRaw, line)
        return MsgHead(dir, conn, gen, atRaw, atMs)
    }

    private fun parseConnection(t: String, line: Int): ConnectionSpec {
        val parts = t.split(Regex("\\s+"))
        // CONNECTION name transport local remote  (gen= 在 attrs 中)
        if (parts.size < 5) throw CaptureFormatException(
            "CONNECTION expects: CONNECTION <name> <udp|tcp|tls> <local> <remote> [gen=N]", line)
        val transport = when (parts[2].lowercase()) {
            "udp" -> Transport.UDP; "tcp" -> Transport.TCP; "tls" -> Transport.TLS
            else -> throw CaptureFormatException("transport must be udp|tcp|tls", line)
        }
        val attrs = parseAttrs(t.substringAfter(parts[4]), line)
        val gen = (attrs["gen"] ?: "0").toIntOrNull()
            ?: throw CaptureFormatException("gen must be an integer", line)
        return ConnectionSpec(parts[1], transport, parts[3], parts[4], gen)
    }

    private fun parseTransportEvent(t: String, line: Int): TransportEventSpec {
        val kind = when (t.substringBefore(' ').uppercase()) {
            "OPEN" -> TransportEventKind.OPEN
            "CLOSE" -> TransportEventKind.CLOSE
            "RESET" -> TransportEventKind.RESET
            else -> throw CaptureFormatException("unknown transport event", line)
        }
        val firstSpace = t.indexOf(' ')
        val rest = if (firstSpace >= 0) t.substring(firstSpace + 1) else ""
        val tokens = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) throw CaptureFormatException("transport event missing connection name", line)
        val conn = tokens.first()
        val attrs = parseAttrs(t.substringAfter(conn), line)
        val atRaw = attrs["at"] ?: errorAttr("at", line)
        return TransportEventSpec(kind, conn, atRaw, parseClock(atRaw, line))
    }

    private fun parseAttrs(s: String, line: Int): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""(\w[\w-]*)=("([^"]*)"|\S+)""").findAll(s).forEach { m ->
            out[m.groupValues[1].lowercase()] = (m.groupValues[3].ifEmpty { m.groupValues[2] })
        }
        return out
    }

    private fun parseClock(raw: String, line: Int): Long =
        raw.toLongOrNull() ?: throw CaptureFormatException("clock value '$raw' must be an integer (virtual ms)", line)

    private fun errorAttr(name: String, line: Int): Nothing =
        throw CaptureFormatException("missing required attribute '$name'", line)
}
