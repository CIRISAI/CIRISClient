package ai.ciris.mobile.shared.ceremony

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CeremonyDesktopTest {
    private fun tmp(): File = Files.createTempDirectory("ceremony-test").toFile().apply { deleteOnExit() }

    // ── Verify before run ────────────────────────────────────────────────────

    @Test fun a_copy_with_the_wrong_hash_is_refused_and_nothing_is_left_to_run() {
        val dir = tmp()
        val bytes = "#!/bin/sh\necho not the tool\n".toByteArray()
        val v = CeremonyToolBundle.verifiedCopy(bytes.inputStream(), "00".repeat(32), dir)
        assertIs<CeremonyToolBundle.Verified.Mismatch>(v)
        assertFalse(File(dir, CeremonyToolBundle.FILE_NAME).exists(), "no runnable copy")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "no partial file either")
    }

    @Test fun a_copy_with_the_built_hash_runs_and_is_owner_only() {
        val dir = tmp()
        val bytes = "#!/bin/sh\necho ok\n".toByteArray()
        val f = File.createTempFile("src", "", dir).apply { writeBytes(bytes) }
        val sha = CeremonyToolBundle.sha256(f)
        f.delete()
        val v = CeremonyToolBundle.verifiedCopy(bytes.inputStream(), sha, dir)
        assertIs<CeremonyToolBundle.Verified.Ok>(v)
        val perms = Files.getPosixFilePermissions(v.file.toPath()).map { it.name }.toSet()
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE"), perms)
    }

    @Test fun every_run_is_verified_a_bad_copy_runs_nothing() = runBlocking {
        val r = DesktopCeremonyRunner(tool = { CeremonyToolBundle.Verified.Mismatch("aa", "bb") }, osName = "Linux")
        val events = r.run(CeremonyStep.TERMS, CeremonyCommands.terms("/nowhere")).events.toList()
        assertTrue(events.any { it is CeremonyEvent.Output && "sha256" in it.text })
        assertEquals(CeremonyEvent.Exited(126), events.last())
    }

    /** With a bundled tool (-PceremonyBinary), the real one: extracted, verified, and its --help lists every step. */
    @Test fun the_bundled_tool_prepares_when_this_build_carries_one() = runBlocking {
        if (CeremonyToolBuild.SHA256.isEmpty() || !System.getProperty("os.name").startsWith("Linux")) return@runBlocking
        val state = DesktopCeremonyRunner().prepare()
        assertIs<CeremonyToolState.Ready>(state, state.toString())
        assertEquals(CeremonyToolBuild.SHA256, state.info.sha256)
    }

    // ── The status, from a real directory ───────────────────────────────────

    @Test fun the_status_reads_the_tools_layout() {
        val d = tmp()
        File(d, "ceremony.json").writeText("""{"family_key_id":"humanity-accord","t0_ms":1796144400000,"scope":["infra:serve"],"halt_fuse_secs":86400}""")
        File(d, "holders/A1").mkdirs()
        listOf("public.hex", "row_id.hex", "recovery.json", "record.cose").forEach { File(d, "holders/A1/$it").writeText("x") }
        File(d, "holders/A1/serial.txt").writeText("31415926\n")
        File(d, "holders/B1").mkdirs(); File(d, "holders/B1/public.hex").writeText("x"); File(d, "holders/B1/row_id.hex").writeText("x")
        File(d, "seats/canonical-1").mkdirs(); File(d, "seats/canonical-1/record.cose").writeText("x"); File(d, "seats/canonical-1/binding.cose").writeText("x")
        File(d, "voices").mkdirs(); File(d, "voices/A1.cose").writeText("x")
        File(d, "terms.json").writeText("""{"bundle_object_id":"${"ab".repeat(32)}","holders":[{"name":"A1"},{"name":"B1"}]}""")
        val s = CeremonyStatus.read(JvmCeremonyFs, d.path)
        assertTrue(s.initialised)
        assertEquals("humanity-accord", s.familyKeyId)
        assertEquals(1796144400000, s.t0Ms)
        assertEquals(listOf("A1", "B1"), s.holders.map { it.name })
        assertTrue(s.holders[0].signed && s.holders[0].serial == "31415926")
        assertTrue(s.holders[1].prepared && !s.holders[1].signed)
        assertEquals(listOf(CeremonyStatus.Seat("canonical-1", true)), s.seats)
        assertTrue(s.termsFixed && s.bundleObjectId == "ab".repeat(32) && s.voices == 1)
        assertFalse(CeremonyStatus.read(JvmCeremonyFs, File(d, "nope").path).initialised)
    }

    @Test fun the_log_file_is_appended_not_truncated() {
        val d = tmp()
        val log = File(d, CeremonyStepRecord.LOG_FILE).apply { writeText("{\"first\":1}\n") }
        JvmCeremonyFs.appendLine(log.path, "{\"second\":2}")
        assertEquals(listOf("{\"first\":1}", "{\"second\":2}"), log.readLines())
    }

    // ── The PIN through a real terminal ──────────────────────────────────────

    /**
     * A stand-in tool, under the same `script` terminal the real one runs in: it asks for
     * the PIN with echo off, as rpassword does, then for `yes`. The PIN must reach it and
     * be in neither the transcript nor the log.
     */
    @Test fun the_pin_reaches_the_tool_through_the_terminal_and_nowhere_else() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Linux") || !File("/usr/bin/script").exists()) return@runBlocking
        val d = tmp()
        val fake = File(d, "fake-tool.sh").apply {
            writeText(
                """
                |#!/bin/sh
                |printf 'YubiKey PIV PIN (slot 9c): '
                |stty -echo; read -r p; stty echo; echo
                |echo "PIN accepted. length ${'$'}{#p}"
                |printf 'Type yes to sign (do NOT touch the YubiKey yet): '
                |read -r a
                |echo ">>> Touch the YubiKey now (1 of 3). <<<"
                |[ "${'$'}a" = yes ] && [ "${'$'}p" = 864209 ] && exit 0
                |exit 9
                """.trimMargin() + "\n",
            )
            setExecutable(true)
        }
        val run = PtyRun(DesktopCeremonyRunner.scriptArgv(CeremonyCommands.shellQuote(fake.absolutePath)))
        val seen = StringBuilder()
        var code = -1
        withTimeout(20_000) {
            run.events.collect { e ->
                when (e) {
                    is CeremonyEvent.Output -> seen.append(e.text)
                    is CeremonyEvent.Prompt -> when (e.prompt) {
                        CeremonyOutput.Prompt.PIN -> assertTrue(run.pin("864209".toCharArray()))
                        CeremonyOutput.Prompt.CONFIRM -> assertTrue(run.answer(CeremonyAnswer.YES))
                        else -> Unit
                    }
                    is CeremonyEvent.Exited -> code = e.code
                    else -> Unit
                }
            }
        }
        assertEquals(0, code, seen.toString())
        assertTrue("PIN accepted. length 6" in seen.toString())
        assertFalse("864209" in seen.toString(), "the PIN was not echoed into the transcript")
        assertTrue(">>> Touch the YubiKey now (1 of 3). <<<" in seen.toString())
    }
}
