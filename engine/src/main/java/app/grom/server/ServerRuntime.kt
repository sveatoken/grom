// SPDX-License-Identifier: Apache-2.0
package app.grom.server

import android.content.Context
import app.grom.server.agent.AgentChannel
import app.grom.server.agent.AgentClient
import app.grom.server.agent.AgentLauncher
import app.grom.server.agent.AssetRuntime
import app.grom.server.agent.NodeAgentProcess
import app.grom.server.audit.AuditStore
import app.grom.server.cluster.FleetService
import app.grom.server.cluster.FleetStatus
import app.grom.server.cluster.NodeIdentity
import app.grom.server.cluster.NodeIdentityStore
import app.grom.server.cluster.PeerStore
import app.grom.server.cluster.PeerAddress
import app.grom.server.mesh.MeshService
import app.grom.server.mesh.MeshSettings
import app.grom.server.mesh.MeshSettingsStore
import app.grom.server.mesh.android.AndroidMeshRadios
import app.grom.server.mesh.net.BluetoothJsonTransport
import app.grom.server.mesh.net.LanBeaconNode
import app.grom.server.mesh.net.RoutingJsonTransport
import app.grom.server.superserver.MeshAnswer
import app.grom.server.core.AgentState
import app.grom.server.core.BindMode
import app.grom.server.core.BindResolution
import app.grom.server.core.ControlPort
import app.grom.server.core.HealthGate
import app.grom.server.core.Redactor
import app.grom.server.core.SuperBindPolicy
import app.grom.server.core.SupervisorPolicy
import app.grom.server.core.Token
import app.grom.server.doctor.AndroidDoctorProbe
import app.grom.server.doctor.DoctorFacade
import app.grom.server.doctor.DoctorDocument
import app.grom.server.doctor.RunMarkerStore
import app.grom.server.http.ApiException
import app.grom.server.http.GromApi
import app.grom.server.http.HttpResponse
import app.grom.server.http.LoopbackHttpServer
import app.grom.server.http.Router
import app.grom.server.notify.NotifyDraft
import app.grom.server.notify.NotifySettingsStore
import app.grom.server.notify.TelegramNotifier
import app.grom.server.projects.ProjectNamespace
import app.grom.server.projects.ProjectRegistry
import app.grom.server.service.GromPrefs
import app.grom.server.superserver.plugins.ai.AiConfigEditor
import app.grom.server.superserver.plugins.ai.AiDocuments
import app.grom.server.superserver.plugins.ai.AiEdit
import app.grom.server.superserver.plugins.ai.AiGateway
import app.grom.server.superserver.plugins.ai.AiInputs
import app.grom.server.superserver.plugins.ai.AiStore
import app.grom.server.superserver.net.AiHttpTransport
import app.grom.server.ssh.SshAuth
import app.grom.server.ssh.SshKeys
import app.grom.server.ssh.SshTarget
import app.grom.server.ssh.SshTargetDraft
import app.grom.server.ssh.SshTargetStore
import app.grom.server.ssh.SshTunnelManager
import app.grom.server.store.AndroidKeystoreSecretBox
import app.grom.server.store.SecureStore
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.AuthMode
import app.grom.server.superserver.ConfigParse
import app.grom.server.superserver.ConfigExport
import app.grom.server.superserver.SuperServerConfig
import app.grom.server.superserver.SuperServerConfigInput
import app.grom.server.superserver.SuperServerHost
import app.grom.sdk.GromClient
import java.io.File
import java.net.BindException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The running server: HTTP listener + supervisor + audit + token.
 *
 * Everything durable lives under `<files>/grom/`:
 *   grom-token        the bearer token
 *   node/             the agent runtime (installed by the operator)
 *   audit/            audit.log, audit.log.1, audit.head
 */
class ServerRuntime(
    private val context: Context,
    private val logSink: (String) -> Unit = {},
    /**
     * The host application's version. The engine is a library, so it has no
     * BuildConfig of its own; callers (GromService, GromEmbed) pass the value
     * that `PackageManager` reports for the APK this code is running in.
     */
    private val appVersion: String = hostVersion(context)
) : GromApi {

    private val rootDir = File(context.filesDir, "grom")
    private val runtimeDir = File(rootDir, "node")

    private val store = SecureStore(rootDir)
    private val projectRegistry = ProjectRegistry(File(rootDir, ProjectRegistry.FILE_NAME))
    private val secretBox = AndroidKeystoreSecretBox()
    private val sshStore = SshTargetStore(File(rootDir, SshTargetStore.FILE_NAME), secretBox)

    /**
     * The AI gateway (D94). The store owns the sealed provider keys; the gateway
     * reads the document per request, so a key added from the screen is live on
     * the next call without restarting the data plane (D54). It is built even in
     * the peripheral role — the store is inert until a provider is added — but
     * only the server role connects it to a listener.
     */
    private val aiStore = AiStore(File(rootDir, AiStore.FILE_NAME), secretBox)
    private val aiGateway = AiGateway(store = aiStore, transport = AiHttpTransport())

    /**
     * This device's fleet identity and its paired peers (D70-D75). Both stores
     * are small and local; the service owns pairing and the on-demand probes.
     */
    private val identityStore = NodeIdentityStore(
        File(rootDir, NodeIdentityStore.FILE_NAME),
        secretBox
    )
    private val peerStore = PeerStore(File(rootDir, PeerStore.FILE_NAME))

    /**
     * The carriers a paired server may live behind (D102): the LAN, Wi-Fi
     * Direct and VPN all speak `http(s)`, and a bonded Bluetooth Classic link
     * speaks the same HTTP/1.1 over RFCOMM. One routing transport keeps the
     * pairing client and the mesh caller on the same code path.
     */
    private val meshRadios: AndroidMeshRadios by lazy {
        AndroidMeshRadios.create(
            context = context,
            nodeId = { fleet.identityOrNull()?.nodeId },
            port = { meshDataPlanePort() },
            dataPlaneUp = { meshDataPlaneLive() },
            dataPlaneTarget = { localDataPlane() }
        )
    }
    private val meshTransport: RoutingJsonTransport = RoutingJsonTransport(
        classic = BluetoothJsonTransport(meshRadios.classic)
    )

    private val fleet = FleetService(
        identityStore = identityStore,
        store = peerStore,
        client = app.grom.server.cluster.PeerClient(transport = meshTransport),
        defaultLabel = { deviceLabel() },
        localPort = { port },
        audit = { action, detail -> safeAudit(action, detail) }
    )

    private val meshSettingsStore = MeshSettingsStore(File(rootDir, MeshSettings.FILE_NAME))
    private val mesh = MeshService(
        identityStore = identityStore,
        peers = peerStore,
        settingsStore = meshSettingsStore,
        caller = app.grom.server.mesh.MeshCaller(transport = meshTransport),
        beacon = LanBeaconNode(logger = { line -> log(line) }),
        radios = meshRadios.radios,
        localServices = { superServer.serviceIds() },
        localPort = { meshDataPlanePort() },
        serverRunning = { meshDataPlaneLive() },
        audit = { action, detail -> safeAudit(action, detail) },
        logger = { line -> log(line) }
    )

    init {
        // A share list may only name a service this device serves (D101).
        fleet.serviceIds = { superServer.serviceIds() }
    }

    /**
     * The reverse SSH bridge (D58). The manager is owned by the runtime, so the
     * UI and the IPC surface observe the same sessions and the same pins.
     */
    private val sshTunnels = SshTunnelManager(
        store = sshStore,
        audit = { action, detail -> safeAudit(action, detail) },
        logger = { line -> log(line) },
        localTarget = { localDataPlane() }
    )
    /**
     * The operator's own alerts (D65-D67). Settings live beside every other
     * store; the notifier owns one daemon worker and never blocks the audit path.
     */
    private val notifyStore =
        NotifySettingsStore(File(rootDir, NotifySettingsStore.FILE_NAME), secretBox)
    private val notifier = TelegramNotifier(
        store = notifyStore,
        logger = { line -> log(line) }
    )
    private val auditStore = AuditStore(File(rootDir, "audit"))

    /**
     * The Doctor (D80-D84). The marker is the one fact Grom remembers, written
     * once on start and once on stop; the probe is built lazily so a runtime
     * that never opens the screen never touches the platform.
     */
    private val runMarker = RunMarkerStore(File(rootDir, RunMarkerStore.FILE_NAME))
    private val doctorProbe: AndroidDoctorProbe by lazy {
        AndroidDoctorProbe(context, logSink = logSink)
    }
    private val policy = SupervisorPolicy()
    private val agentClient = AgentClient()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "grom-supervisor").apply { isDaemon = true }
        }

    private val lock = Any()

    @Volatile
    private var token: String = ""

    @Volatile
    private var startedAt: Long = 0L

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var httpServer: LoopbackHttpServer? = null

    @Volatile
    private var agent: NodeAgentProcess? = null

    @Volatile
    private var backoffUntil: Long = 0L

    @Volatile
    private var lastError: String? = null

    /** The ephemeral loopback port announced by the agent, or -1. */
    @Volatile
    private var agentPort: Int = -1

    val port: Int get() = GromPrefs.port(context)

    /**
     * The public data plane (D39). It is built lazily and started only in the
     * server role; the peripheral role never constructs a listening path.
     */
    private val superServer: SuperServerHost by lazy {
        SuperServerHost(
            context = context,
            controlPort = port,
            agentHealth = { health() },
            agentExec = { op, body -> exec(op, body) },
            audit = { action, detail -> safeAudit(action, detail) },
            logger = { line -> log(line) },
            projects = { projectRegistry.summary() },
            bridge = { sshTunnels.statusForServer() },
            notifications = { notifier.publicStatus() },
            fleet = { fleet.statusDocument() },
            pairing = fleet.pairing,
            principalOf = { keyId -> fleet.principalOf(keyId) },
            peerKeys = { fleet.peerKeys() },
            doctor = { doctorStatusBlock() },
            configExport = { configExportJson() },
            version = { hostVersion(context) },
            ai = aiGateway,
            mesh = mesh
        )
    }

    fun isRunning(): Boolean = running

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        synchronized(lock) {
            if (running) return
            rootDir.mkdirs()
            token = store.loadOrCreate()
            startedAt = System.currentTimeMillis()

            // The agent script ships in the APK; the operator may also have put
            // libnode.so there. Copy both into app-private storage (D24). A
            // failure here is reported, not fatal: MISSING_RUNTIME is a valid
            // state (S3-R035).
            val runtime = AssetRuntime.install(context)
            if (runtime.installed.isNotEmpty()) {
                log("runtime installed: ${runtime.installed.joinToString(",")}")
            }
            if (runtime.rejected.isNotEmpty()) {
                log("runtime rejected: ${runtime.rejected.joinToString(",")}")
            }
            runtime.error?.let { log("runtime install error: $it") }

            if (GromPrefs.isQuarantined(context)) {
                policy.onFatal(GromPrefs.quarantineReason(context) ?: "persisted quarantine")
            }

            val server = LoopbackHttpServer(port, Router(this), logSink)
            try {
                // Throws if the bind is not loopback — deliberately fatal — or
                // when another process already holds the port (D85).
                server.start()
            } catch (_: BindException) {
                // Two Groms on one phone (standalone + an embedding host) share
                // the default control port unless the host chose another one.
                // Report the fact instead of letting the platform's message be
                // mistaken for a network or signature problem (S4-R051).
                lastError = "${ControlPort.PORT_IN_USE}: port $port is already in use on loopback"
                log("control plane refused: ${lastError()}")
                safeAudit("server.start.refused", ControlPort.PORT_IN_USE)
                throw ApiException(
                    503,
                    ControlPort.PORT_IN_USE,
                    "port $port is already in use on loopback; another server is running"
                )
            }
            httpServer = server
            running = true

            // The public data plane is independent of the Node agent: it must
            // stay usable when the runtime is missing, and its own start failure
            // is reported rather than taking the control plane down.
            // The engine is always the server role. A host that embeds it is
            // expected to start the service deliberately (D55); nothing binds
            // unless that happens.
            superServer.start()
            // Discovery is opt-in and inert until a configuration enables it;
            // it never runs while the server is stopped.
            runCatching { mesh.start() }
                .onFailure { log("mesh start failed: ${it.javaClass.simpleName}") }

            scheduler.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)

            if (!policy.isQuarantined()) {
                startAgentLocked()
            }
            safeAudit("server.start", "port=${server.boundPort}")
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            safeAudit("server.stop", "")
            running = false
            runCatching { mesh.stop() }
            superServer.stop()
            fleet.onDataPlaneStopped()
            sshTunnels.stopAll()
            httpServer?.stop()
            httpServer = null
            agent?.stop()
            agent = null
            agentPort = -1
            policy.onStopped()
            scheduler.shutdownNow()
        }
    }

    fun stateName(): String = when (policy.state) {
        AgentState.READY -> "READY"
        AgentState.STARTING, AgentState.BACKOFF -> "STARTING"
        AgentState.MISSING_RUNTIME -> "MISSING_RUNTIME"
        AgentState.QUARANTINED -> "QUARANTINED"
        AgentState.STOPPED -> "STOPPED"
    }

    fun tokenFingerprint(): String = if (token.isEmpty()) "\u2014" else Token.fingerprint(token)

    fun lastError(): String? = lastError

    // ------------------------------------------------------------------ GromApi

    /**
     * The contract version this runtime implements.
     *
     * It is the SDK's own constant, not a literal: before this was shared the
     * number drifted twice (`apiVersion()` answered 3 while the contract said 5,
     * then 7 while it said 8), and a client that gates on `>= N` silently keeps
     * the old behaviour. `tools/test-policy` also pins the plumbing.
     */
    override fun apiVersion(): Int = GromClient.API_VERSION

    override fun authorize(candidate: String?): Boolean =
        candidate != null && Token.matches(token, candidate)

    override fun health(): Map<String, Any?> = buildMap {
        put("api", 1)
        put("state", stateName())
        put("runtime", if (runtime().present) "node" else "missing")
        put("pid", agent?.pid ?: -1L)
        put("uptimeMs", if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt)
        put("version", appVersion)
    }

    override fun status(): Map<String, Any?> {
        synchronized(lock) {
            val missing = runtime().missingFile()
            return health() + mapOf(
                "restarts" to policy.restarts,
                "crashes" to policy.crashCount(),
                "lastCrashMs" to (policy.lastCrashAt ?: -1L),
                "quarantineReason" to policy.quarantineReason,
                "port" to port,
                "enabled" to GromPrefs.startOnBoot(context),
                "runtimeDir" to runtime().binaryDirectory.absolutePath,
                "missing" to missing,
                "agentPort" to agentPort,
                "lastError" to lastError,
                "projects" to projectRegistry.summary(),
                // Sanitised: enabled, configured and the measured last delivery
                // (D67). The chat id and the token stay on the device.
                "notifications" to notifier.publicStatus(),
                "super" to superServer.status()
            )
        }
    }

    override fun auditTail(limit: Int): Map<String, Any?> {
        val entries = auditStore.tail(limit)
        return mapOf(
            "entries" to entries.map { entry ->
                mapOf(
                    "seq" to entry.seq,
                    "ts" to entry.ts,
                    "actor" to entry.actor,
                    "action" to entry.action,
                    "detail" to entry.detail,
                    "prev" to entry.prev,
                    "hash" to entry.hash
                )
            },
            "head" to auditStore.headHash()
        )
    }

    override fun auditVerify(): Map<String, Any?> {
        val result = auditStore.verify()
        return mapOf(
            "ok" to result.ok,
            "entries" to result.entries,
            "head" to result.head,
            "brokenAt" to result.brokenAt
        )
    }

    override fun rotateToken(): String {
        synchronized(lock) {
            val fresh = store.rotate()
            token = fresh
            audited("token.rotate", "fingerprint=${Token.fingerprint(fresh)}")
            return fresh
        }
    }

    override fun startAgent(): Map<String, Any?> {
        synchronized(lock) {
            requireRuntimeOrThrow()
            if (policy.isQuarantined()) {
                throw ApiException(
                    409,
                    "QUARANTINED",
                    "agent is quarantined after ${policy.crashCount()} crashes; reset first"
                )
            }
            if (policy.state == AgentState.READY) {
                throw ApiException(409, "ALREADY_RUNNING", "agent is already running")
            }
            audited("agent.start", "state=${stateName()}")
            startAgentLocked()
            return status()
        }
    }

    override fun stopAgent(): Map<String, Any?> {
        synchronized(lock) {
            agent?.stop()
            agent = null
            agentPort = -1
            policy.onStopped()
            backoffUntil = 0L
            audited("agent.stop", "")
            return status()
        }
    }

    override fun restartAgent(): Map<String, Any?> {
        synchronized(lock) {
            requireRuntimeOrThrow()
            if (policy.isQuarantined()) {
                throw ApiException(409, "QUARANTINED", "agent is quarantined; reset first")
            }
            agent?.stop()
            agent = null
            agentPort = -1
            audited("agent.restart", "")
            startAgentLocked()
            return status()
        }
    }

    override fun resetAgent(): Map<String, Any?> {
        synchronized(lock) {
            agent?.stop()
            agent = null
            agentPort = -1
            policy.reset()
            backoffUntil = 0L
            GromPrefs.setQuarantined(context, null)
            audited("agent.reset", "quarantine cleared")
            return status()
        }
    }

    /**
     * Re-reads the persisted super-server configuration and restarts only that
     * listener. The control plane and the agent are untouched, so an operator
     * can change exposure without dropping the supervisor.
     */
    fun reloadSuperServer(): Map<String, Any?> {
        synchronized(lock) {
            if (!running) {
                throw ApiException(
                    503,
                    "SERVER_STOPPED",
                    "start the server before reloading the access configuration"
                )
            }
            superServer.reload()
            return status()
        }
    }

    /**
     * Validates, persists and applies a data plane document sent by an
     * embedding host (D86).
     *
     * The document is the same one the standalone screen writes
     * (`SuperServerConfig.toJson()`), and it is checked by the same contract:
     * [SuperServerConfigInput.parse] refuses exactly what the screen refuses,
     * with the same code. A refusal is `400` and nothing is written, so a host
     * cannot half-apply a policy. Secrets travel in, never out: the returned
     * status carries `passwordSet` and key ids only (D59).
     */
    fun saveSuperConfig(raw: String?): Map<String, Any?> {
        synchronized(lock) {
            val parsed = SuperServerConfigInput.parse(raw, port)
            if (parsed is ConfigParse.Refused) {
                throw ApiException(400, parsed.code, parsed.message)
            }
            val accepted = (parsed as ConfigParse.Parsed).config
            superServer.saveConfig(accepted)
            safeAudit(
                "super.config.saved",
                "enabled=${accepted.enabled} bind=${accepted.bind.name} auth=${accepted.auth.name}"
            )
            return status()
        }
    }

    // ------------------------------------------------- AI gateway (API 10)

    /**
     * The operator-facing AI document (D94): providers with their endpoints and
     * whether a key is stored, the catalogue, and the last measured probe. No
     * key — sealed or plain — ever leaves the device through this method (D95).
     */
    fun aiStatusJson(): String = Json.encode(aiStatusDocument())

    /**
     * Adds or changes a provider. A plaintext `apiKey` in the document is sealed
     * here and only the ciphertext is written; omitting it keeps the stored key.
     */
    fun aiSaveProviderJson(raw: String?): String {
        synchronized(lock) {
            val (input, problem) = AiInputs.provider(raw.orEmpty())
            if (problem != null) throw ApiException(problem.status, problem.code, problem.message)
            val key = input!!.apiKey
            val sealed = if (key == null) {
                null
            } else {
                aiStore.sealKey(key)
                    ?: throw ApiException(
                        503,
                        "AI_KEY_SEAL_FAILED",
                        "the platform refused to seal the API key"
                    )
            }
            val edited = requireAiEdit(AiConfigEditor.upsertProvider(aiGateway.load(), input, sealed))
            persistAi(edited, "ai.provider.saved", "id=${input.id} keySet=${key != null}")
            return aiStatusJson()
        }
    }

    fun aiRemoveProvider(id: String): String {
        synchronized(lock) {
            val edited = requireAiEdit(AiConfigEditor.removeProvider(aiGateway.load(), id.trim()))
            persistAi(edited, "ai.provider.removed", "id=${id.trim()}")
            return aiStatusJson()
        }
    }

    fun aiSaveModelJson(raw: String?): String {
        synchronized(lock) {
            val (input, problem) = AiInputs.model(raw.orEmpty())
            if (problem != null) throw ApiException(problem.status, problem.code, problem.message)
            val edited = requireAiEdit(AiConfigEditor.upsertModel(aiGateway.load(), input!!))
            persistAi(edited, "ai.model.saved", "id=${input.id} provider=${input.providerId}")
            return aiStatusJson()
        }
    }

    fun aiRemoveModel(id: String): String {
        synchronized(lock) {
            val edited = requireAiEdit(AiConfigEditor.removeModel(aiGateway.load(), id.trim()))
            persistAi(edited, "ai.model.removed", "id=${id.trim()}")
            return aiStatusJson()
        }
    }

    /** `{enabled?: Boolean, defaultModel?: String|null}`; absent fields are kept. */
    fun aiConfigureJson(raw: String?): String {
        synchronized(lock) {
            val root = JsonParser.parseObjectOrNull(raw.orEmpty())
                ?: throw ApiException(400, "AI_BAD_INPUT", "the document must be a JSON object")
            var config = aiGateway.load()
            (root["enabled"] as? Boolean)?.let { enabled ->
                config = requireAiEdit(AiConfigEditor.withEnabled(config, enabled))
            }
            if (root.containsKey("defaultModel")) {
                config = requireAiEdit(
                    AiConfigEditor.withDefaultModel(config, root["defaultModel"] as? String)
                )
            }
            persistAi(config, "ai.configured", "enabled=${config.enabled} default=${config.defaultModel}")
            return aiStatusJson()
        }
    }

    /**
     * One measured probe against the provider's own endpoint (D96). It is the
     * only way a provider can look healthy: the configuration alone proves
     * nothing, and nothing here runs in the background.
     */
    fun aiProbeProviderJson(id: String): String =
        Json.encode(aiGateway.probe(id.trim()))

    private fun aiStatusDocument(): Map<String, Any?> = AiDocuments.operatorStatus(
        config = aiGateway.load(),
        loadError = aiGateway.lastError() ?: aiStore.lastError,
        probes = aiGateway.probeSnapshot()
    )

    private fun requireAiEdit(edit: AiEdit): app.grom.server.superserver.plugins.ai.AiGatewayConfig =
        when (edit) {
            is AiEdit.Ok -> edit.config
            is AiEdit.Failure -> throw ApiException(
                edit.problem.status,
                edit.problem.code,
                edit.problem.message
            )
        }

    private fun persistAi(
        config: app.grom.server.superserver.plugins.ai.AiGatewayConfig,
        action: String,
        detail: String
    ) {
        aiStore.save(config)?.let { throw ApiException(it.status, it.code, it.message) }
        safeAudit(action, detail)
    }

    override fun exec(op: String, argsJson: String?): HttpResponse {
        val port: Int
        val bearer: String
        synchronized(lock) {
            requireRuntimeOrThrow()
            if (policy.isQuarantined()) {
                throw ApiException(409, "QUARANTINED", "agent is quarantined; reset first")
            }
            if (policy.state != AgentState.READY) {
                throw ApiException(503, "AGENT_NOT_READY", "agent state is ${stateName()}")
            }
            if (op !in ALLOWED_OPS) {
                throw ApiException(400, "UNKNOWN_OP", "op is not whitelisted: $op")
            }
            port = agentPort
            if (port <= 0) {
                // The agent said READY but has not announced a control port.
                // Refusing is the only truthful answer; inventing "ok" is not.
                throw ApiException(503, "AGENT_NOT_READY", "agent has not announced its control port")
            }
            bearer = token
        }

        // The socket call happens outside the lock: a slow agent must not stall
        // the supervisor or the other endpoints.
        val body = argsJson?.takeIf { it.isNotBlank() } ?: "{\"op\":\"$op\"}"
        return when (val result = agentClient.exec(port, bearer, body)) {
            is AgentClient.Result.Answered -> {
                synchronized(lock) { audited("agent.exec", "op=$op status=${result.status}") }
                HttpResponse(result.status, result.body)
            }
            is AgentClient.Result.Unavailable -> {
                synchronized(lock) {
                    safeAudit("agent.exec.failed", "op=$op reason=${result.reason}")
                }
                throw ApiException(502, "AGENT_UNAVAILABLE", "agent did not answer: ${result.reason}")
            }
        }
    }

    // ------------------------------------------------------- projects (API 3)

    fun projects(): List<Map<String, Any?>> = projectRegistry.summary()

    /**
     * Registers or refreshes a host project. The claim is stored, not believed:
     * liveness is derived from the heartbeat in [ProjectRecord.state].
     */
    fun announceProject(raw: Map<String, Any?>): String {
        val record = projectRegistry.announce(raw)
        audited("project.announce", "id=${record.id} version=${record.version}")
        return Json.encode(mapOf("ok" to true, "project" to record.toJson(System.currentTimeMillis())))
    }

    fun removeProject(id: String) {
        projectRegistry.remove(id)
        audited("project.remove", "id=$id")
    }

    /**
     * Runs a key/value op inside one project's namespace (D61).
     *
     * The caller names its project and an *unscoped* key; the prefix is added
     * here, never taken from the request. Only `kv.get` and `kv.put` are
     * reachable, and only for a project that is actually registered — so this
     * surface cannot be used to run arbitrary agent ops or to reach a
     * neighbour's data.
     *
     * @throws ApiException with a stable code; the caller maps it to `GROM_E_*`.
     */
    fun projectExec(projectId: String, op: String, argsJson: String?): Pair<Int, String> {
        ProjectNamespace.requireValidProjectId(projectId)
        if (projectRegistry.list().none { it.id == projectId }) {
            throw ApiException(
                403,
                "PROJECT_NOT_REGISTERED",
                "project '$projectId' has not announced itself to this engine"
            )
        }
        // Op whitelist, key ownership and value size are decided by the pure
        // builder, so the same rules are unit-tested without a binder.
        val body = ProjectNamespace.buildRequest(projectId, op, argsJson)
        val response = exec(op, body)
        return response.status to response.body
    }

    /** The sanitised hand-off document (D53); never a serialisation of the config. */
    fun configExportJson(): String {
        val superStatus = superServer.status()
        val lightning = (superStatus["lightningHealth"] as? Map<*, *>)
            ?.entries
            ?.filter { it.key is String }
            ?.associate { it.key as String to it.value }
            ?: emptyMap()
        return ConfigExport.render(
            config = superServer.currentConfig(),
            lightning = lightning,
            projects = projectRegistry.summary(),
            bridge = sshTunnels.statusForServer(),
            notifications = notifier.publicStatus(),
            fleet = FleetStatus.document(fleet.identityOrNull())
        )
    }

    // ------------------------------------------------------- SSH bridge (D58)

    fun sshStatusJson(): String {
        val targets = sshTunnels.statusForUi()
        val active = targets.count { it["state"] == "connected" }
        return Json.encode(
            mapOf(
                "available" to SshKeys.isSupported(),
                // No saved-target cap (D64); only live tunnels are limited.
                "maxActive" to SshTarget.MAX_ACTIVE,
                "advisoryTargets" to SshTarget.ADVISORY_TARGETS,
                "activeCount" to active,
                // The operator's current server, so the UI can mark it without
                // guessing from order (D107).
                "currentId" to sshStore.activeId(),
                // The advisory is derived from what is saved and connected, and
                // a saved server is never reported as running.
                "advisory" to when {
                    targets.size > SshTarget.ADVISORY_TARGETS ->
                        "EXTRA_SERVERS_IDLE"
                    else -> null
                },
                "targets" to targets
            )
        )
    }

    /**
     * Generates an Ed25519 pair. The private key text is returned exactly once
     * so the caller can hand it back to [sshSaveTargetJson], where the store
     * seals it. It is never persisted in clear text and never appears in status.
     */
    fun sshGenerateKeyJson(): String {
        val generated = SshKeys.generate()
            ?: return Json.encode(
                mapOf(
                    "ok" to false,
                    "error" to mapOf(
                        "code" to "SSH_UNAVAILABLE",
                        "message" to "this build has no Ed25519 provider"
                    )
                )
            )
        return Json.encode(
            mapOf(
                "ok" to true,
                "publicKeyLine" to generated.publicKeyLine,
                "privateKeyText" to generated.privateKeyText
            )
        )
    }

    fun sshSaveTargetJson(draftJson: String): String {
        val raw = JsonParser.parseObjectOrNull(draftJson)
            ?: throw ApiException(400, "BAD_TARGET", "body must be a JSON object")
        val auth = SshAuth.fromWire(raw["auth"] as? String)
            ?: throw ApiException(400, "BAD_AUTH", "auth must be 'key' or 'password'")
        val draft = SshTargetDraft(
            id = (raw["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            label = (raw["label"] as? String).orEmpty(),
            host = (raw["host"] as? String).orEmpty(),
            port = (raw["port"] as? Number)?.toInt() ?: 22,
            username = (raw["username"] as? String).orEmpty(),
            auth = auth,
            password = raw["password"] as? String,
            privateKey = raw["privateKey"] as? String,
            publicKeyLine = (raw["publicKeyLine"] as? String)?.takeIf { it.isNotBlank() },
            clearSecret = raw["clearSecret"] as? Boolean ?: false,
            remoteForwardPort = (raw["forwardPort"] as? Number)?.toInt()
                ?: app.grom.server.ssh.SshTarget.DEFAULT_FORWARD_PORT,
            enabled = raw["enabled"] as? Boolean ?: true
        )
        val saved = sshStore.upsert(draft)
        audited("ssh.target.save", "id=${saved.id} label=${saved.label}")
        return Json.encode(mapOf("ok" to true, "target" to saved.toUiJson()))
    }

    fun sshDeleteTarget(id: String) {
        sshTunnels.disconnect(id)
        sshStore.delete(id)
        audited("ssh.target.delete", "id=$id")
    }

    fun sshClearPin(id: String) {
        sshTunnels.disconnect(id)
        sshStore.clearPin(id)
        audited("ssh.hostkey.clear", "target=$id")
    }

    /** Blocking network work; callers must not run this on the main thread. */
    fun sshConnectJson(id: String, trustHostKey: String?): String =
        sshResultJson(sshTunnels.connect(id, trustHostKey))

    /**
     * Checks one saved server without opening a tunnel (D106). It reports the
     * stage that failed, so "wrong password" no longer looks like "network
     * unreachable", and it works with the data plane off.
     *
     * Blocking network work; callers must not run this on the main thread.
     */
    fun sshVerifyJson(id: String, trustHostKey: String?): String =
        sshResultJson(sshTunnels.verify(id, trustHostKey))

    /** Marks the operator's current server, or clears it with an empty [id]. */
    fun sshSetActiveJson(id: String?): String {
        val chosen = id?.trim()?.takeIf { it.isNotEmpty() }
        sshStore.setActive(chosen)
        audited("ssh.active", "id=${chosen ?: "-"}")
        return Json.encode(mapOf("ok" to true, "currentId" to sshStore.activeId()))
    }

    private fun sshResultJson(result: SshTunnelManager.Result): String {
        val body: Map<String, Any?> = when (result) {
            is SshTunnelManager.Result.Connected -> mapOf(
                "ok" to true,
                "state" to "connected",
                "fingerprint" to result.fingerprint,
                "learned" to result.learned
            )
            is SshTunnelManager.Result.Verified -> mapOf(
                "ok" to true,
                "state" to "verified",
                "fingerprint" to result.fingerprint,
                "learned" to result.learned,
                // False means the credentials are proven but the reverse port
                // was not attempted because the data plane is off. It is never
                // reported as checked.
                "forwardChecked" to result.forwardChecked
            )
            is SshTunnelManager.Result.HostKeyUnverified -> mapOf(
                "ok" to false,
                "state" to "unverified",
                "code" to "HOST_KEY_UNVERIFIED",
                "fingerprint" to result.fingerprint
            )
            is SshTunnelManager.Result.HostKeyMismatch -> mapOf(
                "ok" to false,
                "state" to "failed",
                "code" to "HOST_KEY_MISMATCH",
                "pinned" to result.pinned,
                "fingerprint" to result.observed
            )
            is SshTunnelManager.Result.Failed -> linkedMapOf<String, Any?>(
                "ok" to false,
                "state" to "failed",
                "code" to result.code,
                "message" to result.message,
                // Which stage broke: resolve, connect, handshake, auth or
                // forward. The UI turns this into a sentence (D105).
                "stage" to result.stage.wireName
            ).apply {
                // What the server said it accepts, when an authentication
                // attempt actually received that reply (D110). Absent — not
                // empty — means it was not measured.
                if (result.authMethods.isNotEmpty()) {
                    put("authMethods", result.authMethods)
                }
            }
        }
        return Json.encode(body)
    }

    fun sshDisconnect(id: String) {
        sshTunnels.disconnect(id)
    }

    // -------------------------------------------------- Notifications (D65)

    /**
     * The settings as the operator sees them over the Binder: the switch, the
     * chat id and whether a token is set. The token itself is never returned by
     * any method (S4-R042).
     */
    fun notifySettingsJson(): String {
        val settings = notifyStore.settings()
        return Json.encode(
            linkedMapOf(
                "enabled" to settings.enabled,
                "chatId" to settings.chatId,
                "tokenSet" to (settings.tokenSealed != null),
                "lastDelivery" to notifier.lastDelivery()
            )
        )
    }

    /** Creates or updates the settings; the token is sealed before the write. */
    fun notifySaveSettingsJson(body: String): String {
        val raw = JsonParser.parseObjectOrNull(body)
            ?: throw ApiException(400, "BAD_NOTIFY", "body must be a JSON object")
        val draft = NotifyDraft(
            enabled = raw["enabled"] as? Boolean ?: false,
            chatId = (raw["chatId"] as? String).orEmpty(),
            // A null token keeps the stored one; only an explicit clear removes it.
            token = raw["token"] as? String,
            clearToken = raw["clearToken"] as? Boolean ?: false
        )
        val saved = notifyStore.save(draft)
        audited("notify.save", "enabled=${saved.enabled} configured=${saved.isConfigured()}")
        return Json.encode(
            linkedMapOf(
                "ok" to true,
                "enabled" to saved.enabled,
                "configured" to saved.isConfigured()
            )
        )
    }

    /** Sends one message now; `ok` is true only for a provider `{"ok":true}`. */
    fun notifyTestJson(): String {
        val result = notifier.sendTest()
        if (result["ok"] == true) {
            safeAudit("notify.test", "ok=true")
        } else {
            safeAudit("notify.test", "ok=false code=${result["code"]}")
        }
        return Json.encode(result)
    }

    // ------------------------------------------------ Server network (API 11)

    /** The operator view: roster, measured state and nearby, unpaired hints. */
    fun meshStatusJson(): String = mesh.statusJson()

    fun meshConfigureJson(body: String): String = mesh.configureJson(body)

    /** One bounded scan; no scheduler, no polling (D99). */
    fun meshScanJson(): String = mesh.scanJson()

    /** Re-reads each paired server's connect document now (blocking). */
    fun meshRefreshJson(): String = app.grom.server.http.Json.encode(mesh.refresh())

    /** Changes which services one paired server may use here (D101). */
    fun meshSetSharesJson(body: String): String {
        val raw = app.grom.server.http.JsonParser.parseObjectOrNull(body)
            ?: throw ApiException(400, "BAD_SHARE_LIST", "body must be a JSON object")
        val nodeId = (raw["nodeId"] as? String)?.trim()
            ?: throw ApiException(400, "BAD_SHARE_LIST", "nodeId is required")
        val services = (raw["services"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim() }
            ?: throw ApiException(400, "BAD_SHARE_LIST", "services must be a list")
        return fleet.setSharesJson(nodeId, services)
    }

    /** Calls a service on a paired server through this device's own key (D100). */
    fun meshCallJson(body: String): String {
        val answer = mesh.call(body, "binder")
        return app.grom.server.http.Json.encode(answer.payload)
    }

    // ------------------------------------------------------- Fleet (API 6)

    fun nodeIdentityJson(): String = fleet.identityJson()

    fun nodeResetIdentityJson(confirmation: String): String = fleet.resetIdentity(confirmation)

    fun peersJson(): String = fleet.peersJson()

    /**
     * Opens a pairing session. Pairing is reachable from another device only
     * while the public data plane is running on a non-loopback bind; refusing
     * early is better than handing out a code that nothing can reach.
     */
    fun peerPairStartJson(): String {
        requirePairingDataPlane()
        return fleet.startPairingJson()
    }

    private fun requirePairingDataPlane() {
        if (superServer.status()["running"] != true) {
            throw ApiException(
                409,
                "PAIR_DATA_PLANE_STOPPED",
                "turn on Public access before pairing with another device"
            )
        }
        if (superServer.currentConfig().bind == BindMode.LOOPBACK) {
            throw ApiException(
                409,
                "PAIR_LOOPBACK_ONLY",
                "this device is reachable on loopback only; choose the LAN bind to pair"
            )
        }
        // Peers authenticate by Ed25519 key, so a password-only data plane
        // would hand out a pairing that can never be used. Refusing here is
        // better than a lamp that is red for a reason the operator cannot see.
        if (superServer.currentConfig().auth == AuthMode.PASSWORD) {
            throw ApiException(
                409,
                "PAIR_KEY_AUTH_REQUIRED",
                "paired devices sign their requests; choose Key or Both for the public API"
            )
        }
    }

    fun peerPairPendingJson(): String = fleet.pendingJson()

    fun peerPairCompleteJson(body: String): String = fleet.completePairingJson(body)

    /** Blocking network work; callers must not run this on the main thread. */
    fun peerJoinJson(body: String): String {
        requirePairingDataPlane()
        return fleet.joinJson(body)
    }

    fun peerConfirmJson(body: String): String = fleet.confirmJson(body)

    /**
     * One-way scoped trust for an inbound reader such as a hub (D77). No data
     * plane is required: the reader is the one that connects.
     */
    fun peerGrantJson(body: String): String = fleet.grantJson(body)

    fun peerRemove(nodeId: String) {
        fleet.removePeer(nodeId)
    }

    fun peerRefreshJson(): String = fleet.refreshJson()

    // ------------------------------------------------------ Doctor (D80-D84)

    /**
     * The run marker is written by the service, not by the runtime: `onCreate`
     * and `onDestroy` are the only two places that know a session began or ended
     * cleanly, and neither is a timer (D80, D83).
     */
    fun beginRunSession(nowMs: Long = System.currentTimeMillis()): Unit {
        runMarker.beginSession(nowMs)
    }

    fun endRunSession(nowMs: Long = System.currentTimeMillis()) {
        runMarker.endSessionCleanly(nowMs)
    }

    /**
     * A full measurement: the platform facts, the engine's own status and the
     * marker, merged and judged by [DoctorFacade]. Synchronous and cheap; the UI
     * calls it on a worker thread because of the binder calls, not the cost.
     */
    fun doctorJson(): String {
        val report = DoctorFacade.measure(
            probe = doctorProbe.facts(),
            status = if (running) status() else null,
            marker = runMarker.current()
        )
        return DoctorDocument.json(report)
    }

    /**
     * The three fields `/v1/status` may carry (D84). Deliberately narrow: a hub
     * polling status learns *why* a phone is being throttled, not what the phone
     * is doing. Nothing free-text, no exit history, no addresses.
     */
    fun doctorStatusBlock(): Map<String, Any?> = DoctorDocument.statusBlock(
        DoctorFacade.facts(
            probe = doctorProbe.policyFacts(),
            marker = runMarker.current()
        )
    )

    // --------------------------------------------------- Lightning probe (D52)

    fun lightningProbeJson(): String {
        if (superServer.status()["running"] != true) {
            return Json.encode(
                mapOf(
                    "ok" to false,
                    "code" to "DATA_PLANE_STOPPED",
                    "message" to "enable the public data plane and configure a backend first"
                )
            )
        }
        val result = superServer.probeLightning()
        if (result["ok"] == true) {
            audited("lightning.probe", "reachable=${result["reachable"]}")
        } else {
            safeAudit("lightning.probe.failed", "code=${result["code"]}")
        }
        return Json.encode(result)
    }

    /**
     * The address a tunnel should forward to. It is the data plane's real
     * listening address, so a tunnel never points at a port nothing serves.
     */
    /**
     * The port the server network may announce (D99).
     *
     * A beacon is only useful when another phone can actually reach this data
     * plane, so the answer is -1 for a stopped or loopback-only plane. Grom does
     * not silently move the plane off loopback to make discovery work: turning
     * on the LAN bind stays an explicit operator decision.
     */
    private fun meshDataPlanePort(): Int {
        if (!running) return -1
        val config = superServer.currentConfig()
        if (!config.enabled || !superServer.isRunning()) return -1
        if (config.bind == BindMode.LOOPBACK) return -1
        return config.port
    }

    private fun meshDataPlaneLive(): Boolean = meshDataPlanePort() > 0

    private fun localDataPlane(): Pair<String, Int>? {
        if (!running) return null
        val config = superServer.currentConfig()
        if (!config.enabled) return null
        return when (val resolved = SuperBindPolicy.resolve(config.bind)) {
            is BindResolution.Bound ->
                if (SuperBindPolicy.isWildcard(resolved.address)) {
                    "127.0.0.1" to config.port
                } else {
                    resolved.display to config.port
                }
            is BindResolution.Rejected -> null
        }
    }

    // ---------------------------------------------------------------- supervisor

    private fun tick() {
        try {
            synchronized(lock) {
                if (!running) return
                policy.onHealthyTick()

                if (policy.state == AgentState.STARTING && policy.startTimedOut()) {
                    handleCrash("start timeout")
                    return
                }

                val current = agent
                if (current != null && policy.state in LIVE_STATES && !current.isAlive()) {
                    handleCrash("agent exited (code=${current.exitValue()})")
                    return
                }

                if (policy.state == AgentState.BACKOFF && System.currentTimeMillis() >= backoffUntil) {
                    startAgentLocked()
                }
            }
        } catch (unexpected: Throwable) {
            lastError = unexpected.message
            log("supervisor tick failed: ${unexpected.javaClass.simpleName}")
        }
    }

    private fun startAgentLocked() {
        if (policy.isQuarantined()) return
        val runtime = runtime()
        if (!runtime.present) {
            policy.onMissingRuntime()
            lastError = "missing ${runtime.missingFile()}"
            return
        }
        val process = ensureAgent()
        val state = policy.startRequested(true)
        if (state != AgentState.STARTING) return
        agentPort = -1
        val launched = process.launch(port, token)
        if (!launched) {
            handleCrash("launch failed")
            return
        }
        lastError = null
        log("agent launched pid=${process.pid}")
    }

    private fun handleCrash(cause: String) {
        val state = policy.onCrash(cause)
        agent?.stop()
        agent = null
        agentPort = -1
        val quarantined = state == AgentState.QUARANTINED
        // The outcome is part of the record: a notification must not say
        // "restarting" for a crash that quarantined the agent.
        val detail = "cause=${cause} crashes=${policy.crashCount()} quarantined=$quarantined"
        safeAudit("agent.crash", detail)
        if (state == AgentState.QUARANTINED) {
            GromPrefs.setQuarantined(context, policy.quarantineReason)
            log("agent quarantined: ${policy.quarantineReason}")
        } else {
            backoffUntil = System.currentTimeMillis() + policy.backoffMs()
        }
    }

    private fun ensureAgent(): NodeAgentProcess {
        agent?.let { return it }
        val created = NodeAgentProcess(
            runtimeDirectory = runtimeDir,
            homeDirectory = File(context.filesDir, "home"),
            tmpDirectory = context.cacheDir,
            parentPid = android.os.Process.myPid().toLong(),
            logger = { line -> log(redactor().redact(line)) },
            onStdoutLine = { line -> onAgentLine(line) },
            onStderrLine = { line -> log("agent: ${redactor().redact(line)}") }
        )
        agent = created
        return created
    }

    private fun onAgentLine(rawLine: String) {
        val line = redactor().redact(rawLine)

        // The channel line carries the agent's control port. It is additive to
        // GROM_READY, and its pid must match the child we spawned.
        AgentChannel.observe(rawLine)?.let { ready ->
            synchronized(lock) {
                val expected = agent?.pid ?: -1L
                if (AgentChannel.matches(ready, expected)) {
                    agentPort = ready.port
                    safeAudit("agent.channel", "port=${ready.port}")
                } else {
                    handleCrash("agent channel pid mismatch")
                }
            }
            return
        }

        when (val observation = HealthGate.observe(rawLine)) {
            is HealthGate.Observation.Ready -> {
                synchronized(lock) {
                    val expected = agent?.pid ?: -1L
                    if (expected > 0 && observation.pid != expected) {
                        handleCrash("ready pid mismatch")
                        return
                    }
                    policy.onReady()
                    safeAudit("agent.ready", "pid=${observation.pid}")
                }
            }
            is HealthGate.Observation.Fatal -> {
                synchronized(lock) {
                    policy.onFatal(observation.message)
                    GromPrefs.setQuarantined(context, policy.quarantineReason)
                    agent?.stop()
                    agent = null
                    safeAudit("agent.fatal", redactor().redact(observation.message))
                }
            }
            HealthGate.Observation.Unknown -> log("agent: $line")
        }
    }

    // ------------------------------------------------------------------- helpers

    /**
     * D34: the executable is taken from the APK's native library dir, because
     * Android denies `execve()` of files in app-private storage for apps
     * targeting API 29+. The JavaScript entry point is a data file and stays in
     * [runtimeDir]; a binary shipped under `assets/` (legacy operator path, see
     * [AssetRuntime]) is used only when jniLibs supplied none.
     */
    private fun runtime(): AgentLauncher.RuntimeFiles {
        val nativeDir = context.applicationInfo.nativeLibraryDir?.let { File(it) }
        val nativeBinary = nativeDir?.let { File(it, AgentLauncher.BINARY_NAME) }
        return if (nativeDir != null && nativeBinary != null && nativeBinary.isFile) {
            AgentLauncher.RuntimeFiles(
                directory = runtimeDir,
                binaryDirectory = nativeDir,
                libraryDirectory = nativeDir
            )
        } else {
            AgentLauncher.RuntimeFiles(runtimeDir)
        }
    }

    private fun requireRuntimeOrThrow() {
        val missing = runtime().missingFile()
        if (missing != null) {
            throw ApiException(
                503,
                "MISSING_RUNTIME",
                "agent runtime is not installed: missing $missing (see scripts/build-node-android.sh)"
            )
        }
    }

    private fun audited(action: String, detail: String) {
        try {
            auditStore.append(actor = actor(), action = action, detail = redactor().redact(detail))
        } catch (failure: Exception) {
            // S2-R034: no state change without a record.
            throw ApiException(500, "AUDIT_FAILED", "audit append failed; request aborted")
        }
        notifier.onAudit(action, detail)
    }

    private fun safeAudit(action: String, detail: String) {
        try {
            auditStore.append(actor = "supervisor", action = action, detail = redactor().redact(detail))
        } catch (_: Exception) {
            log("audit append failed for $action")
        }
        // One funnel for both audit paths, so a supervisor event and an API
        // action notify identically (D65). Only *recorded* actions reach this,
        // so a message never describes something the chain does not contain.
        notifier.onAudit(action, detail)
    }

    /** Gives the queue a bounded chance to flush before the process goes away. */
    fun flushNotifications(timeoutMs: Long) {
        notifier.flush(timeoutMs)
    }

    /**
     * Stops the notification worker (D66).
     *
     * Called **after** the final flush, and only at the end of the service's
     * life: closing earlier would drop the "server stopped" message the worker
     * is still sending. It used to be called nowhere at all, which left one
     * `grom-notify` thread per service instance alive for the rest of the
     * process — invisible in the standalone app (one instance per process) and a
     * slow leak in a host that stops and starts the embedded server.
     *
     * The join is bounded by the notifier, so a delivery stuck on its own
     * timeout cannot hold `onDestroy` open beyond the documented window.
     */
    fun closeNotifications() {
        runCatching { notifier.close() }
    }

    private fun actor(): String = "api:${tokenFingerprint()}"

    private fun redactor(): Redactor = Redactor(listOfNotNull(token.ifEmpty { null }))

    private fun log(message: String) {
        // The sink decides where this goes; the token never reaches it (D26).
        logSink(message)
    }

    companion object {
        private const val TICK_MS = 1_000L

        /** A readable default label for this device's identity (D70). */
        private fun deviceLabel(): String =
            NodeIdentity.normalizeLabel(android.os.Build.MODEL?.replace('_', ' '))

        /** The version of the APK that hosts this code. */
        fun hostVersion(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        private val LIVE_STATES = setOf(AgentState.STARTING, AgentState.READY)

        /** Single source of truth, shared with the agent (see AgentChannel). */
        private val ALLOWED_OPS = AgentChannel.OPS
    }
}
