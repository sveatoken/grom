# S6 — Client connect (using Grom as a local server)

This document is the contract for an application that is *not* Grom and wants to
use a Grom server on the same phone or over the operator's network. It describes
discovery, compatibility, authentication, service listing, scoped calls, error
handling and reconnection.

It introduces no protocol. Every route below already exists on the public data
plane (S5); the only addition is `GET /grom/v1/connect`, a discovery document
(D91). There is no WebSocket, no MCP handshake and no binary framing.

Read S5 first for the data plane's own rules (bind modes, body caps, rate
limits). This document describes how a *client* behaves.

## 1. The one-paragraph version

```
GET  /grom/v1/connect          → how to authenticate, which scopes exist, which paths
POST /v1/auth/login            → password  → session token
(signed requests)              → Ed25519   → no token at all
GET  /v1/plugins               → services and their callable ops   (services.read)
POST /v1/plugins/{id}/rpc      → call one op                       (services.call)
GET  /v1/status                → measured status                   (status.read)
GET  /v1/config                → sanitised configuration export    (config.read)
```

A client is an ordinary HTTP/JSON client. It is never an operator: a connected
app holds explicit scopes and cannot reach the audit log, the token endpoints,
the event stream, the supervisor controls or any operation a service does not
publish to scoped callers.

## 2. Where the server is

### S6-R001
The client connects to the **data plane** (`/v1/*` and `/grom/v1/connect`), never
to the control plane (`/grom/v1/*` minus connect, loopback-only, bearer token).
The data plane is disabled by default (`enabled=false`, S5-R002); a connection
refused at the transport level means "the operator has not opened the public API
(or the phone is unreachable)", not "Grom is broken".

### S6-R002
The base URL is `scheme://host:port` with **no path, query, fragment or
userinfo**. A client MUST refuse to sign a request whose path was rewritten by a
proxy or a trailing-slash normalisation; the path is part of the signature
(S5-R018).

### S6-R003
The default bind is loopback. A client on the same phone uses
`http://127.0.0.1:47002`; a client off-device needs the operator to choose `LAN`
(or a reverse SSH tunnel, D58/D76) — that is an operator decision, never
something the client can change.

## 3. Discovery — `GET /grom/v1/connect`

### S6-R004
The endpoint is `GET /grom/v1/connect` on the data plane. It answers `200` with
the document below. It is the only document served *before* a credential exists,
besides `/v1/health` and the pairing routes.

Anonymous:

```json
{
  "ok": true,
  "schema": "grom-connect-v1",
  "service": "grom-super",
  "grom": "0.16.0-server29",
  "api": 1,
  "compat": {
    "minApi": 1, "maxApi": 1,
    "signature": "grom-ed25519-v1",
    "keyHeader": "x-grom-key", "timestampHeader": "x-grom-timestamp",
    "nonceHeader": "x-grom-nonce", "signatureHeader": "x-grom-signature"
  },
  "state": "ready",
  "timeMs": 1760000000000,
  "dataPlane": {
    "enabled": true, "running": true, "bind": "LOOPBACK",
    "address": "127.0.0.1:47002", "port": 47002, "maxBodyBytes": 65536
  },
  "auth": { "methods": ["password", "key"], "scopes": ["config.read", "services.call", "services.read", "status.read"], "login": "/v1/auth/login" },
  "principal": { "kind": "anonymous" },
  "capabilities": [],
  "services": { "listed": false, "path": "/v1/plugins", "requires": "services.read", "items": [] },
  "endpoints": { "connect": "...", "health": "/v1/health", "login": "/v1/auth/login", "logout": "/v1/auth/logout", "status": "/v1/status", "config": "/v1/config", "plugins": "/v1/plugins", "events": "/v1/events" }
}
```

With a valid credential the same document adds:

* `principal` = `{"kind":"peer","nodeId":"…"}` or `{"kind":"operator"}`;
* `capabilities` = the scope names this caller may exercise;
* `services.listed` = `true` when the caller holds `services.read` (or is an
  operator), and `services.items` then contains one object per service:

```json
{ "id": "agent", "title": "Agent", "description": "…",
  "capabilities": ["echo", "health", "kv.get", "kv.put", "ping", "status"],
  "scopedOps": ["echo", "kv.get", "kv.put", "ping"],
  "status": { } }
```

`status` is present only when the caller also holds `status.read` (S6-R008).

### S6-R005
The anonymous document publishes only what a client needs to connect. It MUST
NOT list services, and it MUST NOT contain a token, a password, a peer record, an
`npub`, a chat id or any configuration block. An unauthenticated caller must not
be able to use it to enumerate services.

### S6-R006
A credential presented to `connect` is **verified**. An invalid credential gets
`401 UNAUTHORIZED` — the anonymous answer must never be returned in its place,
because a host that signs with a revoked key would otherwise read "no services"
instead of "my key is gone".

### S6-R007
`timeMs` is the server's clock. A client SHOULD compare it with its own clock and
treat a difference above 240 s as a configuration problem, because signed
requests are accepted only within ±300 s (S5-R018) and a skewed clock produces
`SIGNATURE_STALE`, not a permission error.

## 4. Compatibility

### S6-R008
`api` is the data-plane wire version (S5-R004). `compat.minApi`/`compat.maxApi`
bound the versions this server accepts. A client implements one version; it MUST
refuse to continue when its version is outside the range instead of guessing.
`GromConnect.supports(clientApi)` is that check (D93).

### S6-R009
`schema` identifies the discovery document shape (`grom-connect-v1`). Additive
fields MAY appear at any time; a client MUST ignore fields it does not know. A
change to an existing field's meaning requires a new `schema` value and a new
`api` version.

### S6-R010
`api` is independent of `IGromServer.apiVersion()` (S4-R028). A client that speaks
HTTP MUST NOT use the Binder API number, and vice versa.

## 5. Authentication

Two mechanisms exist and `auth.methods` says which are live on this server.

### 5.1 Password → session

### S6-R011
`POST /v1/auth/login` with `{"password":"…"}` returns
`{"ok":true,"token":"…","expiresAtMs":…}`. The token is presented as
`Authorization: Bearer <token>` (or `x-grom-token`). Tokens expire (default 12 h)
and `POST /v1/auth/logout` revokes the current one. Password login may be
disabled (`PASSWORD_AUTH_DISABLED`) or throttled (`429 TOO_MANY_REQUESTS`).

### 5.2 Ed25519 — no token

### S6-R012
A client with its own Ed25519 identity signs every request. Headers:

```
x-grom-key:       <16 lowercase hex key id = sha256(publicKey)[0..16]>
x-grom-timestamp: <unix seconds>
x-grom-nonce:     <8..128 chars of [A-Za-z0-9_-], unique per request>
x-grom-signature: <base64 of the 64-byte Ed25519 signature>
```

The signed string is exactly:

```
grom-ed25519-v1\n
METHOD\n
path\n
timestamp\n
nonce\n
sha256hex(body)
```

`METHOD` is upper-cased, `path` includes the leading `/` and the service id
(`/v1/plugins/agent/rpc`), and the body is the exact UTF-8 text that is sent.
Query strings are not signed, so a client MUST NOT put parameters in the query.

### S6-R013
The nonce is single-use per server window (10 min). Replaying a request returns
`SIGNATURE_REPLAYED`. A nonce must never be derived from the body alone.

### S6-R014
Ed25519 is available when `auth` is `KEY` or `BOTH`. In `PASSWORD` mode it is
refused. The `x-grom-key` header only *names* a trusted key; possession is proven
by the signature over the canonical string. At grant time the operator pastes a
public key and the server derives its key id, so a client cannot register a
record under another device's id.

## 6. Granting a client access

### S6-R015
A client gets **scopes**, never an operator key. The operator grants them by
trusting the client's public key on the phone:

* Grom → **Devices** → **Trust a key**;
* paste the client's base64 public key, choose scopes, confirm.

The client then signs requests with that key. There is no code to exchange and no
auto-approval: the operator sees the key and the scopes (D77).

### S6-R016
The available scopes are:

| scope | grants |
|---|---|
| `status.read` | `GET /v1/status`; the `status` block inside a service listing |
| `config.read` | `GET /v1/config` (the sanitised export) |
| `services.read` | `GET /v1/plugins`, `GET /v1/plugins/{id}`, service listing in `connect` |
| `services.call` | `POST /v1/plugins/{id}/rpc`, limited to `scopedOps` |

Any other route — `/v1/events`, `/v1/auth/logout`, pairing, the control plane —
answers `403 SCOPE_REQUIRED` for a peer. A client that needs more than these
scopes should not be given an operator token; that is a product decision, not a
client one.

### S6-R017
Revocation is immediate on the next request: removing the peer from Grom's
Devices screen makes the next signed request `401 UNAUTHORIZED`. The client MUST
treat that as "the operator removed this device", stop retrying, and show a
re-pair instruction — not as a transient network failure.

## 7. Services and capabilities

### S6-R018
`GET /v1/plugins` returns
`{"ok":true,"plugins":[{"id","title","description","capabilities","scopedOps"[, "status"]}]}`.
`capabilities` is what the service can do; `scopedOps` is the subset a
`services.call` credential may invoke.

### S6-R019
`scopedOps` is owned by the service, not by the route. A service that moves funds
or changes configuration publishes an **empty** `scopedOps` and is operator-only;
`services.call` never implies "anything a service can do". The `agent` service
publishes `echo`, `kv.get`, `kv.put`, `ping` — its `status` op is not callable by
a scoped client (it carries the host's projects, bridge and fleet block).

### S6-R020
`status` appears only with `status.read`. It is deliberately not implied by
`services.read`: knowing a service exists is not knowing how it is doing.

## 8. Calling a service

### S6-R021
`POST /v1/plugins/{id}/rpc` with `{"op":"…","args":{…}}`:

```json
{ "ok": true, "plugin": "agent", "op": "kv.put",
  "result": { "stored": true } }
```

A failure keeps the standard envelope and the plugin's own status:

```json
{ "ok": false, "plugin": "agent", "op": "status",
  "error": { "code": "SCOPE_REQUIRED", "message": "this credential does not allow that" } }
```

### S6-R022
The client MUST treat `501 NOT_SUPPORTED` as "this backend cannot do that" and
`503` as "configured but not reachable". Neither is a client bug; a retry loop
without backoff is.

### S6-R023
`400 UNKNOWN_OP` and `400 INVALID_ARGS` are the plugin's rules, not the server's.
The client must not retry them unchanged.

## 9. Errors

### S6-R024
Every error uses `{"ok":false,"error":{"code":"…","message":"…"}}`. Codes are
stable; messages are for humans and may change. A client keys behaviour on the
code.

| code | status | meaning for the client |
|---|---|---|
| `UNAUTHORIZED` | 401 | no credential, or the key/token was revoked |
| `SCOPE_REQUIRED` | 403 | authenticated, but the scope or op is not granted |
| `SIGNATURE_STALE` | 401 | clock skew above 300 s |
| `SIGNATURE_REPLAYED` | 401 | nonce reused; generate a new one |
| `SIGNATURE_UNKNOWN_KEY` | 401 | the key is not trusted on this phone |
| `PAYLOAD_TOO_LARGE` | 413 | body above `dataPlane.maxBodyBytes` |
| `TOO_MANY_REQUESTS` | 429 | respect `Retry-After` |
| `METHOD_NOT_ALLOWED` | 405 | wrong verb; `Allow` header names the right one |

`TRANSPORT_FAILED` (status `0`) and `BAD_RESPONSE` are produced by the client
(SDK), not by the server.

## 10. Status, reconnect and lifecycle

### S6-R025
`GET /v1/status` is the measured status. The client MUST render a lamp only from
measured values, and MUST treat "never checked" as unknown, not as healthy
(D74's rule applied to clients).

### S6-R026
There is no server-side push to a scoped client. `/v1/events` is operator-only.
A client refreshes on open and on user action; it MUST NOT poll in the
background. Two `connect` or `status` calls per open screen are enough.

### S6-R027
Reconnection is a fresh request: the client re-reads `connect`, checks
`supports()`, and retries its credential. A connection error MUST NOT be cached
as an authorization result.

### S6-R028
A server that is disabled or refused has no listener, so the failure appears as a
transport error. The client message is "Grom's public API is off; open Grom →
Public access", not a stack trace.

## 11. Reference client

`app.grom.sdk.connect.GromConnectClient` (module `:sdk`) implements this
specification with no dependencies:

```kotlin
val client = GromConnectClient("http://127.0.0.1:47002")

// 1. Discovery, anonymous: how do I authenticate?
val info = client.connect()
if (!info.supports(GromConnectClient.API_VERSION)) error("grom is newer: ${info.gromVersion}")

// 2. A session, or a signer the host owns.
val session = client.session(client.login(password).token)

// 3. What may I use?
val authenticated = client.connect(session)
for (service in authenticated.services) {
    println("${service.id}: ${service.capabilities} callable=${service.scopedOps}")
}

// 4. Call a published op.
val answer = client.rpc(session, "agent", "kv.put", mapOf("key" to "hello", "value" to "world"))
```

`examples/connect` is the compiled, tested version of this flow; the engine test
`ConnectRouterTest` additionally proves the SDK's canonical string is identical
to the server's, so the two cannot drift.

For a signer, the host implements `GromSigner` with its own Keystore-backed key:

```kotlin
class KeystoreSigner(private val keyStore: KeyStore, private val alias: String) : GromSigner {
    override val keyId: String get() = /* sha256(publicKey)[0..16] */
    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(keyStore.getKey(alias, null) as PrivateKey)
            update(message); sign()
        }
}
```

The key never enters this library (D93).

## 12. The AI gateway (D94)

An application that wants a completion does not need a second connection or a
second credential: the AI gateway is part of the same data plane and the same
scope model.

### S6-R029
`GET /grom/v1/connect` carries an `ai` block (`enabled`, `configured`, the two
required scopes, the route paths). A client MUST read it before offering an AI
feature, and MUST NOT assume a gateway exists because the build supports one.

### S6-R030
`GET /v1/ai/models` (`ai.read`) is the catalogue: ids, providers and
`available`. `available` is configuration, not health — a client MUST NOT paint
it green (D96). The catalogue never contains an endpoint or a key.

### S6-R031
`POST /v1/ai/chat/completions` and `POST /v1/ai/v1/messages` (`ai.chat`) send the
OpenAI and Anthropic shapes; `stream: true` returns server-sent events. A client
MUST treat `AI_UPSTREAM_*`, `AI_TIMEOUT`, `AI_UNREACHABLE` and `AI_BAD_RESPONSE`
as provider problems, not as its own bug, and MUST NOT retry an
`AI_UNSUPPORTED_PARAM` or `AI_UNKNOWN_MODEL` unchanged.

### S6-R032
`services.call` MUST NOT be used to reach the AI gateway, and `ai.chat` does not
require it: the `ai` service publishes no `scopedOps`, and its operations are
gated by `ai.read`/`ai.chat`. A client MUST NOT ask the operator for an operator
token to make an AI request.

## 13. Out of scope

* **MCP** — not implemented. `/v1/plugins/{id}/rpc` already carries the same
  capability description and call shape; an MCP adapter can be a separate,
  optional bridge if a concrete MCP-only tool appears.
* **WebSocket / SSE for scoped clients** — `/v1/events` stays operator-only.
* **Dynamic plugin loading** — the registry is built in code (S5-R020), and a
  client cannot introduce a service.
* **Cloud accounts, registration, discovery beacons** — D70, S1-R004. The
  address comes from the operator, the tunnel or the provider's own tool.
* **TLS** — D47. The data plane is plain HTTP; use a trusted network or a tunnel.
