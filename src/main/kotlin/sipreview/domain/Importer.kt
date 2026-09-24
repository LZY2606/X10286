package sipreview.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class ImportError(val line: Int, val code: String, val message: String)

@Serializable
data class ImportResult(
    val accepted: List<RawRecord>,
    val errors: List<ImportError>,
    val parserVersion: String = SipParser.ALGO_VERSION,
)

/**
 * 导入 JSONL。每行：
 * {"dir":"SEND|RECV","bytes":"...原始 SIP 文本（按 UTF-8 转字节）...","conn":"c1","gen":0,
 *  "transport":"UDP","src":"127.0.0.1:5060","dst":"127.0.0.1:5061",
 *  "time":"t+500ms","basis":"virtual-ms"}
 * bytes 也可以是 base64:"...."（键 bytes_b64）。
 * 时间坐标基准 basis: virtual-ms | virtual-us | virtual-ns | wall-iso；
 *  无法转换时保留 orig，诊断进 ParsedMessage 同级 ImportError，记录仍不吞掉（bytes 合法时）。
 */
object Importer {
    private val json = Json { ignoreUnknownKeys = true }

    fun importJsonl(
        projectId: String,
        text: String,
        startOrdinal: Long,
        revisionMode: RevisionMode,
        idFactory: IdFactory,
        commitNanos: Long,
    ): ImportResult {
        val accepted = mutableListOf<RawRecord>()
        val errors = mutableListOf<ImportError>()
        text.lineSequence().forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val obj = try {
                json.parseToJsonElement(line) as JsonObject
            } catch (e: Exception) {
                errors += ImportError(lineNo, "BAD_JSON", "不是合法 JSON：${e.message}"); return@forEachIndexed
            }
            val dirStr = obj["dir"]?.jsonPrimitive?.content
            val direction = when (dirStr?.uppercase()) {
                "SEND", "SENT" -> Direction.SENT
                "RECV", "RECEIVED" -> Direction.RECV
                else -> { errors += ImportError(lineNo, "BAD_DIR", "dir 必须是 SEND 或 RECV（得到 $dirStr）"); return@forEachIndexed }
            }
            val bytes = when {
                obj.containsKey("bytes_b64") -> runCatching { SipParser.decode(obj["bytes_b64"]!!.jsonPrimitive.content) }
                    .getOrElse { errors += ImportError(lineNo, "BAD_B64", "bytes_b64 非法 base64：${it.message}"); return@forEachIndexed }
                obj.containsKey("bytes") -> obj["bytes"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
                else -> { errors += ImportError(lineNo, "NO_BYTES", "缺少 bytes 或 bytes_b64"); return@forEachIndexed }
            }
            val conn = obj["conn"]?.jsonPrimitive?.content
                ?: run { errors += ImportError(lineNo, "NO_CONN", "缺少 conn（连接 id）"); return@forEachIndexed }
            val gen = obj["gen"]?.jsonPrimitive?.longOrNull ?: 0L
            val transport = obj["transport"]?.jsonPrimitive?.content?.uppercase()
                ?: run { errors += ImportError(lineNo, "NO_TRANSPORT", "缺少 transport"); return@forEachIndexed }
            if (transport !in listOf("UDP", "TCP", "TLS", "WS", "WSS")) {
                errors += ImportError(lineNo, "BAD_TRANSPORT", "transport 不支持：$transport"); return@forEachIndexed
            }
            val timeOrig = obj["time"]?.jsonPrimitive?.content
                ?: run { errors += ImportError(lineNo, "NO_TIME", "缺少 time（原始时间表示）"); return@forEachIndexed }
            val basis = obj["basis"]?.jsonPrimitive?.content ?: "virtual-ms"
            val (nanos, convDiag) = convertTime(timeOrig, basis)
            if (nanos == null) errors += ImportError(lineNo, "BAD_TIME", "无法把 '$timeOrig'（基准 $basis）转换为虚拟纳秒：原值保留")
            val parsed = SipParser.parse(bytes)
            val withDiag = if (convDiag != null) parsed.copy(diagnostics = parsed.diagnostics + Diagnostic("BAD_TIME", convDiag)) else parsed
            val src = obj["src"]?.jsonPrimitive?.content
            val dst = obj["dst"]?.jsonPrimitive?.content
            val ordinal = startOrdinal + idx
            val id = idFactory.scoped(
                "rec", projectId, ordinal, direction.name, conn, gen, timeOrig, bytes.map { it.toInt() and 0xff }
            )
            accepted += RawRecord(
                id = id, projectId = projectId, ordinal = ordinal, direction = direction,
                bytesBase64 = SipParser.encode(bytes), connectionId = conn, connectionGeneration = gen,
                transport = transport, srcAddr = src, dstAddr = dst,
                recvOrig = timeOrig, recvBasis = basis, recvNanos = nanos ?: (commitNanos),
                parentRecordId = revisionMode.parentFor?.let { idFactory.scoped("rec", projectId, it) },
                revisionOf = revisionMode.revisionOf, supersedes = revisionMode.supersedes,
                parserVersion = SipParser.ALGO_VERSION,
                paramFingerprint = "input:${idFactory.deterministic(projectId, timeOrig, basis, conn, gen)}",
                parsed = withDiag, committedAtNanos = commitNanos,
            )
        }
        return ImportResult(accepted, errors)
    }

    /** t+500ms / 1200us / 2s / 2500000ns / 125（裸数字按基准单位）。 */
    fun convertTime(orig: String, basis: String): Pair<Long?, String?> {
        val s = orig.trim().removePrefix("t+").removePrefix("t=")
        val (numPart, unit) = Regex("^([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|ms|s)?$").matchEntire(s)?.destructured
            ?: return null to "时间格式无法识别"
        val n = numPart.toBigDecimalOrNull() ?: return null to "时间数字非法"
        val multiplier = when (unit.ifBlank { defaultUnit(basis) }) {
            "ns" -> 1L; "us", "µs" -> 1_000L; "ms" -> 1_000_000L; "s" -> 1_000_000_000L
            else -> return null to "时间单位非法"
        }
        return n.multiply(multiplier.toBigDecimal()).toBigInteger().longValueExact() to null
    }

    private fun defaultUnit(basis: String) = when (basis) {
        "virtual-us" -> "us"; "virtual-ns" -> "ns"; else -> "ms"
    }
}

data class RevisionMode(
    val revisionOf: String? = null,
    val supersedes: String? = null,
    val parentFor: String? = null,
)
