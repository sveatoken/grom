# Grom — implementation roadmap

The delivery order for `docs/GROM_MASTER_PLAN.md`. This file is the one that
answers "what do I do next, and what has to be green before I may".
Task ids (`T-nn`) match §U of the master plan.

---

## 1. Current position

| phase | state | evidence in the tree |
|---|---|---|
| **0 — F1 walking skeleton** | **done** | `docs/F1-implementation-plan.md` §Status, 9/9 steps |
| **1A — reference configuration** | **done** | `npub` (NIP-19), `relays` (reference only), `remoteBridge` (label/description), exposed in authenticated `/v1/status` and the sanitized Copy-config export; D52–D54 |
| **1C — embeddable engine, dashboard, SSH bridge** | **done** | `engine/` library, `GromEmbed`, API 3, `projects/`, `ssh/`, `DashboardModel`, sections UI; D55–D60 |
| **1D — integration ergonomics** | **done** | `ProjectNamespace` + API 4 `projectExec` (D61), `GromProject`, real heartbeat in `GromEmbed`, "How Grom works" section (D62), external Lightning node reference (D63), unlimited saved SSH servers (D64) |
| **1E — fleet view (Phase A)** | **done** | `tools/grom-fleet` + `tools/fleet/`, read-only over the existing public data plane; `docs/FLEET.md`; `tools/test-fleet` |
| **1F — identity, pairing, fleet (Phase B)** | **done (code)** | `engine/.../cluster/`, API 6, scoped peers, Devices section; `docs/CLUSTER.md`; D70–D75 |
| **1G — VPS hub and inbound-only trust (Phase C)** | **done** | `tools/grom-hub` + `tools/hub/`, one-way scoped grant (API 7), unique forward ports, `SSH_FORWARD_REFUSED`; `docs/HUB.md`; D76–D78 |
| **1H — provisioning from a computer** | **done** | `tools/grom-provision` + `tools/provision/`, `list`/`plan`/`apply`/`emit` over `adb`, battery exemption and one forward port per phone; `docs/PROVISION.md`; D79 |
| **1I — Doctor diagnostics** | **done** | `engine/.../doctor/` (facts, rules, report, run marker, one Android probe), API 8, Doctor section in the menu, `doctor`/`--fix` in `tools/grom-provision`, three status booleans; `docs/DOCTOR.md`; D80–D84 |
| **1J — "2 in 1": host-chosen control port, host-configured data plane, a lean peripheral** | **done** | `core/ControlPort` + `GromEmbed.Options.controlPort` (D85), `superSaveConfig` API 9 + `SuperServerConfigInput` (D86), peripheral `jniLibs` exclusion (D87), `docs/TWO-IN-ONE.md`; `tools/check-flavours` |
| **1K — "2 in 1" without complications: versioned consent, an automatic port, host-readable health** | **done** | `EmbedConsent` (D88), `ControlPort.resolve` + `GromEmbed.AUTO_PORT` (D89), `EmbedHealth` projection + `GromEmbed.health` (D90), `GromClient.bind(createIfMissing=false)` and a released registration after `onNullBinding`; `docs/TWO-IN-ONE.md` §2, §9 |
| **1L — universal client connect: discovery, scoped services, a thin SDK** | **done** | `GET /grom/v1/connect` (D91), `services.read`/`services.call` + `Plugin.scopedOps` (D92), `app.grom.sdk.connect` HTTP client + compiled `examples/connect` (D93); `docs/specs/S6-client-connect.md` |
| **1M — AI gateway: three protocols, sealed keys, a catalogue that limits spending** | **done** | `AiGateway` + `/v1/ai/*` (D94), sealed keys and endpoint policy (D95), measured not polled (D96), streaming over `SuperResponse.Stream` (D97); API **10**; `docs/AI.md`, `docs/specs/S7-ai-gateway.md` |
| **1M2 — server network: discovery, a roster and scoped server-to-server calls** | **done** | `GET /v1/mesh` + `POST /v1/mesh/call` (D99, D100), per-peer service shares (D101), LAN beacon + BLE advert + Wi-Fi Direct DNS-SD + secure RFCOMM `bt://` (D102), untrusted bounded discovery (D103); `docs/MESH.md`, `docs/specs/S8-server-mesh.md`; API **11** |
| **1N — an SSH/VPS you can actually diagnose, and more than one** | **done** | stage-based failure codes and keyboard-interactive passwords (D105), `sshVerify` without the data plane (D106), one current server and four live tunnels (D107); API **12**; `docs/SSH-BRIDGE.md` |
| **1P — the tunnel carries only what is bound, and says what the server accepts** | **done** | the forward reaches the endpoint the data plane is bound to, LAN included (D111), and an auth failure names the methods the server offered (D110); `docs/SSH-BRIDGE.md` §7a |
| **1Q — the embedding guide is executed, and builds in someone else's root** | **done** | the agent path is module-relative, the host-side `pickFirsts` contract is documented, `examples/embedded` is a compiled caller, and `tools/check-embed` builds a host APK from an unrelated root; D112 |
| **1B — core hardening** | **next** | `apps/AppManifest.kt`, `apps/PortAllocator.kt`, `apps/HealthGate.kt`, `apps/ReleaseStore.kt`, `proc/ChildProcess.kt`, `adapters/`, `auth/Scope.kt` — none exist yet |
| **2 — Application Manager MVP** | **blocked by 1B** | `apps/ApplicationManager.kt`, `apps/AppSupervisor.kt`, the gateway, `/v1/apps` |
| **3 — runtimes and adapters** | blocked by 2 | `apps/runtime/`, `adapters/`, LND |
| **4 — multi-device** | blocked by 3 | `cluster/` — identity/pairing delivered as 1F and the hub as 1G; placement, discovery and a phone-side roster remain |
| **5 — backup, CLI, advanced** | blocked by 4 | `backup/`, `cli/` |
| **6 — production** | blocked by 5 | signing key, soak, review |

Current version: `0.21.0` (versionCode 23). The fourteen gates in `tools/run-gates`
are the baseline; every phase below adds tests, not gates that replace them.

**The next concrete action** is T-01 (`apps/AppManifest.kt`), because every later
task either consumes a validated manifest or is blocked on one existing.

---

## 2. Ordering rules

1. **No phase skipping.** A phase's entry criteria are the previous phase's exit
   gate. The only exception is a spike (`[RESEARCH]`), which is allowed to run
   early, produces a written result, and ships nothing.
2. **Code-complete ≠ closed.** A phase whose gate includes a `[DEVICE]` item is
   closed only when hardware has run it. Mark it `code-complete` and do not claim
   the phase. This applies to Phase 2 onward.
3. **Policy before transport.** A rule that decides something (validation, port
   choice, health arithmetic, scope) lands as pure Kotlin with a JVM test
   (D30) before any route or UI exposes it.
4. **One gate per decision.** Every new decision gets a test or a
   `tools/test-policy` invariant in the same change that introduces it, and the
   invariant is proven to fail on a seeded violation (T-09 is the template).
5. **No new dependency without a decision.** A dependency added in a phase is a
   `Dxx` plus a `third_party/THIRD_PARTY.md` row (D17, S1-R026), not a build-file
   edit.
6. **The baseline never goes red.** `tools/run-gates` must be green at every
   phase boundary; a phase may add tests, never weaken one.

---

## 3. Dependency edges

`A → B` means B cannot start until A is green.

```
Phase 0 (F1) ─▶ Phase 1A (done) ─▶ Phase 1C (done) ─▶ Phase 1B ─▶ Phase 2 ─▶ Phase 3 ─▶ Phase 4 ─▶ Phase 5 ─▶ Phase 6

Phase 1C is orthogonal to 1B: it changed *where* the server lives and how an
operator sees it, not the application-manager core. It is sequenced first only
because the engine extraction touches every file, and doing it after the
Application Manager would have made a large mechanical move collide with new
code.

Phase 1B   T-01 ─▶ T-02 ─▶ T-03 ─▶ T-04       (T-05, T-06, T-07, T-08 beside it; T-09 last)
Phase 2    T-10 ─▶ T-11 ─▶ {T-12, T-13, T-18, T-38}; T-05 ─▶ T-14 ─▶ T-15 ─▶ T-16 ─▶ T-17 ─▶ T-19
Phase 3    T-21, T-22, T-23 on T-14; T-24 on T-06; T-25 on T-21+T-24; T-26 on T-06+T-08
Phase 4    T-27 ─▶ T-28 ─▶ T-33; T-27 ─▶ T-29 ─▶ T-30 ─▶ T-32; T-17+T-29 ─▶ T-31
Phase 5    T-34 ─▶ T-35 ─▶ T-36; T-37 on T-10+T-17
Phase 6    T-39 ─▶ T-40 ─▶ T-41 ─▶ T-42
```

Edges that are easy to forget:

- **T-05 (child process) blocks T-14 (Node runtime)**, not the other way round.
  Extracting the launcher first is what makes the second runtime a file instead of
  a fork of `ServerRuntime`.
- **T-16 (gateway) blocks every "expose this app" story.** Without it the only way
  to reach an app is to bind a LAN port, which is exactly what the plan forbids.
- **T-07 (scopes) blocks T-11 (deploy)** for the `apps.deploy` check, and blocks
  T-28 (pairing) because a pairing without scopes is the thing scopes fix.
- **T-27 (identity) blocks T-28, T-29, T-30, T-31.** Nothing multi-device exists
  before there is a node identity.
- **T-33 (discovery) is a hint** and may ship after everything else in Phase 4; it
  blocks nothing.

---

## 4. Phases and hard gates

Each gate is a command or a named test. "Green" means the command exits 0 on a
clean clone with the pinned toolchain, or the named test passes.

### Phase 0 — F1 walking skeleton — **done**

Gate (historic): `tools/run-gates` 9/9, `docs/F1-implementation-plan.md` §Status.

### Phase 1A — reference configuration — **done**

Delivered:

| file | what it is |
|---|---|
| `superserver/nostr/Nip19.kt` | `npub` validation: bech32 (not bech32m), 32-byte payload, hex accepted and normalised to `npub1…`; every other HRP, bad checksum and mixed case refused |
| `superserver/nostr/RelayUrl.kt` | `ws://`/`wss://` only, host/port/path, no userinfo, query, fragment or control characters; at most 8, 512 characters each |
| `superserver/nostr/RemoteBridgeHint.kt` | label ≤ 80 and description ≤ 240 characters; secret-shaped input (`nsec1…`, private-key headers, `password=`, `bearer …`) refused outright |
| `superserver/SuperStatusDocument.kt` | the reference block of the authenticated `/v1/status`; `lightningHealth.reachable` is `null` until probed, never assumed |
| `superserver/ConfigExport.kt` | the sanitized Copy-config document, built from an allow-list rather than by serialising the config |

Decisions: **D52** — reference configuration is stored and validated, never
dialled (no relay connection, no tunnel); **D53** — status and export are
allow-lists and carry no credential field; **D54** — a reference-only change is
applied in place, without rebuilding the listener, so sessions and SSE streams
survive. Nothing here is a runtime capability claim; it is configuration with
validation.

Gate (historic and still binding):

| check | command | result required |
|---|---|---|
| baseline gates | `tools/run-gates` | 9/9 |
| NIP-19 validation | `Nip19Test` (`:app:testServer29DebugUnitTest`) | bad checksum, `nsec1…`, mixed case, 31-byte payload all refused |
| relay validation | `RelayUrlTest` | `wss`/`ws` accepted; userinfo, query, fragment refused |
| bridge hint | `RemoteBridgeHintTest` | secret-shaped input refused |
| config refusal codes | `SuperServerConfigTest` | `BAD_NPUB`, `BAD_RELAY`, `TOO_MANY_RELAYS`, `BAD_REMOTE_BRIDGE` all exercised |
| reference-only reload | `SuperServerConfigTest.sameRuntimePolicyIgnoresOnlyReferenceFields` | sessions and SSE survive; `super.reload.reference` is appended (S5-R034) |
| status / export shape | `SuperStatusDocumentTest`, `ConfigExportTest` | no password hash, no sealed key, no `nsec`, no audit record |
| no relay connection | `tools/test-policy` (#23) | no `Socket`, `URL`, `WebSocket` or `openConnection` under `superserver/nostr` |
| no credential leak | `tools/test-policy` (#24) | `passwordHash`, `apiKeySealed`, `nsec1`, `PRIVATE KEY` absent from the status and export sources |
| no new outbound path | `tools/test-policy` (#17) | the "one hardcoded URL" invariant still passes |

The static count is a claim: it is 24 in this tree (17 in the F1 plan). When a
phase adds an invariant, update the header comment in `tools/test-policy` and the
count in `tools/run-gates` in the same change.

The records are **D52** (reference configuration, never dialled), **D53**
(status and export are allow-lists), **D54** (a reference-only change does not
restart the listener), with the normative text in S5-R032–R034. If the log, the
spec and the code ever disagree, that is a defect in 1A and the phase reopens.

### Phase 1C — embeddable engine, dashboard, SSH bridge — **done**

Delivered:

| file | what it is |
|---|---|
| `engine/` | the server as an Android library (`app.grom.engine`); every server package moved here, `app/` is a host |
| `engine/.../embed/GromEmbed.kt` | consent, start, embedded client, heartbeat, bounded project registration |
| `engine/.../embed/GromProject.kt` | typed registration, heartbeat, namespaced `put`/`get` (D61) |
| `engine/.../projects/ProjectNamespace.kt` | `p:<projectId>:` prefix, ownership gate |
| `engine/.../projects/ProjectRegistry.kt` | project records with heartbeat-derived `alive`/`stale`, atomic file, 8 projects |
| `engine/.../ssh/SshTarget.kt` | target model, validators, draft, limits (8 targets, 2 active) |
| `engine/.../ssh/SshTargetStore.kt` | sealed secrets, TOFU pin storage, no plaintext on disk |
| `engine/.../ssh/SshKeys.kt` | Ed25519 generation, OpenSSH `PROTOCOL.key` writer, one loader for generated and imported keys |
| `engine/.../ssh/SshTunnelManager.kt` | MINA SSHD client, TOFU verify, one `tcpip-forward`, `SshForwardingPolicy` |
| `sdk/.../IGromServer.aidl` | API 3: projects, export, agentExec, SSH, lightning probe |
| `app/.../ui/DashboardModel.kt` | pure lamp projection (green only for measured values) |
| `app/.../ui/MenuModel.kt` | menu order (`ssh` … `update`, `invite`, `help`), role split, section titles |
| `app/.../GromActivity.kt` | hero + "Now serving" only, header Menu; invite and help are menu entries (D69) |
| `docs/EMBEDDING.md`, `docs/SSH-BRIDGE.md` | host guide and bridge guide |

Gate: `tools/run-gates` 9/9, policy **29/29**, `SshTunnelManagerTest` proving a
real reverse forward against an in-process SSH server, `DashboardModelTest`,
both flavours green, lint 0/0.

Not closed by this phase: **nothing here has run on a phone.** The SSH tunnel is
proven against a real SSH implementation on the JVM, not on device hardware.

### Phase 1B — core hardening — **next**

Entry: Phase 1A green. Deliverables: T-01 … T-09.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 10/10 |
| manifest | `AppManifestTest` | every refusal code in §G.3 exercised; one accept |
| ports | `PortAllocatorTest` | allocate, release, collision, exhaustion, stable reuse |
| releases | `ReleaseStoreTest` | pointer written last; retention never prunes `previous` |
| health arithmetic | `HealthGateTest` | pass, flake, timeout, never-up |
| child process | `ChildProcessTest` | env from scratch, stdin line, 4 KiB line cap, group kill |
| adapter registry | `AdapterRegistryTest` | duplicate id, API mismatch, permission refusal |
| scopes | `ScopeTest` | owner/operator/viewer matrix; absent scope denies |
| status | `StatusDocumentTest` | additive fields only; `api` unchanged |
| new invariant | `tools/test-policy` (#25) | fails on a seeded adapter reference outside `adapters/` |

Exit: all of the above green. No device item, so 1B can actually close.

### Phase 2 — Application Manager MVP

Entry: 1B closed. Deliverables: T-10 … T-20, T-38.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 10/10 |
| deploy transaction | `DeployIntegrationTest` | real sockets; failed gate does not move the pointer; a successful one moves it once |
| local + HTTPS sources | `LocalSourceTest`, `HttpSourceTest` | traversal refused; redirects refused; `http://` refused |
| Node runtime | `NodeRuntimeTest` | plan equals the current launch contract; agent suite unchanged |
| static host | `StaticHostTest` | traversal refused; MIME allow-list |
| gateway | `GatewayTest` | no route to `/grom/v1/*`; no `Upgrade`; no `X-Grom-*` passthrough |
| app routes | `SuperServerIntegrationTest` (extended) | 401/403/404/405/413/429 unchanged in shape |
| secrets | `SecretSetTest` | sealed at rest; delivered on stdin; absent from status and logs |
| retention | `ReleaseStoreTest` | bytes accounted; `previous` preserved |
| device checklist | §V.5 of the master plan | **cannot be signed without hardware** |

Exit: code-complete when the tests above are green. **Closed** only when the
device checklist has been run and recorded in `docs/DEVICES.md`. Until then the
release notes say `unverified on hardware`.

### Phase 3 — runtimes and adapters

Entry: Phase 2 code-complete. Deliverables: T-21 … T-26.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 10/10 |
| binary runtime | `BinaryRuntimeTest` + device smoke | the mechanism works on hardware, or the adapter is withdrawn |
| python spike | written result in `docs/RUNTIME.md` | either a runnable bionic CPython with `sqlite3` and `ssl`, or `[RESEARCH]` stays and nothing ships |
| go/rust spike | written result | DNS and TLS roots resolved, or `[RESEARCH]` stays |
| second adapter | `LndBackendTest` | proves the contract needs no `ServerRuntime` change |
| ledger rule | `tools/check-third-party` | a copyleft row without a `constraint:` note fails |
| status | `StatusDocumentTest` | runtimes and their probes are visible, `MISSING` names the file |

Exit: all green, with the explicit possibility that a runtime is **dropped**
rather than shipped. A failed spike that is recorded is a passing phase.

### Phase 4 — multi-device

Entry: Phase 3 closed. Deliverables: T-27 … T-33.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 10/10 |
| identity | `NodeIdentityTest` | deterministic `nodeId`; seed never leaves sealed storage |
| pairing | `PairingTest` | no auto-approve; revocation effective on the next request; code single-use and expiring |
| registry | `NodeRegistryTest` | stale after 45 s, unreachable after 120 s, never shown as live |
| heartbeat | `HeartbeatTest` | stale timestamp and bad signature both `401` |
| cluster | `ClusterIntegrationTest` | two `ServerRuntime` instances over loopback; local work survives control loss |
| placement | `PlacementTest` | `NO_ELIGIBLE_NODE` instead of pending forever |
| CLI | `tools/test-cli` | every command against a live loopback server |
| discovery | `DiscoveryTest` | announce only; no trust decision |

Exit: green, plus a two-device manual checklist `[DEVICE]`.

### Phase 5 — backup, CLI, advanced

Entry: Phase 4 closed. Deliverables: T-34 … T-38 (T-31 may land here if Phase 4
ran long).

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 10/10 |
| format | `BackupFormatTest` | AEAD, authenticated manifest, path escape refused |
| round trip | `BackupRoundTripTest` | export → wipe → import → every entry verified |
| restore report | `RestoreVerificationTest` | a failing app keeps its old directory and is reported |
| exclusion | `tools/test-policy` (new) | no token, identity or pairing keys in an archive |
| events | `EventBusTest` | bounded queue, drop counting |

Exit: green.

### Phase 6 — production

Entry: Phase 5 closed. Deliverables: T-39 … T-42.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 10/10 on the tagged commit |
| release | `.github/workflows/release.yml` | both APKs, runtime inside, `apksigner verify` passes |
| soak | `tools/soak` | 72 h with a written result (memory, restarts, chain integrity) |
| device matrix | `docs/DEVICES.md` | §K rows replaced with measured ones |
| review | `docs/SECURITY-REVIEW.md` | every finding fixed or recorded as accepted |

Exit: a release a stranger can install, run and recover.

---

## 5. Task table

Task | Depends on | Gate
---|---|---
T-01 manifest | Phase 1A | `AppManifestTest`
T-02 port allocator | T-01 | `PortAllocatorTest`
T-03 release store | T-01, T-02 | `ReleaseStoreTest`
T-04 health gate | T-03 | `HealthGateTest`
T-05 child process | Phase 1A | `ChildProcessTest`, `tools/test-agent`
T-06 adapter registry | T-05 | `AdapterRegistryTest`
T-07 scopes | Phase 1A | `ScopeTest`, static invariant
T-08 status fields | T-01 | `StatusDocumentTest`
T-09 static invariant | T-06, T-07 | `tools/test-policy` (#25), seeded failure
T-10 app supervisor | T-04, T-05 | `AppSupervisorTest`
T-11 application manager | T-03, T-10 | `DeployIntegrationTest`
T-12 local source | T-11 | `LocalSourceTest`
T-13 HTTPS source | T-11 | `HttpSourceTest`
T-14 Node runtime | T-05, T-06 | `NodeRuntimeTest`
T-15 static host | T-14 | `StaticHostTest`
T-16 gateway | T-15 | `GatewayTest`
T-17 app routes | T-11, T-16 | `SuperServerIntegrationTest`
T-18 secret sets | T-05, T-11 | `SecretSetTest`
T-19 UI apps card | T-17 | lint, pure UI-state test
T-20 fixture app | T-10 | used by T-11…T-17
T-21 binary runtime | T-14 | `BinaryRuntimeTest`, device smoke
T-22 Python spike | T-14 | written result in `docs/RUNTIME.md`
T-23 Go/Rust spike | T-14 | written result
T-24 LND backend | T-06 | `LndBackendTest`
T-25 ledger rule | T-21, T-24 | `tools/check-third-party` seeded failure
T-26 runtime status | T-06, T-08 | `StatusDocumentTest`
T-27 node identity | Phase 2 | `NodeIdentityTest`
T-28 pairing | T-07, T-27 | `PairingTest`
T-29 registry | T-27 | `NodeRegistryTest`
T-30 heartbeat | T-27, T-29 | `HeartbeatTest`
T-31 CLI | T-17, T-29 | `tools/test-cli`
T-32 placement | T-29, T-30 | `PlacementTest`
T-33 discovery | T-28 | `DiscoveryTest`, no trust decision
T-34 backup format | Phase 3 | `BackupFormatTest`
T-35 export/import | T-34 | `BackupRoundTripTest`
T-36 restore report | T-35 | `RestoreVerificationTest`
T-37 SSE app events | T-10, T-17 | `EventBusTest`
T-38 retention | T-03 | `ReleaseStoreTest`
T-39 production signing | Phase 5 | tag build, `apksigner verify`
T-40 soak | T-39 | 72 h written result
T-41 device matrix | T-40 | `docs/DEVICES.md` measured rows
T-42 security review | T-41 | `docs/SECURITY-REVIEW.md`

---

## 6. Critical path and parallel tracks

**Critical path:** T-01 → T-03 → T-04 → T-10 → T-11 → T-16 → T-17 → T-20 →
Phase 2 gate → T-27 → T-29 → T-30 → Phase 4 gate → T-35 → Phase 5 gate → T-39 →
Phase 6 gate.

Parallel work that blocks nothing on the critical path:

- **T-05 / T-06** (child process, adapter registry) beside T-01…T-04.
- **T-07 / T-08 / T-09** (scopes, status, invariant) beside T-10…T-13.
- **T-21…T-26** (runtime and adapter work, spikes) can start once T-14 lands; a
  spike is allowed to run earlier and ship nothing.
- **T-31 (CLI)** can start as soon as app routes exist; it is a client, not a
  foundation.
- **T-41 (device matrix)** can start the moment hardware is available, in any
  phase; every measurement unblocks a `[DEVICE]` line in `docs/GROM_MASTER_PLAN.md`
  §K.

---

## 7. What not to start early

| tempting work | why it waits |
|---|---|
| a scheduler or placement heuristic | Phase 4, and only the fixed-order rule in §M.6 |
| a web dashboard | never (master plan §E.3) |
| dynamic plugin loading | never (S5-R020) |
| a bundled tunnel or relay client | never (D52–D54; tunnels stay the operator's) |
| multi-tenant isolation | never on Android (§P.7); a Linux node is the answer |
| a second runtime before `RuntimeAdapter` exists | Phase 3; T-14 then T-21 |
| backup before the release layout is stable | Phase 5; a backup format over a moving layout is a migration cost |
| production signing before the feature set freezes | Phase 6 (D4 still governs until then) |

---

## 8. Definition of done for any phase

Run, in this order, and paste the result into the phase's entry in this file:

```bash
tools/run-gates                                  # 9 baseline gates
gradle :app:testServer29DebugUnitTest            # + the phase's new tests
gradle :app:testPeripheral26DebugUnitTest
tools/test-policy                                # count = the claim in its header
tools/check-third-party                          # ledger matches the build
tools/verify-ipc-signing                         # C-008, needs the three APKs
tools/test-agent                                 # secrets over stdin, not argv
tools/test-agent-runtime                         # agent protocol on the real Node
tools/check-site                                 # the download page stays static
tools/test-fleet                                 # tools/grom-fleet protocol tests
tools/test-hub                                   # tools/grom-hub roster tests
tools/test-provision                             # tools/grom-provision tests (no phone)
gradle :app:lintServer29Debug :app:lintPeripheral26Debug
```

Plus, for a phase with a `[DEVICE]` item: the checklist in master plan §V.5,
recorded in `docs/DEVICES.md` with the device model, Android version and the date.
A phase without that record is `code-complete`, never `closed`.

### Phase 1D — operator alerts, project ergonomics — **done**

Delivered:

| file | what it is |
|---|---|
| `engine/.../notify/NotifySettings.kt` | enabled / chat ID / sealed token, stable validation codes |
| `engine/.../notify/NotifySettingsStore.kt` | owner-only atomic JSON, 64 KiB cap, token only as `SecretBox` ciphertext |
| `engine/.../notify/NotifyEvent.kt` | the fixed event table (17 codes: 16 state events plus the test message), each with one English sentence |
| `engine/.../notify/NotifyRules.kt` | pure `classify(action, detail)`: unknown actions ignored, detail parsed for a code only |
| `engine/.../notify/TelegramNotifier.kt` | one daemon worker, 16-message queue, 10 s coalescing, 20/min, 2 attempts, 8 s timeout |
| `engine/.../notify/NotifyStatus.kt` | the sanitised `{enabled, configured, lastDelivery}` block |
| `app/.../ui/NotifyModel.kt` | pure lamp projection: green only after a measured `ok=true` |
| `app/.../ui/HelpModel.kt` | role-aware help: three blocks on `peripheral26`, four on `server29` |
| `sdk/.../IGromServer.aidl` | API 5: `notifySettingsJson`, `notifySaveSettings`, `notifyTestJson` |

Gate: `tools/run-gates` 9/9, policy **33/33**, both flavours green, lint 0/0,
`TelegramNotifierTest` proving the queue, coalescing, the two attempts, the rate
limit and that a token never appears in an error.

Not closed by this phase: **no message has been sent to the real Bot API from a
device.** The tests use a fake transport; the host half is code-reviewed only.

Exit criteria for anything that depends on the VPS side: the operator's `Send
test message` must return `ok` on a physical phone before an alert is trusted —
recorded as a B2 item, not a JVM item.

### Phase 1E — fleet view, Phase A — **done (code)**

The smallest step toward several devices: an external, read-only view. It adds
no identity, no pairing, no aggregation and **no change to Grom**.

| file | what it is |
|---|---|
| `tools/grom-fleet` | launcher; requires only a JDK 17 |
| `tools/fleet/GromFleet.java` | one file: config validation, password login, Ed25519 `grom-ed25519-v1` signing, a small JSON reader, table renderer |
| `tools/fleet/fleet.example.json` | the documented config shape, with both auth modes |
| `tools/test-fleet` | 18 protocol tests against a real loopback server |
| `docs/FLEET.md` | setup, schema, lamp semantics, security notes |

Why it is not in the app: `tools/test-policy` #17/#27 keep the compiled hosts and
the socket surface fixed, and the fleet tool deliberately adds neither. It calls
only `POST /v1/login`, `GET /v1/status` and `GET /v1/health` — the endpoints that
already exist — and it is not part of any APK.

Gate: `tools/run-gates` **10/10** (the fleet self-test is the new tenth gate),
policy **33/33** unchanged, both flavours and lint unchanged.

Not closed by this phase: nothing has been run against a physical phone from
this repository (B2). The first real run is the operator's, with their own
credentials.

### Phase 1F — device identity, pairing and fleet, Phase B — **done (code)**

| file | what it is |
|---|---|
| `engine/.../cluster/NodeIdentity.kt` | one Ed25519 key per device, sealed seed, corrupt-file refusal |
| `engine/.../cluster/PeerRecord.kt` | scopes, paired record, measured state, address validation |
| `engine/.../cluster/PeerStore.kt` | atomic JSON, size cap, revoke |
| `engine/.../cluster/Pairing.kt` | one-time code, signed hello/accept, operator decision |
| `engine/.../cluster/PeerClient.kt` | signed `GET /v1/status` over the shared transport |
| `engine/.../cluster/FleetService.kt` | join, confirm, refresh, reset |
| `engine/.../cluster/FleetStatus.kt` | `{nodeId,label}` only |
| `superserver/SuperAuth.kt`, `SuperRouter.kt` | principals, `SCOPE_REQUIRED`, `/v1/config`, pairing routes |
| `app/.../ui/FleetModel.kt`, `GromActivity.kt` | measured lamps, pairing dialogs, refresh |
| `docs/CLUSTER.md` | the operator guide and the security model |

Gate: `tools/run-gates` **10/10**, policy **37/37**, both flavours green, lint
0/0. Pairing, the scope matrix and the seeded-seed rule are also pinned by
`tools/test-policy` #34–#37.

Not closed by this phase: nothing has been run against a physical phone (B2),
there is no TLS (D47), no global revoke, and pairwise pairing does not scale
beyond a handful of devices. **Phase C (VPS hub)** is the answer for many phones
behind CGNAT and needs its own decision.

### Phase 1G — VPS hub and inbound-only trust, Phase C — **done**

| file | what it is |
|---|---|
| `tools/grom-hub` | launcher; requires only a JDK 17 |
| `tools/hub/GromHub.java` | identity init, signed poller, loopback roster, self-test |
| `tools/hub/hub.example.json` | documented schema, one port per phone |
| `tools/test-hub` | protocol tests against a strict signature verifier |
| `engine/.../cluster/PeerStore.kt` | `grant()`: derived id, no address, scopes required |
| `engine/.../cluster/PeerClient.kt` | `PEER_NO_ADDRESS`: an empty address is never dialled |
| `engine/.../ssh/SshTargetStore.kt` | one forward port per host (`BAD_FORWARD_PORT_CONFLICT`) |
| `engine/.../ssh/SshTunnelManager.kt` | `SSH_FORWARD_REFUSED` for a refused forward |
| `app/.../GromActivity.kt`, `ui/FleetModel.kt` | "Trust a key", inbound-only rows |
| `docs/HUB.md` | topology, setup, failure behaviour, security |

Gate: `tools/run-gates` **11/11** (the hub self-test is the new eleventh gate),
policy **41/41**, both flavours green, lint 0/0.

Not closed by this phase: nothing has been run against a physical phone (B2) or
a real VPS from this repository; there is no phone-side roster (C3, deliberately
deferred); the hub is a single point of *observation*, not of operation; and a
phone that is off produces no message and no row update.

### Phase 1H — provisioning from a computer — **done**

Follows the review of comparable projects (MDMesh, phoneborg, phonenode, hmdm)
and the agreed order: provisioning first, the "Doctor" diagnostics second.
Nothing here installs other applications' APKs and nothing becomes a device
owner — Grom keeps hosting its own Node/static apps (D79).

| file | what it is |
|---|---|
| `tools/grom-provision` | launcher; requires a JDK 17 and `adb` |
| `tools/provision/GromProvision.java` | `list` / `plan` / `apply` / `emit` / `doctor`, plus 96 in-process cases |
| `tools/test-provision` | the gate: runs the real tool against a fake `adb` |
| `docs/PROVISION.md` | what it does, what it refuses to do, exit codes, limits |

What it settles, measured from the device rather than assumed: the installed
`versionName` (so a blank phone shows `-`), the battery-exemption state (so a
vendor that ignored the request is `partial`, not "done"), the arm64 runtime
requirement (`no-arm64` before an install that would fail later), and the
forward port each phone owns (41001–41099, fed to `tools/grom-hub` through
`emit`).

Gate: `tools/run-gates` **12/12** (the provisioning self-test is the new twelfth
gate), policy **42/42** (#42 bans destructive commands and shell-string
execution in that tool), both flavours green, lint 0/0.

Not closed by this phase: no physical phone was provisioned from this
repository (B2); the tool does not configure the app (no `Public access`, no SSH
target, no pairing) because those are per-device confirmations; and it needs a USB
cable. The follow-up item — explaining *why* Android stopped a server — is Phase
1I below.

### Phase 1I — Doctor diagnostics — **done**

The second half of the agreed order: after provisioning a phone, explain why it
keeps stopping. Nothing here changes a system setting on its own, runs a shell or
polls in the background (D80).

| file | what it is |
|---|---|
| `engine/.../doctor/DoctorFacts.kt` | the measurement, every field nullable |
| `engine/.../doctor/DoctorRules.kt` | thresholds, exit-reason levels, the verdict — pure |
| `engine/.../doctor/DoctorFacade.kt` | merges probe + engine status + run marker — pure |
| `engine/.../doctor/DoctorDocument.kt` | the allow-listed document `/v1/status` and `doctorJson()` use |
| `engine/.../doctor/RunMarkerStore.kt` | the one remembered fact, 4 KiB, atomic, corrupt ⇒ unknown |
| `engine/.../doctor/AndroidDoctorProbe.kt` | the only file here that touches the platform |
| `app/.../ui/DoctorModel.kt` | lamps, rows, fact formatting — pure `Lamp` mapping |
| `tools/grom-provision doctor` | reads the facts an app cannot, `--fix` for two of them |

What it settles, measured rather than assumed: whether the phone is exempt from
Doze, whether background work is restricted, the standby bucket, whether the
running notification is visible, the platform's own exit reason (API 30+), and
whether the previous process ended without `onDestroy`. Three of those are also
published in `/v1/status` so a hub can say *why* a phone is unreachable (D84).

Gate: `tools/run-gates` **12/12**, policy **48/48** (#43 one Android adapter,
#44 no shell, #45 no scheduler, #46 permission budget, #47 three status fields,
#48 the API version has one source),
engine **610** and app **63 + 63** JVM tests, `tools/test-provision` **96/96**,
lint 0/0.

Not closed by this phase: no physical phone was measured from this repository
(B2); the Doctor raises the odds of surviving and explains a failure, it does not
promise uptime; and force-stop remains unbeatable.

### Phase 1J — "2 in 1": a host app that ships Grom — **done**

The shipping gap between "the engine is embeddable" and "an operator can install
one app that has a server". Three pieces, each with a decision:

| decision | change |
|---|---|
| **D85** | `core/ControlPort` + `GromEmbed.Options.controlPort`: the host chooses the loopback control port; a taken port is `PORT_IN_USE`, never a silent fallback and never a signature/network failure |
| **D86** | `IGromServer.superSaveConfig` (API 9) + `SuperServerConfigInput`: a host configures the data plane with the screen's own validation, and `SuperServerHost` never binds a plane it was not asked to start |
| **D87** | the peripheral flavour excludes `jniLibs`: ~39 MB → ~1.6 MB release (~2.2 MB debug), because that role never executes the agent |

`docs/TWO-IN-ONE.md` is the guide: modules, runtime, port, consent, foreground
start, boot, battery, data plane, coexistence with a standalone Grom, and the
codes a host must handle.

Gate: `tools/run-gates` **13/13** (new `tools/check-flavours`), policy **51/51**
(#49 port choice, #50 one validation contract, #51 lean peripheral),
`ControlPortTest` **5**, `SuperServerConfigInputTest` **10**, `check-flavours`
**8/8** with the runtime installed (6/6 and an explicit note without it), lint
0/0.

Not closed by this phase: no host application was built against this repository
in CI (the two `testapps` only prove the signing gate); a companion-install model
(two APKs, one flow) still needs a trust decision, because a third-party host
cannot sign with Grom's key; and the runtime is still arm64-only.

### Phase 1K — "2 in 1" without complications — **done**

Phase 1J made the combination *possible*. This phase makes it *simple*, i.e.
something a host can ship without re-implementing the engine's honesty rules:

| decision | change |
|---|---|
| **D88** | consent records `(noticeVersion, time)`; a start that declares different text is `CONSENT_OUTDATED`, so an old "yes" cannot authorise new behaviour |
| **D89** | `GromEmbed.AUTO_PORT`: the port is resolved before the start (`ControlPort.resolve`), reused while free, persisted and returned; a running listener never moves and nothing falls back silently |
| **D90** | `EmbedHealth.of(report)` + `GromEmbed.health`: a state, a headline code and at most four `(code, EmbedAction)` pairs, projected from the Doctor's own rules; the engine returns codes, never sentences |

Two defects found in the audit were fixed on the way: `GromClient` dropped a dead
service registration without calling `unbindService` (a connection leak that also
made every later `bind` a no-op), and `GromClient.bind` could not bind **without
starting** the server, so a health check would have created the service it was
asked to inspect.

Gate: `tools/run-gates` **13/13**, policy **55/55** (#52 pure embed model, #53
consent and port decisions, #54 every code documented, #55 the notification
worker is closed), JVM tests **801** (engine **672**, sdk **3**, app **63 + 63**),
of which `EmbedHealthTest` **18**, `EmbedConsentTest` **7**, `EmbedSettingsTest`
**6**, `GromEmbedStartCodeTest` **3** and `ControlPortResolveTest` **13**, lint
0/0.

Not closed: the companion model (model B in `docs/TWO-IN-ONE.md`) is still out of
scope — it needs a second trust relation, not code. (A host APK *is* built from
an unrelated root since D112: `tools/check-embed`.)

### Phase 1L — universal client connect — **done**

Grom is usable as a local server without either side inventing a protocol. The
gap was discovery (there was none) and permissions (a client that needed a
service had to be handed an operator key):

| decision | change |
|---|---|
| **D91** | `GET /grom/v1/connect`: an anonymous base document (schema, api, versions, auth methods, scopes, measured endpoint, route map) that adds the caller's capabilities and the readable service list only with a valid credential; an invalid credential is `401`, never a downgrade |
| **D92** | `PeerScope.SERVICES_READ`/`SERVICES_CALL` + `Plugin.scopedOps()`, an op-level allow-list with an empty default; `AgentPlugin` publishes `echo`, `kv.get`, `kv.put`, `ping`; `LightningPlugin` publishes nothing |
| **D93** | `app.grom.sdk.connect`: a dependency-free HTTP client with an injected `GromSigner`, a purpose-built JSON reader/writer, and a `canonicalString` pinned byte-for-byte to the server's `RequestSigner`; `examples/connect` is a compiled, tested module |

`docs/specs/S6-client-connect.md` is the integrator document: discovery,
compatibility, both authentication mechanisms, scopes, services, RPC, the error
table, reconnect and the reference code. `/v1/health`, `/v1/status`, `/v1/config`
and every existing operator route are unchanged; the Binder API stays **9**.

Gate: `tools/run-gates` **13/13**, policy **59/59** (#56 connect route, #57
scoped services, #58 SDK has no key and no dependency, #59 connect is an
allow-list), `ConnectRouterTest` **13**, `GromConnectClientTest` **15**,
`GromJsonTest` **8**, `ConnectExampleTest` **3**, lint 0/0.

Not closed: MCP is not implemented (the RPC already describes and calls a
service; an adapter can be a separate bridge when an MCP-only client appears);
`/v1/events` stays operator-only, so a scoped client refreshes on open, not by
push; and no third-party application has been built against the SDK in CI yet.

### Phase 1M — AI gateway — **done**

Grom can be the AI endpoint of the phone: one local API, the operator's own
provider accounts, and a catalogue that is also the spending limit.

| decision | change |
|---|---|
| **D94** | `AiGateway`: three upstream protocols (OpenAI-compatible, Anthropic, Gemini) behind `POST /v1/ai/chat/completions`, `POST /v1/ai/messages`, `POST /v1/ai/v1/messages`, and a `ai` service for clients that already speak `/v1/plugins/{id}/rpc`. Seven vendor presets plus `custom` (any of the three protocols); a model outside the catalogue is never forwarded; a parameter Grom does not implement is refused by name |
| **D95** | keys sealed by `SecretBox` before they are written, never projected (operator view says `keySet`), endpoints `https` or loopback `http` only, redirects never followed, upstream error text truncated and free of URL and key |
| **D96** | the gateway never polls: a provider is measured only by the operator's **Test** (one `max_tokens: 1` call) or by a real request; `available` in the catalogue is configuration, and S7 says so |
| **D97** | streaming rides `SuperResponse.Stream`: pre-flight refusals are JSON before the head, later failures are error events, and a client disconnect stops the upstream read |

Scopes grew by exactly two — `ai.read` and `ai.chat` — and `services.call`
deliberately grants neither: the `ai` service publishes an empty `scopedOps` and
declares its operations through `Plugin.requiredScopes`. API **10** adds seven
Binder methods for the screen (`aiStatusJson`, `aiSaveProviderJson`,
`aiRemoveProvider`, `aiSaveModelJson`, `aiRemoveModel`, `aiConfigureJson`,
`aiProbeProviderJson`).

`docs/AI.md` is the operator guide and `docs/specs/S7-ai-gateway.md` the
integrator contract; `app.grom.sdk.connect` gained `aiCatalog`, `aiChat` and
`aiChatStream`.

Gate: `tools/run-gates` **13/13**, policy **65/65** (#60 keys sealed and
unprojected, #61 ai scopes, #62 endpoint policy, #63 no socket in the adapters,
#64 published under `ai.read` and nothing polls), `AiProviderTest` **13**,
`AiStoreTest` **14**, `AiGatewayTest` **26**, `AiRouterTest` **11**,
`AiGatewayIntegrationTest` **4** (three over a real loopback socket, one opt-in
live call), `GromConnectClientTest` **+8**, lint 0/0.

Not closed: tools/function calling, images, audio and embeddings are refused
rather than implemented; no vendor has been called with a real key from this
repository (the live test is opt-in via `GROM_AI_LIVE_*`); and a vendor SDK needs
a password session token, because it cannot sign Grom's Ed25519 requests.

### Fix shipped with 0.17.1 — the agent's argv (D98)

Verifying the published artifact found a real defect: `AgentLauncher` passed
`--experimental-default-type=commonjs`, which the pinned Node v24.22.3 rejects
with `bad option`. The agent therefore exited before `GROM_AGENT_READY` — the
server would look installed and dead. The switch is gone (the agent is CommonJS,
which the pinned runtime loads by default), `AgentLauncherTest` pins the
six-element argv, policy #65 keeps unproven engine switches out, and
`scripts/build-node-android.sh --verify` now starts the packaged binary with the
launcher's exact argv and waits for readiness, so this class of defect fails a
gate instead of a phone.

### Phase 1M2 — the server network — **done**  
*(the second increment that landed under the 1M label; 1M above is the AI gateway)*

Several phones, each a complete Grom, that can find each other, see what the
others serve and call one of those services — while every phone stays
independent. There is no second server: the mesh adds discovery (D99/D103), a
roster (D100) and a relay (D100), and every call is the existing signed HTTP
API.

- **Discovery is a hint, never trust.** A signed LAN beacon (Ed25519 over node
  id, port, timestamp and nonce, ±120 s) proves only that the sender holds the
  key it names; a BLE advertisement is eight unsigned bytes and carries no
  request; a Wi-Fi Direct DNS-SD record is chosen by the group owner; a bonded
  Classic link proves only that Android paired once. Pairing remains the only
  trust path, and the book of sightings is bounded (32) and expires (120 s).
- **Carriers by capability.** LAN/VPN over TCP, Wi-Fi Direct group-owner IP,
  Bluetooth Classic as secure RFCOMM carrying the same HTTP/1.1
  (`bt://<MAC>`), and BLE for discovery only. No new request format.
- **A scoped relay.** `GET /v1/mesh` needs `mesh.read` and carries no endpoint;
  `POST /v1/mesh/call` needs `mesh.call`, a paired target and an operation the
  target published. The peer applies its own scopes and its operator's
  per-peer service allow-list (D101, empty = the old behaviour).
- **No polling.** The roster refreshes on open, on Refresh or after an action;
  the only periodic work is the opt-in beacon.

Gate: `tools/run-gates` **13/13**, policy **73/73** (#66–#73), `MeshBeaconTest`
**4**, `BleAdvertisementTest` **4**, `DiscoveryBookTest` **5**,
`MeshSettingsTest` **6**, `MeshCatalogTest` **5**, `MeshCallerTest` **12**,
`MeshDocumentsTest` **3**, `HttpWireTest` **7**, `BluetoothJsonTransportTest`
**4**, `LanBeaconNodeTest` **3**, `MeshRouterTest` **5**, `MeshServiceTest` **4**,
`PeerSharesTest` **6**, `MeshIntegrationTest` **6** (two and three real servers
over real loopback sockets), `MeshModelTest` **7**, plus the updated
menu/contract tests; lint 0/0.

Not closed: no physical phone has run the radios; Wi-Fi Direct groups with more
than one Grom server need a real service record; BLE never carries data; and the
mesh adds no background watching.

### Phase 1N — an SSH/VPS you can diagnose, and more than one — **done**

Reported from use: *"I cannot connect to my VPS over the public address and
password."* The engine had an answer and two of them were wrong.

- **The code came from the exception class** (D105). MINA's `SshException` *is*
  an `IOException`, so a rejected password was reported as `SSH_UNAVAILABLE` —
  "connection refused / DNS / network". The attempt now records the stage it is
  in (`resolve`, `connect`, `handshake`, `auth`, `forward`) and the code follows
  from it; a refused TCP connect, which MINA surfaces from the connect future,
  is separated from a handshake failure by the cause chain.
- **A wrong password on first use was reported as a changed host key.** The
  catch block treated "no pin, but a fingerprint was confirmed" as a mismatch.
  Only the key verifier may claim `HOST_KEY_MISMATCH` now.
- **OpenSSH's PAM answers a password login as one non-echoing
  `keyboard-interactive` prompt**, which the client never answered, so a correct
  password failed as "exhausted methods". `SshPasswordInteraction` answers
  exactly that prompt and declines everything else.
- **A pasted password** with a line break is refused (`BAD_PASSWORD`) instead of
  stored as a secret that can never authenticate.
- **`sshVerify`** runs resolve/connect/handshake/host-key/auth without the data
  plane and requests the forward only when one is running, reporting
  `forwardChecked`. The screen says "accepted, but the tunnel was not tested"
  rather than "connected" (D106).
- **Many servers, one current.** The current server is one id in the file,
  shown first and marked, carried by `/v1/status` as `current: true`, and cleared
  when deleted. Live tunnels go from two to four, because that was the cap that
  forced an operator with three hosts to forget one (D107).

- **The test gate could pass on a filtered run** (D108). `tools/test-agent` runs
  `:engine:testDebugUnitTest --tests 'app.grom.server.agent.*'`, and Gradle keeps
  one results directory per task and clears it at the start of every run — so
  the filtered run erased the full suite's evidence. 92 test classes became five
  result files, 43 of 838 tests had run, and `run-gates` still printed "JVM
  tests: OK". The gate now clears the results before running, `tools/
  check-test-reports` compares the test classes on disk with the XML Gradle
  actually wrote (and fails on a subset), and the filtered run writes to its own
  directory.

Gate: `tools/run-gates` **13/13**, policy **78/78** (#74–#78), `SshTunnelManagerTest`
**14** (wrong password, closed port, unresolvable name, keyboard-interactive-only
server, verify with and without the data plane), `SshTargetStoreTest` **16**
(pasted line break, the current id), plus the SDK contract at API 12; lint 0/0.

Not closed: no physical phone has dialled a real VPS; the `SSH_UNAVAILABLE` path
for a build without Ed25519 is still untestable on a normal JVM; and four live
tunnels are still four threads the phone pays for.

### Phase 1O — a Bluetooth switch that belongs to Grom (D109) — **done**

The mesh had Bluetooth but no way to say "not over Bluetooth": discovery started
the LE advertiser, the LE scanner and the RFCOMM listener unconditionally, and an
operator who wanted the LAN beacon alone had to turn discovery off as well.

- **`MeshSettings.bluetooth`** (default `true`) decides whether *Grom* uses the
  two Bluetooth carriers. Off leaves the LAN and Wi-Fi Direct untouched, reports
  the Bluetooth rows as *"off — Bluetooth is not used for the server network"*
  rather than as failures, and drops the Bluetooth sightings already in the
  discovery book so a switched-off carrier cannot keep offering a pairing.
- **The system radio stays the operator's.** Nothing in `mesh/` calls `enable()`,
  `disable()`, `ACTION_REQUEST_ENABLE` or `createBond()`; when the radio is off
  the screen measures that (`bluetoothOn`, `platformOn`) and offers Android's own
  Bluetooth settings page. Android 13+ would refuse the alternative anyway.
- **The BLE advertisement fits now.** v0.19.0 added the 128-bit UUID list *and*
  the 128-bit service data: 47 bytes of the legacy 31, refused with
  `ADVERTISE_FAILED_DATA_TOO_LARGE` while the row still said "ready". The data
  field alone is 29 bytes and already names the UUID; the scan runs two filters
  so a v0.19.0 phone is still seen, and `BleAdvertisement.legacyAdvertisementBytes()`
  pins the arithmetic.
- **A refusal is visible.** A recorded radio failure wins over `available`, keeps
  the platform code (`BLE_ADVERTISE_FAILED:1`), and the pure model turns known
  codes into sentences while printing an unknown one verbatim.

No AIDL method was added: the switch travels through the existing
`meshConfigureJson`, so the IPC contract stays at API 12.

Gate: `tools/run-gates` **13/13**, policy **80/80** (#79–#80),
`MeshSettingsTest` **8**, `MeshServiceTest` **8**, `DiscoveryBookTest` **6**,
`BleAdvertisementTest` **5**, `MeshModelTest` **10**.

Not closed: no phone has advertised or scanned under the new budget; the BLE
adapter itself is Android-only and not JVM-tested (policy #66 keeps it thin), and
a mixed-version mesh still relies on the service-data filter for the older side.

### Phase 1P — the tunnel carries only what is bound, and says what the server accepts — **done**

Two defects from use, both of the "looks connected" kind:

- **A green tunnel that carried nothing** (D111). The forwarding filter accepted a
  `forwarded-tcpip` channel only when its destination was loopback on the
  data-plane port. That was written when the data plane could only be loopback;
  `LAN` binds the phone's own address, and MINA asks the filter with exactly the
  endpoint we registered — so every connection was refused while the state said
  `CONNECTED`. The filter now allows the single endpoint from our own request, and
  nothing else.
- **"Wrong password" and "this server does not take passwords" were the same
  sentence** (D110). The server names the methods it accepts in
  `SSH_MSG_USERAUTH_FAILURE`; the driver kept that list in a `protected` field and
  only logged it. `SshAuthMethods` now records the offered list per connection
  (filtered, at most six, empty = not measured), and a requested method that is
  not offered returns `SSH_AUTH_METHOD_NOT_ALLOWED`. With
  `PermitRootLogin prohibit-password` — the default on a Hetzner or Debian cloud
  image — that is the difference between a password problem and a policy.

Gate: `tools/run-gates` **13/13**, policy **82/82** (#81, #82),
`SshForwardingPolicyTest` **6**, `SshAuthMethodsTest` **9**,
`SshTunnelManagerTest` (a LAN-bound data plane carrying real bytes).

Not closed: a phone that changes network while the tunnel is up keeps a forward
registered to the old address and must reconnect; and the list cannot separate a
wrong password from a PAM policy that refuses the user — with
`keyboard-interactive` offered, the answer stays `SSH_AUTH_FAILED` (D110).

### Phase 1Q — the embedding guide is executed, and builds in someone else's root — **done**

The "2 in 1" guide had never been run by anything. A host project that followed it
verbatim failed twice:

- **The agent script was resolved from `rootProject`** — correct only while Grom
  *is* the root project. A host that vendored `engine/`, `sdk/` and `agent/` under
  `third_party/grom/` got *"File '<host>/agent/grom-agent.js' specified for
  property 'sourceFile' does not exist"* while the file sat beside the engine
  module. The path is now `layout.projectDirectory.file("../agent/grom-agent.js")`,
  which is the same value inside this repository and correct everywhere else
  (D112).
- **The duplicate `META-INF` files were left to the consumer.** `sshd-core` and
  `sshd-common` both ship `DEPENDENCIES`, `LICENSE` and `NOTICE`, and they are
  merged by the module that *packages*. A `packaging` block in `:engine` is
  ignored for the host — verified against AGP 8.11 by adding it and watching the
  same failure — so the pickFirsts live in the host build file, documented with
  the exact error they prevent, and pinned by policy #85 against a library-side
  "fix" that would silently do nothing.
- **Nothing had a caller.** `GromEmbed` was used by its own tests and by no
  module, the reference app talks to the service directly, and the C-008 test
  apps depend only on `:sdk` — so a wrong guide was invisible. `examples/embedded`
  is now a compiled module that calls the documented API, and its tests pin the
  sentences and the consent rule.
- **`tools/check-embed` is the gate**: an isolated root with no `agent/` at its
  top level, the three directories vendored under `third_party/grom/`, and a host
  APK built from the documented build file. It asserts the APK, the packaged agent
  asset, the private service and the absence of an IPC permission.

The guide also gained the measured numbers: the engine and its dependencies add
**~5.6 MB** to a host APK without the runtime (not the "~2–3 MB" it claimed), and
the merged manifest is listed in full — the mesh brings Bluetooth and Wi-Fi Direct
entries that the old text said did not exist.

Gate: `tools/run-gates` **14/14** (new `check-embed`), policy **85/85** (#83–#85),
`examples/embedded` **18** tests, lint 0/0.

Not closed: the example is a library, so no host *application* runs on a device in
this repository; a real host that already has its own `packaging` block must merge
the patterns rather than replace them; and the engine is still one module, so a
host cannot opt out of the mesh permissions it does not use.
