package sipgsb

interface Clock {
    fun nowMs(): Long
}

class SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

class VirtualClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) { now += ms }
    fun set(ms: Long) { now = ms }
}
