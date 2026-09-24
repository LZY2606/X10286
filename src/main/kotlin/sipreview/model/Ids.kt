package sipreview.model

import java.security.MessageDigest

object Ids {
    fun hash10(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    fun material(projectId: String, seq: Int, kind: String, rawHash: String): String =
        "mat_${hash10("v3", projectId, seq.toString(), kind, rawHash)}"

    fun derived(prefix: String, projectId: String, logicalKey: String): String =
        "${prefix}_${hash10("v3", projectId, logicalKey)}"

    fun step(projectId: String, genSeq: Int, index: Int, label: String, projectId: String): String =
        "step_${hash10("v3", projectId, genSeq.toString(), index.toString(), label)}"

    fun of(prefix: String, vararg parts: String): String =
        "${prefix}_${hash10("v3", *parts)}"
}
