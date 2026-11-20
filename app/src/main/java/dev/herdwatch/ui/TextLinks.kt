package dev.herdwatch.ui

/**
 * URLs that a screen or a message is pointing at.
 *
 * A terminal is often the only place a link appears (a review URL, a CI log, a bug id's page), and
 * retyping it on a phone is not something anyone does. Detection stays conservative: only explicit
 * http(s) URLs, and trailing punctuation that belongs to the sentence rather than the address is
 * dropped so tapping does not open `…/issues/12).`
 */
private val URL_PATTERN = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

/** Sentence punctuation that is a full stop, not part of the address. */
private const val TRAILING = ".,;:!?'\"»”’"

/** At most [limit] distinct links, in the order they appear. */
internal fun urlsIn(text: String, limit: Int = 3): List<String> {
    if (text.isEmpty()) return emptyList()
    val found = LinkedHashSet<String>()
    for (match in URL_PATTERN.findAll(text)) {
        var value = match.value.trimEnd { it in TRAILING }
        // A closing bracket that never opened inside the address ends the sentence, but a balanced
        // pair is part of it: `…/wiki/Foo_(bar)` is a real page, `…/issues/12).` is not.
        value = trimUnbalancedClosers(value)
        if (value.length <= "https://".length + 2) continue
        found.add(value)
        if (found.size >= limit) break
    }
    return found.toList()
}

/** Drops trailing `)`, `]`, `}` only while they have no matching opener left in the address. */
private fun trimUnbalancedClosers(url: String): String {
    var value = url
    while (value.isNotEmpty() && value.last() in ")]}") {
        val closer = value.last()
        val opener = when (closer) {
            ')' -> '('
            ']' -> '['
            else -> '{'
        }
        val body = value.dropLast(1)
        if (body.count { it == opener } >= body.count { it == closer } + 1) break
        value = body.trimEnd { it in TRAILING }
    }
    return value
}

/**
 * Opens a detected link in whatever the phone uses for browsing. A link that cannot be opened must
 * not take the page down with it, so the launch failure is swallowed.
 */
internal fun openLink(context: android.content.Context, url: String) {
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
    runCatching { context.startActivity(view) }
}
