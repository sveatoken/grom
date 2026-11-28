// SPDX-License-Identifier: Apache-2.0
package app.grom.examples.embedded

import app.grom.server.embed.EmbedAction
import app.grom.server.embed.EmbedAdvice
import app.grom.server.embed.EmbedConsent
import app.grom.server.embed.EmbedHealth
import app.grom.server.embed.EmbedState
import app.grom.server.embed.GromEmbed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host-facing behaviour the guide promises, as tests (D112).
 *
 * These are the parts a host writes itself — the notice, consent, and the
 * sentences behind the engine's codes — so they are the parts a host can get
 * wrong. Everything else in the flow is an engine call whose contract is tested
 * in `:engine`.
 */
class EmbedExampleTest {

    // ------------------------------------------------------------- consent

    @Test
    fun `an unrecorded answer asks for consent`() {
        assertTrue(EmbedExample.needsConsent(null))
    }

    @Test
    fun `an answer for an older notice is asked again`() {
        val record = EmbedConsent.Record(noticeVersion = "0", grantedAtMs = 1L)
        assertTrue(EmbedExample.needsConsent(record))
    }

    @Test
    fun `an answer for this notice is not asked again`() {
        val record = EmbedConsent.Record(EmbedExample.NOTICE_VERSION, grantedAtMs = 1L)
        assertFalse(EmbedExample.needsConsent(record))
    }

    @Test
    fun `the notice says what starts and what it never does`() {
        val notice = EmbedExample.notice()
        assertEquals(EmbedExample.NOTICE_VERSION, notice.version)
        assertTrue(notice.title.contains("server", ignoreCase = true))
        assertTrue(notice.body.contains("notification"))
        // The two promises an operator actually cares about (D55, D82).
        assertTrue(notice.points.any { it.contains("reachable") })
        assertTrue(notice.points.any { it.contains("account") || it.contains("cloud") })
        // And the one honesty rule: it can still be stopped.
        assertTrue(notice.points.any { it.contains("stop") })
    }

    // ------------------------------------------------------- start refusals

    @Test
    fun `every documented refusal is a sentence with an action`() {
        val reasons = listOf(
            EmbedConsent.REQUIRED,
            EmbedConsent.OUTDATED,
            "PORT_IN_USE",
            "BAD_CONTROL_PORT",
            "PORT_CHANGE_RESTART_REQUIRED",
            EmbedHealth.FOREGROUND_START_NOT_ALLOWED,
            EmbedHealth.START_REFUSED
        )
        for (reason in reasons) {
            val message = EmbedMessages.startRefused(reason)
            assertTrue("$reason must say something", message.text.length > 20)
            // A refusal an operator can act on: never "nothing to do".
            assertNotEquals("$reason must offer an action", EmbedAction.NONE, message.action)
            // And never the bare code in place of a sentence.
            assertFalse("$reason must not be printed as its code", message.text.trim() == reason)
        }
    }

    @Test
    fun `consent refusals ask for the notice`() {
        assertEquals(EmbedAction.GRANT_CONSENT, EmbedMessages.startRefused(EmbedConsent.REQUIRED).action)
        assertEquals(EmbedAction.GRANT_CONSENT, EmbedMessages.startRefused(EmbedConsent.OUTDATED).action)
    }

    @Test
    fun `a port conflict suggests trying again, not a restart`() {
        // The next start resolves another port (D89), so this is not a to-do
        // that requires the operator to understand ports.
        assertEquals(EmbedAction.RETRY, EmbedMessages.startRefused("PORT_IN_USE").action)
    }

    @Test
    fun `a background start points at the visible app`() {
        val message = EmbedMessages.startRefused(EmbedHealth.FOREGROUND_START_NOT_ALLOWED)
        assertEquals(EmbedAction.RETRY, message.action)
        assertTrue(message.text.contains("on screen"))
    }

    @Test
    fun `an unknown refusal code is shown as it is`() {
        val message = EmbedMessages.startRefused("SOMETHING_NEW")
        assertTrue(message.text.contains("SOMETHING_NEW"))
        assertEquals(EmbedAction.RETRY, message.action)
    }

    @Test
    fun `a start refusal carries the platform detail when there is one`() {
        val message = EmbedMessages.startRefused(EmbedHealth.START_REFUSED, "SecurityException")
        assertTrue(message.text.contains("SecurityException"))
    }

    // ------------------------------------------------------------- health

    @Test
    fun `every engine headline has a sentence`() {
        val headlines = listOf(
            EmbedHealth.SERVER_RUNNING,
            EmbedHealth.SERVER_STARTING,
            EmbedHealth.SERVER_STOPPED,
            EmbedHealth.SERVER_DIED,
            EmbedHealth.SERVER_QUARANTINED,
            EmbedHealth.RUNTIME_MISSING,
            EmbedHealth.NEEDS_ATTENTION,
            EmbedHealth.PARTLY_UNKNOWN
        )
        for (headline in headlines) {
            val message = EmbedMessages.health(EmbedHealth(EmbedState.UNKNOWN, headline))
            assertTrue("$headline must say something", message.text.length > 20)
            assertFalse(message.text.contains(headline))
        }
    }

    @Test
    fun `a green sentence is only produced for the measured green headline`() {
        val green = EmbedMessages.health(EmbedHealth(EmbedState.RUNNING, EmbedHealth.SERVER_RUNNING))
        assertTrue(green.text.contains("running"))
        val died = EmbedMessages.health(EmbedHealth(EmbedState.STOPPED, EmbedHealth.SERVER_DIED))
        assertFalse(died.text.contains("is running"))
        assertTrue(died.text.contains("without shutting down"))
    }

    @Test
    fun `the first advice decides the button`() {
        val health = EmbedHealth(
            EmbedState.ATTENTION,
            EmbedHealth.NEEDS_ATTENTION,
            advice = listOf(
                EmbedAdvice("BATTERY_OPTIMISED", EmbedAction.OPEN_BATTERY_SETTINGS),
                EmbedAdvice("NOTIFICATIONS_OFF", EmbedAction.OPEN_NOTIFICATION_SETTINGS)
            )
        )
        assertEquals(EmbedAction.OPEN_BATTERY_SETTINGS, EmbedMessages.health(health).action)
    }

    @Test
    fun `a healthy report offers no button`() {
        val health = EmbedHealth(EmbedState.RUNNING, EmbedHealth.SERVER_RUNNING)
        assertEquals(EmbedAction.NONE, EmbedMessages.health(health).action)
    }

    @Test
    fun `an unknown headline is reported, never guessed as stopped`() {
        val message = EmbedMessages.health(EmbedHealth(EmbedState.UNKNOWN, "PROCESS_UNKNOWN_STATE"))
        assertTrue(message.text.contains("PROCESS_UNKNOWN_STATE"))
        assertFalse(message.text.contains("is not running"))
    }

    // ------------------------------------------------------------- advice

    @Test
    fun `the advice codes a host can fix are sentences`() {
        val codes = listOf(
            "BATTERY_OPTIMISED",
            "NOTIFICATIONS_OFF",
            "BACKGROUND_RESTRICTED",
            "STORAGE_LOW",
            "PROCESS_STOPPED",
            "AGENT_CRASHING",
            "DATA_PLANE_NOT_RUNNING",
            "PROCESS_NO_RUNTIME",
            "EXIT_LOW_MEMORY"
        )
        for (code in codes) {
            assertFalse(code, EmbedMessages.advice(code) == code)
        }
    }

    @Test
    fun `an advice code the host does not know is shown verbatim`() {
        assertEquals("SOMETHING_NEW", EmbedMessages.advice("SOMETHING_NEW"))
    }

    // ------------------------------------------------------------ constants

    @Test
    fun `the example asks for an automatic port and not the standalone one`() {
        // A literal 47001 here would collide with an installed Grom (D85).
        assertEquals(0, GromEmbed.AUTO_PORT)
    }
}
