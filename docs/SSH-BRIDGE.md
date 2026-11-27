# The SSH bridge (D58)

Grom can open a **reverse SSH tunnel** to a machine the operator controls, so a
phone behind CGNAT can still serve its public data plane to a VPS. Grom is the
SSH *client*. It asks for exactly one thing and nothing else.

```
VPS (public)                          Phone (behind CGNAT)
────────────                          ────────────────────
127.0.0.1:47002  ──tunneled TCP──►   127.0.0.1:47002  (Grom data plane)
      ▲                                     ▲
      │ tcpip-forward                       │ reverse SSH session
      └──────────── SSH server ◄────────────┘  (initiated by the phone)
```

The tunnel is initiated from the device. No inbound port forward, no CGNAT
workaround and no third-party tunnel service is involved.

---

## 1. What the session may do

| Operation | Allowed |
|---|---|
| `tcpip-forward` (server listens, forwards to the phone) | yes, one port |
| Shell / `exec` / subsystem | **never** |
| Agent forwarding, X11 forwarding | **never** |
| Local or dynamic forwarding (`-L`, `-D`) | **never** |
| `direct-tcpip` channels | **never** |

This is enforced twice:

1. Grom never creates a shell, exec or subsystem channel, and it calls
   `startRemotePortForwarding` exactly once per connection.
2. The client installs `SshForwardingPolicy`, which accepts a `forwarded-tcpip`
   channel only when the destination is **exactly the endpoint this connection
   asked to expose** — the address and port returned by `localDataPlane()`, taken
   from our own request and never from the server. An accept-all filter would let
   a compromised server reach any port on the phone; this filter makes that
   impossible.
3. That endpoint is not assumed to be loopback (D111). The data plane may be
   bound to loopback, to the phone's LAN address (what the mesh needs) or to the
   wildcard. A loopback-only rule reported a LAN-bound tunnel as `CONNECTED` and
   then refused every connection — the wrapper accepted the TCP connection on the
   VPS and the phone dropped it.

`tools/test-policy` check 29 fails if a shell/exec/subsystem call appears, and
`SshForwardingPolicyTest` proves the destination rule.

---

## 2. Host keys: pinned on first use, never "accept anyway"

1. A target with no pin is **refused** at connect. The attempt still learns the
   server key, and the UI shows `SHA256:…`.
2. The operator confirms; the pin is stored and the connection is retried.
3. Every later connection must present the same key. A different key is
   `HOST_KEY_MISMATCH` and the connection does not proceed.
4. A legitimate server key change is handled only by *Forget host key*, an
   explicit operator action, followed by a fresh confirmation.

A caller-supplied fingerprint can never override an existing pin.

Fingerprints use the OpenSSH `SHA256:…` form, the same string as
`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.

---

## 3. Credentials

| Mode | On disk | On the wire |
|---|---|---|
| `KEY` (preferred) | private key text sealed by `AndroidKeystoreSecretBox` | public-key auth |
| `PASSWORD` | password sealed by `AndroidKeystoreSecretBox` | password auth, and the single non-echoing `keyboard-interactive` prompt OpenSSH asks through PAM (D105) |

* *Generate Ed25519 key* creates a pair with the CC0 EdDSA provider, stores the
  private half sealed and shows the `ssh-ed25519 AAAA…` line to copy into
  `~/.ssh/authorized_keys` on the VPS.
* *Paste existing private key* accepts OpenSSH/PEM text, validates that it can
  be parsed, and seals it.
* The plaintext never appears in `ssh-targets.json`, in status, in the config
  export, in an audit record or in logcat.
* Changing the target's host or port **drops the pin**: a different machine must
  not inherit the old trust.
* A password containing a control character is **refused before it is stored**
  (`BAD_PASSWORD`, "a pasted line break?"). A trailing newline from a paste is
  otherwise a secret that can never authenticate, and the server's answer to it
  is exactly its answer to a wrong password (D105).
* Password authentication also answers **one non-echoing interactive prompt**
  (`SshPasswordInteraction`). OpenSSH with `UsePAM yes` — the default on Debian
  and Ubuntu — accepts password logins through `keyboard-interactive`, and
  without a handler a correct password fails as "exhausted authentication
  methods". The handler answers only a single non-echoing prompt; a second
  factor or a multi-question challenge is declined and authentication falls
  through to the next method.

---

## 4. Limits

| Limit | Value |
|---|---|
| Saved targets | none (bounded only by the 512 KiB store, D64) |
| Simultaneously active tunnels | 4 (D107) |
| Connect / auth timeout | 15 s each |
| Remote bind address | `127.0.0.1` on the VPS |
| Local destination | whatever `Public access → Bind` is listening on: loopback (default), the phone's LAN address, or the wildcard address (D111) |
| Forward port | per target, default 47002; **must be unique per host** when several phones use one VPS (41001–41099 by convention, D78) |

Saved servers are not capped: the count limit was removed because adding a
server costs no phone resource, and the store refuses an over-size write with
`SSH_STORE_FULL` instead of silently dropping records. Live tunnels are capped
at four, and the screen says so as soon as a fifth server is saved (the extra
servers stay idle).

The limit is a **phone** cost, not a policy: every live tunnel is a thread, a
socket, an SSH session and a MINA transport. Four lets an operator with several
VPS hosts keep them all reachable without forgetting one; beyond that the
refusal is named (`TOO_MANY_TUNNELS`) instead of silently dropping a session.

### More than one server

The store keeps **many** servers and exactly **one current** one (D107). The
current server is a single id in `ssh-targets.json`, so two records cannot both
claim it; the list shows it first and marked, and `/v1/status` carries
`current: true` on that tunnel (the id is already there — no address is added,
D59). Deleting the current server clears the selection rather than leaving a
dangling id.

The current server is a **label for the operator**, not a background behaviour:
it does not auto-connect, and it does not change what the hub polls. Choosing it
is one tap and is recorded in the audit log as `ssh.active`.

The tunnel refuses to start when the public data plane is not running
(`DATA_PLANE_NOT_RUNNING`): pointing a public port at a closed one would be a
lie. **Test connection** is the call that answers "why does my address and
password not work" without it (§7). It also refuses when the target is
disabled, when no credential is stored, or when the stored key cannot be
parsed.

---

## 5. Setting it up

On the VPS:

```bash
# one-time, if you generate the key in the app
echo 'ssh-ed25519 AAAA… grom@android' >> ~/.ssh/authorized_keys

# the forward binds loopback on the VPS, so a reverse proxy there can reach it
```

In Grom: **External servers → Add server**, fill label/host/port/user, generate
or paste a key, confirm the host key, **Connect**.

On the VPS, verify:

```bash
curl -s http://127.0.0.1:47002/v1/health
```

That is the unauthenticated health endpoint. Everything else on the data plane
still requires the password or the Ed25519 API key configured in **Public
access**; the tunnel does not bypass authentication, it only carries bytes.

### TLS

Grom speaks plain HTTP. If the port is reachable from anything other than
loopback on the VPS, put Caddy/nginx in front of it for TLS, or keep the tunnel
loopback-only and reach it through the SSH session itself.

---

## 6. Failure codes

| Code | Meaning |
|---|---|
| `TARGET_NOT_FOUND` | id is not in the store |
| `TARGET_DISABLED` | operator disabled the target |
| `KEY_MISSING` / `PASSWORD_MISSING` | no usable credential |
| `KEY_INVALID` | stored private key cannot be parsed |
| `DATA_PLANE_NOT_RUNNING` | nothing to forward to |
| `CONNECTING` | an attempt is already running |
| `ALREADY_CONNECTED` | tunnel is up |
| `TOO_MANY_TUNNELS` | four tunnels are already active (D107) |
| `HOST_KEY_UNVERIFIED` | first use; confirm the fingerprint |
| `HOST_KEY_MISMATCH` | the fingerprint was compared and differed; connection refused |
| `SSH_HOST_UNKNOWN` | the name did not resolve (stage `resolve`) |
| `SSH_HOST_UNREACHABLE` | nothing accepted a connection on that address and port (stage `connect`) |
| `SSH_TIMEOUT` | no answer in time — connect, handshake or auth (stage `connect`) |
| `SSH_HANDSHAKE_FAILED` | something answered but the SSH handshake did not complete (stage `handshake`) |
| `SSH_AUTH_FAILED` | the server rejected the username, the password or the key (stage `auth`) |
| `SSH_AUTH_METHOD_NOT_ALLOWED` | the server said which methods it accepts and the stored credential's method is not among them — password login is off, or key login is off (stage `auth`, D110) |
| `SSH_FORWARD_REFUSED` | the server refused the remote port (already in use, or forwarding forbidden) — distinct from a network failure (D78) |
| `BAD_FORWARD_PORT_CONFLICT` | the store refused a second target on the same host with the same forward port |
| `SSH_UNAVAILABLE` | this build has no Ed25519 provider (`preflight` only) |
| `SSH_FAILED` | anything else; the message is never the driver's |

Each failure also carries `stage` (`resolve`, `connect`, `handshake`, `auth`,
`forward`). The code comes from **which stage threw**, not from the exception
class (D105):

* MINA raises `SshException`, which **is** an `IOException`, for a rejected
  password. Mapping by type therefore reported a wrong password as
  `SSH_UNAVAILABLE` — "connection refused / DNS / network" — and sent the
  operator to look at a firewall that was never the problem.
* A refused TCP connection surfaces from MINA when the connect future is
  verified, at the same place as a handshake failure. The cause chain separates
  them: `ConnectException`, `NoRouteToHostException` and `UnknownHostException`
  survive it, and a handshake failure never carries one.
* The host key is the only source of `HOST_KEY_MISMATCH`. Before D105 the code
  also claimed a mismatch for *any* failure after the operator had confirmed a
  first-use fingerprint — so a wrong password on first use was reported as
  "the host key changed", with an offer to forget the pin.

After a tunnel drops unexpectedly the state is `failed` with
`SSH_SESSION_CLOSED`; a deliberate disconnect is `disconnected`, not an error.

---

## 7. Checking a server before you connect (D106)

*Connect* needs the public data plane, because it asks the VPS to forward a port
to it. That means the operator with a public address and a password had no way
to find out whether the address, the port, the host key or the password was the
problem — the only answer was `DATA_PLANE_NOT_RUNNING`, which says nothing about
the VPS.

**Test connection** (`sshVerify`) runs the same session without a forward:

| Step | When it runs | Reported as |
|---|---|---|
| name resolution and TCP connect | always | `SSH_HOST_UNKNOWN`, `SSH_HOST_UNREACHABLE`, `SSH_TIMEOUT` |
| handshake and host key | always | `HOST_KEY_UNVERIFIED`, `HOST_KEY_MISMATCH`, `SSH_HANDSHAKE_FAILED` |
| authentication | always | `SSH_AUTH_FAILED` |
| reverse port forward | **only when the data plane is running** | `SSH_FORWARD_REFUSED` |

The reply carries `forwardChecked`. It is `false` when the forward was not
attempted, and the screen then says *"Address, host key and password accepted.
The tunnel was not tested because the public API is off."* — never "connected".

A check leaves nothing behind: the session is closed, no tunnel is registered,
and the only write is the host-key pin the operator just confirmed (the same
write a first connect makes). It is safe to run against a VPS that is already
connected from the same phone.

The check works with the data plane off, which is the state an operator is in
when a fresh server will not accept them.

---

## 7a. When a correct address and password still fail (D110)

Grom can only report what the server actually answers. The list below is the
order in which a real VPS fails, from the most common down, with the sentence
Grom now shows and what to do on the machine.

| What the VPS does | Grom says | Fix on the VPS |
|---|---|---|
| `PasswordAuthentication no`, or `PermitRootLogin prohibit-password` (the default on Hetzner, Debian and Ubuntu cloud images) | `SSH_AUTH_METHOD_NOT_ALLOWED` — *"does not accept that kind of login. It offers: publickey"* | either switch this server to an SSH key in Grom (**Generate Ed25519 key**, copy the line into `~/.ssh/authorized_keys`), or allow passwords |
| password accepted but the user is wrong (`debian` vs `root`) | `SSH_AUTH_FAILED`, with the offered methods including `password` | fix the username |
| `sshd` not running, or a provider firewall (Hetzner Cloud Firewall, `ufw`) dropping 22 | `SSH_HOST_UNREACHABLE` or `SSH_TIMEOUT` | `systemctl status ssh`, open the port in the provider's firewall **and** in the host firewall |
| a different port than 22 | the same two codes | set the port in the target, and in the firewall |
| the VPS was rebuilt or re-created | `HOST_KEY_MISMATCH` | *Forget host key*, then confirm the new fingerprint |
| `AllowTcpForwarding no`, or the remote port is already taken | `SSH_FORWARD_REFUSED` | `AllowTcpForwarding yes`, or choose another forward port |
| the data plane is off | `DATA_PLANE_NOT_RUNNING` | **Test connection** does not need it (§7) |

### Allowing password login on a VPS

Only if the operator wants it — a key is better and is one tap in Grom:

```bash
# Debian / Ubuntu / Hetzner images
sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
sudo sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
sudo systemctl restart ssh            # "ssh", not "sshd", on Debian and Ubuntu
sudo sshd -t                          # validates the file before you lock yourself out
```

Some providers put a drop-in in `/etc/ssh/sshd_config.d/` that overrides the main
file; `sudo sshd -T | grep -E 'passwordauthentication|permitrootlogin'` prints
what `sshd` will actually use.

### What Grom never does here

It never edits `sshd_config`, never runs a command on the VPS (the session has no
shell, §1), never retries a rejected password automatically, and never turns a
"no" into a "maybe" by falling back to another credential. It also never says
"wrong password" when the server said "no passwords" — that distinction is
measured from the server's own reply (D110), and when no reply was seen it stays
silent rather than guessing.

### Why a VPS port cannot be reached from the internet directly

The forward always binds `127.0.0.1` **on the VPS**, by design (D58): a public
port opened by a phone would be an inbound listener the operator never asked the
VPS to have. `GatewayPorts` therefore does not change anything, and
`curl http://<vps-ip>:47002/` will not work no matter what `sshd_config` says.
The public entry point is a reverse proxy on the VPS — Caddy, nginx, or a
Cloudflare Tunnel pointed at `127.0.0.1:47002` — which also terminates TLS
(D47). That proxy is the only thing that needs to listen publicly.

### The forward when the data plane is bound to the LAN

A tunnel to a phone whose **Public access → Bind** is `LAN` works: the forward
lands on the LAN address the data plane is listening on (D111). What it does
**not** survive is the phone changing network while the tunnel is up — the
address it was told about no longer exists, so the tunnel stays `connected` and
stops delivering. Reconnect after a network change; the row reports the state it
measured, and nothing pretends otherwise.

## 8. Licensing

`sshd-core` (Apache-2.0), `net.i2p.crypto:eddsa` (CC0-1.0) and SLF4J (MIT) are
listed in `third_party/THIRD_PARTY.md` and checked by
`tools/check-third-party`. Because Apache MINA SSHD tests one class against
`javax.management.ReflectionException`, which Android does not ship, the engine
provides a minimal stand-in and narrows lint's `InvalidPackage` suppression to
that exact reference (`engine/lint.xml`). Without it, an exception-unwrapping
path would throw `NoClassDefFoundError` on a device.

## 9. Notifications

If the operator has enabled alerts (`NOTIFICATIONS.md`, D65), the bridge emits a
message on these transitions only:

| audit action | message |
|---|---|
| `ssh.connect` | `Grom: external server connected.` |
| `ssh.closed` (unexpected) | `Grom: external server disconnected.` |
| `ssh.failed` | `Grom: external server connection failed (CODE).` |
| `ssh.hostkey.mismatch` | `Grom: external server key changed; refused.` |

`ssh.disconnect` (a deliberate tap), `ssh.hostkey.learned`,
`ssh.hostkey.unverified`, `ssh.verify` (D106) and `ssh.active` (D107) do **not**
notify: the first is the operator's own action, the next two are part of the
first-use handshake the operator is looking at right now, and the last two are
the result of a tap the operator just made.

The message carries the stable code (`SSH_AUTH_FAILED`,
`SSH_AUTH_METHOD_NOT_ALLOWED`, `SSH_HOST_UNREACHABLE`, `SSH_TIMEOUT`,
`HOST_KEY_MISMATCH`, `SSH_SESSION_CLOSED`) and never the host, the
port, the user name or the fingerprint — the audit `detail` contains the target
id and the host, and it is precisely what must not leave the device.
