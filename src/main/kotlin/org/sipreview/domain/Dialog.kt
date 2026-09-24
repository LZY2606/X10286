package org.sipreview.domain

import kotlinx.serialization.Serializable

enum class DialogState { EARLY, CONFIRMED, TERMINATED }

/**
 * Canonical dialog identity: Call-ID + local tag + remote tag. Tags are stored in a
 * canonical (sorted) order so the identity is independent of which side initiated.
 */
@Serializable
data class DialogKey(val callId: String, val tagA: String, val tagB: String) {
    val canonical: String get() = "$callId|$tagA|$tagB"
    companion object {
        fun of(callId: String, t1: String, t2: String): DialogKey =
            if (t1 <= t2) DialogKey(callId, t1, t2) else DialogKey(callId, t2, t1)
    }
}

@Serializable
data class Dialog(
    val key: DialogKey,
    val state: DialogState,
    val establishedBy: String,      // observation id that created it
    val confirmedBy: String? = null,
    val localUri: String? = null,
    val remoteUri: String? = null,
    val remoteTarget: String? = null,   // Contact of the peer
    val routeSet: List<String> = emptyList(),
    val localCSeq: Long? = null,
    val remoteCSeq: Long? = null,
    val forkingBranch: String? = null   // to-tag that forked this dialog off the call
)

object DialogRules {
    /**
     * A response to an INVITE that carries a to-tag creates/updates an early dialog.
     * A 2xx confirms it. Distinct to-tags fork into distinct dialogs.
     */
    fun dialogKeyFor(callId: String, fromTag: String?, toTag: String?): DialogKey? {
        if (callId.isBlank() || fromTag.isNullOrBlank() || toTag.isNullOrBlank()) return null
        return DialogKey.of(callId, fromTag, toTag)
    }

    /** Record-Route on the establishing message becomes the dialog route set. */
    fun routeSetFrom(recordRoutes: List<String>): List<String> =
        recordRoutes.map { it.trim() }.filter { it.isNotEmpty() }
}
