package org.sipreview.engine

import org.sipreview.model.Direction

/** Deterministic, content-derived stable identifiers so identity survives export/import. */
object Ids {
    fun frameId(versionId: String, ordinal: Int) = "frame-$versionId-f${ordinal.toString().padStart(3, '0')}"
    fun versionId(versionNo: Int) = "v${versionNo.toString().padStart(2, '0')}"
    fun txnId(role: String, branch: String, method: String): String {
        val b = branch.ifEmpty { "no-branch" }
        return "txn-${role.lowercase()}-${method.uppercase()}-$b"
    }
    fun standaloneTxnId(kind: String, frameId: String) = "txn-$kind-$frameId"
    fun earlyDialogId(callId: String, fromTag: String, toTag: String) =
        "dlg-${slug(callId)}-${slug(fromTag ?: "notag")}-${slug(toTag ?: "notag")}"
    fun confirmedDialogId(callId: String, tagA: String, tagB: String): String {
        val pair = listOf(slug(tagA), slug(tagB)).sorted()
        return "dlg-conf-${slug(callId)}-${pair[0]}-${pair[1]}"
    }
    fun slug(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifEmpty { "x" }
    fun roleLabel(direction: Direction) =
        if (direction == Direction.OUTBOUND) "client" else "server"
}
