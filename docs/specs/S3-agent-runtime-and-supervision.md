# S3 — Agent runtime and supervision

How Grom finds, starts, feeds, watches, and stops the Node agent.

## 1. Layout

### S3-R001
The runtime is split between an executable and a data file (D34):
```
<ApplicationInfo.nativeLibraryDir>/libnode.so   executable, packaged in jniLibs
<files>/node/grom-agent.js                      data file, read by Node
```
Both files MUST exist and `libnode.so` MUST be executable for the agent to be
considered present. The binary MUST NOT be executed from `<app files>`: exec of
app-private storage is denied to apps targeting API 29+, so an implementation
that copies the binary there cannot start it.

### S3-R002
The script path is located relative to `Context.getFilesDir()`; the executable
path is taken from `ApplicationInfo.nativeLibraryDir`. No path from the
environment or from an intent extra may redirect either.

### S3-R003
If either file is missing, the runtime state is `MISSING_RUNTIME`. The state
payload MUST name which file is missing.

## 2. Launch contract

### S3-R004
The agent is launched as an executable process, not through a shell.

### S3-R005
Command line is exactly:
```
<ApplicationInfo.nativeLibraryDir>/libnode.so
  <files>/node/grom-agent.js
  --port <loopbackPort>
  --parent-pid <serverPid>
```

No engine switch (D98). The agent is CommonJS, which every pinned Node LTS
loads by default. An experimental flag the packaged runtime does not recognise
is reported as `bad option` and the process exits before it prints
`GROM_AGENT_READY`, so the server would look installed and dead. A flag may only
be added after `scripts/build-node-android.sh --verify` proves the packaged
binary accepts it.
No secret, token, path outside the runtime directories, or user data appears in
argv.

### S3-R006
The child environment MUST be constructed from scratch, not inherited wholesale.
It contains only `HOME`, `TMPDIR`, `PATH=/system/bin`, `GROM_LOG_LEVEL`, and
`LANG`, plus `LD_LIBRARY_PATH` pointing at the binary's directory when the
runtime is dynamically linked. It MUST NOT contain the bearer token.

### S3-R007
Sensitive configuration — at minimum the bearer token, the port, and the log
level — is written to the child's **stdin** as a single JSON object followed by
`\n`, after which stdin is closed.

### S3-R008
The stdin payload MUST be written after the process starts and MUST NOT exceed
16 KiB.

### S3-R009
The server MUST NOT read the stdin payload back from the child. If the child
needs to echo a fingerprint it uses stdout, per S3-R013.

### S3-R010
stdout and stderr are captured line by line into the bounded in-memory log ring
(last 200 lines) and never written to storage with secret content.

## 3. Readiness and health

### S3-R011
Starting state is `STARTING`. It becomes `READY` only when the child writes a
line matching `GROM_READY <pid>` on stdout.

### S3-R012
`startTimeoutMs` defaults to 10 000 and is configurable. Exceeding it is a
**crash** (S3-R018), not just a warning.

### S3-R013
The `<pid>` in the ready line MUST equal the spawned child's pid; a mismatch is
rejected and treated as a crash (guards against a wrapper that daemonises).

### S3-R014
Health is a local state query. The server MUST NOT probe a network port to
decide readiness.

### S3-R015
After `READY`, a periodic liveness check runs every 5 s: the child must still be
alive. A dead child is a crash.

### S3-R016
An agent that reports `GROM_FATAL <message>` on stdout transitions directly to
`QUARANTINED` with `reason=fatal`, without consuming retries.

## 4. Crashes, backoff, quarantine

### S3-R017
A **crash** is any of: exit before ready, exit after ready, start timeout, or
ready-line pid mismatch. A deliberate `stop` is never a crash.

### S3-R018
Crash timestamps are kept in a rolling window of 10 minutes. Restarts use
exponential backoff: 1 s, 2 s, 4 s, capped at 8 s.

### S3-R019
The third crash inside the window moves the supervisor to `QUARANTINED`.
No restart is attempted after that.

### S3-R020
`QUARANTINED` is sticky across process restarts and across device reboot.

### S3-R021
Clearing quarantine requires `reset()` (UI action or authenticated
`POST /grom/v1/agent/reset`). The reset clears the crash window and the
quarantine flag and MUST be audited.

### S3-R022
A successful run of 60 s or more removes the oldest stored crash so that a
long-lived agent does not accumulate a permanent count from ancient failures.

### S3-R023
`restart()` performs a deliberate stop/start and MUST NOT add a crash.

### S3-R024
`stop()` kills the whole process group (the child may spawn workers) with
`SIGTERM`, then `SIGKILL` after 2 s.

## 5. Boot

### S3-R025
The boot receiver is registered for `BOOT_COMPLETED` and
`LOCKED_BOOT_COMPLETED`.

### S3-R026
On boot the service starts only if the user enabled "start on boot". Default is
off.

### S3-R027
If the supervisor is quarantined, boot MUST NOT start the agent even when the
flag is on; the UI must show the quarantine.

### S3-R028
The enabled flag is stored in app preferences and is not cleared by a crash.

## 6. Redaction and logging

### S3-R029
The bearer token appears in no log line, no audit entry, and no crash report.
`Redactor` replaces it with `«redacted»`.

### S3-R030
Log lines are bounded to 4 KiB each; longer lines are truncated with a marker.

### S3-R031
The log ring is dropped when the agent is stopped; it is not persisted.

## 7. State model

### S3-R032
States: `STOPPED`, `STARTING`, `READY`, `BACKOFF`, `QUARANTINED`,
`MISSING_RUNTIME`. Transitions are:

| from | event | to |
|---|---|---|
| STOPPED | start | STARTING or MISSING_RUNTIME |
| STARTING | ready line | READY |
| STARTING | crash | BACKOFF or QUARANTINED |
| READY | crash | BACKOFF or QUARANTINED |
| READY | stop | STOPPED |
| BACKOFF | timer / start | STARTING |
| BACKOFF | crash | QUARANTINED after third |
| any | reset | STOPPED |
| any | runtime removed | MISSING_RUNTIME |

### S3-R033
No transition may skip `STARTING` on the way to `READY`.

### S3-R034
The state machine MUST be pure Kotlin with no Android imports so it can be
tested on the JVM.

## 8. Missing runtime behaviour

### S3-R035
`MISSING_RUNTIME` is a normal, truthful state, not an error dialog loop. The
service stays alive, health answers `200`, agent endpoints answer `503`.

### S3-R036
The UI MUST show exactly which file is expected and how to install it.

### S3-R037
Grom MUST NOT attempt to download a runtime. See S1-R003.
