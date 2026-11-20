package dev.herdwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLinksTest {

    @Test
    fun findsHttpLinksInScreenText() {
        val text = "review: https://github.com/acme/api/pull/42\nlog: http://ci.local/job/7"
        assertEquals(
            listOf("https://github.com/acme/api/pull/42", "http://ci.local/job/7"),
            urlsIn(text),
        )
    }

    @Test
    fun dropsSentencePunctuationButKeepsTheAddress() {
        val text = "see (https://example.com/a?b=1&c=2). and https://example.com/x,"
        assertEquals(listOf("https://example.com/a?b=1&c=2", "https://example.com/x"), urlsIn(text))
    }

    @Test
    fun ignoresTextThatIsNotAUrl() {
        assertTrue(urlsIn("no links here, just /home/dev/x and git@host:x.git").isEmpty())
    }

    @Test
    fun keepsTheFirstFewOnlyAndDoesNotDuplicate() {
        val text = (1..6).joinToString(" ") { "https://e.com/$it" } + " https://e.com/1"
        val links = urlsIn(text, limit = 3)
        assertEquals(listOf("https://e.com/1", "https://e.com/2", "https://e.com/3"), links)
    }

    @Test
    fun keepsBalancedBracketsInsideTheAddress() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            urlsIn("read https://en.wikipedia.org/wiki/Foo_(bar) now"),
        )
        assertEquals(
            listOf("https://example.com/a[1]"),
            urlsIn("see https://example.com/a[1]."),
        )
    }
}
