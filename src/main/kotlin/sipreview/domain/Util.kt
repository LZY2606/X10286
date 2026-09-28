package sipreview.domain

import java.security.MessageDigest

object Hashing {
    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 内容寻址 ID：相同内容 => 相同稳定身份（跨导出/导入保持）。 */
    fun stableId(prefix: String, vararg parts: String): String {
        val canonical = parts.joinToString("\u0001")
        return "$prefix_" + sha256Hex(canonical).take(16)
    }
}

object TimeNorm {
    /**
     * 规范化为虚拟毫秒，保留原始表示与单位基准；无法转换时返回 null 并附诊断。
     */
    fun toVirtualMs(raw: String, tsUnit: String): Pair<Double?, String?> {
        val t = raw.trim()
        return when (tsUnit) {
            "virtual-seconds" -> t.toDoubleOrNull()?.let { it * 1000.0 to null }
                ?: (null to "无法解析为 virtual-seconds 数字: '$raw'（保留原位置）")
            "virtual-ms" -> t.toDoubleOrNull()?.let { it to null }
                ?: (null to "无法解析为 virtual-ms 数字: '$raw'（保留原位置）")
            "iso8601" -> parseIsoMillis(t)?.let { it.toDouble() to null }
                ?: (null to "无法解析 iso8601 时间: '$raw'（保留原位置）")
            "unknown" -> (null to "时间单位 unknown，保留原始表示 '$raw'，不参与时序")
            else -> (null to "未知时间单位 '$tsUnit'，保留原始表示 '$raw'")
        }
    }

    private fun parseIsoMillis(s: String): Long? = try {
        java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun decode(s: String): ByteArray {
        val clean = s.replace("\\s".toRegex(), "")
        require(clean.length % 2 == 0) { "hex 长度必须为偶数" }
        ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/** 稳定的参数指纹：定时器决定都记录“当时是哪版参数”。 */
fun EngineParams.fingerprint(): String =
    Hashing.stableId(
        "params",
        t1Ms.toString(), t2Ms.toString(), t4Ms.toString(),
        timerBMultiplier.toString(), timerDUdpMultiplier.toString()
    )
