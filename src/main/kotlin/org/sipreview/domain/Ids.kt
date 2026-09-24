package org.sipreview.domain

import java.security.MessageDigest

/**
 * Stable, content-derived identifiers.
 *
 * Stability requirements:
 *  - identical logical messages (same identity tuple) keep the same identity across
 *    UDP retransmissions and TCP reconnects on different connection generations;
 *  - derived entities keep the same lineage id across re-derivation when inputs are
 *    unchanged, but a distinct version id whenever inputs or rules change;
 *  - ids survive export -> fresh database -> import byte-for-byte.
 */
object Ids {
    private fun sha256Hex(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEachIndexed { idx, p ->
            md.update(p.toByteArray(Charsets.UTF_8))
            if (idx != parts.lastIndex) md.update(byteArrayOf(0x1F))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun hash(parts: List<String>): String = sha256Hex(parts)

    fun short(parts: List<String>): String = sha256Hex(parts).take(16)

    /** Identity of a logical SIP message, independent of connection generation. */
    fun messageIdentity(callId: String, cseqNum: Long, method: String, branch: String, direction: String, roleKey: String): String =
        "msg_" + short(listOf("v1", callId, cseqNum.toString(), method, branch, direction, roleKey))

    /** Per-observation id: same identity re-seen at a different time/generation differs here. */
    fun observationId(identity: String, receivedAt: Long, connGen: Int, rawHash: String): String =
        "obs_" + short(listOf("v1", identity, receivedAt.toString(), connGen.toString(), rawHash))

    /** Stable lineage for a derived entity (transaction / dialog / route-set / timer). */
    fun lineage(kind: String, key: String): String =
        "lin_" + short(listOf("v1", kind, key))

    /** A concrete version of a derived entity. */
    fun versionId(lineageId: String, inputsHash: String, algorithmVersion: String, paramsFingerprint: String): String =
        "ver_" + short(listOf("v2", lineageId, inputsHash, algorithmVersion, paramsFingerprint))

    fun algorithmFingerprint(algorithmVersion: String, paramsFingerprint: String): String =
        "fp_" + short(listOf("v1", algorithmVersion, paramsFingerprint))
}
