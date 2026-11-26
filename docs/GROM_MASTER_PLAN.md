# Grom — master plan

**A universal, lightweight Android server: the phone as an agent, never as an
authority for money.**

This document is the architecture and delivery plan for Grom *after* F1 and the
super server. It starts from what the tree actually contains at `0.6.0`, states
what Dukoi (`github.com/thisuxhq/dukoi`, Apache-2.0) does that is worth keeping,
and specifies the components that turn a supervised loopback API into a server
that can host an operator's own applications. It is a plan: where a statement
describes the tree it is marked; where it describes something that does not exist
yet, it is marked too, and names the gate that will prove it.

## 0. How to read this document

### 0.1 Evidence tags

A claim without a tag is a statement about the plan, not about the product.

| tag | meaning |
|---|---|
| `[SHIPPED]` | In the tree at `0.6.0` and covered by an automated check. |
| `[PLANNED]` | Designed here, not built. The proving gate is named. |
| `[RESEARCH]` | Feasible, but a licence, size, ABI or platform question is open. Doing it is a spike, not a task. |
| `[DEVICE]` | Cannot be settled without hardware. Nothing in this repository has run on a physical device. |
| `[DECISION]` | Needs a new `Dxx` entry before code, because it changes an accepted decision. |

### 0.2 Where the truth lives

| question | document |
|---|---|
| why a decision was made | `docs/000-decisions.md` (D1–D54) |
| product and security model | `docs/specs/S1-product-and-security-model.md` |
| control-plane API | `docs/specs/S2-loopback-http-api.md` |
| agent runtime and supervision | `docs/specs/S3-agent-runtime-and-supervision.md` |
| Binder surface | `docs/specs/S4-ipc-and-sdk-contract.md` |
| data plane | `docs/specs/S5-super-server.md` |
| runtime build and measured evidence | `docs/RUNTIME.md` |
| operator guides | `docs/SUPER-SERVER.md`, `docs/LIGHTNING.md` |
| delivery order and gates | `docs/GROM_IMPLEMENTATION_ROADMAP.md` |

### 0.3 What is actually proven today

| claim | status |
|---|---|
| Two flavours build, one binds | `[SHIPPED]` — `tools/test-policy` 5, 6 |
| Control plane loopback-only, token-gated, audited | `[SHIPPED]` — D5, D8, D10 |
| Binder gated by signature twice | `[SHIPPED]` — D15, `tools/verify-ipc-signing` |
| Node 24.22.3 bionic arm64 runs, including `node:sqlite` | `[SHIPPED]` as a host-executed artefact — `scripts/fetch-node-android.sh --verify` |
| Super server: password + Ed25519, SSE, plugins, bounds | `[SHIPPED]` at JVM-test level — S5, `SuperServerIntegrationTest` |
| Reference config (`npub`, `relays`, `remoteBridge`) | `[SHIPPED]` — D52–D54, S5-R032–R034; validated, reference-only, in authenticated `/v1/status` and the sanitized Copy-config export |
| Embeddable engine (`:engine`) + API 4 | `[SHIPPED]` at JVM-test level — D55/D61, `GromEmbed`, `GromProject`, `ProjectNamespace`, `ProjectRegistry`; manifests proven by policy checks 25/26, namespace by 30 |
| Dashboard lamps and collapsible sections | `[SHIPPED]` at JVM-test level — D56, `DashboardModelTest` |
| Quiet dashboard + header menu, invite then explanation in the menu | `[SHIPPED]` — D68/D69, `MenuModelTest` + `HelpModelTest` |
| Project registry with heartbeat-derived liveness | `[SHIPPED]` at JVM-test level — D57, `ProjectRegistryTest` |
| Reverse SSH bridge (TOFU, `tcpip-forward` only) | `[SHIPPED]` against a real in-process SSH server — D58, `SshTunnelManagerTest`; **not** on hardware |
| Bridge/project status, no operator addresses off-device | `[SHIPPED]` — D59, `ConfigExportTest` sentinel, `SshTunnelManagerTest` |
| Anything on a physical phone | **not proven.** No `adb` in the build environment |
| Application Manager, other runtimes, multi-device | `[PLANNED]` |

The most important sentence here: **Grom has no device evidence.** Every line
about foreground-service survival, task killers, Doze and boot is hypothesis
until measured on hardware, and this plan never converts hypothesis into promise.

## A. Vision of a universal Android server

### A.1 The product

Grom turns a phone the operator already owns into a small, honest, self-hosted
server: it hosts one or more of *their* applications, exposes them through a
network path *they* chose, records what it did, and reports failure as failure.
Universal means not tied to one workload (a static site, a Node service, a bot, a
media index, a read-only wallet dashboard) and not tied to a vendor's cloud.
Lightweight means no dependency it cannot audit, no scheduler it does not need,
and no process it cannot explain.

### A.2 The five invariants

These decide every question below. They are inherited from the tree, not invented
here.

1. **Honesty over availability.** A capability is measured or absent.
   `501 NOT_SUPPORTED` names the capability (S5-R022); `503` means unconfigured
   or unreachable (D43); `502` means the backend failed. Grom never returns
   `{"ok":true}` for work it did not do (D33), which is why the agent's
   `503 STORAGE_UNAVAILABLE` reaches the caller unchanged.
2. **Least exposure by default.** The control plane is loopback-only and fatal to
   start otherwise (D5, S1-R010). The data plane is off, loopback and
   credential-less until a human turns it on (S5-R002); a non-loopback bind
   requires a credential and the wildcard also requires an acknowledgement (D41).
3. **No mandatory cloud.** No account, telemetry, crash reporter or update daemon
   (S1-R003, S1-R004, D16). One operator-initiated HTTPS GET to the release feed
   is the app's entire outbound traffic (D37); the only other exception is a
   backend URL the operator typed at runtime (D48).
4. **No custody, ever.** Grom observes, relays and executes whitelisted local
   operations. It holds no balance, signs no payment, decides no settlement (D1,
   D43).
5. **Every state change is on the record.** One hash-chained entry per
   authenticated mutation, success or failure (D10, S1-R014, S2-R033), verifiable
   while the server runs (S1-R016).

### A.3 What "universal" must not mean

| tempting interpretation | why rejected |
|---|---|
| "Run anything, including untrusted code" | all workloads share Grom's UID; no per-app UID, seccomp or namespace. Deploy equals `adb shell` access (§P.7) |
| "Replace a VPS" | a phone behind CGNAT has no inbound reachability (§L.6) |
| "Orchestrate a fleet" | §M is a registry and explicit placement, not a scheduler: no containers, CRDs, etcd or mesh |
| "24/7 uptime" | Android may kill, throttle or vendor-kill the process (§H.6) |
| "A wallet" | D1: the Lightning plugin forwards to an operator-run backend |

### A.4 Target shape

```
                    ┌──────────────────── one Android process ───────────────────┐
  operator / CLI ──▶│  /grom/v1/*  control   127.0.0.1:47001  bearer token        │
  paired device  ──▶│  /v1/*       data      LOOPBACK|LAN|PUBLIC  password|Ed25519│
  tunnel/browser ──▶│                                                             │
                    │  Supervisor (Kotlin) ─ Agent (Node) ─ Application Manager    │
                    │        │                  │            └─ runtimes + ports   │
                    │        └─ audit (hash chain) ─ SQLite ─ Keystore              │
                    └─────────────────────────────────────────────────────────────┘
```

Two HTTP planes and one Binder surface, one credential per plane, one audit chain,
one supervisor. Applications are *children* of the supervisor, not siblings: whatever
happens to an application, the control plane keeps answering health (D39,
S5-R003).

### A.5 Success criteria

The plan is done when an operator can, with no root, no Google account and no
cloud subscription: (1) install one APK and see an honest state within one
supervisor tick (S1-R018); (2) deploy an application from a local directory or an
operator-supplied HTTPS archive, watch the health gate pass, reach it on a stable
port; (3) roll it back in one action and find the rollback in the audit log;
(4) expose exactly that application — never the control plane — through a tunnel
they chose, with a data-plane credential; (5) pair a second device and lose
nothing on the first when it disappears; (6) export an encrypted backup and
restore it onto a fresh install with a per-file verification report.

Each is a phase gate in §T and a task in §U.

## B. Dukoi repository analysis

### B.1 What Dukoi is, as observed

Dukoi (`github.com/thisuxhq/dukoi`, Apache-2.0) is a self-hosting system for
Android built from a Kotlin Android application, a Node/TypeScript agent, a CLI
and a dashboard. The observations below come from reading that repository's code
and are limited to what the code shows; no claim is made about its behaviour on a
device, its release quality or its roadmap. Observed elements: a foreground
service plus supervisor managing an app process; a Node/TypeScript agent started
by the Android side; executables packaged as `lib*.so` and run from
`nativeLibraryDir` — the same Android 10+ restriction Grom solved as D34;
deployments into release directories behind a stable port; a health check before
traffic is switched; the last five releases retained; Ed25519 pairing and signed
responses; Keystore-encrypted environment secrets; mDNS discovery; tunnels opened
only to application ports, never the admin API.

### B.2 Good, improve, replace, missing, not needed

The classification is about **fit for Grom's invariants**, not Dukoi's quality. A
row can be "good" and still improved.

| # | Dukoi element | Class | Reasoning | Grom response |
|---|---|---|---|---|
| 1 | Foreground service + supervisor | **good** | the only architecture Android permits for a long-lived local server; the platform owns the lifetime | kept: Kotlin FGS with `specialUse` (D21) `[SHIPPED]` |
| 2 | Node/TypeScript agent | **improve** | the agent is capability; it must not supervise itself or a crash takes the supervisor too | supervisor is Kotlin in the service; the agent is a supervised child with a state machine (S3-R032) and quarantine (D13) `[SHIPPED]` |
| 3 | Kotlin Android app | **good** | same platform, same pinned toolchain | kept, split into two roles (D2) `[SHIPPED]` |
| 4 | CLI | **good** | operators script; a CLI over the loopback API costs nothing and adds no listener | kept as a `grom` CLI on the existing API `[PLANNED]` T-31 |
| 5 | Dashboard | **not needed** | another listener, session store and XSS surface for a device whose urgent question is "is it running?" | replaced by the monochrome single screen (D36) plus a terminal CLI; no SPA |
| 6 | `lib*.so` from `nativeLibraryDir` | **good** | exactly right: exec of app-private files is denied to apps targeting API 29+ | kept, extended to every runtime (D34, S3-R001) `[SHIPPED]` for Node |
| 7 | Release directories + stable port | **good** | atomic deploy and a URL that survives a version bump | kept: `apps/<id>/releases/<version>/` + pointer file, port from the allocator `[PLANNED]` |
| 8 | Health check before switching traffic | **good, improve** | one probe can pass on a process that dies a second later, and a stable port forbids true blue/green | candidate on a temporary port, two consecutive passes, cutover, probe again, auto-rollback `[PLANNED]` §G.4 |
| 9 | Last five releases for rollback | **good** | cheap insurance; disk is the only cost | kept: five by default, pruned oldest-first and only after healthy `[PLANNED]` §G.5 |
| 10 | Ed25519 pairing, signed responses | **good, improve** | the right primitive, but pairing without scopes is all-or-nothing | kept with node identity, per-pairing scopes, explicit approval `[PLANNED]` §M.2, §P.4 |
| 11 | Keystore-encrypted env secrets | **good** | the one place a stored secret is worth more than the process reading it; Grom already does this for the LNbits key | generalised from one backend key to per-app secret sets (D44) `[PLANNED]` §G.7 |
| 12 | mDNS discovery | **good, convenience only** | zero-config is real value, but discovery must never authenticate | kept as a hint: NSD announces, a human approves the key `[PLANNED]` §M.5 |
| 13 | Tunnels reach app ports, never the admin API | **good — the best idea there** | it is the difference between exposing a service and exposing a device's control plane | kept as an invariant and made structural: the data plane proxies `/v1/apps/{id}/*`; the control plane has no route to a tunnel `[PLANNED]` §C.1, §L.5 |
| 14 | The agent can be killed | **improve** | a TS supervisor and a TS child share one failure domain | not copied: the Android process supervises, the agent is a leaf (D12, D13) `[SHIPPED]` |
| 15 | Admin API on the LAN without TLS | **replace** | a bearer token in clear text on Wi-Fi is a token an attacker owns | control plane stays loopback-only, fail-closed (D5); off-device access is the data plane behind a terminator (D47) `[SHIPPED]` |
| 16 | No runtime-adapter contract (always Node) | **build new** | a universal server cannot hard-code one runtime or pretend a Python app is a Node app | `RuntimeAdapter` + Application Manager with honest per-runtime verdicts (§I, §J) `[PLANNED]` |
| 17 | Cloudflared effectively required | **replace** | a mandatory provider contradicts S1-R004 and makes reachability someone else's product decision | no bundled tunnel, no preferred vendor; a matrix of operator-owned paths (§L) |
| 18 | No explicit capability model | **build new** | without it, an unsupported operation returns something plausible | capabilities are advertised; unsupported is `501` naming it (S5-R021, S5-R022, D45) `[SHIPPED]` for two plugins |
| 19 | Bun-specific tooling | **not needed** | couples the platform to one JS runtime's cadence | Node 24.22.3 LTS, assembled and pinned (D35) |
| 20 | Cloudflare / telemetry integrations | **not needed** | D16, S1-R003; a crash reporter is also an exfiltration path for a token | absent; `tools/test-policy` fails on a hardcoded outbound URL `[SHIPPED]` |
| 21 | One server per device | **good** | phones are single-operator; multi-tenancy needs UIDs Grom does not have | kept and stated as a limitation (§P.7) |

### B.3 What this analysis does not claim

It does not claim Dukoi fails on a device (nothing was run), that Grom is more
secure in general (only narrower in the five invariants), or that any Dukoi file
was copied. If code is ever borrowed, §R.5 applies.

## C. What to keep from Dukoi

| keep | lands in | must not be carried over |
|---|---|---|
| Foreground service owns the lifetime | `service/GromService.kt` `[SHIPPED]` | a second "agent supervisor" inside the agent |
| Release directories + stable port | `apps/ReleaseStore.kt`, `apps/PortAllocator.kt` `[PLANNED]` | version-suffixed ports or URLs |
| Health-gated cutover | `apps/HealthGate.kt`, `apps/AppSupervisor.kt` `[PLANNED]` | a single probe deciding a deploy |
| Five releases for rollback | `apps/ReleaseStore.kt` `[PLANNED]` | unbounded retention on a 32 GB phone |
| Ed25519 pairing, signed requests | `cluster/NodeIdentity.kt`, `cluster/Pairing.kt` `[PLANNED]` | implicit trust on first sight |
| Keystore-sealed secrets | `store/SecretBox.kt` `[SHIPPED]` | a plaintext fallback when the key is gone |
| mDNS discovery | `cluster/Discovery.kt` `[PLANNED]` | discovery treated as authentication |
| Tunnels reach app ports only | data-plane gateway `[PLANNED]` | any route from a tunnel to `/grom/v1/*` |
| CLI ergonomics | `cli/` (no new listener) `[PLANNED]` | a CLI that edits files behind the API's back |

**C.1 The gateway is the only route to an application.** `[DECISION]` An app
binds `127.0.0.1:<allocated>`. The world, if any, reaches it only through
`/v1/apps/{id}/proxy/*` on the data plane, which authenticates like every other
`/v1` route (S5-R010), applies the same body, connection and rate limits
(S5-R011, S5-R012, S5-R016), opens one loopback socket per request, and strips
every `X-Grom-*` header. This satisfies Dukoi's insight structurally: the control
plane's path prefix does not exist on the data plane at all.

**C.2 Rollback is a file operation.** The pointer is a text file holding a
version; flipping it and restarting the app is the whole rollback. It is testable
without a device, which matters because §V has very few device tests.

## D. What to improve

Dukoi's shape is right; its defaults are looser than Grom's threat model allows.
Each improvement is a behaviour Grom already enforces somewhere, extended.

| # | Before (as observed) | After (Grom) | Enforced by |
|---|---|---|---|
| 1 | supervisor and workload in one process family | supervisor is the Android process; each workload is a child with a state machine, backoff, quarantine | S3-R032, `SupervisorPolicy`; per-app reuse |
| 2 | one token, one scope | roles and per-pairing scopes; a viewer cannot deploy | §P.4, `ScopeTest`, new static invariant |
| 3 | one probe at cutover | two passes on a temporary port, cutover, two more on the stable port, auto-rollback | `HealthGateTest`, `DeployIntegrationTest` |
| 4 | pairing grants access | named scopes, explicit approval, revocation, no auto-approve | `PairingTest`, `pair.*` audit entries |
| 5 | secrets in the process env | sealed at rest, one JSON line on stdin, never argv or env | D11, `tools/test-agent`; per-app variant §G.7 |
| 6 | no capability model | every adapter and plugin advertises capabilities; unsupported is `501` naming it | S5-R021, S5-R022 |
| 7 | tunnel vendor is the deployment story | networking is a matrix of operator-owned paths with the TLS point named | §L, `docs/SUPER-SERVER.md` §5 |
| 8 | public admin surface possible | control plane cannot leave loopback; the server refuses to start otherwise | D5, `BindPolicy`, `tools/test-policy` 5 |
| 9 | config applied by restarting everything | data plane reloads alone (`reloadSuperServer()`, IPC API 2); an app restart is scoped to one app | S4-R029 `[SHIPPED]` |
| 10 | retention is just a number | prune only healthy, non-current releases; report bytes reclaimed | `ReleaseStoreTest` |

**D.1 The sharpest improvement: scopes.** Today a password session or a signed key
can do anything `/v1` allows (S5-R019). That is proportionate while `/v1` exposes
two read-mostly plugins; it stops being proportionate once `/v1/apps/{id}/*` can
deploy or roll back.

`[DECISION]` Add additive role binding to the credential records:

```json
{ "keyId": "…", "publicKey": "…", "enabled": true, "role": "operator",
  "scopes": ["status.read", "apps.read", "apps.restart"] }
```

- `role` and `scopes` are optional; a key without them behaves exactly as today,
  so no existing client breaks.
- A password session is `owner` (a human who knows the password runs the device)
  but only while `bind != PUBLIC`, where it is `operator`, because a
  shared-password deployment must not rotate the device's identity keys.
- Deploy, rollback, key rotation, pairing approval and backup export require an
  explicit scope. Absent scope means `403 INSUFFICIENT_SCOPE`, not "allow".
- Adding scopes is additive to `v1` (S1-R029); removing or renaming one is `v2`.

## E. What to replace or build new

### E.1 Build new

| component | why it cannot be an extension | first gate |
|---|---|---|
| **Application Manager** (`apps/`) | the tree supervises one hard-coded child; N apps need a manifest, releases, ports, health, rollback | `AppManifestTest`, `DeployIntegrationTest` |
| **RuntimeAdapter** (`apps/runtime/`) | Node is not universal, and the contract must be honest about which runtimes are proven | `RuntimeAdapterTest`, per-runtime smoke |
| **Port allocator** (`apps/PortAllocator.kt`) | two fixed ports exist; N apps need a policy, collision detection, a refusal path | `PortAllocatorTest` |
| **Secret sets** (`store/SecretSet.kt`) | one sealed backend key exists; apps need named sets | `SecretSetTest` |
| **Node identity + pairing** (`cluster/`) | no device identity exists today | `NodeIdentityTest`, `PairingTest` |
| **Node registry** (`cluster/NodeRegistry.kt`) | no multi-device concept exists | `NodeRegistryTest` |
| **Backup container** (`backup/`) | no export format exists | `BackupRoundTripTest` |
| **CLI** (`cli/`) | operator ergonomics on the existing API | `tools/test-cli` |
| **Static host** (`apps/StaticHost.kt`) | "universal" needs the trivial case trivial | `StaticHostTest` |
| **Gateway** (`superserver/AppGateway.kt`) | §C.1 | `GatewayTest` |

### E.2 Replace

| replace | with | reason |
|---|---|---|
| one fixed child in `ServerRuntime` | `AgentSupervisor` + `AppSupervisor`, one policy class | one policy, two kinds of child; the agent keeps S3-R017…R024 |
| plugin-only vocabulary | plugin + adapter registries on a shared capability and error model | a plugin wraps a backend; an adapter *is* a runtime. Same honesty rules, different code |
| status assembled inline | `StatusDocument` builder with a schema version | `/v1/status` is already a contract (S5-R024) and must not grow by accretion |

### E.3 Explicitly not built

| not built | why |
|---|---|
| web dashboard / SPA | B.2 row 5; the UI is the phone, the CLI covers scripting |
| container runtime | apps cannot create namespaces or cgroups without root; a fake container is a process with a different name |
| scheduler daemon | §M.6: explicit placement |
| bundled tunnel client | §L.3: no vendor, no binary |
| telemetry, crash reporting, update daemon | D16, S1-R003 |
| dynamic plugin loading | S5-R020: nothing that arrives over a network can add an implementation |
| multi-tenant isolation | §P.7: one UID |
| "24/7 mode" | §H.6: the platform does not offer it |

## F. Grom Core architecture

### F.1 Four layers, one-way dependencies

```
 surface      GromActivity, cli/
 transport    http/ (control)      superserver/ (data)      service/ (FGS, Binder)
 capability   agent/ supervisor    apps/ manager             cluster/ (peers)
 policy+infra core/ (pure Kotlin)  store/  audit/  backup/
```

D30 keeps this testable: **policy is plain Kotlin with no Android imports**, so it
runs in JVM tests. `BindPolicy`, `SuperBindPolicy`, `SupervisorPolicy`,
`AuditChain`, `HealthGate`, `Redactor`, `Token`, `SuperServerConfig`, `Nip19`,
`RelayUrl` and `RemoteBridgeHint` are already in that category. Every new
decision point in this plan — port allocation, manifest validation, health-gate
arithmetic, scope checks, backup manifests — must be written the same way, or it
is untestable on a machine without a phone, which is every machine this
repository is built on (§0.3).

### F.2 Subsystems

| subsystem | responsibility | today | new code | durable state | status |
|---|---|---|---|---|---|
| **Device identity** | stable local node id + Ed25519 identity key, nothing from hardware | none | `cluster/NodeIdentity.kt` | `identity.json` (public), Keystore-sealed seed | `[PLANNED]` |
| **App manager** | install, version, deploy, roll back, uninstall | none | `apps/ApplicationManager.kt` | `apps/<id>/` | `[PLANNED]` |
| **Service manager** | supervise children, restart policy, dependency order, shutdown | `ServerRuntime.tick()` for one child | `apps/AppSupervisor.kt`, `apps/DependencyGraph.kt` | in memory + `state.json` | `[PLANNED]` |
| **Process supervision** | spawn, capture stdout, timeouts, kill the group | `agent/NodeAgentProcess.kt` `[SHIPPED]` | generic `proc/ChildProcess.kt` | none | partial |
| **Ports** | allocate, reserve, collide, bind loopback | 47001, 47002 fixed | `apps/PortAllocator.kt` | `ports.json` | `[PLANNED]` |
| **Config** | validated, atomic, versioned documents | `super-server.json` (D40) | `config/*.json`, same atomic write | `*.json` | partial |
| **Env** | per-app environment, sealed secrets over stdin | `AgentConfig` (D11) `[SHIPPED]` | `apps/AppEnv.kt`, `store/SecretSet.kt` | sealed blobs only | partial |
| **Logs** | bounded, redacted, in-memory ring per child | `Redactor`, 200-line ring (S3-R010) | `apps/AppLogRing.kt` | none (S3-R031) | partial |
| **Monitoring** | status document, `/proc` sampling, SSE events | `/v1/status`, `EventBus` `[SHIPPED]` | `apps/ResourceSampler.kt` | none | partial |
| **API** | two planes, versioned, bounded | `http/`, `superserver/` `[SHIPPED]` | `/v1/apps/*`, `/grom/v1/apps/*` | none | partial |
| **Authz** | token, sessions, signatures, roles, scopes | token + sessions + signatures `[SHIPPED]` | `auth/Scope.kt` | credential records | partial |
| **Backup** | export, encrypt, import, verify | none | `backup/` | archives | `[PLANNED]` |
| **Recovery** | quarantine clear, rollback, restore, cold rebuild | quarantine reset `[SHIPPED]` | rollback + restore | derived from files | partial |
| **Networking** | bind policy, LAN address choice, exposure doc | `BindPolicy`, `SuperBindPolicy` `[SHIPPED]` | gateway, nothing else | config only | partial |
| **Adapter management** | register, enable, configure, health, version check | `PluginRegistry`, 2 plugins `[SHIPPED]` | `adapters/AdapterRegistry.kt` | `adapters.json` | partial |

Notes that the table cannot hold:

- **Identity.** A 32-byte Ed25519 seed from `SecureRandom`, sealed with the
  Android Keystore AES-256-GCM path already used for the LNbits key (D44,
  `SecretBox`). `nodeId` is the first 16 bytes of SHA-256 over the public key as
  32 lowercase hex. No IMEI, no `ANDROID_ID`, no serial — the D50 reasoning. The
  reference `npub` is **not** this identity: it is a public key the operator typed
  for an external bridge, never used to sign (D52).
- **Ports.** `ports.json` maps `appId → port`. Take the manifest port if free,
  else the lowest free port in `47100..47199`. A bind failure is `PORT_IN_USE`
  and the app is `FAILED`; Grom never silently moves an app to a new port,
  because that breaks every URL the operator wrote down.
- **Config.** Every document follows `super-server.json`: temp file, owner-only
  permissions, `rename` over the old, 64 KiB load bound, safe defaults plus a
  reported error (D40, S5-R008). A config file never half-exists and never
  becomes a listener by accident.
- **Logs.** In-memory, 200 lines, 4 KiB per line, token and `Authorization`
  redacted (S3-R010, S3-R029, S3-R030). Per-app rings are dropped on stop
  (S3-R031); Grom does not persist app logs, because S1-R025 forbids persisting
  user content beyond audit metadata.
- **Recovery.** Four explicit paths: quarantine reset (S3-R021 `[SHIPPED]`),
  rollback (§G.5), restore (§O.4), cold rebuild from files after process death. A
  cold start must reconstruct which release is current and which apps were meant
  to run from pointer files and `app.json`, and from nothing else.

### F.3 Module boundaries, and what enforces them

| rule | statement | enforcement |
|---|---|---|
| R1 | `core/` and policy in `apps/` import no `android.*` | the JVM test source set must compile without Android stubs |
| R2 | `http/` never imports `superserver/`, and `superserver/` never imports the control router | new import-direction invariant (today only `tools/test-policy` 24) |
| R3 | only `BindPolicy` and `SuperBindPolicy` may name a bind address | `tools/test-policy` 5, 6, 7 |
| R4 | only the update feed may be a compiled-in URL; a deploy URL is operator-supplied at runtime | `tools/test-policy` 17, D37, D48 |
| R5 | nothing under `apps/`, `cluster/`, `backup/` may read `grom-token` | new invariant; the token is read only by `SecureStore`/`ServerRuntime` |
| R6 | a new adapter touches its own package, a test, and one registry list element | new invariant: `ServerRuntime`/`SuperRouter` must not name an adapter class |
| R7 | every child gets a fresh environment and secrets over stdin | `tools/test-agent`, extended to apps |
| R8 | all new decision logic is a pure Kotlin class with a JVM test | review, D30 |

R6 is the concrete meaning of "adding an adapter must not require core changes"
(§J.3). If an adapter needs `ServerRuntime` edited, the contract is wrong.

### F.4 Filesystem layout after Phase 3

```
<ApplicationInfo.nativeLibraryDir>/     read-only, executable
  libnode.so                            Node 24.22.3 arm64 bionic      [SHIPPED]
  libgromruntime-python.so              Phase 3, if the spike passes   [RESEARCH]
<files>/grom/
  grom-token                            0600 bearer token              [SHIPPED]
  super-server.json                     data-plane policy, atomic      [SHIPPED]
  identity.json                         nodeId + public key            [PLANNED]
  pairings.json, ports.json, adapters.json                              [PLANNED]
  node/grom-agent.js                    agent entry point              [SHIPPED]
  audit/audit.log, audit.log.1, audit.head   hash chain, 4 MiB rotation [SHIPPED]
  apps/<id>/{app.json,current,previous,releases/<v>/,data/,secrets.bin,state.json}
  backup/<timestamp>.grombak                                            [PLANNED]
```

Everything here is app-private storage, and the rule that does not move is D34:
**nothing executable is ever written under `files/`.** Every shipped runtime is
an ELF named `lib*.so` in `jniLibs`; `files/` holds only data.

### F.5 Thread inventory

| thread | created by | purpose | bound |
|---|---|---|---|
| `grom-super-accept` | `SuperServer` `[SHIPPED]` | accept loop | 1 |
| `grom-super-http-1..4` | `SuperServer` `[SHIPPED]` | request workers | 4 |
| `grom-super-sse-*` | `SuperServer` `[SHIPPED]` | one per event stream | `maxSseClients` (default 4) |
| control accept + workers | `LoopbackHttpServer` `[SHIPPED]` | `/grom/v1` | 8 connections (S2-R006) |
| `grom-supervisor` | `ServerRuntime` `[SHIPPED]` | one tick per second | 1 |
| `grom-app-<id>` | `AppSupervisor` `[PLANNED]` | stdout reader + wait per app | 1 per app |
| `grom-resource` | `ResourceSampler` `[PLANNED]` | `/proc` sampling every 30 s | 1 |

The total is bounded by the connection cap, the SSE cap, one thread per app, and a
fixed handful of accept, worker and supervisor threads. A design that starts a
thread per event dies on a phone; the existing SSE bound (one thread per stream,
hard cap 4, 30-minute lifetime) is the model to copy, not to loosen.

## G. Application Manager

### G.1 What an application is

A directory tree plus a manifest. One id (`^[a-z][a-z0-9-]{1,31}$`, the plugin id
rule of S5-R020 reused), one runtime, one entry point, one port, one data
directory. Not a container, not a package-manager entry, not visible to the
launcher.

### G.2 Sources

| source | mechanism | outbound | phase |
|---|---|---|---|
| **local directory** | operator copies a tree, imports it through the system picker (`ACTION_OPEN_DOCUMENT_TREE`); no broad storage permission | none | 2 |
| **ZIP / tar.gz** | same picker, one archive, extracted to staging with traversal checks | none | 2 |
| **HTTPS archive** | operator types a URL; one GET on tap, `https` only, no redirects, 64 MiB cap, optional SHA-256 pin | one, operator-initiated | 2 `[DECISION]` |
| **git** | needs a client. `git` is GPL-2.0 (§R.3); `isomorphic-git` (MIT) inside Node is the permissive path but becomes the first bundled JS dependency, which D3 resists | one | 3 `[RESEARCH]` |
| **binary / package** | a binary cannot be executed where it downloads (§G.2 note) | none | 3 `[DEVICE]` |

The HTTPS row is the second place Grom initiates a connection. It is written
exactly like D48: URL from operator configuration at runtime, never compiled in;
`https` only; no redirects; bounded body and timeout; `tools/test-policy` 17 keeps
passing because no absolute URL enters the source.

**The exec restriction, honestly.** A downloaded binary cannot be executed where
it lands: Android denies `execve` of app-private files for apps targeting API 29+
(D34). Two answers exist: (a) the binary is a runtime that ships in the APK and
the download is only *data* for it; (b) the application runs on a Linux node (§Q).
There is no third answer.

### G.3 The manifest

`app.json`, ≤ 32 KiB, validated before anything is written:

```json
{ "schema": 1, "id": "blog", "version": "1.4.2", "runtime": "node",
  "entry": "server.js", "args": ["--port", "${PORT}"], "port": 47120,
  "health": { "path": "/healthz", "expect": 200, "timeoutMs": 2000,
              "intervalMs": 5000, "graceMs": 30000 },
  "env": { "NODE_ENV": "production" }, "secrets": ["BLOG_TOKEN"],
  "resources": { "maxMemoryMb": 256, "startTimeoutMs": 20000 },
  "dependsOn": [], "persistent": ["data/uploads"],
  "expose": { "gateway": true } }
```

| rule | refusal |
|---|---|
| unknown `schema` | `UNSUPPORTED_SCHEMA` |
| bad or duplicate `id` | `BAD_ID`, `DUPLICATE_ID` |
| version not `MAJOR.MINOR.PATCH`, or already present | `BAD_VERSION`, `VERSION_EXISTS` |
| runtime not registered | `UNKNOWN_RUNTIME` |
| `entry` escapes the release dir after normalisation | `PATH_ESCAPE` |
| port outside `47100..47199`, or reserved | `BAD_PORT`, `PORT_IN_USE` |
| dependency cycle or unknown id | `DEPENDENCY_CYCLE`, `UNKNOWN_DEPENDENCY` |
| `maxMemoryMb` outside `32..2048` | `BAD_MEMORY_LIMIT` |
| `health.path` not starting with `/` | `BAD_HEALTH_PATH` |
| manifest over 32 KiB | `MANIFEST_TOO_LARGE` |

`${PORT}` is substituted; an unknown `${...}` is refused rather than left
literal. There is no shell: `args` is argv, never a command string (S3-R004's
rule, applied to apps).

### G.4 Deploy: the health gate

A stable port forbids true blue/green, so the gate is staged:

```
1. extract to releases/<version>.staging/, validate, resolve + reserve the port
2. start the candidate on a TEMPORARY port
3. health: 2 consecutive successes within graceMs
     fail -> kill, delete staging, state=FAILED, audit deploy.failed
4. stop the current release (SIGTERM, 2 s, SIGKILL)   [brief downtime]
5. rename staging -> releases/<version>/
6. start on the stable port
7. health again: 2 consecutive successes
     fail -> stop, restart the release `current` still names, audit deploy.rolled_back
8. write `previous`, then `current` last, prune, audit deploy.ok
```

Two properties are testable on the JVM with a fixture server: **the pointer is
written last**, so a crash at any earlier step leaves the old release current;
and **a failed gate never deletes the previous release**.

### G.5 Versions, rollback, retention

- `current` and `previous` are text files holding one version. `previous` is
  written only at step 8, so it always names a release that passed its gate.
- Rollback = stop, swap the two pointer files, start, health-gate, audit
  `deploy.rollback`. No rebuild, no network.
- Retention: current + previous + up to three older **healthy** releases (five by
  default, `retainReleases`). Pruning never removes an unproven release and never
  removes `previous`; bytes reclaimed are reported, because a phone has 32 GB.
- Disk pre-check: refuse with `INSUFFICIENT_SPACE` when the archive is larger than
  free space minus 256 MiB. Advisory on Android, but the refusal is real.
- Uninstall: stop, delete `releases/`, keep `data/` for 7 days under
  `apps/.trash/<id>/`, release the port, audit `app.uninstalled` with the
  retained path. A second call inside the window purges. Nothing is destroyed on
  the first click.

### G.6 Logs, status, secrets

`GET /v1/apps/{id}/logs?limit=N` returns the ring (≤ 200 lines, redacted). `GET
/v1/apps/{id}` returns the manifest summary, state, port, pid, uptime, restarts,
last `(exit code, signal)` and last health result; a process killed by `SIGKILL`
after a stop request is `STOPPED`, not a crash (S3-R017).

Secrets: names `^[A-Z][A-Z0-9_]{0,63}$`, values ≤ 8 KiB, ≤ 32 entries, stored
sealed (alias `grom-app-secrets`), delivered over stdin, never in the
environment. Rotation reseals and restarts, auditing names only. An unreadable
set refuses the start with `SECRETS_UNREADABLE` unless the manifest says
`missingSecret: "allow"`; the default is refuse, because starting a service
without its credential usually fails worse later.

### G.7 Ports, URLs and the gateway

| thing | value |
|---|---|
| app bind | `127.0.0.1:<allocated>`, loopback only, always |
| gateway | `/v1/apps/{id}/proxy/<path>` (data plane), `/grom/v1/apps/{id}/proxy/<path>` (control, operator only) |
| stability claim | the port does not change across deploys; the gateway path does not change across versions |

The gateway strips hop-by-hop headers (`Connection`, `Transfer-Encoding`,
`Upgrade`, `Keep-Alive`) and every `X-Grom-*`, caps the body at `maxBodyBytes`
(S5-R011), applies the per-client rate limit (S5-R016), streams with
`Connection: close`. WebSockets are not proxied — no upgrade path exists on either
plane (D46) — and an upgrade is `501 NOT_SUPPORTED` naming `websocket`.

### G.8 Resources: what can and cannot be enforced

| intent | mechanism | verdict |
|---|---|---|
| memory ceiling | sample `/proc/<pid>/statm` every 30 s; restart above `maxMemoryMb × 1.25` twice in a row | **soft**; no per-app cgroup, and the LMK may kill the group first `[DEVICE]` |
| CPU ceiling | none | **cannot**: no `RLIMIT_CPU`, no cgroups, no `nice` below our own UID |
| start timeout | kill the candidate after `startTimeoutMs` | **hard** |
| fd leaks | sample `/proc/<pid>/fd`; warn at 512 | advisory |
| disk | per-app quota counted by walking `data/` on a schedule; enforced only at deploy | **advisory**, the app can write around it |
| battery | nothing; Android decides | **cannot**, beyond FGS type and user settings |

Three of six are advisory, one cannot be done, one is hard, one is the platform's
call. That is the defensible answer to "resources".

## H. Service Manager

### H.1 States

The same shape as the agent's (S3-R032), so one mental model covers both:

| from | event | to |
|---|---|---|
| `STOPPED` | start, manifest valid | `STARTING` |
| `STOPPED` | start, manifest invalid | `FAILED` |
| `STARTING` | health gate passes | `READY` |
| `STARTING` | timeout or exit | `BACKOFF` or `QUARANTINED` |
| `READY` | exit | `BACKOFF` or `QUARANTINED` |
| `READY` | stop | `STOPPED` |
| `BACKOFF` | timer | `STARTING` |
| any | human reset | `STOPPED` |
| any | runtime/adapter missing | `FAILED` with `RUNTIME_MISSING` |

`FAILED` is terminal for a deploy attempt, not for the operator: a corrected
manifest is a new deploy. `QUARANTINED` is sticky across process death and boot,
like the agent's (S1-R022).

### H.2 Restart, backoff, watchdog

- Crash window 10 minutes; three crashes → `QUARANTINED`; backoff 1, 2, 4, 8 s —
  the same numbers as S3-R018/R019, so an operator learns one schedule.
- A run of 60 s removes the oldest crash, so a long-lived app does not carry an
  ancient count (S3-R022).
- Liveness every 5 s (process alive) and health every `intervalMs` are separate:
  an app alive but failing health three times is restarted, and that counts as a
  crash. The alternative is a "running" app serving 500s and a status document
  that lies (S1-R017).
- The watchdog uses the manifest's HTTP health endpoint rather than a port probe.
  That is a deliberate departure from S3-R014: a port probe cannot tell
  "listening" from "ready", and for an application the declared health endpoint
  is better evidence than either. The agent keeps the stdout-line handshake
  (S3-R011, S3-R025) because that contract is normative and must not drift.

### H.3 Dependency ordering

`dependsOn` is a DAG validated at install. Start order is a topological sort with
stable tie-breaking by id, so two boots produce the same order. A dependency that
is `FAILED` or `QUARANTINED` blocks dependants with `DEPENDENCY_DOWN` rather than
starting them into a guaranteed failure. Stop order is the reverse. A dependency
restart does **not** cascade by default (`cascadeRestart: false`): cascading turns
one crash into a storm, and the operator can ask for it explicitly.

### H.4 Graceful shutdown

`SIGTERM` to the process group, wait `shutdownGraceMs` (default 2 000, the
agent's existing value), then `SIGKILL` (S3-R024). Stopping the server stops apps
first, then the agent, then the listeners — the reverse of start, matching
S2-R036. A shutdown that exceeds the grace period is audit-logged with the app id
and elapsed time.

### H.5 Android constraints — the part that cannot be promised

| constraint | what Android does | what Grom does | evidence |
|---|---|---|---|
| **Doze** | idle + stationary defers background network and jobs | keeps an FGS; offers a battery-optimisation exemption only as an explicit, explained action | `[DEVICE]` |
| **App Standby buckets** | unused apps get fewer jobs and alarms | FGS keeps a higher bucket while running; buckets still move | `[DEVICE]` |
| **FGS types** | API 34+ requires a type; `specialUse` needs a declared subtype | `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (D21) | `[SHIPPED]`, lint |
| **FGS start restrictions** | API 31+ restricts starting from the background | the boot path runs only when the operator enabled it (D14, S3-R026) | `[DEVICE]` |
| **Vendor task killers** | Xiaomi, OnePlus, Samsung and others kill FGS processes, sometimes after a swipe from recents | nothing in-process can prevent it; document per-vendor settings, keep `START_STICKY`, never claim survival | `[DEVICE]` |
| **Low memory killer** | kills by `oom_adj`; an FGS is late, not immune | a kill is handled as a crash; quarantine prevents a loop | `[DEVICE]` |
| **Force-stop** | kills everything, blocks restart until the app is opened | nothing; the UI says stopped | `[SHIPPED]` semantics |
| **Reboot** | opt-in restart only | D14, S3-R026 | `[DEVICE]` |
| **16 KB page size** | Android 15+ devices may use 16 KB pages; ELF segments must be aligned | verify every shipped runtime before bundling | `[DEVICE]` `[RESEARCH]` |

Every `[DEVICE]` row above **needs testing on hardware** before it is claimed;
the tag is the shorthand for that sentence. No row in this table has been
measured, and none of them may be copied into a release note as if it had.

Phase 1I (D80–D84) is where most of these rows become observable rather than
assumed: the Doctor reports the exemption, the restriction, the standby bucket,
the notification state and the platform's own exit reason, and it prints
`unknown` where Android does not answer. That is reporting, not prevention — the
promise column above is unchanged by it.

### H.6 What Grom promises, and what it does not

Grom promises **a truthful state**: if the service is killed the app says so when
it next runs; if an app crashed status shows the exit code; if the gate failed the
deploy says `FAILED`. Grom does **not** promise, and this plan will not be edited
to imply: 24/7 uptime or uptime over any window; that an app keeps running with
the screen off on every vendor ROM; that a tunnel stays up while the phone sleeps;
that a restart happens within a particular number of seconds after the platform
kills the process. Those are platform facts, and the difference between a plan
that admits them and one that does not is the difference between Grom and every
"turn your phone into a server" project that quietly dies.

## I. Runtime system

### I.1 Feasibility

| runtime | how it ships | exec mechanism | verdict | evidence |
|---|---|---|---|---|
| **Node** | `libnode.so` in `jniLibs` (D34, D35) | `execve` from `nativeLibraryDir` | `[SHIPPED]` on this host, `[DEVICE]` unproven | `--verify`: version, `node:sqlite` round trip, 10 agent tests |
| **Python** | CPython built for aarch64 bionic (Termux-style), `libpython3.x.so` + stdlib zip | launcher as `lib*.so`, `PYTHONHOME`/`PYTHONPATH` into app storage | `[RESEARCH]`: no official bionic build; `_ssl`/`_sqlite3` need the vendored OpenSSL and SQLite; 30–60 MB | none |
| **Go** | `CGO_ENABLED=0` static aarch64 binary as `libapp.so` | direct `execve` | `[RESEARCH]`: DNS is the open question (pure resolver wants `/etc/resolv.conf`) | none |
| **Rust** | static musl aarch64 binary | direct `execve` | `[RESEARCH]`: same DNS caveat through musl | none |
| **Java** | not a process on-device: ART via `DexClassLoader`, or a JVM on a node | `DexClassLoader` in Grom's process | **not supported as a separate on-device runtime**: shares the failure domain and the UID | none |
| **Native** | NDK executable as `lib*.so`, JNI optional | `execve` from `nativeLibraryDir` | `[RESEARCH]`: the mechanism is proven by Node; packaging and ABI are per-app work | Node proves the mechanism |
| **Web (static)** | no runtime: a directory served by `StaticHost` | none | `[PLANNED]`, lowest risk here | none |
| **WASM (Pyodide/PGlite)** | WASM + JS inside Node | in-process | `[RESEARCH]`: licence and size review; not a substitute for a Python runtime | none |

### I.2 Node is the only measured runtime

Node 24.22.3 LTS, bionic arm64, assembled from pinned packages with a SHA-256 per
archive and accepted only after it **runs** (D35). `node:sqlite` works (Q8
closed). The agent is CommonJS with no engine switch, no
`node_modules`, no bundler, no network at start (D98). That is the bar for a second
runtime: pinned, hashed, executed by a script, covered by a protocol test — or it
is an aspiration, not a runtime.

### I.3 What each open question actually is

- **Python.** A bionic build (no official Android target); extension modules
  (`_ssl`, `_ctypes`, `_sqlite3`) against the same OpenSSL and SQLite Node already
  vendors; a stdlib zip under `files/` (fine, it is data); and `sys.executable`
  pointing at a `lib*.so`, which some packages dislike. A Linux node makes all of
  it disappear, which is why §Q exists.
- **Go and Rust.** Packaging is ideal (one static ELF). The open question is name
  resolution and TLS roots, not exec. Either a `net` shim reading Android's DNS
  properties or a cgo build; a one-week spike with a device.
- **Java.** Two different products: in-process `DexClassLoader` (no isolation, a
  crash kills the server, only defensible for operator-written code) or a real JVM
  on a Linux node (the only honest way to offer "Java apps").
- **Native.** The Node mechanism again: NDK build, `lib<name>.so`, `jniLibs`,
  `nativeLibraryDir`. The 16 KB alignment question from §H.5 applies to every ELF.

### I.4 The RuntimeAdapter shape

```kotlin
interface RuntimeAdapter {
    val id: String                    // "node", "python3", "binary", "static"
    val apiVersion: Int               // 1
    val displayName: String
    fun capabilities(): Set<String>   // "spawn", "stdio-config", "env", "health"
    fun probe(): RuntimeProbe         // PRESENT | MISSING | INCOMPATIBLE + detail
    fun plan(manifest: AppManifest, layout: AppLayout): LaunchPlan
}
```

A `LaunchPlan` is data: executable, argv, a fresh environment, the stdin line (or
null), working directory, port. The supervisor executes a plan and never knows
which runtime made it. That is what makes runtime #5 a file, not a refactor.

## J. Adapter system

### J.1 One word, two things

Grom already calls the Lightning wrapper an adapter: it forwards to an
operator-run backend and refuses what the backend cannot do (D43). The
Application Manager needs the same word for a runtime that hosts code on the
device. Both are adapters because both answer *what can you do, and what happens
when you cannot?*, and both obey the same honesty rules. They differ in where the
work happens.

| kind | work happens | example | contract |
|---|---|---|---|
| **BackendAdapter** | off-device, operator-configured URL | `lightning` → LNbits (D43, D48) | `Plugin` + family interface `[SHIPPED]` |
| **RuntimeAdapter** | on-device, child process | `node`, `binary`, `python3` (research) | `RuntimeAdapter` + `LaunchPlan` `[PLANNED]` |

One `AdapterRegistry` holds both, so status, CLI and UI have one inventory.

### J.2 The standard interface

```kotlin
enum class AdapterKind { BACKEND, RUNTIME }

data class AdapterManifest(
    val id: String,                      // ^[a-z][a-z0-9-]{1,31}$  (S5-R020)
    val kind: AdapterKind,
    val apiVersion: Int,
    val title: String,
    val description: String,
    val requires: Map<String, String>,   // capability -> minimum version
    val permissions: Set<Permission>,    // NET_OUT, SPAWN, FS_APP_PRIVATE, KEYSTORE
    val configSchema: List<ConfigField>  // name, type, required, bounds, secret?
)

interface RuntimeAdapter {
    val manifest: AdapterManifest
    fun capabilities(): Set<String>
    fun probe(): RuntimeProbe
    fun validate(manifest: AppManifest): List<AdapterProblem>
    fun plan(manifest: AppManifest, layout: AppLayout): LaunchPlan
}
```

`probe()` returns `MISSING` with the exact file it looked for — the agent's
existing behaviour (S3-R003, `503 MISSING_RUNTIME` naming the file), applied to
every runtime. Nothing is inferred from a version string alone.

### J.3 Registration and the no-core-change rule

```kotlin
// adapters/AdapterRegistry.kt
private val builtins = listOf(
    NodeRuntime(), BinaryRuntime(), StaticHostAdapter(),
    LightningAdapter()          // already shipped as a Plugin
)
```

The rule, enforced statically rather than by convention: **adding an adapter
touches `adapters/`, a test file, and at most one element of this list.**
`ServerRuntime`, `SuperRouter`, `PluginRegistry` and `GromService` must not name
an adapter class; the check is a grep added to `tools/test-policy` in Phase 3
(R6 in §F.3). If the check must be relaxed to land an adapter, the contract is
wrong and gets fixed instead. Ordering is by `id` for determinism; duplicate ids
fail startup, not last-one-wins (the `DUPLICATE_KEY` reasoning of S5-R008).

### J.4 Install and remove

There is no dynamic loading (S5-R020, `tools/test-policy` 24), so "install" is:

| option | meaning | verdict |
|---|---|---|
| **compile-in, enable by config** | the adapter is in the APK; `adapters.json` enables it and holds its config | **chosen**: auditable, signed with the app key, no loader |
| **second APK, same signing key, over Binder** | a real plug-in package | `[DECISION]` if ever wanted: S1-R012 and D15 mean "install" becomes "install another Grom-signed package" — a distribution decision before a technical one |

Remove = disable + delete configuration; the code stays. The UI and API must say
so, because "uninstalled" that leaves executable code in the APK is a lie.

### J.5 Configuration and versions

Config lives in `adapters.json`, validated by the adapter's own schema before
use, written atomically (D40 pattern), bounded to 64 KiB. Fields: `name`, `type`
(`string|int|bool|url|path|secret`), `required`, `min`/`max`, `pattern`,
`secret`. A `secret` field is sealed on write and reported as
`{"configured": true}`; it is never returned. Compatibility is two integers:
`apiVersion` (the contract) and the runtime's own version from `probe()`. An
adapter whose `apiVersion` the core does not know is refused with
`ADAPTER_API_MISMATCH` — not ignored, not partially used.

### J.6 Health

`probe()` at registration and on every reload, cached with a timestamp. A runtime
whose probe is `MISSING` does not fail the server: apps that need it go `FAILED`
with `RUNTIME_MISSING` and the missing path, everything else keeps running (the
`MISSING_RUNTIME` behaviour of S3-R035/R036, generalised). A backend adapter's
health is a real request (the `lightning` `health` op), never a cached "last known
good".

### J.7 The only five answers

| code | HTTP | when | example |
|---|---|---|---|
| `NOT_SUPPORTED` | 501 | capability not implemented; the message names it | `orderbook`, `websocket`, `python3` |
| `UNCONFIGURED` | 503 | present but not configured | `LIGHTNING_UNCONFIGURED`, `SECRETS_UNREADABLE` |
| `UNAVAILABLE` | 502/503 | configured but unreachable or failed | `LIGHTNING_UNAVAILABLE`, `AGENT_UNAVAILABLE` |
| `INVALID_ARGS` | 400 | the caller's input is wrong | `INVALID_ARGS`, `UNKNOWN_OP` |
| `INTERNAL_ERROR` | 500 | a defect, constant message | never a stack trace (S2-R031, S5-R010) |

There is no sixth answer and no "unknown, assume ok".

### J.8 Isolation and permissions

- **Process-level, not security-level.** Every on-device adapter runs as a child of
  the same UID; a hostile app reads the token and the audit log. §P.7 states this
  without softening it, and §S repeats it.
- Adapters declare `NET_OUT`, `SPAWN`, `FS_APP_PRIVATE`, `KEYSTORE`; the registry
  refuses to enable one whose declared permissions exceed what the operator
  granted. Declaring an unused permission is a defect: `NET_OUT` may only appear
  on an adapter whose class opens a socket (new invariant).
- Every call runs with a timeout on a worker thread and shares no mutable state
  with the supervisor. A hung adapter is `UNAVAILABLE` after the timeout, never a
  stalled data plane (S5-R012).

### J.9 Proof that the contract works

The test of a contract is a second implementation. Phase 3 lands **LND** as the
second `LightningBackend` (the four touch points are already documented in
`docs/LIGHTNING.md` §6) and **`binary`** as the second runtime. If either needs
`ServerRuntime` or `SuperRouter` changed, the contract is revised before a third
implementation is allowed.

## K. Android compatibility matrix

"Works" here means a JVM test or a documented code path, never a device run.

| environment | server role | Node apps | other runtimes | data plane off-device | verdict |
|---|---|---|---|---|---|
| **Android 8–9 (API 26–28)** | **not available** (`server29` is minSdk 29) | — | — | — | **peripheral only**; `peripheral26` never binds (D2, S1-R006) |
| **Android 10–12 (API 29–32)** | expected `[DEVICE]`; FGS and boot receiver unmeasured | `[DEVICE]` | `[DEVICE]` | tunnel or terminator required | **primary target**, unproven |
| **Android 13 (API 33)** | expected; `POST_NOTIFICATIONS` needed or the FGS notification is silent | `[DEVICE]` | `[DEVICE]` | same | expected, unproven |
| **Android 14 (API 34)** | FGS type required; `specialUse` declared (D21) | `[DEVICE]` | `[DEVICE]` | same | expected; Play's `specialUse` policy applies only to Play distribution |
| **Android 15–16 (API 35–36)** | compileSdk 36; 16 KB pages may break a 4 KB-aligned ELF | `[DEVICE]` | `[DEVICE]` | same | **risk**: verify alignment before claiming support |
| **No root** | the supported configuration | yes | yes | yes | **supported** |
| **Root** | nothing requires it; root could enable cgroups and per-app UIDs, which Grom does **not** use | yes | yes | yes | **no difference**. Grom never asks for or checks root |
| **Vendor ROM** (Xiaomi, OnePlus, Samsung, Huawei) | task killers may kill the FGS; autostart settings differ | `[DEVICE]` | `[DEVICE]` | same | **needs testing per vendor**; workaround is user settings, not code |
| **Emulator / x86** | builds and JVM tests only; no `libnode.so` for x86 | — | — | — | no claim; agent answers `MISSING_RUNTIME`, which is designed (D31) |
| **Limited storage / Go edition** | untested | `[DEVICE]` | `[DEVICE]` | same | **unsupported until measured** |
| **Linux node (§Q)** | n/a | n/a | all runtimes with glibc | yes, with a real terminator | `[PLANNED]`, the honest answer for Python, Java, containers |

The two cells that decide support: **API 29–36 with the arm64 runtime present**
(the only configuration claimed, gated on a device run that has not happened) and
**API 26–28** (peripheral only — the listing and the download page must say so
rather than letting an install fail).

Unsupported and staying unsupported: x86/x86_64, 32-bit, and any Android where
the operator cannot grant network access — the app still starts, still serves
loopback, still tells the truth.

## L. Networking

### L.1 The only two questions

Who may connect, and who terminates TLS. Grom itself implements neither beyond
the bind policies it already has (`BindPolicy`, `SuperBindPolicy`, D5, D41, D47).

### L.2 The paths

| path | who initiates | Grom setting | TLS terminates at | inbound needed | cost | verdict |
|---|---|---|---|---|---|---|
| **Loopback** | the device, or `adb forward` | control default | nothing leaves the device | none | none | `[SHIPPED]` |
| **LAN** | a device on the same Wi-Fi | `bind=LAN` + credential | **nowhere** unless a terminator is added | LAN only | none | `[SHIPPED]` at code level, `[DEVICE]` |
| **Tailscale** | the tailnet | bind `LAN`/`LOOPBACK` behind it | WireGuard, end to end | none (works behind CGNAT) | free tier, an account | works; third-party coordination unless self-hosted |
| **Headscale (self-hosted)** | your tailnet | as Tailscale | WireGuard | a public host for the controller | a VPS | same, no account |
| **WireGuard (plain)** | your config | bind `LOOPBACK`, peers route in | WireGuard | one peer needs a public address or relay | a VPS or static IP | full control, most work |
| **Nebula** | your lighthouse | as WireGuard | Nebula (Noise) | a lighthouse host | a VPS | works, smaller ecosystem |
| **Public + domain + Caddy/nginx** | the internet | `bind=PUBLIC` (acknowledged) or `LAN` behind a proxy | the proxy, **required** | a public IP and 80/443 | VPS or home IP | only sane with inbound ports |
| **Cloudflare Tunnel** | the device, outbound | bind `LOOPBACK`; `cloudflared` outside Grom | Cloudflare's edge | none | free tier, an account | works behind CGNAT; the edge sees plaintext unless terminated locally |
| **frp** | the device, outbound | bind `LOOPBACK`; `frpc` outside Grom | your frps, plus TLS on the legs | a public frps host | a VPS | self-hosted tunnel, Apache-2.0 |
| **SSH tunnel** | the device, outbound | bind `LOOPBACK`; `ssh -R` | SSH | a host you already SSH to | existing server | lowest-tech option that works everywhere |

### L.3 What Grom does about tunnels: one narrow case, and nothing else

Since Phase 1C, Grom **does** open one kind of tunnel itself: a **reverse SSH**
session to a server the operator saved (D58, `docs/SSH-BRIDGE.md`). It exists
because a phone behind CGNAT has no other way to be reachable, and because SSH is
the lowest-tech option that works everywhere. The scope is deliberately tiny:
one `tcpip-forward` per connection, no shell, no exec, no agent/X11 forwarding,
no local or dynamic forwarding, a host key pinned on first use, and a
client-side filter that accepts only the local data plane as a destination. It is
opened by an explicit tap and never by a timer.

Everything else stays outside Grom, for the reasons that survive: a bundled
provider client phones home and is a binary Grom cannot audit at the operator's
cadence; a mandatory provider contradicts S1-R004; TURN-like clients are a moving
target better updated by their own app; and `remoteBridge` (`label`,
`description`) exists so the operator can *remember* which VPS sits behind a
tunnel — a signpost, never a connection.

What the plan does instead: name the TLS termination point for every path (L.2),
warn in the UI on `PUBLIC` (S5-R029), and refuse any off-loopback bind without a
credential (D41).

### L.3a State change notification

Grom can also tell the operator that something changed, through their own
Telegram chat (D65–D67, `docs/NOTIFICATIONS.md`). This is the only *other*
outbound connection outside the update check, the Lightning backend and the SSH
bridge, and it is deliberately the narrowest one:

- one compiled host (`api.telegram.org`), because the Bot API cannot be a
  runtime-supplied URL;
- the bot token is entered at runtime and stored only as Keystore ciphertext;
- the message body is a fixed sentence from a table, plus a stable code and, for
  project events, a project id;
- two attempts, no scheduler, no disk queue, no replay, 20 messages a minute;
- nothing at all when the process is dead — Grom is the sender, so a dead server
  cannot report its own death. That needs an external observer (a cron job on
  the VPS), which is out of scope here.

A notification is one-way and carries no state back: it never becomes a control
channel, and nothing in Grom waits for an answer.


### L.4 Domains, reverse proxies, and the gateway rule

A domain pointed at a residential IP is the fragile path: CGNAT makes it
impossible (L.6) and a dynamic address needs DDNS. When it works, the proxy lives
on the same LAN and proxies to `http://<phone-lan-ip>:47002`; the bind is `LAN`,
the credential is required. Nothing in Grom needs to know the domain, and nothing
should: a compiled-in hostname is the beginning of a dependency.

The rule, inherited from Dukoi's best idea: a tunnel may reach
`/v1/apps/{id}/proxy/*` and nothing else. `/grom/v1/*` does not exist on the data
plane at all — different listener, port and credential, and no route prefix that
could reach it. The proxy strips `X-Grom-*` both ways so a proxied app cannot
impersonate Grom. A tunnel is not a credential.

### L.5 CGNAT: the sentence that must be on the page

A phone on a mobile network has no reachable inbound address. `bind=PUBLIC` binds
`0.0.0.0` on a device that is still unreachable; it does not create reachability.
The only paths that work are initiated **from the device** to a host with a public
address, or a mesh VPN with a reachable coordination point. There is no
port-forwarding recipe that fixes CGNAT from outside, as `docs/SUPER-SERVER.md` §5
already says.

### L.6 Test status for every row

`[DEVICE]` for all of them. Nothing here has been reached from another machine
through a tunnel, and no LAN bind has been measured. What exists:
`SuperServerIntegrationTest` binding `127.0.0.1:0` over a real socket (S5-R030),
`SuperBindPolicy` tests, and the recipes in `docs/SUPER-SERVER.md` §3.

## M. Multi-device architecture

### M.1 Roles, and why they are not a cluster

| role | does | does not |
|---|---|---|
| **control node** | holds the node registry, app catalogue, pairings; answers `/v1/nodes` | run workloads only it can run; schedule; hold another node's data |
| **worker node** | runs apps, reports state and health | approve its own pairings; accept a workload it cannot run |

A device can be both. That is the topology: no quorum, leader election,
consensus, etcd, sidecars or mesh. Explicitly **not Kubernetes** — a feature that
only makes sense in a scheduler is out of scope.

### M.2 Pairing

```
control                          worker
   │  (1) show code: 8 chars, 5 min TTL, single use
   │  (2) operator types the code on the worker
   │◀──(3) hello: nodeId, Ed25519 public key, model, caps
   │  (4) control signs a challenge over nodeId||nonce||timestamp
   ├──▶(5) worker verifies, signs the same nonce back
   │  (6) control stores {nodeId, publicKey, scopes, label} after the operator
   │      confirms the fingerprint out of band (6 words or a QR)
   └──▶(7) both audit pair.request / pair.approve
```

- **No auto-approve.** A node answering on the LAN is a stranger until a human
  approves its fingerprint; mDNS only makes the stranger visible.
- **No shared secret.** The code authorises the *exchange*; what is stored is a
  public key.
- **Scopes on the pairing**, not the device: `status.read`, `apps.read`,
  `apps.restart`, `apps.deploy`, `nodes.manage`, `backup.read`.
- **Revocation is one row** plus a `pair.revoke` audit entry, effective on the
  next request.
- Codes use the Crockford alphabet already in `InviteCode`, are single use,
  expire in 5 minutes, and attempts are limited by the S5-R015 pattern.

### M.3 Registry and heartbeat

`nodes` table in SQLite (§N.2). A worker sends `POST /v1/nodes/heartbeat` every
15 s with `{nodeId, uptimeMs, apps: [{id, state, version, health}], load}`; the
control marks a node `STALE` after 45 s and `UNREACHABLE` after 120 s. The
heartbeat is signed and its timestamp checked within 300 s, exactly like a signed
request (S5-R017, S5-R018). A missed heartbeat never changes a worker's local
behaviour.

### M.4 Transport between nodes

Grom has no TLS (D47). Node-to-node traffic is therefore: over a private mesh or
loopback, or over the data plane behind a terminator the operator runs, or not at
all. Pairing messages are signed but **not confidential** without one of those.
The plan says this plainly instead of inventing a bespoke encrypted transport: a
hand-rolled Noise-like layer in a Kotlin app is exactly what looks secure and is
not. `[DECISION]` if a built-in transport is ever wanted.

### M.5 Discovery

Android NSD announces `_grom._tcp` with the node id and data-plane port. Discovery
is a **hint**: it fills "nearby devices", selecting one starts pairing, and a
discovered node with no pairing shows as `unpaired`. It never grants access and
never auto-pairs. A manual "add by address" path exists for networks that block
multicast, which is most hotel and corporate Wi-Fi.

### M.6 Placement: explicit, not scheduled

| policy | meaning |
|---|---|
| `local` | this device only (default) |
| `node:<id>` | a named, paired node |
| `any` | any worker advertising the required runtime |

`any` is not a scheduler: the control asks eligible workers in a fixed order
(most free memory, then lowest id) and the first that accepts wins. No bin
packing, preemption or affinity beyond the runtime requirement. An unsatisfiable
placement is `NO_ELIGIBLE_NODE`, not a workload pending forever.

### M.7 Sync, conflict, duplicates

- **Configuration** (manifests, scopes, pairings) replicates as immutable versions
  with a counter; the control is the only writer, so conflicts are impossible by
  construction.
- **Application data** is **not** replicated: no shared filesystem, no block
  replication, no last-writer-wins on a database. Data lives on the node that runs
  the app; moving it is an explicit export/import (§O). The same app id on two
  nodes is refused (`DUPLICATE_INSTANCE`) rather than merged.
- **Duplicates** are detected by node id plus an instance id generated at install.
  If two nodes claim one instance, the newer claim loses and is reported, because
  a split brain on a phone is a data-loss bug, not a feature.

### M.8 Node loss

The property that matters: **local work continues if the control disappears.** A
worker's supervisor is local and never asks the control whether to restart an app.
Losing the control costs the registry view, remote deploys and cross-node status;
it does not cost a running app, its port, its health checks or its audit log.

The reverse is deliberately weaker: the control keeps a cached view with the
timestamp of the last heartbeat and the UI says `last seen 4 minutes ago`, never
"running". A stale view presented as live is exactly the lie S1-R017 forbids.

### M.9 The AI gateway: one local endpoint, the operator's own keys

A phone that serves other apps will be asked for AI completions, and the worst
answer is "every app talks to every vendor with its own key". The gateway is the
other answer: **one local API on the data plane that already exists** (D94), the
operator's stored credentials, and a catalogue that doubles as the spending limit.

| layer | decision |
|---|---|
| protocols | three wire shapes — OpenAI-compatible, Anthropic, Gemini — and `custom` with any of them; a new vendor is configuration, not an app update |
| credentials | sealed by the keystore before they are written, never projected, only ever sent to the endpoint the operator typed (D95) |
| permissions | `ai.read` and `ai.chat`, separate from `services.call`; no scope reveals a key |
| honesty | no background probing; a provider is "measured" only by an explicit test or a real call (D96) |
| streaming | server-sent events on the existing listener, pre-flight refusals before the head (D97) |

What this is not: an account, a relay, a billing surface, a proxy for arbitrary
URLs, or a place where a phone's data leaves without the operator having chosen
the destination. It is also not — yet — a tools/function-calling or multimodal
gateway: a request that asks for those is refused by name rather than narrowed.

## N. Storage and databases

### N.1 The default: SQLite through `node:sqlite`

`node:sqlite` works on the pinned bionic arm64 Node (Q8 closed), so Grom has a
real relational store without adding a dependency (D3, D17). It holds *Grom's own*
state, not a database service for applications.

### N.2 Schema

```sql
PRAGMA journal_mode = WAL;
CREATE TABLE nodes (node_id TEXT PRIMARY KEY, public_key TEXT NOT NULL,
  label TEXT, role TEXT, address TEXT, scopes TEXT, last_seen_ms INTEGER,
  caps TEXT);
CREATE TABLE apps (id TEXT PRIMARY KEY, version TEXT, port INTEGER UNIQUE,
  manifest TEXT, node_id TEXT REFERENCES nodes(node_id), instance_id TEXT UNIQUE,
  state TEXT, updated_ms INTEGER);
CREATE TABLE releases (app_id TEXT, version TEXT, path TEXT, sha256 TEXT,
  created_ms INTEGER, status TEXT, PRIMARY KEY (app_id, version));
CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER,
  node_id TEXT, kind TEXT, payload TEXT);
```

- `events` is a ring: newest 10 000, older deleted on a schedule.
- The **audit log stays in the hash-chained file** (D10). Duplicating it in SQLite
  would create two truths about what happened.
- Secrets never go in SQLite: sealed blobs per app (§G.6), Keystore material never
  leaves the Keystore.
- Migrations are forward-only and numbered; a downgrade is a restore, not a schema
  rollback.

### N.3 WAL and the filesystem

WAL needs working shared memory. App-private storage on `ext4`/`f2fs` is fine; a
database on adopted SD or a FUSE path is not — WAL may fail or corrupt. Since all
of Grom's state is app-private, this constrains *applications*, and §G.3 refuses a
manifest whose `persistent` path is outside `apps/<id>/`.

### N.4 Per-application database choice

| the app's choice | how it works | constraint |
|---|---|---|
| SQLite in `data/` | its own driver (Node `node:sqlite`, Go pure-Go, Python `sqlite3` if built) | WAL as above; no cross-app queries |
| JSON files | the app's business | fine to a few MB |
| **Postgres** | **not available in-process.** PGlite (WASM Postgres) is `[RESEARCH]`: licence and size review, then it runs inside Node | no real Postgres server on a phone |
| **Valkey / Redis** | **not available in-process.** A RESP server is a separate binary needing a bionic build | the honest answer is a Linux node (§Q), or the phone as a *client* to a Valkey elsewhere |
| **PGlite** | WASM inside Node, no network | verify the licence, add the ledger row, measure the WASM memory floor |

### N.5 Filesystem constraints that shape the design

| constraint | consequence |
|---|---|
| no `execve` of app-private files (D34) | runtimes ship in `jniLibs`; apps never execute what they download |
| no hard links or symlinks outside app storage (scoped storage, API 29+) | a release is copied, not linked; rollback is a pointer file, not a symlink |
| no POSIX users or groups | file permissions cannot isolate two apps from each other |
| quota is device- and vendor-dependent, no reliable pre-flight | advisory free-space check (§G.5) |
| `/data` is encrypted at rest (FBE) | a powered-off stolen phone is protected; a running one is not — backup encryption is still required (§O) |
| directory-entry `rename` is not power-loss atomic in general | ext4/f2fs provide the semantics the atomic-write pattern relies on; a cut between write and rename loses the new file, not the old one |

## O. Backup and recovery

### O.1 What a backup is

A portable, encrypted archive of Grom's configuration and the operator's chosen
application data. Not a filesystem image, not a database replica, and not a way to
move a running app between nodes while it runs.

### O.2 Included, excluded, and why

| content | included | reason |
|---|---|---|
| `super-server.json` minus secrets | yes | a password hash and public keys are not usable credentials (D40), and restoring the exposure policy is the point |
| `identity.json` | **no** by default | a cloned identity is two devices claiming one id; `--include-identity` exists and is labelled dangerous |
| pairing keys | **no** | a pair is between two live devices; re-pair after a restore |
| `grom-token` | **never** | S1-R024, D8. A restore re-creates it, which invalidates old clients — correctly |
| manifests, release metadata | yes | cheap and essential |
| release code | optional (`--with-code`, off) | most of the bytes for something re-deployable |
| app data (`data/`) | per app, opt-in by manifest `backup: true` | an operator may not want 2 GB in a 20 MB archive |
| audit log | optional (`--with-audit`) | evidence after an incident, bulky otherwise |
| sealed secrets | **never** | a sealed blob is readable only by the Keystore key that sealed it; copied ciphertext without the key is worse than useless |

### O.3 The container

```
GROMBAK1\n
{"version":1,"createdMs":…,"deviceNodeId":"…",
 "kdf":{"kind":"pbkdf2-sha256","iterations":600000,"salt":"<b64>"},
 "cipher":{"kind":"aes-256-gcm","nonce":"<b64>"},
 "entries":[{"path":"apps/blog/app.json","sha256":"…","size":1234}, …]}
<AES-256-GCM ciphertext of the gzip stream of the entries>
```

- Key: operator passphrase by default (PBKDF2-SHA256, 600 000 iterations), or a
  random key sealed by the Keystore (`--device-bound`). The second protects
  against file theft and **cannot be restored on a new phone** — the UI says that
  in the same sentence as the option.
- The manifest is authenticated as AEAD additional data, so an attacker cannot
  substitute an entry list that omits a file.
- Paths are relative, normalised and refused if they escape, because an archive is
  hostile input.

### O.4 Restore, and what "verified" means

```
1. decrypt, parse the manifest, check every path
2. verify every entry's SHA-256 before writing anything
3. write into apps/.restore/<timestamp>/   (never over a live app)
4. apply: per app — stop, swap the directory, start, health gate
5. an app that fails its gate keeps the old directory and is reported
6. audit backup.restore with the manifest digest and per-app results
```

"It restored" means every entry verified, every app started, every app passed its
own health gate, and the report says so per app. A restore that half-applies is
worse than one that fails, which is why step 3 exists.

### O.5 Recovery paths that are not restore

| situation | path |
|---|---|
| agent crashing repeatedly | quarantine → human reset (S3-R021) `[SHIPPED]` |
| bad deploy | rollback to `previous` (§G.5) |
| corrupt `app.json` | that release is `FAILED` with the parse error; the current release is untouched |
| database corrupted | delete it and rebuild from files (`apps/`, pointers); the registry is derivable, the apps are not |
| process killed at any instant | cold start rebuilds from files; every write is atomic or append-only |
| Keystore key lost | secrets unreadable → `SECRETS_UNREADABLE`, never a plaintext fallback |

## P. Security

### P.1 Threat model, extended

S1-R008 lists three adversaries: another app, a compromise in the same UID, and a
human with the unlocked device. This plan adds two, because it adds two
capabilities: a **malicious application package** the operator deployed (arbitrary
code as Grom's UID; mitigated by deploy being an explicit audited act and by §P.7
being stated honestly), and a **network attacker on the path** to the data plane
(mitigated by requiring a credential off loopback and by naming the terminator).

### P.2 Identity

One Ed25519 identity per device, seed sealed by the Android Keystore, public key
in `identity.json`, `nodeId` derived from it (§F.2). No hardware identifier. The
reference `npub` (D52) is a display value for an external bridge: Grom never
signs with it, never opens a relay, and the UI must never present it as Grom's
identity.

### P.3 Keystore usage

| secret | alias | sealed by | readable by |
|---|---|---|---|
| LNbits backend key | `grom-super-secrets` | `AndroidKeystoreSecretBox` `[SHIPPED]` | the backend object, in memory |
| app secret sets | `grom-app-secrets` | same `SecretBox` `[PLANNED]` | the supervisor, for one stdin write |
| node identity seed | `grom-node-identity` | same `SecretBox` `[PLANNED]` | the signing path only |
| device-bound backup key | `grom-backup-key` | same `SecretBox` `[PLANNED]` | export/import only |

A rotated or deleted key makes the value unreadable, reported as unconfigured —
never a crash and never a plaintext fallback (D44). The control token stays in
app-private storage (D20, deferred) because the same process checks it; that
reasoning does not transfer to anything useful off-device.

### P.4 Authentication and authorisation

| plane | credential | what it can do |
|---|---|---|
| control `127.0.0.1:47001` | 256-bit bearer, constant time (D8) | everything on the control plane; the owner's key |
| data `:47002` | password session (12 h, PBKDF2) or Ed25519 signature (D42) | `/v1` routes, bounded by role and scopes (§D.1) |
| Binder | signature permission + runtime `checkSignatures` (D15) | `IGromServer`, same-certified apps only |
| node-to-node | Ed25519 signed heartbeat/commands over a private transport (M.4) | the scopes on that pairing |

Authorisation is one function called before routing, so an unauthenticated caller
cannot distinguish an existing route from a missing one (S2-R013, S5-R010). There
is no `allow-all`, no "trusted subnet", no "loopback means admin", and no
auto-approve anywhere in the product.

### P.5 TLS

Unchanged from D47: Grom terminates no TLS, stores no certificate, rotates
nothing. The data plane is plain HTTP on its bound address; exposing it off
loopback requires a terminator or an encrypted tunnel. The UI warns on `PUBLIC`
(S5-R029), `docs/SUPER-SERVER.md` §5 says what is visible on the wire, and this
plan adds no exception for `/v1/apps`.

### P.6 API protection

Every existing bound applies to every new route: 8 KiB headers, 64 KiB body, no
`Transfer-Encoding`, 10 s idle, 16 connections, 4 workers, per-client token bucket
(60 cap, 2/s), uniform `401`, `429` with `Retry-After`, no stack traces (S5-R009…
S5-R012, S5-R016). New routes add path normalisation before authorisation, a
proxied-method allow-list (`GET`, `POST`, `PUT`, `DELETE`, `HEAD`), and a refusal
to proxy `Upgrade`.

### P.7 Application isolation: the honest paragraph

Every application runs as the same Linux UID as Grom. It can read `grom-token`,
the audit log, every other app's data, and use the Keystore handles (not the key
material, which is non-exportable but usable by anything in the process). There
is no per-app UID, namespace, seccomp filter or SELinux domain of our own.

Consequences, stated so they cannot be discovered later: the Application Manager
is for **operator-authored or operator-vetted code**; "deploy" is a privileged
action equivalent to `adb shell` access, and the UI and API must say so on the
confirmation, not in a footnote; a deploy requires `apps.deploy`; an untrusted
workload belongs on a Linux node (§Q) or a server the operator already trusts; and
Grom will not add a fake sandbox, because a sandbox that shares a UID is a promise
it cannot keep and S1-R017 forbids promises it cannot keep.

### P.8 Audit

Every mutating act on either plane appends exactly one entry to the existing hash
chain (D10, S1-R014): `app.deploy`, `app.rollback`, `app.start`, `app.stop`,
`app.uninstall`, `app.secrets.rotated`, `pair.request`, `pair.approve`,
`pair.revoke`, `node.heartbeat.missed`, `backup.export`, `backup.restore`,
`adapter.enable`, `adapter.disable`. Details never contain a secret, token,
password hash or sealed value (D26, S3-R029). The chain is verifiable while
running (S1-R016) and rotates at 4 MiB keeping one generation (D27).

### P.9 Backup protection

A passphrase by default; a device-bound option that says it is not portable; the
manifest authenticated as AEAD data; no token, identity, pairing keys or sealed
secrets inside (O.2); export and restore both audited, the restore with the
manifest digest.

## Q. Linux node as optional extension

**What it is.** A small daemon (`grom-node`) that registers with a Grom control
node as a worker, on the same protocol as an Android worker (M.3), on a machine
with glibc, real cgroups, real users and a real filesystem. It is **optional**:
every capability that is "cannot" in §S is "can" there, and no capability that is
"can" on Android depends on it.

**Why it exists.** Runtimes that need glibc (CPython, a JVM, Postgres/Valkey) are
normal packages there and research projects here; isolation (a separate user,
containers, `seccomp`, `cgroups`) is exactly what §P.7 cannot offer in an app;
long-running jobs meet no Doze, no vendor killer and no battery budget; and a VPS
has a public address and a real TLS terminator.

**What it must not become.** Not a requirement — the phone keeps working alone,
and a plan that needs a VPS to function has replaced the product. Not a second
supervisor: it reuses the manifest, release layout, health gate and honesty rules,
differing only in how a process is spawned and isolated. Not a control plane: a
Linux node is always a worker. Not a gateway to the phone's control plane: the
L.4 rule applies in reverse, so a node never proxies to `/grom/v1/*`.

**Shape.** Go or Rust (one static binary, no host runtime dependency), Apache-2.0
so it can be vendored into the release process (§R). Interface: HTTPS client to
the data plane, signed requests (S5-R017), heartbeat every 15 s, commands by
polling — no inbound port required, so a node behind NAT works like a worker
behind one.

## R. Open-source projects and licences

**The rule.** MIT, Apache-2.0, BSD-2/3, ISC, Zlib, PSF and public domain are
acceptable with attribution, an SPDX header and a `third_party/THIRD_PARTY.md`
row (D17, S1-R026). Copyleft is a decision, not a detail. A non-open licence is a
dependency that can be withdrawn, treated as a risk rather than a component.

**Nothing below is a recommendation to adopt.** Each row is "licence as
documented by the project; verify at adoption time", because projects relicense
and this plan must not become where a stale licence claim lives.

### R.1 Permissive — acceptable with a ledger row

| project | area | licence | note |
|---|---|---|---|
| Node.js | agent and app runtime | MIT | already shipped (D35) |
| SQLite | storage | Public domain | via `node:sqlite`, not a separate dependency |
| Go | Linux node, future runtimes | BSD-3-Clause | standard library carries a BSD notice |
| Rust | Linux node, future runtimes | MIT OR Apache-2.0 | dual; choose Apache-2.0 |
| CPython | optional Python runtime | PSF-2.0 | the build, not the source, is the problem (§I.3) |
| Caddy / nginx / Traefik | documented terminators | Apache-2.0 / BSD-2 / MIT | operator-run, never bundled |
| cloudflared / frp | documented tunnels | Apache-2.0 / Apache-2.0 | operator-run, never bundled |
| Tailscale client / Headscale / Nebula | documented meshes | BSD-3 / BSD-3 / MIT | operator-run |
| `wireguard-go` | documented mesh | MIT | the kernel module is GPL-2.0 (R.2) |
| Valkey | database on a Linux node | BSD-3-Clause | the permissive fork after Redis relicensed |
| PGlite | optional WASM Postgres | Apache-2.0 (verify) | `[RESEARCH]`; size and memory floor first |
| Pyodide | optional WASM Python | MPL-2.0 | file-level copyleft; acceptable if those files stay unmodified, but flag it |
| isomorphic-git | git deploy without a git binary | MIT | `[RESEARCH]`; would be the first bundled JS dependency, which D3 resists |
| JUnit | tests | EPL-1.0 | already in the ledger as a test dependency |

### R.2 Copyleft — a separate program, never linked

| project | licence | constraint |
|---|---|---|
| `git` | GPL-2.0 | fine as a separate executable the operator runs; **incompatible with Apache-2.0 for linked or derived work**. Do not link it, do not vendor it without review — prefer not shipping it (§G.2) |
| WireGuard tools (`wg`, `wg-quick`) | GPL-2.0 | operator-run only |
| Termux and its packages | GPL-3.0 app, mixed per package | a Termux-derived runtime is a **build input** recorded in `docs/RUNTIME.md`, with its source offer honoured — the treatment Node already gets |
| OpenJDK | GPL-2.0 with Classpath Exception | a bundled JVM on a Linux node is acceptable; on Android it is not planned (§I.3) |
| MinIO | AGPL-3.0 | **avoid** in any bundled or linked position; AGPL on a network-facing component would attach obligations to Grom's service |
| Grafana | AGPL-3.0 | do not embed |
| MongoDB | SSPL | **not open source**; avoid |
| Redis 7.4+ | RSALv2 / SSPL | avoid; Valkey is the permissive answer |

### R.3 Non-open — treated as absent

BSL 1.1, SSPL, the Elastic License, the Commons Clause and any custom licence are
not adopted for anything Grom ships or links. They may be *documented* as the
operator's own choice on their own server, in the same tone as a tunnel vendor: a
link, not a dependency.

### R.4 If code is ever borrowed from Dukoi

Dukoi is Apache-2.0 and Grom is Apache-2.0, so the obligations are ordinary but
mandatory: a `NOTICE` entry naming Dukoi, its copyright holder and the derived
files; `SPDX-License-Identifier: Apache-2.0` on every touched file (D18,
`tools/test-policy` 11); a licence review row in `third_party/THIRD_PARTY.md`
even though the licence matches, because S1-R026 asks for *every* component and a
derived file is one; and no relicensing and no removal of attribution.

This plan borrows **ideas**, not files. Ideas are not copyrightable; the tables
above are Grom's own description of what those ideas should mean here.

### R.5 Where the licence gate runs

`tools/check-third-party` fails when the ledger and the build disagree in either
direction (D17). Phase 3 extends it: an adapter or runtime in `adapters/` must
have a ledger row, and a row naming a non-permissive licence must carry a
`constraint:` note a human wrote. A machine cannot review a licence; it can refuse
to let one arrive silently.

## S. Capability table

### S.1 Can, today

| capability | condition | evidence |
|---|---|---|
| authenticated loopback API | always; it is the default | `[SHIPPED]`, S2 |
| refuse a non-loopback control bind | always | `[SHIPPED]`, D5 |
| supervise one Node child with quarantine | runtime present | `[SHIPPED]`, S3, D13 |
| report `MISSING_RUNTIME` honestly | runtime absent | `[SHIPPED]`, D31, S3-R035 |
| a separately bound data plane | operator enables it | `[SHIPPED]` at JVM level, S5 |
| password and Ed25519 auth with replay protection | data plane on | `[SHIPPED]`, D42 |
| SSE event stream | data plane on | `[SHIPPED]`, D46 |
| forward Lightning ops to the operator's backend | backend configured | `[SHIPPED]`, D43 |
| tamper-evident audit | always | `[SHIPPED]`, D10 |
| store `npub`, relays and a bridge hint as reference | validated input only; no relay, no signature, no tunnel | `[SHIPPED]`, D52–D54, S5-R032 |

### S.2 Can, planned

| capability | condition | gate |
|---|---|---|
| host N applications on stable ports | Phase 2 | `DeployIntegrationTest` |
| deploy from a directory or ZIP | Phase 2, operator picks the file | `AppManifestTest` |
| deploy from an operator-supplied HTTPS URL | Phase 2, new `Dxx` for the outbound call | `HttpSourceTest` |
| health-gated cutover, one-action rollback | Phase 2 | `HealthGateTest` |
| serve a static directory | Phase 2 | `StaticHostTest` |
| reach one app through the data-plane gateway | Phase 2, credential present | `GatewayTest` |
| per-app sealed secrets over stdin | Phase 2 | `SecretSetTest` |
| encrypted backup and verified restore | Phase 5 | `BackupRoundTripTest` |
| pair a second device with scopes | Phase 4 | `PairingTest` |
| registry and heartbeat across devices | Phase 4 | `NodeRegistryTest` |
| place an app on a chosen node | Phase 4 | `PlacementTest` |
| CLI over the loopback API | Phase 5 | `tools/test-cli` |
| run Python/Go/Rust/native apps | Phase 3, **per-runtime spike passes** | per-runtime smoke `[RESEARCH]` |

### S.3 Cannot, and will not claim

24/7 uptime or any uptime percentage (Android may kill, throttle or vendor-kill —
§H.6); guaranteed execution with the screen off; running untrusted code safely
(one UID — §P.7); multi-tenant isolation (same); Docker or containers (no
namespaces without root); public reachability behind CGNAT (§L.5); TLS in-process
(D47, deliberate); dynamic plugin loading (S5-R020, deliberate); WebSocket
proxying (D46; `501` says so); custody, signing, balances, settlement (D1, D43);
push notifications without a cloud (FCM is a cloud dependency — SSE while
connected is the answer); per-app CPU quotas (no cgroups); a hard memory cap (soft
sampling only, §G.8); an identity that survives app-data deletion (deleting data
deletes the identity, correctly).

### S.4 Conditional — the condition is the answer

| capability | condition that makes it true |
|---|---|
| reachable from the internet | a tunnel or mesh initiated by the device, or a LAN with a public IP and a proxy — never a bind flag alone |
| survives reboot | operator enabled start-on-boot **and** the vendor ROM honours it `[DEVICE]` |
| keeps running in the background | the platform decides; Grom only reports what happened |
| safe to expose | credential set, TLS terminated elsewhere, gateway only, and the operator knows the phone is now a server |
| backup restores on a new phone | passphrase-based, not device-bound (§O.3) |
| works offline | everything except deploy-from-URL, the update check and a configured backend |

## T. Phased development plan

A phase closes only when its gates are green on a clean clone. Task detail is in
§U; the dependency table and the current position are in
`docs/GROM_IMPLEMENTATION_ROADMAP.md`.

### T.1 Phase 1 — analysis and core *(in progress)*

Goal: decide, and put the pure-policy scaffolding every later phase leans on.
**1A reference configuration is done**: `npub` (NIP-19 validated and normalised),
`relays` (ws/wss, stored only), `remoteBridge` (label/description, secret patterns
refused), exposed in authenticated `/v1/status` and the sanitized Copy-config
export; no websocket is opened and no tunnel started (D52, S5-R032), and a
reference-only change is applied in place without rebuilding the listener
(D54, S5-R034). **1B core hardening is next**: manifest schema, port allocator,
release layout, health-gate arithmetic, adapter registry skeleton, child-process
abstraction, scope model — all pure Kotlin, all JVM-tested.

Gate: `tools/run-gates` 10/10, plus `AppManifestTest`, `PortAllocatorTest`,
`HealthGateTest`, `ScopeTest`, plus new `tools/test-policy` invariants for the
adapter/import rule (R6) and the scope check.

### T.2 Phase 2 — the first server

Goal: the phone hosts one real application and the operator can trust what it
says about it. Scope: `ApplicationManager`, deploy from directory/ZIP/HTTPS,
health gate, rollback, retention, per-app logs, static host, `/v1/apps` routes,
the gateway, per-app secrets, the app card in the UI.

Gate: Phase 1 gates plus `DeployIntegrationTest` (real sockets: deploy → gate →
rollback), `GatewayTest`, `StaticHostTest`, `SecretSetTest`, and a `[DEVICE]`
checklist. Phase 2 can be **code-complete and still not closed** — that
distinction is deliberate.

### T.3 Phase 3 — runtimes and adapters

Goal: "universal" earns its keep, or is withdrawn for the runtimes that do not
pass. Scope: `RuntimeAdapter` v1; `NodeRuntime` (promotes the current launch);
`BinaryRuntime`; `StaticHost` as an adapter; spikes for Python, Go, Rust, native;
LND as the second `LightningBackend` to prove the contract; the ledger rule in
`tools/check-third-party`.

Gate: contract tests, per-runtime probe tests, the LND adapter's tests, and a
per-runtime device smoke matrix. A runtime that fails its spike is marked
`[RESEARCH]` in §I and **not shipped**; the plan is allowed to shrink.

### T.4 Phase 4 — multi-device

Goal: two devices, one operator, no confusion about which is authoritative.
Scope: node identity, pairing with scopes, registry, heartbeat, discovery as a
hint, explicit placement, node-loss behaviour.

Gate: `NodeIdentityTest`, `PairingTest` (including "no auto-approve" and "a
revoked pairing fails on the next request"), `NodeRegistryTest`,
`ClusterIntegrationTest` (two `ServerRuntime` instances over loopback),
`PlacementTest`, and the pairing audit entries.

### T.5 Phase 5 — advanced

Goal: the operator can keep and move their data, and script the device. Scope:
backup container, encrypted export, verified restore, CLI, SSE app events,
retention and pruning.

Gate: `BackupRoundTripTest`, `RestoreVerificationTest`, `tools/test-cli`, and a
`tools/test-policy` invariant that a backup excludes token, identity and pairing
keys.

### T.6 Phase 6 — production

Goal: someone other than the author can install, run and recover it. Scope:
production signing key (D4 still applies until it exists), release workflow with
the runtime verified, download page update, operator documentation, long-run
soak, external security review, a support matrix matching §K.

Gate: the release workflow produces both APKs, `apksigner verify` passes,
`tools/run-gates` is green on the tagged commit, the soak has a written result,
and the review's findings are fixed or recorded as accepted limitations.

## U. Concrete technical tasks

Numbered, file-level, in dependency order. "Gate" names the check that proves it.

### U.1 Phase 1B — core

| # | task | files | gate |
|---|---|---|---|
| T-01 | manifest class, parser, validator with stable refusal codes | `apps/AppManifest.kt` | `AppManifestTest` (10 refusals, 1 accept) |
| T-02 | port reservation and allocation in `47100..47199` | `apps/PortAllocator.kt` | `PortAllocatorTest` incl. exhaustion and collision |
| T-03 | release layout, pointer files, retention, atomic staging rename | `apps/ReleaseStore.kt` | `ReleaseStoreTest` |
| T-04 | health-gate arithmetic (N passes, grace, timeout) | `apps/HealthGate.kt` | `HealthGateTest` (pass, flake, timeout, never-up) |
| T-05 | child-process abstraction: env from scratch, stdin line, line ring, group kill | `proc/ChildProcess.kt`, extracted from `agent/NodeAgentProcess.kt` | `ChildProcessTest`, `tools/test-agent` still green |
| T-06 | adapter registry, `RuntimeAdapter`, `LaunchPlan` | `adapters/AdapterRegistry.kt`, `adapters/RuntimeAdapter.kt` | `AdapterRegistryTest` (dup id, API mismatch, permission refusal) |
| T-07 | scope model and the single `authorize(role, scope)` | `auth/Scope.kt` | `ScopeTest`, new static invariant |
| T-08 | status document versioning and app fields | `ServerRuntime.status()`, `SuperServerHost.status()` | `StatusDocumentTest` |
| T-09 | static invariant: no adapter class name outside `adapters/` | `tools/test-policy` (#25) | the gate, proven to fail on a seeded violation |

### U.2 Phase 2 — first server

| # | task | files | gate |
|---|---|---|---|
| T-10 | app supervisor: state machine, crash window, backoff, quarantine | `apps/AppSupervisor.kt` | `AppSupervisorTest` |
| T-11 | application manager: install, deploy transaction, uninstall, trash | `apps/ApplicationManager.kt` | `DeployIntegrationTest` |
| T-12 | deploy source: directory and ZIP through the system picker | `apps/source/LocalSource.kt` | `LocalSourceTest` |
| T-13 | deploy source: HTTPS archive, operator URL, no redirects, size cap | `apps/source/HttpSource.kt` | `HttpSourceTest` (raw-socket fixture, as in `HttpJsonClientTest`) |
| T-14 | Node runtime adapter promoting the current launch contract | `apps/runtime/NodeRuntime.kt` | `NodeRuntimeTest`, agent suite unchanged |
| T-15 | static site handler: traversal refusal, MIME allow-list | `apps/StaticHost.kt` | `StaticHostTest` |
| T-16 | data-plane gateway `/v1/apps/{id}/proxy/*` | `superserver/AppGateway.kt` | `GatewayTest` |
| T-17 | app routes (`GET /v1/apps`, restart, logs, rollback) | `superserver/SuperRouter.kt`, `http/Router.kt` | `SuperServerIntegrationTest` extended |
| T-18 | per-app sealed secret sets over stdin | `store/SecretSet.kt`, `apps/AppEnv.kt` | `SecretSetTest` |
| T-19 | operator surface: apps card (list, state, deploy, rollback, logs) | `GromActivity.kt`, `ui/Views.kt` | lint + a pure UI-state test |
| T-20 | fixture application for tests (tiny HTTP server run by the test JVM) | `app/src/test/.../fixtures/FixtureApp.java` | used by T-11…T-17 |

### U.3 Phase 3 — runtimes and adapters

| # | task | files | gate |
|---|---|---|---|
| T-21 | `BinaryRuntime`: execute a packaged `lib*.so` with declared argv | `apps/runtime/BinaryRuntime.kt` | `BinaryRuntimeTest`, device smoke `[DEVICE]` |
| T-22 | spike: CPython for bionic; document size, stdlib, `_ssl`, exec path | `docs/RUNTIME.md` appendix, spike script | written result; ship only if it passes |
| T-23 | spike: Go and Rust static binaries; settle DNS and TLS roots | `docs/RUNTIME.md` appendix | written result |
| T-24 | LND backend, second implementation of `LightningBackend` | `superserver/plugins/lightning/LndBackend.kt` | `LndBackendTest` with an injected transport |
| T-25 | adapter ledger enforcement (row required, constraint note for copyleft) | `tools/check-third-party` | the gate, seeded failure test |
| T-26 | runtime capability reporting in status and the CLI | `adapters/AdapterRegistry.kt` | `StatusDocumentTest` |

### U.4 Phase 4 — multi-device

> **Phase B is delivered ahead of Phase 4** as Phase 1F (D70–D75): T-27 and
> T-28 are implemented, plus the scoped peer client and the Devices section.
> **Phase C is delivered as Phase 1G** (D76–D78): the VPS hub (`tools/grom-hub`),
> a one-way scoped grant for an inbound reader, unique forward ports and
> `SSH_FORWARD_REFUSED`. Phone setup from a computer is Phase 1H
> (`tools/grom-provision`, D79) — it installs the signed APK, asks for the
> battery exemption and assigns the forward port, and it destroys nothing on the
> device. Diagnostics are Phase 1I (`engine/.../doctor/`, `docs/DOCTOR.md`,
> D80–D84): the phone explains why Android stopped it, from measurements only,
> and publishes three booleans in `/v1/status`. Shipping the engine inside a host
> application is Phase 1J (D85–D87, `docs/TWO-IN-ONE.md`): a host-chosen control
> port, a host-configured data plane through the screen's own validation, and a
> peripheral flavour that carries no runtime it never runs. Deliberately **not**
> implemented: discovery, a heartbeat endpoint, placement, a phone-side roster
> (C3) and companion (two-APK) installation. Those stay here.

| # | task | files | gate |
|---|---|---|---|
| T-27 | node identity: generate, seal, export public, derive `nodeId` | `cluster/NodeIdentity.kt` | `NodeIdentityStoreTest` |
| T-28 | pairing: code, challenge, approval, scopes, revocation | `cluster/Pairing.kt` | `PairingTest`, `FleetServiceTest` |
| T-29 | registry: schema, upsert, staleness, cached view | `cluster/NodeRegistry.kt` | `NodeRegistryTest` |
| T-30 | heartbeat endpoint and verifier | `superserver/SuperRouter.kt` | `HeartbeatTest` (stale timestamp, bad signature) |
| T-31 | CLI: status, apps, deploy, rollback, logs, nodes, audit | `cli/` (no listener) | `tools/test-cli` against a live loopback server |
| T-32 | placement: local / node / any, refusal when unsatisfiable | `cluster/Placement.kt` | `PlacementTest` |
| T-33 | discovery as a hint (NSD announce, no trust) | `cluster/Discovery.kt` | `DiscoveryTest` (unit; device `[DEVICE]`) |

### U.5 Phase 5 — advanced

| # | task | files | gate |
|---|---|---|---|
| T-34 | backup container: header, AEAD, manifest, PBKDF2 | `backup/BackupFormat.kt` | `BackupFormatTest` |
| T-35 | export/import with per-entry SHA-256 and scratch restore | `backup/BackupService.kt` | `BackupRoundTripTest` |
| T-36 | restore verification report and per-app rollback on failure | `backup/RestoreReport.kt` | `RestoreVerificationTest` |
| T-37 | SSE app events (`app.state`, `app.health`) on the existing bus | `superserver/EventBus.kt` | `EventBusTest` (bounded queue, drop counting) |
| T-38 | retention and pruning with byte accounting | `apps/ReleaseStore.kt` | `ReleaseStoreTest` |

### U.6 Phase 6 — production

| # | task | files | gate |
|---|---|---|---|
| T-39 | production signing key and release runbook | `docs/RELEASING.md`, `.github/workflows/release.yml` | tag build + `apksigner verify` |
| T-40 | soak harness: 72 h with one app, restart counters, memory samples | `tools/soak` (new) | a written result, not a screenshot |
| T-41 | device matrix run and a written support table | `docs/DEVICES.md` (new) | §K rows replaced with measured ones |
| T-42 | external security review and disposition | `docs/SECURITY-REVIEW.md` (new) | every finding fixed or recorded |

## V. Test plan

| layer | proves | where |
|---|---|---|
| **unit (JVM)** | pure policy: bind, manifest, ports, health arithmetic, scopes, backup format, audit chain | `app/src/test/java/...` |
| **integration over real sockets** | parser, worker pool, auth path, SSE, gateway, deploy transaction | `SuperServerIntegrationTest`, `DeployIntegrationTest`, `ClusterIntegrationTest` |
| **static gates** | the shape of the tree: permissions, absent symbols, headers, import direction, one URL | `tools/test-policy`, `tools/check-third-party` |
| **runtime tests** | the agent's protocol and degradation on the real Node binary | `tools/test-agent`, `tools/test-agent-runtime` |
| **device** | everything §K and §H call `[DEVICE]` | not available; a checklist, never a result |
| **long-run** | memory growth, fd leaks, restart storms, chain integrity | Phase 6 soak |
| **load** | connection and SSE caps, rate limiter, gateway throughput | scripted, bounded, honest about a phone's numbers |
| **security** | replay, tampering, path traversal, secret leakage, scope bypass | adversarial cases below |

**Integration rules.** Bind `127.0.0.1:0`, never a fixed port (as
`SuperServerIntegrationTest` already does, S5-R030); drive the real parser and
worker pool, not a mock router; for deploy, fail a health gate and prove the
pointer did not move, then pass it and prove it moved exactly once; for the
gateway, prove a proxied request cannot reach `/grom/v1/*`, cannot smuggle an
`Upgrade`, cannot pass `X-Grom-*`; for the cluster, prove a revoked pairing fails
on the *next* request and a stale heartbeat is never shown as live.

**Adversarial cases, each a required test.**

| case | expected |
|---|---|
| ZIP entry with `../`, or a manifest `entry` escaping the release dir | `PATH_ESCAPE`, nothing written |
| deploy URL redirecting to another host | refused, no request to the target |
| deploy URL with `http://` | `INSECURE_URL` |
| secret pasted into `remoteBridge.description` | refused (existing `RemoteBridgeHint` patterns) |
| `nsec1…` in the `npub` field | rejected (NIP-19 validation) |
| signed request replayed | `401`; the nonce is remembered only after verification |
| session token after a reload | `401` |
| viewer pairing calling deploy | `403 INSUFFICIENT_SCOPE` |
| gateway traversal toward the control plane | `404`; the route does not exist |
| backup with an edited manifest or an escaping path | AEAD failure or refusal before any write |
| token in a log line, or echoed by an app to stdout | `«redacted»` only |

**Test honesty.** A test that cannot run must fail loudly, not skip quietly.
`tools/test-agent-runtime` already takes that position. On a host without the
bionic runtime, a runtime test reports the missing file and fails the gate,
because a green gate that measured nothing is worse than a red one.

**Device checklist** (cannot be signed until hardware exists): install and
start; kill from recents and check the UI's honesty; force-stop and relaunch;
crash the agent three times and confirm quarantine survives reboot; reboot with
start-on-boot and check the quarantine interaction; deploy a fixture app, kill it,
check backoff and restart counts; idle 8 h on battery and record what survived;
repeat on a vendor ROM known for task killers; measure memory for 24 h; check a
16 KB page-size device if one is available.

## W. Risks and limitations

### W.1 Risks

| risk | likelihood | impact | mitigation | early signal |
|---|---|---|---|---|
| **no device ever runs it** | medium | fatal to §K and §H claims | keep the `[DEVICE]` tags; publish a support table only from measurements; make the gap visible, not hidden | a release note saying "unverified on hardware" for a second year |
| **vendor task killers defeat the FGS** | high | unreliable on popular phones | document per-vendor settings; never promise uptime; consider a keep-alive diagnostic screen | reports of the server dying after a swipe |
| **16 KB pages break the Node runtime** | medium | runtime does not start on Android 15+ | verify alignment in `scripts/fetch-node-android.sh`; add a release check | the binary failing to exec on a new device |
| **CPython spike fails** | high | Python apps need a Linux node | spike before promising; mark `[RESEARCH]` | linking failures, missing `_ssl` |
| **dependency creep** | medium | D3's auditability lost | `check-third-party` + the adapter ledger rule; a new dependency needs a decision, not a PR | the ledger growing faster than features |
| **scope model becomes a second auth system** | medium | two ways to authorise is one too many | one `authorize()`, one test file, both planes call it | a route checking a role inline |
| **deploy treated as safe** | medium | a malicious package owns the device | UI and API say "equivalent to shell access"; `apps.deploy`; audit | a confirmation that does not say it |
| **multi-device becomes a distributed system** | medium | scope explosion, consensus bugs | explicit placement, no quorum, the E.3 list | anyone proposing a scheduler |
| **licence surprise** | low | a release cannot ship | R.1–R.3, the constraint-note rule | a copyleft row with no written constraint |
| **test-policy drift** | medium | the gate stops meaning anything | every new decision gets an invariant, proven to fail on a seeded violation (T-09) | the count changing without a decision |
| **data loss on uninstall or restore** | low | operator trust destroyed | trash window (§G.5), scratch restore (§O.4), no in-place overwrite | a restore that overwrites a live app |

### W.2 Limitations that will not be fixed

1. **No hardware evidence yet** — every device-dependent statement is a hypothesis
   with a test plan.
2. **One UID** — applications are not isolated from Grom or each other (§P.7);
   a platform property, not an implementation gap.
3. **No TLS in-process** (D47) — plaintext without a terminator, and the docs say
   so.
4. **No public reachability behind CGNAT** (§L.5).
5. **Release signed with a committed test key** until Phase 6 (D4, D38):
   installable, updatable, not proof of publisher identity.
6. **Node is the only measured runtime**; everything else in §I is a plan with a
   spike, and the plan may shrink when a spike fails.
7. **The supervisor is one process** — if Android kills it, everything stops; the
   answer is honesty plus a restart policy, not redundancy.

### W.3 The test of the plan

If, at the end of Phase 2, "Grom hosts my application and tells me the truth about
it" is true on a real phone, the plan is working. If any part of that sentence
needs a caveat the UI does not show, the feature goes back a phase. That is the
only success metric this document treats as binding.

### M.8 Client connect: applications are clients, not nodes

M.5 is discovery *between Grom nodes*, and it still does not authenticate. A
third-party application needs a different thing: a **contract** discovery, not a
network beacon. `GET /grom/v1/connect` (D91, S6) is that document — schema, data
API, auth methods, scopes, the measured endpoint and the route map — and it is
served only when the operator has enabled the public data plane. There is still
no cloud directory, no registration and no multicast: the client is given an
address by its operator, exactly like every other Grom consumer.

Permission is scoped, never ambient (D92). An application is a peer with
`services.read`/`services.call`; it may enumerate services and invoke the ops
each one publishes in `scopedOps`, and it is never an operator. This keeps the
"one server, many clients" goal from quietly becoming "one operator key, many
apps".
