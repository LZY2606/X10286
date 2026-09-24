package org.sipreview.ingest

import org.sipreview.model.ConnectionRecord
import org.sipreview.model.Direction
import org.sipreview.model.Transport

/**
 * Textual capture format. It is line-oriented metadata plus a verbatim SIP block;
 * the SIP block is copied byte-for-byte (CRLF preserved) and normalized only later
 * in the derived layer.
 *
 *   CLOCK origin=2026-09-25T10:00:00+09:00 unit=ms
 *   CONN c1 UDP 10.0.0.5:5060 <-> 10.0.0.1:5060 gen=1 opened=0 closed=
 *   >>> @recv=0 @conn=c1 @gen=1
 *   INVITE sip:bob@example.com SIP/2.0
 *   Via: SIP/2.0/UDP 10.0.0.5:5060;branch=z9hG4bK776
 *   ...
 *   .
 *
 * A line containing only "." terminates the verbatim block. Values that cannot be
 * converted (e.g. @recv=not-a-number) produce a FormatIssue with the source line.
 */
object ImportFormat {

    data class ParsedCapture(
        val clockOriginRaw: String?,
        val clockUnit: String,
        val connections: List<ConnectionRecord>,
        val frames: List<RawFrameInput>,
        val issues: List<FormatIssue>,
    )

    data class RawFrameInput(
        val ordinal: Int,
        val direction: Direction,
        val recvRaw: String,
        val recvMs: Long?,
        val connRef: String?,
        val gen: Int?,
        val bytes: ByteArray,
        val headerLineNo: Int,
    ) {
        override fun equals(other: Any?): Boolean = other is RawFrameInput && ordinal == other.ordinal
        override fun hashCode(): Int = ordinal
    }

    data class FormatIssue(val lineNo: Int, val code: String, val message: String)

    fun parse(text: String): ParsedCapture {
        val lines = text.split("\n")
        val issues = mutableListOf<FormatIssue>()
        var clockOrigin: String? = null
        var clockUnit = "ms"
        val conns = linkedMapOf<String, ConnectionRecord>()
        val frames = mutableListOf<RawFrameInput>()
        var ordinal = 0

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> Unit
                trimmed.startsWith("CLOCK", ignoreCase = true) -> {
                    val props = parseProps(trimmed.substringAfter(" "))
                    clockOrigin = props["origin"]
                    clockUnit = props["unit"] ?: "ms"
                    if (clockUnit != "ms") issues += FormatIssue(i + 1, "UNKNOWN_CLOCK_UNIT", "unit='$clockUnit' (only ms is fully supported; original retained)")
                }
                trimmed.startsWith("CONN", ignoreCase = true) -> {
                    parseConn(trimmed, i + 1, issues)?.let { conns[it.id] = it }
                }
                trimmed.startsWith(">>>") || trimmed.startsWith("<<<") -> {
                    val direction = if (trimmed.startsWith(">>>")) Direction.INBOUND else Direction.OUTBOUND
                    val markerLen = if (trimmed.startsWith(">>>")) 3 else 3
                    val props = parseProps(trimmed.substring(markerLen))
                    val headerLineNo = i + 1
                    val recvRaw = props["recv"]
                    if (recvRaw == null) {
                        issues += FormatIssue(headerLineNo, "MISSING_RECV", "frame header requires @recv=<virtual-time>")
                    }
                    val recvMs = recvRaw?.toLongOrNull()
                    if (recvRaw != null && recvMs == null) {
                        issues += FormatIssue(headerLineNo, "BAD_RECV", "@recv='$recvRaw' is not an integer $clockUnit; original retained, value unnormalized")
                    }
                    val connRef = props["conn"]
                    val gen = props["gen"]?.toIntOrNull()
                    if (props["gen"] != null && gen == null) {
                        issues += FormatIssue(headerLineNo, "BAD_GEN", "@gen='${props["gen"]}' is not an integer")
                    }
                    // Collect verbatim block.
                    val block = StringBuilder()
                    var j = i + 1
                    var terminated = false
                    while (j < lines.size) {
                        val bl = lines[j]
                        if (bl == "." || bl.trim() == ".") { terminated = true; break }
                        // A new metadata/frame line also ends the block defensively.
                        val bt = bl.trim()
                        if (bt.startsWith(">>>") || bt.startsWith("<<<") || bt.startsWith("CONN", true) || bt.startsWith("CLOCK", true)) break
                        block.append(bl).append('\n')
                        j++
                    }
                    if (!terminated) {
                        issues += FormatIssue(headerLineNo, "UNTERMINATED_BLOCK", "verbatim SIP block is not terminated by a line containing only '.'")
                    }
                    var bytes = block.toString().toByteArray(Charsets.UTF_8)
                    // Strip the single trailing newline added per collected line, then normalize
                    // terminators to CRLF ONLY for an explicitly LF authored block; original is
                    // already preserved by the caller storing the exact authored bytes.
                    if (bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) {
                        bytes = bytes.copyOfRange(0, bytes.size - 1)
                    }
                    bytes = ensureCrlf(bytes)
                    ordinal++
                    frames += RawFrameInput(ordinal, direction, recvRaw ?: "", recvMs, connRef, gen, bytes, headerLineNo)
                    i = j + 1
                    continue
                }
                else -> issues += FormatIssue(i + 1, "UNRECOGNIZED_LINE", "expected CLOCK/CONN/>>> frame, got: '$trimmed'")
            }
            i++
        }
        return ParsedCapture(clockOrigin, clockUnit, conns.values.toList(), frames, issues)
    }

    private fun ensureCrlf(bytes: ByteArray): ByteArray {
        // If the authored block uses LF-only line endings, convert to CRLF for SIP realism.
        val hasCr = bytes.contains('\r'.code.toByte())
        if (hasCr) return bytes
        val out = java.io.ByteArrayOutputStream()
        for (b in bytes) {
            if (b == '\n'.code.toByte()) out.write('\r'.code)
            out.write(b.toInt())
        }
        return out.toByteArray()
    }

    private fun parseProps(s: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Regex("@([A-Za-z_-]+)=(\"[^\"]*\"|\\S+)").findAll(s).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].removeSurrounding("\"")
        }
        return out
    }

    private fun parseConn(line: String, lineNo: Int, issues: MutableList<FormatIssue>): ConnectionRecord? {
        // CONN <id> <transport> <local> <-> <remote> gen=<n> opened=<t> closed=<t>
        val rest = line.substringAfter(' ')
        val tokens = rest.split(Regex("\\s+")).toMutableList()
        if (tokens.size < 5) {
            issues += FormatIssue(lineNo, "BAD_CONN", "usage: CONN <id> <UDP|TCP|TLS> <local> <-> <remote> gen=<n>")
            return null
        }
        val id = tokens.removeAt(0)
        val transport = try { Transport.valueOf(tokens.removeAt(0).uppercase()) } catch (e: Exception) {
            issues += FormatIssue(lineNo, "BAD_TRANSPORT", "unknown transport in '$line'")
            return null
        }
        val local = tokens.removeAt(0)
        if (tokens.removeAt(0) != "<->") {
            issues += FormatIssue(lineNo, "BAD_CONN", "expected '<->' separator in '$line'")
            return null
        }
        val remote = tokens.removeAt(0)
        val props = parseProps(tokens.joinToString(" "))
        val gen = props["gen"]?.toIntOrNull() ?: 1
        val (lAddr, lPort) = splitEndpoint(local)
        val (rAddr, rPort) = splitEndpoint(remote)
        if (lAddr == null || rAddr == null) {
            issues += FormatIssue(lineNo, "BAD_ENDPOINT", "endpoint must be host:port in '$line'")
            return null
        }
        return ConnectionRecord(
            id = id,
            transport = transport,
            localAddr = lAddr,
            localPort = lPort ?: -1,
            remoteAddr = rAddr,
            remotePort = rPort ?: -1,
            generation = gen,
            connectedAtMs = props["opened"]?.toLongOrNull(),
            closedAtMs = props["closed"]?.toLongOrNull(),
            sourceRefs = listOf("line:$lineNo"),
        )
    }

    private fun splitEndpoint(ep: String): Pair<String, Int?>? {
        val idx = ep.lastIndexOf(':')
        if (idx <= 0) return null
        val host = ep.substring(0, idx)
        val port = ep.substring(idx + 1).toIntOrNull()
        return host to port
    }
}
