# Embedding Grom in another Android app (D55)

Grom is two artifacts:

| Artifact | What it is |
|---|---|
| `:engine` | the whole server as an Android library (`app.grom.engine`) |
| `:app` | the operator surface plus the two role flavours |

The shipped Grom app is just the reference host. Any application can depend on
`:engine` and get the same server — the same supervisor, loopback control plane,
public data plane, audit chain, project registry and SSH bridge — inside its own
package, without a second installation and without an IPC permission.

This document is the API reference. The shipping guide — ports, lifecycle,
permissions, APK size, coexistence with a standalone Grom and the operator-facing
honesty rules — is `docs/TWO-IN-ONE.md`.

---

## 1. Add the module

`settings.gradle.kts` of the host project:

```kotlin
include(":sdk")
include(":engine")
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":engine"))
}
```

The engine's manifest is merged automatically. It declares:

* `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `POST_NOTIFICATIONS`;
* `GromService` with `android:exported="false"` and **no** IPC permission.

That default is deliberate. An embedded server is private to the host. If a host
wants to expose the Binder to another of *its own* apps, it must declare the
service again with `exported="true"` and a `signature` permission, exactly as the
standalone `server29` flavour does.

The Node runtime binaries are **not** part of the library: `execve` requires the
file to live in the hosting APK's `nativeLibraryDir` (D34). Add them to the host
app with `scripts/build-node-android.sh --install-dir <host>/app/src/main/jniLibs`.
Without them the server runs and honestly reports `MISSING_RUNTIME`; `kv.*`
returns `503 STORAGE_UNAVAILABLE`.

That is ~38 MB of APK and ~91 MB installed, arm64 only. Grom's own peripheral
role ships none of it (D87), and a host should apply the same rule to a variant
that never starts the agent.

---

## 2. Ask the operator once

A server is a persistent process. The engine refuses to start without recorded
consent, so the host must show its own explanation first:

```kotlin
if (GromEmbed.consentRecord(context) == null) {
    showDialog(
        title = "Run a local server?",
        message = "This app can run Grom, a local server, while it is open. " +
                  "Nothing is exposed to the internet unless you enable it."
    ) { granted ->
        GromEmbed.recordConsent(context, noticeVersion = NOTICE, granted = granted)
    }
}
```

The consent record lives in the host app's private preferences. It is not a server
setting and it is not transmitted anywhere.

**Version the notice (D88).** Pass the same `noticeVersion` to `start`:

```kotlin
GromEmbed.start(context, GromEmbed.Options(projectId, title, noticeVersion = NOTICE))
```

If the operator answered about different text, `start` returns
`CONSENT_OUTDATED` and the host shows the notice again. A host that never
versions (`recordConsent(context, granted)` and no `noticeVersion`) keeps the old
behaviour. `GromEmbed.consentRecord(context)` returns the stored
`(noticeVersion, grantedAtMs)`.

---

## 3. Start and register (one call)

```kotlin
val result = GromEmbed.start(
    context,
    options = GromEmbed.Options(
        projectId = "my-wallet",           // ^[a-z][a-z0-9-]{1,31}$
        projectTitle = "My Wallet",        // <= 64 chars
        projectVersion = "1.2.0",
        capabilities = setOf("kv", "lightning.ref"),
        heartbeat = true,                  // repeat the announcement (default)
        heartbeatIntervalMs = 30_000,      // clamped to 5 s … 10 min
        controlPort = GromEmbed.AUTO_PORT, // D89: a free 47100..47199 port
        noticeVersion = NOTICE             // D88: the text the operator saw
    )
) { registered ->                      // runs on a background thread
    if (!registered) Log.w("Grom", "project registration did not complete")
}

if (!result.requested) {
    // result.reason is a stable code; §9 of TWO-IN-ONE.md maps it to an action.
    Log.e("Grom", "start refused: ${result.reason} (${result.detail})")
}
// result.port is the port the server was asked to serve.
```

`AUTO_PORT` is opt-in and never silent: the chosen port is persisted and returned,
a previously chosen free port is reused, and a running listener is never moved
(`PORT_CHANGE_RESTART_REQUIRED`). Use an explicit number (`47101`) instead if the
host has its own configuration screen.

`start` returns as soon as the platform accepts the foreground-service start.
`requested = true` does **not** mean the agent is ready: the runtime may be
missing. Ask the server for the truth:

```kotlin
val client = GromEmbed.newClient(context)   // Target.EMBEDDED
client.bind(object : GromClient.Listener {
    override fun onConnected(apiVersion: Int) {
        // Compare against GromClient.API_VERSION rather than a literal: a host
        // that hardcodes it goes stale every time the contract grows (D29).
        if (apiVersion < GromClient.API_VERSION) { /* older server; degrade */ }
    }
    override fun onDisconnected(reason: String) { /* show the reason */ }
})
val status = client.call(null) { it.statusJson() }
```

**Android 12 and later.** `startForegroundService` from the background throws
`ForegroundServiceStartNotAllowedException`. `GromEmbed.start` catches it and
returns `requested = false` with `FOREGROUND_START_NOT_ALLOWED` (or
`START_REFUSED` for any other refusal), but the call should
not be made from that position in the first place: call `start` from a visible
`Activity` (or from a user-initiated broadcast), never from
`Application.onCreate` or a background `WorkManager` job. A host that needs a
boot start must use its own `BOOT_COMPLETED` receiver and explain the
exemption, exactly as the standalone `server29` flavour does.

`GromEmbed.newClient` uses the host's own package; the signature gate passes by
construction because caller and service are the same UID.

### Health in one call (D90)

A host that does not want to parse `statusJson` and translate Doctor codes can ask
for the projection instead:

```kotlin
GromEmbed.health(context) { health ->            // background thread
    // health.state: RUNNING | STARTING | ATTENTION | STOPPED | UNKNOWN
    // health.headline: stable code, e.g. SERVER_RUNNING or BATTERY_OPTIMISED
    // health.advice: at most four (code, EmbedAction) pairs
}

val blocking = GromEmbed.measureHealth(context)  // never from the main thread
val report = GromEmbed.doctorReport(context)     // the full DoctorReport
```

`RUNNING` is produced only by a fully measured healthy report: a `BAD` row or an
`unknown` row can never be green. The codes and their actions are listed in
`docs/TWO-IN-ONE.md` §9. The health check binds with `createIfMissing = false`, so
asking whether the server runs never starts it.

`EmbedSettings.intentFor(context, advice.action)` turns an action into the right
platform screen (battery exemption list, notification settings, app settings) and
returns `null` for the actions the host draws itself.

### Binding without starting it

`GromClient.bind(listener, createIfMissing = false)` binds to a **running** server
and returns `false` when none is up. `bind` also releases a dead registration
before rebinding, so a client that saw `onNullBinding`/`onBindingDied` recovers
instead of leaking the connection.

A heartbeat is started for you: the announcement repeats every 30 seconds on a
daemon thread and stops with `GromEmbed.stop(context)` (or `stopHeartbeat()` if
the host wants to keep the server up). Tokens, projects and settings stay on
disk.

---

## 4. The IPC surface (API 4 to 9)

| Method | Purpose |
|---|---|
| `projectsJson()` | registered projects with their **derived** liveness |
| `announceProject(json)` | register or heartbeat; returns the stored record |
| `removeProject(id)` | unregister |
| `configExportJson()` | sanitised hand-off document (D53) |
| `agentExec(op, args)` | whitelisted agent op, returned verbatim (operator surface) |
| `projectExec(projectId, op, args)` | **API 4**: `kv.get`/`kv.put` inside the caller's namespace |
| `sshStatusJson()` … `sshClearPin(id)` | the operator's SSH bridge (D58) |
| `lightningProbeJson()` | run the Lightning `health` op now |
| `notifySettingsJson()` … `notifyTestJson()` | **API 5**: the operator's Telegram alerts (D65) |
| `nodeIdentityJson()` … `peerRefreshJson()` | **API 6**: the device identity, mutual pairing and the paired-device roster (D70–D75) |
| `peerGrantJson(json)` | **API 7**: one-way trust for an inbound reader such as a VPS hub (D76–D78) |
| `doctorJson()` | **API 8**: one measured Doctor report — read-only, works while the server is stopped, contains no credential or address (D80–D84) |
| `superSaveConfig(json)` | **API 9**: validate and apply a data-plane document with the screen's own contract; refusals carry the screen's code and write nothing (D86) |
| `aiStatusJson()` | **API 10**: the AI gateway as the operator sees it — providers, `keySet`, catalogue, default, last measured probe (D94) |
| `aiSaveProviderJson(json)` / `aiRemoveProvider(id)` | **API 10**: add or change a provider (`apiKey` is plaintext in, sealed on write) or remove it with its models (D95) |
| `aiSaveModelJson(json)` / `aiRemoveModel(id)` | **API 10**: the catalogue a host may offer |
| `aiConfigureJson(json)` | **API 10**: `{enabled?, defaultModel?}`, absent fields kept |
| `aiProbeProviderJson(providerId)` | **API 10**: one measured `max_tokens: 1` upstream call; the only thing that can make a provider lamp green (D96) |
| `meshStatusJson()` / `meshConfigureJson(json)` | **API 11**: the server network — radios, nearby sightings and paired peers; the roster carries no endpoint (D99–D104) |
| `meshScanJson()` / `meshRefreshJson()` | **API 11**: one discovery pass, and a re-read of every paired peer's `/grom/v1/connect` |
| `meshSetSharesJson(json)` / `meshCallJson(json)` | **API 11**: which services a peer may use, and one relayed call through the existing signed API |
| `sshVerify(id, trustFingerprint)` | **API 12**: one credential check that works without the data plane and reports the stage that broke (D106) |
| `sshSetActiveJson(id)` | **API 12**: marks the operator's current server; it is a label, not a behaviour (D107) |

### Choosing the control port (D85)

`GromEmbed.Options.controlPort` (or `GromEmbed.setControlPort`) moves the
loopback control plane off the standalone default. A taken port is returned as
`PORT_IN_USE` *before* the service starts, never as a generic refusal, and the
server never falls back to another port. `GromEmbed.controlPort` reads the stored
choice. The full rules — including why a port change needs a restart — are in
`docs/TWO-IN-ONE.md` §3.3.

### Configuring the public data plane (D86)

The control plane is loopback-only and always on; the public data plane is
opt-in. A host configures it with the same document the operator screen writes:

```kotlin
client.call(null) { api ->
    api.superSaveConfig(SuperServerConfig.defaults()
        .copy(enabled = true, bind = BindMode.LAN, auth = AuthMode.KEY, keys = listOf(key))
        .toJson())
}
```

A refusal is an `ApiException` with the screen's stable code and nothing is
written; the returned status carries `passwordSet` and key ids only.

Every method runs the same per-transaction signature check as API 1 and 2. A
foreign certificate still never reaches the server state.

### Notifications are operator-level, not a host feature

API 5 exists so the **operator** can receive state changes on their own phone.
The engine does not ship a screen for it (D55: the library draws no UI), and it
does not send a message on the host's behalf to any chat other than the one the
operator configured, on the device the operator is holding.

A host that wants its own alert surface must draw its own screen and call
`notifySaveSettings`; there is no per-project chat, no per-project token and no
way for one project to redirect another project's alerts. This is deliberate: a
host that could pick the destination could exfiltrate state to a third party
through the operator's bot.

### Project liveness

A project is `alive` only while it keeps announcing: the record is refreshed by
`announceProject` and the state is `alive` for 90 seconds after the last
heartbeat, then `stale`. Nothing in the document can set `alive` directly, and
registration is not evidence that a process is running.

`GromEmbed.start` implements that heartbeat (30 s, three misses to go stale). A
host that stops — process death, user swipe-away — becomes `stale` on its own.
Use `GromProject.startHeartbeat` instead if you bind the client yourself.

### Local data (API 4)

Use the typed handle rather than hand-built JSON:

```kotlin
val client = GromEmbed.newClient(context)
client.bind(listener)                              // wait for onConnected
val project = GromEmbed.newProject(client, "my-wallet")

when (val write = project.put("theme", "dark")) {
    is GromProject.LocalWrite.Ok -> { /* stored: ${write.found} */ }
    GromProject.LocalWrite.StorageUnavailable -> show("local storage is not available")
    is GromProject.LocalWrite.Rejected -> show("${write.code}: ${write.message}")
}

val read = project.get("theme")                    // same sealed result type
```

The engine computes the storage key as `p:<projectId>:<key>` and accepts only
`kv.get`/`kv.put` on this path (`projectExec`). A key that already carries a
foreign `p:` prefix is refused with `PROJECT_KEY_INVALID`, an unregistered
project with `PROJECT_NOT_REGISTERED`, and a value over 32 KiB with
`VALUE_TOO_LARGE`. Without `node:sqlite` the call answers
`503 STORAGE_UNAVAILABLE`, which the SDK surfaces as `StorageUnavailable` — it
never fakes a successful write.

**Scope of the guarantee.** The namespace is an ownership gate on the API, not a
sandbox. Applications in the *same UID* are one trust domain: one that chose to
lie could announce its neighbour's id. Real isolation needs separate UIDs or
signatures, i.e. the standalone + `signature` model. Do not describe the
namespace as a security boundary between co-installed apps.

---

## 5. Distributing the library

The engine and the SDK are published as source modules in this repository
(`include(":engine")`, `include(":sdk")`). There is no AAR on a registry yet:
until the API stops moving, a versioned AAR would be a second artifact to keep
in sync, and `include` gives the host the same code the tests build. A host
that wants a single artifact can run `./gradlew :engine:assembleRelease` and
consume the local AAR; see `RELEASING.md`.

## 6. What changes at the trust boundary

Embedding means the host's UID owns the server. That is the point — and it is
also the cost:

* the host process can read every Grom file (token, audit chain, SSH targets,
  sealed credentials) because they are in its own data directory;
* a host compromise is a server compromise;
* the signature gate protects *other* apps, not the host itself.

There is no way to embed the engine and keep its storage out of the host's
reach. Hosts that need that separation must use the standalone app and the
Binder contract instead, which requires the same signing certificate.

---

## 7. What the engine does not do

* It does not draw any UI. Grom's screens in `:app` are a reference, not part of
  the library.
* It does not start itself on boot. `BootReceiver` belongs to the standalone
  `server29` flavour; an embedding host decides its own lifecycle.
* It does not download or install anything.
* It does not open a network listener unless the operator enables the public
  data plane in the configuration (D39).
