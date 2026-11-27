# Grom server network (mesh)

A **server network** is several phones, each running its own Grom, that can find
each other, see what the others serve, and call a service on another Grom — while
every phone stays a complete, independent server. There is no cloud account, no
central relay and no second server: the mesh is a set of *clients* of the API
Grom already has.

This document is the operator guide. The protocol is specified in
[`specs/S8-server-mesh.md`](specs/S8-server-mesh.md); the decisions are D99–D103
in [`000-decisions.md`](000-decisions.md).

---

## 1. What it actually does

Three pieces, in this order:

1. **Discovery** hears that another Grom is nearby and gives the operator an
   endpoint to approve.
2. **Pairing** — the existing, mutual Ed25519 pairing from `Devices` — is the
   only thing that creates trust. A discovered server is a hint, not a peer.
3. **Calls** go from one Grom to another over the other server's own
   `/grom/v1/connect`, `/v1/plugins/{id}/rpc` and `/v1/ai/*` routes, signed with
   the caller's key and authorized by the scopes and service shares the other
   operator chose.

```
        LAN / Wi-Fi Direct / Bluetooth Classic (a paired endpoint)
  Phone A ─────────────── signed HTTP/JSON ───────────────▶ Phone B
   mesh roster                                                services, AI
   "B: agent, ai"                                             scopes, shares
```

Nothing is uploaded, and no phone gains a route into another phone's control
plane (`127.0.0.1:47001`); the mesh only speaks the public data plane.

## 2. The four carriers

| Carrier | Role | Data plane? | Notes |
|---|---|---|---|
| **LAN / VPN** | discovery and data | yes, `http(s)` | A signed UDP beacon every 30 s while advertising is on. Preferred: ordinary TCP. |
| **Wi-Fi Direct** | discovery and data | yes, `http(s)` | DNS-SD service record (`_grom._tcp`) carries the node prefix and the data-plane port; the group owner's IP is used once the group forms. |
| **Bluetooth Classic** | data | yes, `bt://<MAC>` | Secure RFCOMM to a **bonded** device; HTTP/1.1 is carried over the byte stream. No discovery: Android already knows bonded devices, and Grom will not scan Bluetooth in the background. |
| **Bluetooth LE** | discovery only | **no** | An 8-byte advertisement: version, data-plane flag, node-id prefix, port — carried in the service **data** field only, because the legacy PDU is 31 bytes and the 128-bit UUID list cannot fit beside it (29 used). Deliberately too small for a request. |

The policy is explicit ([`MeshTransport`](../engine/src/main/java/app/grom/server/mesh/MeshModels.kt)):
BLE is never used to move data. A BLE-only sighting has no address and the screen
says so — put both phones on the same Wi-Fi to pair, or type the address.

## 3. Turning it on

Discovery is **off** by default and never starts with the app.

1. **Public access** → enable, bind **LAN** (or a VPN interface), auth **Key** or
   **Both**. A beacon that advertised a loopback address would send other phones
   nowhere, so nothing is advertised while the plane is loopback-only.
2. **Server network** → **Turn discovery on**. The screen requests the Bluetooth
   and Wi-Fi Direct runtime permissions when they are missing; a denied grant is
   not a failure, the radio row then says exactly what it still needs.
3. On the other phone, do the same. Each phone's section shows which radios are
   ready, and exactly which permission is missing if one is not.

When two phones are on the same network, the nearby list shows a fingerprint and
an address. Tap it → **Pair**: the dialog asks what the other server may do
(`services.read` to see this phone's services, `services.call` to use them; both
on by default). Enter the code the other phone shows on `Devices` → compare the
fingerprint on both screens → the other operator sees the same scope list and
approves it. That is the whole trust ceremony; it is the same one the operator
already knows, plus the two service scopes the network needs (D104). Leaving
both unticked is exactly the old `Devices` pairing.

A pairing never carries `mesh.read` or `mesh.call`: those are grants for an
application on *this* phone, not for another device, and the engine refuses them
at the pairing boundary (S8-R015).

### 3.1 The Bluetooth switch (D109)

The section has **one** Bluetooth switch, and it is Grom's, not the phone's.

| | What it does | Who owns it |
|---|---|---|
| **Bluetooth** (in the section) | decides whether Grom starts the LE advertiser/scanner and the Classic listener | Grom; saved as `MeshSettings.bluetooth`, on by default |
| **System Bluetooth** (quick settings) | the radio itself | the operator; Android 13+ will not let an app call `enable()`/`disable()`, and Grom never asks |

- Turning the Grom switch **off** leaves discovery on: the LAN beacon and Wi-Fi
  Direct keep working, and the two Bluetooth rows read *"off — Bluetooth is not
  used for the server network"*. Sightings already heard over Bluetooth are
  dropped, so the nearby list cannot offer a pairing that can no longer be
  dialled.
- Turning it **on** asks for the Bluetooth runtime permissions if they are
  missing (the same dialog discovery uses). It does **not** switch the radio on:
  if the system Bluetooth is off, the section says so and shows **Open Bluetooth
  settings**, which opens Android's own page. There is no request to flip it.
- The system radio state is **measured** every time the section opens or is
  refreshed (`bluetoothOn`), never remembered, and it is `null` on a phone with
  no adapter — "unknown" rather than a guessed `false`.

## 4. Using a service on another server

The screen lists each paired server with the services it publishes (read from its
connect document) and its measured state. From the Binder/API surface a caller
with the right scope can relay one operation:

```http
POST /v1/mesh/call
```
```json
{"server":"0123456789abcdef","service":"agent","op":"ping","args":{}}
```

or, for the AI gateway:

```json
{"server":"0123456789abcdef","service":"ai","op":"chat","body":{"model":"…","messages":[…]}}
```

The target must be a paired server, the service must be one it publishes, and the
operation must be one it exposes to a scoped caller. All three are checked before
a socket is opened; the peer checks them again. The answer carries the peer's own
status and body:

```json
{"ok":true,"server":"…","service":"agent","op":"ping","status":200,
 "result":{"ok":true,"plugin":"agent","op":"ping",…}}
```

A local application can use the same route with a scoped credential
(`mesh.read` to see the roster, `mesh.call` to relay). Neither scope is an
operator key: `mesh.read` cannot call, and `mesh.call` still runs under the
local device's identity and the target's own grants.

## 5. Sharing services, per server

`Server network → tap a server` opens its share list. The checkboxes are the
services **this** phone serves; ticking one means that server's key may use that
service here. Saving all of them is the same as the default: *everything its
scopes already allow*.

The check is applied by the router before the plugin is asked, and the service is
also hidden from that peer's `/v1/plugins` listing. Revoking a pairing takes
effect on the next request — the key lookup is live, there is no restart.

## 6. Scopes used by the mesh

| Scope | Held by | Grants |
|---|---|---|
| `services.read` | the peer | see services and what they expose |
| `services.call` | the peer | call the operations a service publishes |
| `ai.read` / `ai.chat` | the peer | read the AI catalogue / send chat requests |
| `mesh.read` | a local client | read the roster (no address in it) |
| `mesh.call` | a local client | ask this server to relay one call |

`mesh.call` is a relay grant, deliberately separate from `services.call`: letting
a client use a local service and letting it borrow this phone's federation are
different decisions.

## 7. What the mesh will not do

- **No automatic trust.** A beacon, a BLE advert and a Wi-Fi Direct name are all
  chosen by the other side; none of them can add a peer. Only pairing can.
- **No public exposure.** The mesh opens no listening port of its own. The UDP
  beacon is a datagram to a fixed link-local multicast group, the Wi-Fi Direct
  service is a local group service, and the Classic listener is Android's RFCOMM
  socket, usable only by a bonded device.
- **No background polling.** The roster refreshes when the section opens, when
  the operator taps Refresh, or after an action. The only periodic thing is the
  beacon, and only while advertising is on.
- **No keyboard-less data entry.** A BLE-only sighting cannot be paired from the
  screen because it has no endpoint; the app says so instead of guessing a port.

## 8. Failure codes

| Code | Where | Meaning |
|---|---|---|
| `MESH_DISABLED` | local | the server network is off |
| `MESH_PERMISSION_REQUIRED` | radio | an Android permission is missing; the row names it |
| `MESH_NO_ADDRESS` | caller | the record has no reachable endpoint (inbound-only, or BLE-only) |
| `MESH_UNREACHABLE` | caller | the peer did not answer |
| `MESH_UNAUTHORIZED` | caller | the peer does not accept this key (removed/revoked) |
| `MESH_SCOPE_NOT_GRANTED` | both | the peer did not grant the needed scope |
| `MESH_UNKNOWN_SERVER` | caller | no paired server has that node id |
| `MESH_UNKNOWN_SERVICE` | caller | the peer does not publish that service |
| `MESH_OP_NOT_ALLOWED` | both | the peer does not expose that operation |
| `MESH_REDIRECTED` | caller | the peer tried to redirect; not followed |
| `MESH_PAYLOAD_TOO_LARGE` | caller | the relayed body exceeds 64 KiB |
| `MESH_BAD_RESPONSE` | caller | the peer answered non-JSON or an unusable document |
| `SERVICE_NOT_SHARED` | peer | the operator's share list does not open this service |

### 8.1a Radio codes

These are the per-radio row codes. They are not mesh failures: a radio row is a
measurement of *this phone*, and the screen turns each one into a sentence (an
unknown code is printed verbatim rather than hidden).

| Code | Meaning | What the operator can do |
|---|---|---|
| `BLUETOOTH_DISABLED` | the system Bluetooth radio is off | turn Bluetooth on in the system, or leave Grom's switch off |
| `BLUETOOTH_UNAVAILABLE` | the phone has no Bluetooth adapter | nothing — the other carriers still work |
| `BLE_ADVERTISE_FAILED:<n>` | the stack refused the advertisement (`<n>` is Android's `AdvertiseCallback` code, `1` = too large, `3` = already advertising) | stop the other advertiser, or rely on LAN |
| `BLE_ADVERTISE_UNAVAILABLE` | this phone's LE cannot advertise | nothing |
| `BLE_SCAN_FAILED:<n>` | the stack refused the scan | scan again; if it persists the row keeps the code |
| `BLE_NO_IDENTITY` | the device identity is not ready yet | open **Devices** once, then scan |
| `BLUETOOTH_LISTEN_FAILED` | the RFCOMM listener could not be created | pair the device in the system first |
| `BLUETOOTH_ACCEPT_FAILED` | the accepted link died | turn discovery off and on |
| `WIFI_DIRECT_DISABLED` | Wi-Fi is off, so Wi-Fi Direct cannot run | turn Wi-Fi on |
| `WIFI_DIRECT_UNAVAILABLE` | no Wi-Fi Direct hardware | nothing |
| `WIFI_DIRECT_DISCOVERY_FAILED` | the platform refused peer discovery | scan again |

An identity that changed under a pairing is not a mesh code: the connect document
is unsigned, so the mesh cannot prove it. The paired-device probe reports it as
`PEER_IDENTITY_CHANGED` in `Devices` (S1-R043), and the roster then measures the
server as `unreachable`. A call to it fails closed — the reset device forgot this
device's key, so it answers `MESH_UNAUTHORIZED` — and the operator re-pairs.

## 8.1 Which carrier to trust for what

There is no single "best" carrier; there is a right one per job.

| Job | Carrier | Why |
|---|---|---|
| phones on the same Wi-Fi or a VPN | LAN `http(s)` | ordinary TCP, the fastest and the most tested path; a VPN makes it work across CGNAT without anything else |
| no shared network, two phones close | Wi-Fi Direct | real TCP at Wi-Fi speed once the group forms; discovery is per-group |
| pairing / small JSON calls, no network | Bluetooth Classic | the only carrier that works with no infrastructure, at tens of KB/s |
| "is somebody near?" only | BLE | eight bytes; never a request |

For a long-running link, **LAN over a VPN** is the most reliable: TCP retries,
no group ownership and no RFCOMM bonding. Wi-Fi Direct works but the group is
torn down when the screen sleeps on many ROMs. Bluetooth Classic survives screen
sleep better but is slow and needs a bonded device. None of them survives a
force-stop; that is Android, not the carrier.

The connection is maintained by the **server's foreground service**, already
covered by the Doctor (D80): battery exemption, notifications on, no background
restriction. The mesh adds no second mechanism and no keep-alive of its own; it
uses the connection when a call is made and reports `unreachable` when it is gone.

## 9. Android limits, honestly

- **Battery and Doze.** Discovery stops when the server stops. A phone that
  Android has frozen cannot answer, and the lamp goes `unreachable` — that is a
  measurement, not a bug.
- **Bluetooth Classic** needs a bonded device and is slow (tens of KB/s). It is a
  fallback for pairing and small JSON calls, not for streaming.
- **The Bluetooth radio is the operator's switch.** From Android 13 an app may
  not enable or disable it, so Grom only reports the state and opens the system
  page; the Grom switch decides whether Grom uses the radio at all (D109).
- **The BLE advertisement is 29 of the 31 legacy bytes.** Adding the service
  UUID list beside the service data would be 47 and the stack would refuse it, so
  the data field carries the name and a scan filter matches it. A v0.19.0 phone
  still sees a v0.19.1 one through the service-data filter; the reverse needs the
  older phone to update.
- **Wi-Fi Direct** gives a group owner IP but no service payload beyond DNS-SD;
  multiple Grom servers in one group need a real discovery protocol, which this
  increment does not add. Prefer the LAN when both phones share a network.
- **IPv6 link-local** beacon senders are ignored: the stored endpoint format is
  IPv4 `host:port` (or `bt://MAC`), and inventing a second syntax would make the
  stored address ambiguous.
- **Pairing still needs the code from the other screen.** The mesh removes the
  typing of the address, not the trust decision.

## 10. Testing

The engine is covered by JVM tests, including real sockets:

- `MeshBeaconTest`, `BleAdvertisementTest`, `DiscoveryBookTest`,
  `MeshSettingsTest` — the discovery formats and their bounds;
- `MeshCatalogTest`, `MeshCallerTest`, `MeshDocumentsTest` — the connect document,
  local refusals, the address-free roster;
- `HttpWireTest` (which also drives `RoutingJsonTransport`), `BluetoothJsonTransportTest`,
  `LanBeaconNodeTest` — the stream carrier over a real loopback socket, the
  routing of `http(s)`/`bt` URLs, and the beacon socket;
- `MeshRouterTest` — mesh scopes, per-peer shares and the connect shape;
- `PeerSharesTest`, `PeerGrantTest` — the per-peer allow-list and a grant that
  keeps an already-paired endpoint;
- `PairingTest` — a pairing hello that asks for a local-client scope is refused;
- `MeshIntegrationTest` — **two and three real `SuperServer` instances** over real
  loopback sockets, with signatures, scopes, a revoked key and a changed share
  list.

The Android radios (`mesh/android/`) are thin adapters and are **not** JVM-tested;
policy #66 and #70 pin their surface (only that directory imports `android.*`,
only secure RFCOMM, no insecure variant). No physical phone has run this yet.
