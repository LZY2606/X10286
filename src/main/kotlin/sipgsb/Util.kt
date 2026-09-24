package sipgsb

import java.security.MessageDigest

fun sha1(s: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun esc(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '&' -> append("&amp;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}
