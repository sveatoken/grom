// SPDX-License-Identifier: Apache-2.0
package app.grom.server

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.grom.server.core.BindMode
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.notify.NotifySettingsRules
import app.grom.server.referral.ReferralStore
import app.grom.server.service.GromPrefs
import app.grom.server.ssh.SshTarget
import app.grom.server.store.AndroidKeystoreSecretBox
import app.grom.server.superserver.ApiKeyConfig
import app.grom.server.superserver.AuthMode
import app.grom.server.superserver.ConfigExport
import app.grom.server.superserver.LightningKind
import app.grom.server.superserver.LightningSettings
import app.grom.server.superserver.SuperServerConfig
import app.grom.server.superserver.SuperServerConfigStore
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.superserver.nostr.Nip19
import app.grom.server.superserver.nostr.RelayUrl
import app.grom.server.superserver.nostr.RemoteBridgeHint
import app.grom.server.superserver.plugins.ai.AiProviderKind
import app.grom.server.superserver.plugins.lightning.ExternalNodeKind
import app.grom.server.superserver.plugins.lightning.ExternalNodeRef
import app.grom.server.ui.DashboardModel
import app.grom.server.ui.DoctorModel
import app.grom.server.ui.FactsRow
import app.grom.server.ui.FleetModel
import app.grom.server.ui.GromTheme
import app.grom.server.ui.HelpModel
import app.grom.server.ui.Lamp
import app.grom.server.ui.MenuEntry
import app.grom.server.ui.MenuModel
import app.grom.server.ui.NotifyModel
import app.grom.server.ui.Palette
import app.grom.server.ui.actionRow
import app.grom.server.ui.actionRowState
import app.grom.server.ui.card
import app.grom.server.ui.divider
import app.grom.server.ui.dp
import app.grom.server.ui.gap
import app.grom.server.ui.label
import app.grom.server.ui.lampView
import app.grom.server.ui.pill
import app.grom.server.ui.repaintLamp
import app.grom.server.ui.servingRow
import app.grom.server.ui.shape
import app.grom.server.ui.text
import app.grom.server.update.UpdateChecker
import app.grom.sdk.GromClient
import app.grom.sdk.ipc.IGromServer
import java.io.File
import java.util.Base64

/**
 * The operator surface (D56).
 *
 * One screen, one measured status, one primary action. Everything below the
 * "Now serving" card is a collapsible section, so the dashboard stays quiet
 * while the server is running. Every lamp is driven by
 * [DashboardModel][app.grom.server.ui.DashboardModel]: configuration alone can
 * never turn it green.
 *
 * The screen is optional — the server is fully usable over HTTP and Binder —
 * but it is the honest face of the app. It says MISSING_RUNTIME when the agent
 * cannot start, "not probed" for Lightning until a probe has really run, and
 * "not connected" for a tunnel that is only configured.
 */
class GromActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var client: GromClient
    private lateinit var palette: Palette

    private var connected = false
    private var disconnectReason: String? = null
    private var stoppedByOperator = false
    private var status: Map<String, Any?>? = null

    private lateinit var stateView: TextView
    private lateinit var detailView: TextView
    private lateinit var primaryAction: TextView

    /** "Now serving" rows: id -> (lamp, value). */
    private val servingViews = LinkedHashMap<String, Pair<View, TextView>>()

    /**
     * Menu sections, built once and kept detached (D68). A section is
     * reparented into a dialog when it is opened, so every renderer below keeps
     * a live view and a section can never show stale or uninitialised content.
     */
    private val sectionPanels = LinkedHashMap<String, LinearLayout>()

    // AI gateway (D94). The section reads one Binder document and re-renders
    // only when its signature changes; every action replaces the document.
    private var aiStatusDoc: Map<String, Any?>? = null
    private var aiLoaded = false
    private var lastAiSignature: String? = null
    private lateinit var aiProviderList: LinearLayout
    private lateinit var aiModelList: LinearLayout
    private lateinit var aiToggleRow: Pair<LinearLayout, TextView>
    private lateinit var aiDefaultLine: TextView
    private lateinit var aiDetail: TextView

    private lateinit var runtimeTitle: TextView
    private lateinit var runtimeDetail: TextView
    private lateinit var bootValue: TextView

    private val superStore by lazy { SuperServerConfigStore(File(filesDir, "grom")) }
    private val secretBox by lazy { AndroidKeystoreSecretBox() }
    private var superConfig: SuperServerConfig = SuperServerConfig.defaults()
    private lateinit var superDetail: TextView
    private lateinit var superEnableRow: Pair<LinearLayout, TextView>
    private lateinit var superBindRow: Pair<LinearLayout, TextView>
    private lateinit var superAuthRow: Pair<LinearLayout, TextView>
    private lateinit var superPasswordRow: Pair<LinearLayout, TextView>
    private lateinit var superKeysRow: Pair<LinearLayout, TextView>
    private lateinit var superLightningRow: Pair<LinearLayout, TextView>
    private lateinit var superNpubRow: Pair<LinearLayout, TextView>
    private lateinit var superRelaysRow: Pair<LinearLayout, TextView>
    private lateinit var superBridgeRow: Pair<LinearLayout, TextView>
    private lateinit var superCopyConfigRow: Pair<LinearLayout, TextView>
    private lateinit var lightningHealthRow: Pair<LinearLayout, TextView>
    private lateinit var superExternalNodeRow: Pair<LinearLayout, TextView>

    private lateinit var sshList: LinearLayout
    private var sshAvailable = false
    private var sshTargets: List<Map<String, Any?>> = emptyList()
    private var lastSshSignature: String? = null

    private lateinit var projectsList: LinearLayout
    private var lastProjectsSignature: String? = null

    /** Fleet: this device's identity and the devices paired with it (D70-D75). */
    private lateinit var fleetList: LinearLayout
    private lateinit var fleetIdentityLabel: TextView
    private lateinit var fleetIdentityDetail: TextView
    private lateinit var fleetIdentityNote: TextView
    private lateinit var fleetDetail: TextView
    private var fleetIdentityDoc: Map<*, *>? = null
    private var fleetPeers: List<Map<String, Any?>> = emptyList()
    private var fleetLoaded = false
    private var fleetRefreshing = false
    private var lastFleetSignature: String? = null

    // Doctor (D80-D84). The document is the engine's measurement; this screen
    // only paints it, so nothing here can make a lamp greener than the engine
    // allowed.
    private lateinit var doctorVerdict: TextView
    private lateinit var doctorVerdictLamp: View
    private lateinit var doctorSubtitle: TextView
    private lateinit var doctorChecks: LinearLayout
    private lateinit var doctorFacts: LinearLayout
    private lateinit var doctorDetail: TextView
    private var doctorDocument: Map<*, *>? = null
    private var doctorMeasuredAtMs = 0L
    private var lastDoctorSignature: String? = null

    /** Telegram alerts (D65). The token is never displayed, only "set". */
    private lateinit var alertsEnableRow: Pair<LinearLayout, TextView>
    private lateinit var alertsChatRow: Pair<LinearLayout, TextView>
    private lateinit var alertsTokenRow: Pair<LinearLayout, TextView>
    private lateinit var alertsTestRow: Pair<LinearLayout, TextView>
    private lateinit var alertsStateRow: Pair<LinearLayout, TextView>
    private var alertsLamp: View? = null
    private lateinit var alertsDetail: TextView
    private var alertsChatId: String = ""
    private var alertsTokenSet: Boolean = false
    private var alertsEnabled: Boolean = false
    private var lastAlertsSignature: String? = null

    private lateinit var updateTitle: TextView
    private lateinit var updateDetail: TextView
    private lateinit var updateAction: TextView

    private lateinit var inviteCodeView: TextView
    private lateinit var inviteSharesView: TextView
    private lateinit var inviteRedeemRow: Pair<LinearLayout, TextView>

    private var updateState: UpdateState = UpdateState.Idle

    private sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object Current : UpdateState
        data class Available(val release: UpdateChecker.ReleaseInfo) : UpdateState
        data class Failed(val reason: String) : UpdateState
    }

    private val refresher = object : Runnable {
        override fun run() {
            if (connected) refreshNow()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GromTheme.applyWindow(this)
        palette = GromTheme.palette(this)
        client = GromClient(this)
        if (BuildConfig.GROMSERVER) superConfig = superStore.load()
        setContentView(buildUi())
        renderUpdate()
        renderInvite()
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.GROMSERVER && !stoppedByOperator) bind()
        handler.post(refresher)
    }

    override fun onStop() {
        handler.removeCallbacks(refresher)
        client.unbind()
        connected = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        render()
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.background)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        column.addView(header())
        column.addView(gap(20))
        column.addView(heroCard())

        // Only the server role serves anything: peripheral26 never binds a
        // socket, so an all-grey "Now serving" card would be noise, not status.
        if (BuildConfig.GROMSERVER) {
            column.addView(gap(14))
            column.addView(servingCard())
        }

        // Everything else lives in the header menu (D68). The panels are built
        // here, detached, so every renderer below keeps a live view from the
        // first refresh and an opened section is never stale.
        //
        // The invite card and the explanation are informational, so they open
        // from the menu too (D69): the page itself holds only the server, never
        // something that has to be scrolled past to reach the server state.
        buildSectionPanels()

        column.addView(gap(24))
        column.addView(
            text(
                getString(R.string.ui_footer, BuildConfig.VERSION_NAME),
                12f,
                palette.muted,
                gravity = Gravity.CENTER
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )

        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            view.setPadding(0, top, 0, bottom)
            insets
        }
        return scroll
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = text("G", 16f, palette.onAccent, Typeface.BOLD, gravity = Gravity.CENTER).apply {
            background = shape(palette.accent, 9)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            includeFontPadding = false
        }
        row.addView(mark)
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })

        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(
            text(
                getString(R.string.ui_wordmark),
                21f,
                palette.onSurface,
                Typeface.BOLD,
                letterSpacing = 0.16f
            )
        )
        titles.addView(text(getString(R.string.ui_subtitle), 10f, palette.muted, letterSpacing = 0.12f))
        row.addView(
            titles,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        // Menu sits next to the theme switch in the top-right corner (D68).
        val menuAction = pill(
            value = getString(R.string.ui_menu),
            textColor = palette.onSurface,
            fill = palette.background,
            rippleColor = palette.ripple,
            stroke = palette.border,
            onClick = { showMenu() }
        ).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(menuAction)
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })

        val themeAction = pill(
            value = GromTheme.mode(this).label,
            textColor = palette.onSurface,
            fill = palette.background,
            rippleColor = palette.ripple,
            stroke = palette.border,
            onClick = {
                GromTheme.setMode(this, GromTheme.mode(this).next())
                recreate()
            }
        ).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(themeAction)
        return row
    }

    private fun heroCard(): View {
        val card = card(palette, radiusDp = 24)
        card.addView(label(getString(R.string.ui_status), palette))
        card.addView(gap(8))
        stateView = text(
            getString(R.string.ui_dash),
            34f,
            palette.onSurface,
            Typeface.BOLD,
            letterSpacing = -0.02f
        )
        card.addView(stateView)
        card.addView(gap(6))
        detailView = text("", 14f, palette.muted)
        detailView.maxLines = 3
        detailView.ellipsize = TextUtils.TruncateAt.END
        card.addView(detailView)

        if (BuildConfig.GROMSERVER) {
            card.addView(gap(18))
            primaryAction = pill(
                value = getString(R.string.action_start),
                textColor = palette.onAccent,
                fill = palette.accent,
                rippleColor = palette.ripple,
                onClick = {
                    val running = connected && state() != null && state() != "STOPPED"
                    if (running) stopServer() else startServer()
                }
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            card.addView(primaryAction)
        } else {
            primaryAction = text("", 1f, palette.muted)
        }
        return card
    }

    private fun servingCard(): View {
        val card = card(palette)
        card.addView(label(getString(R.string.ui_serving), palette))
        card.addView(gap(2))
        val rows = listOf(
            "control" to R.string.serve_control,
            "data" to R.string.serve_data,
            "bridge" to R.string.serve_bridge,
            "project" to R.string.serve_project
        )
        for ((id, titleRes) in rows) {
            val (row, dot, value) = servingRow(getString(titleRes), null, Lamp.UNKNOWN, palette)
            servingViews[id] = dot to value
            card.addView(row)
        }
        return card
    }

    // ------------------------------------------------------- menu (D68)

    /**
     * Builds every menu section once, detached from the page. Building eagerly
     * costs the same as before this change (the cards were always built) and
     * keeps `render*` pointing at real views, which is why opening a section is
     * instant and never shows a placeholder.
     */
    private fun buildSectionPanels() {
        panel(MenuModel.KEY_UPDATE) { buildUpdateSection(it) }
        panel(MenuModel.KEY_INVITE) { buildInviteSection(it) }
        panel(MenuModel.KEY_HELP) { buildHelpSection(it) }
        if (!BuildConfig.GROMSERVER) return
        panel(MenuModel.KEY_RUNTIME) { buildRuntimeSection(it) }
        panel(MenuModel.KEY_DOCTOR) { buildDoctorSection(it) }
        panel(MenuModel.KEY_SSH) { buildSshSection(it) }
        panel(MenuModel.KEY_FLEET) { buildFleetSection(it) }
        panel(MenuModel.KEY_NOSTR) { buildNostrSection(it) }
        panel(MenuModel.KEY_AI) { buildAiSection(it) }
        panel(MenuModel.KEY_PROJECTS) { buildProjectsSection(it) }
        panel(MenuModel.KEY_ALERTS) { buildAlertsSection(it) }
        panel(MenuModel.KEY_ACCESS) { buildAccessSection(it) }
    }

    private fun panel(key: String, build: (LinearLayout) -> Unit) {
        val cardView = card(palette)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardView.addView(body)
        build(body)
        sectionPanels[key] = cardView
    }

    /** The dropdown behind the header button: sections, in importance order. */
    private fun showMenu() {
        val entries = MenuModel.entries(BuildConfig.GROMSERVER)
        val dialog = Dialog(this)

        val cardView = card(palette, radiusDp = 22).apply {
            setPadding(dp(10), dp(16), dp(10), dp(10))
        }
        cardView.addView(
            label(getString(R.string.ui_menu_sections), palette).apply {
                setPadding(dp(10), 0, dp(10), dp(6))
            }
        )
        for (entry in entries) {
            cardView.addView(
                menuRow(entry) {
                    dialog.dismiss()
                    showSection(entry.key)
                }
            )
        }
        cardView.addView(gap(6))
        cardView.addView(
            pill(
                value = getString(R.string.ui_close),
                textColor = palette.onSurface,
                fill = palette.background,
                rippleColor = palette.ripple,
                stroke = palette.border,
                onClick = { dialog.dismiss() }
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(cardView)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /** Title plus one short line, with the same ripple and chevron as a row. */
    private fun menuRow(entry: MenuEntry, onClick: () -> Unit): View {
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(getString(entry.titleRes), 15f, palette.onSurface))
            addView(gap(2))
            addView(text(getString(entry.subtitleRes), 11f, palette.muted))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(palette.ripple), null, null)
            setOnClickListener { onClick() }
            addView(
                titles,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(text("\u203A", 20f, palette.muted, gravity = Gravity.END))
        }
    }

    /**
     * Opens one section in a dialog. The panel is moved out of any previous
     * parent first, and moved back out on dismiss, so a section can be opened
     * as many times as the operator likes without being rebuilt or duplicated.
     */
    private fun showSection(key: String) {
        val titleRes = MenuModel.titleFor(key) ?: return
        val panel = sectionPanels[key] ?: return
        (panel.parent as? ViewGroup)?.removeView(panel)

        val dialog = Dialog(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(panel)
        scroll.addView(inner)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(6))
        }
        headerRow.addView(
            label(getString(titleRes), palette),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        headerRow.addView(
            pill(
                value = getString(R.string.ui_close),
                textColor = palette.onSurface,
                fill = palette.background,
                rippleColor = palette.ripple,
                stroke = palette.border,
                onClick = { dialog.dismiss() }
            ).apply {
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }
        root.addView(headerRow)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            (panel.parent as? ViewGroup)?.removeView(panel)
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val height = (resources.displayMetrics.heightPixels * 0.86f).toInt()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height)
        // Paint the freshly opened section from the last status immediately
        // instead of waiting up to a second for the refresher tick.
        render()
        // Probes run when the operator asks, never in the background (D74).
        if (key == MenuModel.KEY_FLEET && !fleetLoaded) refreshFleet()
        // A measurement, on demand, when the operator opens the screen (D74, D80).
        if (key == MenuModel.KEY_DOCTOR) refreshDoctor()
        // The gateway document is read when the section opens, never in a loop.
        if (key == MenuModel.KEY_AI && !aiLoaded) loadAi()
    }

    // ---------------------------------------------------------- section bodies

    /**
     * The plain-language explanation (D62). Every string lives in strings.xml,
     * the order is fixed by [HelpModel], and nothing here reports state.
     */
    private fun buildHelpSection(body: LinearLayout) {
        for ((index, block) in HelpModel.blocks(BuildConfig.GROMSERVER).withIndex()) {
            if (index > 0) body.addView(gap(14))
            body.addView(
                text(getString(block.headingRes), 14f, palette.onSurface, Typeface.BOLD)
            )
            body.addView(gap(4))
            body.addView(text(getString(block.bodyRes), 12f, palette.muted))
        }
        body.addView(gap(14))
        body.addView(text(getString(HelpModel.footnoteRes()), 11f, palette.muted))
    }

    private fun buildRuntimeSection(body: LinearLayout) {
        runtimeTitle = text(getString(R.string.ui_dash), 20f, palette.onSurface, Typeface.BOLD)
        body.addView(runtimeTitle)
        body.addView(gap(4))
        runtimeDetail = text("", 13f, palette.muted)
        runtimeDetail.maxLines = 4
        runtimeDetail.ellipsize = TextUtils.TruncateAt.END
        body.addView(runtimeDetail)
        body.addView(divider(palette))
        body.addView(
            actionRow(getString(R.string.ui_restart_agent), getString(R.string.ui_agent), palette) {
                invoke("restart") { it.restartAgent() }
            }
        )
        body.addView(divider(palette))
        body.addView(
            actionRow(getString(R.string.ui_reset_quarantine), getString(R.string.ui_policy), palette) {
                invoke("reset") { it.resetAgent() }
            }
        )
        body.addView(divider(palette))
        body.addView(
            actionRow(getString(R.string.ui_rotate_token), getString(R.string.ui_rotate), palette) {
                invoke("rotate") { it.rotateToken() }
                toast(getString(R.string.toast_token_rotated))
            }
        )
        body.addView(divider(palette))

        bootValue = text(
            if (GromPrefs.startOnBoot(this)) getString(R.string.ui_on) else getString(R.string.ui_off),
            13f,
            palette.muted,
            gravity = Gravity.END
        )
        val bootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                GromPrefs.setStartOnBoot(this@GromActivity, !GromPrefs.startOnBoot(this@GromActivity))
                bootValue.text = if (GromPrefs.startOnBoot(this@GromActivity)) {
                    getString(R.string.ui_on)
                } else {
                    getString(R.string.ui_off)
                }
            }
            addView(
                text(getString(R.string.ui_start_on_boot), 15f, palette.onSurface),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(bootValue)
        }
        body.addView(bootRow)
    }

    /**
     * The Doctor section (D80).
     *
     * Three groups, and the separation is the point: the **verdict** and the
     * checks carry lamps because they are measurements; "Right now" has no lamps
     * because a hot phone is context, not a diagnosis; and the limits block says
     * out loud what an app cannot see, so a grey row is never mistaken for a
     * missing feature.
     */
    private fun buildDoctorSection(body: LinearLayout) {
        if (!BuildConfig.GROMSERVER) {
            body.addView(text(getString(R.string.doctor_unavailable), 12f, palette.muted))
            return
        }
        body.addView(text(getString(R.string.doctor_hint), 12f, palette.muted))
        body.addView(gap(12))

        // --- verdict -------------------------------------------------------
        val verdictRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        doctorVerdictLamp = lampView(Lamp.UNKNOWN, palette)
        verdictRow.addView(doctorVerdictLamp)
        verdictRow.addView(gap(8))
        doctorVerdict = text("", 16f, palette.onSurface, Typeface.BOLD)
        verdictRow.addView(doctorVerdict)
        body.addView(verdictRow)
        doctorSubtitle = text("", 11f, palette.muted)
        body.addView(gap(3))
        body.addView(doctorSubtitle)

        // --- checks --------------------------------------------------------
        body.addView(divider(palette))
        body.addView(label(getString(R.string.doctor_section_checks), palette))
        doctorChecks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(doctorChecks)

        // --- right now -----------------------------------------------------
        body.addView(divider(palette))
        body.addView(label(getString(R.string.doctor_section_now), palette))
        doctorFacts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(doctorFacts)

        // --- limits --------------------------------------------------------
        body.addView(divider(palette))
        body.addView(label(getString(R.string.doctor_section_limits), palette))
        body.addView(gap(4))
        body.addView(text(getString(R.string.doctor_limits_body), 11f, palette.muted))
        body.addView(gap(8))
        body.addView(
            text(
                getString(R.string.doctor_adb_commands, packageName),
                10f,
                palette.muted
            ).apply { typeface = Typeface.MONOSPACE }
        )
        body.addView(gap(8))
        body.addView(docButton(R.string.doctor_copy_adb) { copyText(getString(R.string.doctor_adb_commands, packageName), R.string.doctor_adb_copied) })

        // --- actions -------------------------------------------------------
        body.addView(divider(palette))
        body.addView(label(getString(R.string.doctor_section_actions), palette))
        body.addView(
            docButton(R.string.doctor_refresh) { refreshDoctor() }
        )
        body.addView(gap(6))
        body.addView(
            docButton(R.string.doctor_copy) { copyText(Json.encode(doctorDocument ?: emptyMap<String, Any?>()), R.string.doctor_copied) }
        )
        body.addView(gap(6))
        body.addView(
            docButton(R.string.doctor_open_battery_exempt) { confirmBatteryExemption() }
        )
        body.addView(gap(6))
        body.addView(
            docButton(R.string.doctor_open_battery) {
                openSystemSettings(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
        )
        body.addView(gap(6))
        body.addView(
            docButton(R.string.doctor_open_notifications) {
                openSystemSettings(
                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    android.provider.Settings.EXTRA_APP_PACKAGE to packageName
                )
            }
        )
        body.addView(gap(6))
        body.addView(
            docButton(R.string.doctor_open_app) {
                openSystemSettings(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    null,
                    "package:$packageName"
                )
            }
        )
        doctorDetail = text("", 11f, palette.muted)
        body.addView(gap(8))
        body.addView(doctorDetail)
    }

    private fun docButton(res: Int, onClick: () -> Unit): TextView = pill(
        value = getString(res),
        textColor = palette.onSurface,
        fill = palette.background,
        rippleColor = palette.ripple,
        stroke = palette.border,
        onClick = onClick
    ).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    // -------------------------------------------------------- AI gateway (D94)

    /**
     * Providers and models, as one card (D94).
     *
     * The screen never holds a key in a field it later reads back: the key is
     * typed once, sent, sealed by the engine and replaced by "key stored". A
     * refusal from the engine (a bad URL, a duplicate id) is shown with its own
     * code instead of "it failed".
     */
    private fun buildAiSection(body: LinearLayout) {
        if (!BuildConfig.GROMSERVER) {
            body.addView(text(getString(R.string.ai_unavailable), 12f, palette.muted))
            return
        }
        body.addView(text(getString(R.string.ai_hint), 12f, palette.muted))
        body.addView(gap(10))

        aiToggleRow = actionRowState(getString(R.string.ai_toggle), palette) { toggleAiEnabled() }
        body.addView(aiToggleRow.first)
        body.addView(divider(palette))

        body.addView(label(getString(R.string.ai_section_providers), palette))
        aiProviderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(aiProviderList)
        body.addView(gap(8))
        body.addView(
            docButton(R.string.ai_add_provider) { showAddProviderDialog() }
        )

        body.addView(divider(palette))
        body.addView(label(getString(R.string.ai_section_models), palette))
        aiModelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(aiModelList)
        body.addView(gap(8))
        body.addView(
            docButton(R.string.ai_add_model) { showAddModelDialog() }
        )

        body.addView(divider(palette))
        aiDefaultLine = text("", 12f, palette.muted)
        body.addView(aiDefaultLine)
        aiDetail = text("", 11f, palette.muted)
        body.addView(gap(4))
        body.addView(aiDetail)
    }

    /** Reads the gateway document from the engine, off the main thread. */
    private fun loadAi() {
        if (!BuildConfig.GROMSERVER) return
        Thread {
            val document = runCatching { aiCall { it.aiStatusJson() } }.getOrNull()
            handler.post {
                if (document != null) {
                    aiLoaded = true
                    aiStatusDoc = document
                    renderAi()
                } else if (::aiDetail.isInitialized) {
                    aiDetail.text = getString(R.string.state_offline)
                }
            }
        }.start()
    }

    /**
     * Runs one mutating call and adopts the status document it returns. The
     * engine's own code is what reaches the operator: `AI_UNKNOWN_MODEL` and
     * `BAD_PROVIDER_URL` are actionable, "failed" is not.
     */
    private fun applyAi(block: (IGromServer) -> String, successRes: Int) {
        if (!connected) {
            toast(getString(R.string.toast_not_bound, getString(R.string.ui_ai)))
            return
        }
        Thread {
            val result = runCatching { aiCall { block(it) } }
            handler.post {
                result.fold(
                    onSuccess = { document ->
                        aiStatusDoc = document
                        aiLoaded = true
                        lastAiSignature = null
                        toast(getString(successRes))
                        renderAi()
                    },
                    onFailure = { failure ->
                        toast(failure.message ?: getString(R.string.ai_saved))
                        loadAi()
                    }
                )
            }
        }.start()
    }

    /** The measured test is the only way a provider lamp turns green (D96). */
    private fun probeAiProvider(id: String) {
        if (!connected) {
            toast(getString(R.string.toast_not_bound, getString(R.string.ui_ai)))
            return
        }
        toast(getString(R.string.ai_test_running))
        Thread {
            val result = runCatching {
                val server = client.server
                    ?: throw IllegalStateException(getString(R.string.state_offline))
                JsonParser.parseObjectOrNull(server.aiProbeProviderJson(id))
                    ?: throw IllegalStateException("the engine returned no document")
            }
            handler.post {
                val probe = result.getOrNull()
                if (probe == null) {
                    toast(result.exceptionOrNull()?.message ?: getString(R.string.ai_saved))
                } else if (probe["ok"] == true) {
                    toast(
                        getString(
                            R.string.ai_test_ok,
                            (probe["latencyMs"] as? Number)?.toLong() ?: 0L
                        )
                    )
                } else {
                    val reason = (probe["detail"] as? String)
                        ?: (probe["code"] as? String)
                        ?: getString(R.string.ai_saved)
                    toast(getString(R.string.ai_test_failed, reason))
                }
                loadAi()
            }
        }.start()
    }

    private fun aiCall(block: (IGromServer) -> String): Map<String, Any?> {
        val server = client.server
            ?: throw IllegalStateException(getString(R.string.state_offline))
        return JsonParser.parseObjectOrNull(block(server))
            ?: throw IllegalStateException("the engine returned no document")
    }

    private fun renderAi() {
        if (!BuildConfig.GROMSERVER || !::aiProviderList.isInitialized) return
        val doc = aiStatusDoc ?: return
        val providers = (doc["providers"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val models = (doc["models"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }

        val signature = buildString {
            append(doc["enabled"])
            append('|').append(doc["defaultModel"])
            append('|').append(doc["error"])
            for (provider in providers) {
                append('|').append(provider["id"]).append(':').append(provider["enabled"])
                append(':').append(provider["keySet"])
                append(':').append((provider["probe"] as? Map<*, *>)?.get("ok"))
            }
            for (model in models) {
                append('|').append(model["id"]).append(':').append(model["enabled"])
            }
        }
        if (signature == lastAiSignature) return
        lastAiSignature = signature

        aiToggleRow.second.text = if (doc["enabled"] == true) {
            getString(R.string.ai_state_on)
        } else {
            getString(R.string.ai_state_off)
        }

        aiProviderList.removeAllViews()
        if (providers.isEmpty()) {
            aiProviderList.addView(text(getString(R.string.ai_none_providers), 12f, palette.muted))
        } else {
            for (provider in providers) aiProviderList.addView(aiProviderRow(provider))
        }

        aiModelList.removeAllViews()
        if (models.isEmpty()) {
            aiModelList.addView(text(getString(R.string.ai_none_models), 12f, palette.muted))
        } else {
            for (model in models) aiModelList.addView(aiModelRow(model))
        }

        val default = doc["defaultModel"] as? String
        aiDefaultLine.text = if (default.isNullOrEmpty()) {
            getString(R.string.ai_no_default)
        } else {
            getString(R.string.ai_default_model, default)
        }
        val error = doc["error"] as? String
        aiDetail.text = when {
            error != null -> getString(R.string.ai_error_line, error)
            doc["enabled"] != true -> getString(R.string.ai_off_note)
            else -> ""
        }
    }

    private fun aiProviderRow(provider: Map<*, *>): View {
        val id = provider["id"] as? String ?: ""
        val keySet = provider["keySet"] == true
        val enabled = provider["enabled"] == true
        val probe = provider["probe"] as? Map<*, *>
        val lamp = when {
            !enabled -> Lamp.UNKNOWN
            !keySet -> Lamp.OFF
            probe == null -> Lamp.UNKNOWN
            probe["ok"] == true -> Lamp.ON
            else -> Lamp.OFF
        }
        val modelCount = (provider["modelCount"] as? Number)?.toInt() ?: 0
        val modelText = if (modelCount == 0) {
            getString(R.string.ai_no_models)
        } else {
            resources.getQuantityString(R.plurals.ai_model_count, modelCount, modelCount)
        }
        val detail = getString(
            R.string.ai_provider_detail,
            provider["protocol"]?.toString() ?: "",
            if (keySet) getString(R.string.ai_key_set) else getString(R.string.ai_key_missing),
            modelText
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { showProviderDialog(id) }
            addView(lampView(lamp, palette))
            addView(
                LinearLayout(this@GromActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(provider["title"]?.toString() ?: id, 14f, palette.onSurface))
                    addView(gap(2))
                    addView(text(detail, 11f, palette.muted))
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(text("\u203A", 20f, palette.muted))
        }
    }

    private fun aiModelRow(model: Map<*, *>): View {
        val id = model["id"] as? String ?: ""
        val enabled = model["enabled"] == true
        val available = model["available"] == true
        val lamp = when {
            !enabled -> Lamp.UNKNOWN
            available -> Lamp.ON
            else -> Lamp.OFF
        }
        val detail = getString(
            R.string.ai_model_detail,
            model["providerTitle"]?.toString() ?: "",
            if (available) getString(R.string.ai_state_on) else getString(R.string.ai_not_available)
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { showModelDialog(id) }
            addView(lampView(lamp, palette))
            addView(
                LinearLayout(this@GromActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(id, 14f, palette.onSurface))
                    addView(gap(2))
                    addView(text(detail, 11f, palette.muted))
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(text("\u203A", 20f, palette.muted))
        }
    }

    private fun toggleAiEnabled() {
        val next = aiStatusDoc?.get("enabled") != true
        applyAi({ it.aiConfigureJson(Json.encode(mapOf("enabled" to next))) }, R.string.ai_saved)
    }

    private fun showAddProviderDialog() {
        var kind = AiProviderKind.OPENAI
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val idInput = EditText(this).apply {
            hint = getString(R.string.ai_field_id)
            setSingleLine()
        }
        val titleInput = EditText(this).apply {
            hint = getString(R.string.ai_field_title)
            setSingleLine()
        }
        val baseInput = EditText(this).apply {
            hint = kind.defaultBaseUrl.ifEmpty { getString(R.string.ai_field_base_url) }
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val kindRow = text(getString(R.string.ai_field_kind, kind.wireName), 13f, palette.onSurface)
            .apply {
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener {
                    val kinds = AiProviderKind.entries.map { it.wireName }
                    AlertDialog.Builder(this@GromActivity)
                        .setTitle(R.string.ai_kind_pick)
                        .setItems(kinds.toTypedArray()) { _, which ->
                            kind = AiProviderKind.entries[which]
                            text = getString(R.string.ai_field_kind, kind.wireName)
                            // The placeholder follows the kind, so an operator
                            // who never types a URL still sees where it goes.
                            baseInput.hint = kind.defaultBaseUrl.ifEmpty {
                                getString(R.string.ai_field_base_url)
                            }
                        }
                        .show()
                }
            }
        val keyInput = EditText(this).apply {
            hint = getString(R.string.ai_field_api_key)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val enabledBox = CheckBox(this).apply {
            setText(R.string.ai_field_enabled)
            isChecked = true
        }
        column.addView(idInput)
        column.addView(titleInput)
        column.addView(kindRow)
        column.addView(baseInput)
        column.addView(keyInput)
        column.addView(enabledBox)
        column.addView(
            text(getString(R.string.ai_dialog_provider_body), 11f, palette.muted)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.ai_dialog_add_provider)
            .setView(column)
            .setPositiveButton(R.string.ai_action_save) { _, _ ->
                val id = idInput.text.toString().trim()
                if (id.isEmpty()) {
                    toast(getString(R.string.ai_id_required))
                    return@setPositiveButton
                }
                applyAi({
                    it.aiSaveProviderJson(
                        Json.encode(
                            mapOf(
                                "id" to id,
                                "kind" to kind.wireName,
                                "title" to titleInput.text.toString().trim(),
                                "baseUrl" to baseInput.text.toString().trim(),
                                "apiKey" to keyInput.text.toString().trim()
                                    .takeIf { value -> value.isNotEmpty() },
                                "enabled" to enabledBox.isChecked
                            )
                        )
                    )
                }, R.string.ai_saved)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showProviderDialog(id: String) {
        val provider = (aiStatusDoc?.get("providers") as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .firstOrNull { it["id"] == id } ?: return

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val kind = AiProviderKind.fromWire(provider["kind"] as? String)
        val baseInput = EditText(this).apply {
            setText(provider["baseUrl"] as? String ?: "")
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = kind?.defaultBaseUrl ?: getString(R.string.ai_field_base_url)
        }
        val keyInput = EditText(this).apply {
            hint = getString(R.string.ai_field_api_key_keep)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val clearBox = CheckBox(this).apply {
            setText(R.string.ai_action_clear_key)
            // An explicit action, never a default: a checked box here used to
            // discard a key that had just been pasted.
            isChecked = false
        }
        val titleInput = EditText(this).apply {
            setText(provider["title"] as? String ?: id)
            setSingleLine()
        }
        val enabledBox = CheckBox(this).apply {
            setText(R.string.ai_field_enabled)
            isChecked = provider["enabled"] == true
        }
        column.addView(titleInput)
        column.addView(baseInput)
        column.addView(keyInput)
        column.addView(clearBox)
        column.addView(enabledBox)
        column.addView(
            text(
                getString(R.string.ai_key_note, if (provider["keySet"] == true) {
                    getString(R.string.ai_key_set)
                } else {
                    getString(R.string.ai_key_missing)
                }),
                11f,
                palette.muted
            )
        )

        val canTest = provider["keySet"] == true

        AlertDialog.Builder(this)
            .setTitle(provider["title"]?.toString() ?: id)
            .setView(column)
            .setPositiveButton(R.string.ai_action_save) { _, _ ->
                applyAi({
                    it.aiSaveProviderJson(
                        Json.encode(
                            mapOf(
                                "id" to id,
                                "title" to titleInput.text.toString().trim(),
                                "baseUrl" to baseInput.text.toString().trim(),
                                "apiKey" to keyInput.text.toString().trim()
                                    .takeIf { value -> value.isNotEmpty() },
                                "clearKey" to clearBox.isChecked,
                                "enabled" to enabledBox.isChecked
                            )
                        )
                    )
                }, R.string.ai_saved)
            }
            .setNeutralButton(R.string.ai_action_test, null)
            .setNegativeButton(R.string.ai_action_remove, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        if (!canTest) {
                            toast(getString(R.string.ai_test_failed, getString(R.string.ai_key_missing)))
                            return@setOnClickListener
                        }
                        dismiss()
                        probeAiProvider(id)
                    }
                    getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        dismiss()
                        confirmRemoveProvider(id, provider["title"]?.toString() ?: id)
                    }
                }
                show()
            }
    }

    private fun showAddModelDialog() {
        val providers = (aiStatusDoc?.get("providers") as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
        if (providers.isEmpty()) {
            toast(getString(R.string.ai_provider_required))
            return
        }
        var providerId = providers.first()["id"] as? String ?: ""
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val idInput = EditText(this).apply {
            hint = getString(R.string.ai_field_id)
            setSingleLine()
        }
        val providerRow = text(getString(R.string.ai_field_provider, providerId), 13f, palette.onSurface)
            .apply {
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener {
                    val ids = providers.mapNotNull { it["id"] as? String }
                    AlertDialog.Builder(this@GromActivity)
                        .setTitle(R.string.ai_provider_pick)
                        .setItems(ids.toTypedArray()) { _, which ->
                            providerId = ids[which]
                            text = getString(R.string.ai_field_provider, providerId)
                        }
                        .show()
                }
            }
        val upstreamInput = EditText(this).apply {
            hint = getString(R.string.ai_field_upstream)
            setSingleLine()
        }
        val titleInput = EditText(this).apply {
            hint = getString(R.string.ai_field_title)
            setSingleLine()
        }
        val enabledBox = CheckBox(this).apply {
            setText(R.string.ai_field_enabled)
            isChecked = true
        }
        column.addView(idInput)
        column.addView(providerRow)
        column.addView(upstreamInput)
        column.addView(titleInput)
        column.addView(enabledBox)

        AlertDialog.Builder(this)
            .setTitle(R.string.ai_dialog_add_model)
            .setView(column)
            .setPositiveButton(R.string.ai_action_save) { _, _ ->
                val id = idInput.text.toString().trim()
                if (id.isEmpty()) {
                    toast(getString(R.string.ai_id_required))
                    return@setPositiveButton
                }
                applyAi({
                    it.aiSaveModelJson(
                        Json.encode(
                            mapOf(
                                "id" to id,
                                "provider" to providerId,
                                "upstream" to upstreamInput.text.toString().trim()
                                    .takeIf { value -> value.isNotEmpty() },
                                "title" to titleInput.text.toString().trim(),
                                "enabled" to enabledBox.isChecked
                            )
                        )
                    )
                }, R.string.ai_saved)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showModelDialog(id: String) {
        val model = (aiStatusDoc?.get("models") as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .firstOrNull { it["id"] == id } ?: return

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val upstreamInput = EditText(this).apply {
            setText(model["upstream"] as? String ?: id)
            setSingleLine()
            hint = getString(R.string.ai_field_upstream)
        }
        val titleInput = EditText(this).apply {
            setText(model["title"] as? String ?: id)
            setSingleLine()
        }
        val enabledBox = CheckBox(this).apply {
            setText(R.string.ai_field_enabled)
            isChecked = model["enabled"] == true
        }
        val tokensInput = EditText(this).apply {
            hint = getString(R.string.ai_field_max_tokens)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            (model["maxOutputTokens"] as? Number)?.let { setText(it.toString()) }
        }
        column.addView(upstreamInput)
        column.addView(titleInput)
        column.addView(tokensInput)
        column.addView(enabledBox)

        AlertDialog.Builder(this)
            .setTitle(id)
            .setView(column)
            .setPositiveButton(R.string.ai_action_save) { _, _ ->
                val tokens = tokensInput.text.toString().trim().toIntOrNull()
                applyAi({
                    it.aiSaveModelJson(
                        Json.encode(
                            mapOf(
                                "id" to id,
                                "provider" to (model["provider"] as? String),
                                "upstream" to upstreamInput.text.toString().trim()
                                    .takeIf { value -> value.isNotEmpty() },
                                "title" to titleInput.text.toString().trim(),
                                "enabled" to enabledBox.isChecked,
                                "maxOutputTokens" to tokens
                            )
                        )
                    )
                }, R.string.ai_saved)
            }
            .setNeutralButton(R.string.ai_action_set_default, null)
            .setNegativeButton(R.string.ai_action_remove, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        dismiss()
                        applyAiRefreshing(R.string.ai_default_saved) {
                            it.aiConfigureJson(Json.encode(mapOf("defaultModel" to id)))
                        }
                    }
                    getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        dismiss()
                        confirmRemoveModel(id)
                    }
                }
                show()
            }
    }

    /**
     * A mutation whose success message is set by the caller (the default
     * picker). It reports success only after the engine accepted the change —
     * the answer decides what is shown, not the tap.
     */
    private fun applyAiRefreshing(successRes: Int, block: (IGromServer) -> String) {
        Thread {
            val result = runCatching { aiCall { block(it) } }
            handler.post {
                result.onSuccess {
                    aiStatusDoc = it
                    lastAiSignature = null
                    renderAi()
                    toast(getString(successRes))
                }.onFailure { failure ->
                    toast(failure.message ?: getString(R.string.ai_saved))
                }
            }
        }.start()
    }

    private fun confirmRemoveProvider(id: String, title: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_remove_provider_title)
            .setMessage(getString(R.string.ai_remove_provider_body, title))
            .setPositiveButton(R.string.ai_action_remove) { _, _ ->
                applyAi({ it.aiRemoveProvider(id) }, R.string.ai_removed)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmRemoveModel(id: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_action_remove)
            .setMessage(getString(R.string.ai_remove_model_body, id))
            .setPositiveButton(R.string.ai_action_remove) { _, _ ->
                applyAi({ it.aiRemoveModel(id) }, R.string.ai_removed)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun buildSshSection(body: LinearLayout) {
        body.addView(text(getString(R.string.ssh_hint), 12f, palette.muted))
        body.addView(gap(8))
        sshList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(sshList)
        body.addView(gap(10))
        body.addView(
            pill(
                value = getString(R.string.ssh_add),
                textColor = palette.onSurface,
                fill = palette.background,
                rippleColor = palette.ripple,
                stroke = palette.border,
                onClick = { showTargetEditor(null) }
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )
    }


    /**
     * Devices (D70-D75).
     *
     * The identity block is what the operator compares on two screens; the peer
     * rows carry measured lamps only. Nothing here polls: the section loads once
     * when opened (and on Refresh), because a phone that pretends to watch other
     * phones around the clock would be the exact lie the lamps avoid.
     */
    private fun buildFleetSection(body: LinearLayout) {
        if (!BuildConfig.GROMSERVER) {
            body.addView(text(getString(R.string.fleet_unavailable), 12f, palette.muted))
            return
        }
        body.addView(text(getString(R.string.fleet_hint), 12f, palette.muted))
        body.addView(gap(12))

        body.addView(label(getString(R.string.fleet_this_device), palette))
        fleetIdentityLabel = text("", 15f, palette.onSurface, Typeface.BOLD)
        fleetIdentityDetail = text("", 11f, palette.muted)
        fleetIdentityNote = text("", 11f, palette.negative)
        body.addView(gap(4))
        body.addView(fleetIdentityLabel)
        body.addView(gap(2))
        body.addView(fleetIdentityDetail)
        body.addView(gap(2))
        body.addView(fleetIdentityNote)
        body.addView(gap(8))
        body.addView(
            fleetButton(R.string.fleet_copy_identity) { copyFleetIdentity() }
        )
        body.addView(gap(6))
        body.addView(
            fleetButton(R.string.fleet_reset_identity) { confirmResetIdentity() }
        )

        body.addView(divider(palette))
        body.addView(label(getString(R.string.fleet_paired_devices), palette))
        fleetList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(fleetList)
        body.addView(gap(12))
        body.addView(fleetButton(R.string.fleet_pair) { showPairPicker() })
        body.addView(gap(6))
        body.addView(fleetButton(R.string.fleet_grant) { showGrantDialog() })
        body.addView(gap(6))
        body.addView(fleetButton(R.string.fleet_refresh) { refreshFleet() })
        fleetDetail = text("", 11f, palette.muted)
        body.addView(gap(6))
        body.addView(fleetDetail)
        body.addView(gap(12))
        body.addView(text(getString(R.string.fleet_pair_warning), 11f, palette.muted))
    }

    private fun fleetButton(res: Int, onClick: () -> Unit): TextView = pill(
        value = getString(res),
        textColor = palette.onSurface,
        fill = palette.background,
        rippleColor = palette.ripple,
        stroke = palette.border,
        onClick = onClick
    ).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buildNostrSection(body: LinearLayout) {
        superNpubRow = actionRowState(getString(R.string.access_npub), palette) { showNpubDialog() }
        body.addView(superNpubRow.first)
        body.addView(divider(palette))
        superRelaysRow = actionRowState(getString(R.string.access_relays), palette) { showRelaysDialog() }
        body.addView(superRelaysRow.first)
        body.addView(divider(palette))
        superBridgeRow = actionRowState(getString(R.string.access_bridge), palette) { showBridgeDialog() }
        body.addView(superBridgeRow.first)
        body.addView(divider(palette))
        superLightningRow = actionRowState(getString(R.string.access_lightning), palette) {
            showLightningDialog()
        }
        body.addView(superLightningRow.first)
        body.addView(divider(palette))
        superExternalNodeRow = actionRowState(getString(R.string.access_external_node), palette) {
            showExternalNodeDialog()
        }
        body.addView(superExternalNodeRow.first)
        body.addView(divider(palette))
        lightningHealthRow = actionRowState(getString(R.string.lightning_health), palette) {
            probeLightning()
        }
        body.addView(lightningHealthRow.first)
    }

    private fun buildProjectsSection(body: LinearLayout) {
        body.addView(text(getString(R.string.projects_hint), 12f, palette.muted))
        body.addView(gap(8))
        projectsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(projectsList)
    }

    /**
     * Telegram alerts (D65-D67).
     *
     * The token is write-only from the screen's point of view: the row says
     * "set" or "not set" and the dialog starts empty, because the sealed value
     * is never handed back to the UI.
     */
    private fun buildAlertsSection(body: LinearLayout) {
        body.addView(text(getString(R.string.alerts_hint), 12f, palette.muted))
        body.addView(gap(8))
        alertsEnableRow = actionRowState(getString(R.string.alerts_enable), palette) {
            toggleAlerts()
        }
        body.addView(alertsEnableRow.first)
        body.addView(divider(palette))
        alertsChatRow = actionRowState(getString(R.string.alerts_chat_id), palette) {
            showChatIdDialog()
        }
        body.addView(alertsChatRow.first)
        body.addView(divider(palette))
        alertsTokenRow = actionRowState(getString(R.string.alerts_token), palette) {
            showTokenDialog()
        }
        body.addView(alertsTokenRow.first)
        body.addView(divider(palette))
        alertsTestRow = actionRowState(getString(R.string.alerts_test), palette) {
            sendTestAlert()
        }
        body.addView(alertsTestRow.first)
        body.addView(divider(palette))
        // The last delivery is the only line with a lamp: it is the only one
        // that can be measured (D67). Configuration alone stays grey.
        val state = servingRow(
            getString(R.string.alerts_last_delivery),
            getString(R.string.alerts_state_ready),
            Lamp.UNKNOWN,
            palette
        )
        alertsStateRow = state.first to state.third
        alertsLamp = state.second
        body.addView(state.first)
        body.addView(gap(6))
        alertsDetail = text("", 11f, palette.muted)
        body.addView(alertsDetail)
    }

    // ------------------------------------------------------- Telegram alerts

    private fun toggleAlerts() {
        val enabled = !alertsEnabled
        if (enabled && (!alertsTokenSet || alertsChatId.isBlank())) {
            toast(getString(R.string.toast_alerts_not_configured))
            return
        }
        saveAlerts(mapOf("enabled" to enabled, "chatId" to alertsChatId))
    }

    private fun showChatIdDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = getString(R.string.dialog_chat_hint)
            setSingleLine()
            setText(alertsChatId)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_chat_title)
            .setMessage(R.string.dialog_chat_body)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val raw = input.text.toString().trim()
                // The engine's own pattern: validation cannot drift between
                // the screen and the store.
                if (raw.isNotEmpty() && !NotifySettingsRules.CHAT_ID_PATTERN.matches(raw)) {
                    toast(getString(R.string.toast_chat_invalid))
                    return@setPositiveButton
                }
                saveAlerts(mapOf("enabled" to alertsEnabled, "chatId" to raw))
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                saveAlerts(mapOf("enabled" to false, "chatId" to ""))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * The token is never shown: the field starts empty and an empty Save keeps
     * the stored one, so the operator can edit the chat id without retyping a
     * secret the engine will not hand back.
     */
    private fun showTokenDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.dialog_token_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_token_title)
            .setMessage(R.string.dialog_token_body)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) {
                    saveAlerts(mapOf("enabled" to alertsEnabled, "chatId" to alertsChatId))
                    return@setPositiveButton
                }
                if (!NotifySettingsRules.TOKEN_PATTERN.matches(raw)) {
                    toast(getString(R.string.toast_token_invalid))
                    return@setPositiveButton
                }
                saveAlerts(
                    mapOf("enabled" to alertsEnabled, "chatId" to alertsChatId, "token" to raw)
                )
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                saveAlerts(
                    mapOf(
                        "enabled" to false,
                        "chatId" to alertsChatId,
                        "clearToken" to true
                    )
                )
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun saveAlerts(body: Map<String, Any?>) {
        val server = client.server
        if (server == null) {
            toast(getString(R.string.toast_not_bound, getString(R.string.ui_alerts)))
            return
        }
        val result = runCatching { server.notifySaveSettings(Json.encode(body)) }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            val message = (failure as? IllegalStateException)?.message ?: failure.javaClass.simpleName
            toast(getString(R.string.toast_alerts_save_failed, message))
            return
        }
        toast(getString(R.string.toast_alerts_saved))
        refreshNow()
    }

    /** Blocking send: never on the main thread, and the answer is reported. */
    private fun sendTestAlert() {
        val server = client.server
        if (server == null) {
            toast(getString(R.string.toast_not_bound, getString(R.string.alerts_test)))
            return
        }
        Thread({
            val raw = runCatching { server.notifyTestJson() }.getOrNull()
            val parsed = raw?.let { JsonParser.parseObjectOrNull(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (parsed?.get("ok") == true) {
                    toast(getString(R.string.toast_alerts_delivered))
                } else {
                    val code = parsed?.get("code") as? String
                        ?: getString(R.string.alerts_state_failed)
                    toast(getString(R.string.toast_alerts_failed, code))
                }
                refreshNow()
            }
        }, "grom-alert-test").start()
    }

    private fun buildAccessSection(body: LinearLayout) {
        superDetail = text("", 13f, palette.muted)
        superDetail.maxLines = 4
        superDetail.ellipsize = TextUtils.TruncateAt.END
        body.addView(superDetail)
        body.addView(gap(4))
        superEnableRow = actionRowState(getString(R.string.access_enable), palette) { toggleSuperEnabled() }
        body.addView(superEnableRow.first)
        body.addView(divider(palette))
        superBindRow = actionRowState(getString(R.string.access_bind), palette) { cycleBindMode() }
        body.addView(superBindRow.first)
        body.addView(divider(palette))
        superAuthRow = actionRowState(getString(R.string.access_auth), palette) { cycleAuthMode() }
        body.addView(superAuthRow.first)
        body.addView(divider(palette))
        superPasswordRow = actionRowState(getString(R.string.access_password), palette) { showPasswordDialog() }
        body.addView(superPasswordRow.first)
        body.addView(divider(palette))
        superKeysRow = actionRowState(getString(R.string.access_keys), palette) { showKeysDialog() }
        body.addView(superKeysRow.first)
        body.addView(divider(palette))
        superCopyConfigRow = actionRowState(getString(R.string.access_copy_config), palette) {
            copyConfigToClipboard()
        }
        body.addView(superCopyConfigRow.first)
    }

    private fun buildUpdateSection(body: LinearLayout) {
        updateTitle = text(
            getString(R.string.update_updates),
            20f,
            palette.onSurface,
            Typeface.BOLD
        )
        body.addView(updateTitle)
        body.addView(gap(4))
        updateDetail = text(getString(R.string.update_installed, BuildConfig.VERSION_NAME), 13f, palette.muted)
        updateDetail.maxLines = 4
        updateDetail.ellipsize = TextUtils.TruncateAt.END
        body.addView(updateDetail)
        body.addView(gap(14))
        updateAction = pill(
            value = getString(R.string.update_check_now),
            textColor = palette.onSurface,
            fill = palette.background,
            rippleColor = palette.ripple,
            stroke = palette.border,
            onClick = {
                val current = updateState
                if (current is UpdateState.Available) {
                    openUrl(current.release.apkUrl ?: current.release.htmlUrl)
                } else {
                    checkForUpdates()
                }
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(updateAction)
    }

    private fun buildInviteSection(body: LinearLayout) {
        body.addView(
            text(getString(R.string.invite_detail), 13f, palette.muted).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        body.addView(gap(12))

        val codePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(palette.background, 14, palette.border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        codePanel.addView(
            text(
                getString(R.string.invite_your_code).uppercase(java.util.Locale.ROOT),
                10f,
                palette.muted,
                Typeface.BOLD,
                letterSpacing = 0.16f
            )
        )
        codePanel.addView(gap(4))
        inviteCodeView = text(
            getString(R.string.ui_dash),
            26f,
            palette.onSurface,
            Typeface.BOLD,
            letterSpacing = 0.28f
        )
        codePanel.addView(inviteCodeView)
        codePanel.addView(gap(2))
        inviteSharesView = text("", 11f, palette.muted)
        codePanel.addView(inviteSharesView)
        body.addView(codePanel)
        body.addView(gap(12))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copy = pill(
            value = getString(R.string.invite_copy_link),
            textColor = palette.onAccent,
            fill = palette.accent,
            rippleColor = palette.ripple
        ) { copyInviteLink() }
        val share = pill(
            value = getString(R.string.invite_share),
            textColor = palette.onSurface,
            fill = palette.background,
            rippleColor = palette.ripple,
            stroke = palette.border
        ) { shareInvite() }
        actions.addView(
            copy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, dp(5), 0) }
        )
        actions.addView(
            share,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(5), 0, 0, 0) }
        )
        body.addView(actions)
        body.addView(gap(12))
        body.addView(
            text(
                getString(R.string.invite_rewards).uppercase(java.util.Locale.ROOT),
                11f,
                palette.muted,
                Typeface.BOLD,
                letterSpacing = 0.14f
            ).apply {
                background = shape(palette.background, 999, palette.border)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )
        body.addView(gap(6))
        body.addView(text(getString(R.string.invite_rewards_detail), 12f, palette.muted))
        body.addView(gap(6))
        body.addView(divider(palette))
        inviteRedeemRow = actionRowState(getString(R.string.invite_redeem), palette) {
            showRedeemDialog()
        }
        body.addView(inviteRedeemRow.first)
    }

    // ------------------------------------------------------------ super config

    private fun renderSuper() {
        val superStatus = status?.get("super") as? Map<*, *>
        val error = superStatus?.get("error") as? String
        val running = superStatus?.get("running") == true
        val address = superStatus?.get("address") as? String ?: getString(R.string.ui_dash)

        superDetail.text = when {
            error != null -> error
            !superConfig.enabled -> getString(R.string.access_off)
            running -> getString(R.string.access_listening, address)
            else -> getString(R.string.access_off)
        }
        superDetail.setTextColor(if (error != null) palette.negative else palette.muted)

        superEnableRow.second.text =
            if (superConfig.enabled) getString(R.string.super_on) else getString(R.string.super_off)
        superBindRow.second.text = when (superConfig.bind) {
            BindMode.LOOPBACK -> getString(R.string.access_bind_loopback)
            BindMode.LAN -> getString(R.string.access_bind_lan)
            BindMode.PUBLIC -> getString(R.string.access_bind_public)
        }
        superAuthRow.second.text = when (superConfig.auth) {
            AuthMode.PASSWORD -> getString(R.string.access_auth_password)
            AuthMode.KEY -> getString(R.string.access_auth_key)
            AuthMode.BOTH -> getString(R.string.access_auth_both)
        }
        superPasswordRow.second.text = if (superConfig.passwordHash != null) {
            getString(R.string.access_password_set)
        } else {
            getString(R.string.access_password_unset)
        }
        superKeysRow.second.text = if (superConfig.keys.isEmpty()) {
            getString(R.string.access_keys_none)
        } else {
            superConfig.keys.size.toString()
        }
        superLightningRow.second.text = if (superConfig.lightning.kind == LightningKind.NONE) {
            getString(R.string.access_lightning_none)
        } else {
            superConfig.lightning.kind.wireName
        }
        superNpubRow.second.text = superConfig.npub
            ?.let { Nip19.normalizeNpub(it) ?: it }
            ?.let { "${it.take(9)}\u2026${it.takeLast(5)}" }
            ?: getString(R.string.access_npub_none)
        superRelaysRow.second.text = if (superConfig.relays.isEmpty()) {
            getString(R.string.access_relays_none)
        } else {
            resources.getQuantityString(
                R.plurals.access_relays_count,
                superConfig.relays.size,
                superConfig.relays.size
            )
        }
        superBridgeRow.second.text = superConfig.remoteBridge
            ?.label
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.access_bridge_none)
        superExternalNodeRow.second.text = superConfig.lightning.externalNode
            .takeUnless { it.isEmpty() }
            ?.let { node ->
                val kind = node.kind.wireName.uppercase()
                if (node.label.isBlank()) kind else "$kind \u00b7 ${node.label}"
            }
            ?: getString(R.string.access_external_node_none)
        superCopyConfigRow.second.text = ""

        val health = superStatus?.get("lightningHealth") as? Map<*, *>
        val reachable = health?.get("reachable") as? Boolean
        lightningHealthRow.second.text = when (reachable) {
            true -> getString(R.string.lightning_reachable)
            false -> getString(R.string.lightning_unreachable)
            null -> getString(R.string.lightning_not_probed)
        }
    }

    private fun toggleSuperEnabled() {
        if (!superConfig.enabled) {
            if (!superConfig.hasCredential()) {
                toast(getString(R.string.access_credentials_required))
                showPasswordDialog()
                return
            }
            persistSuperConfig(superConfig.copy(enabled = true))
        } else {
            persistSuperConfig(superConfig.copy(enabled = false))
        }
    }

    private fun cycleBindMode() {
        val next = when (superConfig.bind) {
            BindMode.LOOPBACK -> BindMode.LAN
            BindMode.LAN -> BindMode.PUBLIC
            BindMode.PUBLIC -> BindMode.LOOPBACK
        }
        if (next != BindMode.LOOPBACK && !superConfig.hasCredential()) {
            toast(getString(R.string.access_credentials_required))
            return
        }
        if (next == BindMode.PUBLIC) toast(getString(R.string.access_public_warning))
        persistSuperConfig(
            superConfig.copy(bind = next, acknowledgePublicBind = next == BindMode.PUBLIC)
        )
    }

    private fun cycleAuthMode() {
        val next = when (superConfig.auth) {
            AuthMode.PASSWORD -> AuthMode.KEY
            AuthMode.KEY -> AuthMode.BOTH
            AuthMode.BOTH -> AuthMode.PASSWORD
        }
        if (next == AuthMode.PASSWORD && superConfig.passwordHash == null) {
            toast(getString(R.string.access_credentials_required))
            return
        }
        if (next == AuthMode.KEY && superConfig.keys.isEmpty()) {
            toast(getString(R.string.access_credentials_required))
            return
        }
        persistSuperConfig(superConfig.copy(auth = next))
    }

    private fun persistSuperConfig(updated: SuperServerConfig) {
        val previous = superConfig
        superConfig = updated
        try {
            superStore.save(updated)
        } catch (_: Exception) {
            superConfig = previous
            toast(getString(R.string.access_reload_failed))
            renderSuper()
            return
        }
        val reloaded = try {
            client.call(false) {
                it.reloadSuperServer()
                true
            }
        } catch (failure: IllegalStateException) {
            toast(failure.message ?: getString(R.string.access_reload_failed))
            false
        }
        toast(getString(if (reloaded) R.string.access_saved else R.string.access_reload_failed))
        handler.postDelayed({ refreshNow() }, 300)
        renderSuper()
    }

    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.dialog_password_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_password_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val value = input.text.toString()
                if (value.length < PasswordHash.MIN_LENGTH) {
                    toast(getString(R.string.dialog_password_too_short))
                    return@setPositiveButton
                }
                persistSuperConfig(superConfig.copy(passwordHash = PasswordHash.create(value).value))
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                persistSuperConfig(superConfig.copy(passwordHash = null))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showKeysDialog() {
        val summary = if (superConfig.keys.isEmpty()) {
            getString(R.string.dialog_key_none)
        } else {
            superConfig.keys.joinToString("\n") { "${it.keyId}  ${it.label}" }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_key_title)
            .setMessage(summary)
            .setPositiveButton(R.string.dialog_key_generate) { _, _ -> generateApiKey() }
            .setNeutralButton(R.string.dialog_key_remove) { _, _ ->
                persistSuperConfig(superConfig.copy(keys = emptyList()))
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun generateApiKey() {
        val seed = Ed25519.generatePrivateSeed()
        val publicKey = Ed25519.derivePublicKey(seed)
        val keyId = Ed25519.keyId(publicKey)
        val encoded = Base64.getEncoder().encodeToString(publicKey)
        val key = ApiKeyConfig(keyId = keyId, label = "operator", publicKey = encoded)
        persistSuperConfig(superConfig.copy(keys = superConfig.keys + key))
        showSeedDialog(seed, keyId)
    }

    private fun showSeedDialog(seed: ByteArray, keyId: String) {
        val seedText = Base64.getUrlEncoder().withoutPadding().encodeToString(seed)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_key_seed_title)
            .setMessage(getString(R.string.dialog_key_seed_body, seedText, keyId))
            .setPositiveButton(R.string.dialog_key_copy) { _, _ ->
                copyText(seedText, R.string.toast_key_copied)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun showLightningDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val url = EditText(this).apply {
            hint = getString(R.string.dialog_lightning_url)
            setText(superConfig.lightning.baseUrl)
            setSingleLine()
        }
        val apiKey = EditText(this).apply {
            hint = getString(R.string.dialog_lightning_key)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        container.addView(url)
        container.addView(apiKey)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_lightning_title)
            .setMessage(R.string.dialog_lightning_hint)
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val base = url.text.toString().trim()
                if (!base.startsWith("https://")) {
                    toast(getString(R.string.toast_https_required))
                    return@setPositiveButton
                }
                val typed = apiKey.text.toString()
                val sealed = if (typed.isNotBlank()) {
                    secretBox.seal(typed)
                } else {
                    superConfig.lightning.apiKeySealed
                }
                persistSuperConfig(
                    superConfig.copy(
                        lightning = LightningSettings(
                            kind = LightningKind.LNBITS,
                            baseUrl = base,
                            apiKeySealed = sealed
                        )
                    )
                )
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                persistSuperConfig(superConfig.copy(lightning = LightningSettings()))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun probeLightning() {
        val server = client.server
        if (server == null) {
            toast(getString(R.string.toast_not_bound, getString(R.string.lightning_probe_now)))
            return
        }
        toast(getString(R.string.lightning_probing))
        Thread({
            val raw = runCatching { server.lightningProbeJson() }.getOrNull()
            val parsed = raw?.let { JsonParser.parseObjectOrNull(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (parsed?.get("ok") == true) {
                    toast(getString(R.string.lightning_probe_ok))
                } else {
                    val code = parsed?.get("code") as? String
                        ?: parsed?.get("message") as? String
                        ?: getString(R.string.lightning_not_probed)
                    toast(getString(R.string.lightning_probe_failed, code))
                }
                refreshNow()
            }
        }, "grom-lightning-probe").start()
    }

    private fun showNpubDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.dialog_npub_hint)
            setSingleLine()
            setText(superConfig.npub ?: "")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_npub_title)
            .setMessage(R.string.dialog_npub_body)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) {
                    persistSuperConfig(superConfig.copy(npub = null))
                    return@setPositiveButton
                }
                val normalized = Nip19.normalizeNpub(raw)
                if (normalized == null) {
                    toast(getString(R.string.toast_npub_invalid))
                    return@setPositiveButton
                }
                persistSuperConfig(superConfig.copy(npub = normalized))
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                persistSuperConfig(superConfig.copy(npub = null))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showRelaysDialog() {
        val working = ArrayList(superConfig.relays)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_relay_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        column.addView(listView)
        column.addView(gap(8))
        column.addView(input)

        fun renderList() {
            listView.removeAllViews()
            if (working.isEmpty()) {
                listView.addView(text(getString(R.string.access_relays_none), 12f, palette.muted))
                return
            }
            for (relay in working.toList()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(
                    text(relay, 11f, palette.onSurface).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                row.addView(
                    text(getString(R.string.dialog_relay_copy), 11f, palette.muted).apply {
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        setOnClickListener { copyText(relay, R.string.toast_relay_copied) }
                    }
                )
                row.addView(
                    text(getString(R.string.dialog_relay_remove), 11f, palette.negative).apply {
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        setOnClickListener {
                            working.remove(relay)
                            renderList()
                        }
                    }
                )
                listView.addView(row)
            }
        }
        renderList()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_relay_title)
            .setMessage(R.string.dialog_relay_body)
            .setView(column)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val normalized = ArrayList<String>()
                for (relay in working) {
                    RelayUrl.normalizeRelay(relay)?.let { if (it !in normalized) normalized.add(it) }
                }
                persistSuperConfig(superConfig.copy(relays = normalized))
            }
            .setNeutralButton(R.string.dialog_relay_add, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setOnClickListener
                val normalized = RelayUrl.normalizeRelay(raw)
                if (normalized == null) {
                    toast(getString(R.string.toast_relay_invalid))
                    return@setOnClickListener
                }
                if (working.any { RelayUrl.normalizeRelay(it) == normalized }) {
                    toast(getString(R.string.toast_relay_duplicate))
                    return@setOnClickListener
                }
                if (working.size >= RelayUrl.MAX_RELAYS) {
                    toast(
                        resources.getQuantityString(
                            R.plurals.toast_relay_limit,
                            RelayUrl.MAX_RELAYS,
                            RelayUrl.MAX_RELAYS
                        )
                    )
                    return@setOnClickListener
                }
                working.add(normalized)
                input.setText("")
                renderList()
            }
        }
        dialog.show()
    }

    private fun showBridgeDialog() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.dialog_bridge_label)
            setSingleLine()
            setText(superConfig.remoteBridge?.label ?: "")
        }
        val descriptionInput = EditText(this).apply {
            hint = getString(R.string.dialog_bridge_description)
            setSingleLine()
            setText(superConfig.remoteBridge?.description ?: "")
        }
        column.addView(labelInput)
        column.addView(descriptionInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_bridge_title)
            .setMessage(R.string.dialog_bridge_body)
            .setView(column)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val hint = RemoteBridgeHint(
                    label = labelInput.text.toString(),
                    description = descriptionInput.text.toString()
                ).normalized()
                if (hint.isEmpty()) {
                    persistSuperConfig(superConfig.copy(remoteBridge = null))
                    return@setPositiveButton
                }
                if (hint.validate() != null) {
                    toast(getString(R.string.toast_bridge_invalid))
                    return@setPositiveButton
                }
                persistSuperConfig(superConfig.copy(remoteBridge = hint))
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                persistSuperConfig(superConfig.copy(remoteBridge = null))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * A reference is not a connection (D63): Grom stores the kind, a label and a
     * host hint, and never opens a gRPC, NWC or socket connection to the node.
     */
    private fun showExternalNodeDialog() {
        val existing = superConfig.lightning.externalNode
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val kindInput = EditText(this).apply {
            hint = getString(R.string.dialog_external_node_kind)
            setSingleLine()
            setText(if (existing.kind == ExternalNodeKind.NONE) "" else existing.kind.wireName)
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.dialog_external_node_label)
            setSingleLine()
            setText(existing.label)
        }
        val endpointInput = EditText(this).apply {
            hint = getString(R.string.dialog_external_node_endpoint)
            setSingleLine()
            setText(existing.endpointHint)
        }
        column.addView(kindInput)
        column.addView(labelInput)
        column.addView(endpointInput)
        column.addView(
            text(getString(R.string.dialog_external_node_note), 11f, palette.muted)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_external_node_title)
            .setMessage(R.string.dialog_external_node_body)
            .setView(column)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val kindText = kindInput.text.toString().trim()
                val kind = if (kindText.isEmpty()) {
                    ExternalNodeKind.NONE
                } else {
                    ExternalNodeKind.entries.firstOrNull { it.wireName == kindText.lowercase() }
                }
                if (kind == null) {
                    toast(getString(R.string.toast_external_node_invalid))
                    return@setPositiveButton
                }
                val node = ExternalNodeRef(
                    kind = kind,
                    label = labelInput.text.toString().trim(),
                    endpointHint = endpointInput.text.toString().trim()
                )
                if (node.isEmpty()) {
                    persistSuperConfig(
                        superConfig.copy(
                            lightning = superConfig.lightning.copy(externalNode = ExternalNodeRef())
                        )
                    )
                    return@setPositiveButton
                }
                if (node.validate() != null) {
                    toast(getString(R.string.toast_external_node_invalid))
                    return@setPositiveButton
                }
                persistSuperConfig(
                    superConfig.copy(
                        lightning = superConfig.lightning.copy(externalNode = node)
                    )
                )
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                persistSuperConfig(
                    superConfig.copy(
                        lightning = superConfig.lightning.copy(externalNode = ExternalNodeRef())
                    )
                )
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun copyConfigToClipboard() {
        val superStatus = status?.get("super") as? Map<*, *>
        val lightningRaw = superStatus?.get("lightningHealth") as? Map<*, *>
        val lightning: Map<String, Any?> = lightningRaw?.entries
            ?.filter { it.key is String }
            ?.associate { it.key as String to it.value }
            ?: emptyMap()
        val projects = (status?.get("projects") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.map { it.entries.filter { entry -> entry.key is String }
                .associate { entry -> entry.key as String to entry.value } }
            ?: emptyList()
        val bridge = (superStatus?.get("bridge") as? Map<*, *>)
            ?.entries
            ?.filter { it.key is String }
            ?.associate { it.key as String to it.value }
            ?: emptyMap()
        val notifications = (status?.get("notifications") as? Map<*, *>)
            ?.entries
            ?.filter { it.key is String }
            ?.associate { it.key as String to it.value }
            ?: emptyMap()
        copyText(
            ConfigExport.render(superConfig, lightning, projects, bridge, notifications),
            R.string.toast_config_copied
        )
    }

    private fun copyText(value: String, toastRes: Int) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            toast(getString(R.string.toast_invite_copy_failed))
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("grom", value))
        toast(getString(toastRes))
    }

    // -------------------------------------------------------------- SSH bridge

    // -------------------------------------------------------------- Doctor

    /**
     * Measures again. The probe is synchronous (a handful of binder calls), so it
     * runs on a worker thread and never on the main thread; the same call is made
     * when the section is opened, and never in the background (D74, D80).
     */
    private fun refreshDoctor() {
        if (!BuildConfig.GROMSERVER || !connected) {
            renderDoctor()
            return
        }
        Thread {
            val document = client.call(null) {
                JsonParser.parseObjectOrNull(it.doctorJson())
            }
            handler.post {
                if (document != null) {
                    doctorDocument = document
                    doctorMeasuredAtMs = (document["measuredAtMs"] as? Number)?.toLong() ?: 0L
                }
                renderDoctor()
            }
        }.start()
    }

    /**
     * Paints the last measurement. It never invents a row: with no document every
     * check stays grey and the headline says "not measured", because that is
     * exactly what Grom knows when the Binder is not bound.
     */
    private fun renderDoctor() {
        if (!BuildConfig.GROMSERVER || !::doctorVerdict.isInitialized) return

        val verdict = doctorDocument?.get("verdict") as? String
        val rows = DoctorModel.rows(doctorDocument)
        val facts = DoctorModel.facts(
            doctorDocument,
            getString(R.string.doctor_level_unknown)
        )
        val signature = buildString {
            append(verdict)
            for (row in rows) append('|').append(row.id).append(':').append(row.lamp)
            for (fact in facts) append('|').append(fact.valueText)
            append('|').append(connected)
        }
        if (signature == lastDoctorSignature) return
        lastDoctorSignature = signature

        repaintLamp(doctorVerdictLamp, DoctorModel.verdictLamp(verdict), palette)
        doctorVerdict.text = getString(DoctorModel.verdictRes(verdict))
        doctorVerdict.setTextColor(
            when (DoctorModel.verdictLamp(verdict)) {
                Lamp.ON -> palette.positive
                Lamp.OFF -> palette.negative
                Lamp.UNKNOWN -> palette.muted
            }
        )
        val attention = DoctorModel.attentionCount(doctorDocument)
        val unknown = DoctorModel.unknownCount(doctorDocument)
        val age = DoctorModel.relativeAge(
            doctorMeasuredAtMs,
            getString(R.string.doctor_level_unknown)
        )
        doctorSubtitle.text = when {
            !connected -> getString(R.string.doctor_not_running)
            doctorMeasuredAtMs <= 0L -> ""
            attention > 0 -> resources.getQuantityString(
                R.plurals.doctor_measured_at_attention,
                attention,
                age,
                attention
            )
            unknown > 0 -> resources.getQuantityString(
                R.plurals.doctor_measured_at_unknown,
                unknown,
                age,
                unknown
            )
            else -> getString(R.string.doctor_measured_at, age)
        }

        doctorChecks.removeAllViews()
        for (row in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            line.addView(lampView(row.lamp, palette))
            line.addView(gap(8))
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(text(getString(row.titleRes), 14f, palette.onSurface))
            if (row.detailRes != 0) {
                column.addView(gap(2))
                column.addView(text(getString(row.detailRes), 11f, palette.muted))
            }
            line.addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            line.addView(
                text(
                    getString(row.valueRes),
                    12f,
                    when (row.lamp) {
                        Lamp.ON -> palette.positive
                        Lamp.OFF -> palette.negative
                        Lamp.UNKNOWN -> palette.muted
                    },
                    gravity = Gravity.END
                )
            )
            doctorChecks.addView(line)
            doctorChecks.addView(divider(palette))
        }

        doctorFacts.removeAllViews()
        if (facts.isEmpty()) {
            doctorFacts.addView(text(getString(R.string.doctor_verdict_nothing_measured), 12f, palette.muted))
        } else {
            for (fact in facts) {
                doctorFacts.addView(factLine(fact))
            }
        }

        doctorDetail.text = when {
            connected -> ""
            else -> disconnectReason?.let { getString(R.string.detail_ipc_reason, it) }
                ?: getString(R.string.detail_ipc_unbound)
        }
    }

    /** One "Right now" line: no lamp on purpose (see [DoctorModel]). */
    private fun factLine(fact: FactsRow): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        addView(
            text(getString(fact.labelRes), 12f, palette.muted),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(text(fact.valueText, 12f, palette.onSurface, gravity = Gravity.END))
    }

    /**
     * Opens a system settings page. A vendor ROM without that page throws
     * `ActivityNotFoundException`; the screen says so instead of crashing.
     */
    private fun openSystemSettings(action: String, extra: Pair<String, String>? = null, data: String? = null) {
        val intent = Intent(action)
        if (extra != null) intent.putExtra(extra.first, extra.second)
        if (data != null) intent.data = Uri.parse(data)
        try {
            startActivity(intent)
        } catch (failure: Exception) {
            toast(getString(R.string.doctor_open_failed))
        }
    }

    /**
     * The one system change this screen is allowed to *request* (D82). The
     * dialog is Android's own; Grom never sets the exemption itself, and the
     * confirmation explains that before the request is made.
     *
     * `BatteryLife` is suppressed rather than hidden in a baseline: the lint rule
     * says this permission needs a Play-acceptable use case, and Grom is not
     * distributed through Play (it is installed by hand, `docs/PROVISION.md`).
     * A foreground server that Android freezes is exactly the use case the
     * exemption exists for, and the request is one tap in the platform's dialog,
     * not a silent write.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun confirmBatteryExemption() {
        AlertDialog.Builder(this)
            .setTitle(R.string.doctor_optimise_title)
            .setMessage(R.string.doctor_optimise_body)
            .setNegativeButton(R.string.doctor_optimise_cancel, null)
            .setPositiveButton(R.string.doctor_optimise_continue) { _, _ ->
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (failure: Exception) {
                    // No such dialog on this ROM: fall back to the list, which
                    // at least lets the operator find Grom by hand.
                    openSystemSettings(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    )
                }
            }
            .show()
    }

    private fun renderSsh() {
        if (!::sshList.isInitialized) return
        val signature = buildString {
            append(sshAvailable)
            for (target in sshTargets) {
                append('|').append(target["id"]).append(':').append(target["state"])
            }
        }
        if (signature == lastSshSignature) return
        lastSshSignature = signature
        sshList.removeAllViews()

        if (!sshAvailable) {
            sshList.addView(text(getString(R.string.ssh_unavailable), 12f, palette.negative))
            return
        }
        if (sshTargets.isEmpty()) {
            sshList.addView(text(getString(R.string.ssh_none), 12f, palette.muted))
            return
        }
        for (target in sshTargets) {
            sshList.addView(sshRow(target))
            sshList.addView(divider(palette))
        }
        // Honest capacity note: saved servers are unlimited, live tunnels are
        // not, because each one costs a thread, a socket and an SSH session.
        if (sshTargets.size > SshTarget.ADVISORY_TARGETS) {
            sshList.addView(
                text(
                    resources.getQuantityString(
                        R.plurals.ssh_advisory,
                        sshTargets.size,
                        sshTargets.size,
                        SshTarget.MAX_ACTIVE
                    ),
                    11f,
                    palette.muted
                )
            )
        }
    }


    /**
     * Paints the fleet from the last measurement. It never calls the network:
     * [refreshFleet] does that on a worker thread and hands the result here.
     */
    private fun renderFleet() {
        if (!BuildConfig.GROMSERVER || !::fleetList.isInitialized) return

        val fromStatus = status?.get("fleet") as? Map<*, *>
        if (fleetIdentityDoc == null && fromStatus != null) fleetIdentityDoc = fromStatus

        val signature = buildString {
            append(fleetIdentityDoc?.get("nodeId"))
            append('|').append(fleetPeers.size)
            for (peer in fleetPeers) {
                append('|').append(peer["nodeId"]).append(':').append(peer["state"])
            }
        }
        if (signature == lastFleetSignature) return
        lastFleetSignature = signature

        val identity = fleetIdentityDoc
        val nodeId = identity?.get("nodeId") as? String
        val identityLabel = identity?.get("label") as? String
        if (nodeId.isNullOrEmpty()) {
            fleetIdentityLabel.text = getString(R.string.fleet_identity_missing)
            fleetIdentityDetail.text = ""
            fleetIdentityNote.text = if (fleetLoaded) {
                getString(R.string.fleet_identity_error)
            } else {
                ""
            }
        } else {
            fleetIdentityLabel.text = identityLabel ?: getString(R.string.ui_wordmark)
            fleetIdentityDetail.text = getString(
                R.string.fleet_identity_detail,
                nodeId,
                FleetModel.fingerprint(nodeId)
            )
            fleetIdentityNote.text = ""
        }

        fleetList.removeAllViews()
        if (fleetPeers.isEmpty()) {
            fleetList.addView(text(getString(R.string.fleet_none), 12f, palette.muted))
        } else {
            for (peer in fleetPeers) {
                fleetList.addView(fleetRow(peer))
                fleetList.addView(divider(palette))
            }
        }
    }

    private fun fleetRow(peer: Map<String, Any?>): View {
        val dot = lampView(FleetModel.lampOf(peer), palette)
        val state = peer["state"] as? String
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            text(
                (peer["label"] as? String).orEmpty(),
                15f,
                palette.onSurface,
                Typeface.BOLD
            )
        )
        column.addView(
            text(
                if ((peer["address"] as? String).isNullOrEmpty()) {
                    getString(R.string.fleet_inbound_only)
                } else {
                    getString(R.string.fleet_address, (peer["address"] as? String).orEmpty())
                },
                11f,
                palette.muted
            )
        )
        column.addView(
            text(getString(FleetModel.stateRes(state)), 11f, stateColour(state ?: ""))
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { showPeerDialog(peer) }
            addView(dot)
            addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(text("\u203A", 20f, palette.muted))
        }
    }

    private fun fleetDoc(block: (IGromServer) -> String): Map<String, Any?> {
        val server = client.server
            ?: throw IllegalStateException(getString(R.string.toast_not_bound, getString(R.string.ui_fleet)))
        return JsonParser.parseObjectOrNull(block(server))
            ?: throw IllegalStateException("the engine returned no document")
    }

    /**
     * Runs the probes now. The engine never schedules them, so this is the only
     * place a peer lamp can change.
     */
    private fun refreshFleet() {
        if (!connected) {
            toast(getString(R.string.toast_not_bound, getString(R.string.ui_fleet)))
            return
        }
        if (fleetRefreshing) return
        fleetRefreshing = true
        if (::fleetDetail.isInitialized) fleetDetail.text = getString(R.string.fleet_checking)
        Thread {
            val identity = runCatching { fleetDoc { it.nodeIdentityJson() } }.getOrNull()
            val result = runCatching { fleetDoc { it.peerRefreshJson() } }.getOrNull()
            val peers = extractPeers(result)
            handler.post {
                fleetRefreshing = false
                if (identity != null) fleetIdentityDoc = identity
                fleetPeers = peers
                fleetLoaded = true
                lastFleetSignature = null
                render()
                if (result != null && ::fleetDetail.isInitialized) {
                    val checked = (result["refreshed"] as? Number)?.toInt() ?: 0
                    val total = (result["total"] as? Number)?.toInt() ?: peers.size
                    fleetDetail.text = getString(R.string.fleet_refreshed, checked, total)
                }
            }
        }.start()
    }

    /**
     * The engine's pairing codes are stable; two of them mean the same thing to
     * an operator (the public API is not reachable), so they get one sentence
     * instead of a raw `GROM_E_` prefix.
     */
    private fun fleetFailure(failure: Throwable): String {
        val message = failure.message.orEmpty()
        return when {
            message.contains("PAIR_DATA_PLANE_STOPPED") ||
                message.contains("PAIR_LOOPBACK_ONLY") ->
                getString(R.string.fleet_data_plane_off)
            message.contains("PAIR_KEY_AUTH_REQUIRED") ->
                getString(R.string.fleet_key_auth_required)
            else -> message.ifEmpty { getString(R.string.fleet_pair) }
        }
    }

    private fun extractPeers(document: Map<String, Any?>?): List<Map<String, Any?>> =
        (document?.get("peers") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.map { raw ->
                raw.entries.filter { it.key is String }
                    .associate { it.key as String to it.value }
            }
            ?: emptyList()

    private fun copyFleetIdentity() {
        val identity = fleetIdentityDoc ?: return
        val nodeId = (identity["nodeId"] as? String).orEmpty()
        if (nodeId.isEmpty()) return
        copyText(
            "Grom device\n${identity["label"]}\n$nodeId\n${FleetModel.fingerprint(nodeId)}",
            R.string.fleet_identity_copied
        )
    }

    private fun confirmResetIdentity() {
        val input = EditText(this).apply {
            hint = getString(R.string.fleet_identity_reset_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.fleet_identity_reset_title)
            .setMessage(R.string.fleet_identity_reset_body)
            .setView(input)
            .setPositiveButton(R.string.fleet_identity_reset_action) { _, _ ->
                val answer = input.text.toString().trim()
                try {
                    fleetDoc { it.nodeResetIdentityJson(answer) }
                    fleetIdentityDoc = null
                    fleetPeers = emptyList()
                    fleetLoaded = false
                    lastFleetSignature = null
                    toast(getString(R.string.fleet_identity_reset_done))
                    refreshNow()
                } catch (failure: Exception) {
                    toast(failure.message ?: getString(R.string.fleet_identity_reset_action))
                }
            }
            .setNegativeButton(R.string.fleet_cancel, null)
            .show()
    }

    /**
     * One-way scoped trust (D77). The operator pastes a reader's public key and
     * chooses what it may read; there is no code and no return address, so the
     * key can only ever call in. The engine derives the key id, so a pasted key
     * cannot claim to be a device that was paired before.
     */
    private fun showGrantDialog() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val keyInput = EditText(this).apply {
            hint = getString(R.string.fleet_grant_key)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.fleet_grant_label)
            setSingleLine()
        }
        val statusBox = CheckBox(this).apply {
            setText(R.string.fleet_grant_scope_status)
            isChecked = true
        }
        val configBox = CheckBox(this).apply {
            setText(R.string.fleet_grant_scope_config)
            isChecked = false
        }
        // Services are what an application uses Grom for (D92). They are off by
        // default and callable only for the operations a service publishes.
        val servicesReadBox = CheckBox(this).apply {
            setText(R.string.fleet_grant_scope_services_read)
            isChecked = false
        }
        val servicesCallBox = CheckBox(this).apply {
            setText(R.string.fleet_grant_scope_services_call)
            isChecked = false
        }
        // The AI gateway is a separate grant (D94): reading the catalogue and
        // spending the operator's credit are two decisions, so they are two
        // checkboxes and never implied by "call services".
        val aiReadBox = CheckBox(this).apply {
            setText(R.string.fleet_grant_scope_ai_read)
            isChecked = false
        }
        val aiChatBox = CheckBox(this).apply {
            setText(R.string.fleet_grant_scope_ai_chat)
            isChecked = false
        }
        column.addView(keyInput)
        column.addView(labelInput)
        column.addView(statusBox)
        column.addView(configBox)
        column.addView(servicesReadBox)
        column.addView(servicesCallBox)
        column.addView(aiReadBox)
        column.addView(aiChatBox)
        AlertDialog.Builder(this)
            .setTitle(R.string.fleet_grant_title)
            .setMessage(R.string.fleet_grant_body)
            .setView(column)
            .setPositiveButton(R.string.fleet_grant) { _, _ ->
                val publicKey = keyInput.text.toString().trim()
                if (publicKey.isEmpty()) {
                    toast(getString(R.string.fleet_grant_key_required))
                    return@setPositiveButton
                }
                val scopes = buildList {
                    if (statusBox.isChecked) add("status.read")
                    if (configBox.isChecked) add("config.read")
                    if (servicesReadBox.isChecked) add("services.read")
                    if (servicesCallBox.isChecked) add("services.call")
                    if (aiReadBox.isChecked) add("ai.read")
                    if (aiChatBox.isChecked) add("ai.chat")
                }
                if (scopes.isEmpty()) {
                    toast(getString(R.string.fleet_grant_scope_required))
                    return@setPositiveButton
                }
                try {
                    fleetDoc {
                        it.peerGrantJson(
                            Json.encode(
                                mapOf(
                                    "publicKey" to publicKey,
                                    "label" to labelInput.text.toString().trim(),
                                    "scopes" to scopes
                                )
                            )
                        )
                    }
                    toast(getString(R.string.fleet_granted))
                    refreshFleet()
                } catch (failure: Exception) {
                    toast(failure.message ?: getString(R.string.fleet_grant))
                }
            }
            .setNegativeButton(R.string.fleet_cancel, null)
            .show()
    }

    private fun showPairPicker() {
        AlertDialog.Builder(this)
            .setTitle(R.string.fleet_pair_pick)
            .setItems(
                arrayOf(
                    getString(R.string.fleet_pair_show),
                    getString(R.string.fleet_pair_join)
                )
            ) { _, which ->
                if (which == 0) showShowCodeDialog() else showJoinDialog()
            }
            .show()
    }

    private fun showShowCodeDialog() {
        val started = try {
            fleetDoc { it.peerPairStartJson() }
        } catch (failure: Exception) {
            toast(fleetFailure(failure))
            return
        }
        val code = started["code"] as? String ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.fleet_code_title)
            .setMessage(getString(R.string.fleet_code_body, code))
            .setPositiveButton(R.string.fleet_code_check) { _, _ -> checkPendingPairing() }
            .setNegativeButton(R.string.fleet_cancel, null)
            .show()
    }

    private fun checkPendingPairing() {
        val pending = try {
            (fleetDoc { it.peerPairPendingJson() }["pending"] as? Map<*, *>)
        } catch (failure: Exception) {
            toast(fleetFailure(failure))
            return
        }
        val nodeId = pending?.get("nodeId") as? String
        if (pending == null || nodeId.isNullOrEmpty()) {
            toast(getString(R.string.fleet_code_none))
            return
        }
        val label = (pending["label"] as? String).orEmpty()
        val fingerprint = (pending["fingerprint"] as? String)
            ?: FleetModel.fingerprint(nodeId)
        val address = (pending["address"] as? String).orEmpty()
        val scopes = (pending["requestedScopes"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?: emptyList()
        AlertDialog.Builder(this)
            .setTitle(R.string.fleet_pending_title)
            .setMessage(
                getString(
                    R.string.fleet_pending_body,
                    label,
                    fingerprint,
                    address,
                    scopes.joinToString(", ")
                )
            )
            .setPositiveButton(R.string.fleet_approve) { _, _ ->
                completePairing(accept = true, scopes = scopes)
            }
            .setNegativeButton(R.string.fleet_reject) { _, _ ->
                completePairing(accept = false, scopes = emptyList())
            }
            .show()
    }

    private fun completePairing(accept: Boolean, scopes: List<String>) {
        try {
            fleetDoc {
                it.peerPairCompleteJson(
                    Json.encode(mapOf("accept" to accept, "scopes" to scopes))
                )
            }
            toast(
                getString(if (accept) R.string.fleet_approved else R.string.fleet_rejected)
            )
            if (accept) refreshFleet()
        } catch (failure: Exception) {
            toast(failure.message ?: getString(R.string.fleet_pair))
        }
    }

    private fun showJoinDialog() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val addressInput = EditText(this).apply {
            hint = getString(R.string.fleet_join_address)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val codeInput = EditText(this).apply {
            hint = getString(R.string.fleet_join_code)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        column.addView(addressInput)
        column.addView(codeInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.fleet_join_title)
            .setMessage(R.string.fleet_pair_warning)
            .setView(column)
            .setPositiveButton(R.string.fleet_join_action) { _, _ ->
                val address = addressInput.text.toString().trim()
                val code = codeInput.text.toString().trim().uppercase()
                if (address.isEmpty() || code.isEmpty()) {
                    toast(getString(R.string.fleet_join_address))
                    return@setPositiveButton
                }
                joinDevice(address, code)
            }
            .setNegativeButton(R.string.fleet_cancel, null)
            .show()
    }

    private fun joinDevice(address: String, code: String) {
        toast(getString(R.string.fleet_join_waiting))
        Thread {
            val result = runCatching {
                fleetDoc {
                    it.peerJoinJson(
                        Json.encode(mapOf("address" to address, "code" to code))
                    )
                }
            }
            handler.post {
                val document = result.getOrNull()
                if (document == null) {
                    toast(
                        result.exceptionOrNull()?.let { fleetFailure(it) }
                            ?: getString(R.string.fleet_join_action)
                    )
                    return@post
                }
                val peer = document["peer"] as? Map<*, *>
                val nodeId = peer?.get("nodeId") as? String
                if (peer == null || nodeId.isNullOrEmpty()) {
                    toast(getString(R.string.fleet_join_action))
                    return@post
                }
                showConfirmJoin(nodeId, peer)
            }
        }.start()
    }

    private fun showConfirmJoin(nodeId: String, peer: Map<*, *>) {
        val fingerprint = (peer["fingerprint"] as? String)
            ?: FleetModel.fingerprint(nodeId)
        val label = (peer["label"] as? String).orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.fleet_confirm_title)
            .setMessage(getString(R.string.fleet_confirm_body, fingerprint, label))
            .setPositiveButton(R.string.fleet_confirm) { _, _ ->
                try {
                    fleetDoc {
                        it.peerConfirmJson(Json.encode(mapOf("nodeId" to nodeId)))
                    }
                    toast(getString(R.string.fleet_approved))
                    refreshFleet()
                } catch (failure: Exception) {
                    toast(failure.message ?: getString(R.string.fleet_confirm))
                }
            }
            .setNegativeButton(R.string.fleet_cancel, null)
            .show()
    }

    private fun showPeerDialog(peer: Map<String, Any?>) {
        val nodeId = peer["nodeId"] as? String ?: return
        val label = (peer["label"] as? String).orEmpty()
        val scopes = FleetModel.scopesText(peer)
            .ifEmpty { getString(R.string.fleet_none_scopes) }
        val message = buildString {
            append(FleetModel.fingerprint(nodeId)).append('\n')
            val address = peer["address"] as? String
            append(
                if (address.isNullOrEmpty()) {
                    getString(R.string.fleet_inbound_only)
                } else {
                    getString(R.string.fleet_address, address)
                }
            ).append('\n')
            append(getString(R.string.fleet_scopes, scopes)).append('\n')
            append(getString(FleetModel.stateRes(peer["state"] as? String)))
            val error = peer["lastError"] as? String
            if (!error.isNullOrEmpty()) {
                append('\n').append(getString(R.string.fleet_last_error, error))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(label.ifEmpty { getString(R.string.fleet_paired_devices) })
            .setMessage(message)
            .setNeutralButton(R.string.fleet_refresh) { _, _ -> refreshFleet() }
            .setNegativeButton(R.string.ui_close, null)
            .setPositiveButton(R.string.fleet_remove) { _, _ ->
                confirmRemovePeer(nodeId, label)
            }
            .show()
    }

    private fun confirmRemovePeer(nodeId: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.fleet_remove_title)
            .setMessage(getString(R.string.fleet_remove_body, label.ifEmpty { nodeId }))
            .setPositiveButton(R.string.fleet_remove) { _, _ ->
                try {
                    invoke(getString(R.string.fleet_remove)) { it.peerRemove(nodeId) }
                    fleetPeers = fleetPeers.filter { it["nodeId"] != nodeId }
                    lastFleetSignature = null
                    toast(getString(R.string.fleet_removed))
                    refreshNow()
                } catch (failure: Exception) {
                    toast(failure.message ?: getString(R.string.fleet_remove))
                }
            }
            .setNegativeButton(R.string.fleet_cancel, null)
            .show()
    }

    private fun sshRow(target: Map<String, Any?>): View {
        val state = (target["state"] as? String) ?: "disconnected"
        val lamp = when (state) {
            "connected" -> Lamp.ON
            "connecting" -> Lamp.UNKNOWN
            "failed" -> Lamp.OFF
            else -> Lamp.UNKNOWN
        }
        val dot = lampView(lamp, palette)
        val labelText = (target["label"] as? String) ?: (target["id"] as? String) ?: ""
        val user = (target["username"] as? String) ?: ""
        val host = (target["host"] as? String) ?: ""
        val port = (target["port"] as? Number)?.toInt() ?: 22

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(labelText, 15f, palette.onSurface, Typeface.BOLD))
        column.addView(
            text(
                getString(R.string.ssh_row_detail, user, host, port),
                12f,
                palette.muted
            )
        )
        column.addView(text(sshStateLabel(state), 11f, stateColour(state)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { showTargetDialog(target) }
            addView(dot)
            addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(text("\u203A", 20f, palette.muted))
        }
        return row
    }

    private fun sshStateLabel(state: String): String = when (state) {
        "connected" -> getString(R.string.ssh_state_connected)
        "connecting" -> getString(R.string.ssh_state_connecting)
        "failed" -> getString(R.string.ssh_state_failed)
        else -> getString(R.string.ssh_state_disconnected)
    }

    private fun stateColour(state: String): Int = when (state) {
        "connected" -> palette.positive
        "failed" -> palette.negative
        else -> palette.muted
    }

    private fun showTargetDialog(target: Map<String, Any?>) {
        val id = target["id"] as? String ?: return
        val connected = target["state"] == "connected"
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val user = (target["username"] as? String) ?: ""
        val host = (target["host"] as? String) ?: ""
        val port = (target["port"] as? Number)?.toInt() ?: 22
        column.addView(
            text(
                getString(R.string.ssh_row_detail, user, host, port) +
                    " \u00b7 " + sshStateLabel((target["state"] as? String) ?: "disconnected"),
                13f,
                palette.muted
            )
        )
        val pinned = target["hostKeySha256"] as? String
        if (!pinned.isNullOrBlank()) {
            column.addView(gap(6))
            column.addView(text(pinned, 10f, palette.muted))
        }
        column.addView(gap(12))

        val dialog = AlertDialog.Builder(this)
            .setTitle((target["label"] as? String) ?: getString(R.string.ui_external))
            .setView(column)
            .setNegativeButton(R.string.ssh_action_close, null)
            .create()

        fun addAction(textRes: Int, primary: Boolean, action: () -> Unit) {
            val button = pill(
                value = getString(textRes),
                textColor = if (primary) palette.onAccent else palette.onSurface,
                fill = if (primary) palette.accent else palette.background,
                rippleColor = palette.ripple,
                stroke = if (primary) null else palette.border,
                onClick = {
                    dialog.dismiss()
                    action()
                }
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }
            }
            column.addView(button)
        }

        if (connected) {
            addAction(R.string.ssh_action_disconnect, primary = true) { disconnectTarget(id) }
        } else {
            addAction(R.string.ssh_action_connect, primary = true) { connectTarget(id, null) }
        }
        val publicLine = target["publicKeyLine"] as? String
        if (!publicLine.isNullOrBlank()) {
            addAction(R.string.ssh_action_copy_key, primary = false) {
                copyText(publicLine, R.string.ssh_public_key_copied)
            }
        }
        addAction(R.string.ssh_action_edit, primary = false) { showTargetEditor(target) }
        if (!pinned.isNullOrBlank()) {
            addAction(R.string.ssh_action_clear_pin, primary = false) { clearPin(id) }
        }
        addAction(R.string.ssh_action_delete, primary = false) { confirmDeleteTarget(id) }
        dialog.show()
    }

    private fun connectTarget(id: String, trustFingerprint: String?) {
        val server = client.server
        if (server == null) {
            toast(getString(R.string.toast_not_bound, getString(R.string.ssh_action_connect)))
            return
        }
        toast(getString(R.string.ssh_connecting))
        Thread({
            val raw = runCatching { server.sshConnect(id, trustFingerprint) }.getOrNull()
            val parsed = raw?.let { JsonParser.parseObjectOrNull(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    parsed?.get("ok") == true -> {
                        toast(getString(R.string.ssh_connected))
                        refreshNow()
                    }
                    parsed?.get("code") == "HOST_KEY_UNVERIFIED" -> {
                        val fingerprint = parsed["fingerprint"] as? String
                        if (fingerprint != null) confirmHostKey(id, fingerprint)
                    }
                    parsed?.get("code") == "HOST_KEY_MISMATCH" -> {
                        confirmHostKeyChange(
                            id,
                            parsed["pinned"] as? String,
                            parsed["fingerprint"] as? String
                        )
                    }
                    else -> {
                        val code = parsed?.get("code") as? String
                            ?: parsed?.get("message") as? String
                            ?: getString(R.string.ssh_state_failed)
                        toast(getString(R.string.ssh_error, code))
                        refreshNow()
                    }
                }
            }
        }, "grom-ssh-connect").start()
    }

    private fun confirmHostKey(id: String, fingerprint: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_confirm_title)
            .setMessage(getString(R.string.ssh_confirm_body, fingerprint))
            .setPositiveButton(R.string.ssh_confirm_action) { _, _ ->
                connectTarget(id, fingerprint)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmHostKeyChange(id: String, pinned: String?, observed: String?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_mismatch_title)
            .setMessage(
                getString(
                    R.string.ssh_mismatch_body,
                    pinned ?: "\u2014",
                    observed ?: "\u2014"
                )
            )
            .setPositiveButton(R.string.ssh_mismatch_action) { _, _ ->
                clearPin(id)
                connectTarget(id, null)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun disconnectTarget(id: String) {
        val server = client.server ?: return
        Thread({
            runCatching { server.sshDisconnect(id) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(getString(R.string.ssh_disconnected))
                refreshNow()
            }
        }, "grom-ssh-disconnect").start()
    }

    private fun clearPin(id: String) {
        val server = client.server ?: return
        Thread({
            runCatching { server.sshClearPin(id) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshNow()
            }
        }, "grom-ssh-pin").start()
    }

    private fun confirmDeleteTarget(id: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_delete_title)
            .setMessage(R.string.ssh_delete_body)
            .setPositiveButton(R.string.ssh_delete_action) { _, _ ->
                val server = client.server ?: return@setPositiveButton
                Thread({
                    runCatching { server.sshDeleteTarget(id) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        refreshNow()
                    }
                }, "grom-ssh-delete").start()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Add/edit dialog. The private key is never shown again after save; a
     * generated key is shown as its public line, and the private half is sealed
     * by the service when the draft is stored.
     */
    private fun showTargetEditor(existing: Map<String, Any?>?) {
        var auth = (existing?.get("auth") as? String)?.let { wire ->
            if (wire == "password") "password" else "key"
        } ?: "key"
        val existingAuth = existing?.get("auth") as? String
        var generatedPrivate: String? = null
        var publicLine = existing?.get("publicKeyLine") as? String

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        fun field(hintRes: Int, value: String?): EditText = EditText(this).apply {
            hint = getString(hintRes)
            setSingleLine()
            if (value != null) setText(value)
        }

        val labelInput = field(R.string.ssh_field_label, existing?.get("label") as? String)
        val hostInput = field(R.string.ssh_field_host, existing?.get("host") as? String)
        val portInput = field(
            R.string.ssh_field_port,
            ((existing?.get("port") as? Number)?.toInt() ?: 22).toString()
        )
        val forwardInput = field(
            R.string.ssh_field_forward,
            ((existing?.get("forwardPort") as? Number)?.toInt() ?: 47002).toString()
        )
        val userInput = field(R.string.ssh_field_user, existing?.get("username") as? String)
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.ssh_field_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val privateKeyInput = EditText(this).apply {
            hint = getString(R.string.ssh_private_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(false)
            minLines = 3
        }
        val publicText = text(
            publicLine?.takeIf { it.isNotBlank() } ?: getString(R.string.ssh_public_line),
            10f,
            palette.muted
        )
        val noteText = text("", 10f, palette.muted)

        val authPill = pill(
            value = if (auth == "key") getString(R.string.ssh_auth_key) else getString(R.string.ssh_auth_password),
            textColor = palette.onSurface,
            fill = palette.background,
            rippleColor = palette.ripple,
            stroke = palette.border,
            onClick = {}
        )

        val generatePill = pill(
            value = getString(R.string.ssh_generate),
            textColor = palette.onSurface,
            fill = palette.background,
            rippleColor = palette.ripple,
            stroke = palette.border,
            onClick = {}
        )

        // Local visibility helper; `auth` changes through the pill's listener.
        fun refreshAuthUi() {
            authPill.text = if (auth == "key") {
                getString(R.string.ssh_auth_key)
            } else {
                getString(R.string.ssh_auth_password)
            }
            passwordInput.visibility = if (auth == "password") View.VISIBLE else View.GONE
            privateKeyInput.visibility = if (auth == "key") View.VISIBLE else View.GONE
            generatePill.visibility = if (auth == "key") View.VISIBLE else View.GONE
            publicText.visibility = if (auth == "key") View.VISIBLE else View.GONE
            noteText.visibility = if (auth == "key") View.VISIBLE else View.GONE
            noteText.text = if (auth == "key") {
                getString(R.string.ssh_key_private_note)
            } else {
                ""
            }
        }
        authPill.setOnClickListener {
            auth = if (auth == "key") "password" else "key"
            refreshAuthUi()
        }
        generatePill.setOnClickListener {
            val server = client.server ?: return@setOnClickListener
            Thread({
                val raw = runCatching { server.sshGenerateKeyJson() }.getOrNull()
                val parsed = raw?.let { JsonParser.parseObjectOrNull(it) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (parsed?.get("ok") == true) {
                        generatedPrivate = parsed["privateKeyText"] as? String
                        publicLine = parsed["publicKeyLine"] as? String
                        privateKeyInput.setText("")
                        publicText.text = publicLine ?: getString(R.string.ssh_public_line)
                        toast(getString(R.string.ssh_key_generated))
                    } else {
                        toast(getString(R.string.ssh_key_generate_failed))
                    }
                }
            }, "grom-ssh-keygen").start()
        }

        column.addView(labelInput)
        column.addView(hostInput)
        column.addView(portInput)
        column.addView(userInput)
        column.addView(authPill)
        column.addView(passwordInput)
        column.addView(privateKeyInput)
        column.addView(generatePill)
        column.addView(publicText)
        column.addView(noteText)
        column.addView(forwardInput)
        column.addView(text(getString(R.string.ssh_forward_note), 10f, palette.muted))
        refreshAuthUi()

        val scroll = ScrollView(this).apply { addView(column) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) R.string.dialog_ssh_add else R.string.dialog_ssh_edit
            )
            .setView(scroll)
            .setPositiveButton(R.string.dialog_save, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = existing?.get("id") as? String
                val privateText = when {
                    auth != "key" -> null
                    !generatedPrivate.isNullOrBlank() -> generatedPrivate
                    privateKeyInput.text.isNotBlank() -> privateKeyInput.text.toString()
                    else -> null
                }
                val unchangedAuth = existing != null && existingAuth == auth &&
                    existing["configured"] == true
                if (auth == "key" && privateText == null && !unchangedAuth) {
                    toast(getString(R.string.ssh_key_required))
                    return@setOnClickListener
                }
                if (auth == "password" && passwordInput.text.isBlank() && !unchangedAuth) {
                    toast(getString(R.string.ssh_password_required))
                    return@setOnClickListener
                }
                val draft = linkedMapOf<String, Any?>(
                    "label" to labelInput.text.toString(),
                    "host" to hostInput.text.toString(),
                    "port" to (portInput.text.toString().toIntOrNull() ?: 22),
                    "username" to userInput.text.toString(),
                    "auth" to auth,
                    "forwardPort" to (forwardInput.text.toString().toIntOrNull() ?: 47002),
                    "enabled" to true
                )
                if (id != null) draft["id"] = id
                if (privateText != null) draft["privateKey"] = privateText
                if (auth == "password" && passwordInput.text.isNotBlank()) {
                    draft["password"] = passwordInput.text.toString()
                }
                saveTarget(draft)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveTarget(draft: Map<String, Any?>) {
        val server = client.server
        if (server == null) {
            toast(getString(R.string.toast_not_bound, getString(R.string.ssh_add)))
            return
        }
        val body = Json.encode(draft)
        Thread({
            val outcome = runCatching { server.sshSaveTarget(body) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    onSuccess = {
                        toast(getString(R.string.ssh_saved))
                        refreshNow()
                    },
                    onFailure = { failure ->
                        toast(
                            failure.message?.removePrefix("GROM_E_")
                                ?: getString(R.string.ssh_state_failed)
                        )
                    }
                )
            }
        }, "grom-ssh-save").start()
    }

    // --------------------------------------------------------------- projects

    private fun renderProjects() {
        if (!::projectsList.isInitialized) return
        val list = (status?.get("projects") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?: emptyList()
        val signature = list.joinToString("|") {
            "${it["id"]}:${it["state"]}:${it["title"]}"
        }
        if (signature == lastProjectsSignature) return
        lastProjectsSignature = signature
        projectsList.removeAllViews()

        if (list.isEmpty()) {
            projectsList.addView(text(getString(R.string.projects_empty), 12f, palette.muted))
            return
        }
        for (project in list) {
            val alive = project["state"] == "alive"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(lampView(if (alive) Lamp.ON else Lamp.OFF, palette))
            }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = (project["title"] as? String) ?: (project["id"] as? String) ?: ""
            val version = (project["version"] as? String).orEmpty()
            column.addView(
                text(
                    if (version.isBlank()) title else "$title $version",
                    14f,
                    palette.onSurface,
                    Typeface.BOLD
                )
            )
            column.addView(
                text(
                    if (alive) getString(R.string.project_alive)
                    else getString(R.string.project_stale),
                    11f,
                    if (alive) palette.positive else palette.negative
                )
            )
            row.addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            projectsList.addView(row)

            // Two honest actions. There is no Start/Stop here: the engine does
            // not own the host app's lifecycle, so a button that claimed to
            // control it would be a lie (D61).
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            actions.addView(
                pill(
                    value = getString(R.string.project_copy_details),
                    textColor = palette.onSurface,
                    fill = palette.background,
                    rippleColor = palette.ripple,
                    stroke = palette.border,
                    onClick = { copyProjectDetails(project) }
                )
            )
            actions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            actions.addView(
                pill(
                    value = getString(R.string.project_remove),
                    textColor = palette.negative,
                    fill = palette.background,
                    rippleColor = palette.ripple,
                    stroke = palette.border,
                    onClick = { confirmRemoveProject(project) }
                )
            )
            projectsList.addView(actions)
        }
        projectsList.addView(gap(4))
        projectsList.addView(text(getString(R.string.project_embedded_note), 10f, palette.muted))
        projectsList.addView(text(getString(R.string.project_actions_hint), 10f, palette.muted))
    }

    /** Copies the registration only: no key, no heartbeat timestamp, no host data. */
    private fun copyProjectDetails(project: Map<*, *>) {
        val details = buildString {
            appendLine("id: ${project["id"]}")
            appendLine("title: ${project["title"]}")
            appendLine("version: ${project["version"]}")
            appendLine("package: ${project["package"]}")
            appendLine("capabilities: ${(project["capabilities"] as? List<*>)?.joinToString(",")}")
            append("state: ${project["state"]}")
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            toast(getString(R.string.toast_invite_copy_failed))
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("grom-project", details))
        toast(getString(R.string.project_details_copied))
    }

    /**
     * Removing a live project is only temporary: the host re-announces itself at
     * its next heartbeat. The dialog says so instead of implying a revocation.
     */
    private fun confirmRemoveProject(project: Map<*, *>) {
        val id = (project["id"] as? String).orEmpty()
        val title = (project["title"] as? String) ?: id
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.project_remove_title, title))
            .setMessage(R.string.project_remove_body)
            .setPositiveButton(R.string.project_remove) { _, _ ->
                val server = client.server
                if (server == null) {
                    toast(getString(R.string.toast_not_bound, getString(R.string.project_remove)))
                    return@setPositiveButton
                }
                try {
                    server.removeProject(id)
                    toast(getString(R.string.project_removed))
                    lastProjectsSignature = null
                    handler.postDelayed({ refreshNow() }, 300)
                } catch (failure: IllegalStateException) {
                    toast(
                        getString(
                            R.string.toast_error,
                            getString(R.string.project_remove),
                            failure.message ?: failure.javaClass.simpleName
                        )
                    )
                } catch (failure: android.os.RemoteException) {
                    toast(getString(R.string.toast_server_gone, getString(R.string.project_remove)))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // --------------------------------------------------------------- behaviour

    private fun bind() {
        disconnectReason = null
        client.bind(object : GromClient.Listener {
            override fun onConnected(apiVersion: Int) {
                connected = true
                refreshNow()
            }

            override fun onDisconnected(reason: String) {
                connected = false
                disconnectReason = reason
                render()
            }
        })
        render()
    }

    private fun startServer() {
        stoppedByOperator = false
        startForegroundService(Intent(this, app.grom.server.service.GromService::class.java))
        handler.postDelayed({
            if (!connected) bind() else refreshNow()
        }, 350)
    }

    private fun stopServer() {
        stoppedByOperator = true
        client.unbind()
        connected = false
        status = null
        sshTargets = emptyList()
        stopService(Intent(this, app.grom.server.service.GromService::class.java))
        render()
    }

    private fun refreshNow() {
        if (!connected) return
        status = JsonParser.parseObjectOrNull(client.call("{}") { it.statusJson() })
        val ssh = JsonParser.parseObjectOrNull(client.call("{}") { it.sshStatusJson() })
        sshAvailable = ssh?.get("available") == true
        sshTargets = (ssh?.get("targets") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.map { raw -> raw.entries.filter { it.key is String }
                .associate { it.key as String to it.value } }
            ?: emptyList()
        render()
    }

    private fun invoke(label: String, block: (IGromServer) -> Unit) {
        val server = client.server
        if (server == null) {
            toast(getString(R.string.toast_not_bound, label))
            return
        }
        try {
            block(server)
            toast(getString(R.string.toast_ok, label))
        } catch (failure: IllegalStateException) {
            toast(getString(R.string.toast_error, label, failure.message ?: failure.javaClass.simpleName))
        } catch (failure: SecurityException) {
            toast(getString(R.string.toast_forbidden, label))
        } catch (failure: android.os.RemoteException) {
            toast(getString(R.string.toast_server_gone, label))
        }
        handler.postDelayed({ refreshNow() }, 300)
    }

    // -------------------------------------------------------------------- render

    /**
     * The alerts rows. `chatId`/`tokenSet` come from the Binder (status keeps
     * them off the data plane), while the lamp comes from the status block, so
     * only a measured delivery can turn it green.
     */
    private fun renderAlerts() {
        val block = status?.get("notifications") as? Map<*, *>
        val settings = client.call(null) {
            JsonParser.parseObjectOrNull(it.notifySettingsJson())
        }
        alertsChatId = (settings?.get("chatId") as? String).orEmpty()
        alertsTokenSet = settings?.get("tokenSet") == true
        alertsEnabled = block?.get("enabled") == true

        val signature = "${block?.get("enabled")}|${block?.get("configured")}|" +
            "${(block?.get("lastDelivery") as? Map<*, *>)?.get("ok")}|" +
            "$alertsChatId|$alertsTokenSet"
        if (signature == lastAlertsSignature) return
        lastAlertsSignature = signature

        val row = NotifyModel.row(block)
        alertsEnableRow.second.text =
            if (alertsEnabled) getString(R.string.ui_on) else getString(R.string.ui_off)
        alertsChatRow.second.text =
            alertsChatId.ifEmpty { getString(R.string.alerts_chat_not_set) }
        alertsTokenRow.second.text =
            if (alertsTokenSet) getString(R.string.alerts_token_set)
            else getString(R.string.alerts_token_not_set)
        alertsTestRow.second.text = getString(R.string.alerts_test)
        alertsStateRow.second.text = getString(row.valueRes)
        alertsDetail.text = if (row.detailRes != 0) getString(row.detailRes) else ""
        alertsLamp?.let { repaintLamp(it, row.lamp, palette) }
    }

    private fun render() {
        if (BuildConfig.GROMSERVER) {
            renderSuper()
            renderSsh()
            renderProjects()
            renderAlerts()
            renderFleet()
            renderDoctor()
            renderAi()
        }
        val dashboard = DashboardModel.build(status)
        renderServing(dashboard)

        if (!BuildConfig.GROMSERVER) {
            stateView.text = getString(R.string.state_peripheral)
            stateView.setTextColor(palette.onSurface)
            detailView.text = getString(R.string.detail_peripheral)
            return
        }
        if (!connected) {
            stateView.text = getString(R.string.state_offline)
            stateView.setTextColor(palette.onSurface)
            detailView.text = disconnectReason?.let { getString(R.string.detail_ipc_reason, it) }
                ?: getString(R.string.detail_ipc_unbound)
            primaryAction.text = getString(R.string.action_start)
            renderRuntimeSection()
            return
        }

        stateView.text = when (dashboard.state) {
            "READY" -> getString(R.string.state_running)
            "STARTING" -> getString(R.string.state_starting)
            "MISSING_RUNTIME" -> getString(R.string.state_runtime_missing)
            "QUARANTINED" -> getString(R.string.state_quarantined)
            "STOPPED" -> getString(R.string.state_stopped)
            else -> getString(R.string.state_server)
        }
        stateView.setTextColor(
            when (dashboard.stateLamp) {
                Lamp.ON -> palette.positive
                Lamp.OFF -> palette.negative
                Lamp.UNKNOWN -> palette.onSurface
            }
        )
        detailView.text = when {
            dashboard.detail.isNotEmpty() -> dashboard.detail
            dashboard.state == "STARTING" -> getString(R.string.detail_starting)
            dashboard.state == "MISSING_RUNTIME" -> getString(R.string.detail_runtime_missing)
            dashboard.state == "STOPPED" -> getString(R.string.detail_stopped)
            dashboard.state == "QUARANTINED" -> getString(R.string.detail_quarantine_default)
            else -> ""
        }
        primaryAction.text = if (dashboard.state == "STOPPED") {
            getString(R.string.action_start)
        } else {
            getString(R.string.action_stop)
        }
        renderRuntimeSection()
    }

    private fun renderServing(dashboard: app.grom.server.ui.Dashboard) {
        for (row in dashboard.rows) {
            val views = servingViews[row.id] ?: continue
            repaintLamp(views.first, row.lamp, palette)
            views.second.text = row.value ?: getString(R.string.serve_none)
            views.second.setTextColor(
                when (row.lamp) {
                    Lamp.ON -> palette.positive
                    Lamp.OFF -> palette.negative
                    Lamp.UNKNOWN -> palette.muted
                }
            )
        }
    }

    private fun renderRuntimeSection() {
        if (!::runtimeTitle.isInitialized) return
        if (!BuildConfig.GROMSERVER) {
            runtimeTitle.text = getString(R.string.runtime_not_applicable)
            runtimeDetail.text = getString(R.string.runtime_install_server_role)
            return
        }
        val runtimePresent = status?.get("runtime") == "node"
        val pid = number("pid")
        if (runtimePresent) {
            runtimeTitle.text = if (pid > 0) {
                getString(R.string.runtime_ready)
            } else {
                getString(R.string.runtime_installed)
            }
            runtimeDetail.text = if (pid > 0) {
                getString(R.string.runtime_ready_detail, pid, number("restarts"), number("crashes"))
            } else {
                getString(R.string.runtime_no_process)
            }
        } else {
            val missing = status?.get("missing") as? String
            runtimeTitle.text = getString(R.string.runtime_missing_title)
            runtimeDetail.text = if (!missing.isNullOrEmpty()) {
                getString(R.string.runtime_missing_named, missing)
            } else {
                getString(R.string.runtime_missing_generic)
            }
        }
    }

    private fun renderUpdate() {
        when (val current = updateState) {
            UpdateState.Idle -> {
                updateTitle.text = getString(R.string.update_updates)
                updateDetail.text = getString(R.string.update_installed, BuildConfig.VERSION_NAME)
                updateAction.text = getString(R.string.update_check_now)
                updateAction.alpha = 1f
            }
            UpdateState.Checking -> {
                updateTitle.text = getString(R.string.update_checking_title)
                updateDetail.text = getString(R.string.update_checking_detail)
                updateAction.text = getString(R.string.update_checking_button)
                updateAction.alpha = 0.45f
            }
            UpdateState.Current -> {
                updateTitle.text = getString(R.string.update_current)
                updateDetail.text = getString(R.string.update_installed, BuildConfig.VERSION_NAME)
                updateAction.text = getString(R.string.update_check_again)
                updateAction.alpha = 1f
            }
            is UpdateState.Available -> {
                updateTitle.text = getString(R.string.update_available, current.release.version)
                val notes = current.release.notes.trim()
                updateDetail.text = if (notes.isEmpty()) {
                    getString(R.string.update_installed, BuildConfig.VERSION_NAME)
                } else {
                    notes
                }
                updateAction.text = if (current.release.apkUrl != null) {
                    getString(R.string.update_download)
                } else {
                    getString(R.string.update_open_release)
                }
                updateAction.alpha = 1f
            }
            is UpdateState.Failed -> {
                updateTitle.text = getString(R.string.update_failed)
                updateDetail.text = current.reason
                updateAction.text = getString(R.string.update_try_again)
                updateAction.alpha = 1f
            }
        }
    }

    private fun renderInvite() {
        if (!::inviteCodeView.isInitialized) return
        inviteCodeView.text = ReferralStore.code(this)
        val shares = ReferralStore.shares(this)
        inviteSharesView.text = if (shares == 0) {
            getString(R.string.invite_not_shared)
        } else {
            resources.getQuantityString(R.plurals.invite_shares, shares, shares)
        }
        inviteRedeemRow.second.text =
            ReferralStore.referrer(this) ?: getString(R.string.invite_none)
    }

    private fun inviteLink(): String =
        getString(R.string.invite_link, ReferralStore.code(this))

    private fun copyInviteLink() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            toast(getString(R.string.toast_invite_copy_failed))
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("grom-invite", inviteLink()))
        ReferralStore.recordShare(this)
        renderInvite()
        toast(getString(R.string.toast_invite_copied))
    }

    private fun shareInvite() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.invite_share_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.invite_share_text, inviteLink()))
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.invite_share_chooser)))
            ReferralStore.recordShare(this)
            renderInvite()
        } catch (_: Exception) {
            copyInviteLink()
        }
    }

    private fun showRedeemDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = getString(R.string.dialog_invite_hint)
            setSingleLine()
            setText(ReferralStore.referrer(this@GromActivity) ?: "")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_invite_title)
            .setMessage(R.string.dialog_invite_body)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                when (val result = ReferralStore.redeem(this, input.text.toString())) {
                    is ReferralStore.Redeem.Applied -> {
                        renderInvite()
                        toast(getString(R.string.toast_invite_saved, result.code))
                    }
                    ReferralStore.Redeem.OwnCode ->
                        toast(getString(R.string.toast_invite_own_code))
                    ReferralStore.Redeem.Invalid ->
                        toast(getString(R.string.toast_invite_invalid))
                }
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                ReferralStore.clearReferrer(this)
                renderInvite()
                toast(getString(R.string.toast_invite_cleared))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun checkForUpdates() {
        if (updateState is UpdateState.Checking) return
        updateState = UpdateState.Checking
        renderUpdate()
        val current = BuildConfig.VERSION_NAME
        val serverRole = BuildConfig.GROMSERVER
        Thread({
            val result = UpdateChecker.check(current, serverRole)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateState = result.fold(
                    onSuccess = { release ->
                        if (release == null) UpdateState.Current else UpdateState.Available(release)
                    },
                    onFailure = { failure ->
                        UpdateState.Failed(
                            failure.message ?: getString(R.string.update_unknown_failure)
                        )
                    }
                )
                renderUpdate()
            }
        }, "grom-update-check").start()
    }

    private fun openUrl(url: String?) {
        if (url.isNullOrEmpty()) {
            toast(getString(R.string.toast_no_download_link))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(getString(R.string.toast_no_browser))
        }
    }

    // ------------------------------------------------------------------- helpers

    private fun state(): String? = status?.get("state") as? String

    private fun number(key: String): Long = (status?.get(key) as? Number)?.toLong() ?: -1L

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_MS = 1_000L
        private const val REQUEST_NOTIFICATIONS = 47001
    }
}
