// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import app.grom.sdk.ipc.IGromServer

/**
 * Client side of the Grom IPC contract (S4-R017…S4-R020).
 *
 * A client signed with a different certificate gets no Binder: the platform
 * refuses the bind on the signature permission and, even if that were bypassed,
 * GromService.onBind returns null. In both cases the listener is told the
 * connection failed rather than left waiting.
 */
class GromClient(
    private val context: Context,
    private val target: Target = Target.STANDALONE
) {

    /**
     * Where the server lives (D55).
     *
     * `STANDALONE` is the shipped Grom app. `EMBEDDED` means the caller *is* the
     * host application that embeds the engine, so the component lives in the
     * caller's own package and the signature gate matches by construction.
     */
    enum class Target { STANDALONE, EMBEDDED }

    interface Listener {
        fun onConnected(apiVersion: Int)
        fun onDisconnected(reason: String)
    }

    /**
     * The live binder, or `null` when there is none.
     *
     * `@Volatile` because the connection callbacks arrive on the main thread
     * while [call] runs on whichever thread the host chose: without it a caller
     * could keep reading a stale `null` (or a dead binder) after a rebind.
     */
    @Volatile
    var server: IGromServer? = null
        private set

    private var listener: Listener? = null

    /**
     * True while the platform still holds our [connection].
     *
     * It is deliberately **not** cleared when the connection dies: Android keeps
     * the registration until `unbindService` is called, so clearing it early
     * (as `onNullBinding` used to) leaks the connection for the life of the
     * process and makes every later `bind` a no-op.
     */
    @Volatile
    private var bound = false

    /** True while [server] can actually be used; false after any death. */
    @Volatile
    private var usable = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val api = IGromServer.Stub.asInterface(binder)
            if (api == null) {
                usable = false
                listener?.onDisconnected("binder unavailable")
                return
            }
            server = api
            usable = true
            val version = try {
                api.apiVersion()
            } catch (_: RemoteException) {
                // A binder that cannot even answer the version call is not
                // usable, and the caller is told so rather than handed a client
                // whose every later call silently falls back.
                server = null
                usable = false
                listener?.onDisconnected("version call failed")
                return
            }
            listener?.onConnected(version)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            server = null
            usable = false
            listener?.onDisconnected("service disconnected")
        }

        override fun onNullBinding(name: ComponentName?) {
            // `onNullBinding` means the service's `onBind` returned null, which
            // GromService does when the runtime cannot start (for example a
            // control port already held by another server). It is *not* the
            // signature gate: a foreign certificate is refused earlier, by the
            // platform, and shows up as `bindService` returning false
            // (S4-R008/S4-R009). The old text here claimed "signature rejected",
            // which sent an integrator hunting the wrong cause.
            //
            // The registration is still held by the platform, so `bound` stays
            // true and `unbind()` will really release it (S4-R018).
            server = null
            usable = false
            listener?.onDisconnected("the service refused to start")
        }

        override fun onBindingDied(name: ComponentName?) {
            server = null
            usable = false
            listener?.onDisconnected("binding died")
        }
    }

    /**
     * Binds to the server.
     *
     * @param createIfMissing when false the service is **not** started if it is
     *   not already running (`bindService` without `BIND_AUTO_CREATE`). That is
     *   what a health check needs: asking "is it running?" must never make it
     *   run. The call then returns false when nothing is running.
     * @return false when the platform refused the bind outright, or (with
     *   `createIfMissing = false`) when no server is up.
     */
    fun bind(listener: Listener, createIfMissing: Boolean = true): Boolean {
        this.listener = listener
        if (bound && usable) return true
        // A dead registration still holds the platform connection; release it
        // before asking for a new one, or `bindService` returns the dead binder.
        if (bound) release()
        val intent = Intent(ACTION).apply {
            setClassName(serverPackage(), SERVICE_CLASS)
        }
        val flags = if (createIfMissing) Context.BIND_AUTO_CREATE else 0
        bound = runCatching { context.bindService(intent, connection, flags) }
            .getOrDefault(false)
        if (!bound) {
            usable = false
            listener.onDisconnected("bind refused")
        }
        return bound
    }

    /** Idempotent (S4-R018). */
    fun unbind() {
        if (!bound) return
        release()
    }

    private fun release() {
        bound = false
        usable = false
        server = null
        runCatching { context.unbindService(connection) }
    }

    /** Runs a call, mapping a dead binder to a stable message (S4-R019). */
    fun <T> call(fallback: T, block: (IGromServer) -> T): T {
        val api = server ?: return fallback
        return try {
            block(api)
        } catch (_: RemoteException) {
            server = null
            usable = false
            fallback
        }
    }

    private fun serverPackage(): String = when (target) {
        Target.STANDALONE -> SERVER_PACKAGE
        Target.EMBEDDED -> context.packageName
    }

    companion object {
        const val SERVER_PACKAGE: String = "app.grom.server"
        const val SERVICE_CLASS: String = "app.grom.server.service.GromService"
        const val ACTION: String = "app.grom.sdk.ipc.GROM_SERVER"
        const val PERMISSION: String = "app.grom.sdk.ipc.BIND_GROMSERVER"
        /**
         * API 2 added [IGromServer.reloadSuperServer] (D39); API 3 adds the
         * embeddable surface: projects, config export, agent exec and the SSH
         * bridge (D55, D58); API 4 adds the project-scoped key/value path (D61);
         * API 5 adds the operator's notification settings (D65); API 6 adds the
         * device identity, mutual pairing and the fleet roster (D70-D75); API 7
         * adds the one-way scoped grant for an inbound reader (D76-D78); API 8
         * adds the measured Doctor report (D80-D84); API 9 adds a supported way
         * for an embedding host to configure the data plane (D86); API 10 adds
         * the AI gateway (D94-D96); API 11 adds the server network (D99-D101).
         */
        const val API_VERSION: Int = 11
    }
}
