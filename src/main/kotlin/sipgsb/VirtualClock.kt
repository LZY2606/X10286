package sipgsb

import java.util.PriorityQueue

/** Virtual clock: all times in the system are logical milliseconds on this clock.
 *  Tests and replays advance it explicitly; nothing reads wall time. */
class VirtualClock(startMs: Long = 0) {
    var nowMs: Long = startMs
        private set

    private data class Scheduled(val atMs: Long, val order: Long, val id: String, val note: String)

    private var counter = 0L
    private val queue = PriorityQueue<Scheduled>(compareBy({ it.atMs }, { it.order }))
    val fired = mutableListOf<TimerDecision>()
    val pending = mutableListOf<Pair<Long, String>>()

    fun schedule(delayMs: Long, id: String, note: String) {
        queue.add(Scheduled(nowMs + delayMs, counter++, id, note))
    }

    fun advanceTo(targetMs: Long) {
        require(targetMs >= nowMs) { "virtual clock cannot go backwards: $targetMs < $nowMs" }
        while (queue.isNotEmpty() && queue.peek().atMs <= targetMs) {
            val s = queue.poll()
            nowMs = s.atMs
            fired.add(TimerDecision(id = s.id, timer = s.note, atMs = s.atMs,
                decision = "fired", basis = "virtual-clock@${s.atMs}ms"))
        }
        nowMs = targetMs
    }

    fun snapshotPending(): List<Pair<Long, String>> =
        queue.sortedBy { it.atMs }.map { it.atMs to "${it.id}: ${it.note}" }
}
