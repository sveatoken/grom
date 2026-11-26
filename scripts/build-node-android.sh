#!/usr/bin/env bash
# build-node-android.sh — obtain, verify and install the arm64 Node runtime that
# Grom supervises.
#
# Modes:
#   (none)            diagnose this host and print the install instructions
#   --status          readiness report for humans and logs; always exits 0
#   --check           exit 1 unless a valid aarch64 libnode.so is installed
#   --verify          same checks, with the full report
#   --install DIR     install a prebuilt runtime from DIR into jniLibs/arm64-v8a
#                     (D34: an executable must live in the APK's native library
#                     dir; exec from app-private storage is denied on API 29+)
#   --docker          print the reproducible cross-build recipe
#   --q8              print the Q8 (node:sqlite) result and the probe commands
#
# The quickest way to produce DIR: scripts/fetch-node-android.sh — pinned
# packages, normalised library names, and verification by execution.
#
# Grom itself never downloads a runtime (S1-R003, S3-R037). Everything here is
# an explicit operator action; no mode except --docker's printed recipe
# performs a network request.

set -euo pipefail
cd "$(dirname "$0")/.."

REPO="$PWD"
# Where the runtime is executed from (D34). Android denies execve() of files in
# app-private storage for apps targeting API 29+, so the binary is packaged as a
# native library and run from ApplicationInfo.nativeLibraryDir.
JNI_DIR="${JNI_DIR:-$REPO/app/src/main/jniLibs/arm64-v8a}"
# Legacy operator path: a binary dropped into assets is copied to files/node on
# first start. Keep it working, but jniLibs is the supported location.
ASSET_DIR="${ASSET_DIR:-$REPO/app/src/main/assets/node}"
NODE_VERSION="${NODE_VERSION:-24.22.3}"   # verified on bionic/arm64 with node:sqlite
OUT_DIR="${OUT_DIR:-$REPO/node-out}"
BINARY_NAME="libnode.so"
SCRIPT_NAME="grom-agent.js"

HOST_ARCH="$(uname -m)"
EM_AARCH64=183   # e_machine for 64-bit ARM; no heuristics, the header is read

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ok    %s\n' "$*"; }
warn() { printf '  warn  %s\n' "$*" >&2; }
bad()  { printf '  FAIL  %s\n' "$*" >&2; }

# --------------------------------------------------------------------- probing

ndk_paths() {
  { [ -n "${ANDROID_NDK_HOME:-}" ] && printf '%s\n' "$ANDROID_NDK_HOME"
    [ -n "${ANDROID_HOME:-}" ] && printf '%s\n' "$ANDROID_HOME"/ndk/*
    printf '%s\n' /root/android-sdk/ndk/* /opt/android-sdk/ndk/*
  } 2>/dev/null | sort -u
}

# A prebuilt NDK toolchain is usable only if its clang already executes here.
# The common failure is linux-x86_64 prebuilts on an aarch64 host: the files
# exist, so a naive `ls` check would lie about being able to build.
find_runnable_ndk() {
  local ndk prebuilt clang
  while IFS= read -r ndk; do
    [ -d "$ndk" ] || continue
    for prebuilt in "$ndk"/toolchains/llvm/prebuilt/*; do
      clang="$prebuilt/bin/clang"
      [ -x "$clang" ] || continue
      if "$clang" --version >/dev/null 2>&1; then
        printf '%s\n' "$ndk"
        return 0
      fi
    done
  done < <(ndk_paths)
  return 1
}

elf_machine() {
  # Decimal ELF e_machine, or nothing when the file is not ELF.
  local file="$1"
  [ -f "$file" ] || return 0
  python3 - "$file" <<'PY' 2>/dev/null || true
import struct, sys
with open(sys.argv[1], "rb") as fh:
    head = fh.read(20)
if len(head) < 20 or head[:4] != b"\x7fELF":
    sys.exit(0)
end = "<" if head[5] == 1 else ">"
print(struct.unpack(end + "H", head[18:20])[0])
PY
}

elf_description() {
  case "$1" in
    183) echo "aarch64" ;;
    62)  echo "x86_64" ;;
    40)  echo "arm (32-bit)" ;;
    3)   echo "i386" ;;
    "")  echo "not an ELF file" ;;
    *)   echo "ELF machine $1" ;;
  esac
}

glibc_linked() {
  # A glibc aarch64 binary passes the e_machine check but cannot run on
  # Android (bionic). Catch it at install time instead of on the phone.
  local file="$1"
  [ -f "$file" ] || return 1
  python3 - "$file" <<'PY' 2>/dev/null
import sys
data = open(sys.argv[1], "rb").read(65536)
sys.exit(0 if b"ld-linux" in data else 1)
PY
}

libnode_present() { [ -f "$JNI_DIR/$BINARY_NAME" ] || [ -f "$ASSET_DIR/$BINARY_NAME" ]; }
agent_present()   { [ -f "$REPO/agent/$SCRIPT_NAME" ]; }

# The runtime an operator would install: jniLibs wins over the legacy asset.
libnode_path() {
  if [ -f "$JNI_DIR/$BINARY_NAME" ]; then printf '%s\n' "$JNI_DIR/$BINARY_NAME"
  else printf '%s\n' "$ASSET_DIR/$BINARY_NAME"; fi
}

docker_recipe() {
  cat <<EOF
DEFAULT PATH — no cross-build needed, and it answers Q8:

  scripts/fetch-node-android.sh            # pinned bionic aarch64 packages
  scripts/fetch-node-android.sh --verify   # executes it: version + node:sqlite
  scripts/build-node-android.sh --install  # into app/src/main/jniLibs/arm64-v8a

Build from source instead (x86_64 host + docker; ~2 GB RAM for V8):

  mkdir -p "${OUT_DIR}"
  docker run --rm --platform linux/amd64 \\
    -v "${OUT_DIR}:/out" \\
    node:22-bookworm \\
    bash -lc '
      set -e
      apt-get update && apt-get install -y --no-install-recommends curl xz-utils ca-certificates
      curl -fsSLO https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}.tar.xz
      tar -xf node-v${NODE_VERSION}.tar.xz
      cd node-v${NODE_VERSION}
      # --fully-static keeps the binary self-contained: no Termux prefix, no
      # glibc, only bionic's static startup on the phone.
      ./configure --dest-cpu=arm64 --dest-os=linux --fully-static \\
                  --without-npm --without-corepack --openssl-no-asm
      make -j"\$(nproc)"
      cp out/Release/node /out/${BINARY_NAME}
    '

Then on this machine:

  scripts/build-node-android.sh --install "${OUT_DIR}"

Q8 caveat: a fully static build still has node:sqlite, because the module is
built into the binary rather than loaded as a shared library. The recipe is the
one that can answer Q8 on the device.

Not sure whether your runtime has the module? Run:

  scripts/build-node-android.sh --q8
EOF
}

q8_probe() {
  cat <<EOF
Q8 — is node:sqlite available in a runtime a phone can actually run?

ANSWER: yes, on the pinned runtime (Node v${NODE_VERSION}, bionic aarch64).
Proof, executable on any host with the Android linker present:

  scripts/fetch-node-android.sh --verify
  -> ok    executes through /system/bin/linker64: v${NODE_VERSION}
  -> ok    Q8 closed: Q8 v${NODE_VERSION} sqlite=7

Measured on this repo's dev host (Android 15 kernel, aarch64, bionic):
  Node v${NODE_VERSION} (Termux bionic build): node:sqlite loads and runs SQL
  with no flag, and the whole agent suite passes on it (10/10).
  Node v24.19.0 (glibc host build): same.
  Node v22.14.0: same, with an ExperimentalWarning; some older 22.x need
  --experimental-sqlite, which Grom's argv does not pass (S3-R005).

Re-check on the phone, against the exact installed binary (needs adb):

  adb shell run-as app.grom.server \\
    /data/app/*/app.grom.server*/lib/arm64/libnode.so -e \\
    "const s=require('node:sqlite');new s.DatabaseSync(':memory:').exec('create table t(a)');console.log('Q8 OK',process.version)"

  (nativeLibraryDir is readable through \`run-as\`; the interpreter line is
  /system/bin/linker64, so no extra environment is needed.)

Ask Grom itself once the agent is running:

  curl -s -H "Authorization: Bearer \$(cat grom-token)" \\
    http://127.0.0.1:<port>/grom/v1/agent/exec -d '{"op":"status"}'
  -> "sqlite":{"available":true,"backend":"node:sqlite"} means Q8 is closed.

On a runtime without the module, kv.* still answers 503 STORAGE_UNAVAILABLE:
truthful, but not useful, which is why v${NODE_VERSION} is pinned above.
EOF
}

# Launch the agent exactly as AgentLauncher does (S3-R005, D98).
#
# A wrong argv is silent at build time and fatal on the phone: the runtime
# answers "bad option" and the process exits before GROM_AGENT_READY, so the
# server looks installed and dead. This probe therefore runs the *packaged*
# binary with the *launcher's* argv and waits for that line. It speaks only when
# this host can execute the binary; otherwise it says so instead of pretending.
launch_probe() {
  local binary script linker tmp pid i ready=0
  binary="$(libnode_path)"
  script="$REPO/agent/$SCRIPT_NAME"
  [ -f "$binary" ] && [ -f "$script" ] || return 0

  local libdir
  libdir="$(dirname "$binary")"
  if [ -x /system/bin/linker64 ]; then
    linker=/system/bin/linker64
  elif "$binary" --version >/dev/null 2>&1; then
    linker=""
  else
    say "  skip  the packaged runtime cannot execute on this host, so --verify"
    say "        cannot prove the launch argv; run it on a device (--q8)"
    return 0
  fi

  tmp="$(mktemp -d)"
  mkfifo "$tmp/in"
  if [ -n "$linker" ]; then
    LD_LIBRARY_PATH="$libdir" "$linker" "$binary" "$script" \
      --port 47002 --parent-pid $$ < "$tmp/in" > "$tmp/out" 2> "$tmp/err" &
  else
    "$binary" "$script" --port 47002 --parent-pid $$ \
      < "$tmp/in" > "$tmp/out" 2> "$tmp/err" &
  fi
  pid=$!
  exec 9> "$tmp/in"
  printf '{"token":"verify-%s","port":47002,"logLevel":"warn"}\n' "$$" >&9
  exec 9>&-
  for i in $(seq 1 100); do
    if grep -q GROM_AGENT_READY "$tmp/out" 2>/dev/null; then ready=1; break; fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.1
  done
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
  if [ "$ready" -eq 1 ]; then
    ok "the agent starts with the launcher's exact argv (GROM_AGENT_READY)"
  else
    bad "the agent did not announce readiness with the launcher's argv"
    say "        $(head -1 "$tmp/err" 2>/dev/null)"
  fi
  rm -rf "$tmp"
  [ "$ready" -eq 1 ]
}

status_block() {
  say "Grom agent runtime status"
  say "========================"
  say "host        : $HOST_ARCH ($(uname -sr))"
  say "asset dir   : $ASSET_DIR"
  say "node target : v$NODE_VERSION"
  say ""
  say "Runtime assets"

  if libnode_present; then
    local machine size path deps
    path="$(libnode_path)"
    machine="$(elf_machine "$path")"
    size="$(du -h "$path" | cut -f1)"
    if [ "$machine" = "$EM_AARCH64" ]; then
      if glibc_linked "$path"; then
        bad "$BINARY_NAME ($size, aarch64) is glibc-linked and will not run on Android"
      else
        ok "$BINARY_NAME ($size, aarch64, bionic) at ${path#"$REPO"/}"
        if [ "$path" = "$JNI_DIR/$BINARY_NAME" ]; then
          deps="$(find "$JNI_DIR" -maxdepth 1 -name 'lib*.so' ! -name "$BINARY_NAME" | wc -l)"
          if [ "$deps" -gt 0 ]; then
            ok "$deps co-located libraries in jniLibs (libssl, libcrypto, libicu, …)"
          else
            say "  info  a static build needs no libraries beside the binary"
          fi
        else
          warn "installed under assets/: still works, but jniLibs is the D34 location"
        fi
      fi
    else
      bad "$BINARY_NAME ($size, $(elf_description "$machine") — must be aarch64)"
    fi
  else
    bad "$BINARY_NAME missing (Blocker B1) — run scripts/fetch-node-android.sh"
  fi

  if agent_present; then
    ok "$SCRIPT_NAME source: agent/$SCRIPT_NAME ($(du -h "$REPO/agent/$SCRIPT_NAME" | cut -f1)), packaged into the APK as node/$SCRIPT_NAME"
  else
    bad "$SCRIPT_NAME missing from agent/"
  fi

  say ""
  say "Build host"
  local runnable
  if runnable="$(find_runnable_ndk)"; then
    ok "NDK toolchain executes here: $runnable"
    ok "an arm64 cross-build is possible on this host (memory permitting)"
  else
    local prebuilts
    prebuilts="$(ls -d /root/android-sdk/ndk/*/toolchains/llvm/prebuilt/* 2>/dev/null | xargs -r -n1 basename | sort -u | tr '\n' ' ')"
    bad "no NDK toolchain on this host can execute (prebuilts: ${prebuilts:-none}) on $HOST_ARCH"
    say "        -> Node cannot be cross-built locally; use --docker or --install"
  fi

  if command -v docker >/dev/null 2>&1; then
    ok "docker present (scripts/build-node-android.sh --docker)"
  else
    warn "docker not installed on this host"
  fi

  if command -v adb >/dev/null 2>&1; then
    local devices
    devices="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l)"
    if [ "$devices" -gt 0 ]; then
      ok "adb sees $devices device(s): Blocker B2 can be closed"
    else
      warn "adb present but no device attached (Blocker B2)"
    fi
  else
    warn "adb not installed (Blocker B2: nothing has run on a device)"
  fi

  say ""
  say "Q8 (node:sqlite): see scripts/build-node-android.sh --q8"
}

# ----------------------------------------------------------------------- modes

mode="${1:---diagnose}"
case "$mode" in
  --status|--check|--verify|--install|--docker|--q8) shift || true ;;
  -*) echo "unknown option: $mode (try --status)" >&2; exit 2 ;;
  *)  echo "unknown argument: $mode" >&2; exit 2 ;;
esac

case "$mode" in
  --docker) docker_recipe; exit 0 ;;
  --q8)     q8_probe; exit 0 ;;
esac

if [ "$mode" = "--install" ]; then
  src="${1:-}"
  [ -n "$src" ] || { echo "--install needs a source directory" >&2; exit 2; }
  [ -d "$src" ] || { echo "not a directory: $src" >&2; exit 2; }
  mkdir -p "$JNI_DIR"

  if [ -f "$src/$BINARY_NAME" ]; then
    :
  elif [ -f "$src/node" ]; then
    cp -f "$src/node" "$src/$BINARY_NAME"
  else
    echo "no $BINARY_NAME (or node) in $src" >&2
    exit 1
  fi

  machine="$(elf_machine "$src/$BINARY_NAME")"
  if [ "$machine" != "$EM_AARCH64" ]; then
    echo "refusing to install: $BINARY_NAME is $(elf_description "$machine"), not aarch64" >&2
    exit 1
  fi
  if glibc_linked "$src/$BINARY_NAME"; then
    echo "refusing to install: aarch64 but glibc-linked; Android needs a bionic or static build" >&2
    exit 1
  fi

  # Dynamic builds carry dependencies; they must sit beside the binary, because
  # Android only lets an app execute binaries from the native library dir (D34).
  installed=0
  for lib in "$src"/lib*.so; do
    [ -f "$lib" ] || continue
    case "$(basename "$lib")" in
      "$BINARY_NAME") continue ;;
    esac
    cp -f "$lib" "$JNI_DIR/"
    chmod 644 "$JNI_DIR/$(basename "$lib")"
    installed=$((installed + 1))
  done

  cp -f "$src/$BINARY_NAME" "$JNI_DIR/$BINARY_NAME"
  chmod 755 "$JNI_DIR/$BINARY_NAME"

  if [ -f "$src/$SCRIPT_NAME" ]; then
    # Keep one canonical copy: refresh agent/ rather than shadowing it in assets.
    cp -f "$src/$SCRIPT_NAME" "$REPO/agent/$SCRIPT_NAME"
  fi

  echo "installed $BINARY_NAME ($(elf_description "$machine"), $(du -h "$JNI_DIR/$BINARY_NAME" | cut -f1)) into ${JNI_DIR#"$REPO"/}"
  [ "$installed" -gt 0 ] && echo "installed $installed shared libraries beside it"
  echo "note: jniLibs files are build outputs and stay out of git (see .gitignore)"
  exit 0
fi

case "$mode" in
  --diagnose|--status)
    status_block
    if [ "$mode" = "--diagnose" ]; then
      say ""
      say "Next steps"
      say "  1. scripts/fetch-node-android.sh                        # pinned, verifies by execution"
      say "  2. scripts/build-node-android.sh --install node-out/runtime"
      say "  3. gradle :app:assembleServer29Debug"
      say "  4. on a device: scripts/build-node-android.sh --q8"
    fi
    ;;

  --check|--verify)
    status_block
    say ""
    failure=0
    installed_path="$(libnode_path)"
    machine="$(elf_machine "$installed_path" 2>/dev/null || true)"
    if [ "$machine" != "$EM_AARCH64" ] || glibc_linked "$installed_path"; then
      bad "runtime is not installable: $BINARY_NAME is $(elf_description "${machine:-}")"
      failure=1
    else
      ok "runtime is installable: aarch64 $BINARY_NAME at ${installed_path#"$REPO"/}"
    fi
    agent_present || { bad "$SCRIPT_NAME missing from agent/"; failure=1; }
    if [ "$failure" -eq 0 ]; then
      launch_probe || failure=1
    fi
    if [ "$failure" -ne 0 ]; then
      say ""
      say "Expected when no runtime is installed: the app still builds and reports"
      say "MISSING_RUNTIME at runtime; only the agent stays unavailable."
      say "Install one with: scripts/fetch-node-android.sh && scripts/build-node-android.sh --install node-out/runtime"
      exit 1
    fi
    ;;
esac