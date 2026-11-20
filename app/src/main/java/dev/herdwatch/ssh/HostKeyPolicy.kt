package dev.herdwatch.ssh

/**
 * Host-key algorithm preference shared by the bridge client and the interactive shell.
 *
 * JSch's built-in order starts with the ECDSA NIST curves, while OpenSSH (and therefore
 * `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`, which is what an administrator reads out)
 * prefers Ed25519. Without this list the app asked the user to confirm a fingerprint that did not
 * match anything the administrator could produce, which makes the confirmation meaningless.
 *
 * Ed25519 first, then the RSA SHA-2 signatures, then the NIST curves. SHA-1 `ssh-rsa` and `ssh-dss`
 * are deliberately absent: a fingerprint the user is asked to verify must not rest on a broken hash.
 */
internal const val HOST_KEY_ALGORITHMS: String =
    "ssh-ed25519,rsa-sha2-512,rsa-sha2-256," +
        "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521"

/**
 * True when the attempt never got an answer from the address: refused, unreachable, timed out or
 * the name did not resolve.
 *
 * JSch wraps socket errors in `JSchException`, so the type of the outer exception says nothing. A
 * rejected key or a refused password is *not* a transport failure — retrying those on another
 * address would double the attempts and hide the real cause. This walks the cause chain instead.
 */
internal fun Throwable.isUnreachableAddress(): Boolean {
    var current: Throwable? = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is java.net.ConnectException ||
            current is java.net.NoRouteToHostException ||
            current is java.net.UnknownHostException ||
            current is java.net.SocketTimeoutException ||
            current is java.net.PortUnreachableException
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * A rejected credential, found in the transport message rather than in the sentence shown to the
 * user: the display copy can be reworded, the retry decision must not change with it.
 */
internal fun Throwable.isAuthRejection(): Boolean {
    var current: Throwable? = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        val lower = current.message.orEmpty().lowercase()
        if (lower.contains("auth fail") || lower.contains("auth cancel") || lower.contains("userauth")) return true
        current = current.cause
    }
    return false
}

/** The blob's first length-prefixed field is the algorithm name ("ssh-ed25519", …). */
internal fun keyAlgorithm(key: ByteArray): String? = runCatching {
    val len = ((key[0].toInt() and 0xff) shl 24) or ((key[1].toInt() and 0xff) shl 16) or
        ((key[2].toInt() and 0xff) shl 8) or (key[3].toInt() and 0xff)
    String(key, 4, len, Charsets.US_ASCII)
}.getOrNull()

/**
 * Human-readable key type for the fingerprint dialog, e.g. `ED25519` or `ECDSA`.
 *
 * OpenSSH prints the same word in `ssh-keygen -l` output, so showing it lets the user compare
 * like with like instead of guessing which of a host's several keys they are looking at.
 */
internal fun hostKeyLabel(type: String?): String = when {
    type == null -> ""
    type == "ssh-ed25519" -> "ED25519"
    type.startsWith("ecdsa-sha2-") -> "ECDSA"
    type.startsWith("rsa-sha2-") || type == "ssh-rsa" -> "RSA"
    type == "ssh-dss" -> "DSA"
    else -> type.uppercase()
}
