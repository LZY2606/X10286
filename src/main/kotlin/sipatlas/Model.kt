package sipatlas

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.security.MessageDigest

object Ids {
    fun sha(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
    /** Content-addressed stable identity: same content -> same id, survives export/import. */
    fun id(prefix: String, vararg parts: String?): String =
        prefix + "_" + sha(parts.joinToString("") { (it ?: "∅") }).substring(0, 20)
}

val gson = GsonBuilder().serializeNulls().create()

/** Tunable engine parameters. Any change is a new versioned params input; the fingerprint
 *  is stamped on every derived result so old conclusions keep their original basis. */
data class EngineParams(
    val t1Ms: Long = 500,
    val t2Ms: Long = 4000,
    val t4Ms: Long = 5000,
    val timerBMs: Long = 32000,   // INVITE client transaction timeout (64*T1)
    val timerDMs: Long = 32000,   // wait for response retransmissions (client INVITE, UDP)
    val timerFMs: Long = 32000,   // non-INVITE client transaction timeout
    val timerHMs: Long = 32000,   // server INVITE: wait for ACK
    val timerKMs: Long = 5000,    // non-INVITE server: wait for request retransmissions
    val udpRetransmitToleranceMs: Long = 250, // tolerance around expected T1*2^k spacing
) {
    val fingerprint: String get() = Ids.id("fp", gson.toJson(this))

    companion object {
        const val ALGORITHM_VERSION = "sip-atlas-engine/1.0.0"
        fun fromJson(json: String): EngineParams = gson.fromJson(json, EngineParams::class.java)
    }
}

enum class Direction { A2B, B2A }

/** A parsed import unit before it becomes an immutable input revision. */
data class InputDraft(
    val kind: String,                 // message | connection | clock | params
    val direction: Direction? = null, // messages only
    val connId: String? = null,
    val connGen: Int? = null,
    val receivedAtMs: Long? = null,   // virtual clock reading
    val raw: String = "",
    val meta: Map<String, String> = emptyMap(),
)

/** The line-oriented capture format used by the case package and the paste box.
 *
 *   # comment
 *   @conn id=1 transport=udp event=open gen=1 t=0
 *   @conn id=1 event=close t=9000
 *   @clock anchor=start t=0
 *   @params t1Ms=500 timerBMs=32000
 *   @msg dir=A2B t=0 conn=1 gen=1
 *   INVITE sip:bob@b.example SIP/2.0
 *   ...
 *   @end
 */
object CapFormat {
    fun parse(text: String): Pair<List<InputDraft>, List<String>> {
        val drafts = mutableListOf<InputDraft>()
        val errors = mutableListOf<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() || line.trimStart().startsWith("#") -> i++
                line.startsWith("@msg") -> {
                    val attrs = parseAttrs(line.removePrefix("@msg"))
                    val body = StringBuilder()
                    i++
                    var closed = false
                    while (i < lines.size) {
                        if (lines[i].trim() == "@end") { closed = true; i++; break }
                        body.append(lines[i]).append("\r\n"); i++
                    }
                    if (!closed) { errors.add("@msg starting with '${line.take(40)}' misses @end"); break }
                    val raw = body.toString().trimEnd('\r', '\n')
                    val dir = attrs["dir"]?.let {
                        runCatching { Direction.valueOf(it) }.getOrElse {
                            errors.add("bad dir '$it'"); null
                        }
                    } ?: run { errors.add("@msg missing dir"); null }
                    val t = attrs["t"]?.toLongOrNull() ?: run { errors.add("@msg missing/bad t"); null }
                    if (dir != null && t != null) {
                        drafts += InputDraft("message", dir, attrs["conn"],
                            attrs["gen"]?.toIntOrNull(), t, raw)
                    }
                }
                line.startsWith("@conn") -> {
                    val a = parseAttrs(line.removePrefix("@conn"))
                    drafts += InputDraft("connection", null, a["id"], a["gen"]?.toIntOrNull(),
                        a["t"]?.toLongOrNull() ?: 0, line, a)
                    i++
                }
                line.startsWith("@clock") -> {
                    val a = parseAttrs(line.removePrefix("@clock"))
                    drafts += InputDraft("clock", null, null, null,
                        a["t"]?.toLongOrNull() ?: 0, line, a)
                    i++
                }
                line.startsWith("@params") -> {
                    val a = parseAttrs(line.removePrefix("@params"))
                    drafts += InputDraft("params", null, null, null, null, line, a)
                    i++
                }
                else -> { errors.add("unrecognized line ${i + 1}: ${line.take(60)}"); i++ }
            }
        }
        return drafts to errors
    }

    private fun parseAttrs(s: String): Map<String, String> =
        Regex("""(\w+)=("[^"]*"|\S+)""").findAll(s)
            .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
}
