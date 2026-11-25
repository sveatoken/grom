// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import android.content.Context
import app.grom.server.cluster.PairingPort
import app.grom.server.core.BindResolution
import app.grom.server.core.SuperBindPolicy
import app.grom.server.http.HttpResponse
import app.grom.server.referral.ReferralStore
import app.grom.server.store.AndroidKeystoreSecretBox
import app.grom.server.store.SecretBox
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import app.grom.server.superserver.plugins.lightning.LightningBackend
import app.grom.server.superserver.plugins.lightning.LightningBackendFactory
import app.grom.server.superserver.plugins.lightning.LightningPlugin
import java.io.File

/**
 * Owns the lifecycle of the public data plane inside the server process.
 *
 * Everything is rebuilt from the persisted configuration on every start, so a
 * reload can never leave half of an old policy in place. A configuration that
 * fails validation produces a reported error and **no listener** — there is no
 * degraded "bound anyway" state (S5-R008).
 *
 * All fields read by [status] are volatile and the method takes no lock: the UI
 * polls status once a second and must never be able to deadlock the supervisor.
 */
class SuperServerHost(
    context: Context,
    private val controlPort: Int,
    private val agentHealth: () -> Map<String, Any?>,
    private val agentExec: (String, String?) -> HttpResponse,
    private val audit: (String, String) -> Unit,
    private val logger: (String) -> Unit,
    /**
     * Read-only project registrations (D55) and the measured SSH bridge state
     * (D58). Both are lazily read so a heartbeat refresh needs no rebuild.
     */
    private val projects: () -> List<Map<String, Any?>> = { emptyList() },
    private val bridge: () -> Map<String, Any?> = { emptyMap() },
    /**
     * The operator-notification state (D65-D67). Sanitised by the notifier:
     * enabled, configured and the measured last delivery only.
     */
    private val notifications: () -> Map<String, Any?> = { emptyMap() },
    /** This device's fleet identity, never a peer list or an address (D59). */
    private val fleet: () -> Map<String, Any?> = { emptyMap() },
    /** Pairing routes; live only while the operator has opened a session (D71). */
    private val pairing: PairingPort = PairingPort.DISABLED,
    /** Maps a verified key id to an operator or a scoped peer (D73). */
    private val principalOf: (String) -> SuperAuth.Principal = { SuperAuth.Principal.Operator },
    /** Paired-device public keys, read live so a pairing needs no restart (D54). */
    private val peerKeys: () -> Map<String, ByteArray> = { emptyMap() },
    /**
     * The three measured Doctor fields, and only those (D84): a hub polling
     * status learns whether Android is throttling this phone, never what the
     * phone is doing, where it is or what it is paired with.
     */
    private val doctor: () -> Map<String, Any?> = { emptyMap() },
    /** The sanitised hand-off document served at `GET /v1/config` (D53). */
    private val configExport: () -> String = { "{}" },
    /**
     * The version of the APK that hosts this engine (D91). Injected rather than
     * read from the package manager here, so the host class stays testable and
     * the `connect` document never reports a version the build did not stamp.
     */
    private val version: () -> String = { "unknown" },
    /**
     * The AI gateway (D94). It is built and owned by the runtime — the config
     * lives beside every other store — so adding a provider key takes effect on
     * the next request without rebinding the listener (D54).
     */
    private val ai: AiPort = AiPort.DISABLED
) {

    private val lock = Any()
    private val configStore = SuperServerConfigStore(File(context.filesDir, "grom"))
    private val secretBox: SecretBox = AndroidKeystoreSecretBox()

    /**
     * The device's local referral record (D50). It describes *this* device, not
     * the data plane, so it is reported only to callers already allowed to see
     * the full status: the loopback control plane and an authenticated
     * `GET /v1/status`. The unauthenticated `/v1/health` never includes it.
     */
    private val appContext: Context = context.applicationContext

    @Volatile
    private var config: SuperServerConfig = SuperServerConfig.defaults()

    @Volatile
    private var server: SuperServer? = null

    @Volatile
    private var registry: PluginRegistry = PluginRegistry(emptyList())

    @Volatile
    private var events: EventBus = EventBus()

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var backendError: String? = null

    @Volatile
    private var startedAtMs: Long = 0L

    /**
     * Whether [start] has been asked for and [stop] has not followed.
     *
     * It is what lets [saveConfig] apply a new document to a live plane without
     * ever binding a socket on a plane the runtime has not started (D86).
     */
    @Volatile
    private var started: Boolean = false

    fun start() {
        synchronized(lock) {
            stopLocked()
            started = true
            startLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            started = false
            stopLocked()
        }
    }

    fun reload() {
        synchronized(lock) {
            if (!started) {
                // A reload before start would bind a socket nobody asked for.
                // Take the document in memory only; start() loads it again.
                config = configStore.load()
                lastError = configStore.lastError
                return
            }
            val loaded = configStore.load()
            // Changing only npub/relays/remoteBridge cannot invalidate a session
            // or a listener, so it is applied in place (D54). Everything else
            // rebuilds the whole plane, as before.
            if (server != null && config.sameRuntimePolicy(loaded)) {
                config = loaded
                lastError = configStore.lastError
                audit(
                    "super.reload.reference",
                    "npub=${loaded.npub != null} relays=${loaded.relays.size} " +
                        "bridge=${loaded.remoteBridge != null}"
                )
                logger("super server reference configuration updated without rebinding")
                return
            }
            audit("super.reload", "")
            stopLocked()
            startLocked()
        }
    }

    fun isRunning(): Boolean = server?.isRunning() == true

    /**
     * Persists an already-validated document and applies it (D86).
     *
     * Validation is not optional and not repeated here: the only way to produce
     * the argument is [SuperServerConfigInput.parse], which runs the screen's
     * own contract. Saving implies applying — a host that writes a document and
     * then reads a status telling it nothing happened would be a lie.
     */
    fun saveConfig(newConfig: SuperServerConfig) {
        synchronized(lock) {
            configStore.save(newConfig)
        }
        // Outside the lock: reload() takes it, and a bind failure must not be
        // reported while holding it.
        reload()
    }

    /**
     * Runs the Lightning adapter's `health` op now (D52). This is the only path
     * that can turn the Lightning lamp green: status never probes on its own.
     * When the data plane is disabled the registry is empty, so the answer is a
     * truthful "not configured" rather than a fabricated result.
     */
    fun probeLightning(): Map<String, Any?> {
        val plugin = registry.find("lightning")
            ?: return mapOf(
                "ok" to false,
                "code" to "LIGHTNING_UNCONFIGURED",
                "message" to "no Lightning backend is configured"
            )
        return when (val result = plugin.rpc("health", emptyMap())) {
            is PluginResult.Ok -> mapOf("ok" to true) + result.payload
            is PluginResult.Failure -> mapOf(
                "ok" to false,
                "code" to result.code,
                "message" to result.message
            )
        }
    }

    /**
     * The configuration currently applied. Read by the sanitised export (D53)
     * and by the SSH bridge so it forwards to the address that really exists.
     */
    fun currentConfig(): SuperServerConfig = config

    /** Snapshot for `GET /v1/status`, `/grom/v1/status` and the UI. */
    fun status(): Map<String, Any?> {
        val current = server
        val lightningStatus = registry.find("lightning")?.status() ?: emptyMap()
        return mapOf(
            "enabled" to config.enabled,
            "running" to (current?.isRunning() == true),
            "bind" to config.bind.name,
            "address" to (current?.address ?: "not bound"),
            "port" to config.port,
            "auth" to config.auth.name,
            "passwordSet" to (config.passwordHash != null),
            "keys" to config.enabledKeys().map {
                mapOf("keyId" to it.keyId, "label" to it.label)
            },
            "lightning" to config.lightning.kind.wireName,
            "error" to lastError,
            "backendError" to backendError,
            "streams" to events.subscriberCount(),
            "plugins" to registry.all().map {
                mapOf("id" to it.id, "capabilities" to it.capabilities().sorted())
            },
            "referral" to ReferralStore.summary(appContext),
            "projects" to projects(),
            "bridge" to bridge(),
            "notifications" to notifications(),
            "fleet" to fleet()
        ) + SuperStatusDocument.build(config, lightningStatus)
    }

    // ------------------------------------------------------------- lifecycle

    private fun startLocked() {
        val loaded = configStore.load()
        config = loaded
        lastError = configStore.lastError
        backendError = null
        startedAtMs = 0L

        if (!loaded.enabled) {
            logger("super server is disabled")
            return
        }

        val check = loaded.validate(controlPort)
        if (check is ConfigCheck.Invalid) {
            lastError = "${check.code}: ${check.message}"
            logger("super server refused: $lastError")
            audit("super.refused", check.code)
            return
        }

        val resolved = SuperBindPolicy.resolve(loaded.bind)
        if (resolved is BindResolution.Rejected) {
            lastError = "${resolved.code}: ${resolved.message}"
            logger("super server refused: $lastError")
            audit("super.refused", resolved.code)
            return
        }
        val bound = resolved as BindResolution.Bound

        val passwords = loaded.passwordHash?.let { PasswordHash.decode(it) }
        if (loaded.passwordHash != null && passwords == null) {
            lastError = "BAD_PASSWORD_HASH: the stored password hash cannot be decoded"
            logger("super server refused: $lastError")
            return
        }

        val keys = LinkedHashMap<String, ByteArray>()
        for (key in loaded.enabledKeys()) {
            val bytes = key.decodedPublicKey() ?: continue
            keys[key.keyId] = bytes
        }

        val backend = buildBackend(loaded)
        val pluginRegistry = PluginRegistry(
            listOf(
                AgentPlugin(agentHealth, agentExec) {
                    // Read at call time so a reference-only reload needs no
                    // registry rebuild (D54). Projects and the SSH bridge are
                    // part of the authenticated status (D59); the health op
                    // stays free of them.
                    SuperStatusDocument.build(
                        config,
                        registry.find("lightning")?.status() ?: emptyMap()
                    ) + mapOf(
                        "projects" to projects(),
                        "bridge" to bridge(),
                        "notifications" to notifications(),
                        "fleet" to fleet()
                    )
                },
                LightningPlugin(backend, loaded.lightning.kind.wireName, backendError)
            ) + listOfNotNull(ai.service())
        )

        val sessions = SessionStore(ttlMs = loaded.sessionTtlMs)
        // Peers are read at every verification, so pairing or revoking needs no
        // listener restart (D54); operator keys keep precedence.
        val signer = RequestSigner(keyLookup = { id -> keys[id] ?: peerKeys()[id] })
        val auth = SuperAuth(
            config = loaded,
            passwords = passwords,
            sessions = sessions,
            requestLimiter = RateLimiter(),
            loginLimiter = LoginLimiter(),
            signer = signer,
            principalOf = principalOf
        )

        val bus = EventBus()
        startedAtMs = System.currentTimeMillis()

        val router = SuperRouter(
            config = loaded,
            auth = auth,
            plugins = pluginRegistry,
            events = bus,
            uptimeMs = {
                val started = startedAtMs
                if (started == 0L) 0L else System.currentTimeMillis() - started
            },
            pairing = pairing,
            ai = ai,
            configExport = configExport,
            gromVersion = version,
            dataPlane = {
                // Read at request time: the accept socket is assigned after
                // this lambda is built, and a stopped plane must never answer
                // with the address it used to have.
                linkedMapOf(
                    "enabled" to config.enabled,
                    "running" to (server?.isRunning() == true),
                    "bind" to config.bind.name,
                    "address" to (server?.address ?: "not bound"),
                    "port" to config.port,
                    "maxBodyBytes" to config.maxBodyBytes
                )
            },
            serverStatus = {
                mapOf(
                    "address" to (server?.address ?: "not bound"),
                    "port" to config.port,
                    "limits" to mapOf(
                        "maxBodyBytes" to config.maxBodyBytes,
                        "maxConnections" to config.maxConnections,
                        "maxSseClients" to config.maxSseClients
                    ),
                    "agent" to agentHealth(),
                    "streams" to bus.subscriberCount(),
                    // Read at call time, so the block is a measurement of this
                    // request and never a value cached at start (D80, D84).
                    "doctor" to doctor()
                )
            }
        )

        val created = SuperServer(loaded, bound, router, logger)
        try {
            created.start()
        } catch (failure: Exception) {
            // A bind conflict is reported, not retried on another address.
            lastError = "BIND_FAILED: ${failure.javaClass.simpleName}"
            logger("super server bind failed: ${failure.javaClass.simpleName}")
            audit("super.bind.failed", failure.javaClass.simpleName)
            startedAtMs = 0L
            return
        }

        server = created
        registry = pluginRegistry
        events = bus
        audit("super.start", "address=${created.address} bind=${loaded.bind.name} auth=${loaded.auth.name}")
        bus.publish("server", mapOf("state" to "started", "address" to created.address))
        logger("super server started at ${created.address}")
    }

    private fun stopLocked() {
        val current = server ?: return
        runCatching { current.stop() }
        runCatching { events.publish("server", mapOf("state" to "stopped")) }
        server = null
        startedAtMs = 0L
        audit("super.stop", "")
        logger("super server stopped")
    }

    private fun buildBackend(cfg: SuperServerConfig): LightningBackend? {
        if (cfg.lightning.kind == LightningKind.NONE) {
            backendError = null
            return null
        }
        val sealed = cfg.lightning.apiKeySealed
        if (sealed.isNullOrBlank()) {
            backendError = "no backend credential is stored"
            return null
        }
        val plaintext = secretBox.open(sealed)
        if (plaintext.isNullOrBlank()) {
            backendError = "the stored backend credential could not be decrypted"
            logger("lightning backend credential could not be opened")
            return null
        }
        val backend = LightningBackendFactory.create(cfg.lightning, plaintext)
        if (backend == null) {
            backendError = "the backend configuration is incomplete"
        }
        return backend
    }
}
