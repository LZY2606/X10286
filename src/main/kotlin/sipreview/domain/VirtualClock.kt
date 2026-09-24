package sipreview.domain

import java.security.MessageDigest

/**
 * 虚拟时钟：测试与导入重放全部走它。真实挂钟只用于“提交时刻”的元信息，
 * 且始终与虚拟接收时间分开保存（recvOrig/recvBasis 保留原始表示）。
 */
class VirtualClock(initialNanos: Long = 0L) {
    private var now: Long = initialNanos
    fun nowNanos(): Long = now
    fun advance(nanos: Long): Long { now += nanos; return now }
    fun set(nanos: Long) { now = nanos }
}

/** 稳定身份：由内容决定，导出->删库->导入后保持不变。 */
class IdFactory(private val seed: Long) {
    fun deterministic(vararg parts: Any?): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(seed.toString().toByteArray())
        parts.flatMap { if (it is List<*>) it else listOf(it) }.forEach {
            md.update(0x1F); md.update(it.toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
    fun scoped(scope: String, vararg parts: Any?): String =
        "$scope:${deterministic(scope, *parts)}"
}
