# Grom — decision log

Status legend: **ACCEPTED** (normative), **REJECTED** (recorded so it is not
re-proposed), **DEFERRED** (out of F1, tracked for later).

Provenance: see `docs/RECOVERY.md`. This log is a re-derivation of the D1–D31
record named in the handoff, extended with D32–D48 as the agent runtime, the
operator surface, updates, release packaging and the public data plane were
built out. The numbering is kept so cross-references survive.

---

## D1 — Grom is an agent, never an authority for money
**ACCEPTED.** Grom may observe, relay, and execute whitelisted local operations.
It never holds balances, never signs payments, never decides that a payment is
valid. Any amount, account, or settlement concept in server code is a defect.

*Enforced by:* `tools/test-policy` (forbidden-symbol scan), review.

## D2 — One artifact, two roles
**ACCEPTED.** A single application ships two Gradle flavours:

| flavour | minSdk | role |
|---|---|---|
| `server29` | 29 | full server: binds loopback, supervises the agent |
| `peripheral26` | 26 | client/peripheral: uses the SDK, never binds a socket |

`BuildConfig.GROMSERVER` gates every server-only code path. A peripheral build
must never open a listening socket.

*Enforced by:* `tools/test-policy`, flavour matrix build in CI.

## D3 — Kotlin, zero third-party runtime dependencies
**ACCEPTED.** F1 uses only the Android framework, Kotlin stdlib and `org.json`
(part of the framework). No HTTP server library, no JSON library, no DI
framework. The APK stays small and the dependency surface stays auditable.

*Enforced by:* `tools/check-third-party`.

## D4 — Test-only keystores are committed
**ACCEPTED.** `keys/grom-test-same.jks` and `keys/grom-test-foreign.jks` are
committed so that the signature gate (C-008) is reproducible on any machine.

These keys are **public by design**. They must never sign a distributed build;
a release build must supply its own key material outside the repository.

*Enforced by:* README warning, `tools/verify-ipc-signing` refuses to run against
a release keystore path.

## D5 — Loopback bind only, fail closed
**ACCEPTED.** The listener may bind `127.0.0.1` or `::1` and nothing else.
`0.0.0.0`, `::`, any LAN address, and any hostname that resolves off-loopback
are rejected before the socket is created. Rejection is fatal to server start,
not a warning.

*Enforced by:* `BindPolicy`, `BindPolicyTest`, `tools/test-policy` (manifest has
no `INTERNET`-bound export surface).

## D6 — Bind `0.0.0.0` so desktop clients can connect directly
**REJECTED.** It turns every phone running Grom into a LAN service with one
bearer token as the only control. Conflicts with D5 and with the threat model in
S1. Remote access is a separate future design (pairing, per-device keys), not a
bind-address flag.

## D7 — HTTP/1.1 on a raw socket
**ACCEPTED.** The loopback API is HTTP/1.1 served by a small parser over
`ServerSocket`. Requests are bounded: max 8 concurrent connections, max 64 KiB
body, 5 s idle timeout. Anything larger is rejected with `413`/`431`.

## D8 — Bearer token, 256-bit, constant-time compare
**ACCEPTED.** The token is 32 random bytes, base64url without padding. It is
stored in app-private storage and compared in constant time. Tokens are never
logged; the audit log stores a short fingerprint (first 8 hex chars of
SHA-256) when it must refer to one.

## D9 — Health is unauthenticated; everything else is authenticated
**ACCEPTED.** `GET /grom/v1/health` answers on loopback without a token so a
supervisor or a local launcher can probe liveness. It exposes no secrets.
Every other endpoint requires `Authorization: Bearer <token>`.

## D10 — Append-only hash-chained audit log
**ACCEPTED.** Every authenticated mutating request appends an entry
`{seq, ts, actor, action, detail, prev, hash}` where
`hash = SHA-256(domain || seq || ts || actor || action || detail || prev)` and
`prev` is the previous entry's hash (`0…0` for genesis). Verification replays the
chain. Truncation or edit is detectable.

## D11 — Secrets reach the agent over stdin, never argv or env
**ACCEPTED.** Anything sensitive (token, future signing material) is written to
the child's stdin as a single JSON line and then the pipe is closed. `ps` on
Android does not show it; `/proc/<pid>/environ` does not contain it.

*Enforced by:* `AgentConfigTest`, `tools/test-agent`.

## D12 — Supervised agent with a health handshake
**ACCEPTED.** The agent process must emit `GROM_READY <pid>` on stdout within
`startTimeoutMs` (default 10 000). Until then the agent is `STARTING`. A process
that dies before the handshake counts as a crash.

## D13 — Quarantine after three crashes, cleared only by a human
**ACCEPTED.** Three crashes inside a rolling 10-minute window move the agent to
`QUARANTINED`. The supervisor stops restarting it. Quarantine clears only on an
explicit user action (app UI or `POST /grom/v1/agent/reset` with a token), never
on a timer.

## D14 — Boot restart is opt-in and persistent
**ACCEPTED.** `RECEIVE_BOOT_COMPLETED` is honoured only when the user enabled
"start on boot". The flag lives in app preferences. Quarantine survives reboot:
a quarantined agent does not restart at boot.

## D15 — IPC is signature-gated twice
**ACCEPTED.** Layer 1: the service is protected by
`app.grom.sdk.ipc.BIND_GROMSERVER` with `protectionLevel="signature"`.
Layer 2: `onBind` compares the caller UID's signatures with Grom's own via
`PackageManager.checkSignatures` and returns `null` on mismatch. A foreign
signature gets no Binder at all.

## D16 — No outbound network, no telemetry
**ACCEPTED.** Grom does not initiate connections to the internet. No analytics,
no crash reporting, no update check. Permissions are limited to what the local
service needs.

## D17 — Every third-party component is documented and gated
**ACCEPTED.** `third_party/THIRD_PARTY.md` lists each component, version,
licence and why it is present. `tools/check-third-party` fails when the document
and the build disagree in either direction.

## D18 — Apache-2.0, SPDX headers
**ACCEPTED.** Repository licence is Apache-2.0. Each source file carries
`SPDX-License-Identifier: Apache-2.0`.

## D19 — Per-request capability tokens (scoped grants)
**DEFERRED.** F1 has one bearer token with one scope. Scoped grants, expiry and
rotation-with-overlap belong to F2; adding them now would widen the attack
surface without a consumer.

## D20 — Keystore-wrapped token storage
**DEFERRED.** F1 stores the token in app-private storage (`0600` semantics by
directory permissions). Wrapping it with the Android Keystore is worthwhile but
buys little while the same UID can read the process memory anyway. Revisit with
the F2 threat model.

## D21 — Foreground service type `specialUse`, server role only
**ACCEPTED.** The server role runs as a foreground service with
`foregroundServiceType="specialUse"` and the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
property. The peripheral role does not start it.

## D22 — Token rotation is explicit
**ACCEPTED.** The token persists across restarts so clients are not broken on
every launch. `POST /grom/v1/token/rotate` issues a new token and invalidates the
old one atomically. Rotation is itself audited.

## D23 — Fixed port, user-overridable
**ACCEPTED.** Default port `47001` on `127.0.0.1`. The user may change it in
settings; the bind policy is unchanged by a port change.

## D24 — Agent state lives in app-private storage
**ACCEPTED, revised by D34.** Expected layout:

```
<files>/node/libnode.so        the runtime (exec-only)   <- moved by D34
<files>/node/grom-agent.js     the agent entry point
```

When either file is missing the server starts in `MISSING_RUNTIME`, serves
health with `"runtime":"missing"`, and refuses agent endpoints with `503`.
That is a truthful state, not an error to hide.

**Revision (D34):** the *executable* cannot live here — Android denies
`execve()` of app-private files to apps targeting API 29+, and Grom targets 36.
The binary is now launched from `ApplicationInfo.nativeLibraryDir`; only the
script stays under `<files>/node`. The rest of D24 stands.

## D25 — Readiness is a stdout line, not a port probe
**ACCEPTED.** The agent writes `GROM_READY <pid>` when it is able to serve.
Probing a port would race with the agent's own bind and cannot distinguish
"listening" from "ready".

## D26 — Logs redact secrets
**ACCEPTED.** The token and any `Authorization` header value are replaced by
`«redacted»` before logging or auditing. A unit test asserts this.

## D27 — Audit log bounds
**ACCEPTED.** The audit file rotates at 4 MiB into `audit.log.1`, keeping one
generation. Rotation records the last hash of the previous segment so the chain
stays verifiable across files.

## D28 — Genesis and chain identity
**ACCEPTED.** `prev` of the first entry is 64 zeros. The hash domain string is
`grom.audit.v1`. Changing either is a breaking format change.

## D29 — API version is an integer, independent of app version
**ACCEPTED.** `GET /grom/v1/health` returns `api: 1`. `IGromServer.apiVersion()`
returns `1`. App version is `versionName`; they move independently.

*Amended (0.13.1):* the Binder value has **one source** —
`GromClient.API_VERSION` — and `ServerRuntime.apiVersion()` returns that
constant instead of a literal. The literal drifted twice: it answered `3` while
the contract said 5, then `7` while it said 8 (which is what `doctorJson()`
shipped as). A client gating on `>= N` cannot distinguish "the server is old"
from "the server forgot to bump", so the number must not live in two files.
`tools/test-policy` 48 pins the rule, and S4 §13 now names the API 8 value the
contract had omitted.

*Enforced by:* `tools/test-policy` 48, `GromClientContractTest`.

## D30 — Policy logic is JVM-testable, no Robolectric
**ACCEPTED.** Bind policy, token, audit chain, supervisor state machine, health
gate, redaction and agent config are plain Kotlin with no Android imports.
Android classes are confined to the service, the activity and the process
launcher, which are thin.

*Enforced by:* `app/src/test` compiles and runs on the JVM with no emulator.

## D31 — F1 ships only when the three blockers are closed
**ACCEPTED.** The walking skeleton is useful but not "working". F1 is complete
only when (a) `libnode.so` exists for arm64, (b) Grom has run on a real device
through a boot cycle and a crash cycle, and (c) Q8 (`node:sqlite` on aarch64) is
answered. Until then the README and the UI both say `MISSING_RUNTIME`.

## D32 — The agent announces a private control port on stdout
**ACCEPTED.** `GROM_READY <pid>` (S3-R011) is unchanged. Immediately after it,
the agent prints a second, additive line:

```
GROM_AGENT_READY <pid> <port>
```

The port is an ephemeral `127.0.0.1` listener owned by the agent; the `--port`
argument still belongs to the Kotlin HTTP facade and is never bound by the
agent. The `<pid>` must equal the child the supervisor spawned, exactly like
S3-R013, or the line is treated as a crash. An unknown line would otherwise be
discarded by `HealthGate`, so the extension cannot break older supervisors.

*Why not a second fixed port:* a fixed port collides with other apps and cannot
be inferred from the facade port. *Why not a unix socket:* it needs a path,
which would have to appear in argv or in the runtime directory, and the
ephemeral port needs neither.

*Enforced by:* `AgentChannelTest`, `tools/test-agent-runtime`.

## D33 — `exec` is proxied, never synthesized
**ACCEPTED.** `POST /grom/v1/agent/exec` forwards the request to the agent and
returns the agent's status and body unchanged. If the agent cannot be reached
the answer is `502 AGENT_UNAVAILABLE`; if it has not announced a control port
the answer is `503 AGENT_NOT_READY`. The server never returns `{"ok":true}` for
an op it did not execute, and a `503 STORAGE_UNAVAILABLE` from the agent reaches
the caller as `503`.

*Enforced by:* `RouterTest` (status passthrough), `AgentClientTest`,
`ServerRuntime.exec`.

## D34 — The executable lives in the APK native library dir, not in files/
**ACCEPTED.** The agent is launched as:

```
<ApplicationInfo.nativeLibraryDir>/libnode.so     executable, packaged in jniLibs
<files>/grom/node/grom-agent.js                   data, read by Node
```

with `jniLibs.useLegacyPackaging = true` in `build.gradle.kts` (AGP's
supported way to get `extractNativeLibs="true"`) and `LD_LIBRARY_PATH`
set to the same directory when the runtime is dynamically linked.

*Why:* Android denies `execve()` of files in app-private storage for apps
targeting API 29+; Grom targets 36. D24's `<files>/node/libnode.so` could
therefore never be executed on a supported device, however valid the ELF was.
JavaScript is data, so it stays in app storage. `AssetRuntime`/`RuntimeInstaller`
remain for the operator path that ships a binary under `assets/`, but the native
library dir wins whenever it holds one.

*Consequence for packaging:* dependencies must be co-located and named
`lib*.so` — Termux SONAMEs such as `libssl.so.3` are renamed and their
`DT_NEEDED`/`DT_SONAME` strings rewritten in place, which is why the runtime is
assembled by `scripts/fetch-node-android.sh` rather than copied by hand.

*Enforced by:* `AgentLauncherTest` (paths, `LD_LIBRARY_PATH`), lint,
`scripts/fetch-node-android.sh --verify`.

## D35 — The runtime is pinned, vendored and verified by execution
**ACCEPTED.** `scripts/fetch-node-android.sh` assembles the arm64 runtime from
pinned bionic packages (Node v24.22.3 LTS and its libraries), checks a SHA-256
for every archive, and treats the build as successful only after the binary
**runs** — version, a `node:sqlite` round trip (Q8), and the agent suite.

*Why not cross-compile here:* this host is aarch64 with an x86_64-only NDK and
no Docker; a full V8 build would also need RAM this environment does not have.
Not compiling is not the same as not knowing: the vendored binary is the same
bionic/arm64 target, and executing it through `/system/bin/linker64` answers the
only question that mattered — does Node, including `node:sqlite`, run where the
phone will run it.

*Why not commit it:* ~91 MB of binaries in an Apache-2.0 repository defeats
auditability, and a runtime must stay an explicit operator action (S1-R003).

*Enforced by:* `scripts/fetch-node-android.sh --verify`, `docs/RUNTIME.md`.

## D36 — The operator surface is monochrome, with an explicit light/dark choice
**ACCEPTED.** The app has two palettes: white ground / black ink and black
ground / white ink. There is no brand accent; colour is reserved for state
(green positive, red negative) so "running" and "runtime missing" are readable
without reading. Cards are near-flat surfaces with hairline borders, labels are
tracked uppercase, and the status is the largest type on the screen — the
zen.com-style minimalism the operator asked for, applied to a server console.

The mode is `Auto` (follow the system), `Light`, or `Dark`, stored with the other
non-secret preferences; the app never flips it on its own. The window, status
bar and navigation bar are painted from the same palette, and the app icon is
the same mark in both modes.

*Enforced by:* `GromTheme`, `:app:lint`, and the `values/` + `values-night/`
resource pair.

## D37 — The update check is operator-initiated and reads only the release feed
**ACCEPTED.** "Check now" performs exactly one HTTPS GET to
`https://api.github.com/repos/sveatoken/grom/releases/latest`. No account, no
identifier, no token, no background job, no analytics. The response is parsed by
Grom's own JSON reader (D3), the version is compared numerically against
`BuildConfig.VERSION_NAME` (flavour suffix stripped), and the result is one of:
newer release, current, or failure. A failure is shown as a failure.

A newer release is opened in the browser — either the flavour-matching APK asset
or the release page. Grom never downloads or installs an APK itself. This
amends S1-R003 and adds S1-R033; it is why `tools/test-policy` permits exactly
one absolute URL, in `UpdateChecker.kt`, and fails if any other source grows one.

*Enforced by:* `JsonParserTest`, `UpdateCheckerTest`, `tools/test-policy` (17).

## D38 — Release APKs bundle the runtime and are verified before publishing
**ACCEPTED.** `.github/workflows/release.yml` runs on a `v*` tag: it assembles
the pinned arm64 runtime, installs it into `jniLibs`, builds both role flavours,
and verifies each APK contains `lib/arm64-v8a/libnode.so` and
`assets/node/grom-agent.js` and passes `apksigner verify` before attaching it to
the GitHub release with a `SHA256SUMS` file. The release is the same document
the in-app update check reads, so tagging is the whole update mechanism.

Release builds use the committed test key (D4) until a production key exists;
the release notes and README say so. A public release signed with a test key is
installable and updatable, but it does not establish publisher identity.

*Enforced by:* `.github/workflows/release.yml`, `scripts/build-node-android.sh
--verify`.

## D39 — The public data plane is separate from the loopback control plane
**ACCEPTED.** The server keeps two listeners with two independent policies. The
control plane (`/grom/v1/*`) is unchanged: loopback only, bearer-token
authenticated, and fatal to start when it cannot bind (D5). The data plane
(`/v1/*`) is the super server: separately enabled, separately bound, separately
authenticated, default port `47002`, and its refusal or failure is reported in
status without stopping the control plane or the agent. The two planes share the
process, the audit chain and the supervised agent, and nothing else. Data-plane
code lives under `superserver/`; the control-plane HTTP code does not import it,
and the only wiring is in `ServerRuntime` and the app's access card.

*Why not extend the existing listener:* one listener means one bind policy and
one credential, so enabling remote access would silently widen the control plane
and its token. Two planes keep the default safe and make the risky mode a
separate, explicit decision.

*Enforced by:* `SuperServerIntegrationTest`, `tools/test-policy` (6, 22),
`ServerRuntime`.

## D40 — Super-server configuration is atomic, validated, and secret-free
**ACCEPTED.** `<files>/grom/super-server.json` is written to a temporary file,
hardened to owner-only permissions, then renamed over the previous file, so a
crash never leaves half a policy. Loading is bounded at 64 KiB; an oversized,
unreadable or non-object file falls back to the safe defaults with a reported
`lastError`, never to a listener. The file holds a PBKDF2 hash and Ed25519
public keys, so it contains no usable credential — except the backend key, which
is sealed (D44). `SuperServerConfig.validate(controlPort)` runs before a socket
exists and refuses with a stable code; the server never "starts anyway" on a
configuration it could not honour.

*Enforced by:* `SuperServerConfigStore`, `SuperServerConfig`,
`SuperServerHost.startLocked`, `tools/test-policy` (18, 21).

## D41 — A non-loopback bind requires a credential; PUBLIC requires an acknowledgement
**ACCEPTED.** The data plane has three bind modes. `LOOPBACK` is `127.0.0.1` and
the default. `LAN` resolves the current site-local IPv4 address, preferring a
private address and never selecting loopback, wildcard or link-local; no usable
address is `NO_LAN_ADDRESS`, not a fallback. `PUBLIC` is `0.0.0.0` and is
refused unless `acknowledgePublicBind` is true. Any non-loopback mode is refused
without a password hash or an enabled key (`CREDENTIAL_REQUIRED`). The hostname
is never resolved and a bind failure is reported, never retried on another
address.

*Why the acknowledgement:* the wildcard means every interface, including any the
operator did not think about. A boolean in the file that the app sets only when
the operator cycles to *all interfaces* makes the decision deliberate and
auditable.

*Enforced by:* `SuperBindPolicy`, `SuperServerConfig.validate`,
`SuperServerHost`, `tools/test-policy` (6, 18).

## D42 — Password sessions and Ed25519 request signatures are the two credentials
**ACCEPTED.** Both mechanisms are supported and the mode selects which are live:
`PASSWORD`, `KEY`, or `BOTH` (the default). A password is verified against
`pbkdf2-sha256$iterations$salt$hash` (PBKDF2-HMAC-SHA256, 16-byte salt, 150000
iterations, 32-byte key) in constant time, then exchanged for a session token of
32 random bytes, base64url, stored only as its SHA-256 digest, 12 h TTL and at
most 16 live sessions. A signed request instead carries `X-Grom-Key`,
`X-Grom-Timestamp`, `X-Grom-Nonce` and `X-Grom-Signature` over the exact
six-line `grom-ed25519-v1` canonical string; timestamps are accepted within
±300 s and a nonce is remembered only after the signature verifies, so replays
are rejected without letting an unauthenticated caller fill the cache. Failures
are uniform: the wire answer is one `401 UNAUTHORIZED`.

*Why two:* a human-run client can type a password once and keep a short-lived
session; a machine client can avoid putting any reusable secret on the wire,
which matters when the traffic crosses a proxy the operator does not control.
Neither mechanism is enough on its own for both consumers.

*Enforced by:* `Ed25519Test`, `PasswordHashTest`, `RequestSignerTest`,
`SessionStoreTest`, `RateLimiterTest`, `tools/test-policy` (19, 20).

## D43 — Lightning is an adapter, never a custody layer
**ACCEPTED.** Grom holds no wallet keys, signs no payments, computes no balance
and matches no orders. The `lightning` plugin forwards an operator-configured
backend's answer, in integer millisatoshis, and every failure is explicit: an
unimplemented capability is `501 NOT_SUPPORTED` naming it, an unconfigured
backend is `503 LIGHTNING_UNCONFIGURED`, an unreachable one is `503
LIGHTNING_UNAVAILABLE`. A balance is either measured or absent; there is no
default, no cached zero and no optimistic pending. This narrows S1-R002 for the
data plane without repealing it: money vocabulary is confined to the adapter,
and `tools/test-policy` fails if it appears elsewhere in server code.

*Why an adapter:* custody is a different product with different liabilities.
An operator who already runs a wallet should be able to reuse it; an operator
who does not should not accidentally acquire one.

*Enforced by:* `LightningBackend`, `LightningPluginTest`, `LnbitsBackendTest`,
`tools/test-policy` (10).

## D44 — The backend credential is sealed by the Android Keystore
**ACCEPTED.** The LNbits API key is the only secret Grom stores that is valuable
to an attacker off the device: it grants spend on a service Grom does not own.
It is encrypted with AES-256-GCM
under a non-exportable Android Keystore key (alias `grom-super-secrets`) and
stored only as base64 of `version || ivLength || iv || ciphertext+tag`. The
plaintext exists in memory while a backend object lives and in the `X-Api-Key`
header; it never appears in the file, the status document, the audit log, a URL
or an exception message. A keystore key that was rotated or deleted makes the
sealed value unreadable, which is reported as an unconfigured backend, not a
crash and not a plaintext fallback.

*Why not the control token (D20):* the control token is checked by the same
process that could read it anyway, so wrapping it buys little. A remote
backend credential is different — it is useful to an attacker off the device.

*Enforced by:* `SecretBox`, `AndroidKeystoreSecretBox`,
`SuperServerHost.buildBackend`, `tools/test-policy` (21).

## D45 — The agent is exposed as a plugin, not as a second raw port
**ACCEPTED.** The supervised agent stays loopback-only and token-authenticated.
The data plane reaches it through the `agent` plugin, which relays a whitelisted
op to the control-plane `exec` path and returns the agent's own status and body
unchanged. An unknown op is `400 UNKNOWN_OP`; an agent failure passes its status
and code through; an unreachable agent is `502 AGENT_UNAVAILABLE`; a non-JSON
reply is `502 AGENT_PROTOCOL_ERROR`. The plugin never synthesises `ok` and never
widens the whitelist, which remains `ping`, `echo`, `status`, `kv.get`, `kv.put`.

*Why:* a second raw port would need its own authentication, its own bounds and
its own audit, and would expose the agent's protocol instead of a stable API.

*Enforced by:* `AgentPlugin`, `ServerRuntime.exec`, `SuperServerIntegrationTest`.

## D46 — Server events use SSE, not WebSocket
**ACCEPTED.** `GET /v1/events` is a server-sent-events stream over the existing
HTTP/1.1 parser and worker pool. It opens with `retry: 5000` and a `hello`
event, emits `: ping` when no event was written for 15 s, ends with `bye`, and
lives at most 30 minutes.
Each subscriber owns a 32-frame bounded queue: a slow reader loses events, and
that loss is counted, rather than blocking the publisher or growing the heap.

*Why not WebSocket:* the traffic is one-way, and SSE needs no upgrade handshake,
no framing library and no second authentication path. It also passes unchanged
through the TLS terminators and tunnels an operator is told to use (D47).

*Enforced by:* `EventBus`, `SuperRouter.eventStream`,
`SuperServerIntegrationTest`.

## D47 — TLS is terminated outside the process
**ACCEPTED.** Grom contains no TLS server, no certificate storage and no
certificate rotation. The data plane speaks plain HTTP on its bound address; an
operator exposing it off loopback must put a TLS terminator (Caddy, nginx) or an
encrypted tunnel (WireGuard, Tailscale, SSH) in front. The app warns when
`PUBLIC` is selected, and `docs/SUPER-SERVER.md` states what is visible on the
wire without one: password logins in clear text, request and response bodies in
clear text, and Ed25519 signatures that reveal no key and cannot be replayed.

*Why:* certificate lifecycle, renewal and hostname validation are a product of
their own, and doing them badly is worse than delegating them. It also keeps D3
true — no TLS library is added to the APK.

*Enforced by:* `SuperServerConfig` (no TLS fields), the access-card warning in
`GromActivity`, `docs/SUPER-SERVER.md`, `tools/test-policy` (16).

## D48 — Outbound connections only to an operator-configured plugin backend
**ACCEPTED.** Grom initiates no connection of its own except the update check
(D37). A Lightning backend is the second exception, and it is narrow: the URL is
supplied by the operator at runtime, never compiled in; it must start with
`https://` (`INSECURE_BACKEND_URL`); redirects are not followed; the transport
refuses every scheme except `http` and `https`, and the configuration accepts
only `https`; responses are capped at 64 KiB; timeouts are bounded;
and the API key travels only in the `X-Api-Key` header. `tools/test-policy`
permits exactly one absolute URL in server code — the update feed — and fails on
any other. This narrows S1-R003 and S1-R028 for the data plane.

*Why:* the alternative is either no Lightning integration or a compiled-in
endpoint, and a compiled endpoint cannot be audited by the operator who is
supposed to control where their money traffic goes.

*Enforced by:* `HttpJsonClient`, `LnbitsBackend`,
`SuperServerConfig.validate`, `tools/test-policy` (17).

## D49 — The download page is static, and release assets have stable names
**ACCEPTED.** Distribution gets one human address:
`https://sveatoken.github.io/grom/`. It is plain HTML, CSS and one small script
deployed by GitHub Pages from `web/`; there is no build step, no framework, no
third-party script, no cookie and no analytics. The page has exactly one
optional network call — the public releases API — used to show the current
version and to point the primary button at the exact asset. With JavaScript
off, rate-limited or offline, the button still works because it is an ordinary
link to `releases/latest/download/grom-server29.apk`.

For that link to survive version bumps, release assets are published under
**version-free names**, `grom-server29.apk` and `grom-peripheral26.apk`; the
version lives in the release tag, the release title and the app's own build.
The in-app update checker selects assets by the role substring, so the stable
names are compatible with it (D37).

*Why:* the previous names embedded the version (`grom-0.3.0-server29.apk`),
which makes any permanent download URL impossible. A stable name is the only
way to give a non-technical person one link that keeps working.

*Enforced by:* `.github/workflows/release.yml` (asset naming),
`.github/workflows/pages.yml` (deployment), `tools/check-site` (static,
dependency-free, stable links), `docs/DOWNLOAD.md`.

## D50 — Referral identity is local-only and never leaves the device
**ACCEPTED.** An invite code is a random local identifier, not an account. It
is drawn from a Crockford base32 alphabet (no `I`, `L`, `O` or `U`), generated
once with `SecureRandom`, stored in app-private preferences, and never
transmitted. Entering a friend's code stores it locally and is reported as
exactly that: Grom has no registry, so it cannot confirm that a code exists, and
the dialog says so. The copy/share counter counts actions started on this
device, not people reached.

Where the record is visible: the loopback control plane (`/grom/v1/status`,
`IGromServer.statusJson`) and the **authenticated** `GET /v1/status` of the
super server, under `referral`. The unauthenticated `GET /v1/health` never
carries it — that endpoint is reachable by anyone who can reach the bound
address, and D39 keeps device facts on the control plane.

The share link itself is a `res/values/strings.xml` template
(`invite_link`), not a compiled-in URL in Kotlin, so the "one absolute URL in
server code" rule of D48 and `tools/test-policy` (17) is unchanged.

*Why:* a referral program that reports installs would give Grom its first
device identifier and its first mandatory outbound call. Both contradict S1,
D37 and D48. A local code still lets people share a link and paste a code
without turning the app into a tracking client.

*Enforced by:* `InviteCode`, `ReferralStore`, `GromActivity` (Invite & Earn),
`SuperServerHost.status`, `SuperRouter` (`/v1/health` has no referral block),
`tools/test-policy` (17) and `tools/check-site`.

## D51 — Rewards are "coming soon", never implied and never simulated
**ACCEPTED.** The app shows a fixed badge, **"Crypto rewards — coming soon"**,
and one sentence stating that nothing is paid today. There is no balance, no
pending amount, no estimate, no leaderboard, no count of "people you invited"
(which Grom cannot know) and no placeholder transaction. The rewards state is
the literal string `coming_soon` in the status payload.

*Why:* the moment a UI renders an amount that no system owes, the app is lying.
This project's rule is that a status is either measured or absent (D31, D33).

*Enforced by:* `ReferralStore.REWARDS_COMING_SOON`, the Invite card in
`GromActivity`, the rewards block on the download page, `tools/check-site`.

## D52 — npub and relays are reference configuration; Grom dials no relay
**ACCEPTED.** `SuperServerConfig` gains `npub`, `relays` and `remoteBridge`.
They exist so an external VPS bridge (for example a Mostro fork) can read the
operator's Nostr public key and relay list from the authenticated
`GET /v1/status` (and from the app's *Copy config* action) while Grom stays the
local control point.

The boundary is absolute: none of these fields causes a network connection,
a signature or a tunnel. `npub` is validated as a NIP-19 bech32 key with a
32-byte payload (or a 64-character hex key normalised to bech32); an `nsec1…`
secret is rejected because Grom never holds a Nostr private key. `relays` is a
list of at most 8 `ws://`/`wss://` URLs; a plaintext `ws://` is accepted only
because it may be a local relay, and the UI says so. `remoteBridge` is a
label/description (80/240 chars) and cannot contain a key, password, API key or
bearer token.

Per Q1/verified operation: the tunnel is the operator's (SSH, WireGuard,
Tailscale) and is initiated from the device. Grom does not start it. Per Q4:
LNbits stays the only Lightning adapter inside Grom; LND/CLN/NWC stay on the
VPS. `peripheral26` is unchanged.

*Why:* the alternative — a relay client and a tunnel supervisor inside the app —
would add an outbound network surface and a second set of credentials to a
server whose whole value is that it does one thing honestly. A reference list
gives the bridge what it needs without any of that.

*Enforced by:* `Nip19`, `RelayUrl`, `RemoteBridgeHint`, `SuperServerConfig.
validate` (`BAD_NPUB`, `BAD_RELAY`, `TOO_MANY_RELAYS`, `BAD_REMOTE_BRIDGE`),
`tools/test-policy` (23).

## D53 — status and "Copy config" are allow-lists, never a serialised config
**ACCEPTED.** The reference block in status is built by `SuperStatusDocument`,
and the hand-off document by `ConfigExport`. Both name every field they emit;
neither serialises `SuperServerConfig`. The export carries `npub`, `relays`,
`remoteBridge`, the data-plane shape and the Lightning backend's location and
measured health. It never carries `passwordHash`, public key material, the
sealed backend key, session tokens or audit records.

`lightningHealth.reachable` is `null` until the adapter has actually been
probed. `status()` does not call the backend, so a poll cannot turn "configured"
into "healthy", and an unreachable backend cannot make the status call slow.

*Why:* a "copy my config" button that serialises the object is one field away
from pasting a password hash into a chat window. An allow-list makes a future
field safe by default.

*Enforced by:* `ConfigExportTest` (sentinel secrets), `SuperStatusDocumentTest`,
`LightningPluginTest`, `tools/test-policy` (24).

## D54 — a reference-only change does not restart the listener
**ACCEPTED.** `SuperServerHost.reload()` compares the loaded config with the
running one via `sameRuntimePolicy`, which nulls only `npub`, `relays` and
`remoteBridge`. When nothing else changed, it updates the in-memory config and
appends `super.reload.reference`; sessions and SSE streams survive. Any other
change rebuilds the whole plane, as before, and invalidates sessions.

The `agent` plugin's `status` reads the reference block through a callback at
call time, so it sees the new values without a registry rebuild.

*Why:* editing a relay URL should not log out a paired client or drop an event
stream. The distinction is safe because those three fields are read by no
listener, no authenticator and no rate limiter.

*Enforced by:* `SuperServerConfigTest.sameRuntimePolicyIgnoresOnlyReferenceFields`,
`AgentPluginTest`, `SuperServerHost.reload`.


## D55 — the server engine is an embeddable library; `app` is a host
**ACCEPTED.** Every server package moved from `app/` to a new `:engine` Android
library (`app.grom.engine`): supervisor, control plane, data plane, audit chain,
secret store, project registry and SSH bridge. `:sdk` (AIDL + `GromClient`) is
its `api` dependency. `app/` keeps only the operator surface, the two role
flavours and the Node runtime binaries.

The engine manifest declares `GromService` with `exported="false"` and no IPC
permission: an embedded server is private to its host. The standalone
`server29` flavour replaces that declaration (`tools:node="replace"`) with the
public, `signature`-gated one. The per-transaction signature check is unchanged
and applies in both cases.

A host uses `GromEmbed`:
`recordConsent` → `start(options)` → `newClient` (Target.EMBEDDED). The engine
never starts itself: a persistent server is a visible operator decision, and
`start` refuses without recorded consent. `start` reports that the launch was
*requested*, never that the agent is ready.

*Why:* "install my app and the phone becomes a server" cannot be built by adding
an SDK to a separate app. It requires the engine in the host's own UID, where
`requireSameSigner()` is satisfied by construction and no second installation
exists. Extracting the library is mechanical; the security boundary is not
weakened because the host is the server.

*Cost, stated plainly:* the host's UID can read every Grom file (token, audit,
sealed SSH credentials). Embedding is for hosts that are the same trust domain
as the server. Anything else must use the standalone app.

*Enforced by:* merged manifests (`EmbeddedManifest` policy check 25/26),
`GromEmbed` consent gate, `tools/test-policy` 25–26, `docs/EMBEDDING.md`.

## D56 — the dashboard shows measured lamps; everything else is collapsible
**ACCEPTED.** The screen is a header, a hero status with the single Start/Stop
action, a "Now serving" card with four lamps (control plane, data plane, bridge,
project) and collapsible sections for runtime, external servers,
Nostr & Lightning, local projects, public access, update and invite.

The lamp rule is absolute:

| Lamp | Meaning |
|---|---|
| green | a measured positive value (`running`, `reachable`, `alive`, `connected`) |
| red | a measured negative value, or an explicit error |
| grey | not configured / never measured |

`enabled = true` is configuration and can never produce green. Every lamp is
paired with text, and the mapping is a pure function in `DashboardModel`, tested
on the JVM. Section open/closed state is presentation-only, stored in
`GromPrefs`.

> **Amended by D68 and D69:** the collapsible cards and the stored open/closed
> state are gone. Sections now open from a header menu, so there is nothing to
> remember; `GromPrefs.sectionOpen`/`setSectionOpen` were removed with the last
> two cards. The lamp rule above is unchanged and is what still matters here.

*Why:* a dashboard that turns green because a flag is set is worse than no
dashboard. The projection being pure means the rule has tests instead of
screenshots.

*Enforced by:* `DashboardModelTest` (every lamp mapping), `SshTunnelManagerTest`
(measured bridge state), `LightningPluginTest` (no green before a probe).

## D57 — projects register themselves and liveness is derived
**ACCEPTED.** An embedded host may register a project
(`id`, `title`, `version`, `package`, `capabilities`). The record lives in
`files/grom/projects.json` and is written atomically, owner-only, with a size
cap. A project is `alive` only while its last heartbeat is at most 90 seconds
old; afterwards it is `stale`.

The caller supplies no `state`: `state` is computed at read time and is not
persisted as a claim. Registration is not evidence that a process is running.
At most 8 projects are kept; ids follow the plugin-id shape, titles are at most
64 characters and capabilities at most 12 short names. The file contains no
secret.

*Why:* a registry that trusts a `"running": true` field would be trivial to
fake and would make the dashboard lie. A heartbeat has a cost — the host must
keep announcing — which is exactly the property we want to measure.

*Enforced by:* `ProjectRegistryTest`, `ProjectRegistry`, `tools/test-policy`.

## D58 — the SSH bridge is a client that can only forward one port
**ACCEPTED.** Grom opens a reverse SSH session to an operator-saved target and
requests one `tcpip-forward` from that host to the local data plane. It is the
only outbound TCP client in the engine besides the loopback agent client and
the Lightning adapter.

Hard rules:

* **TOFU pinning.** A target with no pin is refused; the attempt reports the
  observed `SHA256:` fingerprint. The operator confirms; the pin is stored. A
  changed key is `HOST_KEY_MISMATCH`, never "accept anyway". A caller-supplied
  trust value cannot override an existing pin. Changing host or port drops the
  pin.
* **No shell.** No shell, exec, subsystem, agent or X11 channel is ever opened.
  `SshForwardingPolicy` additionally accepts a `forwarded-tcpip` channel only
  for loopback on the data-plane port, so even a hostile server cannot reach
  another service on the phone.
* **Sealed secrets.** Password or private key is stored only as
  `AndroidKeystoreSecretBox` ciphertext. Plaintext exists only for the duration
  of a connect and never reaches a log, audit record or status document.
* **Measured state.** `connected` only after the server acknowledged the
  forward. A stopped data plane is `DATA_PLANE_NOT_RUNNING`. At most 2 active
  tunnels, 8 saved targets, 15 s connect/auth timeouts.
* **Explicit action only.** A tunnel is opened by an operator tap in the UI;
  there is no auto-connect and no background retry.

This amends S1-R003: outbound connections are still limited to the update feed,
the operator-configured Lightning backend and now the operator-saved SSH
targets.

*Why:* a phone behind CGNAT can serve a VPS only if it dials out. Doing that
with a real SSH implementation, a pinned host key and a one-destination filter
is narrower than any VPN or tunnel daemon, and it removes the need for a
second process.

*Enforced by:* `SshTunnelManagerTest` (real MINA server, real forward, bytes
through the tunnel, cap, mismatch), `SshForwardingPolicyTest`,
`SshTargetStoreTest`, `SshKeysTest`, `tools/test-policy` 27–29,
`docs/SSH-BRIDGE.md`.

## D59 — status gains the bridge and projects, but not the operator's addresses
**ACCEPTED.** The authenticated `/v1/status` and the `agent` plugin's `status`
op gain:

* `projects`: id, title, version, capabilities and derived state;
* `bridge`: `configured`, `connected`, `active` and per-tunnel
  `{id, label, state, sinceMs, lastError}`.

`/v1/health` is unchanged and still carries none of this (D39). The SSH host,
username, port and fingerprint are **not** in the server-facing status: they are
configuration of the phone, not information a remote bridge needs. The on-screen
`statusForUi()` includes them because the operator is looking at their own
device.

`ConfigExport` gains `bridge` and `projects` using the same allow-list
discipline as D53. The Lightning lamp can only become green after a real
`health` op; `lightningProbeJson()` is that explicit probe, and it audits the
result.

*Why:* the VPS should know whether the bridge is up and which projects are
alive, not where the phone dials from. Splitting the two views keeps the
outbound credential surface on the device.

*Enforced by:* `ConfigExportTest` (address sentinel), `SshTunnelManagerTest`
(status has no host/user), `SuperStatusDocumentTest`, `DashboardModelTest`.

## D60 — SSH key material is generated on the device and sealed
**ACCEPTED.** Key authentication prefers an Ed25519 pair generated inside the
app. The engine uses the CC0 EdDSA provider, writes the private half as an
unencrypted OpenSSH key blob (`PROTOCOL.key`, cipher `none`) only long enough to
hand it to `SecretBox`, and exposes the public line for
`~/.ssh/authorized_keys`. An operator may instead paste an existing
OpenSSH/PEM key; it is validated by parsing it and then sealed the same way.

Both generated and imported keys travel through one loader
(`SshKeys.keyPairFromPrivateText`), so there is one failure mode and one test
surface. The OpenSSH blob is never written to disk unsealed and never appears in
a status or export document. Because MINA SSHD's exception peeler references
`javax.management.ReflectionException`, which Android does not ship, the engine
provides a minimal stand-in and narrows lint's `InvalidPackage` suppression to
that reference.

*Why:* requiring a laptop to generate a key is the difference between a feature
an operator uses and one they skip. Generating on-device and sealing immediately
keeps the private key out of every file, backup and bug report.

*Enforced by:* `SshKeysTest` (round-trip through MINA's loader), `SshTargetStoreTest`
(no plaintext on disk), `tools/test-policy` 28.

## D61 — Project key/value access is namespaced by the engine, per project
**ACCEPTED.** Every key written on behalf of a project is stored as
`p:<projectId>:<key>`. The prefix is computed in `ProjectNamespace`, never taken
from the request, and a key that already carries a foreign `p:` prefix is
refused with `PROJECT_KEY_INVALID`. The new API 4 method
`projectExec(projectId, op, argsJson)` is the only project-facing data path: it
accepts `kv.get`/`kv.put` only, requires the project to be registered
(`PROJECT_NOT_REGISTERED`), caps a value at 32 KiB (`VALUE_TOO_LARGE`) and
routes the call through the normal agent whitelist.

Scope, stated plainly in `docs/EMBEDDING.md`: this is an ownership gate on the
API surface, not a sandbox. Applications inside the **same UID** are one trust
domain; a deliberately malicious one could announce another project's id.
Isolation between applications needs separate UIDs or signatures, which is what
the standalone + `signature` model provides (D15, D55).

The SDK was also given what it was missing: `GromProject` (typed registration,
heartbeat, `put`/`get`, `configExport`, `probeLightning`) and a real heartbeat
in `GromEmbed.start`. Before this decision the documentation recommended a
30-second heartbeat that no code implemented.

*Why:* a documented boundary that only a convention does not protect anything,
and hand-built JSON in every host app is both a bug farm and a way to silently
bypass the namespace.

*Enforced by:* `ProjectNamespaceTest`, `GromProjectTest`, `tools/test-policy` 30,
`DashboardModelTest`.

## D62 — "How Grom works" is static, last, and closed by default
**ACCEPTED.** A fourth section at the bottom of the screen explains, in plain
language: what Grom does, why most people need no rented server, and when an
external SSH server helps. The content lives in `HelpModel` + `strings.xml`, is
fixed at three blocks, and reports no state.

> **Amended by D68:** the section has seven blocks. **Amended by D69:** it is
> no longer a card on the page — it opens from the header menu as the last
> entry. The static-content rule and the test below are unchanged.

The test asserts the block count and order, that every string exists, and that
the text contains no URL, no currency, no `balance`, no `payout`, and no uptime
promise. The only honesty claim it is allowed to make is that a down link is
shown as down.

*Why:* an explanation that reads state would be one more place to lie. Keeping
it static lets the gate prove what it promises.

*Enforced by:* `HelpModelTest`.

## D63 — An external Lightning node is a reference, never a client
**ACCEPTED.** `LightningSettings.externalNode` describes a node on the
operator's own server: `kind` (`lnd`/`cln`/`nwc`), a label, and an endpoint hint
of the form `host` or `host:port`. Grom opens no gRPC channel, no NWC
connection and no socket to it; LND, CLN and NWC stay on the VPS (the operator's
Q4 answer).

Validation refuses a kind-less record (`BAD_EXTERNAL_NODE`), a URL-shaped
endpoint, oversized fields, and anything that looks like a secret — the same
anti-secret rule as the bridge hint (`nsec1`, PEM, `password=`, `token=`,
`api_key=`, `secret=`).

`endpointHint` never leaves the device: `/v1/status` and `ConfigExport` carry
`kind`, `label` and `configured` only. Changing the reference does not restart
the listener (`sameRuntimePolicy` ignores it, extending D54).

*Why:* the address of the operator's infrastructure is exactly the kind of
detail D59 already keeps off the data plane. Storing a description is useful;
dialling it from a phone would be neither safe nor honest.

*Enforced by:* `ExternalNodeRefTest`, `SuperServerConfigTest`,
`SuperStatusDocumentTest`, `ConfigExportTest` (endpoint sentinel),
`tools/test-policy` 31.

## D64 — Saved SSH servers are unlimited; only live tunnels are capped
**ACCEPTED.** The saved-target count cap was removed. The only bound is the
store's 512 KiB file limit, enforced **at write time** with `SSH_STORE_FULL`:
an over-size write would otherwise make every record unreadable on the next
start, which is silent data loss. `MAX_ACTIVE = 2` stayed until D107, when the
cap was raised to four for an operator with several hosts; the cost it names — a
thread, a socket, an SSH session and a MINA transport per live tunnel — did not
change.

The UI and `sshStatusJson()` show an advisory once more servers are saved than
can be connected (`ssh_advisory`, `EXTRA_SERVERS_IDLE`; four since D107): extra
servers stay idle until the operator connects them, and the screen says so
rather than pretending the limit is storage.

*Why:* the operator asked to stop being told "no" for saving a server, while the
phone still must not be over-committed. Two different limits deserve two
different messages.

*Enforced by:* `SshTargetStoreTest` (no count cap; full store refuses the write
and stays readable), `tools/test-policy`.

## D65 — Telegram alerts are the fourth, and last, compiled host
**ACCEPTED.** Grom may post a short state message to the operator's own chat
through the Telegram Bot API. This amends `S1-R003` with a fourth explicit
outbound case (S1-R038).

The host `https://api.telegram.org` is compiled in because it cannot be
otherwise: the Bot API is one host, not a URL the operator supplies. Every other
property that made D48 acceptable is preserved: the feature is off unless the
operator enables it, the bot token is entered at runtime and never compiled in,
the message body is a fixed string from a table, and delivery is bounded.

`tools/test-policy` check 17 is rewritten to allow exactly two compiled hosts —
the update feed (`UpdateChecker.kt`) and the Bot API (`notify/TelegramNotifier.kt`)
— and to fail if either host appears anywhere else.

*Why not route it through the SSH bridge:* the bridge is a **reverse** tunnel
(`tcpip-forward`); it lets the VPS reach the phone. It does not proxy the
phone's outbound traffic. Using it for alerts would mean adding a SOCKS/proxy
client plus a relay on the VPS — more moving parts, a weaker audit trail, and a
new failure mode. A direct HTTPS POST with a fixed body is smaller and easier to
prove.

*Enforced by:* `tools/test-policy` 17/32, `TelegramNotifierTest`.

## D66 — Delivery is bounded; there is no scheduler and no disk queue
**ACCEPTED.** One message = at most **two** attempts (immediately, then once
after 4 s), an 8 s timeout, a 16-message in-memory queue that drops the oldest,
coalescing of the same event within 10 s, and a hard ceiling of 20 messages per
minute. There is no `Timer`, no `ScheduledExecutorService`, no
`scheduleAtFixedRate`, and no persisted queue to replay after a restart.

A failed delivery is recorded as failed with a stable code and reported as such.
`Send test message` is synchronous and bypasses the queue and the rate limit,
because the operator asked at that moment; it still requires `{"ok":true}`.

*Why:* an alert system that silently retries forever becomes an unbounded
outbound client — exactly what `S1-R003` exists to prevent. Two attempts absorb
a single Wi-Fi hiccup; anything more is a monitoring service, which Grom is not.

*Enforced by:* `TelegramNotifierTest`, `tools/test-policy` 33 (no scheduler
symbol in the notify package).

## D67 — Notification state is measured, sanitised, and never leaves the device whole
**ACCEPTED.** The bot token is stored only as `SecretBox` ciphertext. The chat ID
is not a secret but it identifies the device's owner, so it stays on the phone:
`/v1/status`, the sanitised export and the audit log carry
`{enabled, configured, lastDelivery:{ok, atMs}}` and nothing else.

The lamp follows the same rule as every other lamp (D56): it is green only after
a delivery the provider accepted, red after a rejection, grey before the first
attempt. `configured=true` never turns it green.

The message contains the event text plus an optional stable code and project id.
It never contains the token, the chat ID, an SSH host or user, a fingerprint, a
Lightning URL, an `npub`, a relay, a balance or free-form exception text.

*Why:* one leaking alert would undo the care taken everywhere else — the chat
ID is the identifier an attacker needs to spam the operator, and the SSH host is
the address of their infrastructure.

*Enforced by:* `NotifySettingsStoreTest` (no plaintext on disk),
`NotifyRulesTest` (no `detail` passthrough), `TelegramNotifierTest` (no token in
an error), `ConfigExportTest` (chat-ID sentinel), `tools/test-policy` 24/33.

## D68 — The dashboard keeps only state, Start/Stop and the serving lamps; everything else opens from a header menu
**ACCEPTED.** The page is now: header (wordmark, **Menu**, theme switch), the
status hero with Start/Stop, the "Now serving" card with its four measured
lamps, then — at the very bottom — the invite card and, immediately after it,
the "How Grom works" explanation. Runtime, external servers, Nostr & Lightning,
local projects, Telegram alerts, public access and updates are no longer cards
on the page: they open from a menu in the top-right corner, in that order,
because that is the order in which ignoring them breaks something.

The menu order is data, not layout: `MenuModel.entries(serverRole)` returns the
list and `MenuModelTest` pins it. A build without a server (`peripheral26`)
gets the update entry only, because it has no tunnel, no projects and no
alerts, and listing them would open empty dialogs.

Every section panel is still built once, exactly as before, and kept detached;
opening one reparents it into a dialog. That keeps every `render*` pointing at a
live view, so an opened section is never stale, no card is rebuilt twice, and
the periodic refresh keeps updating an open panel.

The explanation was extended from three blocks to seven (what Grom does, no
rented server, the external SSH booster, Nostr/Lightning, local projects,
Telegram alerts, and the honest limits), and it is open by default on first run
because it now sits below everything else and cannot push the dashboard down.
D62's static-content rule is unchanged: no block reads state.

*Why:* the operator opens the app to see whether the server is up and to start
or stop it. Six collapsed cards between the hero and the invite code made the
things that matter compete with the things that are configured once. Nothing is
removed — only moved behind one tap — and no lamp, secret or gate changes.

*Enforced by:* `MenuModelTest` (order, uniqueness, role split, strings exist),
`HelpModelTest` (block count and order, no address/price/uptime claim),
`tools/run-gates` (lint stays at 0/0, all three JVM suites).

> **Amended by D69:** the invite card and the explanation also moved into the
> menu. The page is now the header, the hero and "Now serving" — nothing else.

## D69 — the page holds nothing but the server; the invite card and the explanation are menu entries
**ACCEPTED.** The invite card and the "How Grom works" explanation are no longer
cards on the page. They are the last two entries of the header menu, in that
order: `MenuModel.KEY_INVITE` then `MenuModel.KEY_HELP`. The page is the header
(wordmark, Menu, theme switch), the status hero with Start/Stop, the "Now
serving" card and the footer version line.

`peripheral26` gets update, invite and help — the invite card and the text exist
in that build, so listing them is not an empty promise.

The collapsible card helper (`sectionCard`/`toggleSection`), the chevron header
(`Views.sectionHeader`) and their presentation preference
(`GromPrefs.sectionOpen`/`setSectionOpen`) are deleted rather than left unused:
every section is now a menu dialog, so "is this card open" has no meaning.

*Why:* the previous increment already moved the configuration into the menu, but
left the invite card and a scroll-length explanation underneath the dashboard.
An operator opening the app still had to scroll past two informational cards to
confirm that the footer — and therefore the screen — had ended. The instruction
was explicit: the main page shows only what matters, and the explanation belongs
in the menu like everything else.

*Cost, stated plainly:* the import from D62's "open on first run" is gone.
Nothing is lost — the explanation is one tap away and is the last entry of the
menu — but a first-time user no longer sees it without opening the menu. The
section cards were also the only place that remembered UI state; that state is
now simply absent.

*Enforced by:* `MenuModelTest` (order ends with `invite`, `help`; both roles;
titles and subtitles exist), `HelpModelTest` (unchanged content rules),
`tools/run-gates` (9/9, lint 0/0).


## D70 — multi-device is a pairing graph, not an account
**ACCEPTED.** There is no cloud account, no directory service, no shared
private key and no escrow. Every device has its **own** Ed25519 identity; the
"one operator" is the set of devices the operator has explicitly paired, held in
a local file on each of them.

A pairing is a mutual relationship, not a login: nothing is registered anywhere,
nothing is recovered from a server, and losing a device does not revoke it
remotely. That cost is the point — Grom stays usable with no account (S1-R004)
and there is no provider that can observe the fleet.

*Why:* an account would make the fleet depend on a service Grom does not own,
and one shared key across ten phones would mean one stolen phone compromises
all of them.

*Enforced by:* `NodeIdentity`/`NodeIdentityStore` (one key per device),
`PeerStore` (the local graph), `FleetServiceTest`, `NodeIdentityStoreTest`.

## D71 — pairing is mutual and explicit, never automatic
**ACCEPTED.** A pairing succeeds only when all of the following hold:

1. the device that shows the code has an **open session** (operator action,
   5-minute TTL, one at a time);
2. the joining device proves possession of its key by signing the
   `grom-pair-hello-v1` transcript, which binds the code hash to its node id;
3. the code hash matches — compared with `MessageDigest.isEqual`, with at most
   5 attempts before the session closes;
4. the operator on the showing device approves **and** chooses a non-empty
   subset of the requested scopes;
5. the joining operator compares the fingerprint on both screens and confirms;
6. the acceptance is signed (`grom-pair-accept-v1`) and verified with the
   showing device's key.

Knowing a code, or a node id, is never sufficient. Nothing is stored before both
confirmations; a rejection or an expiry stores nothing.

*Why:* the alternative — "first device to claim wins" — would let anyone on the
same LAN pair silently with a device whose public API is on.

*Enforced by:* `PairingTest` (signature, code binding, attempts, expiry, self,
forged hello, scope subset), `ClusterRouterTest` (pre-auth route, closed
session), `tools/test-policy` #36.

## D72 — the identity seed is sealed and cannot be recovered
**ACCEPTED.** The 32-byte seed exists only inside `NodeIdentityStore` and only
as Android Keystore ciphertext on disk (`seedSealed`). It is never returned by
any API, never written to status/export/audit and never logged. There is no
escrow and no backup.

A file that fails verification (`nodeId` must equal `Ed25519.keyId(publicKey)`,
and the seed must derive that same public key) is reported as
`IDENTITY_CORRUPT` and is **not** replaced. Recovery is the explicit
`nodeResetIdentityJson("RESET")` action, which mints a new key and forgets every
pairing.

*Why:* silently minting a new identity would break every pairing on every other
device without the operator noticing.

*Enforced by:* `NodeIdentityStoreTest` (tamper, resealed seed, over-size,
reset), `tools/test-policy` #35.

## D73 — peer access is scoped; grants are symmetric in this phase
**ACCEPTED.** Two scopes exist: `status.read` (`GET /v1/status`) and
`config.read` (`GET /v1/config`, the sanitised export). A paired device is a
`Principal.Peer` with exactly the scopes granted during pairing; everything else
— the operator's session, the operator's keys, an unpaired key — is
`Principal.Operator` and keeps the previous behaviour.

In this phase the grant is symmetric: each side grants the other the same set,
and both operators see it before ordering. Revocation is deleting the row and
takes effect on the next request, because the key lookup reads the store live
(no listener restart).

*Why:* an all-or-nothing peer would hand another phone the whole configuration
just to check whether it is alive.

*Enforced by:* `SuperAuthPrincipalTest`, `ClusterRouterTest`
(`status.read` passes, `config.read`/plugins return `403 SCOPE_REQUIRED`),
`tools/test-policy` #37.

## D74 — peer state is measured on request, never scheduled
**ACCEPTED.** A peer row starts at `unknown`. A successful signed
`GET /v1/status` within 90 s of the probe is `alive`; an older success is
`stale`; a failed probe is `unreachable` with a stable code. Pairing itself
never makes a lamp green.

Probes run only when the operator opens the Devices section or taps Refresh
(at most 16 peers per run). The engine schedules no background polling, so a
sleeping phone does not pretend to watch the fleet.

*Why:* "configured" is not "reachable", and a background poll would drain a
battery to produce a status the operator is not looking at.

*Enforced by:* `FleetServiceTest`, `PeerClientTest`, `PeerStoreTest`,
`FleetModelTest`, `tools/test-policy` #34/#37.

## D75 — the peer client is the fifth outbound case
**ACCEPTED.** `S1-R003` gains a fifth case: a signed request to a **paired
device**, at an address the operator typed during pairing and that is stored in
`PeerStore`. No host is compiled in; the client uses the shared `JsonTransport`
and holds no socket symbol of its own. It is never started by Grom — only by an
explicit `peerJoinJson` or `peerRefreshJson`.

This amends the outbound list in S1-R003 and the socket allow-list in
`tools/test-policy` #27 (now `cluster/` would be refused, and #34 asserts it
contains no socket instead). Pairing itself is a short-lived pre-auth surface on
the existing listener, reachable only while a session is open.

*Why:* a fleet that cannot ask another phone whether it is alive is a list, not
a fleet. Restricting the target to the paired, operator-entered address keeps
the "no host compiled in" property that makes the outbound list auditable.

*Enforced by:* `PeerClientTest`, `ClusterRouterTest`, `tools/test-policy`
#17/#27/#34, and `docs/CLUSTER.md` §5.

## D76 — the VPS hub is an operator-run tool, not a Grom service
**ACCEPTED.** Phase C is a small program in `tools/grom-hub`, run by the
operator on their own VPS. It is not shipped in the APK, Grom does not call it,
and no phone function depends on it: if the hub is down, the phones keep
working and only the roster is missing. It polls the documented data plane
through the reverse SSH tunnel the operator already opens, so a phone behind
CGNAT is reachable without a LAN bind and without a public port.

The hub's key is a mode-0600 file owned by a dedicated user, because a VPS has
no Android Keystore. That limitation is stated plainly rather than papered over.

*Why:* a service would need onboarding, updates, an account and a trust decision
about who runs it. A tool is something the operator starts, reads and stops.

*Enforced by:* `docs/HUB.md`, `tools/test-hub`, `tools/test-policy` #41.

## D77 — a one-way scoped grant, not a second pairing
**ACCEPTED.** A reader that only calls in (the hub) is admitted by pasting its
Ed25519 public key under Devices → Trust a key and choosing scopes. The key id is
derived from the key, at least one scope is required, and the stored record has
**no address**: the phone never dials it, and `refresh` skips it rather than
recording a false failure. The caller is a `Principal.Peer` with exactly those
scopes and can never become an operator key.

There is no handshake, no code and no auto-approval: the operator still supplies
the key and picks the scopes by hand. The grant is revocable by removing the row,
which takes effect on the next request.

*Why:* mutual pairing needs a Grom engine on both sides; a hub is not one.
Deriving the id prevents a pasted key from claiming an identity it does not have.

*Enforced by:* `PeerGrantTest`, `PeerClientTest` (no dial for an empty address),
`SuperAuthPrincipalTest`, `tools/test-policy` #40.

## D78 — one forward port per phone; a refused forward is its own failure
**ACCEPTED.** Each SSH target on one host MUST use a distinct
`remoteForwardPort` (one per phone, 41001–41099 by convention). The store
refuses a duplicate with `BAD_FORWARD_PORT_CONFLICT` before writing, and the
tunnel manager reports a server that refuses the forward as
`SSH_FORWARD_REFUSED` rather than `SSH_FAILED`, because the two send the
operator to different places. The hub roster listens on loopback only and
carries no port, address or key.

*Why:* with every target defaulting to 47002, the second phone on a VPS failed
in a way that looked like a network problem — the exact confusion a hub must
not create.

*Enforced by:* `SshTargetStoreTest`, `SshTunnelManagerTest`, `tools/test-hub`,
`tools/test-policy` #38/#39.

## D79 — provisioning is an operator tool outside the APK, and it destroys nothing
**ACCEPTED.** Preparing several phones runs through `tools/grom-provision`,
which drives `adb` from the computer. It is never bundled into the APK, the app
never depends on it, and `adb` is never a runtime requirement of Grom. The tool
may only read a phone, install the APK the operator names, and ask for a battery
exemption; it MUST NOT remove packages, wipe data, restart a phone or terminate
a process, and it MUST talk to adb through an argument list rather than a shell
string. Anything that fails must be reported with a stable code and a hint,
never assumed to have worked.

*Why:* the fastest way to make "install Grom on ten phones" pleasant is also the
fastest way to add a remote wipe primitive to a project that promises it has
none. Installing and exempting are setup; deleting and wiping are the operator's
actions, taken on the phone, with the phone in hand. The tool also keeps the
project's promise that the security model is unchanged by convenience tooling:
it uses the same signed APK and the same public data plane.

*Scope kept out on purpose:* it does not configure the app (no `Public access`,
no SSH targets, no pairing), because those are per-device decisions with their
own UI confirmations, and it emits only `hub.json` — a `fleet.json` needs a LAN
address the computer cannot know.

*Amendment (phase b, D81):* the tool also has a `doctor` command, which reads the
settings an app cannot read — the Doze whitelist, `cmd appops get … 
RUN_ANY_IN_BACKGROUND`, `device_config … max_phantom_processes`,
`settings_enable_monitor_phantom_procs` and `wifi_sleep_policy` — and prints them
with stable finding codes. `--fix` is the only mutating path and is limited to
**two** changes: adding the package to the Doze whitelist, and setting the legacy
`wifi_sleep_policy` **only on API 28 and older**, where it actually has an effect.
It never changes an app-op, never removes a package and never restarts a phone.
Both changes are re-read afterwards, so the report states what the phone now says
rather than what the tool asked for. The self-test enforces this structurally:
every command the tool issues must match a fixed allow-list, so a verb nobody
thought of fails the test instead of passing silently.

*Enforced by:* `tools/test-provision` (96 cases incl. a recorded-invocation
allow-list, the doctor read/fix paths and `VERIFY_FAILED` for an unconfirmed
install), `tools/test-policy` #42.

## D80 — the Doctor is local, on-demand and read-only
**ACCEPTED.** "Why does my phone keep stopping the server?" is answered by a
Doctor section that measures, explains and opens system settings. It MUST NOT
change a system setting, run a shell, ask for root, start a timer or poll in the
background. It measures when the operator opens the section or taps "Measure
again", and at no other time.

Every row is exactly one of three things, and they are never blurred:
*measured* (green or red, from a value Android returned), *notice* (a measured
thing that is neither healthy nor a fault, grey), and *unknown* (this Android did
not answer — grey, and the row says so in words). The verdict MAY be green only
when every check measured `OK`; a report with an unknown row is "partly unknown"
even if nothing is wrong, because a green headline that hides an unmeasured line
is the lie this screen exists to prevent.

*Why:* the app cannot know whether doze, a vendor killer or a flat battery ended
the process, and guessing would make the screen useless exactly when it is
needed. Reporting only what was measured keeps it trustworthy; refusing to be
green while anything is unknown keeps it honest.

*Enforced by:* `DoctorRulesTest` (a "nothing measured is not a pass" case and a
"unknown keeps the verdict from turning green" case), `DoctorModelTest`,
`tools/test-policy` #43/#44/#45.

## D81 — the deeper system facts are read by the operator, over adb
**ACCEPTED.** The Doze whitelist, `device_config max_phantom_processes`,
`settings_enable_monitor_phantom_procs`, app-ops and vendor auto-start lists are
not readable by an ordinary app. The Doctor MUST show the exact commands, offer
to copy them, and MUST NOT run them. `tools/grom-provision doctor` MAY read them
from a computer, because there the operator is already the one holding the cable.

*Why:* the alternative is a permission the platform does not offer, or a shell
that would make Grom a privilege-escalation surface. Showing the command teaches
the operator what the setting does; running it silently would leave them without
that knowledge and with a tool that can change anything.

*Enforced by:* `tools/test-policy` #44 (no shell symbol in `engine/doctor/`),
`tools/test-provision` (the `doctor` command's allow-list).

## D82 — the Doctor permission budget is two normal permissions
**ACCEPTED.** The Doctor MAY declare `ACCESS_NETWORK_STATE` (to name the
transport) and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (to offer Android's own
one-tap exemption dialog). It MUST NOT declare `QUERY_ALL_PACKAGES`,
`PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, and MUST NOT ask for root. Where a
probe would need one of those, the field stays null and the row is `unknown`.

*Why:* every extra permission widens what a compromised build could reach, and
none of them is needed to answer the question. Grom is installed by hand rather
than through Play, which is why the battery permission is acceptable at all; that
fact is documented rather than assumed.

*Enforced by:* `tools/test-policy` #46, `AndroidDoctorProbe` (every call is
guarded and returns null).

## D83 — an unexpected stop is a fact; its cause is reported only when the OS says it
**ACCEPTED.** `GromService` writes a run marker on `onCreate` (`cleanStop=false`)
and on `onDestroy` (`cleanStop=true`). If the next start reads `false`, the
previous process ended without `onDestroy` — a fact. The *reason* is reported only
from `ApplicationExitInfo` (API 30+); below that the row is `unknown` and says the
version does not record exit reasons. `lastUnexpectedStopMs` is the **detection**
time, never a fabricated death time, because the marker cannot know the moment.

*Why:* "it died" and "Android killed it for memory" are different facts with
different fixes. Conflating them would put a plausible-sounding cause on screen
with nothing behind it.

*Enforced by:* `RunMarkerStoreTest` (detection time, corrupt file, clamping),
`DoctorRulesTest` (every `REASON_*`, API 29 vs 30), `tools/test-policy` #45.

## D84 — `/v1/status` may carry three Doctor booleans, and nothing else
**ACCEPTED.** The authenticated status document MAY include
`doctor {batteryExempt, backgroundRestricted, lastUnexpectedStopMs}` so a hub can
say *why* a phone is unreachable rather than only that it is. Nothing else from
the Doctor leaves the device: no exit history, no free text, no memory, thermal or
network detail. `/v1/health` stays unchanged and stays public. The block is read
at request time from a cheap two-call probe, never cached at start.

*Why:* a roster that says "unreachable" is less useful than one that says
"unreachable, and this phone is not exempt from Doze". Three booleans answer that
without turning the status document into a device fingerprint.

*Enforced by:* `tools/test-policy` #47, `DoctorFacadeTest`, `DoctorDocumentTest`.

## D85 — the control port belongs to the host, and a conflict is named
**ACCEPTED.** One phone may hold more than one Grom: the standalone server and a
host application that embeds `:engine` are separate packages, so they have
separate data, separate tokens and separate node identities. They do not have
separate default ports. `GromEmbed.Options.controlPort` (and
`GromEmbed.setControlPort`) lets the host choose the loopback control port before
the service starts, and the start is refused with the stable code `PORT_IN_USE`
when something already holds it. The runtime also maps a bind conflict to the same
code, so the trace is never "signature rejected" or "network failure".

**No silent fallback.** A server that moved itself to another port would break
every client that knows the old one — the hub's tunnel target, a paired device,
`tools/grom-fleet`. The standalone app therefore keeps 47001 and reports the
conflict honestly instead of hiding it.

The probe (`core/ControlPort`) is advisory: the authoritative answer is still the
bind. It exists so the failure arrives before the process starts, with a name.

*Why not one shared server for both apps:* sharing requires a second trust
relation — an IPC permission that is not "same signature" — which is exactly the
gate S4-R007 and D15 exist to keep closed. Two servers, two ports, is the smaller
and safer change.

*Enforced by:* `ControlPortTest` (probe agrees with a real bind; two listeners
cannot share a port; two distinct ports coexist), `tools/test-policy` #49.

## D86 — a host configures the data plane through the screen's own contract
**ACCEPTED.** `IGromServer.superSaveConfig(String)` (API 9) accepts the
`SuperServerConfig.toJson()` document, validates it through
`SuperServerConfigInput.parse` — which runs `SuperServerConfig.validate`, the
screen's exact contract, plus the same 64 KiB cap as the store — persists it
atomically and applies it if the plane is started. A refusal is a `400` with the
same stable code the screen would show (`PORT_CONFLICT`, `CREDENTIAL_REQUIRED`,
`INSECURE_BACKEND_URL`, …) and **nothing is written**.

A plaintext password is not accepted: the document carries `passwordHash`, and a
host that wants password auth hashes with `PasswordHash.hash` first. The returned
status is the sanitised one — `passwordSet` and key ids, never the hash.

`SuperServerHost` now tracks whether it was started, so saving a document can
never bind a listener the runtime has not started. Before this, `reload()` on a
stopped plane would have started one.

*Why not a `GromEmbed` option with a typed builder:* the screen would need the
same change or the two paths would diverge again. One document type, one
validation function, two callers.

*Enforced by:* `SuperServerConfigInputTest` (every refusal is the screen's code),
`tools/test-policy` #50, `GromClientContractTest` (API 9).

## D87 — the peripheral role ships no runtime it never executes
**ACCEPTED.** `peripheral26` has no listener, starts no agent and never executes
`libnode.so`, yet it carried the whole ~91 MB arm64 runtime because the files live
in `src/main/jniLibs`. The variant now excludes `jniLibs` and the debug APK drops
from ~39 MB to ~2.2 MB. The files stay where
`scripts/build-node-android.sh --install` documents them, so the server role and
an embedding host keep the runtime they execute.

*Why not move the files to `src/server29/jniLibs`:* every documented command,
script and doc points at the current path, and moving them would break the
operator's flow to save one build-configuration line. The exclusion is per
variant, which is the narrowest place for the decision.

*Enforced by:* `tools/check-flavours` (the built APKs, not the build script),
`tools/test-policy` #51. The repository ships no binaries (D35), so a clean CI
checkout builds both APKs without the runtime: `check-flavours` reports that
explicitly instead of pretending to have checked the server half, and the
release workflow — which installs the runtime — asserts that `server29` contains
`libnode.so` and `peripheral26` contains no `lib/` entry at all.

## D88 — consent is about a notice, not a flag
**ACCEPTED.** `GromEmbed.recordConsent` used to store a boolean. A host could
record "yes" once, then change what it starts — a data plane, another project, a
rewritten dialog — and the old answer would silently authorise the new
behaviour. The record is now `(noticeVersion, grantedAtMs)`, and `start` refuses
with `CONSENT_OUTDATED` when the host declares a version the operator never saw.
A `false` answer clears any previous consent, so "no" cannot be overwritten by an
older "yes".

The engine still owns no wording (D55): the host writes and localizes the notice
and picks the version string; the engine stores the pair in the host's private
preferences and compares it. A host that declares no version keeps the pre-D88
behaviour, and is asked once more the day it starts versioning — which is the
point of versioning it.

*Why not a hash of the notice text:* a hash would have to be recomputed by the
engine, which does not have the text, and would break on every
translation change for a reason the operator cannot see.

*Enforced by:* `EmbedConsentTest`, `tools/test-policy` #53.

## D89 — an automatic control port is resolved, persisted and returned
**ACCEPTED (extends D85).** D85 made the port the host's choice and forbade a
silent fallback, because a server that relocates itself breaks the hub's tunnel
target, a paired device and any CLI holding the old port. That rule stands. What
was missing for a host that does not want to think about ports at all is an
*explicit* automatic choice: `GromEmbed.AUTO_PORT` (`0`).

`ControlPort.resolve` handles the whole matrix before the service starts, with
the probe injected so every branch is a JVM test: a free `47100..47199` port is
picked when nothing is stored, a previously chosen port is **reused while it is
still free** (stability matters more than freshness), the standalone default
`47001` is never reused for an automatic choice, a running listener is never
relocated (`PORT_CHANGE_RESTART_REQUIRED`), and an exhausted range is
`PORT_IN_USE`. The chosen port is persisted and returned in `StartResult.port`.

*Why not silently retry in the service:* that is exactly the fallback D85
forbids. The difference here is that the host asked for a choice and is told
which one it got.

*Enforced by:* `ControlPortResolveTest`, `tools/test-policy` #53.

## D90 — the engine exposes health as codes and actions, never as sentences
**ACCEPTED.** Embedding hosts were told to read `statusJson` *and* `doctorJson`
and then invent the sentence for `BATTERY_OPTIMISED` — which is how a host ends up
painting a green lamp over a report with a `BAD` row. `EmbedHealth.of(report)`
projects the Doctor into `(state, headline, advice[])`, where every code is
stable and every advice carries an `EmbedAction`. The projection is built from
`DoctorRules`' own output, so it cannot disagree with the Doctor screen, and the
mapping is pure Kotlin with no `android.*` import, so it is fully JVM-tested.

Three rules are absolute: `RUNNING` is produced only by a fully measured healthy
report; a `NOTICE` (the data plane deliberately off, no SSH target) never becomes
a to-do; and `GromEmbed.measureHealth` binds with `createIfMissing = false`, so a
health check can never start a server the operator did not ask for.

The engine returns no user-visible text: the host owns wording and translations
(D55). `docs/TWO-IN-ONE.md` §9 is the code table, and `tools/test-policy` #54
fails when a code exists in the code but not in the guide.

*Enforced by:* `EmbedHealthTest`, `GromEmbedStartCodeTest`, `tools/test-policy`
#52, #54.

## D91 — a client discovers Grom through one data-plane document
**ACCEPTED.** An application that wants to use Grom as a local server had to read
three endpoints and guess the rest: `/v1/health` says a server exists,
`/v1/status` needs a credential, and `/v1/plugins` needs an *operator* credential
it should never have. `GET /grom/v1/connect` is now the one document a client
reads first (S6).

The document is anonymous on the base level — schema, api, versions, auth
methods, scopes, the measured data-plane endpoint and the route map — and adds
the caller's capabilities and the service list only when a valid credential is
presented. A presented but invalid credential is refused with `401`; it is never
downgraded to the anonymous answer, because a host must not read "my key was
revoked" as "this server has no services". The anonymous document lists no
service at all: knowing that Grom exists is not knowing how it is configured.

`/v1/health` is untouched. The new route lives under the `/grom/v1/` namespace
the project already uses for its own contract, on the data plane, and the
`/v1/*` paths every existing client speaks are unchanged.

*Enforced by:* `ConnectRouterTest`, `GromConnectClientTest`, `tools/test-policy`
#56, #59.

## D92 — a connected app gets services, never an operator key
**ACCEPTED.** `status.read` and `config.read` let a client read; they did not let
it *use* a service, and the only alternative was handing it an operator key,
which would also unlock the audit surface, the event stream, logout and the
Lightning adapter. Two scopes are added: `services.read` (enumerate services,
descriptions, capabilities, `scopedOps`) and `services.call` (invoke an op the
service publishes).

The route-level scope is not enough, because a service knows its own semantics.
`Plugin.scopedOps()` is the per-service allow-list of callable operations; its
default is **empty**, so a plugin that forgets to declare it exposes nothing. The
op check runs after authentication and before `rpc`, so `services.call` can never
widen into "anything a service can do". `AgentPlugin` publishes `echo`,
`kv.get`, `kv.put`, `ping` and withholds `status` (the host's projects, bridge,
notifications and fleet block are a `status.read` surface). `LightningPlugin`
publishes nothing: it can move funds, and a money permission must never be a
side effect of a service grant.

Listing a service does not imply its status: the `status` block appears only for
a caller that also holds `status.read`. A connected app is never an operator.

*Enforced by:* `ConnectRouterTest`, `ClusterRouterTest`, `tools/test-policy` #57.

## D93 — the client SDK is thin, signer-injected and pinned to one signature
**ACCEPTED.** Integrators were told to re-derive the canonical signature string,
the nonce alphabet and the error envelope by reading S5. That is how a client and
a server drift into two incompatible formats. `app.grom.sdk.connect` now carries
the reference HTTP client (S6), with no dependency beyond `HttpURLConnection` and
a small purpose-built JSON reader/writer.

Three rules make it honest. It **never holds a private key**: a host that uses
Ed25519 supplies a `GromSigner`, so key material stays in the host's Keystore
store (D72). Its `canonicalString` is compared byte-for-byte with the server's
`RequestSigner.canonicalString` by a test, so the two cannot drift silently. And
the example (`examples/connect`) is a compiled module with its own test, not a
snippet in a document that rots.

MCP, WebSocket and dynamic plugin loading stay out: the existing HTTP/JSON RPC
already describes and calls a service, and a second protocol would be a second
thing to keep secure.

*Enforced by:* `GromConnectClientTest`, `GromJsonTest`, `ConnectExampleTest`,
`ConnectRouterTest` (parity), `tools/test-policy` #58.

## D94 — the AI gateway is a protocol router on the existing data plane
**ACCEPTED.** "One AI endpoint for every app" could be a new server, a new port
and a new credential model. It is none of those: a request arrives on the public
data plane that already exists, the operator's saved configuration selects a
provider, and one of three wire adapters speaks that provider's own dialect
(OpenAI-compatible, Anthropic, Gemini). A new vendor is a `custom` row with a
protocol and an endpoint — not an APK update, and not a new class.

Two rules make it more than a proxy. The **catalogue is the spending limit**: a
model that is not registered is refused (`AI_UNKNOWN_MODEL`) rather than forwarded
under the provider's own name, because the operator approved specific models and
specific costs. And the **requested parameters are an allow-list**: a field the
gateway does not implement (`tools`, `response_format`, image content) is refused
with its own name, because a client that receives a plain answer while asking for
tool calls would conclude the model ignored it.

The gateway is also a service (`ai`), so an app that already speaks
`/v1/plugins/{id}/rpc` needs no new transport; both surfaces call one
`AiGateway`, so there is one implementation and one set of rules.

*Enforced by:* `AiGatewayTest`, `AiRouterTest`, `AiGatewayIntegrationTest`,
`ConnectExampleTest`, `tools/test-policy` #63 and #64.

## D95 — a provider key is sealed, unprojected, and only ever sent to its endpoint
**ACCEPTED.** A provider API key is a bearer secret for a third-party account:
whoever holds it spends the operator's money. It is therefore treated like the
Lightning credential (D44): sealed with a non-exportable Android keystore key
before it is written, opened for the duration of one upstream request, and never
projected. The operator view says `keySet: true`; the data-plane view says only
that a provider is `configured`. No status, export, error, header or log carries
the key — the upstream error text is kept, truncated, after the key is removed
from what Grom composes (Grom never echoes the request, so it cannot echo the key).

The endpoint is held to the same standard. It must be `https`, or `http` only for
a loopback host so a local runtime (llama.cpp, Ollama, LM Studio) still works
while a key cannot travel in clear text off the device. Redirects are never
followed: a `Location` would move the key to a host the operator never approved.

*Enforced by:* `AiStoreTest` (the plaintext never reaches the file),
`AiGatewayTest` (no endpoint or key in a projection), `tools/test-policy` #60 and
#62.

## D96 — an AI lamp is measured, on demand, and never derived
**ACCEPTED.** A provider row that is enabled, has a key and looks correct proves
nothing: the key can be revoked, the endpoint can be wrong and the model can be
retired. The gateway therefore reports **no** health until the operator presses
**Test** (or an app makes a call), and **Test** is one tiny upstream request
(`max_tokens: 1`) whose real latency and result become the lamp. There is no
scheduler, no background probe and no cached "healthy": the same rule the Doctor
(D80) and the fleet (D74) already follow.

`available` in the catalogue is configuration, and S7-R005 says so explicitly, so
a client does not turn an enabled row into a health claim.

*Enforced by:* `AiGatewayTest` (probe measurement, no dial for a disabled or
unconfigured provider), `tools/test-policy` #64 (no scheduler in the package).

## D97 — streaming is server-sent events on the existing listener
**ACCEPTED.** Streaming needed no new protocol, port or framing: the data plane
already writes `SuperResponse.Stream` for the operator event bus, so the gateway
reuses that head/handoff path and emits the two shapes its clients already parse
(OpenAI `data:` chunks ending in `[DONE]`, Anthropic named events ending in
`message_stop`).

The ordering is the part that matters. Everything that can fail without a network
— unknown model, disabled provider, missing key, an oversized body — is decided
**before** the head is written, so a client never receives `200` and then learns
the request was rejected (S7-R015). A failure after the head becomes an error
event. The client closing the connection stops the upstream read; Grom does not
keep reading a provider for a caller that is gone.

*Enforced by:* `AiGatewayTest` (frames, early close, mid-stream failure),
`AiRouterTest` (the router hands a `Stream` through), `AiGatewayIntegrationTest`
(real SSE over a real socket).

## D98 — the agent's argv carries no engine switch, and `--verify` runs it
**ACCEPTED.** The launcher used to pass `--experimental-default-type=commonjs`
before the script. That was correct for the runtime it was written against and
wrong for the runtime this project pins: Node v24.22.3 answers `bad option`,
exits before printing `GROM_AGENT_READY`, and the server then looks installed and
dead — the worst failure shape this project has, because every other check still
passes. The agent is CommonJS and every pinned Node LTS loads it that way without
a switch, so the flag is gone.

The rule that follows is narrower than "be careful": **argv is verified by
execution, not by a string assertion.** A unit test can only confirm that the
launcher emits the argv it was written to emit; it cannot confirm that the
runtime accepts it. `scripts/build-node-android.sh --verify` therefore starts the
**packaged binary** with the **launcher's exact argv** and waits for
`GROM_AGENT_READY`, and says plainly when the host cannot execute the binary
instead of skipping silently. Policy #65 keeps `--experimental-` out of the
launcher until such a flag is proven against the pinned runtime.

*Enforced by:* `AgentLauncherTest` (six-element argv, no `--experimental-`),
`scripts/build-node-android.sh --verify` (a real launch), `tools/test-policy` #65.

## D99 — the mesh is discovery, a roster and a relay over the existing API
**ACCEPTED.** A "server network" of phones needs three things and no new server:
a way to hear that another Grom is nearby, a scored roster of what it serves, and
a way to call it. None of those require a second protocol. Every call the mesh
makes is the data plane's own `/grom/v1/connect`, `/v1/plugins/{id}/rpc` or
`/v1/ai/*`, signed with this device's Ed25519 identity and verified by the same
`RequestSigner`. A mesh call is an ordinary peer call with a different reason.

The carriers are chosen by what they can do, not by fashion. LAN (and a VPN above
it) is ordinary TCP and preferred. Wi-Fi Direct gives a group owner IP and a
DNS-SD service record. Bluetooth Classic carries HTTP/1.1 over a secure RFCOMM
stream (`bt://<MAC>`). BLE is **discovery only**: eight bytes are a name tag, and
pretending a BLE link is a data plane would be the kind of claim this project
does not make. A BLE-only sighting has no endpoint and the screen says so.

Discovery is a sixth documented outbound path (S1-R003 amendment): an opt-in UDP
beacon to one fixed link-local multicast group, a BLE advertisement, a Wi-Fi
Direct service and an RFCOMM link. It is off by default, it starts and stops with
the server, and it never dials an arbitrary host. All RPC still goes through the
shared transport.

*Enforced by:* `MeshIntegrationTest` (real sockets), `MeshRouterTest`,
`tools/test-policy` #66/#67.

## D100 — a server-to-server call uses the peer's scopes, never an operator key
**ACCEPTED.** The relay at `POST /v1/mesh/call` is gated by its own `mesh.call`
scope, which is separate from `services.call`: using a local service and
borrowing this device's federation are different decisions. The roster at
`GET /v1/mesh` needs `mesh.read`, and it carries no endpoint — not a host, not a
port, not a MAC. An address is operator state, returned only over the
signature-gated Binder, because the operator is the one who approved it.

The call itself runs under this device's identity, so the target applies exactly
the scopes and shares it granted this device. The relay checks the obvious
things first — paired server, published service, published op, held scope — so a
misconfiguration is a local, stable code and not a remote 403 storm. The peer
checks them all again; neither side trusts the other's decision.

*Enforced by:* `MeshCallerTest`, `MeshRouterTest`, `MeshIntegrationTest`
(revoked key, unlisted op), `tools/test-policy` #69.

## D101 — services are shared per peer, and the empty list is the old behaviour
**ACCEPTED.** Until now a paired device with `services.call` could use every
operation every service published. That was the right default for one trusted
app; it is too wide for a network of phones. `PeerRecord.services` adds an
operator-chosen allow-list by plugin id. An empty list keeps the pre-D101
meaning — everything the scopes already allow — so no existing pairing changes
behaviour, and the UI treats "all ticked" as the same empty list. A non-empty
list is a hard filter in the router: the service is hidden from that peer's
listing and refused with `SERVICE_NOT_SHARED`.

The list is read live with the record, so a change applies to the next request
without a restart (D54). It is validation-checked at write time: a name this
device does not serve is refused, and a malformed id cannot be stored.

*Enforced by:* `PeerSharesTest`, `MeshRouterTest` (hidden listing),
`MeshIntegrationTest` (404/403 after a share change), `tools/test-policy` #69.

## D102 — Bluetooth Classic is secure RFCOMM carrying the same HTTP request
**ACCEPTED.** Android's public Wi-Fi Direct and BLE APIs have no service payload
large enough for a request, and Classic has no advertisement Grom can filter on.
Classic therefore has one job: a byte pipe. `bt://<MAC>` opens
`createRfcommSocketToServiceRecord` — the authenticated, encrypted variant that
requires a bonded device — and the mesh writes the same HTTP/1.1 request and reads
the same response. There is no insecure variant, no automatic reconnection and no
streaming: an SSE answer has no length and would never end.

One routing transport dispatches `http(s)` to the client the peer probe already
used and `bt` to this stream carrier. The stream codec is tested on the JVM over
a real loopback socket (the "RFCOMM link" is a duplex stream), so a wrong request
line, header or body length fails in CI rather than on a phone.

*Enforced by:* `BluetoothJsonTransportTest`, `HttpWireTest` (which drives
`RoutingJsonTransport`), `MeshCallerTest` (bt path), `tools/test-policy` #70.

## D103 — discovery is untrusted, bounded and on demand
**ACCEPTED.** Every discovery carrier can be spoofed by anyone in radio range: a
signed LAN beacon only proves the sender holds the key it names (which stops it
claiming *your* node id), a BLE prefix collides, a Wi-Fi Direct service record is
whatever the group owner published. The mesh therefore never turns a sighting
into trust: the book is bounded (32 entries), expires (120 s) and is only a list
of hints. The operator pairs, compares fingerprints and chooses scopes.

Nothing polls. The roster refreshes when the section opens, when Refresh is
tapped or after an action; the only periodic work is the beacon, and only while
advertising is on. A disabled mesh answers `MESH_DISABLED` and the rest of the
server is untouched.

*Enforced by:* `DiscoveryBookTest`, `MeshSettingsTest`, `MeshServiceTest`,
`tools/test-policy` #67/#68.

## D104 — a pairing carries peer grants, and the mesh asks for them explicitly
**ACCEPTED.** Until now every pairing requested exactly `status.read` and
`config.read`. That is enough to *see* another device and nothing else, so the
server network's first real use — see what the other phone serves, then call one
of its services — could not be reached from the screens at all: the roster said
`MESH_SCOPE_NOT_GRANTED` and every call was refused. The feature existed in the
engine and in the tests, but not in the product.

The fix is not to widen the default. `Devices` keeps asking for the same two
scopes, and the server network's pairing dialog adds `services.read` and
`services.call`, unticked only if the operator says so. The accepting operator
sees the same list before approving *(the pairing is symmetric, D73)*. A pairing
now carries a subset of a **peer-grantable** set: `status.read`, `config.read`,
`services.read`, `services.call`, `ai.read`, `ai.chat`. `mesh.read` and
`mesh.call` are grants this phone gives its own applications; handing one to
another device would put a permission the other operator cannot interpret in
front of the approval screen, so both the joining request and the accepting
endpoint refuse it.

Two smaller corrections travel with it, both found while making the flow work:

- `PeerStore.grant` used to replace a record wholesale. Granting scopes to a key
  that was **already paired** therefore dropped its address and made it
  inbound-only — a paired server that could never be called again while the
  screen still listed it as paired. A grant to an existing record now replaces
  only `grantedByMe` (and the label) and keeps the endpoint, the other side's
  grants and the pairing time.
- The mesh screen requests the Bluetooth/Wi-Fi-Direct runtime permissions before
  enabling discovery or scanning. Without that, the engine could only report
  `MESH_PERMISSION_REQUIRED` and the operator had no way to grant it from the
  app.

A pairing still grants exactly what it says: a service also has to be shared
before it can be called (D101), and `services.call` with an empty share list is
the established "everything its scopes allow" default, which the share dialog
shows in words.

*Enforced by:* `PairingTest` (a hello asking for `mesh.call` is refused),
`PeerGrantTest` (a grant keeps the endpoint), `MeshModelTest` (`pairScopes`),
`tools/test-policy` #73, S8-R015.

---

## D105 — an SSH failure is diagnosed by the stage that broke, not by its class
**ACCEPTED.** Reported from use: *"I cannot connect to my VPS over the public
address and password."* The engine could not answer the question, and in two
ways it answered it wrongly.

1. `SshTunnelManager` mapped failures by exception type: `TimeoutException` →
   `SSH_TIMEOUT`, **`IOException` → `SSH_UNAVAILABLE`**, everything else →
   `SSH_FAILED`. Apache MINA SSHD raises `SshException` — which *extends*
   `IOException` — when authentication is rejected, so **a wrong password was
   reported as "connection refused / DNS / network"**. The operator was told to
   check a firewall. With `SSH_FAILED`, the message was the class name only
   ("the SSH session failed (SshException)"), which carries no information by
   design (a driver message can contain the host, the user and key fragments).
2. After the operator confirmed the first-use fingerprint, **any** later failure
   was reported as `HOST_KEY_MISMATCH`: the catch block treated "pinned is null
   and a trust value was supplied" as a mismatch, even when the observed key was
   byte-identical to the confirmed one. A wrong password on the first connect
   therefore produced *"the host key changed — the server presented a different
   key"*, with an offer to forget the pin. That describes a machine substitution
   and is the opposite of what happened.

The rule is now structural: the attempt records **which stage it is in**
(`resolve`, `connect`, `handshake`, `auth`, `forward`) immediately before the
call that can fail, and the code comes from the stage. MINA surfaces a refused
TCP connection when the *connect future* is verified — the same call that does
the key exchange — so the two are separated by the **cause chain**, where
`ConnectException`, `NoRouteToHostException` and `UnknownHostException` survive
and a handshake failure never carries one. The codes are `SSH_HOST_UNKNOWN`,
`SSH_HOST_UNREACHABLE`, `SSH_TIMEOUT`, `SSH_HANDSHAKE_FAILED`, `SSH_AUTH_FAILED`
and `SSH_FORWARD_REFUSED`; `SSH_UNAVAILABLE` remains only for "this build has no
Ed25519 provider".

Two related corrections travel with it:

- **Keyboard-interactive.** OpenSSH with `UsePAM yes` — the default on Debian and
  Ubuntu — accepts a password login as a single non-echoing
  `keyboard-interactive` prompt, not as the `password` method. With no
  `UserInteraction` the challenge is never answered and a **correct** password
  fails as "exhausted available authentication methods". `SshPasswordInteraction`
  answers exactly one non-echoing prompt and declines anything else, so a second
  factor falls through instead of being answered with the password.
- **A pasted password.** `BAD_PASSWORD` now refuses a control character
  ("a pasted line break?"). Storing one produced a secret that could never
  authenticate, and the server's answer to it is identical to its answer to a
  wrong password.

*Enforced by:* `SshTunnelManagerTest` (wrong password, closed port, unresolvable
name, keyboard-interactive-only server), `SshTargetStoreTest` (pasted line
break), `tools/test-policy` #74 and #77.

## D106 — a credential check does not need the data plane
**ACCEPTED.** `connect` requires the public data plane, because it asks the VPS
to forward a port to it. That made the failing case untestable: with the data
plane off, the operator with a fresh public address and password got
`DATA_PLANE_NOT_RUNNING` — a true sentence about Grom that says nothing about
the VPS.

`sshVerify` runs the same session and reports each stage it could actually
verify. The reverse forward is requested **only when the data plane is up**, and
the reply carries `forwardChecked` so the screen can say *"Address, host key and
password accepted. The tunnel was not tested because the public API is off."*
rather than "connected". A check closes its session, registers no tunnel and
changes nothing the operator is looking at; its only write is the host-key pin a
first connect would have made anyway.

*Enforced by:* `SshTunnelManagerTest` ("verify proves the credentials with the
data plane off", "verify reports the forward when the data plane is up"),
`tools/test-policy` #75, S4-R058.

## D107 — many saved servers, one current one, four live
**ACCEPTED.** The store already held many targets, but there was no way to say
which one is *the* server: the UI showed an unordered list and `/v1/status` gave
no hint, so an operator with three VPS hosts had to remember which was which.
And the live-tunnel cap was two, which for three hosts means "forget one to use
another".

The current server is **one id in the file** (`activeId`), not a flag on each
record: two records both claiming it is a state the store must not be able to
reach. The list shows it first and marked, `/v1/status` carries `current: true`
on that tunnel (the id is already in the projection — no address is added, D59),
and deleting it clears the selection instead of leaving a dangling id. Choosing
it is one tap, recorded as `ssh.active`, and it is a **label, not a behaviour**:
nothing auto-connects and no poll is introduced.

The cap becomes **four**. It is a phone cost — a thread, a socket, a session and
a MINA transport each — and the refusal stays named (`TOO_MANY_TUNNELS`).

*Enforced by:* `SshTargetStoreTest` (one id, survives edit, cleared by delete,
unknown id refused), `SshTunnelManagerTest` (cap boundary), `tools/test-policy`
#76.

## D108 — a green test gate must mean every test class ran
**ACCEPTED.** Found while auditing v0.19.0. `tools/run-gates` reported "JVM tests:
OK" while **43 of 838** engine tests had run, and every later inspection of
`engine/build/test-results/` agreed: five result files for 92 test classes.

The cause was not the build cache, which is what an earlier note blamed.
`tools/test-agent` narrows the *same* task —
`:engine:testDebugUnitTest --tests 'app.grom.server.agent.*'` — and Gradle keeps
**one results directory per task**, clearing it at the start of every run. The
filtered run therefore deleted the full suite's evidence and left its own five
files in its place. The gate itself had run everything; the proof had been
overwritten by a later gate. A gate that cannot show what it ran is not a gate,
and "the reports say 43" is exactly the sentence that makes a false green
credible.

Three changes, at three levels:

- **The filtered run writes elsewhere.** `engine/build.gradle.kts` honours
  `-PgromTestReportsDir`; `tools/test-agent` passes
  `test-results/agent-only`, so it can never touch the full run's reports.
- **The gate checks its own evidence.** `tools/run-gates` removes the results
  directories before running the tests (a stale file cannot be mistaken for a
  fresh one) and then calls `tools/check-test-reports`, which compares the test
  classes on disk with the XML Gradle wrote and fails on a subset. It checks
  **classes**, not test counts: `@Ignore`, a parameterised suite or a nested
  class changes the count without changing which classes ran, and a count-based
  rule would drift exactly as the hardcoded labels did.
- **CI runs the same check**, because "BUILD SUCCESSFUL" is printed whether a
  task ran 838 tests or five.

*Enforced by:* `tools/check-test-reports` (proved against a run with five of 92
result files: verdict `FAILED`), `tools/run-gates`, `.github/workflows/ci.yml`,
`tools/test-policy` #78.

## D109 — one Bluetooth switch that belongs to Grom, and one that does not
**ACCEPTED.** v0.19.0 had Bluetooth in the mesh but no way to say "not over
Bluetooth". Turning discovery on started the BLE advertiser, the BLE scanner and
the RFCOMM listener unconditionally, and an operator who wanted discovery over
the LAN alone had to turn discovery off as well — which also stopped the LAN
beacon. The screen listed the four radios but nothing let an operator choose.

Two switches are involved and they are **not** the same thing, which is the whole
of this decision:

- **The system radio** belongs to the operator. Android 13+ (`targetSdk` 33+)
  refuses `BluetoothAdapter.enable()`/`disable()` for a normal app, and Grom will
  not ask for the platform dialog either: nothing in `mesh/` calls `enable()`,
  `disable()`, `ACTION_REQUEST_ENABLE` or `createBond()`, and a policy check now
  keeps it that way. When the radio is off, the screen says so and offers
  Android's own Bluetooth settings page — as a fact to act on, not a button Grom
  pretends to own.
- **Grom's use of it** is `MeshSettings.bluetooth` (default `true`). Off means the
  LE and Classic radios are never started, never scanned and never listed as
  broken: the row says "off — Bluetooth is not used for the server network". The
  switch is persisted with the rest of the mesh settings, applied through the
  existing `meshConfigureJson` (no new AIDL method, no API bump), and turning it
  off also drops the Bluetooth sightings already in the discovery book so a
  switched-off carrier cannot keep offering a pairing that cannot be dialled.

The default is `true` on purpose: discovery has always started these radios, and
an upgrade that silently removed a carrier would break the one thing this
project keeps insisting on — that a change is stated, not hidden.

While implementing it, two defects in the same area were found and fixed rather
than documented around:

1. **The BLE advertisement did not fit.** The adapters added both the 128-bit
   service UUID list (18 bytes) and the 128-bit service data (26 bytes) on top of
   the 3 flag bytes, for 47 of the legacy 31. The stack answers that with
   `ADVERTISE_FAILED_DATA_TOO_LARGE`, and the screen — which only looked at
   `available` — still printed "ready" while nothing was ever transmitted. The
   advertisement now carries the service data only (29 bytes, the data field
   already names the UUID), the scan runs two filters so an older Grom that still
   advertises the list stays visible, and `BleAdvertisement.legacyAdvertisementBytes()`
   makes the arithmetic testable.
2. **A refusal was invisible.** `lastError` was hidden behind `available`, so
   "the adapter refused to start" could not be seen. A recorded failure now wins
   over the capability, keeps the platform's numeric code (`BLE_ADVERTISE_FAILED:1`),
   and is turned into a sentence by the pure model — an unknown code is printed
   verbatim instead of swallowed.

*Enforced by:* `MeshSettingsTest` (default, partial apply, an older file),
`MeshServiceTest` (radios left out, turned back on, measured platform switch,
sightings dropped), `DiscoveryBookTest` (`retain`), `BleAdvertisementTest` (the
31-byte budget), `MeshModelTest` (two facts stay distinct, codes become
sentences), `tools/test-policy` #79, #80.

---

## D110 — an authentication failure reports the methods the server actually offered

"Wrong password" and "this server does not accept passwords" produce the same
`SSH_MSG_USERAUTH_FAILURE` on the wire, and until now the same sentence on the
screen: *"the server rejected the username or the password"*. That is a guess
wearing the clothes of a measurement, and it is the wrong guess for the most
common real cause — Hetzner, Debian and Ubuntu cloud images ship
`PermitRootLogin prohibit-password`, and many add `PasswordAuthentication no`, so
the password is correct and login is refused anyway.

The server names the methods it will accept in its failure reply. The driver
keeps that list in a protected field and only writes it to a debug log, so the
list is captured by observing the reply: `SshAuthMethods` is the driver's own
`ssh-userauth` factory with one overridden hook, per connection. It changes no
authentication behaviour.

Rules:

- The list is a **measurement of one attempt**, filtered to `[a-z0-9-]{1,32}`
  names, de-duplicated, sorted and capped at six, so nothing the server chooses
  can become a long string in a screen, a status document or a log line.
- **Empty means "not measured"**, never "nothing is allowed". A refused TCP
  connection, a handshake failure or a closed session leaves it empty and the
  old generic diagnosis stands.
- `keyboard-interactive` counts as a password login (OpenSSH with PAM answers
  the password there, D105); only a list without both it **and** `password`
  produces `SSH_AUTH_METHOD_NOT_ALLOWED`. An empty list never does.
- The same rule applies to a key: a server that does not offer `publickey` is
  `SSH_AUTH_METHOD_NOT_ALLOWED`, not "the key was rejected".
- The new code carries `authMethods` in the reply; the screen puts the names in
  the sentence. The engine keeps writing its own advice in `message`, and the UI
  still never renders engine prose.
- If a driver upgrade stops calling the hook, the list stays empty and the
  diagnosis degrades to the previous, honest sentence. Nothing else changes.

A measured limit, stated rather than hidden: a server that offers
`keyboard-interactive` still counts as accepting passwords, because OpenSSH with
PAM answers the password there (D105). A host that disables password login but
keeps PAM keyboard-interactive therefore still reports `SSH_AUTH_FAILED` — the
list cannot separate "wrong password" from "PAM refuses this user", and guessing
would be worse than the generic sentence.

*Enforced by:* `SshAuthMethodsTest` (filtering, bound, "not measured"),
`SshTunnelManagerTest` (a keys-only server is `SSH_AUTH_METHOD_NOT_ALLOWED` with
`publickey`; a wrong password stays `SSH_AUTH_FAILED`).

---

## D111 — the forwarded channel may reach the endpoint the data plane is actually bound to

The client-side forwarding filter accepted a `forwarded-tcpip` channel only when
its destination was loopback on the data-plane port. That was written when the
data plane could only be loopback. It cannot be bound that way any more: `LAN`
binds the phone's own site-local address (it is what the mesh needs), and
`PUBLIC` binds the wildcard.

So with the data plane on the LAN address — a documented, recommended setup —
the tunnel connected, reported `CONNECTED`, and then refused **every** incoming
connection, because the destination was `192.168.x.y:47002` and the filter
wanted `127.0.0.1:47002`. The VPS port accepted TCP and immediately got nothing.
A green lamp over a dead tunnel is the exact failure this project treats as a
bug, not a limitation.

The filter now allows exactly **one** endpoint: the address and port this
connection asked to expose, taken from our own request and never from the
server. Loopback spellings (`127.0.0.1`, `localhost`, `::1`) are the same
endpoint; everything else must match the requested host exactly. Agent
forwarding, X11, local listens and `direct-tcpip` remain refused, and a
connection with no data plane has no endpoint, so it accepts nothing.

This is not a widening of trust: the destination is chosen by `localDataPlane()`
on the device, not by the peer, and the port is still the only port that can be
reached.

*Enforced by:* `SshForwardingPolicyTest` (loopback, a LAN destination, a foreign
host, a foreign port, a null endpoint), `SshTunnelManagerTest` (bytes really flow
through a tunnel whose data plane is bound to the LAN address).

## D112 — the embedding guide is a build, and the engine resolves the agent beside itself

`GromEmbed` was the documented way to ship "2 in 1", and nothing had ever built a
host from that documentation. A host project that followed it verbatim failed
twice before it produced an APK:

```
A problem was found with the configuration of task ':engine:syncAgentRuntime'
  - property 'sourceFile' specifies file '<host>/agent/grom-agent.js'
    which doesn't exist
```

`engine/build.gradle.kts` read the agent script from `rootProject`. That is Grom's
root only while Grom *is* the root project; in a host it is the host's root, and
the script is not there — it sits beside the engine module, wherever that module
lives. The path is now `layout.projectDirectory.file("../agent/grom-agent.js")`,
the same value inside this repository and correct in every other root.

The second failure came later, when the APK was packaged:

```
Execution failed for task ':app:mergeDebugJavaResource'.
> 2 files found with path 'META-INF/DEPENDENCIES' from inputs:
   - org.apache.sshd:sshd-core:2.13.1/sshd-core-2.13.1.jar
   - org.apache.sshd:sshd-common:2.13.1/sshd-common-2.13.1.jar
```

Those files are merged by the module that packages, so the engine cannot pick
them for its consumer. A `packaging { resources { pickFirsts ... } }` block was
added to `:engine` and the same failure came back unchanged — measured, not
assumed — which means a library-side "fix" would have been a silent lie. The
pickFirsts therefore live in the host build file and in the guide, next to
`jniLibs.useLegacyPackaging`, which is host-side for the same structural reason.
Policy #85 keeps a `packaging` block out of the engine, so nobody can remove the
host contract believing it is redundant.

Why neither was noticed: `:app` builds from Grom's own root (where `rootProject`
is correct), the C-008 test apps depend only on `:sdk`, and `GromEmbed` had **no
caller outside its own tests** — the reference application talks to `GromService`
directly. A guide with no build cannot be wrong in a way a gate sees.

Three things changed so that it can be:

1. **`examples/embedded`** — a compiled module that depends on `:engine` the way a
   host does, calls the documented API, and pins the sentences and the consent
   rule in JVM tests. It is built and tested by `tools/run-gates` and CI.
2. **`tools/check-embed`** — the gate. It copies `engine/`, `sdk/` and `agent/`
   into a temporary root, deliberately leaves **no** `agent/` at that root's top
   level (the layout the old line could not survive), writes the host from the
   guide, and builds its APK. It asserts the agent asset is packaged, the service
   stays `exported="false"`, and the host needed no IPC permission.
3. **The guide measures.** The engine and its dependencies add **~5.6 MB** to a
   host APK without the runtime, not the "~2–3 MB" it claimed; the merged manifest
   is listed in full, because the mesh has brought Bluetooth and Wi-Fi Direct
   entries since the paragraph that said the engine adds no such permission was
   written.

*Enforced by:* `tools/check-embed` (isolated root, host APK), `examples/embedded`
(built and tested in `tools/run-gates` and CI), `tools/test-policy` #83 (the
module-relative path and the gate's existence), #84 (the example is wired into
the gate, the test-report check and CI), #85 (no library-side packaging block).
