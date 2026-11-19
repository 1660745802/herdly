package dev.herdwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptTest {

    private val realisticSnapshot = """
        ────────────────────────────────────────────────
        > How do I fix the failing build?

        ● The build fails because the lockfile is stale.
          I will regenerate it now.

        ⎿ Bash(./gradlew build --refresh-dependencies)
          BUILD SUCCESSFUL in 12s
        ────────────────────────────────────────────────
        ~/Documents/herdr-mobile
        ↑478k ↓141k R5.8M CH99.9% ${'$'}0.301 17.0%/1.0M
        deepseek/deepseek-flash • high
        ctrl+o to expand
        >
    """.trimIndent()

    @Test
    fun `realistic pi snapshot parses into user assistant tool in order`() {
        val t = AgentTranscript.parse(realisticSnapshot)
        assertEquals(
            listOf(TranscriptRole.USER, TranscriptRole.ASSISTANT, TranscriptRole.TOOL),
            t.blocks.map { it.role },
        )
        assertTrue(t.reliable)
        assertTrue(t.coverage > 0.6f)
        assertTrue(t.blocks[0].text.contains("How do I fix the failing build?"))
        assertTrue(t.blocks[1].text.contains("lockfile is stale"))
        assertTrue(t.blocks[2].text.contains("BUILD SUCCESSFUL"))
        // Footer chrome must not leak into any block.
        for (b in t.blocks) {
            assertFalse(b.text.contains("478k"))
            assertFalse(b.text.contains("deepseek-flash"))
            assertFalse(b.text.contains("to expand"))
            assertFalse(b.text.contains("─"))
        }
    }

    @Test
    fun `wrapped prose joins into one block`() {
        val t = AgentTranscript.parse(
            """
            ● This is a long answer
            that wraps across lines
            and keeps going.
            """.trimIndent(),
        )
        assertEquals(1, t.blocks.size)
        assertEquals(TranscriptRole.ASSISTANT, t.blocks[0].role)
        assertTrue(t.blocks[0].text.contains("long answer that wraps across lines and keeps going."))
    }

    @Test
    fun `approval menu with three options becomes one approval block`() {
        val t = AgentTranscript.parse(
            """
            ● I need to run npm test.

            Do you want to proceed?
            ❯ 1. Yes
              2. Yes, and don't ask again
              3. No
            """.trimIndent(),
        )
        assertEquals(listOf(TranscriptRole.ASSISTANT, TranscriptRole.APPROVAL), t.blocks.map { it.role })
        val approval = t.blocks[1].text
        assertTrue(approval.contains("Do you want to proceed?"))
        assertTrue(approval.contains("1. Yes"))
        assertTrue(approval.contains("2. Yes, and don't ask again"))
        assertTrue(approval.contains("3. No"))
        // The question was moved out of the assistant block, not duplicated.
        assertFalse(t.blocks[0].text.contains("Do you want to proceed?"))
    }

    @Test
    fun `yes-no prompt becomes approval`() {
        val t = AgentTranscript.parse(
            """
            ● Ready to apply the patch.
            Apply changes? (y/n)
            """.trimIndent(),
        )
        assertEquals(listOf(TranscriptRole.ASSISTANT, TranscriptRole.APPROVAL), t.blocks.map { it.role })
        assertTrue(t.blocks[1].text.contains("(y/n)"))

        val bracket = AgentTranscript.parse("Overwrite file? [y/N]")
        assertEquals(TranscriptRole.APPROVAL, bracket.blocks[0].role)
    }

    @Test
    fun `pure chrome only input yields no blocks and is unreliable`() {
        val t = AgentTranscript.parse("╭──────╮\n│      │\n╰──────╯\n⠋\n───\n...\n│\n")
        assertTrue(t.blocks.isEmpty())
        assertFalse(t.reliable)
        assertEquals(0f, t.coverage, 0f)
    }

    @Test
    fun `shell session without agent markers is unreliable`() {
        val t = AgentTranscript.parse(
            """
            ${'$'} ls -la
            total 48
            drwxr-xr-x  5 user user 4096 Nov 19 10:00 .
            -rw-r--r--  1 user user  220 Nov 19 10:00 build.gradle
            ${'$'} pwd
            /home/user/repo
            """.trimIndent(),
        )
        assertFalse(t.reliable)
        assertTrue(t.blocks.isNotEmpty())
        assertTrue(t.blocks.all { it.role == TranscriptRole.TOOL || it.role == TranscriptRole.SYSTEM })
    }

    @Test
    fun `truncated head is detected on first block only`() {
        val t = AgentTranscript.parse(
            """
            and then we fixed the issue by updating the lockfile.

            > thanks, that worked
            """.trimIndent(),
        )
        assertEquals(2, t.blocks.size)
        assertTrue(t.blocks[0].truncatedHead)
        assertFalse(t.blocks[1].truncatedHead)

        // Tool output whose command was cut off above the snapshot.
        val tool = AgentTranscript.parse("⎿ Done\n● All good\n> nice")
        assertTrue(tool.blocks[0].truncatedHead)

        // An explicit user marker is a clean turn start even when lowercase.
        val clean = AgentTranscript.parse("> hello there\n● hi")
        assertFalse(clean.blocks[0].truncatedHead)
    }

    @Test
    fun `footer lines are dropped but sentences with dollar or path survive`() {
        val t = AgentTranscript.parse(
            """
            > what did it cost?
            ● The run cost ${'$'}0.301 in total.
              See /repo/src/main.kt for details.
            ────────────────────────────────────────────────
            ~/Documents/herdr-mobile
            ↑478k ↓141k R5.8M CH99.9% ${'$'}0.301 17.0%/1.0M
            deepseek/deepseek-flash • high
            ? for shortcuts
            >
            """.trimIndent(),
        )
        val assistant = t.blocks.first { it.role == TranscriptRole.ASSISTANT }
        assertTrue(assistant.text.contains("${'$'}0.301 in total"))
        assertTrue(assistant.text.contains("/repo/src/main.kt"))
        assertEquals(2, t.blocks.size)
        for (b in t.blocks) {
            assertFalse(b.text.contains("478k"))
            assertFalse(b.text.contains("deepseek-flash"))
            assertFalse(b.text.contains("shortcuts"))
        }
    }

    @Test
    fun `crlf tabs and ansi leftovers are normalised`() {
        val input = "\u001B[32m> hi there\u001B[0m\r\n\u001B[36m●\u001B[0m hello\r\n\twrapped bit\r\n"
        val t = AgentTranscript.parse(input)
        assertEquals(listOf(TranscriptRole.USER, TranscriptRole.ASSISTANT), t.blocks.map { it.role })
        assertTrue(t.blocks[1].text.contains("hello wrapped bit"))
        for (b in t.blocks) {
            assertFalse(b.text.contains("\u001B"))
            assertFalse(b.text.contains("\t"))
            assertFalse(b.text.contains("\r"))
            assertTrue(input.contains(b.raw))
        }
    }

    @Test
    fun `re-parsing joined block text keeps the same roles`() {
        val input = """
            > first question
            ● first answer
            ⎿ tool output line
            > second question
            ● second answer
        """.trimIndent()
        val first = AgentTranscript.parse(input)
        val joined = first.blocks.joinToString("\n\n") { it.text }
        val second = AgentTranscript.parse(joined)
        assertEquals(first.blocks.map { it.role }, second.blocks.map { it.role })
        assertEquals(first.blocks.map { it.text }, second.blocks.map { it.text })
    }

    @Test
    fun `raw slices are verbatim substrings of the input`() {
        val t = AgentTranscript.parse(realisticSnapshot)
        assertTrue(t.blocks.isNotEmpty())
        for (b in t.blocks) {
            assertTrue("raw not a substring: ${b.raw}", realisticSnapshot.contains(b.raw))
            assertTrue(b.raw.isNotEmpty())
        }
    }

    @Test
    fun `empty input yields empty unreliable transcript`() {
        val t = AgentTranscript.parse("")
        assertTrue(t.blocks.isEmpty())
        assertEquals(0f, t.coverage, 0f)
        assertFalse(t.reliable)
    }

    @Test
    fun `whitespace only input yields empty unreliable transcript`() {
        val t = AgentTranscript.parse("   \n\t\n  \n\n\n")
        assertTrue(t.blocks.isEmpty())
        assertEquals(0f, t.coverage, 0f)
        assertFalse(t.reliable)
    }

    @Test
    fun `coverage maths on a known fixture`() {
        // 5 non-empty non-chrome lines: user, assistant x2, cwd, token counter.
        // The two footer lines are dropped, 3 lines are consumed -> 3/5 = 0.6.
        val t = AgentTranscript.parse(
            """
            > hello there
            ● answer line one
              answer line two
            ──────────────
            ~/repo
            ↑1k ↓2k ${'$'}0.01 5%
            """.trimIndent(),
        )
        assertEquals(0.6f, t.coverage, 0f)
        assertEquals(2, t.blocks.size)
        assertTrue(t.reliable)
    }

    @Test
    fun `blank line does not end a block before an indented continuation`() {
        val t = AgentTranscript.parse(
            """
            ● Answer part one

              continued after the blank
            """.trimIndent(),
        )
        assertEquals(1, t.blocks.size)
        assertTrue(t.blocks[0].text.contains("Answer part one continued after the blank"))
    }

    @Test
    fun `user marker variants all parse as user`() {
        val t = AgentTranscript.parse("You: one\n❯ two\nuser: three")
        assertEquals(3, t.blocks.size)
        assertTrue(t.blocks.all { it.role == TranscriptRole.USER })
        assertTrue(t.reliable) // 3 USER blocks, coverage 1
    }

    @Test
    fun `tool marker variants all parse as tool`() {
        val t = AgentTranscript.parse(
            """
            Bash(ls)
            Read(a.txt)
            Edit(a.txt)
            Write(b.txt)
            Running tests
            Took 0.3s
            ... (12 earlier lines)
            """.trimIndent(),
        )
        assertEquals(7, t.blocks.size)
        assertTrue(t.blocks.all { it.role == TranscriptRole.TOOL })
        assertFalse(t.reliable) // no USER/ASSISTANT
    }

    @Test
    fun `welcome and tip banners parse as system`() {
        val t = AgentTranscript.parse(
            """
            Welcome to pi v0.5.1
            Tip: use /help for commands

            > hello
            ● hi there
            """.trimIndent(),
        )
        assertEquals(
            listOf(TranscriptRole.SYSTEM, TranscriptRole.USER, TranscriptRole.ASSISTANT),
            t.blocks.map { it.role },
        )
        assertTrue(t.blocks[0].text.contains("Welcome to pi"))
        assertTrue(t.blocks[0].text.contains("Tip:"))
    }

    @Test
    fun `numbered prose list is not mistaken for an approval menu`() {
        val t = AgentTranscript.parse(
            """
            ● Here is the plan:
            1. Refactor the parser
            2. Add more tests
            3. Ship it
            """.trimIndent(),
        )
        assertEquals(1, t.blocks.size)
        assertEquals(TranscriptRole.ASSISTANT, t.blocks[0].role)
        assertTrue(t.blocks[0].text.contains("1. Refactor the parser"))
        assertTrue(t.blocks[0].text.contains("3. Ship it"))
    }

    @Test
    fun `chrome wrapped markers are recognised and pipes removed from text`() {
        val t = AgentTranscript.parse(
            """
            ╭──────────────────────────╮
            │ > fix the bug            │
            │ ● done, fixed it         │
            ╰──────────────────────────╯
            """.trimIndent(),
        )
        assertEquals(listOf(TranscriptRole.USER, TranscriptRole.ASSISTANT), t.blocks.map { it.role })
        assertTrue(t.reliable)
        for (b in t.blocks) {
            assertFalse(b.text.contains("│"))
            assertFalse(b.text.endsWith(" "))
        }
        assertEquals("> fix the bug", t.blocks[0].text)
        assertEquals("● done, fixed it", t.blocks[1].text)
    }

    @Test
    fun `quoted table rows are not labelled as the user`() {
        val text = """
            ● 判读：
            > 405 px/s │ 120Hz
            > 105 ~ 405 │ 60Hz
            > 45 ~ 105 │ 40Hz
        """.trimIndent()
        val t = AgentTranscript.parse(text)
        assertTrue(t.blocks.none { it.role == TranscriptRole.USER })
    }

    @Test
    fun `a short prompt line is still the user`() {
        val t = AgentTranscript.parse("> 帮我看看这个抖动\n● 好的，我先抓日志。")
        assertEquals(TranscriptRole.USER, t.blocks.first().role)
    }


    @Test
    fun `bare fence closes tool block and preserves following turns`() {
        val text = listOf(
            "● here is the plan",
            "```bash",
            "# A. pin 120Hz",
            "adb shell setprop x false",
            "---",
            "> quoted inside code",
            "1. not a menu",
            "```",
            "● done",
            "> next request",
        ).joinToString("\n")
        val t = AgentTranscript.parse(text)
        assertEquals(
            listOf(
                TranscriptRole.ASSISTANT,
                TranscriptRole.TOOL,
                TranscriptRole.ASSISTANT,
                TranscriptRole.USER,
            ),
            t.blocks.map { it.role },
        )
        val code = t.blocks.single { it.role == TranscriptRole.TOOL }
        assertTrue(code.text.contains("adb shell setprop x false"))
        assertTrue(code.text.contains("---"))
        assertTrue(code.text.endsWith("```"))
        assertEquals("● done", t.blocks[2].text)
        assertEquals("> next request", t.blocks[3].text)
        assertTrue(t.blocks.none { it.role == TranscriptRole.APPROVAL })
    }

}
