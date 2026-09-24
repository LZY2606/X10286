package sipreview.engine

import sipreview.domain.ALGO_VERSION
import sipreview.domain.sha1

/**
 * Deterministic, stable identity scheme.
 *
 * Every derived id is a pure function of (direct sources + algorithm version + params fingerprint),
 * so export/delete/import and full replay reproduce identical ids.
 */
object Ids {
    fun messageViewId(recordId: String) = "msg::$recordId"

    fun txnId(side: String, key: String, fp: String): String =
        "txn::${side}::" + sha1("$key|$ALGO_VERSION|$fp").take(16)

    fun dialogId(callId: String, localTag: String, remoteTag: String?, fp: String): String =
        "dlg::" + sha1("$callId|$localTag|${remoteTag ?: "-"}|$ALGO_VERSION|$fp").take(16)

    fun routeSetId(dialogId: String) = "route::" + dialogId.removePrefix("dlg::")

    fun timerId(txnId: String, timer: String, attempt: Int): String =
        "tmr::${txnId.removePrefix("txn::")}::$timer:$attempt"

    fun candidateId(clazz: String, sources: List<String>, fp: String): String =
        "cand::" + sha1("$clazz|${sources.sorted().joinToString(",")}|$fp").take(16)

    fun findingId(clazz: String, sources: List<String>): String =
        "find::" + sha1("$clazz|${sources.sorted().joinToString(",")}").take(16)
}
