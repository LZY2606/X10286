package org.sipreview.domain

import kotlinx.serialization.Serializable

/** Client (UAC) transaction states, RFC 3261 17.1.1.2 / 17.1.2.2. */
enum class ClientState { CALLING, CT_PROCEEDING, CT_COMPLETED, CT_TERMINATED }

/** Server (UAS) transaction states, RFC 3261 17.2.1 / 17.2.2. */
enum class ServerState {
    ST_PROCEEDING, ST_COMPLETED, ST_CONFIRMED, ST_TERMINATED,
    ST_TRYING, ST_NI_COMPLETED
}

@Serializable
data class TxnTransition(
    val observationId: String,
    val role: String,
    val from: String?,
    val to: String,
    val trigger: String,
    val note: String = ""
)

/**
 * Pure state-machine logic for one transaction. It models both legs of an on-path
 * observation point: the C2S leg drives the server side and the S2C leg drives the
 * client side.
 */
object TransactionMachine {

    fun isProvisional(code: Int?) = code != null && code in 100..199
    fun isSuccess(code: Int?) = code != null && code in 200..299
    fun isFinalNon2xx(code: Int?) = code != null && code in 300..699

    fun initialClient(invite: Boolean) = if (invite) ClientState.CALLING else ClientState.CT_PROCEEDING
    fun initialServer(invite: Boolean) = if (invite) ServerState.ST_PROCEEDING else ServerState.ST_TRYING

    fun stepClient(state: ClientState, invite: Boolean, status: Int, isAckNon2xx: Boolean, is2xxAck: Boolean): Pair<ClientState, String> {
        if (invite) return when (state) {
            ClientState.CALLING -> when {
                isProvisional(status) -> ClientState.CT_PROCEEDING to "1xx provisional"
                isSuccess(status) -> ClientState.CT_TERMINATED to "2xx: txn terminated, ACK handled by dialog"
                else -> ClientState.CT_COMPLETED to "non-2xx final: await ACK"
            }
            ClientState.CT_PROCEEDING -> when {
                isSuccess(status) -> ClientState.CT_TERMINATED to "2xx while proceeding"
                isAckNon2xx -> state to "non-2xx ACK is absorbed by INVITE txn (no state move yet)"
                else -> ClientState.CT_COMPLETED to "non-2xx final while proceeding"
            }
            ClientState.CT_COMPLETED -> when {
                isAckNon2xx -> ClientState.CT_TERMINATED to "non-2xx ACK completes INVITE txn"
                isSuccess(status) -> state to "LATE 2xx after txn completion (Timer D / post-cancel)"
                else -> state to "retransmitted response ignored while COMPLETED"
            }
            ClientState.CT_TERMINATED -> when {
                isSuccess(status) -> state to "LATE 2xx after termination (race / forking residual)"
                else -> state to "ignored: client txn already TERMINATED"
            }
        }
        // non-INVITE client
        return when (state) {
            ClientState.CT_PROCEEDING -> if (isSuccess(status) || isFinalNon2xx(status))
                ClientState.CT_COMPLETED to "final response"
            else state to "provisional ignored"
            ClientState.CT_COMPLETED -> {
                if (isSuccess(status) || isFinalNon2xx(status))
                    ClientState.CT_TERMINATED to "Timer J fires / final absorbed"
                else state to "provisional ignored"
            }
            else -> state to "non-INVITE client idle"
        }
    }

    fun stepServer(
        state: ServerState,
        invite: Boolean,
        isRequest: Boolean,
        method: String?,
        status: Int,
        isAckNon2xx: Boolean
    ): Pair<ServerState, String> {
        if (invite) return when (state) {
            ServerState.ST_PROCEEDING -> when {
                isRequest && method == "ACK" -> state to "ACK without non-2xx (dialog-level) ignored by txn"
                isProvisional(status) -> state to "provisional sent"
                isSuccess(status) -> ServerState.ST_TERMINATED to "2xx sent: txn ends, ACK is separate"
                else -> ServerState.ST_COMPLETED to "non-2xx final sent, absorb retransmits"
            }
            ServerState.ST_COMPLETED -> when {
                isAckNon2xx -> ServerState.ST_CONFIRMED to "non-2xx ACK received -> CONFIRMED"
                isRequest -> state to "INVITE retransmit absorbed while COMPLETED"
                else -> state to "re-sent final while COMPLETED"
            }
            ServerState.ST_CONFIRMED -> when {
                isRequest && method == "INVITE" -> state to "retransmit absorbed in CONFIRMED"
                else -> ServerState.ST_TERMINATED to "Timer I fires -> TERMINATED"
            }
            else -> state to "server INVITE txn ended"
        }
        // non-INVITE server
        return when (state) {
            ServerState.ST_TRYING, ServerState.ST_PROCEEDING ->
                if (!isRequest && (isSuccess(status) || isFinalNon2xx(status)))
                    ServerState.ST_NI_COMPLETED to "final sent"
                else if (!isRequest && isProvisional(status))
                    ServerState.ST_PROCEEDING to "provisional sent"
                else state to "request received / proceeding"
            ServerState.ST_NI_COMPLETED ->
                if (isRequest) state to "retransmitted request absorbed (Timer J)"
                else ServerState.ST_TERMINATED to "Timer J fires -> TERMINATED"
            else -> state to "non-INVITE server ended"
        }
    }
}
