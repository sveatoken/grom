// SPDX-License-Identifier: Apache-2.0
package app.grom.examples.embedded

import app.grom.server.embed.EmbedAction
import app.grom.server.embed.EmbedConsent
import app.grom.server.embed.EmbedHealth

/**
 * The sentences a host shows, and the action behind each (D112).
 *
 * The engine owns stable codes and no wording (D55), so every host has to answer
 * "what do I tell the operator?" for the codes in `docs/TWO-IN-ONE.md` §9. That
 * is the one part of "2 in 1" an ordinary developer gets wrong first, because the
 * codes are easy to print and easy to print *badly*: `PORT_IN_USE` on a phone
 * screen tells the operator nothing.
 *
 * This is a plain object over plain data — no `android.*`, no engine call — so
 * every branch is a JVM test. A host should translate these strings and keep the
 * codes; the pairing (code → action) is what makes the button in the UI correct.
 */
object EmbedMessages {

    /** One line the operator can read, and the button that belongs under it. */
    data class Message(val text: String, val action: EmbedAction)

    /**
     * What to say when [app.grom.server.embed.GromEmbed.start] did not start.
     *
     * `result.reason` is `null` only on success, which the caller has already
     * handled; an unknown code is shown verbatim rather than replaced by a vague
     * sentence, because a wrong explanation costs more than an unfriendly one.
     */
    fun startRefused(reason: String?, detail: String? = null): Message = when (reason) {
        EmbedConsent.REQUIRED, EmbedConsent.OUTDATED ->
            Message("This app needs your permission before it can run its local server.", EmbedAction.GRANT_CONSENT)
        // D89: the host asked for a port and it is held (usually by a standalone
        // Grom). "Try again" is honest: the next start picks another free port.
        "PORT_IN_USE" ->
            Message("Another app is using the address this server needs. Try again and it will pick a different one.", EmbedAction.RETRY)
        "BAD_CONTROL_PORT" ->
            Message("The server address this app asked for is not usable. Try again.", EmbedAction.RETRY)
        "PORT_CHANGE_RESTART_REQUIRED" ->
            Message("The server has to be stopped once before it can use a different address.", EmbedAction.RESTART_SERVER)
        // Android 12+ refuses a foreground-service start from the background.
        EmbedHealth.FOREGROUND_START_NOT_ALLOWED ->
            Message("Open the app and try again — Android only allows this while the app is on screen.", EmbedAction.RETRY)
        EmbedHealth.START_REFUSED -> Message(
            "Android refused to start the server" + (detail?.let { " ($it)" } ?: "") + ". Restart the app and try again.",
            EmbedAction.RETRY
        )
        null -> Message("The server started.", EmbedAction.NONE)
        else -> Message("The server did not start ($reason).", EmbedAction.RETRY)
    }

    /**
     * What to say about a measured health report.
     *
     * The state decides the colour and the headline code decides the sentence;
     * `RUNNING` never appears here unless the measurement said so, because the
     * projection behind [health] cannot be greener than the Doctor's report.
     */
    fun health(health: EmbedHealth): Message {
        val text = when (health.headline) {
            EmbedHealth.SERVER_RUNNING -> "The local server is running."
            EmbedHealth.SERVER_STARTING -> "The local server is starting."
            EmbedHealth.SERVER_STOPPED -> "The local server is not running."
            EmbedHealth.SERVER_DIED ->
                "The local server stopped without shutting down — Android or a system cleaner ended it."
            EmbedHealth.SERVER_QUARANTINED ->
                "The local server kept restarting its agent and was stopped. Open this app to try again."
            EmbedHealth.RUNTIME_MISSING ->
                "The local server runs, but this build has no local runtime — the features that need one will say so."
            EmbedHealth.NEEDS_ATTENTION -> "The local server may stop soon. See what can be changed."
            EmbedHealth.PARTLY_UNKNOWN -> "Some checks could not be measured, so this is not a health result."
            // A code the engine added after this host was written. Saying "not
            // running" would be a guess; the code is at least true.
            else -> "The server health could not be described (${health.headline})."
        }
        val action = health.advice.firstOrNull()?.action ?: EmbedAction.NONE
        return Message(text, action)
    }

    /**
     * The Doctor's own code as a sentence (D90).
     *
     * Only the codes a host can actually do something about are spelled out; the
     * rest fall back to the code, and the action that came with it still works.
     */
    fun advice(code: String): String = when (code) {
        "BATTERY_OPTIMISED" -> "Android is optimising the battery, which can stop the server."
        "STANDBY_RARE", "STANDBY_RESTRICTED", "STANDBY_NEVER" ->
            "Android has put this app into a restricted standby bucket."
        "NOTIFICATIONS_OFF" ->
            "Notifications are off. Android hides a running server without one and may stop it."
        "BACKGROUND_RESTRICTED" -> "Background activity is restricted for this app."
        "STORAGE_LOW" -> "The phone is low on storage."
        "PROCESS_STOPPED", "PROCESS_STOPPED_UNCLEAN" ->
            "The server is not running right now."
        "PROCESS_QUARANTINED", "AGENT_CRASHING" ->
            "The server's agent kept crashing and was stopped."
        "DATA_PLANE_NOT_RUNNING" ->
            "The public API is switched on but is not listening, so other devices cannot reach it."
        "PROCESS_NO_RUNTIME" ->
            "There is no local runtime in this build, so features that need one are unavailable."
        "EXIT_LOW_MEMORY" -> "Android ended the server because memory was low."
        "EXIT_SIGNALED" -> "The server process was ended by a signal."
        "EXIT_FREEZER" -> "Android froze the server while the phone was idle."
        "EXIT_CRASH", "EXIT_CRASH_NATIVE", "EXIT_ANR", "EXIT_UNRECORDED" ->
            "The server process ended unexpectedly."
        else -> code
    }
}
