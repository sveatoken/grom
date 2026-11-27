# Grom

**A universal, lightweight Android server — the phone as an agent, never as an
authority for money.**

Grom exposes a small, authenticated, loopback-only control API and a
signature-protected Binder surface on an Android device. It supervises a Node
agent, records every state change in a hash-chained audit log, and refuses to
believe anything about itself that is not true. A second, separately configured
public data plane is available but off by default.

[![CI](https://github.com/sveatoken/grom/actions/workflows/ci.yml/badge.svg)](https://github.com/sveatoken/grom/actions/workflows/ci.yml)

```
grom/
├── app/                    Android app, two role flavours
│   ├── src/main/           shared: monochrome UI, manifests, resources
│   ├── src/server29/       the server role manifest (service, boot receiver)
│   ├── src/peripheral26/   the peripheral role (no socket, no service)
│   └── src/test/           JVM tests for all policy logic
├── agent/                  grom-agent.js, the supervised Node agent
├── sdk/                    app.grom.sdk (AIDL) + app.grom.sdk.connect (HTTP client)
├── examples/connect/       the compiled Kotlin client example (S6)
├── testapps/               samecert / foreigncert — the C-008 evidence
├── web/                    the static download page (GitHub Pages, D49)
├── docs/                   000-decisions, specs S1–S8, F1 plan, master plan, roadmap, guides
├── tools/                  gates, plus grom-fleet, grom-hub and grom-provision
├── scripts/                test keys and the arm64 Node runtime pipeline
├── third_party/            the dependency ledger
└── keys/                   TEST-ONLY signing material (see D4)
```

## Status

F1, the walking skeleton, is **complete**, and the agent runtime is real:
`agent/grom-agent.js` implements the protocol, the app installs it from its own
APK, and `POST /grom/v1/agent/exec` is proxied to the agent instead of being
answered with a fabricated `ok`. Release APKs bundle the verified arm64 bionic
Node runtime (D34, D35, D38), so an installed release starts the agent without
any extra operator step.

| Item | State |
|---|---|
| `libnode.so` for arm64 | **closed** — produced by `scripts/fetch-node-android.sh`, verified by execution, bundled into release APKs |
| Q8: `node:sqlite` on arm64 | **closed** — works on bionic Node 24.22.3; `--verify` runs a real SQLite round trip |
| Nothing has run on a physical device | **open** — no `adb` in the build environment; boot receiver, vendor task-killers and battery policy are unverified on hardware |
| Production signing key | **open** — releases use the committed test key (D4) and say so |

The server is now an **embeddable library** (`:engine`, D55) and the app is one
possible host. The operator surface is a measured dashboard that keeps only the
state, Start/Stop and the "Now serving" lamps, with every configuration section
one tap away behind the header menu (D56, D68). Apps can register themselves as
projects (D57), and an operator can open a reverse SSH tunnel to a VPS without a
second process (D58).

| Item | State |
|---|---|
| Embedding (`GromEmbed`, API 3) | **delivered** — `:engine` + `:sdk`, consent gate, project registry |
| Dashboard lamps | **delivered** — pure `DashboardModel`, green only for measured values |
| SSH bridge | **delivered in code** — TOFU pin, `tcpip-forward` only, one-destination filter, sealed credentials; proven against a real SSH server in JVM tests |
| SSH bridge on a device | **open** — never run on hardware, like the rest of the server |
| Devices (identity, pairing, fleet) | **delivered in code** — one Ed25519 key per phone, mutual pairing with fingerprints, scoped peers, measured lamps (D70–D75) |
| Devices on a device | **open** — pairing has never been exercised between two physical phones |
| Doctor (why Android stops the server) | **delivered in code** — measured checks, honest `unknown`, run marker, API 8, three booleans in `/v1/status` (D80–D84) |
| Doctor on a device | **open** — no physical phone has been measured |
| "2 in 1" (host app ships Grom) | **delivered in code** — host-chosen or automatic control port, versioned consent, host-readable health, host-configured data plane (API 9), a ~1.6 MB release peripheral build (2.2 MB debug); guide in `docs/TWO-IN-ONE.md` (D85–D90) |
| Client connect (S6) | **delivered in code** — `GET /grom/v1/connect`, `services.read`/`services.call` with per-op allow-lists, a dependency-free Kotlin client and a compiled example (D91–D93) |
| AI gateway | **delivered in code** — one local AI API over your own keys: OpenAI/Anthropic/Gemini protocols, seven vendor presets plus any custom endpoint, a sealed-key store, a model catalogue, OpenAI- and Anthropic-compatible routes with streaming, `ai.read`/`ai.chat` scopes, API 10; guide in `docs/AI.md`, contract in `docs/specs/S7-ai-gateway.md` (D94–D97) |
| AI gateway against a live provider | **open** — every shape is proven against a real loopback provider and fakes; a live vendor call needs the operator's own key (`GROM_AI_LIVE_*`) |
| Server network (mesh) | **delivered in code** — signed LAN beacon, BLE and Wi-Fi Direct discovery, Bluetooth Classic as a secure RFCOMM carrier, an address-free roster, a scoped relay to another phone's services and AI gateway, API 11; guide in `docs/MESH.md`, contract in `docs/specs/S8-server-mesh.md` (D99–D104) |
| Server network on a device | **open** — no physical phone has run the Bluetooth or Wi-Fi Direct radios; two and three servers are proven over real loopback sockets |

When the runtime is genuinely absent, the app reports `MISSING_RUNTIME` and the
agent endpoints answer `503` naming the missing file. That is the designed
behaviour (D31), not a bug.

## AI gateway

Grom can be the AI endpoint for the apps you trust, using **your** provider keys:
OpenAI, Anthropic, Gemini, DeepSeek, Qwen, Kimi, OpenRouter, or any endpoint that
speaks one of the three protocols. The phone holds the key, you choose the models,
and a connected app gets a catalogue — never a credential.

```
GET  /v1/ai/models             ai.read    the callable catalogue
POST /v1/ai/chat/completions   ai.chat    OpenAI shape, streaming or not
POST /v1/ai/v1/messages        ai.chat    Anthropic shape
```

Set it up in **Menu → AI gateway** (provider, key, model, **Test**), then grant a
key `ai.read`/`ai.chat` in **Devices → Trust a key**. Read
[docs/AI.md](docs/AI.md) for the operator guide and
[docs/specs/S7-ai-gateway.md](docs/specs/S7-ai-gateway.md) for the contract.

## Install

One tap, no GitHub account:

**https://sveatoken.github.io/grom/**

The page resolves the newest release, explains the three install steps
(including "Allow from this source") and carries an invite code if someone
shared a `?ref=` link. Direct links, if you prefer them:

```bash
adb install -r grom-server29.apk          # full server role, Android 10+
# or
adb install -r grom-peripheral26.apk      # client role, Android 8+, never binds
sha256sum -c SHA256SUMS                   # optional integrity check
```

Both roles share one application ID and are mutually exclusive installs
(S1-R007). Releases are signed with the committed test key: installable and
self-updatable, but not proof of publisher identity.

## Invite & Earn

Each install generates a local invite code and shares
`https://sveatoken.github.io/grom/?ref=<CODE>`. **Copy link** and **Share
invite** use the Android share sheet; a friend's code can be pasted under
**Invite & Earn → Invited by**. Everything is local: there is no referral
server, no account, no attribution call and no device identifier (D50). The
rewards badge says **"Crypto rewards — coming soon"** and nothing is paid
(D51).

## Updates

The app has an **Update** card with a single **Check now** action. It performs
one HTTPS GET to the public GitHub release feed only when tapped — no scheduler,
no background work, no account, no telemetry (S1-R003, S1-R033, D37). A newer
release shows its version and a **Download APK** button that opens the matching
flavour asset in the browser. A failed check is reported as a failure, never as
"up to date". Grom never downloads or installs an APK itself.

Tagging is the release process: `git tag v0.6.0 && git push origin v0.6.0`
runs `.github/workflows/release.yml`, which builds both roles with the runtime
inside, verifies the contents and signature, and publishes
`grom-server29.apk`, `grom-peripheral26.apk` and `SHA256SUMS` under stable
names so `releases/latest/download/` links never break (D49). Full procedure:
[`docs/RELEASING.md`](docs/RELEASING.md); the page itself:
[`docs/DOWNLOAD.md`](docs/DOWNLOAD.md).

## Build

Pinned toolchain: AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, JDK 17, Gradle 8.14.3.

```bash
gradle :app:assembleServer29Debug
gradle :app:assemblePeripheral26Debug
gradle :app:testServer29DebugUnitTest
```

The server role is `server29` (minSdk 29). The peripheral role is
`peripheral26` (minSdk 26) and never binds a socket.

Release builds with the runtime (what the release workflow does):

```bash
scripts/fetch-node-android.sh --all
scripts/build-node-android.sh --install node-out/runtime
gradle :app:assembleServer29Release :app:assemblePeripheral26Release
```

## Conformance

```bash
tools/run-gates                           # all 13 gates, in order
gradle :engine:testDebugUnitTest          # engine: supervisor, HTTP, data plane, SSH
gradle :app:testServer29DebugUnitTest     # app: dashboard projection, update check
gradle :app:lint                          # lint, 0 errors required
tools/check-third-party                   # dependency ledger matches the build
tools/test-policy                         # 42 static invariants
tools/test-agent                          # secrets go over stdin, not argv/env
tools/test-agent-runtime                  # grom-agent.js protocol + degradation
tools/check-site                          # download page: static, no third parties
tools/verify-ipc-signing                  # C-008, needs the three APKs
tools/test-fleet                          # tools/grom-fleet protocol tests
tools/test-hub                            # tools/grom-hub protocol tests
tools/test-provision                      # tools/grom-provision tests, no phone needed
```

`verify-ipc-signing` needs:

```bash
gradle :app:assembleServer29Debug \
       :testapps:samecert:assembleDebug \
       :testapps:foreigncert:assembleDebug
```

GitHub Actions runs the same set on every push and pull request
(`.github/workflows/ci.yml`), plus the agent suite on Node 22 and 24 and the
download-page gate as its own job.

## Using it

The server listens on `127.0.0.1:47001` only. The token is created on first run
and lives in app-private storage.

```bash
TOKEN=$(adb shell run-as app.grom.server cat files/grom/grom-token | tr -d '\r\n')
adb forward tcp:47001 tcp:47001

curl -s http://127.0.0.1:47001/grom/v1/health
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:47001/grom/v1/status
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:47001/grom/v1/audit/verify
```

## Fleet view (several devices, one table)

`tools/grom-fleet` reads the public data plane of the Grom servers you run and
prints one table with a lamp per device. It is read-only, needs only a JDK 17,
and changes nothing in the app:

```bash
cp tools/fleet/fleet.example.json fleet.json   # then edit it
tools/grom-fleet --config fleet.json
tools/grom-fleet --config fleet.json --json | jq .
tools/grom-fleet --config fleet.json --watch 30
```

A lamp is green only when the device answered **and** reported `running: true`;
`stopped`, `refused` and `unreachable` are red, and a deliberately disabled
public API is grey. A plaintext `password` field in the config is refused: the
password comes from an environment variable or a file, and key auth signs each
request locally with an Ed25519 key the phone never receives.

Full setup, the config schema and the security notes: **`docs/FLEET.md`**.

## Devices (pair your other phones)

The app can pair with your other devices without any account. Every phone has
its **own** Ed25519 key (the seed is sealed with the Android Keystore); pairing
is mutual, and both screens must show the same fingerprint before anything is
stored. A paired device may read `/v1/status` and, if granted, the sanitised
config — nothing else.

Open **Menu → Devices**. The list has a lamp per device: green only after a
**measured** signed request succeeds, red when the last probe failed, grey when
there is no measurement. Probes run when you open the screen or tap Refresh;
Grom does not poll in the background.

The honest limits: Grom signs its traffic but does not encrypt it (D47), there
is no global revoke because there is no account, and pairwise pairing does not
scale past a handful of devices.

For a reader that only calls in — a hub on your VPS — use **Devices → Trust a
key**: paste its public key and choose scopes. It gets no address, is never
dialled, and stays a scope-limited peer.

Full guide: **`docs/CLUSTER.md`**.

## Hub (several phones behind any network)

`tools/grom-hub` runs on your VPS and polls the phones through the reverse SSH
tunnels they already open, so it works behind CGNAT without exposing a single
port. It serves one loopback roster and changes nothing in the app:

```bash
tools/grom-hub identity --init                       # mode-0600 key, prints the public key
tools/grom-hub check --config hub.json               # one pass, table, exit code
tools/grom-hub run   --config hub.json               # roster on 127.0.0.1:47100
```

Each phone needs its **own** forward port (41001–41099); the store refuses a
duplicate and the tunnel reports `SSH_FORWARD_REFUSED` distinctly. The roster
carries a label, node id and measured state — never a port, address or key. If
the hub is down, the phones keep working and only the roster is missing.

Full guide: **`docs/HUB.md`**.

## Server network (phones that find each other)

The hub is central: one VPS reads many phones. The **server network** is the
opposite shape — phones that find each other directly, with no VPS and no
account. Each phone stays a complete server; the network adds discovery, a
roster and a relay:

1. **Public access** → enable, bind **LAN** (or a VPN interface), auth **Key** or
   **Both**.
2. **Server network** → **Turn discovery on** (the screen asks for the nearby
   devices permissions it needs). The section has its own **Bluetooth** switch:
   turning it off keeps discovery on over the LAN and Wi-Fi Direct. It does not
   touch the system radio — Android 13+ will not let an app do that, so when the
   phone's Bluetooth is off the screen says so and opens the system settings.
3. Tap a nearby phone → **Pair**, compare the fingerprint on both screens, and
   enter the code the other phone shows.

Then each phone lists the others with a measured lamp and the services they
publish, and one scoped call reaches them:

```http
POST /v1/mesh/call
{"server":"0123456789abcdef","service":"agent","op":"ping","args":{}}
```

Carriers are chosen by capability: LAN/VPN over ordinary TCP, Wi-Fi Direct for
a group with no router, **Bluetooth Classic** as secure RFCOMM carrying the same
HTTP request to a bonded phone, and **BLE for discovery only** — its eight-byte
advertisement cannot carry a request. Discovery is a hint, never trust: only the
existing pairing creates a peer, `mesh.read`/`mesh.call` never cross a pairing,
and the roster never contains an address. If nothing is enabled, nothing is
scanned and nothing is advertised.

Full guide: **`docs/MESH.md`**.

## Preparing phones from a computer

`tools/grom-provision` gets a phone from first plug-in to "running Grom with its
own forward port", from the machine with the USB cable. It only reads the
device, installs the APK you point it at, and asks for a battery exemption:

```bash
tools/grom-provision list                                    # what is connected
tools/grom-provision plan  --apk grom-server29.apk           # what apply would do
tools/grom-provision apply --apk grom-server29.apk --flavour server29
tools/grom-provision emit  --hub hub.json                    # config for grom-hub
tools/grom-provision doctor                                  # what an app cannot read
```

Every result is read back from the phone: the installed `versionName` (so the
wrong role flavour is caught), the battery-whitelist entry (so a vendor that
ignored the request is reported, not claimed), and the port that `emit` turns
into a hub config. It never removes a package, wipes data or restarts a phone,
and it is scanned for exactly that.

Full guide: **`docs/PROVISION.md`**.

## Doctor — why Android stops the server

**Menu → Doctor** answers the question the dashboard cannot: *why*. It measures
the battery exemption, background restriction, standby bucket, notification
state, free space and the platform's own exit reason, and it reports `unknown`
in words where Android does not answer. A lamp is green only for a value the
phone actually returned, and the headline cannot say "all pass" while one row is
unmeasured.

It changes nothing on its own: the one system change it can *request* is
Android's own battery-exemption dialog. The deeper facts — the Doze whitelist,
the phantom-process limit, app-ops — are shown as commands you run on a computer,
because an app cannot read them and Grom will not grow a shell to try.

`tools/grom-provision doctor` reads those same facts over `adb`; its `--fix`
covers exactly two of them (the Doze whitelist and, on API 28 and older, the
legacy Wi-Fi sleep policy).

Full guide: **`docs/DOCTOR.md`**.

## Embedding Grom in your app

Grom is a library first and an app second. An application that depends on
`:engine` gets the whole server inside its own package — no second install, no
IPC permission — and starts it with one call after the operator consents:

```kotlin
GromEmbed.recordConsent(context)
GromEmbed.start(context, GromEmbed.Options("my-wallet", "My Wallet", "1.2.0"))
```

The project then appears in **Local projects** with a lamp driven by its real
heartbeat (30 s, three misses to go stale). Local data goes through the typed
handle, and the engine adds the namespace prefix itself:

```kotlin
val project = GromEmbed.newProject(client, "my-wallet")
when (val write = project.put("theme", "dark")) {
    is GromProject.LocalWrite.Ok -> { /* stored */ }
    GromProject.LocalWrite.StorageUnavailable -> show("local storage unavailable")
    is GromProject.LocalWrite.Rejected -> show("${write.code}: ${write.message}")
}
```

That namespace (`p:<projectId>:<key>`, API 4 `projectExec`) is an ownership gate
on the API, not a sandbox between apps in the same UID. See
[`docs/EMBEDDING.md`](docs/EMBEDDING.md) for the IPC surface (API 4 to 10), the
runtime requirement and the trust-boundary cost.

Shipping "2 in 1" — the app and the server in one package — has its own guide:
[`docs/TWO-IN-ONE.md`](docs/TWO-IN-ONE.md). It covers the control port a host
chooses — or lets the engine choose (D85, D89), consent about a specific notice
(D88), the one-call health a host can paint without parsing anything (D90),
configuring the public data plane through the Binder (D86), the ~38 MB runtime
and the peripheral build that ships none of it (D87), the Android 12+
foreground-service rules, the battery exemption, and what happens when the
operator also has the standalone Grom installed.

## Using Grom from another app (client connect)

Another application does not need the AIDL surface — and must not be given an
operator key. It connects to the public data plane over the same HTTP/JSON the
project already speaks:

```
GET  /grom/v1/connect        → schema, api, auth methods, scopes, endpoint map
POST /v1/auth/login          → password  → session token
   or Ed25519-signed requests → the app's own key, trusted on the phone
GET  /v1/plugins             → services, capabilities, scopedOps   (services.read)
POST /v1/plugins/{id}/rpc    → one op the service publishes        (services.call)
GET  /v1/status              → measured status                     (status.read)
```

The discovery document is anonymous on the base level and adds the service list
only for a valid credential; an invalid credential is `401`, never a silent
downgrade. A client is never an operator, and `services.call` reaches only the
operations a service lists in `scopedOps` — the Lightning adapter lists none.

The thin, dependency-free client lives in `:sdk`:

```kotlin
val client = GromConnectClient("http://127.0.0.1:47002")
val info = client.connect()                       // D91
if (!info.supports()) return show("Grom is newer: ${info.gromVersion}")
val session = client.session(client.login(password).token)
for (service in client.services(session)) {        // services.read
    println("${service.id}: ${service.scopedOps}")
}
val answer = client.rpc(session, "agent", "kv.put", mapOf("key" to "k", "value" to "v"))
```

Full contract: [`docs/specs/S6-client-connect.md`](docs/specs/S6-client-connect.md).
Working example: `examples/connect` (compiled and tested in the gates).

## How Grom works

The page holds only the server: the status headline with **Start/Stop** and the
**Now serving** card with its measured lamps. Everything else — external
servers, Nostr & Lightning, local projects, alerts, public access, supervision,
updates, the invite card and this explanation — opens from the **Menu** button
in the top right, next to the theme switch, in that order.

"How Grom works" is the last entry in that menu. It explains in plain language
what Grom does, why most people need no rented server, when an external SSH
server helps, what the Nostr/Lightning and local-project rows mean, how the
Telegram alerts work, and — last — what Grom cannot do when the phone is off or
offline. It is static, reports no state, and cannot claim anything is running.

## Telegram alerts

**Telegram alerts** sends a short message to the operator's own chat when
something changes: the server started or stopped, an external server connected,
failed or changed its host key, an app registered, or the agent crashed and was
quarantined.

Enter a bot token (from @BotFather) and a numeric chat ID, switch alerts on, and
press **Send test message**. The token is sealed by the Android Keystore and is
never returned by any API; the message text comes from a fixed table and carries
an event code and, for project events, a project id — never a token, a chat ID, an
SSH host, a relay, a balance or free-form exception text.

Delivery is bounded: two attempts per message, a 16-message queue, coalescing
within 10 s, at most 20 messages a minute, no scheduler and no disk queue. When
the process is gone, nothing is sent — Grom is the sender. See
[`docs/NOTIFICATIONS.md`](docs/NOTIFICATIONS.md).

## Reverse SSH bridge

**External servers** opens a reverse tunnel from the phone to a VPS you own. It
is the answer to CGNAT: the device dials out, the VPS gets a loopback port that
reaches Grom's data plane. The session may only request `tcpip-forward`; there
is no shell, no exec and no agent forwarding, the host key is pinned on first
use, and the client-side filter allows one destination — the local data plane.
Credentials are generated (Ed25519) or pasted, and always sealed by the Android
Keystore.

Keep **as many servers as you like** and mark one as your current one. A failure
names the step that broke — name, connect, handshake, password or forward port —
so a wrong password is never reported as an unreachable host. **Test
connection** checks the address, host key and password *without* the public data
plane, which is exactly the state you are in when a new VPS refuses you. Password
logins also answer the single non-echoing `keyboard-interactive` prompt that
OpenSSH asks through PAM.

The tunnel's destination is wherever the data plane is actually bound — loopback,
the phone's LAN address (what the server network needs) or the wildcard — and
nothing else: the address comes from the phone, never from the VPS. When a login
is refused, Grom reports the methods the server itself said it accepts, so
"this server does not take passwords (it offers: `publickey`)" is not reported as
a wrong password. [`docs/SSH-BRIDGE.md`](docs/SSH-BRIDGE.md) §7a is the
field guide for a real VPS: Hetzner and cloud Debian images disable password
login for `root` by default, and the fix is one tap to a generated key.

## Super server (public API)

The control plane above is loopback-only and cannot be moved. Grom also has a
separate **data plane**, the super server, on `/v1/*` (default port `47002`).
It is **off by default and loopback-only** until an operator changes it.

Enable it in the app's **Public API** card: *Enable*, *Bind* (loopback / LAN /
all interfaces), *Auth* (password / key / both), *Password*, *API keys*
(Ed25519), *Lightning*, and the three reference rows below. Enabling needs a
password or a key; a non-loopback bind is refused without a credential, and
*all interfaces* binds `0.0.0.0` only after an explicit acknowledgement. Changes
are applied by the running server through `reloadSuperServer()` (IPC API 2);
changing anything except the reference rows invalidates existing sessions.

### Reference config for an external bridge (D52–D54)

The **Nostr npub**, **Nostr relays** and **Remote bridge** rows store
configuration for a bridge that runs elsewhere (for example a Mostro VPS). Grom
**validates and reports** them; it never signs a Nostr event, never opens a
relay connection and never starts a tunnel:

| field | accepted | what Grom does |
|---|---|---|
| `npub` | NIP-19 `npub1…` (32-byte bech32) or 64-char hex, stored canonical | reports it |
| `relays` | up to 8 `ws://` / `wss://` URLs, no userinfo/query/fragment | lists them |
| `remoteBridge` | label ≤80, description ≤240, no secret-looking text | labels a tunnel you run |

They appear in the **authenticated** `GET /v1/status` (and the `agent` plugin's
`status` op) as `npub`, `relays`, `remoteBridge` and `lightningHealth`, and in
the **Copy config** document — an allow-list that never contains a password
hash, key material or the sealed backend credential (D53). Changing only these
three fields reloads in place, so sessions and SSE streams survive (D54). The
unauthenticated `/v1/health` never carries them.

The tunnel pattern (reverse SSH, WireGuard/Tailscale, CGNAT, TLS) is documented
in [`docs/SUPER-SERVER.md`](docs/SUPER-SERVER.md) §10.

| endpoint | purpose |
|---|---|
| `GET /v1/health` | unauthenticated liveness, no secrets |
| `POST /v1/auth/login` | exchange a password for a session token |
| `POST /v1/auth/logout` | revoke the session token |
| `GET /v1/status` | address, limits, plugin inventory, agent state |
| `GET /v1/plugins` | registered plugins and their capabilities |
| `GET /v1/plugins/{id}` | one plugin |
| `POST /v1/plugins/{id}/rpc` | `{"op":"...","args":{...}}` |
| `GET /v1/events` | server-sent events |

Auth is `PASSWORD`, `KEY` or `BOTH`. Passwords are stored as PBKDF2-SHA256
hashes and exchanged for 12 h session tokens; signed requests use Ed25519 over a
six-line canonical string with a timestamp and a nonce. The only plugins are the
supervised `agent` and the `lightning` adapter: Lightning is an operator-run
LNbits backend reached over https, with its API key sealed by the Android
Keystore. Grom custodies nothing and never fabricates a balance or an invoice.

Grom speaks plain HTTP in-process; put a TLS terminator or an encrypted tunnel
in front before exposing the port. The data plane has JVM tests only — nothing
has been run on a device for this feature.

Spec: [`docs/specs/S5-super-server.md`](docs/specs/S5-super-server.md). Operator
guide: [`docs/SUPER-SERVER.md`](docs/SUPER-SERVER.md). Lightning:
[`docs/LIGHTNING.md`](docs/LIGHTNING.md).

## The agent runtime

Grom does not download anything at runtime (D16). The agent script
(`agent/grom-agent.js`) is packaged into the APK and copied into app-private
storage on first start; the Node binary runs from the APK's native library dir,
because Android will not `execve()` a file in app-private storage (D34):

```
<app native lib dir>/libnode.so     the runtime, executable (packaged from jniLibs)
<app files>/grom/node/grom-agent.js the agent entry point (from the APK)
```

```bash
scripts/fetch-node-android.sh              # pinned bionic arm64 runtime + verify
scripts/fetch-node-android.sh --verify     # runs it: version, node:sqlite (Q8), agent suite
scripts/build-node-android.sh --install     # into app/src/main/jniLibs/arm64-v8a
scripts/build-node-android.sh --status      # is this host able to build/run?
scripts/build-node-android.sh --q8          # the Q8 probe commands
```

The agent binds its own ephemeral loopback port and announces it with
`GROM_AGENT_READY <pid> <port>` after `GROM_READY <pid>`. Ops are `ping`,
`echo`, `status`, `kv.get`, `kv.put`; if `node:sqlite` is missing, `kv.*`
answers `503 STORAGE_UNAVAILABLE` rather than pretending to persist.

The repository itself holds no binaries (D35); `jniLibs/` is a build input and
stays out of git. Details and measured evidence: `docs/RUNTIME.md`.

## Design

The operator surface is deliberately monochrome: white on black and black on
white, one neutral surface, hairline borders, and colour used only for state.
The mode is an explicit **Auto / Light / Dark** choice (D36). The status card is
the largest element on the screen because "is it running?" is the only question
the app has to answer at a glance.

## Security model, in one paragraph

The control plane binds loopback and fails closed on anything else. Every
control-plane endpoint except health requires a 256-bit bearer token compared in
constant time. The optional data plane is off by default, can be bound to the
LAN or to every interface only with a credential, and requires an explicit
acknowledgement for the wildcard; it has no TLS in-process, so it is meant to sit
behind a terminator or tunnel. The Binder surface is gated by a `signature`
permission *and* a runtime signature comparison, so only apps signed with the
same certificate get a Binder at all. Secrets reach the agent over stdin, never
argv or environment. Every state change is appended to a SHA-256 hash chain that
can be replayed to detect tampering. Three agent crashes in ten minutes
quarantine it, and only a human can clear that.

Full rationale: `docs/000-decisions.md`. Normative contracts:
`docs/specs/S1`–`S5`.

## History

This tree is a post-migration rebuild; the original working copy was lost.
`docs/RECOVERY.md` states exactly what was recovered, what was re-derived, and
how to graft forward if the original ever resurfaces.

## Licence

Apache-2.0. See `LICENSE`. Test keystores under `keys/` are public by design
and must never sign a distributed build.
