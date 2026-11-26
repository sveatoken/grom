// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R

/**
 * The navigation menu (D68).
 *
 * The dashboard keeps only what an operator must see without tapping: the
 * server state, Start/Stop and the four "Now serving" lamps. Every other
 * section is still built and still live, but it opens from the menu in the
 * header instead of pushing the important things down the page.
 *
 * The order here is the order on screen and it is a product decision, not a
 * refactor: the things that cost money or break silently (a tunnel, a backend,
 * the apps that are actually served) come first; maintenance, updates, the
 * invite card and finally the explanation come last. The invite card and the
 * "How Grom works" text are informational, so they sit at the very bottom of
 * the menu and the page holds nothing but the server itself (D69).
 *
 * `peripheral26` never binds a socket and has no server to lose, so it gets the
 * update entry plus the two informational ones — the invite card and the explanation exist
 * in that build too. Promising sections it does not have would be the same
 * kind of lie the "How Grom works" text exists to avoid (D62).
 *
 * Pure Kotlin, no `android.*`, no state: [MenuModelTest] can assert the order,
 * the keys and that every entry points at a real string (D30).
 */
data class MenuEntry(
    /** Stable key shared with the activity's section builders. */
    val key: String,
    val titleRes: Int,
    /** One short line saying what the section is for. */
    val subtitleRes: Int
)

object MenuModel {

    const val KEY_SSH: String = "ssh"
    const val KEY_FLEET: String = "fleet"
    const val KEY_MESH: String = "mesh"
    const val KEY_NOSTR: String = "nostr"
    const val KEY_AI: String = "ai"
    const val KEY_PROJECTS: String = "projects"
    const val KEY_ALERTS: String = "alerts"
    const val KEY_ACCESS: String = "access"
    const val KEY_RUNTIME: String = "runtime"
    const val KEY_DOCTOR: String = "doctor"
    const val KEY_UPDATE: String = "update"
    const val KEY_INVITE: String = "invite"
    const val KEY_HELP: String = "help"

    /**
     * Ordered by what breaks first if it is ignored: the link to the outside,
     * the chain settings other software reads, the AI gateway other apps spend
     * through, the apps being served, the alerts, the public surface,
     * supervision, the Doctor that explains a phone which keeps stopping the
     * server, updates, and finally the two informational entries (invite, then
     * the explanation).
     */
    fun entries(serverRole: Boolean): List<MenuEntry> {
        if (!serverRole) {
            return listOf(
                MenuEntry(KEY_UPDATE, R.string.ui_update, R.string.menu_update_sub),
                MenuEntry(KEY_INVITE, R.string.invite_label, R.string.menu_invite_sub),
                MenuEntry(KEY_HELP, R.string.ui_help, R.string.menu_help_sub)
            )
        }
        return listOf(
            MenuEntry(KEY_SSH, R.string.ui_external, R.string.menu_ssh_sub),
            MenuEntry(KEY_FLEET, R.string.ui_fleet, R.string.menu_fleet_sub),
            MenuEntry(KEY_MESH, R.string.ui_mesh, R.string.menu_mesh_sub),
            MenuEntry(KEY_NOSTR, R.string.ui_nostr_lightning, R.string.menu_nostr_sub),
            MenuEntry(KEY_AI, R.string.ui_ai, R.string.menu_ai_sub),
            MenuEntry(KEY_PROJECTS, R.string.ui_projects, R.string.menu_projects_sub),
            MenuEntry(KEY_ALERTS, R.string.ui_alerts, R.string.menu_alerts_sub),
            MenuEntry(KEY_ACCESS, R.string.ui_public_access, R.string.menu_access_sub),
            MenuEntry(KEY_RUNTIME, R.string.ui_runtime_section, R.string.menu_runtime_sub),
            MenuEntry(KEY_DOCTOR, R.string.ui_doctor, R.string.menu_doctor_sub),
            MenuEntry(KEY_UPDATE, R.string.ui_update, R.string.menu_update_sub),
            MenuEntry(KEY_INVITE, R.string.invite_label, R.string.menu_invite_sub),
            MenuEntry(KEY_HELP, R.string.ui_help, R.string.menu_help_sub)
        )
    }

    /**
     * The dialog title for a key. Deliberately independent of the role: the
     * lookup answers "what is this key called", not "may this build show it".
     */
    fun titleFor(key: String): Int? = when (key) {
        KEY_SSH -> R.string.ui_external
        KEY_FLEET -> R.string.ui_fleet
        KEY_MESH -> R.string.ui_mesh
        KEY_NOSTR -> R.string.ui_nostr_lightning
        KEY_AI -> R.string.ui_ai
        KEY_PROJECTS -> R.string.ui_projects
        KEY_ALERTS -> R.string.ui_alerts
        KEY_ACCESS -> R.string.ui_public_access
        KEY_RUNTIME -> R.string.ui_runtime_section
        KEY_DOCTOR -> R.string.ui_doctor
        KEY_UPDATE -> R.string.ui_update
        KEY_INVITE -> R.string.invite_label
        KEY_HELP -> R.string.ui_help
        else -> null
    }
}
