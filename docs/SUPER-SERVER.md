# Super server — operator guide

The super server is Grom's optional public data plane: a second HTTP listener on
`/v1/*` that can be bound to loopback, the LAN or every interface, with its own
credential. The control plane on `/grom/v1/*` is unchanged and stays
loopback-only (S1-R010, S2-R001).

It is **off by default** and **loopback-only** until an operator changes it. It
is specified in [`specs/S5-super-server.md`](specs/S5-super-server.md); this page
is how to run it.

## 1. Enable it in the app

The **Public API** card is the access card. Every row is a tap target:

| row | what it does |
|---|---|
| **Enable** | Turns the listener on or off. On is refused unless a password or key already exists; the password dialog opens instead. |
| **Bind** | Cycles loopback → LAN → all interfaces. A non-loopback choice is refused without a credential. Choosing *all interfaces* shows a warning to put a TLS terminator in front. |
| **Auth** | Cycles password → key → both. Selecting password with no password set, or key with no keys, is refused. |
| **Password** | Sets a password (at least 8 characters) or clears it. Only a PBKDF2 hash is stored. |
| **API keys** | Generates an Ed25519 key pair, or removes all keys. The private seed is shown once, in base64url, and copied to the clipboard; it is never stored by Grom. It also lists the key ids already registered. |
| **Lightning** | Sets the LNbits HTTPS base URL and the API key. The key field may be left blank to keep the stored key. *Clear* removes the backend. |

Changes are applied through the running server. The app writes the configuration
file first and then calls `reloadSuperServer()` (IPC API 2, S4-R029), which
rebuilds only the data-plane listener; the control plane and the agent are not
restarted. If the server is not running, the toast says the reload failed and
the saved file takes effect the next time the server starts. Enabling or
changing access requires the server role to be running to take effect
immediately.

A reload builds a new session store, rate limiter and nonce cache. **Every
signed-in session is invalidated by any access change.**

## 2. The configuration file

Path: `<files>/grom/super-server.json`, i.e. under the app's private data
directory, next to `grom-token`, `node/` and `audit/`. The app writes it
atomically (temp file, owner-only permissions, rename over the old one) and
refuses to load a file larger than 64 KiB. A corrupt, oversized or non-object
file falls back to the safe defaults and the reason appears in
`super.error`; it can never turn into an open listener.

```json
{
  "version": 1,
  "enabled": false,
  "bind": "LOOPBACK",
  "port": 47002,
  "auth": "BOTH",
  "passwordHash": "pbkdf2-sha256$150000$<base64-salt>$<base64-derived-key>",
  "keys": [
    {
      "keyId": "<16-hex-key-id>",
      "label": "<label>",
      "publicKey": "<base64-32-byte-ed25519-public-key>",
      "enabled": true
    }
  ],
  "lightning": {
    "kind": "none",
    "baseUrl": "",
    "timeoutMs": 8000,
    "apiKeySealed": "<base64-sealed-api-key-or-null>",
    "externalNode": {
      "kind": "none",
      "label": "",
      "endpointHint": ""
    }
  },
  "acknowledgePublicBind": false,
  "sessionTtlMs": 43200000,
  "maxBodyBytes": 65536,
  "maxConnections": 16,
  "maxSseClients": 4,
  "npub": null,
  "relays": [],
  "remoteBridge": null
}
```

Field notes:

- `bind` is `LOOPBACK`, `LAN` or `PUBLIC`; `port` is an integer; `auth` is
  `PASSWORD`, `KEY` or `BOTH`.
- `passwordHash` is the encoded PBKDF2 value, never a password and never
  reversible. `keys[].publicKey` is the 32-byte Ed25519 public key in standard
  or URL-safe base64. A supplied `keyId` is used as-is; when it is omitted it is
  derived as the first 8 bytes of the key's SHA-256, as 16 lowercase hex
  characters.
- `lightning.apiKeySealed` is **AES-256-GCM ciphertext produced by the Android
  Keystore** (`SecretBox`, alias `grom-super-secrets`), base64 of
  `version(1) || ivLength(1) || iv || ciphertext+tag`. The plaintext backend key
  never reaches the file. A value sealed by a different or deleted keystore key
  cannot be opened; the backend is then reported unconfigured rather than
  guessed. Only the app's Lightning dialog produces a valid value, so do not
  hand-write this field.
- `lightning.kind` is the lowercase wire name: `none` or `lnbits`.
- `lightning.externalNode` (D63) is a **reference** to a node on the operator's
  own server: `kind` (`none`, `lnd`, `cln`, `nwc`), a label (≤ 64) and an
  endpoint hint (`host` or `host:port`, ≤ 253, never a URL). Grom opens no
  gRPC/NWC/socket connection to it. `endpointHint` stays on the device:
  `/v1/status` and *Copy config* publish `kind`, `label` and `configured` only.
- `npub` (D52) is the operator's Nostr **public** key: a NIP-19 `npub1…`
  (bech32, 32-byte payload) or a 64-character hex key, stored in canonical
  lowercase form. An `nsec1…` secret key is rejected. Grom never signs.
- `relays` (D52) is a list of at most 8 `wss://` or `ws://` URLs. It is a
  **reference** for an external bridge: Grom opens no WebSocket and no relay
  connection. A `ws://` URL is accepted because it may be a local relay; the app
  labels it as plaintext.
- `remoteBridge` (D52, D54) is `{"label": "…", "description": "…"}`, at most 80
  and 240 characters. It is a signpost for a tunnel the operator runs elsewhere
  (SSH, WireGuard, Tailscale); Grom does not start it. A value that looks like a
  private key, password, API key or bearer token is refused.
- `acknowledgePublicBind` must be `true` for `PUBLIC`; the app sets it when you
  cycle to *all interfaces*.
- Bounds: `port` 1–65535 and different from the control port; `maxBodyBytes`
  1024–65536; `maxConnections` 1–64; `maxSseClients` 1–16; `sessionTtlMs`
  1 minute–7 days; Lightning `timeoutMs` 1–30 s.
- Unknown or malformed individual values fall back to defaults. A typo in
  `bind` therefore yields `LOOPBACK`, not an error; check `super.error` and the
  status address rather than assuming the file was honoured.
- A malformed `relay` is **not** silently dropped on load: validation reports
  `BAD_RELAY`, so a typo cannot quietly disappear from the list.
- Changing only `npub`, `relays`, `remoteBridge` or `lightning.externalNode` is
  applied **in place**: the listener is not restarted and active sessions/SSE
  streams survive (D54, S5-R041). Changing anything else rebuilds the plane and
  invalidates sessions.
- `version` is written for future format changes and is not currently read.

## 3. curl recipes

Replace `<password>` and `<token>`; never put a real credential in a file that
is committed or shared.

```bash
export BASE=http://127.0.0.1:47002

# health — no credential, no secrets
curl -s "$BASE/v1/health"

# connect — what a client needs before it owns a credential (D91).
# It lists no service until a credential is presented.
curl -s "$BASE/grom/v1/connect"

# login (BOTH or PASSWORD mode)
curl -s -X POST "$BASE/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"password":"<password>"}'

# keep the token for the calls below
export TOKEN=$(curl -s -X POST "$BASE/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"password":"<password>"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# status and plugin inventory
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/v1/status"
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/v1/plugins"

# the same connect document, now with this caller's capabilities and the
# service list (D91)
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/grom/v1/connect"

# the device's own invite record (D50) is part of that status document
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/v1/status" | grep -o '"referral":{[^}]*}'

# the reference block an external bridge reads (D52, D53)
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/v1/status" | grep -o '"npub":[^,]*'
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/v1/status" | grep -o '"relays":\[[^]]*\]'
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/v1/status" | grep -o '"lightningHealth":{[^}]*}'

# the agent plugin's status carries the same reference block; `health` does not
curl -s -X POST "$BASE/v1/plugins/agent/rpc" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"op":"status","args":{}}'

# agent plugin: status, then a whitelisted op
curl -s -X POST "$BASE/v1/plugins/agent/rpc" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"op":"status","args":{}}'
curl -s -X POST "$BASE/v1/plugins/agent/rpc" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"op":"kv.put","args":{"key":"k","value":"v"}}'

# lightning plugin: measured balance, then an invoice
curl -s -X POST "$BASE/v1/plugins/lightning/rpc" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"op":"balance","args":{}}'
curl -s -X POST "$BASE/v1/plugins/lightning/rpc" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"op":"invoice.create","args":{"amountMsat":1000000,"memo":"<memo>"}}'

# server-sent events: -N disables buffering
curl -N -H "Authorization: Bearer $TOKEN" "$BASE/v1/events"

# logout
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE/v1/auth/logout"
```

With `bind=LAN` or `PUBLIC`, use the address the status document reports instead
of `127.0.0.1`. On an Android device you can reach a loopback listener through
`adb forward tcp:47002 tcp:47002`.

A third-party application does not use an operator token at all. It is a
**scoped client** (D92): the operator trusts its public key under **Devices →
Trust a key** and grants `services.read` and/or `services.call`. With
`services.call` it may invoke only the operations the service publishes in
`scopedOps` — for the `agent` service that is `echo`, `kv.get`, `kv.put` and
`ping`; the Lightning adapter publishes none. The full contract, including the
discovery document and the error table, is
[`specs/S6-client-connect.md`](specs/S6-client-connect.md).

## 4. Signing requests with Ed25519

`auth=KEY` (or `BOTH`) accepts requests signed with a key the operator
registered. The server never sees the private key.

Register a key generated outside the app. The key id is the first 8 bytes of
SHA-256 over the raw public key, as 16 lowercase hex characters.

```bash
# 1. key pair (OpenSSL 3.x)
openssl genpkey -algorithm ed25519 -out grom-key.pem

# 2. raw 32-byte public key, base64 — paste into keys[].publicKey
openssl pkey -in grom-key.pem -pubout -outform DER | tail -c 32 | base64

# 3. key id — paste into keys[].keyId
openssl pkey -in grom-key.pem -pubout -outform DER | tail -c 32 \
  | sha256sum | cut -c1-16
```

Write both values into `super-server.json` and restart the server: a key
generated outside the app cannot be imported through the UI, and a hand-edited
file is read at the next start. The app rewrites the whole file from its own
in-memory copy whenever an access row is tapped, so do not edit the file by hand
while the app is holding a different copy. Then sign a request:

```bash
export BASE=http://127.0.0.1:47002
export KEY_ID=<16-hex-key-id>
PATH_=/v1/plugins/agent/rpc
BODY='{"op":"status","args":{}}'
TS=$(date +%s)
NONCE=$(openssl rand -base64 24 | tr -d '+/=\n')

# Canonical string: exactly six lines, no trailing newline after the digest.
BODY_HASH=$(printf '%s' "$BODY" | sha256sum | awk '{print $1}')
printf 'grom-ed25519-v1\nPOST\n%s\n%s\n%s\n%s' \
  "$PATH_" "$TS" "$NONCE" "$BODY_HASH" > canonical.txt

SIG=$(openssl pkeyutl -sign -rawin -inkey grom-key.pem -in canonical.txt \
  | base64 | tr -d '\n')

curl -s -X POST "$BASE$PATH_" \
  -H "Content-Type: application/json" \
  -H "X-Grom-Key: $KEY_ID" \
  -H "X-Grom-Timestamp: $TS" \
  -H "X-Grom-Nonce: $NONCE" \
  -H "X-Grom-Signature: $SIG" \
  -d "$BODY"
```

The canonical string must match **byte-for-byte** (S5-R017):

```
grom-ed25519-v1
POST
/v1/plugins/agent/rpc
1700000000
<nonce>
<sha256 hex of the body>
```

- The method is upper-cased; the other lines are verbatim.
- The path is the decoded request path with the query string removed and
  trailing slashes trimmed. Do not sign the query string, and do not put a
  state-changing parameter in it.
- The body digest covers the exact UTF-8 bytes sent, including whitespace. If
  the shell adds or drops a character, the signature fails.
- Timestamp must be within 300 s of the device clock; a nonce is single-use.
- `openssl pkeyutl -sign -rawin` must be available (OpenSSL 3.x); on older tools
  use any Ed25519 implementation that returns the raw 64-byte signature.

## 5. Network exposure

- **Loopback (default).** `127.0.0.1` only. Reachable from the device itself, or
  from a workstation through `adb forward`. Nothing is exposed to the network.
- **LAN.** Binds the device's *current* site-local IPv4 address. The address is
  chosen when the server starts; after a Wi-Fi or network change,
  restart/reload so the new address is picked. If no usable address exists the
  server refuses with `NO_LAN_ADDRESS` rather than binding something else.
- **PUBLIC.** Binds `0.0.0.0`, every IPv4 interface, and requires
  `acknowledgePublicBind=true`. It still requires a credential. This is the
  mode to use behind a reverse proxy, not the mode to leave running.

Grom speaks plain HTTP in-process. Put a TLS terminator (Caddy, nginx) or an
encrypted tunnel (WireGuard, Tailscale, an SSH tunnel) in front of the port
**before** exposing it. Without one:

- Password login over plain HTTP sends the password on the wire, readable by
  anyone on the path. The session token that follows is equally readable.
- Ed25519 signatures do not reveal the private key and a captured request cannot
  be replayed, but the request and response bodies are still in clear text.

Behind CGNAT a phone has no reachable inbound address at all. Binding `PUBLIC`
or `LAN` does not change that. Use a tunnel initiated **from the device** to a
host with a public address. There is no port-forwarding recipe that fixes CGNAT
from the outside. Section 10 has the concrete pattern for an external VPS.

## 6. Resource footprint

| resource | bound |
|---|---|
| accept thread | 1 (`grom-super-accept`) |
| request workers | 4 (`grom-super-http-1..4`) |
| connections in flight | 16 by default, refused with `503 SERVER_BUSY` above that |
| event streams | one thread each, 4 by default (bus admits 8) |
| per-subscriber queue | 32 frames, dropped when full |
| sessions | 16, least recently used evicted |
| rate-limiter clients | 1024 IPs, least recently used evicted |
| nonce cache | 4096 entries, 600 s window |

SSE is used instead of WebSocket because the traffic is one-way server events:
SSE is plain HTTP/1.1, so it reuses the same parser, the same auth path and the
same `Connection: close` lifecycle, and every TLS terminator and reverse proxy
forwards it without an upgrade handshake or a framing library (D46). Grom opens
no connection of its own for events; the client does.

## 7. Troubleshooting

| symptom | code | meaning and fix |
|---|---|---|
| Listener not running, `super.error` names it | `CREDENTIAL_REQUIRED` | A non-loopback bind was chosen without a password or key, or `auth=BOTH` has neither. Set a credential, or go back to loopback. |
| Listener not running | `PUBLIC_NOT_ACKNOWLEDGED` | `bind=PUBLIC` without `acknowledgePublicBind=true`. Use the app (it sets it) or set the field deliberately. |
| Listener not running | `NO_LAN_ADDRESS` | LAN mode and no usable IPv4 address. Connect to Wi-Fi/Ethernet and restart/reload. |
| Listener not running | `BIND_FAILED: <class>` | The socket could not be bound, typically the port is in use. Change the port; there is no retry on another address. |
| Login answers `403` | `PASSWORD_AUTH_DISABLED` | `auth=KEY`; use a signed request, or change the mode to `BOTH`. |
| Login answers `429` | `TOO_MANY_REQUESTS` | Rate limit or 5 failed logins in 60 s (300 s lockout). Wait for `Retry-After`, then retry. |
| Any endpoint answers `401` | `UNAUTHORIZED` | Missing, malformed, expired or wrong credential; a signed request with a wrong canonical string, stale timestamp or reused nonce looks identical by design. Check the header set and the canonical bytes. A reload also invalidates sessions. |
| `503 TOO_MANY_STREAMS` | | Four event streams are already open. Close one; streams end on their own after 30 minutes. |
| `503 SERVER_BUSY` | | 16 connections in flight. Retry later; the server does not queue. |
| `501 NOT_SUPPORTED` | | The configured backend does not implement the capability (see `docs/LIGHTNING.md`). |

## 8. What is tested, and what is not

The data plane has JVM unit and integration tests only. `SuperServerIntegrationTest`
binds `127.0.0.1:0` and exercises the real parser, worker pool, auth path and SSE
writer; the auth, Lightning and transport classes have plain JVM tests. The
reference configuration has its own tests: `Nip19Test` (including the NIP-19
vector), `RelayUrlTest`, `RemoteBridgeHintTest`, `SuperStatusDocumentTest` and
`ConfigExportTest` (which asserts that no stored secret appears in the export).
Nothing here has been run on a physical device or benchmarked; those tests are
the only evidence. The UI path, the Android Keystore seal and the actual network
bind of a LAN/PUBLIC address are code-reviewed, not measured.

## 9. The `referral` field in status

An authenticated `GET /v1/status` includes the device's local invite record
(S5-R031, D50):

```json
"referral": {
  "code": "K7M2Q9XA",
  "referredBy": null,
  "shares": 0,
  "rewards": "coming_soon"
}
```

`code` and `referredBy` are 4–12 characters from the Crockford base32 alphabet;
`referredBy` is `null` until the operator pastes a friend's code in the app.
`shares` counts copy/share actions started on this device, not people reached.
`rewards` is always `"coming_soon"` — there is no amount, no pending value and
no reward ledger (D51).

None of it is in `GET /v1/health`, which answers without credentials. Referral
data is device identity; it belongs to the control plane, not a data endpoint
anyone who reaches the port can read (D39).

## 10. Reference config for an external VPS bridge

Grom can hold four pieces of **reference configuration** for a bridge that runs
elsewhere — for example a Nostr/Lightning service on a VPS (D52):

```json
"npub": "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg",
"relays": ["wss://relay.example", "ws://127.0.0.1:7777"],
"remoteBridge": {
  "label": "Mostro VPS",
  "description": "SSH reverse tunnel; Grom does not start it"
},
"lightning": {
  "externalNode": { "kind": "lnd", "label": "VPS node", "endpointHint": "" }
}
```

What Grom does with it: it validates it, persists it in `super-server.json` and
reports it in the **authenticated** `GET /v1/status` and the `agent` plugin's
`status` op. `reachable` in `lightningHealth` is `null` until the adapter has
actually been probed; Grom does not turn "configured" into "healthy".

What Grom does **not** do with it:

- it does not open a WebSocket to a relay;
- it does not open a gRPC, NWC or socket connection to `externalNode`
  (`endpointHint` never leaves the device);
- it does not sign a Nostr event (it never has a private key — an `nsec1…`
  value is refused);
- it does not start, supervise or health-check a tunnel.

### 10.1 Tunnel patterns

<details>
<summary>1. Reverse SSH, initiated by the phone</summary>

On the phone (e.g. through Termux or a dedicated key), initiate:

```bash
ssh -N -R 47002:127.0.0.1:47002 bridge@vps.example
```

Then on the VPS, `http://127.0.0.1:47002/v1/status` reaches the phone's data
plane. The VPS never needs an inbound port and the phone needs no public
address. Keep the data plane on `LOOPBACK`; the tunnel is the only path.

</details>

<details>
<summary>2. WireGuard / Tailscale / Headscale</summary>

Put the phone and the VPS in the same private network. Two options:

- keep Grom on `LOOPBACK` and let the mesh peer reach it through a local
  forwarder on the phone;
- bind `LAN` so the phone's private-network address is reachable directly. This
  still requires a credential and still speaks plain HTTP inside the encrypted
  mesh.

</details>

<details>
<summary>3. Behind CGNAT</summary>

There is no inbound address. `PUBLIC` bind does not help. The tunnel must be
initiated from the device; patterns 1 and 2 are the supported ones.

</details>

<details>
<summary>4. TLS</summary>

Grom has no in-process TLS (S5-R029). Put Caddy, nginx or an encrypted tunnel in
front of the port. On a reverse SSH tunnel, terminate TLS on the VPS.

</details>

`remoteBridge` is a **label**, not an automation. It never changes what Grom
does; it exists so the operator and the VPS-side config can refer to the same
tunnel by name. Use the app's **Copy config** action to move the sanitised
hand-off document (npub, relays, bridge label, data-plane shape, Lightning
health) to the VPS without ever copying a password hash, a key or a sealed
backend credential.


## 11. Notifications in the status document

An authenticated `GET /v1/status` — and the `agent` plugin's `status` op —
include the operator-notification state (D65–D67):

```json
"notifications": {
  "enabled": true,
  "configured": true,
  "lastDelivery": { "ok": false, "atMs": 1730000000000 }
}
```

`configured` means a bot token and a numeric chat ID are stored; `enabled` is the
operator's switch. `lastDelivery.ok` is the **measured** result of the last
attempt: `true` only when the provider answered `{"ok":true}`, `false` after a
rejection, and `null` (never sent) before the first attempt. Configuration alone
never makes it `true`.

Deliberately absent: the chat ID and the bot token. `chatId` identifies the
device's owner, so it stays on the phone exactly like an SSH host (D59); the
token is a credential and is stored only as Keystore ciphertext.

None of it is in `GET /v1/health`, which answers without credentials (S5-R042).

See [`NOTIFICATIONS.md`](NOTIFICATIONS.md) for setup, the event table, the
delivery limits and what cannot work: when the process is dead, no alert is
sent, because Grom is the sender.

## 12. The AI gateway on this listener

The AI gateway is not a second listener: it is three routes and one service on
the data plane this document describes.

| route | scope | shape |
|---|---|---|
| `GET /v1/ai/models` | `ai.read` | the callable catalogue |
| `POST /v1/ai/chat/completions` | `ai.chat` | OpenAI request/answer, `stream: true` for SSE |
| `POST /v1/ai/v1/messages` (alias `/v1/ai/messages`) | `ai.chat` | Anthropic request/answer |
| `POST /v1/plugins/ai/rpc` | `ai.read` / `ai.chat` | the same gateway as a service call |

It inherits every rule on this page — the bind mode, the body cap, the rate
limiter, the credential modes and the connection limits — and adds three of its
own: the provider endpoint must be `https` or loopback `http`, redirects are never
followed, and the operator's API keys are sealed and never projected. `connect`
carries an `ai` block, `/v1/status` carries a summary without endpoints, and
`/v1/health` is untouched.

A gateway call spends money at a third party, so it has its own budget: 20
requests per minute per client. Everything that can fail without a network is
decided before a socket is opened; see
[docs/AI.md](AI.md) for the operator guide and
[specs/S7](specs/S7-ai-gateway.md) for the contract.

