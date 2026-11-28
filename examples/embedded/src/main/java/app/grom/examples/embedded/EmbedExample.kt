// SPDX-License-Identifier: Apache-2.0
package app.grom.examples.embedded

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import app.grom.server.embed.EmbedAction
import app.grom.server.embed.EmbedConsent
import app.grom.server.embed.EmbedHealth
import app.grom.server.embed.EmbedSettings
import app.grom.server.embed.GromEmbed

/**
 * The whole "2 in 1" host flow, in the order an operator meets it (D112).
 *
 * Four steps, and nothing else is required: say what starts, record the answer,
 * start from a visible Activity, and show what was measured. Everything a host
 * needs on top of that — the port, the consent version, the health projection —
 * is engine behaviour behind these calls; a host that invents its own diagnosis
 * or its own port logic is doing work the engine already did.
 *
 * This file is compiled, so `:engine` cannot drift away from the API the guide
 * documents without the build failing. Its pure parts live in [EmbedMessages].
 */
object EmbedExample {

    /** Bump when the text below changes meaning (D88); pass the same to `start`. */
    const val NOTICE_VERSION: String = "1"

    /**
     * What the host shows before the first start.
     *
     * The engine owns no wording (D55): this is a template that a real host
     * translates and shortens, not a constant it inherits.
     */
    data class Notice(
        val version: String,
        val title: String,
        val body: String,
        val points: List<String>
    )

    fun notice(): Notice = Notice(
        version = NOTICE_VERSION,
        title = "Run a local server?",
        body = "This app can run a small server for its own features while it is open. " +
            "It uses a notification so Android does not stop it, and it works best when it is " +
            "exempt from battery optimisation. You can stop it at any time.",
        points = listOf(
            "Nothing is reachable from other devices unless you turn that on yourself.",
            "It never connects to an account or a cloud service.",
            "Android may still stop it (for example when the phone runs out of memory); " +
                "a stopped server does not delete your data."
        )
    )

    /**
     * `true` when the operator has not answered *this* notice.
     *
     * A recorded `true` from an older notice does not count, and neither does a
     * missing record: both mean the dialog is shown again (D88).
     */
    fun needsConsent(record: EmbedConsent.Record?): Boolean =
        record == null || record.noticeVersion != NOTICE_VERSION

    /**
     * Records the operator's answer. Call it from the dialog, before [start].
     *
     * "No" is recorded too — it clears an earlier "yes", so a refusal is not
     * overwritten by a stale agreement.
     */
    fun recordAnswer(context: Context, granted: Boolean) {
        GromEmbed.recordConsent(context, noticeVersion = NOTICE_VERSION, granted = granted)
    }

    /**
     * Starts the embedded server from a visible Activity.
     *
     * Not from `Application.onCreate`: on Android 12+ a foreground-service start
     * from the background is refused, and the refusal would be hidden behind a
     * spin that never ends. [GromEmbed.AUTO_PORT] keeps the server away from a
     * standalone Grom's 47001 without asking the operator what a port is.
     *
     * The returned message is a start *request*, not a health claim; ask
     * [health] for the measurement.
     */
    fun start(activity: Activity): EmbedMessages.Message {
        if (needsConsent(GromEmbed.consentRecord(activity))) {
            return EmbedMessages.startRefused(EmbedConsent.REQUIRED)
        }
        val result = GromEmbed.start(
            activity,
            GromEmbed.Options(
                projectId = "embed-demo",
                projectTitle = "Embed demo",
                noticeVersion = NOTICE_VERSION,
                controlPort = GromEmbed.AUTO_PORT
            )
        )
        if (!result.requested) return EmbedMessages.startRefused(result.reason, result.detail)
        return EmbedMessages.startRefused(null)
    }

    /** Stops the server and the heartbeat. Nothing on disk is deleted. */
    fun stop(context: Context) = GromEmbed.stop(context)

    /**
     * Measures and describes the server for the host's screen.
     *
     * The callback runs on a background thread; marshal to the main thread before
     * touching views.
     */
    fun health(context: Context, onMessage: (EmbedMessages.Message, EmbedHealth) -> Unit) {
        GromEmbed.health(context) { measured ->
            onMessage(EmbedMessages.health(measured), measured)
        }
    }

    /**
     * Opens the system screen an advice item points at.
     *
     * Vendor ROMs remove some of these pages, so the reminder is caught and the
     * code is reported instead of the app crashing on a missing Activity.
     */
    fun openAdvice(context: Context, action: EmbedAction): Boolean {
        val intent = EmbedSettings.intentFor(context, action) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
