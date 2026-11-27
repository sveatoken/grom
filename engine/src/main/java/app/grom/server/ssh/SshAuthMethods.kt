// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import org.apache.sshd.client.future.AuthFuture
import org.apache.sshd.client.session.ClientUserAuthService
import org.apache.sshd.client.session.ClientUserAuthServiceFactory
import org.apache.sshd.common.Service
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.buffer.Buffer

/**
 * Remembers which authentication methods the server said it would accept (D110).
 *
 * This exists to answer one question honestly: *why* was a correct password
 * rejected? The answer is usually not "wrong password" but "this server does not
 * accept passwords for this user" — every Hetzner, Debian and Ubuntu cloud image
 * ships `PermitRootLogin prohibit-password`, and many add
 * `PasswordAuthentication no`. The server says so in its
 * `SSH_MSG_USERAUTH_FAILURE` reply, but the driver only puts that list in a
 * debug log and throws `SshException("No more authentication methods
 * available")`, so the list never reaches the caller.
 *
 * The list is captured by observing the reply, not by guessing from a failure:
 *
 *  - it is empty when no failure reply was received (a refused TCP connection, a
 *    handshake error, or a server that closed the session), and the caller must
 *    then fall back to the generic auth diagnosis — never to "methods are off";
 *  - it holds only the method names the server itself sent, filtered through
 *    [sanitize], so nothing the server chooses can become a long string in the
 *    UI, a status document or a log line;
 *  - it is a *measurement of one attempt*. It says what the server offered this
 *    time, not what it is configured to do in general.
 *
 * The factory replaces the driver's own `ssh-userauth` factory on a client that
 * is created per connection. The service it builds is the stock implementation
 * with one overridden hook, so authentication behaviour is unchanged — only the
 * bookkeeping is added. If the override ever stops being called (a driver
 * upgrade), the list stays empty and the diagnosis degrades to the old, honest
 * "the server rejected the username or the password".
 */
internal class SshAuthMethods : ClientUserAuthServiceFactory() {

    @Volatile
    var lastOffered: List<String> = emptyList()
        private set

    override fun create(session: Session): Service = RecordingUserAuthService(session, this)

    private fun record(offered: List<String>?) {
        lastOffered = sanitize(offered)
    }

    private class RecordingUserAuthService(
        session: Session,
        private val owner: SshAuthMethods
    ) : ClientUserAuthService(session) {

        override fun processUserAuth(cmd: Int, buffer: Buffer, future: AuthFuture) {
            try {
                super.processUserAuth(cmd, buffer, future)
            } finally {
                // The field is written by the stock implementation while it
                // parses SSH_MSG_USERAUTH_FAILURE; reading it here is the whole
                // point of the override.
                owner.record(serverMethods)
            }
        }
    }

    companion object {

        /** Method names are ASCII identifiers; anything else is dropped. */
        private val NAME = Regex("^[a-z0-9-]{1,32}$")

        /** Enough to name the real choices, few enough to stay a sentence. */
        const val MAX_METHODS: Int = 6

        /**
         * Filters a driver-supplied list into stable, printable method names.
         * Returns a sorted, de-duplicated list so the same server always
         * produces the same sentence.
         */
        fun sanitize(offered: List<String>?): List<String> {
            if (offered == null) return emptyList()
            return offered
                .mapNotNull { raw -> raw?.trim()?.lowercase()?.takeIf { NAME.matches(it) } }
                .distinct()
                .sorted()
                .take(MAX_METHODS)
        }

        /**
         * True when the server offers a way to use a password at all. OpenSSH
         * with PAM advertises `keyboard-interactive` and answers the password
         * there, so a list without `password` is not enough to call it off.
         */
        fun acceptsPassword(offered: List<String>): Boolean =
            offered.any { it == "password" || it == "keyboard-interactive" }

        /** True when the server offers public-key authentication. */
        fun acceptsPublicKey(offered: List<String>): Boolean = offered.contains("publickey")
    }
}
