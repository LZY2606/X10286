package sipreview.sip

import java.util.Base64 as JBase64

object Base64 {
    fun encode(bytes: ByteArray): String = JBase64.getEncoder().encodeToString(bytes)
    fun decode(s: String): ByteArray = JBase64.getDecoder().decode(s)
}
