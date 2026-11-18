#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "${BASH_SOURCE[0]%/*}" && pwd -P)"
BUILD_SCRIPT="$SCRIPT_DIR/build.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/demo-image-test.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT

STUBS="$TEST_ROOT/stubs"
CHECKS_ONLY="$TEST_ROOT/checks-only"
PRIVATE_TMP="$TEST_ROOT/private tmp"
mkdir -p "$STUBS" "$CHECKS_ONLY" "$PRIVATE_TMP"
chmod 0700 "$PRIVATE_TMP"
printf 'ssh-ed25519 AAAAB3NzaC1yc2V1ZDI1NTE5AAAAITestData test key ; printf; safe\n' > "$TEST_ROOT/demo key.pub"

write_stub() {
    local name=$1
    shift
    printf '%s\n' '#!/bin/bash' "$@" > "$STUBS/$name"
    chmod 0755 "$STUBS/$name"
}

write_stub uname \
    'if [[ "${DEMO_TEST_MAC:-0}" == 1 && "${DEMO_TEST_VM:-0}" != 1 ]]; then' \
    '  if [[ "$1" == -s ]]; then printf "Darwin\n"; else printf "%s\n" "${DEMO_TEST_MAC_ARCH:-arm64}"; fi' \
    'elif [[ "$1" == -s ]]; then printf "Linux\n"; else printf "%s\n" "${DEMO_TEST_MACHINE:-aarch64}"; fi'
write_stub id \
    '[[ "$1" == -u ]] && { if [[ "${DEMO_TEST_MAC:-0}" == 1 && "${DEMO_TEST_VM:-0}" != 1 ]]; then printf "501\n"; else printf "0\n"; fi; exit 0; }' \
    'exit 1'
write_stub sudo \
    '[[ "${1:-}" != -n ]] || shift' \
    'exec "$@"'
write_stub ssh-keygen \
    '[[ "${DEMO_TEST_BAD_KEY:-0}" != 1 ]] || exit 1' \
    '[[ "$1" == -lf && -s "$2" ]]'
write_stub openssl \
    'if [[ "$1" == rand ]]; then printf "stub-random-password\n"; exit 0; fi' \
    'if [[ "$1" == passwd && "$2" == -6 && "$3" == -stdin ]]; then IFS= read -r _; printf "%s\n" "$DEMO_TEST_HASH"; exit 0; fi' \
    'exit 1'
write_stub podman \
    'printf "%s\0" "$@" >> "$DEMO_TEST_PODMAN_ARGS"' \
    'printf "__CALL_END__\0" >> "$DEMO_TEST_PODMAN_ARGS"' \
    'if [[ "$1" == machine && "$2" == ssh ]]; then' \
    '  shift 2; [[ "${1:-}" != -- ]] || shift' \
    '  if [[ -n "${DEMO_TEST_REMOTE_RESPONSE:-}" && "$*" == *"mktemp -d"* ]]; then printf "%s\n" "$DEMO_TEST_REMOTE_RESPONSE"; exit 31; fi' \
    '  if [[ -n "${DEMO_TEST_FAIL_SSH:-}" && "$*" == *"$DEMO_TEST_FAIL_SSH"* ]]; then exit 31; fi' \
    '  DEMO_TEST_VM=1 /bin/bash -c "$*"' \
    '  exit $?' \
    'fi' \
    'if [[ "$1" == info ]]; then' \
    '  if [[ "$3" == *Rootless* ]]; then printf "false\n"; else printf "%s\n" "$DEMO_TEST_GRAPHROOT"; fi' \
    '  exit 0' \
    'fi' \
    'if [[ "${DEMO_TEST_FAIL:-}" == "$1" ]]; then exit 23; fi' \
    'if [[ "$1" == run ]]; then' \
    '  for ((i=1; i<=$#; i++)); do' \
    '    if [[ "${!i}" == --volume ]]; then' \
    '      next=$((i + 1)); value=${!next}' \
    '      if [[ "$value" == *:/config.toml:ro ]]; then config=${value%:/config.toml:ro}; fi' \
    '    fi' \
    '  done' \
    '  [[ -n "${config:-}" && -f "$config" ]] || exit 24' \
    '  python3 -c '\''import os,stat,sys; assert stat.S_IMODE(os.stat(sys.argv[1]).st_mode) == 0o600'\'' "$config" || exit 25' \
    '  cp "$config" "$DEMO_TEST_CAPTURE_CONFIG"' \
    '  mkdir -p "$DEMO_OUTPUT_DIR/qcow2"' \
    '  printf "test disk\n" > "$DEMO_OUTPUT_DIR/qcow2/disk.qcow2"' \
    'fi' \
    'exit 0'

cp "$STUBS/uname" "$CHECKS_ONLY/uname"
cp "$STUBS/id" "$CHECKS_ONLY/id"
export DEMO_TEST_GRAPHROOT="$TEST_ROOT/storage"
export DEMO_TEST_PODMAN_ARGS="$TEST_ROOT/podman-args"
export DEMO_TEST_CAPTURE_CONFIG="$TEST_ROOT/captured-config"
export DEMO_TEST_HASH='$6$test-salt$unusable-test-hash'
export DEMO_OUTPUT_DIR="$TEST_ROOT/output with spaces"
export TMPDIR="$PRIVATE_TMP"
mkdir -p "$DEMO_TEST_GRAPHROOT"
touch "$DEMO_TEST_PODMAN_ARGS"
FULL_PATH="$STUBS:$PATH"

run_build() {
    PATH="$FULL_PATH" /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/demo key.pub"
}

assert_fails() {
    local expected=$1
    shift
    if "$@" > "$TEST_ROOT/failure.stdout" 2> "$TEST_ROOT/failure.stderr"; then
        printf 'Expected failure: %s\n' "$expected" >&2
        exit 1
    fi
    if ! python3 -c 'import pathlib,sys; assert sys.argv[1] in pathlib.Path(sys.argv[2]).read_text()' \
        "$expected" "$TEST_ROOT/failure.stderr"; then
        printf 'Failure did not report %s\n' "$expected" >&2
        exit 1
    fi
}

assert_fails 'native aarch64 Linux' env PATH="$FULL_PATH" DEMO_TEST_MACHINE=x86_64 /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/demo key.pub"
assert_fails 'Required command not found: podman' env PATH="$CHECKS_ONLY" /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/demo key.pub"
assert_fails 'SSH public-key file is missing or unreadable' env PATH="$FULL_PATH" /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/missing.pub"
touch "$TEST_ROOT/empty.pub"
assert_fails 'SSH public-key file must contain exactly one non-empty key line' env PATH="$FULL_PATH" /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/empty.pub"
assert_fails 'not a valid OpenSSH public key' env PATH="$FULL_PATH" DEMO_TEST_BAD_KEY=1 /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/demo key.pub"

run_build > "$TEST_ROOT/success.stdout"
[[ -s "$DEMO_OUTPUT_DIR/qcow2/disk.qcow2" ]]
python3 -c 'import pathlib,sys; text=pathlib.Path(sys.argv[1]).read_text(); q=chr(34); assert "console=ttyAMA0" in text; assert "name = "+q+"demo"+q in text; assert "password = "+q+"$6$test-salt$unusable-test-hash"+q in text; assert "ssh-ed25519 AAAAB3NzaC1yc2V1ZDI1NTE5AAAAITestData test key ; printf; safe" in text; assert "wheel" not in text' "$DEMO_TEST_CAPTURE_CONFIG"
python3 -c 'import pathlib,sys; args=pathlib.Path(sys.argv[1]).read_bytes().split(b"\0"); assert b"localhost/android-cluster-demo:local" in args; assert b"quay.io/centos-bootc/bootc-image-builder@sha256:dab86ee6b46dcade411b37f4e70b07d93b6a06da0dbfec6fe89247a220e0d9f8" in args; assert b"--local" in args and b"--type" in args and b"qcow2" in args; assert any(x.decode().endswith("/output with spaces:/output") for x in args); assert any(x.decode().endswith(":/config.toml:ro") for x in args)' "$DEMO_TEST_PODMAN_ARGS"
! python3 -c 'import pathlib,sys; assert sys.argv[1] not in pathlib.Path(sys.argv[2]).read_text()' "$DEMO_TEST_HASH" "$TEST_ROOT/success.stdout"
shopt -s nullglob
leftovers=("$PRIVATE_TMP"/*)
[[ ${#leftovers[@]} == 0 ]]

: > "$DEMO_TEST_PODMAN_ARGS"
export DEMO_TEST_FAIL=run
if run_build > "$TEST_ROOT/failure.stdout" 2> "$TEST_ROOT/failure.stderr"; then
    printf 'Expected image builder failure to propagate\n' >&2
    exit 1
else
    status=$?
fi
[[ "$status" == 23 ]]
leftovers=("$PRIVATE_TMP"/*)
[[ ${#leftovers[@]} == 0 ]]

unset DEMO_TEST_FAIL
: > "$DEMO_TEST_PODMAN_ARGS"
export DEMO_TEST_MAC=1
export DEMO_OUTPUT_DIR="$TEST_ROOT/mac output with spaces"
assert_fails 'Apple Silicon' env PATH="$FULL_PATH" DEMO_TEST_MAC_ARCH=x86_64 /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/demo key.pub"
assert_fails 'Podman Machine must be running' env PATH="$FULL_PATH" DEMO_TEST_FAIL_SSH='sudo -n true' /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/demo key.pub"
assert_fails 'native aarch64 Linux' env PATH="$FULL_PATH" DEMO_TEST_MACHINE=x86_64 /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/demo key.pub"
: > "$DEMO_TEST_PODMAN_ARGS"
assert_fails 'Could not create a temporary directory' env PATH="$FULL_PATH" DEMO_TEST_REMOTE_RESPONSE='/tmp/unsafe' DEMO_TEST_FAIL_SSH='rm -rf' /bin/bash "$BUILD_SCRIPT" "$TEST_ROOT/demo key.pub"
python3 -c 'import pathlib,sys; assert b"rm -rf" not in pathlib.Path(sys.argv[1]).read_bytes()' "$DEMO_TEST_PODMAN_ARGS"
: > "$DEMO_TEST_PODMAN_ARGS"
run_build > "$TEST_ROOT/mac-success.stdout"
[[ -s "$DEMO_OUTPUT_DIR/qcow2/disk.qcow2" ]]
python3 -c 'import pathlib,sys; args=pathlib.Path(sys.argv[1]).read_bytes().split(b"\0"); assert b"machine" in args and b"ssh" in args; assert b"--local" in args and b"qcow2" in args' "$DEMO_TEST_PODMAN_ARGS"
python3 -c 'import pathlib,re,sys; data=pathlib.Path(sys.argv[1]).read_bytes().decode(); paths=set(re.findall(r"/var/tmp/android-cluster-demo\.[A-Za-z0-9]+",data)); assert paths; assert all(not pathlib.Path(p).exists() for p in paths)' "$DEMO_TEST_PODMAN_ARGS"

printf 'original disk\n' > "$DEMO_OUTPUT_DIR/qcow2/disk.qcow2"
export DEMO_TEST_FAIL_SSH='disk.qcow2'
assert_fails 'Podman Machine' run_build
[[ "$(<"$DEMO_OUTPUT_DIR/qcow2/disk.qcow2")" == 'original disk' ]]
python3 -c 'import pathlib,re,sys; data=pathlib.Path(sys.argv[1]).read_bytes().decode(); paths=set(re.findall(r"/var/tmp/android-cluster-demo\.[A-Za-z0-9]+",data)); assert paths; assert all(not pathlib.Path(p).exists() for p in paths)' "$DEMO_TEST_PODMAN_ARGS"
python3 -c 'import pathlib,sys; assert {p.name for p in pathlib.Path(sys.argv[1]).iterdir()} == {"disk.qcow2"}' "$DEMO_OUTPUT_DIR/qcow2"
unset DEMO_TEST_FAIL_SSH
export DEMO_TEST_FAIL=run
assert_fails 'Podman Machine' run_build
[[ $(<"$DEMO_OUTPUT_DIR/qcow2/disk.qcow2") == 'original disk' ]]
python3 -c 'import pathlib,re,sys; data=pathlib.Path(sys.argv[1]).read_bytes().decode(); paths=set(re.findall(r"/var/tmp/android-cluster-demo\.[A-Za-z0-9]+",data)); assert paths; assert all(not pathlib.Path(p).exists() for p in paths)' "$DEMO_TEST_PODMAN_ARGS"

printf 'Build wrapper tests passed\n'