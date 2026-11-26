# S8 — Server network (mesh)

Status: implemented in v0.18.0 (D99–D103). This document is the contract between
two Grom servers, and between a Grom server and a phone that runs it.

A mesh is **not** a protocol of its own. Every byte between two servers is the
data plane defined in S5: HTTP/1.1, signed with `grom-ed25519-v1`, authorized by
the scopes of S5-R017 and the service rules of S5-R056…S5-R058. The mesh adds
discovery, a roster and a relay, and nothing else.

---

## 1. Roles

| Role | What it is |
|---|---|
| **server** | a phone running the Grom server role with its public data plane enabled |
| **peer** | a server this device has paired with (S1-R041, S5-R052) |
| **candidate** | a sighting of a server that has **not** been paired — a hint |
| **client** | an application calling this device; it may hold `mesh.read`/`mesh.call` |

A candidate never becomes a peer without the pairing handshake. There is no
auto-accept path.

## 2. Discovery

Discovery is opt-in (`MeshSettings.enabled`, default `false`) and only runs while
the server is running. Three carriers publish sightings; one carries data only.

### 2.1 LAN beacon (S8-R001)

A UDP datagram to the link-local group `239.255.71.10:47110` (overridable for
tests), at most every 30 s while advertising is on. One line, at most 512 bytes:

```
grom-mesh-beacon-v1 <nodeId> <port> <publicKeyB64Url> <timestampSeconds> <nonceB64Url> <signatureB64Url>
```

- `nodeId` is 16 lowercase hex characters, derived from the key (S1-R040).
- `port` is the public data-plane port, never the loopback control port.
- The signature is Ed25519 over:

```
grom-mesh-beacon-v1
<nodeId>
<port>
<timestampSeconds>
<nonce>
```

A receiver MUST:

1. reject a line whose fields do not parse, whose key hashes to a different
   `nodeId`, or whose signature does not verify;
2. reject a timestamp more than 120 s from its own clock;
3. treat the sender's IPv4 address plus the announced port as a **candidate
   endpoint**, never as a peer;
4. ignore its own `nodeId`.

The beacon carries no capability list, no status and no request.

### 2.2 BLE advertisement (S8-R002)

A legacy advertisement with service UUID
`67726f6d-6d65-7368-7631-000000000001` and eight bytes of service data:

```
[0]     version = 1
[1]     flags: bit 0 = the data plane is up
[2..5]  the first 4 bytes of the node id
[6..7]  the data-plane port, big endian
```

A receiver MUST treat it as an unauthenticated hint with **no endpoint**: BLE is
not a data carrier, and the prefix may collide. `signedBeacon` is `false`.

### 2.3 Wi-Fi Direct (S8-R003)

The radio publishes a DNS-SD service `_grom._tcp` with TXT fields `node` (the
first eight hex digits of the node id) and `port`. A client side of a group MAY
combine the group owner's IP with the port from that record once the group
forms; that endpoint is still a candidate until paired.

The group owner's device name is **not** used for this, because it is neither
signed nor guaranteed to survive the platform.

### 2.4 Bluetooth Classic (S8-R004)

Android exposes no Grom-specific discovery for bonded devices, so this carrier is
**data only**. A peer record may store `bt://<MAC>`; the MAC is canonical upper
case. See §4.3.

## 3. Trust handshake

Unchanged from S1-R041 and S5-R052: the operator opens a pairing session, the two
screens show a one-time code and a fingerprint, and the `grom-pair-hello-v1` /
`grom-pair-accept-v1` signatures bind the code to both node ids. Scopes are
chosen by the operator (S1-R042).

A pairing asks for a subset of the **peer-grantable** scopes — `status.read`,
`config.read`, `services.read`, `services.call`, `ai.read`, `ai.chat` (D104).
`mesh.read` and `mesh.call` are local-client grants and MUST be refused at the
pairing boundary, both by the joining request and by the accepting endpoint
(S8-R015). The Devices flow continues to ask for `status.read` and `config.read`
only; the server network's pairing dialog adds `services.read` and
`services.call`, and the accepting operator sees the same list before approving.
The scopes are symmetric: both records carry the same set.

The mesh adds one field to a peer record:

```json
{"services": ["agent", "ai"]}
```

`services` is the operator's per-peer allow-list (D101). Absent or empty means
"every operation this peer's scopes already allow" — the pre-mesh behaviour — so
an existing pairing is never silently narrowed. A non-empty list is a hard
filter: the service does not appear in that peer's `/v1/plugins` listing and any
call to it returns `403 SERVICE_NOT_SHARED`.

## 4. Data carriers

All data calls use the S5 request format. The only difference is the byte carrier
and the endpoint scheme.

### 4.1 `http(s)://`

LAN, Wi-Fi Direct and VPN. The transport is the same client the peer probe
already used. Redirects are not followed.

### 4.2 Endpoint validation

An endpoint MUST be a canonical `scheme://host:port` with `http`, `https` or `bt`
and nothing else: no userinfo, path, query or fragment. A non-canonical address
written by a candidate is dropped, never normalised a second time.

### 4.3 `bt://<MAC>` (D102)

A **secure** RFCOMM socket (`createRfcommSocketToServiceRecord`), usable only
with a bonded device. The same HTTP/1.1 request is written to the stream and the
response is read from it. Constraints:

- no streaming (SSE) over this carrier — a response without `Content-Length` is
  read to end-of-stream, so an event stream never terminates;
- no insecure RFCOMM variant, no automatic reconnection;
- an unbonded or unreachable MAC is a stable `TransportException`, reported as
  `MESH_UNREACHABLE`.

A receiving Grom MAY accept RFCOMM links and forward them to its loopback data
plane; the bridge adds no authentication of its own.

## 5. Roster

### 5.1 `GET /v1/mesh` — requires `mesh.read`

```json
{
  "ok": true,
  "enabled": true,
  "advertise": true,
  "scan": true,
  "generatedAtMs": 1700000000000,
  "local": {"nodeId": "…", "label": "…", "services": ["agent"]},
  "servers": [
    {
      "nodeId": "0123456789abcdef",
      "label": "Phone B",
      "fingerprint": "0123-4567-89ab-cdef",
      "state": "alive",
      "lastProbeMs": 1700000000000,
      "inboundOnly": false,
      "transports": ["lan"],
      "grantedByMe": ["services.read", "services.call"],
      "grantedByPeer": ["services.read"],
      "sharedServices": ["agent"],
      "servicesListed": true,
      "catalogError": null,
      "services": [{"id": "agent", "title": "Agent", "capabilities": ["ping"], "scopedOps": ["ping"]}],
      "ai": {"enabled": false, "models": []}
    }
  ]
}
```

Rules:

- **No endpoint of any kind ever appears in this document.** Not a `host:port`,
  not a MAC, not a tunnel. Only the operator's signature-gated Binder view shows
  an address, because the operator is the one approving it (S8-R010).
- `state` is measured (`unknown|alive|stale|unreachable`, S1-R043); a stored
  pairing is never evidence of reachability.
- `servicesListed: false` means the peer answered but did not enumerate — the
  catalogue is unknown, not empty.
- `catalogError` is one of §7's codes and never contains an address.
- Unpaired candidates are **not** listed here.

### 5.2 `POST /v1/mesh/call` — requires `mesh.call`

```json
{"server":"0123456789abcdef","service":"agent","op":"ping","args":{}}
```

or, for the AI gateway:

```json
{"server":"0123456789abcdef","service":"ai","op":"chat","body":{…}}
```

AI operations are `models` (GET, needs `ai.read` at the peer), `chat` and
`messages` (POST, need `ai.chat` at the peer).

Before opening a socket the caller MUST check: the server is paired, the service
id is valid, the operation is one the peer published, and this device holds the
scope the peer requires. Each failure maps to §7. The peer enforces all of them
again.

Answer:

```json
{"ok": true, "server": "…", "service": "agent", "op": "ping",
 "status": 200, "result": {…}}
```

`ok` is true only for a 2xx from the peer; `result` is the peer's parsed JSON
body (or the raw text when it is not JSON).

## 6. Refresh

`GET /v1/mesh` is a snapshot. The catalogue is refreshed only when the operator
opens the section, taps Refresh, or after an action; there is no polling and no
scheduler in the mesh. A refresh reads each paired peer's
`/grom/v1/connect` once, at most 16 peers per pass, and stores the result in
memory only.

## 7. Stable codes

| Code | HTTP | Meaning |
|---|---|---|
| `MESH_DISABLED` | 409/503 | the server network is off |
| `MESH_PERMISSION_REQUIRED` | — | a radio permission is missing (UI) |
| `MESH_NO_ADDRESS` | 409 | the record has no endpoint (inbound-only) |
| `MESH_UNREACHABLE` | 502 | the peer did not answer |
| `MESH_UNAUTHORIZED` | 401 | the peer rejects this key |
| `MESH_SCOPE_NOT_GRANTED` | 403 | the peer did not grant the scope |
| `MESH_UNKNOWN_SERVER` | 404 | no paired record for that node id |
| `MESH_UNKNOWN_SERVICE` | 404 | the peer does not publish the service |
| `MESH_OP_NOT_ALLOWED` | 403 | the peer does not expose the operation |
| `MESH_REDIRECTED` | 502 | the peer answered 3xx; not followed |
| `MESH_PAYLOAD_TOO_LARGE` | 413 | body over 64 KiB |
| `MESH_BAD_RESPONSE` | — | the peer answered non-JSON or a bad document |
| `SERVICE_NOT_SHARED` | 403 | the serving operator's share list closes it |

There is no mesh code for a changed peer identity: the connect document is
unsigned, so the mesh cannot prove one. The paired-device probe owns that
verdict and reports `PEER_IDENTITY_CHANGED` (S1-R043); the roster then measures
the server `unreachable`, and a call to it fails closed because the reset device
no longer holds this device's key.

## 8. Compatibility

- The mesh adds routes and scopes; it does not change any existing route. A
  client that never calls `/v1/mesh` sees exactly the pre-mesh server.
- The catalogue is read from each peer's `/grom/v1/connect` (S6), not from
  `/v1/mesh`: a peer that is itself running the mesh but has it switched off
  still publishes its services, so the roster and the calls keep working. A peer
  older than API 10 answers `404` there, which the roster reports as
  `MESH_BAD_RESPONSE`; pairing and `services.*` calls are unaffected.
- `api` in the connect document is the data-plane API version (S5), not the
  Binder API. The mesh methods enter the Binder contract at API 11 (S4 §16).
- New carriers, if any, MUST be expressed as another endpoint scheme and
  discovered as candidates; they MUST NOT introduce a second request format.

## 9. Requirements

| Id | Requirement |
|---|---|
| S8-R001 | a beacon is signed, timestamped and bounded; a receiver rejects anything else |
| S8-R002 | BLE carries discovery only and never a request |
| S8-R003 | Wi-Fi Direct yields a candidate endpoint, never a peer |
| S8-R004 | Bluetooth Classic is secure RFCOMM to a bonded device; no insecure variant |
| S8-R005 | discovery is off by default and stops with the server |
| S8-R006 | a candidate is never auto-trusted; pairing is the only trust path |
| S8-R007 | `GET /v1/mesh` requires `mesh.read` and carries no endpoint |
| S8-R008 | `POST /v1/mesh/call` requires `mesh.call`, a paired target and a published op |
| S8-R009 | the relay checks scope and operation before dialling; the peer checks again |
| S8-R010 | the mesh never publishes an address in `/v1/status`, an export or the roster |
| S8-R011 | the catalogue is fetched on demand and never polled; memory only |
| S8-R012 | a peer's service allow-list is enforced as a hard filter |
| S8-R013 | every failure is a stable code and never contains an address or a key |
| S8-R014 | a disabled or absent mesh answers truthfully and breaks nothing |
| S8-R015 | a pairing carries peer-grantable scopes only; a local-client scope is refused on both sides, and a grant to an already paired device keeps its endpoint |
