// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.session.ClientSession

/**
 * Answers the one keyboard-interactive prompt a password login actually is
 * (D105).
 *
 * OpenSSH with `UsePAM yes` — the default on every Debian and Ubuntu VPS —
 * advertises `keyboard-interactive` alongside `password`, and a great many
 * hosts accept the password only through it. The `password` method then fails
 * with "exhausted available authentication methods" while the password is
 * perfectly correct, which is indistinguishable from a wrong password.
 *
 * The handler is deliberately narrow:
 *
 *  - it answers only a **single, non-echoing** prompt — the shape of a password
 *    question — and returns `null` (declining) for anything else, so a second
 *    factor or a multi-question challenge falls through to the next method
 *    instead of being answered with the password;
 *  - it only ever returns the credential the operator stored for this target
 *    and never reads or logs what the server asked for;
 *  - `getUpdatedPassword` is `null`: Grom has no password-change flow, and
 *    silently accepting one would change the operator's VPS behind their back.
 */
internal class SshPasswordInteraction(private val password: String) : UserInteraction {

    override fun isInteractionAllowed(session: ClientSession?): Boolean = true

    override fun interactive(
        session: ClientSession?,
        name: String?,
        instruction: String?,
        lang: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?
    ): Array<String>? {
        if (prompt == null || echo == null) return null
        if (prompt.size != 1 || echo.size != 1) return null
        if (echo[0]) return null
        return arrayOf(password)
    }

    override fun getUpdatedPassword(
        session: ClientSession?,
        prompt: String?,
        lang: String?
    ): String? = null
}
