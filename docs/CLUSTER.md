# Cluster — this device's identity and the phones it pairs with

Grom has no account and no cloud. A "fleet" here is the set of devices **you**
paired, each holding its own key and its own list. This guide covers what that
means, how to pair two phones, what the lamps prove, and the limits you should
not paper over.

> Status: Phase B (D70–D75, API 6). The engine, the data-plane scopes, the
> pairing handshake and the UI are covered by JVM tests. **Nothing has been run
> on a physical phone from this repository yet** — B2 is still open.

## 1. What exists

| Piece | Where | What it does |
|---|---|---|
| Identity | `engine/.../cluster/NodeIdentity.kt` | one Ed25519 key per device; `nodeId` derived from the public key |
| Peer store | `engine/.../cluster/PeerRecord.kt`, `PeerStore.kt` | paired devices, grants and the last measured probe |
| Pairing | `engine/.../cluster/Pairing.kt` | the code, the signed transcripts, the operator decision |
| Peer client | `engine/.../cluster/PeerClient.kt` | signed `GET /v1/status` to a paired address |
| Service | `engine/.../cluster/FleetService.kt` | join, confirm, refresh, revoke, identity reset |
| Projection | `engine/.../cluster/FleetStatus.kt` | `{nodeId,label}` for `/v1/status` |
| UI | Devices section in `GromActivity` | identity, pairing dialogs, measured lamps |

`peripheral26` has no data plane, so it has no Devices section.

## 2. The identity

- A 32-byte seed is generated on first use and sealed with the Android Keystore
  (`SecretBox`, AES-256-GCM). Only `seedSealed` is on disk.
- `nodeId` is the first 16 hex characters of `SHA-256(publicKey)` — the same
  `Ed25519.keyId` used by the data plane. The UI shows it as
  `1234-5678-9abc-def0`.
- A damaged file is reported as `IDENTITY_CORRUPT` and is **not** replaced.
  **Reset identity** mints a new key and forgets every pairing; type `RESET` to
  confirm. Every other device sees a stranger until you pair again.

There is no escrow and no recovery. That is the trade for not having an account.

## 3. Pairing two phones

Both devices must have the public API running on a **LAN** bind with **Key** or
**Both** authentication. Pairing refuses when the data plane is off, bound to
loopback, or password-only — a peer signs its requests, so a password-only
server could never check those signatures.

### On phone A

1. **Menu → Devices → Pair a device → Show a code on this device.**
2. An 8-character code appears (Crockford alphabet, five minutes, single use).
3. Keep the dialog open and tap **Check now** after phone B enters the code.

### On phone B

1. **Menu → Devices → Pair a device → Enter a code from another device.**
2. Enter phone A's `host:port` and the code.
3. Grom asks A, waits for the operator there, and shows A's **fingerprint**.
4. Only confirm if A's screen shows the same fingerprint.

### On phone A, when B is waiting

Grom shows B's label, fingerprint, source address and the requested scopes.
Approve (scopes are chosen from what B asked) or reject. Nothing is stored on
either side until both operators confirm.

The signed transcripts are domain-separated:

```
hello:  grom-pair-hello-v1\n<A.nodeId>\n<B.nodeId>\n<codeHash>
accept: grom-pair-accept-v1\n<A.nodeId>\n<B.nodeId>\n<codeHash>\n<scopes>
codeHash = sha256("grom-pair-v1\n<code>\n<B.nodeId>")
```

The code hash is bound to B's node id, so a hash seen on the wire is useless to a
third device, and knowing the code without B's private key is not enough.

## 4. Scopes

| Scope | Allows |
|---|---|
| `status.read` | `GET /v1/status` |
| `config.read` | `GET /v1/config` (the sanitised export) |

Everything else — plugins, events, logout, plugin RPC — is operator-only and
answers `403 SCOPE_REQUIRED` to a peer. In Phase B the grant is **symmetric**:
each side grants the other the same set, and both operators see it before
confirming.

Revoking is **Remove** in the peer dialog. The key lookup reads the peer store on
every request, so removal takes effect immediately without restarting the
listener.

### A hub instead of 45 pairings

Mutual pairing is for two devices that know each other. A VPS hub that only
reads status does not need a handshake, so it uses a **one-way grant**
(D77): **Devices → Trust a key**, paste the hub's Ed25519 public key, name it,
choose scopes. The key id is derived from the key, the stored record has **no
address**, and the hub is never dialled; `Refresh` skips it instead of recording
a false failure. The hub stays a `Principal.Peer` with exactly those scopes and
can never become an operator key.

Revoking a grant is removing the row, exactly like a pairing. When a phone sits
behind CGNAT, the hub reaches it through the reverse SSH tunnel the phone
already opens — not through an address the phone publishes. See
[`HUB.md`](HUB.md) for the VPS side.

## 5. What the lamps mean

| Lamp | State | Meaning |
|---|---|---|
| ● green | `alive` | a signed `GET /v1/status` succeeded within the last 90 s |
| ● red | `unreachable` | the last probe failed (`PEER_UNREACHABLE`, `PEER_UNAUTHORIZED`, …) |
| ● grey | `unknown` | never probed since pairing |
| ● grey | `stale` | last success was more than 90 s ago |

Probes run **only** when you open Devices or tap **Refresh** (at most 16 per
run). There is no background polling; a sleeping phone is `unreachable` until
you ask, and Grom will not pretend otherwise.

A pairing alone never turns a lamp green.

## 6. Addresses and status

`/v1/status` and the config export carry this device's `nodeId` and `label`
only. A paired-device address is **never** published — it identifies where
another machine lives. The roster (with addresses) is returned only over the
signature-gated Binder, which the operator's own app already controls.

## 7. Security model, stated plainly

- **Signed, not encrypted.** Grom has no TLS (D47). On the same Wi-Fi another
  device can see that these devices exist and observe the status response. Pair
  on a network you trust, over a VPN/mesh, or behind your own terminator.
- **No global revoke.** If a phone is lost, its key stays trusted on every peer
  until you remove it there. There is no account that can do it for you.
- **No recovery.** Losing the seed (or resetting it) means pairing again
  everywhere.
- **Pairwise does not scale.** Ten phones is 45 pairs. Phase C (a hub on a VPS)
  is the answer for many devices; this phase is for a handful.
- **Clock skew matters.** A signature is valid for ±300 s. A large clock
  difference is reported as `SIGNATURE_STALE`, not as a broken device.
- **A reset device is detected.** If the other side reports a different
  `nodeId`, the probe fails with `PEER_IDENTITY_CHANGED` instead of silently
  trusting a new key.

## 8. Codes you may see

| Code | What it means |
|---|---|
| `PAIR_SESSION_CLOSED` | no operator opened a pairing session |
| `PAIR_BAD_CODE` | the code did not match (5 attempts close the session) |
| `PAIR_BAD_SIGNATURE` | the other side did not prove its key |
| `PAIR_SELF` | that is this device |
| `PAIR_BUSY` | another device is already waiting |
| `PAIR_EXPIRED` | the code or the request timed out |
| `PAIR_TIMEOUT` | the other operator did not decide in time |
| `PAIR_REJECTED` | the other operator declined |
| `PAIR_UNREACHABLE` | the other device did not answer (API off, wrong address) |
| `PAIR_KEY_AUTH_REQUIRED` | the public API accepts passwords only, but peers sign |
| `PEER_UNREACHABLE` | a measured probe failed |
| `PEER_UNAUTHORIZED` | the peer no longer accepts this device's key |
| `PEER_SCOPE_REQUIRED` | the peer key exists but lacks the scope |
| `PEER_NO_ADDRESS` | a one-way grant: nothing to dial, so no probe was attempted |
| `PEER_IDENTITY_CHANGED` | the peer was reset and now has a different key |
| `IDENTITY_CORRUPT` | the identity file cannot be verified; reset explicitly |

## 9. Testing

```
./gradlew :engine:testDebugUnitTest --tests 'app.grom.server.cluster.*'
./gradlew :engine:testDebugUnitTest --tests 'app.grom.server.superserver.ClusterRouterTest'
./gradlew :engine:testDebugUnitTest --tests 'app.grom.server.superserver.SuperAuthPrincipalTest'
./gradlew :app:testServer29DebugUnitTest --tests 'app.grom.server.ui.FleetModelTest'
```

Real Ed25519 keys are used in `PairingTest` and `FleetServiceTest`; the router is
a pure function over an `HttpRequest`, so the scope matrix needs no socket.
`tools/test-policy` #34–#41 pin the socket surface, the sealed seed, the pairing
bounds, the scope gate, the one-way grant and the hub roster. `PeerGrantTest`
covers the inbound-only path end to end at the model level.

## Server network (mesh)

The fleet tools in this document describe **operator-side** views: a VPS that
polls paired phones. Since v0.18.0 a phone can also be a member of a **server
network**: it discovers another Grom (signed LAN beacon, BLE advertisement,
Wi-Fi Direct, bonded Bluetooth Classic), pairs with it through the same
fingerprint ceremony, and calls a service the other server publishes, with a
per-peer service allow-list. Discovery is opt-in, untrusted and bounded; the
roster carries no address; and every call is the existing signed HTTP API, not a
new protocol. See [`MESH.md`](MESH.md) and
[`specs/S8-server-mesh.md`](specs/S8-server-mesh.md) (D99–D103).
