package sipreview.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

@Serializable
data class FormatError(val line: Int, val column: Int, val code: String, val message: String, val excerpt: String)

@Serializable
data class ParsedSample(
    val entries: List<SampleEntry>,
    val errors: List<FormatError>,
    val header: JsonObject
)

@Serializable
data class SampleEntry(
    val kind: String,
    val line: Int,
    val direction: String?,
    val connectionId: String?,
    val connectionGeneration: Int?,
    val receivedAtOriginal: String?,
    val receivedAtMs: Long?,
    val timeUnit: String?,
    val rawBytes: ByteArray,
    val note: String?
)

object SampleFormat {
    private val DIR = Regex("""^(IN|OUT)\s+(UDP|TCP|TLS)(?:/([A-Za-z0-9_.\-]+)(?:#(\d+))?)?(?:\s+@(\S+))?(?:\s*//\s*(.*))?$""")
    private val CONNLINE = Regex("""^CONN\s+(UDP|TCP|TLS)/([A-Za-z0-9_.\-]+)(?:#(\d+))?\s+(\S+)\s+(\S+)(?:\s+@(\S+))?(?:\s*//\s*(.*))?$""")
    private val CLOCKLINE = Regex("""^CLOCK\s+(\S+)\s*=\s*(\S+)(?:\s+(ms|s|virtual-tick))?(?:\s*//\s*(.*))?$""")

    fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun parse(input: String): ParsedSample {
        val errors = mutableListOf<FormatError>()
        val entries = mutableListOf<SampleEntry>()
        val lines = input.replace("\r\n", "\n").split("\n")
        var header = buildJsonObject { put("format", "sipreview-sample-1") }
        var i = 0
        while (i < lines.size) {
            val lineNo = i + 1
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) { i++; continue }
            if (trimmed.startsWith("PROJECT ")) {
                header = buildJsonObject { put("format", "sipreview-sample-1"); put("projectName", trimmed.removePrefix("PROJECT ").trim()) }
                i++; continue
            }
            if (trimmed.startsWith("CLOCK ")) {
                val m = CLOCKLINE.matchEntire(trimmed)
                if (m == null) {
                    errors += FormatError(lineNo, 1, "BAD_CLOCK_LINE", "expected 'CLOCK <id> = <epoch> [ms|s|virtual-tick]'", trimmed)
                    i++; continue
                }
                val raw = m.groupValues[2]; val unit = m.groupValues[3].ifEmpty { "ms" }
                val ms = parseClock(raw, unit)
                if (ms == null) errors += FormatError(lineNo, trimmed.indexOf(raw) + 1, "BAD_CLOCK_VALUE", "cannot convert '$raw' in unit '$unit'; raw retained in place", raw)
                entries += SampleEntry("CLOCK", lineNo, null, null, null, raw, ms, unit.ifEmpty { "ms" },
                    trimmed.toByteArray(), m.groupValues[4].ifEmpty { null })
                i++; continue
            }
            if (trimmed.startsWith("CONN ")) {
                val m = CONNLINE.matchEntire(trimmed)
                if (m == null) {
                    errors += FormatError(lineNo, 1, "BAD_CONN_LINE", "expected 'CONN UDP|TCP|TLS/<id>[#<gen>] <local> <remote> @<time>'", trimmed)
                    i++; continue
                }
                val gen = m.groupValues[3].ifEmpty { "1" }.toIntOrNull()
                if (gen == null) errors += FormatError(lineNo, 1, "BAD_CONN_GENERATION", "connection generation not an integer", m.groupValues[3])
                val raw = m.groupValues[6]; val ms = parseClock(raw, "ms")
                if (raw.isNotEmpty() && ms == null) errors += FormatError(lineNo, trimmed.indexOf(raw) + 1, "BAD_CLOCK_VALUE", "cannot convert '$raw'; raw retained in place", raw)
                entries += SampleEntry("CONNECTION", lineNo, null, m.groupValues[2], gen ?: 1,
                    raw.ifEmpty { null }, if (raw.isEmpty()) null else ms, "ms", trimmed.toByteArray(), m.groupValues[7].ifEmpty { null })
                i++; continue
            }
            if (trimmed == "===") {
                val block = mutableListOf<String>()
                val startLine = lineNo
                i++
                while (i < lines.size && lines[i].trim() != "===") { block += lines[i]; i++ }
                if (i >= lines.size) {
                    errors += FormatError(lineNo, 1, "UNTERMINATED_BLOCK", "message block opened with '===' is never closed", "===")
                    break
                }
                val closeLine = i + 1
                if (block.isEmpty()) {
                    errors += FormatError(startLine, 1, "EMPTY_BLOCK", "message block is empty", "===")
                    i++; continue
                }
                val meta = block.removeAt(0).trim()
                val mm = DIR.matchEntire(meta)
                if (mm == null) {
                    errors += FormatError(startLine + 1, 1, "BAD_MESSAGE_META",
                        "first block line must be 'IN|OUT UDP|TCP|TLS[/<conn>[#<gen>]] [@<time>] [// note]'", meta)
                    i++; continue
                }
                val sipText = block.joinToString("\n")
                if (!sipText.contains("SIP/2.0", ignoreCase = true)) {
                    errors += FormatError(startLine + 2, 1, "NOT_SIP", "block body contains no SIP/2.0 start line", sipText.lineSequence().firstOrNull().orEmpty())
                }
                val rawTime = mm.groupValues[5]
                val timeMs = if (rawTime.isEmpty()) null else parseClock(rawTime, "ms")
                if (rawTime.isNotEmpty() && timeMs == null) {
                    errors += FormatError(startLine + 1, meta.indexOf("@") + 1, "BAD_CLOCK_VALUE", "cannot convert '$rawTime'; raw retained in place", rawTime)
                }
                val wire = (sipText + "\r\n").toByteArray(Charsets.UTF_8)
                entries += SampleEntry("MESSAGE", startLine, mm.groupValues[1],
                    mm.groupValues[3].ifEmpty { null },
                    mm.groupValues[4].ifEmpty { null }?.toIntOrNull() ?: if (mm.groupValues[3].isNotEmpty()) 1 else null,
                    rawTime.ifEmpty { null }, timeMs, "ms", wire, mm.groupValues[6].ifEmpty { null })
                i++; continue
            }
            errors += FormatError(lineNo, 1, "UNRECOGNIZED_LINE", "line is neither a message block (===), CLOCK, CONN, PROJECT or comment", trimmed.take(80))
            i++
        }
        return ParsedSample(entries, errors, header)
    }

    private fun parseClock(raw: String, unit: String): Long? {
        val decimal = raw.toBigDecimalOrNull() ?: return null
        return when (unit) {
            "ms", "" -> decimal.toBigInteger().toString().toLongOrNull()
            "s" -> decimal.multiply(1000.toBigDecimal()).toBigInteger().toString().toLongOrNull()
            "virtual-tick" -> null
            else -> null
        }
    }
}
