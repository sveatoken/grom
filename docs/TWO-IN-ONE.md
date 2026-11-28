# "2 in 1": an app that needs a server, shipped with Grom

A host application can carry Grom inside its own APK. The operator installs
**one** package, taps **one** consent button, and gets the host's UI plus the
real Grom engine: supervisor, loopback control plane, public data plane, audit
chain, project registry, SSH bridge and Node agent — all in the host's process,
under the host's UID.

This guide is the operational companion to `EMBEDDING.md` (the API surface) and
`docs/CLUSTER.md` / `docs/HUB.md` (many devices). It answers the questions that
only appear when you actually ship the combination: which port, which lifecycle,
which permission, what happens when the operator already has Grom, and what the
APK weighs.

---

## 1. The three models, and why one of them is the recommendation

| | A — embedded (one APK) | B — companion (two APKs) | C — install side by side |
|---|---|---|---|
| Installations | **one** | two | two |
| Bind to the server | same UID, no IPC permission | **needs the same signing certificate** (S4-R007) | two servers, two UIDs |
| Server identity | the host's own | the standalone app's | one per package |
| Update channel | host's own | Grom's release | independent |
| APK cost | +~38 MB (arm64 runtime) | none | none |
| Trust | host compromise = server compromise | separated by signature | separated |
| Works today | **yes** | no (a third-party host cannot sign with Grom's key) | yes, with distinct ports (D85) |

**Model A is the supported "2 in 1".** Model B is not a code change — it is a new
trust relation: a host signed with a different certificate cannot bind to the
standalone service, and adding a second way in would weaken the invariant that
only the same signature reaches the Binder (S4-R007, S4-R008, D15). Do not ship
model B; if you ever need it, it is a decision (a user-granted permission or a
"this app may use this server" screen), not a build flag.

Model C is not "2 in 1" but it must still work, because operators do install the
standalone Grom and then a host app that embeds it. Section 6 covers it.

---

## 2. What the operator gets, and what they must be told

The engine draws no UI (D55). The host owns the screens, so the host owns the
honesty:

1. **Consent before start.** Show what a local server is and that nothing is
   exposed until the operator enables it. `GromEmbed.recordConsent` after the
   answer; `start` refuses with `CONSENT_REQUIRED` before that.
2. **A visible "server running" state**, with a way to stop it.
3. **The truth from `status()`, not from "we called start".** `started` means the
   platform accepted the foreground-service start; `state` in the status is the
   measurement. `MISSING_RUNTIME` and `QUARANTINED` are normal outcomes.
4. **The limits.** A phone in Doze, an aggressive vendor killer or a low-memory
   kill can stop the service; `force-stop` is not recoverable by any app. The
   Doctor section in the standalone app is the reference treatment of this.

The three subsections below are the engine's contribution to that honesty, so a
host does not have to build it from `statusJson` and Doctor codes by hand.

### 2.1 Consent about specific text (D88)

A boolean is not consent. If the host records `true` once and later changes what
it starts — a data plane, another project, a rewritten notice — the old answer
silently covers the new behaviour. Pass the version of the notice the operator
actually read:

```kotlin
GromEmbed.recordConsent(context, noticeVersion = "3")   // after the dialog
val result = GromEmbed.start(context, GromEmbed.Options(
    projectId = "my-wallet", projectTitle = "My Wallet",
    noticeVersion = "3",            // the text the operator is answering about
    controlPort = GromEmbed.AUTO_PORT
))
when (result.reason) {
    "CONSENT_REQUIRED" -> showNotice()              // nothing recorded yet
    "CONSENT_OUTDATED" -> showNotice()              // the text changed; ask again
}
```

`GromEmbed.consentRecord(context)` returns the recorded `(version, time)` so a
host can say *what* the operator agreed to. A host that never versions its notice
(`noticeVersion = ""`, the old `recordConsent(context, granted)`) keeps working
exactly as before — and is asked once more the day it starts versioning, which is
the point.

**A notice you can copy.** The engine owns no wording (D55), so this is a
template, not a constant — translate it, cut it down, but do not make it vaguer
than what actually starts:

> **Run a local server?**
> This app can run a small server for its own features while it is open. It uses
> a notification so Android does not stop it, and it needs to be exempt from
> battery optimisation to keep running in the background. Nothing is reachable
> from other devices unless you turn that on yourself, and it never connects to
> an account or a cloud service. You can stop it at any time. Android may still
> stop it (for example when the phone runs out of memory); a stopped server does
> not delete your data.

Bump `noticeVersion` whenever that text changes meaning, and pass the same string
to `start`. Only the version and the timestamp are stored; the text never leaves
the host.

Nothing about this is transmitted: it is a `SharedPreferences` pair in the host's
private storage.

### 2.2 A port without thinking about ports (D89)

Ports are an implementation detail an ordinary operator should never see. Pass
`GromEmbed.AUTO_PORT` and the engine resolves one **before** the service starts:

```kotlin
val result = GromEmbed.start(context, GromEmbed.Options(
    projectId = "my-wallet", projectTitle = "My Wallet",
    controlPort = GromEmbed.AUTO_PORT
))
if (result.requested) show("Server started")       // result.port is the choice
```

* the range is `47100..47199`, deliberately away from the standalone app's
  `47001` and the public data plane's `47002`;
* a previously chosen port is **reused while it is still free**, so a tunnel
  target or paired device that knows the port keeps working across restarts;
* the chosen port is persisted and returned in `StartResult.port`;
* a running listener is never relocated — `PORT_CHANGE_RESTART_REQUIRED` instead;
* if the whole range is taken, the answer is `PORT_IN_USE`, not a silent move.

This is opt-in: a host that does not ask for `AUTO_PORT` keeps exactly the
behaviour D85 defined.

### 2.3 Health the operator can understand (D90)

`GromEmbed.health` runs the Doctor and projects it into a state, a headline code
and at most four actions, so the host's screen is a small `when` and not a JSON
parser plus a diagnosis:

```kotlin
GromEmbed.health(context) { health ->
    when (health.state) {
        EmbedState.RUNNING -> showGreen(health.headline)      // measured healthy
        EmbedState.STARTING -> showSpinner(health.headline)
        EmbedState.ATTENTION -> showYellow(health.headline, health.advice)
        EmbedState.STOPPED -> showRed(health.headline, health.advice)
        EmbedState.UNKNOWN -> showGrey(health.headline)       // say "not measured"
    }
    health.advice.forEach { advice ->
        when (advice.action) {
            EmbedAction.GRANT_CONSENT -> showNotice()
            EmbedAction.OPEN_BATTERY_SETTINGS -> openBatterySettings()
            EmbedAction.OPEN_NOTIFICATION_SETTINGS -> openNotificationSettings()
            EmbedAction.OPEN_APP_SETTINGS -> openAppSettings()
            EmbedAction.RESTART_SERVER -> { GromEmbed.stop(context); startAgain() }
            EmbedAction.RETRY -> startAgain()
            EmbedAction.NONE -> showExplanation(advice.code)  // nothing a button can fix
        }
    }
}
```

Rules the projection keeps, because it is built from the Doctor's own rules and
not from a second opinion:

* **green means measured.** `EmbedState.RUNNING` is produced only by a report
  where every row measured healthy; a `BAD` or an `unknown` row can never be
  green (D80, D62);
* **no advice for a choice.** The data plane being off, or no SSH target saved,
  is a decision the operator made — `NOTICE`, never a to-do;
* **no invented sentences.** The engine returns stable codes (table in §9); the
  host owns the wording and the translation (D55);
* **no server start.** The health check binds with `createIfMissing = false`, so
  asking "is it running?" can never make it run.

`GromEmbed.measureHealth(context)` is the blocking form for a background thread
(at most a two-second Binder wait), and `GromEmbed.doctorReport(context)` returns
the full report if the host wants to draw every row itself.

The button under an advice item is one line, and the engine maps the action to
the right platform screen so the host cannot open the wrong page:

```kotlin
EmbedSettings.intentFor(context, advice.action)?.let { intent ->
    try { startActivity(intent) } catch (_: ActivityNotFoundException) { showPlainText(advice.code) }
}
```

| action | screen |
|---|---|
| `OPEN_BATTERY_SETTINGS` | Android's list of apps exempt from battery optimisation |
| `OPEN_NOTIFICATION_SETTINGS` | this app's notification settings |
| `OPEN_APP_SETTINGS` | this app's own settings page (includes storage and cache) |
| `GRANT_CONSENT`, `RESTART_SERVER`, `RETRY`, `NONE` | the host's own UI; `intentFor` returns `null` |

Vendor ROMs do remove pages: always catch `ActivityNotFoundException` and say
what the code means instead of crashing.

---

## 3. Build it

Everything in this section is compiled and built by this repository, not just
described: `examples/embedded` is a host module that uses the flow below, and
`tools/check-embed` builds a host APK from a temporary root that is **not**
Grom's (D112). If a step stops working, a gate fails here instead of in your
project.

### 3.1 Modules — three directories, side by side (D112)

A host vendors **three** directories from this repository: `engine/`, `sdk/` and
`agent/`. They must sit next to each other, because the engine packages the agent
script from `../agent/grom-agent.js` — a path relative to the *module*, so it is
correct wherever the module lives. Nothing else of Grom is needed (no `app/`, no
`tools/`, no `scripts/`).

The simplest layout puts them at the host's root:

```text
host/
  settings.gradle.kts
  engine/     ← vendored
  sdk/        ← vendored
  agent/      ← vendored (grom-agent.js)
  app/        ← your application
```

```kotlin
// settings.gradle.kts
include(":sdk")
include(":engine")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":engine"))   // pulls :sdk in
}
```

A host that prefers to keep third-party code apart can vendor under a directory
and point at it — same result, and the layout the gate builds:

```kotlin
// settings.gradle.kts
include(":sdk")
project(":sdk").projectDir = file("third_party/grom/sdk")
include(":engine")
project(":engine").projectDir = file("third_party/grom/engine")
```

```text
host/
  third_party/grom/{engine,sdk,agent}/
  app/
```

**Two build settings the host owns.** Neither can live in a library, and both are
verified by `tools/check-embed`, which builds this exact project from an unrelated
root:

```kotlin
// app/build.gradle.kts
android {
    packaging {
        resources {
            // sshd-core and sshd-common both ship these files, and they are
            // merged by the module that packages — this one. A packaging block
            // in :engine does not reach you (verified against AGP 8.11).
            pickFirsts += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST"
            )
        }
    }
}
```

Without it the build ends with:

```text
Execution failed for task ':app:mergeDebugJavaResource'.
> 2 files found with path 'META-INF/DEPENDENCIES' from inputs:
   - org.apache.sshd:sshd-core:2.13.1/sshd-core-2.13.1.jar
   - org.apache.sshd:sshd-common:2.13.1/sshd-common-2.13.1.jar
```

The other host-side setting is `jniLibs.useLegacyPackaging = true` (§3.2). If the
engine build fails with `File '<host>/agent/grom-agent.js' ... does not exist`,
the `agent/` directory is missing or not beside `engine/`.

A small machine may also need more heap than Gradle's 512 MB default for the
daemon that compiles this: `org.gradle.jvmargs=-Xmx1024m
-XX:MaxMetaspaceSize=512m` (the engine's own profile is in `gradle.properties`).
A metaspace failure looks like a Kotlin `InvocationTargetException`, not like an
embedding error.

The engine manifest merges automatically. It declares, in full (D82):

| Group | Permissions |
|---|---|
| Service | `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` |
| Diagnostics | `ACCESS_NETWORK_STATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Server network (mesh) | `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES` (`neverForLocation`), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, plus `BLUETOOTH`/`BLUETOOTH_ADMIN` (maxSdk 30) and `ACCESS_COARSE/FINE_LOCATION` (maxSdk 32) for older devices |
| Optional hardware | `android.hardware.bluetooth_le`, `android.hardware.wifi.direct` — both `required="false"` |

and one **private** (`exported="false"`) `GromService`. You do not add an IPC
permission — caller and service are the same UID — and you do not copy permission
lines by hand.

The Bluetooth and Wi-Fi Direct entries arrive with the server network even in a
host that never calls it. They are all *runtime* permissions, so the manifest
line grants nothing; the mesh asks when the operator turns discovery on. The
module is one unit today, so a host that wants a smaller budget needs the
optional `core`/`mesh` split, which is not built.

If your app already declares permissions, check the merged manifest
(`app/build/intermediates/merged_manifests/<variant>/AndroidManifest.xml`). The
engine adds no storage, package-query or overlay permission; a host that adds
`QUERY_ALL_PACKAGES` or `PACKAGE_USAGE_STATS` is making its own decision, not
inheriting one from Grom (D82).

### 3.2 The Node runtime (the 38 MB)

The engine is pure Android library code; the Node agent is an executable that
must live in the APK's `nativeLibraryDir` (D34). Fetch the pinned arm64 runtime
into the host's `jniLibs`:

```bash
scripts/fetch-node-android.sh --all
scripts/build-node-android.sh --install-dir <host>/app/src/main/jniLibs
```

* **arm64 only.** x86_64 emulators do not run it. Without the runtime the server
  still starts and honestly reports `MISSING_RUNTIME`; `kv.*` answers
  `503 STORAGE_UNAVAILABLE`.
* **+~38 MB APK / ~91 MB installed.** If your host does not need the agent, do
  not ship it — a server without `MISSING_RUNTIME` is not a broken server, it is
  a server without local execution.
* Keep `jniLibs.useLegacyPackaging = true` in the host app: the runtime is
  executed from disk, so the libraries must be extracted at install time. The
  Grom app does this in `app/build.gradle.kts`.
* A host that ships *both* a peripheral build and a server build of itself can
  apply the same per-variant exclusion Grom uses (D87,
  `app/build.gradle.kts` → `androidComponents { onVariants(...) { variant ->
  variant.packaging.jniLibs.excludes } }`).

### 3.3 Choose the control port **before** the first start (D85)

The default is 47001, which is what a standalone Grom on the same phone uses.
Two servers cannot share it. Embedding hosts should take a port of their own —
47101, 47201, anything in `1..65535` that the phone does not use:

```kotlin
val options = GromEmbed.Options(
    projectId = "my-wallet",
    projectTitle = "My Wallet",
    controlPort = 47101            // chosen once, then persisted
)
val result = GromEmbed.start(context, options) { registered -> /* … */ }
when {
    result.requested -> { /* ask status() for the truth */ }
    result.reason == "PORT_IN_USE" -> show("Another server is using port 47101.")
    result.reason == "BAD_CONTROL_PORT" -> show("That port cannot be used.")
    result.reason == "PORT_CHANGE_RESTART_REQUIRED" ->
        show("Stop the server, then start it again to change the port.")
    result.reason == "CONSENT_REQUIRED" -> showConsentDialog()
    else -> show("Start refused: ${result.reason}")
}
```

`GromEmbed.setControlPort(context, port)` stores the choice without starting;
`GromEmbed.controlPort(context)` reads it back.

**Rules that matter:**

* the port is validated (`BAD_CONTROL_PORT`) and probed (`PORT_IN_USE`) before the
  service is asked to start, so the failure is a returned code;
* the app's **own** listener is never mistaken for a conflict — the service
  records the port it serves;
* changing the port while the service runs does not move the listener; the call
  returns `PORT_CHANGE_RESTART_REQUIRED` rather than pretending;
* **no silent fallback.** A server that moved itself would break every client
  that knows the old port — the hub's tunnel target, a paired device, a CLI.

A port conflict is a **fact**, not a bug: `PORT_IN_USE` also appears as
`lastError` in the status if a bind is refused for any other reason.

### 3.4 Start from a visible Activity

```kotlin
// In an Activity (or a user-initiated broadcast). NOT in Application.onCreate.
if (GromEmbed.consentRecord(this) == null) { showNotice(); return }
val result = GromEmbed.start(this, GromEmbed.Options(..., noticeVersion = NOTICE))
```

Android 12+ refuses `startForegroundService` from the background
(`ForegroundServiceStartNotAllowedException`). `GromEmbed.start` catches it and
returns `requested = false` with `FOREGROUND_START_NOT_ALLOWED` (or
`START_REFUSED` for another refusal, named in `result.detail`), but the call
should not be made from that position at all.

**Boot.** The engine does not start itself on boot — that is a visible decision
the host owns. A host that wants it declares `RECEIVE_BOOT_COMPLETED` and its own
receiver, checks the operator's opt-in setting, and calls `start`. On Android
12+ a boot-started foreground service needs an allowed exemption; the standalone
`server29` flavour documents its path in `docs/RUNTIME.md`, and the same
constraints apply to you.

**Stop.** `GromEmbed.stop(context)` stops the service and the heartbeat; state
stays on disk.

### 3.5 Configure the public data plane (D86, API 9)

The control plane is loopback-only and always on. The **public** data plane
(`/v1/*`, D39) is opt-in, and a host configures it through the Binder with the
same document the standalone screen writes:

```kotlin
val client = GromEmbed.newClient(context)
client.bind(object : GromClient.Listener {
    override fun onConnected(apiVersion: Int) {
        if (apiVersion < 9) return            // check before calling (S4-R028)
        val document = """
            {"enabled":true,"bind":"LAN","port":47002,"auth":"KEY",
             "keys":[{"keyId":"…","label":"laptop","publicKey":"…","enabled":true}],
             "acknowledgePublicBind":false}
        """.trimIndent()
        val status = client.call(null) { it.superSaveConfig(document) }
        // status contains "super": {enabled, running, bind, address, auth,
        //   passwordSet, keys:[{keyId,label}], error, …} — no secret.
    }
    override fun onDisconnected(reason: String) { /* show it */ }
})
```

* The document is the `SuperServerConfig.toJson()` shape. Build it from a data
  class in your own code, not by string concatenation.
* **Password auth:** put a `passwordHash` from
  `app.grom.server.superserver.auth.PasswordHash.hash(password)` in the document.
  A plaintext `password` field is simply ignored — Grom never stores one.
* A refusal is an `ApiException` carrying the screen's own code
  (`PORT_CONFLICT`, `CREDENTIAL_REQUIRED`, `PUBLIC_NOT_ACKNOWLEDGED`,
  `INSECURE_BACKEND_URL`, `BAD_KEY`, …) and **nothing is written**.
* For a listener off loopback you must supply at least one credential; `PUBLIC`
  also needs `acknowledgePublicBind: true`. These are the same rules the operator
  screen enforces — there is no "host bypass".
* Changing only `npub`/`relays`/`remoteBridge` applies without rebinding (D54);
  everything else restarts the data plane only, never the control plane.

If the data plane is off — the default — nothing listens outside loopback and no
client can reach the server. That includes `tools/grom-fleet` and a VPS hub.

### 3.6 Battery and Doctor

The engine already declares the whole diagnostics budget (D82): the merged
manifest gains `ACCESS_NETWORK_STATE` and
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and nothing else. If you want the
exemption UI in your own screens, show Android's dialog yourself:

```kotlin
// android.provider.Settings, android.net.Uri
startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    Uri.parse("package:$packageName")))
```

The standalone app asks once, explains why, and never asks again; do the same
rather than re-prompting on every launch.

* `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only lets you show Android's own
  one-tap exemption dialog (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); the
  operator still decides. It does not exempt you automatically, and Play
  restricts it — Grom is installed by hand, which is why it is acceptable here.
* The exemption is the single most effective measure. The foreground service and
  `START_STICKY` are already handled by the engine; **boot is the host's own
  decision** (§3.4). None of them makes the process immortal, and nothing should
  claim otherwise.
* The engine's Doctor (`GromEmbed.newClient(...).call { it.doctorJson() }`, API 8)
  works even while the server is stopped and reports measured facts with an
  explicit `unknown`. A host that wants a "why did it stop?" screen should render
  that report rather than inventing its own diagnosis.

---

## 4. What "2 in 1" does and does not change

| Concern | Reality |
|---|---|
| Storage | The engine's files live in the host's private directory. The host can read the token, audit chain and sealed secrets; that is the embedding trust model (D55). |
| Identity | The embedded server has its **own** `nodeId`, derived from a seed sealed by the **host's** Keystore entry. It is not the standalone app's identity. |
| Updates | The engine and the runtime ship with the host, so they update when the host updates. There is no separate Grom channel. |
| Uninstall | Uninstalling the host removes the embedded server and its data. The standalone Grom, if installed, is unaffected. |
| Size | +~38 MB APK with the runtime. Without it the engine, its dependencies and the agent script add **~5.6 MB** to a host APK (measured on the debug host that `tools/check-embed` builds; debug builds are not shrunk, so a release host is at or below that). |
| Permissions | The merged manifest is the union. Check it once; a permission your host adds is a capability the server process also holds. |
| Background | The same Doze/vendor-kill constraints as the standalone app. The host must expose the exemption and the boot opt-in. |

---

## 5. What NOT to build

* **Do not call `start` in `Application.onCreate`.** It fails on Android 12+ and
  hides the failure from the operator.
* **Do not use the standalone port (47001) when the host ships its own server**
  unless you truly do not expect a standalone Grom on the same phone. If you do,
  the operator gets `PORT_IN_USE` — honest, but annoying.
* **Do not run two embedded servers in one process.** One engine, one process, one
  service; a second start replaces the project session, not the server.
* **Do not try to bind to the standalone app's service.** Different certificate
  means no Binder, by design.
* **Do not promise uptime.** A force-stop, a low-memory kill and a vendor
  "deep clean" can all stop the server. Doctor measures; it does not prevent.

---

## 6. Coexistence with a standalone Grom (model C)

An operator may have both. The rules follow from the code:

| Aspect | Behaviour |
|---|---|
| Package / service | Two packages, two services, two foreground notifications. |
| Data | Separate private directories. Nothing is shared. |
| Tokens | Separate. Rotating one does not affect the other. |
| Node identity | Separate `nodeId`s, separate peer stores. Pair them if you want them to see each other (`Devices`, D70–D75). |
| Control port | **Must differ** (D85). Use 47101+ for the embedded one. |
| Public data plane | Separate ports (47002 by default) and separate credentials. Only one may take a given port. |
| SSH tunnels | Separate saved targets; the forward-port uniqueness rule is per app (C0). Give each its own port on the VPS. |
| Uninstall | Removing one leaves the other running and intact. |

A useful operator pattern: the standalone Grom holds the fleet identity and the
VPS tunnel; the embedded server in the host app is a second, independent node
with its own scopes. Both can be paired into the same roster.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `CONSENT_REQUIRED` | `start` before `recordConsent` | Show your consent dialog first. |
| `CONSENT_OUTDATED` | The host declared a notice version the operator never saw | Show the notice again, then `recordConsent(context, noticeVersion)`. |
| `PORT_IN_USE` | 47001 (or your chosen port) is held — usually the standalone Grom | Use `Options.controlPort = GromEmbed.AUTO_PORT`, or pick another port. |
| `BAD_CONTROL_PORT` | Outside `1..65535` | Use a real port, or `AUTO_PORT`. |
| `PORT_CHANGE_RESTART_REQUIRED` | Port changed while the server runs | `GromEmbed.stop`, then `start`. |
| `FOREGROUND_START_NOT_ALLOWED` | Started from the background (Android 12+) | Start from a visible Activity and offer a retry button. |
| `START_REFUSED` | The platform refused the start for another reason | `result.detail` names the exception class; read the Doctor. |
| Binder never connects | Service could not start | Read `lastError`/Doctor; a bind conflict now says `PORT_IN_USE` instead of a generic refusal. |
| `MISSING_RUNTIME` in status | `libnode.so` not in the host's `jniLibs` | Run `scripts/build-node-android.sh --install-dir …` and rebuild. |
| `ApiException` 400 on `superSaveConfig` | The document would be refused by the screen too | Fix the document; the code names the field (`BAD_KEY`, `CREDENTIAL_REQUIRED`, …). |
| Server dies overnight | Doze / vendor killer / low memory | Request the battery exemption, check Doctor, keep the phone on power. |

---

## 8. Checklist

- [ ] `engine/`, `sdk/` and `agent/` vendored **side by side**, `include(":engine")`
      and `include(":sdk")`, `implementation(project(":engine"))`.
- [ ] `packaging.resources.pickFirsts` for `META-INF/DEPENDENCIES`,
      `LICENSE*`, `NOTICE*`, `INDEX.LIST` — the host packages, so the host picks.
- [ ] `jniLibs` populated **or** a deliberate decision to run without the agent.
- [ ] `jniLibs.useLegacyPackaging = true` in the host app (only if you ship it).
- [ ] `controlPort = GromEmbed.AUTO_PORT` (or an explicit `47101`, not `47001`).
- [ ] Consent screen → `recordConsent(context, noticeVersion)` → `start` from an
      Activity, with `Options.noticeVersion` set to the same version.
- [ ] UI renders `GromEmbed.health` (`state`, `headline`, `advice`) instead of
      parsing `statusJson` and inventing sentences.
- [ ] Public data plane stays **off** until the operator turns it on; configured
      only through `superSaveConfig` (API 9, checked with `apiVersion() >= 9`).
- [ ] Battery exemption offered with Android's own dialog; boot opt-in explained.
- [ ] Merged manifest reviewed.
- [ ] The operator has been told: no uptime guarantee, no cloud account, no
      shared disk, and what "pair a device" means if you expose one.


---

## 9. Stable codes

Everything the embed layer can return, so a host can `when` over it and own the
wording. The codes are part of the contract: a new one is added by a decision and
documented here first (`tools/test-policy` #54 fails if this table drifts).

### 9.1 `StartResult.reason` — the start did not happen

| Code | Meaning | Action |
|---|---|---|
| `CONSENT_REQUIRED` | nothing was recorded | `EmbedAction.GRANT_CONSENT` |
| `CONSENT_OUTDATED` | recorded for different notice text (D88) | `EmbedAction.GRANT_CONSENT` |
| `PORT_IN_USE` | the resolved port is held | `EmbedAction.RETRY` with `AUTO_PORT` |
| `BAD_CONTROL_PORT` | outside `1..65535` | `EmbedAction.RETRY` |
| `PORT_CHANGE_RESTART_REQUIRED` | a running listener cannot move (D85) | `EmbedAction.RESTART_SERVER` |
| `FOREGROUND_START_NOT_ALLOWED` | Android 12+ refused a background start | `EmbedAction.RETRY` from a visible Activity |
| `START_REFUSED` | anything else; `result.detail` has the class name | `EmbedAction.RETRY` |

### 9.2 `EmbedHealth.headline` — what the measurement says

| Code | State | Meaning |
|---|---|---|
| `SERVER_RUNNING` | `RUNNING` | every row measured healthy — the only green |
| `SERVER_STARTING` | `STARTING` | the process is coming up; nothing proven yet |
| `SERVER_STOPPED` | `STOPPED` | not running; the last end was clean or unknown |
| `SERVER_DIED` | `STOPPED` | the previous process ended without `onDestroy` (D83) |
| `SERVER_QUARANTINED` | `STOPPED` | the supervisor stopped restarting a crashing agent |
| `RUNTIME_MISSING` | `STOPPED` | no arm64 Node runtime in this APK; the server still serves |
| `NEEDS_ATTENTION` | `ATTENTION` | a measured value explains why it may stop |
| `PARTLY_UNKNOWN` | `UNKNOWN` | something was not measured; not health |

A process whose status document names a state these rules do not recognise
(`PROCESS_UNKNOWN_STATE`) is reported as `PARTLY_UNKNOWN`, never as
`SERVER_STOPPED`: the server answered, so "stopped" would be a guess. The
`PROCESS_NOT_RUNNING` case — no status at all — is the one that really is
`SERVER_STOPPED`.

### 9.3 `EmbedAdvice.code` — the Doctor's own codes, with an action

| Code | `EmbedAction` |
|---|---|
| `BATTERY_OPTIMISED`, `STANDBY_RARE`, `STANDBY_RESTRICTED`, `STANDBY_NEVER` | `OPEN_BATTERY_SETTINGS` |
| `EXIT_LOW_MEMORY`, `EXIT_SIGNALED`, `EXIT_FREEZER`, `EXIT_EXCESSIVE_RESOURCE_USAGE` | `OPEN_BATTERY_SETTINGS` |
| `NOTIFICATIONS_OFF` | `OPEN_NOTIFICATION_SETTINGS` |
| `BACKGROUND_RESTRICTED`, `STORAGE_LOW` | `OPEN_APP_SETTINGS` |
| `PROCESS_STOPPED`, `PROCESS_STOPPED_UNCLEAN`, `PROCESS_QUARANTINED`, `AGENT_CRASHING`, `DATA_PLANE_NOT_RUNNING` | `RESTART_SERVER` |
| `EXIT_CRASH`, `EXIT_CRASH_NATIVE`, `EXIT_ANR`, `EXIT_UNRECORDED` | `RESTART_SERVER` |
| `PROCESS_NO_RUNTIME`, `BRIDGE_NOT_CONNECTED`, anything new | `NONE` (nothing a button can fix) |

Only a `BAD` row produces advice. A `NOTICE` — `DATA_PLANE_OFF`, `BRIDGE_NONE` —
is a decision the operator made, and nagging about it would teach them to ignore
the screen.

---

## 10. Where this is enforced

| Rule | Enforced by |
|---|---|
| one API version, from the SDK | `tools/test-policy` #48, `GromClientContractTest` |
| control port is choosable, conflicts are named | `ControlPortTest`, `tools/test-policy` #49 |
| host config uses the screen's contract | `SuperServerConfigInputTest`, `tools/test-policy` #50 |
| the peripheral role carries no runtime | `tools/check-flavours` (built APKs; the release workflow asserts it on the shipped ones), `tools/test-policy` #51 |
| every IPC method passes the signature gate | `tools/test-policy` #15, `tools/verify-ipc-signing` |
| no address or secret in a status document | `tools/test-policy` #24, #47; `ConfigExportTest` |
| the embed layer stays pure and honest | `EmbedHealthTest`, `EmbedConsentTest`, `GromEmbedStartCodeTest`, `tools/test-policy` #52 |
| consent covers specific notice text (D88) | `EmbedConsentTest`, `tools/test-policy` #53 |
| an automatic port is resolved, persisted and returned (D89) | `ControlPortResolveTest`, `tools/test-policy` #53 |
| every code in §9 is documented | `tools/test-policy` #54 |
| a health check never starts the server | `GromClient.bind(createIfMissing = false)`, `GromEmbedStartCodeTest` |
| advice opens the right system page | `EmbedSettingsTest` |
| a dead registration is released | `GromClient.unbind()` after `onNullBinding`/`onBindingDied` (S4-R018) |
| the guide's build file really builds, from a root that is not Grom's | `tools/check-embed` (a temp root, the three directories under `third_party/grom/`, a host APK with the agent asset and a private service), `tools/test-policy` #83 |
| a host has a compiled caller, not just a document | `examples/embedded` (built and tested in `tools/run-gates` and CI), `tools/test-policy` #84 |
| the engine does not pretend to fix its consumer's packaging | `tools/test-policy` #85 (a `packaging` block in `:engine` would be ignored, so the contract lives in the host) |
