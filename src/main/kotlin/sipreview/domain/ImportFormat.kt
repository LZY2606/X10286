package sipreview.domain

import java.util.Base64

/**
 * Import envelope (copy-paste friendly JSON). Each item carries its own direction,
 * connection generation and receive time; the message itself may be text or exact bytes.
 */
data class ImportItem(
    val direction: String?,
    val transport: String?,
    val connectionId: String?,
    val connectionGeneration: Int?,
    val localAddress: String?,
    val remoteAddress: String?,
    val receivedAt: String?,
    val clockSource: String?,
    val label: String?,
    val note: String?,
    val messageText: String?,
    val messageBase64: String?
)

data class ImportEnvelope(
    val format: String?,
    val batchName: String?,
    val items: List<ImportItem>?
)

data class PreparedItem(
    val direction: Direction,
    val transport: Transport,
    val connectionId: String,
    val connectionGeneration: Int,
    val localAddress: String,
    val remoteAddress: String,
    val receivedAtMillis: Long,
    val clockSource: String,
    val rawBytes: ByteArray,
    val rawText: String,
    val label: String?,
    val note: String?,
    val diagnostics: List<String>
)

object ImportValidator {

    fun validate(envelope: ImportEnvelope): Pair<List<PreparedItem>, List<String>> {
        val global = mutableListOf<String>()
        if (envelope.format != null && envelope.format != "sip-review-import/1") {
            global += "unsupported format: ${envelope.format} (expected sip-review-import/1)"
        }
        val items = envelope.items
        if (items == null) {
            global += "missing 'items' array"
            return emptyList<PreparedItem>() to global
        }
        if (items.isEmpty()) global += "'items' is empty; nothing to import"

        val prepared = mutableListOf<PreparedItem>()
        items.forEachIndexed { idx, item ->
            val errs = mutableListOf<String>()
            val where = "items[$idx]"
            val direction = when (item.direction?.lowercase()) {
                "ingress", "in", "recv", "received" -> Direction.INGRESS
                "egress", "out", "sent" -> Direction.EGRESS
                null -> null.also { errs += "$where.direction is required (ingress|egress)" }
                else -> null.also { errs += "$where.direction invalid: ${item.direction}" }
            }
            val transport = when (item.transport?.uppercase()) {
                "UDP" -> Transport.UDP
                "TCP" -> Transport.TCP
                "TLS" -> Transport.TLS
                null -> null.also { errs += "$where.transport is required (udp|tcp|tls)" }
                else -> null.also { errs += "$where.transport invalid: ${item.transport}" }
            }
            val generation = item.connectionGeneration
            if (generation == null || generation < 0) errs += "$where.connectionGeneration must be >= 0"
            val receivedAt = item.receivedAt?.let(::parseInstant)
            if (item.receivedAt == null) errs += "$where.receivedAt is required (ISO-8601)"
            else if (receivedAt == null) errs += "$where.receivedAt not ISO-8601: ${item.receivedAt}"

            val rawBytes: ByteArray? = when {
                item.messageBase64 != null -> try {
                    Base64.getDecoder().decode(item.messageBase64)
                } catch (e: IllegalArgumentException) {
                    errs += "$where.messageBase64 is not valid base64"; null
                }
                item.messageText != null -> item.messageText.toByteArray(Charsets.UTF_8)
                else -> { errs += "$where needs messageText or messageBase64"; null }
            }
            if (rawBytes != null) {
                val text = SipParser.decode(rawBytes)
                val parsed = SipParser.parse(text)
                errs += parsed.parseErrors.map { "$where.sip: $it" }
            }
            if (errs.isNotEmpty()) {
                global += errs
            } else {
                val bytes = rawBytes!!
                prepared += PreparedItem(
                    direction = direction!!,
                    transport = transport!!,
                    connectionId = item.connectionId?.takeIf { it.isNotBlank() }
                        ?: "${transport.name.lowercase()}-${item.remoteAddress ?: "unknown"}",
                    connectionGeneration = generation!!,
                    localAddress = item.localAddress ?: "",
                    remoteAddress = item.remoteAddress ?: "",
                    receivedAtMillis = receivedAt!!,
                    clockSource = item.clockSource ?: "wall",
                    rawBytes = bytes,
                    rawText = SipParser.decode(bytes),
                    label = item.label,
                    note = item.note,
                    diagnostics = emptyList()
                )
            }
        }
        return prepared to global
    }

    /** Accepts 2026-09-25T10:15:30.123Z or with explicit +09:00. */
    fun parseInstant(value: String): Long? = try {
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e2: Exception) { null }
    }
}
