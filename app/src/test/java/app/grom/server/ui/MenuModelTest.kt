// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The header menu (D68).
 *
 * The dashboard keeps Start/Stop, the state headline and the "Now serving"
 * lamps; everything else opens from the menu, including the Doctor that explains
 * a phone which keeps stopping the server (D80). These tests pin the order (a
 * product decision, not a refactor), make sure a section can never be listed
 * twice, and — like [HelpModelTest] — check that every entry points at strings
 * that really exist.
 */
class MenuModelTest {

    @Test
    fun `the server menu lists the sections by importance`() {
        assertEquals(
            listOf(
                MenuModel.KEY_SSH,
                MenuModel.KEY_FLEET,
                MenuModel.KEY_NOSTR,
                MenuModel.KEY_AI,
                MenuModel.KEY_PROJECTS,
                MenuModel.KEY_ALERTS,
                MenuModel.KEY_ACCESS,
                MenuModel.KEY_RUNTIME,
                MenuModel.KEY_DOCTOR,
                MenuModel.KEY_UPDATE,
                MenuModel.KEY_INVITE,
                MenuModel.KEY_HELP
            ),
            MenuModel.entries(serverRole = true).map { it.key }
        )
    }

    @Test
    fun `a role without a server only offers update, invite and help`() {
        // peripheral26 never binds a socket and has no tunnel, projects or
        // alerts; listing them would open empty dialogs and promise features
        // that this build does not have. The invite card and the explanation do
        // exist there, so they stay (D69).
        assertEquals(
            listOf(MenuModel.KEY_UPDATE, MenuModel.KEY_INVITE, MenuModel.KEY_HELP),
            MenuModel.entries(serverRole = false).map { it.key }
        )
        val full = MenuModel.entries(serverRole = true)
        assertEquals(full.takeLast(3), MenuModel.entries(serverRole = false))
    }

    @Test
    fun `every entry is unique and points at a title`() {
        val entries = MenuModel.entries(serverRole = true)
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        assertEquals(entries.size, entries.map { it.subtitleRes }.toSet().size)
        for (entry in entries) {
            assertTrue(entry.titleRes != 0)
            assertTrue(entry.subtitleRes != 0)
            assertEquals(entry.titleRes, MenuModel.titleFor(entry.key))
        }
    }

    @Test
    fun `an unknown key has no title`() {
        assertNull(MenuModel.titleFor("nope"))
        assertNull(MenuModel.titleFor(""))
        assertNull(MenuModel.titleFor("SSH"))
    }

    @Test
    fun `every menu string exists`() {
        val xml = stringsXml()
        assumeTrue("strings.xml not reachable from this working dir", xml != null)
        val names = Regex("""<string name="([A-Za-z0-9_]+)"""")
            .findAll(xml!!)
            .map { it.groupValues[1] }
            .toSet()
        listOf(
            "ui_menu", "ui_menu_sections", "ui_close",
            "menu_ssh_sub", "menu_fleet_sub", "menu_nostr_sub", "menu_projects_sub",
            "menu_alerts_sub", "menu_access_sub", "menu_runtime_sub",
            "menu_doctor_sub", "menu_update_sub", "menu_invite_sub", "menu_help_sub"
        ).forEach { name ->
            assertTrue("missing string: $name", names.contains(name))
        }
    }

    @Test
    fun `the section titles are the same strings the menu titles use`() {
        // The menu is the only way in now, so a renamed section cannot keep an
        // old title in one of the two places.
        assertEquals(R.string.ui_external, MenuModel.titleFor(MenuModel.KEY_SSH))
        assertEquals(R.string.ui_fleet, MenuModel.titleFor(MenuModel.KEY_FLEET))
        assertEquals(R.string.ui_nostr_lightning, MenuModel.titleFor(MenuModel.KEY_NOSTR))
        assertEquals(R.string.ui_projects, MenuModel.titleFor(MenuModel.KEY_PROJECTS))
        assertEquals(R.string.ui_alerts, MenuModel.titleFor(MenuModel.KEY_ALERTS))
        assertEquals(R.string.ui_public_access, MenuModel.titleFor(MenuModel.KEY_ACCESS))
        assertEquals(R.string.ui_runtime_section, MenuModel.titleFor(MenuModel.KEY_RUNTIME))
        assertEquals(R.string.ui_doctor, MenuModel.titleFor(MenuModel.KEY_DOCTOR))
        assertEquals(R.string.ui_update, MenuModel.titleFor(MenuModel.KEY_UPDATE))
        assertEquals(R.string.invite_label, MenuModel.titleFor(MenuModel.KEY_INVITE))
        assertEquals(R.string.ui_help, MenuModel.titleFor(MenuModel.KEY_HELP))
    }

    private fun stringsXml(): String? {
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }
}
