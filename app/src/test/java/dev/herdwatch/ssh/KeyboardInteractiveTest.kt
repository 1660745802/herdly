package dev.herdwatch.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardened sshd setups offer `keyboard-interactive` instead of `password`. Answering those prompts
 * with the stored password is what makes them usable; answering a one-time-code prompt with it
 * would burn an attempt and look like a wrong password.
 */
class KeyboardInteractiveTest {

    @Test
    fun aHiddenPasswordPromptIsAnsweredWithTheSecret() {
        val answers = keyboardInteractiveAnswers(listOf("Password: "), listOf(false), "s3cret")
        assertEquals(listOf("s3cret"), answers)
    }

    @Test
    fun severalHiddenPromptsAllGetTheSecret() {
        val answers = keyboardInteractiveAnswers(
            listOf("Password: ", "Password: "),
            listOf(false, false),
            "s3cret",
        )
        assertEquals(listOf("s3cret", "s3cret"), answers)
    }

    @Test
    fun aVisiblePromptIsNotAnsweredWithThePassword() {
        // A visible prompt is a username or a selector; the secret must not land there.
        assertNull(keyboardInteractiveAnswers(listOf("Login: "), listOf(true), "s3cret"))
        assertNull(keyboardInteractiveAnswers(listOf("Password: ", "Domain: "), listOf(false, true), "s3cret"))
    }

    @Test
    fun oneTimeCodePromptsAreRefused() {
        listOf(
            "Verification code: ",
            "One-time password: ",
            "Enter passcode: ",
            "Authenticator code: ",
            "Duo push approval: ",
            "请输入动态口令: ",
            "请输入验证码: ",
        ).forEach { prompt ->
            assertNull(prompt, keyboardInteractiveAnswers(listOf(prompt), listOf(false), "s3cret"))
        }
    }

    @Test
    fun theHintMatcherIsCaseInsensitive() {
        assertTrue(looksLikeOneTimeCode("VERIFICATION CODE"))
        assertFalse(looksLikeOneTimeCode("Password"))
    }

    @Test
    fun anEmptyRoundAsksNothing() {
        assertEquals(emptyList<String>(), keyboardInteractiveAnswers(emptyList(), emptyList(), "s3cret"))
    }

    @Test
    fun aVisiblePromptIsRefusedAsNotAPasswordNotAsAOneTimeCode() {
        assertEquals(
            ChallengeRefusal.NOT_A_PASSWORD,
            keyboardInteractiveRefusal(listOf("Password: "), listOf(true)),
        )
        assertEquals(
            ChallengeRefusal.ONE_TIME_CODE,
            keyboardInteractiveRefusal(listOf("Verification code: "), listOf(false)),
        )
        assertNull(keyboardInteractiveRefusal(listOf("Password: "), listOf(false)))
    }

    @Test
    fun ordinaryWordsAreNotOneTimeCodeRequests() {
        assertFalse(looksLikeOneTimeCode("Allow pinning to this host?"))
        assertFalse(looksLikeOneTimeCode("ship it?"))
        assertFalse(looksLikeOneTimeCode("tokenize the log"))
        assertTrue(looksLikeOneTimeCode("PIN:"))
        assertTrue(looksLikeOneTimeCode("Duo push approval"))
    }
}
