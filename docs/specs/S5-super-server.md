# S5 — Super server (public data plane)

The separately configurable data plane. The control plane in S2 is untouched:
`/grom/v1/*` stays loopback-only. This document is normative for `/v1/*`.

## 1. Planes and purpose

### S5-R001
Grom serves two independent HTTP planes from one process:

| plane | prefix | bind | credential |
|---|---|---|---|
| control | `/grom/v1/*` | loopback only (S2-R001, S1-R010) | bearer token (S2-R010) |
| data | `/v1/*` | `LOOPBACK`, `LAN` or `PUBLIC` (S5-R005) | password session or Ed25519 signature (S5-R019) |

The data plane is what D39 calls the super server. It exposes the agent and the
configured plugins; it does not expose the audit log, the token endpoints or the
supervisor controls. Agent state appears only as the health subset inside
`/v1/status`.

### S5-R002
The data plane MUST be disabled by default: `enabled=false`, bind `LOOPBACK`,
port `47002`, no listener. Enabling it and moving it off loopback are two
separate, explicit operator decisions. Neither is a default.

### S5-R003
A refused, failed or stopped data plane MUST NOT stop, restart or degrade the
control plane or the agent. The reason MUST be reported in
`GET /grom/v1/status` under `super.error` and MUST NOT be hidden by binding
another address.

### S5-R004
The data plane is versioned `v1`. `GET /v1/health` returns `api: 1` and
`service: "grom-super"`. Additive changes keep `v1`; removals require `v2`. This
version is independent of the control-plane `api` field and of
`IGromServer.apiVersion()` (S4-R028).

## 2. Bind policy and configuration

### S5-R005
Exactly three modes exist:

| mode | address | requirement |
|---|---|---|
| `LOOPBACK` | `127.0.0.1` | none; the default |
| `LAN` | the current site-local IPv4 address | a credential (S5-R008) |
| `PUBLIC` | `0.0.0.0` (all IPv4 interfaces) | a credential and `acknowledgePublicBind=true` |

IPv6 binds are not offered. The hostname is never resolved; there is no
`InetAddress.getByName` on the bind path.

### S5-R006
`LAN` MUST prefer a private (site-local) IPv4 address and MAY fall back to any
other non-loopback IPv4 address. Loopback, wildcard and link-local addresses are
never selected. When no candidate exists, start MUST be refused with
`NO_LAN_ADDRESS` and no listener. The address is resolved at start time; an
address change requires a restart or reload.

### S5-R007
The data-plane port defaults to `47002`; the control-plane port defaults to
`47001` (D23). The two ports MUST differ. A configured data-plane port MUST be
in `1..65535`.

### S5-R008
`SuperServerConfig.validate(controlPort)` MUST run before any socket is created
and MUST refuse with one of these stable codes:

| code | condition |
|---|---|
| `BAD_PORT` | port outside `1..65535` |
| `PORT_CONFLICT` | data-plane port equals the control-plane port |
| `BAD_BODY_LIMIT` | `maxBodyBytes` outside `1024..65536` |
| `BAD_CONNECTION_LIMIT` | `maxConnections` outside `1..64` |
| `BAD_SSE_LIMIT` | `maxSseClients` outside `1..16` |
| `BAD_SESSION_TTL` | `sessionTtlMs` outside 1 minute .. 7 days |
| `CREDENTIAL_REQUIRED` | non-loopback bind with no credential, or `auth=BOTH` with no credential |
| `PUBLIC_NOT_ACKNOWLEDGED` | `bind=PUBLIC` without `acknowledgePublicBind=true` |
| `PASSWORD_REQUIRED` | `auth=PASSWORD` with no password hash |
| `KEY_REQUIRED` | `auth=KEY` with no enabled key |
| `BAD_KEY` | blank `keyId`, or a public key that is not 32 bytes of base64 |
| `DUPLICATE_KEY` | two entries share a `keyId` |
| `BAD_BACKEND_TIMEOUT` | Lightning timeout outside `1..30` seconds |
| `INSECURE_BACKEND_URL` | Lightning `baseUrl` does not start with `https://` |
| `BAD_BACKEND_URL` | URL longer than 512 characters or containing whitespace |
| `BAD_NPUB` | `npub` is neither a valid NIP-19 `npub1…` nor a 64-character hex key |
| `BAD_RELAY` | a `relays` entry is not a valid `ws://` or `wss://` URL |
| `TOO_MANY_RELAYS` | more than 8 distinct relays |
| `BAD_REMOTE_BRIDGE` | bridge label/description too long, contains control characters, or resembles a secret |

The check order is: port, conflict, the three limit bounds, session TTL,
bind/credential rules, auth-mode rules, keys, backend, reference config
(npub, relays, bridge hint). The first failure wins.
A refusal MUST leave the server unbound, record the code and message in
`lastError`, and append a `super.refused` audit entry with the code as detail. A
stored password hash that cannot be decoded MUST refuse with `BAD_PASSWORD_HASH`.
A socket-bind failure MUST record `BIND_FAILED: <exception class>` and append
`super.bind.failed`. Neither case retries on a different address, and no case
starts the server on a configuration it could not honour.

## 3. Transport and routing

### S5-R009
The protocol is HTTP/1.1; a request with any other version is answered `505`.
`Connection: close` is the only lifecycle; keep-alive is not implemented and no
connection state survives one response. JSON responses carry
`Content-Type: application/json; charset=utf-8`, `Content-Length`,
`Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. A request that
declares a content type other than `application/json` is answered `415`; a
missing content type is accepted and treated as JSON.

### S5-R010
Routing order is fixed. `/v1/health` is answered before any credential work; the
rate limiter runs before login; authentication runs before path resolution, so
an unauthenticated caller cannot distinguish existing from non-existing paths
and learns nothing about key ids. An authenticated request to an unknown path is
`404 NOT_FOUND`. A known path with the wrong method is `405
METHOD_NOT_ALLOWED` with an `Allow` header. Every error body is
`{"ok":false,"error":{"code":"...","message":"..."}}`, where `code` is a stable
machine token and `message` is human text that MAY change. A response body MUST
NOT contain a stack trace, a file path or an exception class name.

### S5-R011
Requests are bounded before parsing: at most 8 KiB of headers (else `431`), at
most 64 KiB of body (else `413`), no `Transfer-Encoding` (else `400`). After
parsing, a body larger than the configured `maxBodyBytes` is answered
`413 PAYLOAD_TOO_LARGE`. A connection idle for 10 s MUST be closed.

### S5-R012
At most `maxConnections` sockets (default `16`) may be in flight; a connection
over the limit is refused immediately with `503 SERVER_BUSY` and MUST NOT wait in
an application queue. Requests are served by a fixed pool of 4 worker threads; no
handler may block the accept loop. At most `maxSseClients` event streams (default
`4`) may be open, refused with `503 TOO_MANY_STREAMS` above that. The in-process
bus additionally admits at most 8 subscribers, so the smaller of the two bounds
takes effect. A stream MUST end after 30 minutes and MUST emit a `: ping`
heartbeat whenever no event has been written for 15 s.

## 4. Authentication

### S5-R013
Passwords are stored only as `pbkdf2-sha256$<iterations>$<base64salt>$<base64hash>`,
derived with PBKDF2-HMAC-SHA256, a fresh 16-byte salt and 150000 iterations by
default, producing a 32-byte key. Comparison MUST be constant time
(`MessageDigest.isEqual`). A plaintext password MUST never be written to the
configuration, the audit log or the process log. The minimum password length is
8 characters. A login attempt with a blank password MUST cost the same as one
with a candidate, so response time does not disclose whether a candidate was
supplied.

### S5-R014
A session token is 32 random bytes, base64url without padding (43 characters).
Only its SHA-256 hex digest is kept, in memory; the token MUST never be logged or
persisted. The default TTL is 12 h (configurable 1 minute .. 7 days, see
S5-R008) and at most 16 sessions are held, least-recently-used evicted first. A
restart or reload builds a new store and therefore invalidates every session.

### S5-R015
Login failures are limited separately from request rate: 5 failures inside 60 s
lock the client out for 300 s. A success clears the client's history. The login
limiter is checked before the credential mode is examined, so a locked client
gets `429` even when password auth is disabled.

### S5-R016
Every path except `/v1/health` is subject to a per-client token bucket: capacity
60, refill 2 tokens per second, keyed by client IP address (never by credential).
Clients are held in an access-ordered map bounded at 1024 entries. An exhausted
bucket returns `429 TOO_MANY_REQUESTS` with `Retry-After` (at least 1 second,
capped at 3600; the login limiter caps at 86400).

### S5-R017
Signed requests carry exactly four headers:

| header | value |
|---|---|
| `X-Grom-Key` | the registered `keyId`; it is derived as the first 16 lowercase hex characters of SHA-256 over the public key when the configuration leaves it blank |
| `X-Grom-Timestamp` | Unix seconds |
| `X-Grom-Nonce` | `[A-Za-z0-9_-]{8,128}` |
| `X-Grom-Signature` | standard or URL-safe base64 of the 64-byte Ed25519 signature |

The canonical string is exactly six lines, with no trailing newline:
```
grom-ed25519-v1
<METHOD uppercase>
<path>
<timestamp>
<nonce>
<sha256-hex of body>
```
The body digest is lowercase hex of SHA-256 over the exact UTF-8 body; an empty
body hashes the empty string. The path is the request path with the query string
removed, percent-decoded, and trailing slashes trimmed (the router normalizes
before verification). It MUST match byte-for-byte; a single byte of difference
invalidates the signature. Query strings are NOT part of the signed string, so
every state-changing parameter MUST travel in the JSON body and MUST NOT be read
from the query string.

### S5-R018
A timestamp more than 300 s away from the server clock MUST be rejected. A
malformed nonce, unknown key id, malformed signature or a signature that does not
verify MUST be rejected. The nonce MUST be remembered only after the signature
verifies; a nonce seen inside a 600 s window MUST be rejected as a replay
(`SIGNATURE_REPLAYED`). The nonce cache holds at most 4096 entries. The verifier
distinguishes these reasons internally; S5-R019 keeps the response uniform, so
the wire answer is `401 UNAUTHORIZED` in every case.

### S5-R019
Three modes exist: `PASSWORD` (session tokens only), `KEY` (Ed25519 signatures
only) and `BOTH` (either). `BOTH` is the default. A session token is accepted when
the mode is not `KEY`; a signature is accepted when the mode is not `PASSWORD`. A
session token may be presented as `Authorization: Bearer <token>` or, for local
tooling, as `X-Grom-Token: <token>`. A request that presents neither, or a bad
one, is `401 UNAUTHORIZED` with the same body in every case. On the login
endpoint a wrong password is `401 INVALID_CREDENTIALS` (S5-R024); elsewhere every
failure is the uniform `401`.

## 5. Plugins and endpoints

### S5-R020
The plugin registry is fixed in code at start time. There is no dynamic class
loading, no plugin directory and no network-installed plugin. Plugin ids match
`^[a-z][a-z0-9-]{1,31}$` and are unique. This build registers exactly two:
`agent` and `lightning`.

### S5-R021
Every plugin answers `GET /v1/plugins/{id}` and `GET /v1/plugins` with
`{id,title,description,capabilities,status}`, where `capabilities` is a sorted
list and `status` is a side-effect-free snapshot containing no secret. The RPC
body is `{"op":"<name>","args":{...}}`; `op` is required and at most 64
characters, and `args` MUST be an object and defaults to `{}`. A missing `op` is
`400 BAD_REQUEST`.

### S5-R022
A plugin that cannot serve an operation MUST fail honestly: `501 NOT_SUPPORTED`
naming the capability for a capability the backend does not implement; `503
LIGHTNING_UNCONFIGURED` when no backend is configured; `503 LIGHTNING_UNAVAILABLE`
when the configured backend cannot be reached or answered garbage. A balance is
either measured by the backend or absent; Grom MUST NOT fabricate a balance, a
zero, a pending state or an invoice.

### S5-R023 — `GET /v1/health`
Unauthenticated and not rate-limited. Returns `200`:
```json
{"ok":true,"service":"grom-super","api":1,"uptimeMs":12345}
```
It MUST contain no token, no password hash, no key id, no backend URL and no
device identifier. Any method other than `GET` on this path is `405`.

### S5-R024 — `POST /v1/auth/login`, `POST /v1/auth/logout`, `GET /v1/status`
Login is unauthenticated but rate-limited. Body `{"password":"<password>"}`; a
body that is not a JSON object is `400 BAD_REQUEST`. On success it returns `200`:
```json
{"ok":true,"token":"<token>","expiresAtMs":1700000000000}
```
A wrong password returns `401 INVALID_CREDENTIALS`; password auth disabled
(`auth=KEY`) returns `403 PASSWORD_AUTH_DISABLED`; a locked client returns
`429 TOO_MANY_REQUESTS` with `Retry-After`. Logout is authenticated, revokes the
presented session token and returns `{"ok":true}`; revoking an unknown token is
not an error. Status is authenticated and returns the health fields plus `auth`,
`bind`, `plugins`, `address`, `port`, `limits` (`maxBodyBytes`,
`maxConnections`, `maxSseClients`), `agent` (the control-plane health document)
and `streams` (the current subscriber count). It contains no credential.

### S5-R025 — plugin endpoints
`GET /v1/plugins` returns `{"ok":true,"plugins":[...]}`. `GET /v1/plugins/{id}`
returns `{"ok":true}` merged with the description; an id that is not registered
is `404 UNKNOWN_PLUGIN`. `POST /v1/plugins/{id}/rpc` returns on success:
```json
{"ok":true,"plugin":"agent","op":"status","result":{...}}
```
and, on a plugin failure, the failure's HTTP status with:
```json
{"ok":false,"plugin":"lightning","op":"balance","error":{"code":"...","message":"..."}}
```
Paths below `/v1/plugins/` that do not resolve to `{id}` or `{id}/rpc` are `404
NOT_FOUND`.

### S5-R026 — `GET /v1/events`
Authenticated server-sent events; a non-`GET` method is `405`. The stream opens
with `retry: 5000` followed by a `hello` event, uses `event:`/`data:` frames
separated by a blank line, sends `: ping` as the heartbeat, and ends with a `bye`
event when the 30-minute lifetime expires or the server stops. Each subscriber
owns a bounded queue of 32 frames; when it is full the frame is dropped for that
subscriber rather than blocking the publisher.

## 6. Audit

### S5-R027
Data-plane lifecycle events MUST go through the existing hash-chained audit store
(D10, S2-R033): `super.start` on a successful bind, `super.stop` on stop,
`super.reload` on reload, `super.refused` on a policy refusal and
`super.bind.failed` on a socket failure. The detail MUST NOT contain a
credential. Agent operations relayed through the `agent` plugin are audited by
the control-plane runtime as `agent.exec`, exactly as an HTTP agent call is.

## 7. Non-goals

### S5-R028
The data plane explicitly does not provide: TLS in the process (S5-R029); order
matching; custody of funds; storage of wallet keys or seeds; dynamic plugin
loading; IPv6 binds; WebSocket (D46).

### S5-R029
Grom terminates no TLS. An operator exposing the data plane off loopback MUST
put a TLS terminator or an encrypted tunnel in front of it. The process speaks
plain HTTP on the configured address and makes no claim to confidentiality
without one.

## 8. Conformance

### S5-R030
The data plane is covered by JVM unit and integration tests only:
`SuperServerIntegrationTest` runs over a real loopback socket, and the auth,
Lightning and transport units are plain JVM tests. No device run, benchmark or
CI result is claimed for this feature.

## 9. Device facts in status

### S5-R031
The authenticated `GET /v1/status` MAY include a `referral` object describing
the device's local invite record (D50): `code`, `referredBy`, `shares`, and
`rewards: "coming_soon"`. It MUST NOT appear in `GET /v1/health`, which is
answered without credentials (S5-R004), and it is never transmitted anywhere
else. No amount, pending reward, remote install count or attribution exists
(D51).

## 10. Reference configuration (D52–D54)

### S5-R032
`SuperServerConfig` MAY carry `npub`, `relays` and `remoteBridge` as reference
configuration for an external bridge. They MUST validate as follows: `npub` is
a NIP-19 `npub1…` with a valid bech32 (not bech32m) checksum and a 32-byte
payload, or a 64-character hex key normalised to that form; every relay is a
`ws://`/`wss://` URL with a host, an optional port in `1..65535` and no userinfo,
query or fragment; at most 8 distinct relays; the bridge hint is at most 80
(label) / 240 (description) characters, contains no control characters and
resembles no secret. A stored secret (`nsec1…`, a PEM private key, a
password/API-key/token assignment or a bearer token) MUST be refused with
`BAD_REMOTE_BRIDGE`.

Grom MUST NOT open a network connection because of any of these fields: no
WebSocket client, no relay dial, no Nostr signing and no tunnel supervision. The
`superserver/nostr` package MUST contain no socket, HTTP client or WebSocket
symbol.

### S5-R033
The authenticated `GET /v1/status` and the `agent` plugin's `status` op MUST
include `npub` (canonical form or `null`), `relays` (canonical list) and
`remoteBridge` (`{label, description}` or `null`), plus
`lightningHealth: {configured, backend, reachable, error}`. The
`lightningHealth.reachable` value MUST be `null` until the Lightning adapter's
`health` op has actually run; a configured backend is not a reachable one. `GET /v1/health` and the
`agent` plugin's `health` op MUST NOT include the reference block.

### S5-R034
Changing only `npub`, `relays` or `remoteBridge` MUST be applied without stopping
the listener: `sameRuntimePolicy` compares the whole configuration with those
three fields nulled, and a reference-only reload updates the in-memory config and
appends `super.reload.reference` without invalidating sessions or SSE streams. A
change to any other field MUST rebuild the plane exactly as before.

The "Copy config" export is built from an explicit allow-list, never by
serialising `SuperServerConfig`. It MUST NOT contain the password hash, any key
material, the sealed backend credential, session tokens or audit records; the
static gate asserts that the credential identifiers are absent from
`ConfigExport` and `SuperStatusDocument`.


### S5-R035
The authenticated `GET /v1/status` and the `agent` plugin's `status` op MUST
include a `bridge` block
`{configured, connected, active, tunnels:[{id, label, state, sinceMs, lastError}]}`
and a `projects` array of `{id, title, version, package, capabilities,
registeredAtMs, lastSeenMs, state}`. `connected` MUST be true only while a
tunnel is actually up; `state` is derived from the heartbeat (D57, D59).

The `bridge` block MUST NOT contain the SSH host, username, port or host-key
fingerprint. Those stay on the device; the on-screen status may include them.

### S5-R036
`GET /v1/health` MUST remain unchanged: it carries no `bridge`, `projects`,
`npub`, `relays`, `remoteBridge`, referral or Lightning data.

### S5-R037
The Lightning lamp/data MUST be based on a measured probe. `lightningHealth.
reachable` is `null` until `lightningProbeJson()` (or the plugin's `health` op)
has run; status polling MUST NOT call the backend.

### S5-R038
The sanitised export MUST include `bridge` and `projects` and MUST continue to
omit every credential. The static gate asserts that `ConfigExport` and
`SuperStatusDocument` contain no credential identifier, and the sentinel test
asserts that no SSH address, password hash or sealed key reaches the document.

### S5-R039
`lightning.externalNode` MUST be treated as reference configuration: no gRPC
channel, no NWC connection, no socket. A URL-shaped endpoint, a kind-less record
or a value that looks like a secret MUST be refused with `BAD_EXTERNAL_NODE`.

### S5-R040
`/v1/status` and the sanitised export MUST publish `externalNode` as `kind`,
`label` and `configured` only. `endpointHint` MUST NOT appear in either
document; the static gate asserts the file opens no connection and the sentinel
test asserts the endpoint text is absent.

### S5-R041
A change that only touches reference fields — `npub`, `relays`, `remoteBridge`
or `lightning.externalNode` — MUST NOT restart the listener or drop sessions
(extends D54).

### S5-R042
`/v1/status` MUST carry a `notifications` block of `enabled`, `configured` and
`lastDelivery = {ok, atMs}`. `ok` is the measured result of the last delivery
attempt; it MUST NOT be derived from configuration. `GET /v1/health` MUST NOT
carry the block.

### S5-R043
The `notifications` block MUST NOT contain the chat ID, the bot token, or any
part of either. The sanitised export MUST publish the same three fields and
nothing else. The static gate asserts that neither `ConfigExport` nor
`NotifyStatus` names a credential identifier.

### S5-R044
Notification settings are device-level and independent of the data plane:
changing them MUST NOT stop, restart or rebind the listener, and a delivery
failure MUST NOT change the data plane's reported state. The agent plugin's
`status` op carries the block through the same `statusExtra` callback; its
`health` op does not.

### S5-R045
`/v1/pair/identity`, `/v1/pair/hello` and `/v1/pair/poll` are the only
pre-authenticated routes besides `/v1/health` and `/v1/auth/login`. They MUST
answer `PAIR_SESSION_CLOSED` (or `PAIR_UNKNOWN`) whenever no operator session is
open, and the code comparison MUST be constant-time with a bounded number of
attempts. They MUST pass the request rate limiter.

### S5-R046
A paired device MUST be authenticated with the same Ed25519 request signature as
an operator key and MUST be limited to its granted scopes: `status.read` for
`GET /v1/status`, `config.read` for `GET /v1/config`. Every other route MUST
answer `403 SCOPE_REQUIRED` for a peer. `/v1/config` MUST return the sanitised
`ConfigExport` document and MUST NOT serialise the live configuration.

### S5-R047
`/v1/status` MUST carry a `fleet` block with this device's `nodeId` and `label`
only. `GET /v1/health` MUST NOT carry it. A paired-device roster, an address, a
public key and a scope MUST NOT appear in either document or in the sanitised
export.

### S5-R048
Pairing or revoking a device MUST NOT restart the listener, drop a session or
rebind: the key lookup and the principal mapping read the peer store live
(extends D54).

### S5-R049
The public status is a projection, not a state store: `fleet` MUST be built from
the identity record alone, and the agent plugin's `status` op MUST carry the same
block through `statusExtra` without adding a peer list.

### S5-R050
A one-way grant (D77) MUST be admitted without a new route and without
restarting the listener: the granted key joins the live key lookup, the record
has no address, and it resolves to `Principal.Peer` with exactly the granted
scopes. `peerRefreshJson()` MUST skip a record with no address instead of
dialling it; skipping is not a failed measurement.

### S5-R051
The VPS hub (D76) is a separate operator-run tool and MUST NOT be a dependency
of the data plane. It polls `GET /v1/status` over the operator's reverse SSH
tunnel and serves its roster on **loopback only**; the roster MUST carry no
port, address, key or credential. A phone whose tunnel is down is `unreachable`
in the roster, never `alive`, and no phone behaviour depends on the hub being
reachable.

### S5-R052 (D84)
The authenticated `/v1/status` MAY carry a `doctor` object with exactly
`batteryExempt`, `backgroundRestricted` and `lastUnexpectedStopMs`. It MUST be
read at request time from a cheap probe, MUST NOT be cached at start, and MUST
carry nothing else from the diagnostics surface — no exit history, no free text,
no memory, thermal or network detail. `/v1/health` MUST remain unchanged, public
and free of the block.

### S5-R053
The Doctor MUST be local to the device apart from S5-R052: it MUST NOT appear in
`GET /v1/config`, in the sanitised export or in the fleet block, and the deep
system facts it cannot read (Doze whitelist, phantom-process limit, app-ops,
vendor lists) MUST be read by the operator over adb rather than by the app.

### S5-R054 (D86)
A data plane document supplied over IPC MUST be parsed with the same contract as
the standalone screen — the same field rules, the same validation and the same
64 KiB cap — and MUST be refused with the screen's stable code and no partial
write. A plaintext password MUST NOT be accepted; the document carries a PBKDF2
hash. Persisting a document MUST NOT start a listener that the runtime has not
started, and applying it MUST NOT require a new route.

### S5-R055 (D87)
The engine MUST remain an ordinary library so a host can embed it, and the
peripheral role MUST NOT package the native runtime. A packaging change that
re-adds the runtime to the peripheral role MUST fail a gate rather than only
enlarge an APK.

### S5-R056 (D91)
`GET /grom/v1/connect` MUST answer `200` with the discovery document of S6-R004:
`schema`, `service`, `grom`, `api`, `compat`, `state`, `timeMs`, `dataPlane`,
`auth`, `principal`, `capabilities`, `services` and `endpoints`. It MUST be
read-only (`GET` only) and MUST pass the request rate limiter.

### S5-R057 (D91)
The anonymous document MUST list no service (`services.listed=false` with an
empty `items`) and MUST NOT contain a token, a password hash, a peer record, an
`npub`, a chat id or a configuration block. A credential presented to the route
MUST be verified: an invalid one answers `401 UNAUTHORIZED`, never the anonymous
document. `/v1/health` MUST remain unchanged and MUST NOT gain the document.

### S5-R058 (D92)
`services.read` MAY enumerate services through `GET /v1/plugins` and
`GET /v1/plugins/{id}`, and `services.call` MAY invoke
`POST /v1/plugins/{id}/rpc`. An RPC op MUST additionally be listed in the
plugin's `scopedOps`; the default `scopedOps` is empty, so a plugin that does not
declare a callable operation exposes none. `scopedOps` MUST NOT contain an op
that reads the full status, changes configuration, or moves funds. A service's
`status` block MUST be present only for a caller holding `status.read`. Every
other route — `/v1/events`, `/v1/auth/logout`, pairing, the control plane — MUST
keep answering `403 SCOPE_REQUIRED` for a scoped peer.

### S5-R059 (D94)
`GET /v1/ai/models` MUST require `ai.read`; `POST /v1/ai/chat/completions`,
`POST /v1/ai/messages` and `POST /v1/ai/v1/messages` MUST require `ai.chat`. The
`ai` service MUST publish an empty `scopedOps` and declare its operations through
`Plugin.requiredScopes`, so `services.call` never implies an AI request. Every AI
route MUST pass the request rate limiter and MUST enforce the request bounds
before any upstream connection is opened.

### S5-R060 (D94, D97)
The `connect` document MUST carry an `ai` block with `enabled`, `configured`,
the two required scopes and the route paths. Its `items` MUST be empty unless the
caller holds `ai.read`. Neither the block nor `/v1/status` nor `GET /v1/config`
MUST contain a provider endpoint or a key. Streaming answers MUST use
`SuperResponse.Stream` on the existing listener with
`Content-Type: text/event-stream`, MUST decide every pre-flight refusal before
writing the head, and MUST stop reading the provider when the client disconnects.
