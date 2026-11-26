# S1 — Product and security model

Normative requirements for Grom F1. IDs are stable; do not renumber.
`MUST`/`MUST NOT`/`SHOULD` carry their RFC 2119 meaning.

> **Amendments (D39–D48).** The public data plane specified in S5 extends the
> product beyond one loopback listener. `S1-R001` now describes the control
> plane; an operator may separately enable the data plane of S5 under D39 and
> D41. `S1-R002` is narrowed, not repealed: Grom still holds no balance and
> decides no payment, and the Lightning adapter (D43) only forwards to an
> operator-configured backend. `S1-R003` is narrowed by D48 and D65: the process
> may open a connection to a backend URL the operator supplied at runtime and
> must never compile one in, it may post an operator-enabled notification to
> the single notification host (S1-R038), and it may run the opt-in server-network
> discovery described in S1-R049 (D99–D103). `S1-R010` applies unchanged to the control plane; for the
> data plane the bind is chosen by `SuperBindPolicy`, and a non-loopback choice
> requires a credential and, for the wildcard, an explicit acknowledgement
> (D41). No other S1 requirement changes.

## 1. Purpose

### S1-R001
Grom turns an Android device into a **local agent**: it exposes a small,
authenticated, loopback-only API through which an operator can query state and
trigger whitelisted local operations.

### S1-R002
Grom MUST NOT hold, compute, authorise, or transmit value. It has no ledger, no
balance, no account, no settlement, no payment credential.

### S1-R003
Grom MUST NOT initiate an outbound connection to the internet on its own. There
is no telemetry, analytics or crash reporting. Outbound connections are limited
to six explicit, operator-initiated cases:

1. the update check (S1-R033): one HTTPS GET to the public release feed, only
   after the operator asks for it;
2. the operator-configured Lightning backend (D48), using a URL supplied at
   runtime and never compiled in;
3. the operator-saved SSH target (D58): a reverse-tunnel session to a host the
   operator entered, opened only by an explicit tap, restricted to
   `tcpip-forward`, with a pinned host key and a client-side filter that allows
   only the local data plane as a destination;
4. the operator-enabled notification delivery (D65, S1-R038): one HTTPS POST to
   the single notification host, using a bot token the operator entered at
   runtime and a fixed message body;
5. a request to a **paired device** (D75): a signed HTTP request to the address
   the operator typed during pairing and that is stored in `PeerStore`, made
   only when the operator joins or refreshes the fleet. No host is compiled in,
   and the client uses the shared transport (S1-R040). The same path carries a
   server-to-server call to a paired device (D100), including a Bluetooth
   Classic carrier (`bt://<MAC>`, D102);
6. **server-network discovery** (S1-R049, D99): while the operator has enabled
   it, one signed UDP beacon to a fixed link-local multicast group, a BLE
   advertisement of eight bytes, a Wi-Fi Direct DNS-SD record, and an RFCOMM
   listener for bonded devices. None of these opens a connection to an arbitrary
   host, and none carries a request.

The VPS hub (D76) is an **operator-run tool outside the APK**; it adds no
outbound case to Grom, because the phone never connects to it. The hub connects
to the phone through the operator's existing reverse SSH tunnel.

There is no scheduler, no retry loop and no connection that Grom starts by
itself. The notification case allows at most one additional attempt per message
(D66) and no periodic tick of any kind.

### S1-R004
Grom MUST be usable with no account, no sign-in, and no cloud component.

## 2. Roles

### S1-R005
Two roles are shipped from one source tree: `server29` (minSdk 29) and
`peripheral26` (minSdk 26).

### S1-R006
Only the `server29` role may bind a listening socket. A `peripheral26` build
MUST NOT contain a live code path that binds.

### S1-R007
Both roles must be installable side by side on the same device only if the
application IDs differ; by default they do not, so the roles are mutually
exclusive installs.

## 3. Threat model

### S1-R008
Primary adversaries: (a) another app on the same device, (b) a process running
as the same Linux UID after a compromise, (c) a human with the unlocked device.

### S1-R009
Explicitly out of scope for F1: a malicious platform/OS, physical extraction of
app-private storage, side-channel attacks, and network attackers — there is no
network exposure (see S1-R010).

### S1-R010
The listener MUST bind loopback only. Binding to `0.0.0.0`, `::`, a LAN address,
or a hostname that resolves off-loopback is a hard failure, not a warning.

### S1-R011
Every state-changing API call MUST require a bearer token. The only exception is
the health endpoint, which MUST expose no secret and no user data.

### S1-R012
The IPC surface MUST be reachable only by applications signed with the same
certificate as Grom.

### S1-R013
Secrets (token and any future signing material) MUST NOT appear in process argv,
in the child environment, or in logs.

## 4. Auditability

### S1-R014
Every authenticated state-changing request MUST append exactly one audit entry.
The append MUST happen whether the operation succeeded or failed.

### S1-R015
Audit entries MUST be hash-chained so that deletion, reordering or editing of
any entry is detectable by replay.

### S1-R016
Audit verification MUST be available without stopping the server.

## 5. Honesty of state

### S1-R017
When the agent runtime is absent or unhealthy, the server MUST report that
state. It MUST NOT report `ok` for a capability it cannot perform.

### S1-R018
The UI and the health payload MUST agree about the runtime state at all times
within one supervisor tick.

### S1-R019
The application MUST NOT crash when the runtime is missing; it starts, serves
health, and returns `503` for agent endpoints.

## 6. Failure handling

### S1-R020
A crash of the agent process MUST NOT crash the server process.

### S1-R021
Three agent crashes within a rolling window MUST quarantine the agent and stop
restarts.

### S1-R022
Quarantine MUST require an explicit human action to clear and MUST survive
process death and device reboot.

### S1-R023
Automatic start on boot MUST be opt-in, persisted, and MUST NOT start a
quarantined agent.

## 7. Data

### S1-R024
The token and the audit log live in app-private storage. No world-readable or
world-writable file may be created.

### S1-R025
Grom MUST NOT persist user content beyond the audit metadata needed for
S1-R014.

## 8. Dependency and licence discipline

### S1-R026
Every third-party component MUST be listed in `third_party/THIRD_PARTY.md` with
version, licence and purpose.

### S1-R027
The repository licence is Apache-2.0 and every source file carries an SPDX
header.

### S1-R028
No dependency may be added that contacts the network at runtime.

## 9. Compatibility

### S1-R029
The HTTP API is versioned (`v1`) and reports its version in health. Additive
changes keep the version; removals require `v2`.

### S1-R030
The AIDL interface is versioned by `apiVersion()`. Clients MUST check it before
using methods introduced later.

## 10. Conformance

### S1-R031
Each requirement in S1–S4 maps to at least one automated check or to a named
manual check in `docs/F1-implementation-plan.md`.

### S1-R032
A conformance run is: `gradle test`, `tools/check-third-party`,
`tools/test-policy`, `tools/verify-ipc-signing`, `tools/test-agent`, and
`gradle lint`.

## 11. Updates

### S1-R033
The app MAY check for updates only as an explicit operator action. The check is
one HTTPS GET to `https://api.github.com/repos/sveatoken/grom/releases/latest`
with no account, identifier or token; it never runs on a schedule, on boot, or
in the background. A failed check MUST be reported as a failure, never as "up to
date". The app MUST NOT download or install an APK itself: a newer release is
handed to the operator as a link (the release page or the matching APK asset).

### S1-R034
The operator surface MUST be usable in both light and dark mode, and the mode
MUST be an explicit operator choice (Auto / Light / Dark) rather than something
the app changes on its own (D36).


## 12. Embedding (D55)

### S1-R035
The server engine MUST be usable as an Android library (`:engine`) by a host
application that is the same trust domain. In that mode the service is declared
`exported="false"` with no IPC permission, and the engine MUST refuse to start
until the host has recorded operator consent.

### S1-R036
`start` MUST report only that the platform accepted the foreground-service
launch. Readiness, runtime presence and project liveness MUST be measured from
the running server, never inferred from a configuration flag.

### S1-R037
The embedded surface (`projectsJson`, `announceProject`, `removeProject`,
`configExportJson`, `agentExec`, the SSH methods and `lightningProbeJson`) MUST
pass the same per-transaction signature gate as the API 1 surface (S4-R007).
Embedding does not relax the gate; it only makes caller and service the same
UID.

## 13. Notifications (D65–D67)

### S1-R038
Grom MAY deliver a short state notification to one operator-configured chat,
and only when all of the following hold:

1. the operator has switched notifications on;
2. a bot token and a numeric chat ID are configured;
3. the message text is chosen from a fixed table of event codes;
4. delivery is bounded: at most two attempts per message, no scheduler, no
   periodic tick, no disk queue, no replay.

The message MUST NOT contain a credential, an address, a device identifier or
any free-form text taken from an exception or a log line. The bot token MUST be
stored only as `SecretBox` ciphertext. The chat ID MUST NOT appear in
`/v1/status`, in the sanitised export or in the audit log. A delivery failure
MUST be reported as a failure with a stable code, never as success.

### S1-R039
Notification settings MUST NOT affect the data plane, the control plane or the
agent: enabling, disabling, changing or failing to deliver a message MUST NOT
change any other subsystem's state. `GET /v1/health` MUST NOT carry notification
state.

## 14. Multi-device fleet (D70–D75)

### S1-R040
A device identity MUST be a local Ed25519 key pair. The `nodeId` MUST be derived
from the public key (`Ed25519.keyId`), never supplied by a caller. The private
seed MUST be stored only as `SecretBox` ciphertext and MUST NOT appear in any
API response, status document, export, audit record or log. Grom MUST NOT
provide an account, a directory, a recovery service or an escrow for it.

### S1-R041
Pairing MUST be mutual and operator-confirmed on both devices: a short-lived
single-use code, a signature over a domain-separated transcript, a
constant-time code comparison with a bounded number of attempts, and a
fingerprint comparison on both screens. No automatic approval path may exist and
nothing may be stored before both confirmations.

### S1-R042
A paired device MUST be limited to the scopes granted at pairing. Those scopes
MUST come from the peer-grantable set (`status.read`, `config.read`,
`services.read`, `services.call`, `ai.read`, `ai.chat`); a local-client grant
(`mesh.read`, `mesh.call`) MUST be refused on both the joining and the accepting
side, because it names a permission the other operator cannot use (D104). A
revoked pairing MUST stop verifying on the next request. The operator's own
session and keys MUST keep their previous, unrestricted behaviour unless the host
explicitly maps a key to a peer.

### S1-R043
A paired-device address MUST NOT appear in `GET /v1/status`, in
`GET /v1/health` or in the sanitised configuration export. It is returned only
over the signature-gated Binder. `/v1/status` MAY report this device's own
`nodeId` and `label`; `/v1/health` MUST NOT report either.

## 15. VPS hub and inbound-only trust (D76–D78)

### S1-R044
A one-way grant MUST be an explicit operator action that supplies only a public
key: the `nodeId` is derived from that key, at least one scope is required, and
the resulting record MUST have no address. Such a record MUST never be dialled,
MUST be a scope-limited peer and MUST never become an operator key. A hub
(D76) MUST be an operator-run tool outside the APK; no Grom function may depend
on it. Its roster MUST listen on loopback only and MUST carry no address, port
or key.

## 16. Doctor and on-device diagnostics (D80–D84)

### S1-R045
Grom MUST provide a diagnostics surface that reports, per check, one of exactly
three kinds of outcome: a **measured** value (healthy or faulty), a **notice**
(measured, neither healthy nor faulty) or **unknown** (this Android did not
answer). A check MUST be reported healthy only from a value the platform
returned; configuration MUST NOT produce a healthy outcome. The headline MUST NOT
report success while any check is unknown. The surface MUST NOT change a system
setting, execute a shell, request root, start a timer or poll in the background;
it MAY request the platform's own battery-exemption dialog, which is the single
system change it is allowed to ask for. An unexpected process end MUST be
reported as a fact derived from a run marker, and its cause MUST be reported only
when the platform supplies one; below API 30 the cause MUST be unknown. The
diagnostic report MUST be an allow-list that carries no token, chat id, SSH host,
key, address or free text.

## 17. Two Groms on one phone (D85, D87)

### S1-R046
A phone MAY hold more than one Grom — the standalone application and a host
application that embeds the engine are separate packages. Each instance MUST keep
its own storage, token, node identity and peer store; no instance may read or
share another's. An embedding host MUST be able to choose its loopback control
port. A start whose port is already held MUST report the stable code
`PORT_IN_USE`; it MUST NOT silently move to another port, and it MUST NOT be
reported as a signature or network failure. The peripheral role MUST NOT package
the native runtime it never executes.

### S1-R047 (D91, D92)
An application that uses Grom as a server is a **scoped client**, never an
operator. It holds only the scopes the operator granted, it is identified by its
own Ed25519 key (or a password session the operator enabled), it cannot read the
audit surface, the event stream or the configuration export without the matching
scope, and it cannot invoke an operation a service does not publish to scoped
callers. `services.call` MUST NOT be treated as a weaker operator token.

### S1-R048 (D94, D95)
The AI gateway is an **outbound case**, and it is the only one whose destination
comes from an operator-entered base URL rather than a compiled-in host: a request
MUST go only to the endpoint of a saved, enabled provider that has a stored key,
and to no other host. The endpoint MUST use `https`, or `http` only when the host
is loopback. Redirects MUST NOT be followed. A provider API key MUST be sealed at
rest, MUST NOT appear in any status, export, error or log, and MUST NOT be
readable through any scope: `ai.read` and `ai.chat` grant the catalogue and the
completion, never the credential. The gateway MUST NOT poll, probe in the
background, or contact any host merely because it is configured.

### S1-R049 (D99–D103)
Server-network discovery MUST be **off by default**, MUST start and stop with
the server, and MUST be limited to the four carriers above. A discovery sighting
MUST NOT create a peer: only the pairing handshake (S1-R041) does. A LAN beacon
MUST be signed and timestamped and MUST be rejected outside a 120-second skew.
A BLE advertisement MUST NOT carry a request. Every discovery carrier MUST be
treated as untrusted input: the candidate book MUST be bounded and MUST expire.
Discovery MUST NOT cause the public data plane to bind a non-loopback address.
