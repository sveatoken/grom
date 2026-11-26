# S4 — IPC and SDK contract

The Binder surface that other apps use, and the SDK that wraps it.

## 1. Naming

### S4-R001
AIDL package is `app.grom.sdk.ipc`. Interface name is `IGromServer`.

### S4-R002
SDK package is `app.grom.sdk`. The client class is `GromClient`.

### S4-R003
Service class is `app.grom.server.service.GromService`; action is
`app.grom.sdk.ipc.GROM_SERVER`; the client targets package `app.grom.server`.

## 2. Permission

### S4-R004
The server declares
`<permission android:name="app.grom.sdk.ipc.BIND_GROMSERVER"
android:protectionLevel="signature"/>`.

### S4-R005
The service is declared with
`android:permission="app.grom.sdk.ipc.BIND_GROMSERVER"` and
`android:exported="true"` (export is required for cross-app binding, the
permission is what gates it).

### S4-R006
A client that does not hold the permission MUST fail to bind. The platform
enforces this; Grom MUST NOT rely on it alone (see S4-R008).

## 3. Signature verification

### S4-R007
Every `IGromServer` transaction MUST verify the caller before touching any
server state.

### S4-R008
Verification compares signatures by UID, on the binder thread:
`PackageManager.checkSignatures(Process.myUid(), Binder.getCallingUid())` must
equal `SIGNATURE_MATCH`. On mismatch the call throws `SecurityException` with a
`GROM_E_FORBIDDEN` message and no state is read or written.

The check lives in the transaction rather than in `onBind` because `onBind` may
run outside a binder transaction, where `Binder.getCallingUid()` is not the
caller's identity (see S4-R009).

### S4-R009
Verification MUST NOT depend on resolving the caller's package name, so that
package-visibility restrictions on API 30+ cannot weaken the check. It MUST NOT
read `Binder.getCallingUid()` on a thread that is not handling a binder
transaction.

### S4-R010
A rejected transaction MUST be logged locally with the calling UID. The
`signature` protection on the service declaration (S4-R005) is the first gate,
so a foreign signature normally fails to bind at all and never reaches S4-R008.

## 4. Interface

### S4-R011
```aidl
int      apiVersion();
String   healthJson();
String   statusJson();
String   auditTail(int maxEntries);
void     restartAgent();
void     resetAgent();
```
Callers on a foreign signature never reach these; callers on the same
signature get all of them in F1. The interface also declares
`void rotateToken()` (the IPC counterpart of S2-R020). API 2 adds
`void reloadSuperServer()`; section 8 states the additive contract.

### S4-R012
`apiVersion()` returns `1` for F1. Clients MUST check it before assuming a
method exists. API 2 returns `2` (S4-R028). Each later API section states the
value it adds; the current contract is API 12, so the value is `12` (§17).

### S4-R013
`auditTail(int)` clamps `maxEntries` to 1…200, matching S2-R018.

### S4-R014
`restartAgent()` and `resetAgent()` MUST behave exactly like the corresponding
HTTP endpoints, including audit entries and quarantine rules.

### S4-R015
`healthJson()`/`statusJson()` return the same JSON documents as the HTTP
endpoints, byte-for-byte equal for the same instant.

### S4-R016
No method may throw a checked exception to the client; failures surface as
`IllegalStateException` with a stable message code prefix (`GROM_E_`).

## 5. Lifecycle

### S4-R017
`GromClient` binds with `BIND_AUTO_CREATE` by default and reports the connection
through a listener, never by polling `isBound` in a loop. `bind(listener,
createIfMissing = false)` MUST bind **without** creating the service, so a health
check can ask whether the server is running without starting it, and MUST return
`false` when nothing is running.

### S4-R018
`GromClient.unbind()` MUST be idempotent, and MUST release the platform
registration even when the connection never became usable — a null binding, a
died binding or a failed version call. Dropping the registration without calling
`unbindService` leaks it for the life of the process and turns every later
`bind` into a no-op, because the client believes it is still bound.

### S4-R019
A dead Binder (`DeadObjectException`) MUST be reported via the listener as a
disconnect, not crash the client.

A **null binding** MUST be reported as *the service refused to start*, never as
a signature failure. A foreign certificate is refused by the platform before
`onBind` runs and surfaces as `bindService` returning false (S4-R008/S4-R009),
so a null binding can only mean the service's `onBind` returned null — which the
server role does when the runtime cannot start, for example because another
server already holds the loopback control port. Naming the wrong cause sends an
integrator after a signature problem that does not exist.

### S4-R020
The SDK MUST NOT require any permission in the client manifest. Holding a
matching signature is sufficient.

## 6. Conformance apps

### S4-R021
`testapps/samecert` is signed with the Grom test key. On a device where Grom is
installed, it MUST bind and its listener MUST fire `onConnected(apiVersion)` with
the contract version the server implements (`12` in API 12, §17).

### S4-R022
`testapps/foreigncert` is signed with a different key. `bindService` MUST return
`false` and its listener MUST never fire `onConnected`.

### S4-R023
Both apps MUST compile in the same Gradle build and be listed in
`docs/F1-implementation-plan.md` as the C-008 evidence.

### S4-R024
`tools/verify-ipc-signing` MUST verify, without a device:
1. both APKs exist;
2. the merged manifest of `app` declares the permission with
   `protectionLevel="signature"` and the service with that permission;
3. `samecert` and `app` certificates are identical (SHA-256);
4. `foreigncert` certificate differs from both.

### S4-R025
The tool exits non-zero if any of the four checks fails.

## 7. Limits

### S4-R026
The Binder interface is local-only. Grom MUST NOT expose it through any remote
transport.

### S4-R027
No AIDL method may accept a file descriptor or a custom Parcelable in F1.

## 8. API 2 — the public data plane

### S4-R028
`apiVersion()` returns `2`. API 2 is additive: every API 1 method is unchanged.
The interface also declares `rotateToken()` (the IPC counterpart of S2-R020) and
the one new transaction:

```aidl
void reloadSuperServer();
```

### S4-R029
`reloadSuperServer()` re-reads `<files>/grom/super-server.json` and rebuilds
only the public data plane described in S5. The control plane, the bearer token
and the supervised agent are untouched, so an operator can change exposure
without dropping the supervisor. It is the only way the app applies an access
change (D39).

### S4-R030
The call passes the same signature gate as every other transaction
(S4-R007/S4-R008). It never throws a checked exception (S4-R016): when the server
is not running it fails as `GROM_E_SERVER_STOPPED`, and in the peripheral role as
`GROM_E_PERIPHERAL_ROLE`. A refused data-plane configuration is not a Binder
failure; it is reported through `statusJson()` under `super.error` (S5-R003).

### S4-R031
`GromClient.API_VERSION` names the **current** contract; the number itself lives
only in §14 of this document plus the SDK constant, because two places kept it in
step by hand and drifted twice. Clients MUST compare the value returned by
`apiVersion()` before calling a method introduced in a later API (S4-R012). A
method added under a later API number does not remove or change any earlier one,
so a client that only needs the methods of an older API keeps working against a
newer server.

### S4-R032
A reload invalidates every password session of the data plane, because the
session store is rebuilt with the listener (S5-R014). Clients that held a session
token MUST sign in again; Ed25519 keys registered in the new configuration keep
working.

## 9. API 3 — the embeddable surface (D55)

### S4-R033
`apiVersion()` returns `3`. API 3 is additive: every API 1 and API 2 method is
unchanged in name, signature and behaviour.

### S4-R034
API 3 adds:

```aidl
String projectsJson();
String announceProject(String json);
void   removeProject(String id);
String configExportJson();
String agentExec(String op, String argsJson);
String sshStatusJson();
String sshGenerateKeyJson();
String sshSaveTarget(String draftJson);
void   sshDeleteTarget(String id);
String sshConnect(String id, String trustFingerprint);
void   sshDisconnect(String id);
void   sshClearPin(String id);
String lightningProbeJson();
```

Every method MUST call `requireSameSigner()` before touching server state, so a
client with a different certificate still receives `GROM_E_FORBIDDEN`.

### S4-R035
`projectsJson()` MUST return the stored records with `state` derived from the
heartbeat (`alive` for at most 90 seconds). `announceProject` MUST validate the
id, title, version, package and capabilities before writing, and MUST NOT accept
a caller-supplied `state`. Errors are `GROM_E_BAD_PROJECT`,
`GROM_E_TOO_MANY_PROJECTS` or `GROM_E_PROJECT_NOT_FOUND`.

### S4-R036
`configExportJson()` MUST return the same sanitised allow-list document as the
UI's *Copy config* action (D53). It MUST NOT contain a password hash, key
material, a sealed credential, a session token or an audit record.

### S4-R037
`agentExec` MUST pass the op through the same whitelist as the loopback HTTP API
and return `{"status":…,"body":…}`. A failure returned by the agent is data in
that envelope; the Binder call itself fails only for local reasons (quarantine,
missing runtime, unknown op).

### S4-R038
`sshConnect` is blocking (bounded by the 15 s connect and 15 s auth timeouts).
It MUST return a JSON result with `ok`, `state` and either `fingerprint`/
`learned` or a stable `code`. `HOST_KEY_UNVERIFIED` includes the observed
fingerprint so the caller can ask the operator; `HOST_KEY_MISMATCH` includes the
pin and the observed value and MUST NOT connect.

### S4-R039
`lightningProbeJson()` runs the adapter's `health` op now and returns its
measured result. It MUST NOT report `reachable: true` without a successful
backend probe, and it MUST answer `DATA_PLANE_STOPPED` when the public plane is
not running.

### S4-R040 (API 4)
`projectExec(projectId, op, argsJson)` MUST be the only project-facing data
path. It MUST compute the storage key as `p:<projectId>:<key>`, MUST refuse a
key that already carries a foreign `p:` prefix (`PROJECT_KEY_INVALID`), MUST
refuse an unregistered project (`PROJECT_NOT_REGISTERED`), MUST accept only
`kv.get` and `kv.put`, and MUST return `{"status":…,"body":…}` so a
`503 STORAGE_UNAVAILABLE` from the agent is visible as such. It MUST apply the
same per-transaction signature gate as every other method.

### S4-R041 (API 4)
`GromProject` MUST map that envelope onto a typed result where a missing
`node:sqlite` is `StorageUnavailable` and never `Ok`. `GromEmbed.start` with a
project registration MUST repeat the announcement every 30 s by default (clamped
to 5 s … 10 min) until `GromEmbed.stop`, so the dashboard's `alive` state is
derived from an ongoing heartbeat rather than a single claim.

## 10. API 5 — operator notifications (D65–D67)

`apiVersion()` returns `5`. API 5 is additive: every API 1–4 method is
unchanged. It adds:

```aidl
String notifySettingsJson();
String notifySaveSettings(String json);
String notifyTestJson();
```

### S4-R042 (API 5)
`notifySettingsJson()` MUST return
`{"enabled":…,"chatId":…,"tokenSet":…,"lastDelivery":{…}}`. `chatId` is the
operator's own chat identifier and is returned only over the signature-gated
Binder; `tokenSet` is a boolean, and the token itself MUST NOT be returned by any
method.

### S4-R043 (API 5)
`notifySaveSettings(json)` MUST accept `{enabled, chatId, token?, clearToken?}`
and MUST seal the token through `SecretBox` before the write. A token that does
not match the bot-token shape MUST be refused with `BAD_BOT_TOKEN`; a chat ID
that is not numeric MUST be refused with `BAD_CHAT_ID`; `enabled` without a
usable token or chat ID MUST be refused with `NOTIFY_NOT_CONFIGURED`. A `null`
token MUST keep the stored one, so a caller can toggle `enabled` without
resending the secret.

### S4-R044 (API 5)
`notifyTestJson()` MUST send one message synchronously and MUST report
`{"ok":…,"code":…}` where `ok` is true only when the provider answered
`{"ok":true}`. It MUST bypass the queue and the rate limit (the operator asked
in that moment) and MUST NOT alter the event table. Every API 5 method MUST pass
the same per-transaction signature gate (S4-R007).

## 11. API 6 — device identity and the fleet (D70–D75)

`apiVersion()` returns `6`. API 6 is additive: every API 1–5 method is
unchanged. It adds:

```aidl
String nodeIdentityJson();
String nodeResetIdentityJson(String confirmation);
String peersJson();
String peerPairStartJson();
String peerPairPendingJson();
String peerPairCompleteJson(String decisionJson);
String peerJoinJson(String requestJson);
String peerConfirmJson(String confirmJson);
void   peerRemove(String nodeId);
String peerRefreshJson();
```

### S4-R045 (API 6)
`nodeIdentityJson()` MUST return `{nodeId, publicKey, label, fingerprint,
createdAtMs}` and MUST NOT return the private seed or any value from which it can
be reconstructed. `nodeResetIdentityJson(confirmation)` MUST refuse any
confirmation other than `RESET` with `RESET_CONFIRMATION_REQUIRED`, and on
success MUST forget every paired device.

### S4-R046 (API 6)
`peersJson()` is the only projection that may carry a peer address, because the
caller already passed the per-transaction signature gate. It MUST return the
measured `state` (`unknown`, `alive`, `stale`, `unreachable`) and MUST NOT return
a secret.

### S4-R047 (API 6)
Pairing is three operator steps and no shortcut:
`peerPairStartJson()` opens a session and returns `{code, expiresAtMs, nodeId,
fingerprint}`; `peerPairPendingJson()` returns the waiting device (or nothing);
`peerPairCompleteJson({accept, scopes})` approves a non-empty subset of the
requested scopes or rejects. A scope that was not requested MUST be refused with
`BAD_SCOPE`.

### S4-R048 (API 6)
`peerJoinJson({address, code})` MUST validate the address and code locally,
verify the other device's acceptance signature over
`grom-pair-accept-v1`, and return an **unconfirmed** offer; nothing is written
until `peerConfirmJson({nodeId})`. `peerRefreshJson()` performs at most one
measured probe per paired device (clamped to 16 per call) and MUST NOT schedule
any future probe. `peerRemove(nodeId)` takes effect on the next request. Every
API 6 method MUST pass the same per-transaction signature gate (S4-R007).

## 12. API 7 — inbound-only trust for a hub (D76–D78)

`apiVersion()` returns `7`. API 7 is additive: every API 1–6 method is
unchanged. It adds one method:

```aidl
String peerGrantJson(String json);
```

### S4-R049 (API 7)
`peerGrantJson({publicKey, label?, nodeId?, scopes})` MUST trust a reader that
only calls in, such as the hub on the operator's VPS. The `nodeId` MUST be
derived from the public key (`Ed25519.keyId`); a supplied `nodeId` that does not
match MUST be refused with `BAD_PUBLIC_KEY`, and this device's own key MUST be
refused with `PEER_SELF`. At least one known scope is required
(`SCOPE_REQUIRED` / `BAD_SCOPE`). The stored record MUST have an empty address,
MUST never be probed, and MUST resolve to a `Principal.Peer` with exactly those
scopes — never to an operator. Every API 7 method MUST pass the same
per-transaction signature gate (S4-R007).

## 13. API 8 — measured Doctor diagnostics (D80–D84)

`apiVersion()` returns `8`. API 8 is additive: every API 1–7 method is
unchanged. It adds one method:

```aidl
String doctorJson();
```

The value is a single integer that names the whole contract, so a client MUST
compare it before calling an API 8 method (S4-R028). The runtime reports the
constant `GromClient.API_VERSION` rather than a literal of its own: the number
drifted twice — `3` while the contract said 5, then `7` while it said 8 —
because two places were kept in step by hand. `tools/test-policy` now pins the
single-source rule.

### S4-R050 (API 8)
`doctorJson()` MUST return one measured Doctor report: a verdict, the ordered
checks with a stable level and code, and the facts behind them. It MUST be
read-only, MUST be callable while the server is stopped (the device can be
measured without it), MUST contain no credential, identifier, address or free
text, and MUST pass the same per-transaction signature gate as every other
method (S4-R007). A client MUST be able to tell `unknown` from a measured `false`
without parsing a sentence.

## 14. API 9 — host-configured data plane (D85, D86)

`apiVersion()` returns `9`. API 9 is additive: every API 1–8 method is
unchanged. It adds one method:

```aidl
String superSaveConfig(String json);
```

### S4-R051 (API 9)
`superSaveConfig(json)` MUST accept only a `SuperServerConfig.toJson()` document,
MUST validate it through the same contract the standalone screen uses (so a
refusal carries the screen's own stable code), MUST NOT write anything on
refusal, and MUST apply the document only when the data plane has been started.
It MUST return the sanitised status (`passwordSet`, key ids) and MUST NOT return
the stored password hash or any secret. It MUST pass the same per-transaction
signature gate as every other method (S4-R007), and `apiVersion()` MUST still
report `GromClient.API_VERSION` rather than a literal.

## 15. API 10 — the AI gateway (D94–D97)

`apiVersion()` returns `10`. API 10 is additive: every API 1–9 method is
unchanged. It adds seven methods:

```aidl
String aiStatusJson();
String aiSaveProviderJson(String json);
String aiRemoveProvider(String id);
String aiSaveModelJson(String json);
String aiRemoveModel(String id);
String aiConfigureJson(String json);
String aiProbeProviderJson(String providerId);
```

### S4-R052 (API 10)
`aiStatusJson()` MUST return the operator view: providers with their endpoint,
`keySet`, `configured`, `modelCount` and the last measured probe; models with
their upstream name and enabled flag; the default model; the load error and the
measured probes. It MUST NOT return an API key in any form, a sealed ciphertext or
a password hash, and MUST pass the per-transaction signature gate (S4-R007).

### S4-R053 (API 10)
`aiSaveProviderJson`, `aiSaveModelJson`, `aiConfigureJson`, `aiRemoveProvider` and
`aiRemoveModel` MUST validate through the same rules the screen uses, MUST write
nothing on a refusal, and MUST fail with an `IllegalStateException` carrying
`GROM_E_<CODE>` so a host can act on `BAD_PROVIDER_URL`, `INSECURE_PROVIDER_URL`,
`AI_UNKNOWN_MODEL` or `UNKNOWN_PROVIDER` instead of "it failed". A plaintext
`apiKey` MUST be sealed before the document is written and MUST NOT be echoed
back. Removing a provider MUST also remove the models that pointed at it and any
default that would dangle.

### S4-R054 (API 10)
`aiProbeProviderJson(providerId)` MUST make one real, minimal request to the
provider's endpoint and MUST report the measured result (`ok`, `code`, `detail`,
`latencyMs`, `atMs`, `model`). It MUST NOT run on a timer, MUST NOT be called by
`aiStatusJson`, and MUST refuse a disabled or unconfigured provider without
dialling. `apiVersion()` MUST still report `GromClient.API_VERSION` rather than a
literal.

## 16. API 11 — the server network (D99–D103)

API version **11** adds six methods. Every one applies `requireSameSigner()`.

| Method | Meaning |
|---|---|
| `meshStatusJson()` | operator view: switches, radio readiness, paired servers, nearby candidates; the only projection that carries an address |
| `meshConfigureJson(json)` | `{enabled?, advertise?, scan?}`; persists and applies |
| `meshScanJson()` | one bounded scan now; never starts a timer |
| `meshRefreshJson()` | re-reads each paired server's connect document (blocking) |
| `meshSetSharesJson(json)` | `{nodeId, services:[…]}`: the per-peer service allow-list (D101) |
| `meshCallJson(json)` | `{server, service, op, args?, body?}`: relays one signed call (D100) |

The data plane's roster (`GET /v1/mesh`) carries no endpoint; only this
signature-gated surface may show one, because the operator is the one approving
it. `GromClient.API_VERSION` is the single source of the number and
`apiVersion()` returns it.

## 17. API 12 — SSH diagnosis and the current server (D105–D107)

API version **12** adds two methods. Both apply `requireSameSigner()`.

| Method | Meaning |
|---|---|
| `sshVerify(id, trustFingerprint)` | one credential check: resolve, connect, handshake, host key and authentication are always attempted; the reverse forward only when the data plane is up. Blocking. |
| `sshSetActiveJson(id)` | marks the operator's current server; an empty id clears it. Returns `{ok, currentId}`. |

### S4-R058 — a check reports what it checked, and a failure names its stage
`sshVerify` MUST run without the data plane and MUST NOT require it to be
running. Its reply MUST carry `forwardChecked`, `false` when the reverse port was
not requested, and the UI MUST NOT render such a result as `connected`.

Every SSH failure reply MUST carry `stage`, one of `resolve`, `connect`,
`handshake`, `auth`, `forward`, and a `code` that follows from that stage
(D105). The host key MUST be the only source of `HOST_KEY_MISMATCH`.

`sshSetActiveJson` MUST store at most one current id for the file, MUST refuse
an id that is not saved (`TARGET_NOT_FOUND`), and deleting the current server
MUST clear the selection. The current server MUST NOT start, stop or poll
anything.

`sshVerify` MUST NOT leave a session open or register a tunnel; its only
permitted write is the host-key pin the operator confirmed.

## 18. The embed layer a host calls (D88–D90)

The Binder contract is what a *client* uses. An embedding host uses a thin
Kotlin layer on top of it, and that layer carries rules of its own because every
host would otherwise re-implement them slightly wrongly.

### S4-R055 — consent covers a notice version
`GromEmbed.recordConsent` MUST store the operator's answer together with the
notice version it was given for and the time it was recorded, and MUST clear the
record when the answer is `false`. `GromEmbed.start` MUST refuse with
`CONSENT_REQUIRED` when no answer is recorded and with `CONSENT_OUTDATED` when
the host declares a notice version different from the recorded one. A host that
declares no notice version MUST keep the pre-D88 behaviour. The record MUST stay
in the host's private preferences and MUST NOT be transmitted anywhere.

### S4-R056 — the control port is decided before the start
`GromEmbed.start` MUST resolve the port through `ControlPort.resolve` **before**
asking the platform for a foreground service, and MUST persist the resolved port.
`GromEmbed.AUTO_PORT` MUST be an explicit opt-in that selects a free port from
`ControlPort.AUTO_RANGE`, MUST reuse a stored port while it is free, MUST NOT
select the standalone default port, MUST NOT relocate a listener that is already
serving (the answer is `PORT_CHANGE_RESTART_REQUIRED`), and MUST report
`PORT_IN_USE` when the range is exhausted. Every refusal is a stable code
returned to the caller; none is a silent fallback.

### S4-R057 — health is a projection of the Doctor, and codes are the contract
`EmbedHealth.of(report)` MUST derive its state, headline and advice from a
`DoctorReport` and MUST NOT measure anything itself. It MUST produce
`EmbedState.RUNNING` only when every check measured `OK`; a `BAD` or `UNKNOWN`
row MUST NOT produce it. It MUST NOT turn a `NOTICE` into advice. Every advice
item MUST be a stable code plus an `EmbedAction`, and MUST NOT contain
user-visible prose. `EmbedHealth` and `EmbedConsent` MUST remain pure Kotlin (no
`android.*` import) so their rules run as JVM tests, and every code they can
emit MUST be documented in `docs/TWO-IN-ONE.md`.

`GromClient.bind(listener, createIfMissing = false)` MUST NOT create the service,
MUST return `false` when no server is running, and `unbind()` MUST release the
platform connection even after `onNullBinding` or `onBindingDied` (a registration
that is dropped without `unbindService` leaks for the life of the process).
