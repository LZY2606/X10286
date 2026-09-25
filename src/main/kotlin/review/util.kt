package review

import java.security.MessageDigest

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun sha256Bytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/** 稳定短身份：前缀 + 内容哈希前 12 位，导入/导出保持不变。 */
fun stableId(prefix: String, content: String): String =
    "$prefix_${sha256Hex(content).take(12)}"
