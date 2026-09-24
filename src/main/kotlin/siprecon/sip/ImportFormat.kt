package siprecon.sip

import java.security.MessageDigest

object Ids {
    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun short(s: String, n: Int = 16) = sha256(s).take(n)
}

/** Direction of capture, relative to endpoint A (the UAC side of the trace). */
enum class Direction(val wire: String) {
    A_TO_B("ab"), B_TO_A("ba");
    companion object { fun of(s: String) = entries.firstOrNull { it.wire == s } }
}

/**
 * One captured message as submitted by the operator. Raw representation is kept verbatim;
 * [vtimeMs] is the normalized virtual-clock value in milliseconds; when normalization fails,
 * [vtimeMs] is null and [diagnostics] explains why (the raw value stays in [vtimeRaw]).
 */
data class CapturedMessage(
    val id: String,
    val direction: Direction,
    val connGen: Int,          // transport connection generation (TCP reconnect bumps it)
    val vtimeRaw: String,      // original representation, e.g. "1200ms" or "1.2s"
    val vtimeMs: Long?,        // normalized to ms, virtual clock basis
    val ordinal: Int,          // submission order, tie-breaks equal vtime
    val message: SipMessage?,
    val diagnostics: List<String> = emptyList(),
)

class ImportFormatException(val errors: List<String>) : Exception(errors.joinToString("\n")) {
    constructor(msg: String) : this(listOf(msg))
}

/**
 * Copy-paste friendly import format. Blocks separated by lines of only '---'.
 * Each block starts with a meta line:  # dir=ab conn=1 vtime=1200ms
 * followed by a raw SIP message. Format errors are reported with block and line numbers.
 */
object ImportFormat {
    private val META = Regex("""^#\s*(\w+=\S+(\s+\w+=\S+)*)\s*$""")

    fun parseBlocks(text: String): List<CapturedMessage> {
        val blocks = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        for (ln in text.lines()) {
            if (ln.trim() == "---") { if (cur.isNotEmpty()) blocks.add(cur); cur = mutableListOf() }
            else cur.add(ln)
        }
        if (cur.any { it.isNotBlank() }) blocks.add(cur)
        if (blocks.isEmpty()) throw ImportFormatException("no message blocks found (separate blocks with '---')")

        val errors = mutableListOf<String>()
        val out = mutableListOf<CapturedMessage>()
        blocks.forEachIndexed { bi, block ->
            val first = block.firstOrNull { it.isNotBlank() }
            if (first == null) return@forEachIndexed
            val meta = META.matchEntire(first.trim())
            if (meta == null) { errors.add("block ${bi + 1}: first line must be meta '# dir=ab conn=1 vtime=0ms', got: '$first'"); return@forEachIndexed }
            val kv = meta.groupValues[1].split(Regex("""\s+""")).associate {
                val p = it.split("=", limit = 2); p[0] to p.getOrElse(1) { "" }
            }
            val dir = kv["dir"]?.let { Direction.of(it) }
            if (dir == null) { errors.add("block ${bi + 1}: dir must be 'ab' or 'ba', got '${kv["dir"]}'"); return@forEachIndexed }
            val conn = kv["conn"]?.toIntOrNull()
            if (conn == null || conn < 1) { errors.add("block ${bi + 1}: conn must be a positive integer, got '${kv["conn"]}'"); return@forEachIndexed }
            val vtimeRaw = kv["vtime"] ?: run { errors.add("block ${bi + 1}: missing vtime"); return@forEachIndexed }
            val rawSip = block.dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
            if (rawSip.isEmpty()) { errors.add("block ${bi + 1}: empty SIP message"); return@forEachIndexed }
            val (vtimeMs, vdiag) = normalizeVtime(vtimeRaw)
            val msg = try { SipParser.parse(rawSip) } catch (e: SipParseException) {
                errors.add("block ${bi + 1}: ${e.message}"); return@forEachIndexed
            }
            val id = "msg-" + Ids.short("$dir|$conn|$vtimeRaw|$bi|$rawSip")
            out.add(CapturedMessage(id, dir, conn, vtimeRaw, vtimeMs, bi, msg, vdiag))
        }
        if (errors.isNotEmpty()) throw ImportFormatException(errors)
        return out
    }

    /** Returns (ms, diagnostics). Unconvertible values stay raw with a diagnostic. */
    fun normalizeVtime(raw: String): Pair<Long?, List<String>> {
        raw.toLongOrNull()?.let { return it to emptyList() }
        val m = Regex("""^(\d+(?:\.\d+)?)(ms|s|us)$""").matchEntire(raw.trim())
            ?: return null to listOf("vtime '$raw' has unknown unit; kept raw, excluded from timer derivations")
        val v = m.groupValues[1].toDouble()
        val ms = when (m.groupValues[2]) { "ms" -> v; "s" -> v * 1000; "us" -> v / 1000; else -> v }
        return ms.toLong() to emptyList()
    }
}
