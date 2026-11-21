package ai.ciris.mobile.shared.ceremony

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each step's command line, pinned against the tool's README and its `--help`
 * (genesis-ceremony 0dabc27e, ~/ciris-ceremony-notes/genesis-ceremony-help.txt). Eric
 * rehearses these exact lines in a terminal; the screen must run the same ones.
 */
class CeremonyCommandsTest {
    private val d = "/media/coord/D"

    @Test fun init_is_the_readme_line() = assertEquals(
        "genesis-ceremony init --dir /media/coord/D --t0 2026-12-01T17:00:00.000Z",
        CeremonyCommands.shellLine(CeremonyCommands.init(d, "2026-12-01T17:00:00.000Z")),
    )

    /** Eric's rehearsed line, exactly (the server session, 2026-11-21). */
    @Test fun holder_prep_is_erics_rehearsed_line() = assertEquals(
        "genesis-ceremony holder-prep --dir /media/coord/D --name A1 --mldsa-seed /media/A1/mldsa65.seed --keygen " +
            "--backup-mldsa-seed /media/A2/mldsa65.seed --backup-keygen",
        CeremonyCommands.shellLine(
            CeremonyCommands.holderPrep(
                CeremonyCommands.HolderPrep(d, "A1", "/media/A1/mldsa65.seed", true, emptyList(), "/media/A2/mldsa65.seed", true),
            ),
        ),
    )

    /** The two Yubico intermediates, always, in chain order, right after the seed part. */
    @Test fun holder_prep_passes_both_yubico_intermediates_in_chain_order() = assertEquals(
        listOf(
            "holder-prep", "--dir", d, "--name", "A1", "--mldsa-seed", "/media/A1/mldsa65.seed", "--keygen",
            "--intermediate", "/rt/intermediate-1.pem", "--intermediate", "/rt/intermediate-2.pem",
            "--backup-mldsa-seed", "/media/A2/mldsa65.seed", "--backup-keygen",
        ),
        CeremonyCommands.holderPrep(
            CeremonyCommands.HolderPrep(d, "A1", "/media/A1/mldsa65.seed", true, listOf("/rt/intermediate-1.pem", "/rt/intermediate-2.pem"), "/media/A2/mldsa65.seed", true),
        ),
    )

    @Test fun holder_prep_with_an_intermediate_puts_it_after_keygen() = assertEquals(
        listOf("holder-prep", "--dir", d, "--name", "A1", "--mldsa-seed", "/s", "--keygen", "--intermediate", "ca.pem", "--backup-mldsa-seed", "/b"),
        CeremonyCommands.holderPrep(CeremonyCommands.HolderPrep(d, "A1", "/s", true, listOf("ca.pem"), "/b", false)),
    )

    @Test fun holder_prep_without_the_optional_flags() = assertEquals(
        listOf("holder-prep", "--dir", d, "--name", "B1", "--mldsa-seed", "/m/B1.seed", "--backup-mldsa-seed", "/m/B2.seed"),
        CeremonyCommands.holderPrep(CeremonyCommands.HolderPrep(d, "B1", "/m/B1.seed", false, listOf(""), "/m/B2.seed", false)),
    )

    @Test fun seat_carries_canonical_1s_three_hints_in_order() = assertEquals(
        listOf(
            "seat", "--dir", d, "--name", "canonical-1", "--seat-seed", "/m/seat.seeds", "--transport", "/m/seat.json",
            "--dial", "tcp:canonical-1.ciris-services-1.ai:4242",
            "--dial", "tcp:108.61.242.236:4242",
            "--dial", "tcp:2001:19f0:5c00:4b0c:5400:5ff:fed2:3667:4242",
        ),
        CeremonyCommands.seat(d, "canonical-1", "/m/seat.seeds", "/m/seat.json", CeremonyCommands.CANONICAL_1_DIAL + ""),
    )

    @Test fun terms_check_and_final_gate() {
        assertEquals(listOf("terms", "--dir", d), CeremonyCommands.terms(d))
        assertEquals(listOf("check", "--dir", d, "--halts", "/m/A1/sealed", "--halts", "/m/B1/sealed"), CeremonyCommands.check(d, listOf("/m/A1/sealed", " ", "/m/B1/sealed")))
        assertEquals(listOf("final-gate", "--dir", d, "--halts", "/m/A1/sealed"), CeremonyCommands.finalGate(d, listOf("/m/A1/sealed")))
    }

    @Test fun holder_sign_is_the_readme_line_and_module_only_when_given() {
        assertEquals(
            "genesis-ceremony holder-sign --dir /media/coord/D --name A1 --mldsa-seed /media/A1/mldsa65.seed --sealed-dir /media/A1/sealed",
            CeremonyCommands.shellLine(CeremonyCommands.holderSign(d, "A1", "/media/A1/mldsa65.seed", "/media/A1/sealed", "  ")),
        )
        assertEquals(
            listOf("holder-sign", "--dir", d, "--name", "A1", "--mldsa-seed", "/s", "--sealed-dir", "/h", "--module", "/usr/lib/x86_64-linux-gnu/libykcs11.so"),
            CeremonyCommands.holderSign(d, "A1", "/s", "/h", "/usr/lib/x86_64-linux-gnu/libykcs11.so"),
        )
    }

    @Test fun no_dry_run_flag_is_ever_built() {
        val all = listOf(
            CeremonyCommands.init(d, "t"), CeremonyCommands.terms(d), CeremonyCommands.check(d, listOf("h")),
            CeremonyCommands.finalGate(d, listOf("h")), CeremonyCommands.holderSign(d, "A1", "s", "h", "m"),
            CeremonyCommands.holderPrep(CeremonyCommands.HolderPrep(d, "A1", "s", true, listOf("i"), "b", true)),
            CeremonyCommands.seat(d, "c", "s", "t", listOf("tcp:h:1")),
        ).flatten()
        assertFalse(all.any { it == "--yes" || it.startsWith("--mock") || it == "--chain" })
    }

    @Test fun help_must_list_every_subcommand() {
        val help = """
            Commands:
              init             Start a ceremony directory
              holder-prep      ONE SITTING, part 1
              seat             Step 2
              terms            Fix the party set
              holder-sign      ONE SITTING, part 2
              check            The whole offer
              final-gate       The final gate
        """.trimIndent()
        assertTrue(CeremonyCommands.missingSubcommands(help).isEmpty())
        assertEquals(listOf("holder-prep", "holder-sign"), CeremonyCommands.missingSubcommands(help.lines().filterNot { "holder-" in it }.joinToString("\n")))
    }

    @Test fun the_entry_is_shown_whenever_the_tool_is_bundled_on_every_edition() {
        val fs = MemFs()
        val bundled = EchoRunner(fs, bundled = true)
        val notBundled = EchoRunner(fs, bundled = false)
        val editions = listOf(
            ai.ciris.mobile.shared.models.capability.NodeEdition.EDITION_1,
            ai.ciris.mobile.shared.models.capability.NodeEdition.EDITION_2,
            ai.ciris.mobile.shared.models.capability.NodeEdition.UNKNOWN,
            null,
        )
        editions.forEach { e ->
            assertTrue(ai.ciris.mobile.shared.ui.screens.GenesisCeremonyEntryGate.shows(bundled, e), "bundled, $e")
            assertFalse(ai.ciris.mobile.shared.ui.screens.GenesisCeremonyEntryGate.shows(notBundled, e), "not bundled, $e")
            assertFalse(ai.ciris.mobile.shared.ui.screens.GenesisCeremonyEntryGate.shows(null, e), "no runner, $e")
        }
    }

    @Test fun shell_quoting_survives_spaces_and_quotes() =
        assertEquals("'/media/A 1/it'\\''s.seed'", CeremonyCommands.shellQuote("/media/A 1/it's.seed"))
}
