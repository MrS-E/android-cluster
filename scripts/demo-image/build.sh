#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

usage() {
    printf 'Usage: %s SSH_PUBLIC_KEY_FILE\n' "${0##*/}" >&2
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

if (($# != 1)); then
    usage
    exit 2
fi

KEY_FILE=$1
[[ -f "$KEY_FILE" && -r "$KEY_FILE" ]] || die "SSH public-key file is missing or unreadable: $KEY_FILE"

SCRIPT_PATH=${BASH_SOURCE[0]}
case "$SCRIPT_PATH" in
    */*) ;;
    *) SCRIPT_PATH="$PWD/$SCRIPT_PATH" ;;
esac
SCRIPT_DIR="$(cd -- "${SCRIPT_PATH%/*}" && pwd -P)"

HOST_OS="$(uname -s)"
HOST_ARCH="$(uname -m)"

if [[ "$HOST_OS" == Darwin ]]; then
    [[ "$HOST_ARCH" == arm64 ]] || die "Apple Silicon macOS is required; detected $HOST_ARCH"
    for required_command in podman tar mkdir mktemp chmod rm mv; do
        command -v "$required_command" >/dev/null 2>&1 || die "Required command not found: $required_command"
    done

    OUTPUT_DIR="${DEMO_OUTPUT_DIR:-$SCRIPT_DIR/output}"
    mkdir -p -- "$OUTPUT_DIR/qcow2"
    OUTPUT_DIR="$(cd -- "$OUTPUT_DIR" && pwd -P)"
    LOCAL_DISK_TMP=''
    REMOTE_DIR=''

    cleanup_machine_build() {
        if [[ -n "$LOCAL_DISK_TMP" ]]; then
            rm -f -- "$LOCAL_DISK_TMP"
        fi
        if [[ -n "$REMOTE_DIR" ]]; then
            podman machine ssh -- "sudo -n rm -rf -- '$REMOTE_DIR'" \
                || printf 'warning: Could not remove Podman Machine temporary directory: %s\n' "$REMOTE_DIR" >&2
        fi
    }
    trap cleanup_machine_build EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    podman machine ssh -- 'sudo -n true' \
        || die 'Podman Machine must be running and allow passwordless sudo; start it with podman machine start'
    REMOTE_DIR_CANDIDATE="$(podman machine ssh -- 'umask 077; mktemp -d /var/tmp/android-cluster-demo.XXXXXX')" \
        || die 'Could not create a temporary directory in Podman Machine'
    if [[ ! "$REMOTE_DIR_CANDIDATE" =~ ^/var/tmp/android-cluster-demo\.[[:alnum:]]+$ ]]; then
        die 'Podman Machine returned an unexpected temporary directory path'
    fi
    REMOTE_DIR="$REMOTE_DIR_CANDIDATE"
    unset REMOTE_DIR_CANDIDATE

    tar -c -C "$SCRIPT_DIR" Containerfile config kernel-args.toml build.sh \
        | podman machine ssh -- "tar -xf - -C '$REMOTE_DIR'" \
        || die 'Could not transfer build files to Podman Machine'
    podman machine ssh -- "umask 077; cat > '$REMOTE_DIR/demo.pub'" < "$KEY_FILE" \
        || die 'Could not transfer the public key to Podman Machine'

    printf 'Building inside Podman Machine\n'
    podman machine ssh -- "sudo -n env TMPDIR='$REMOTE_DIR' DEMO_OUTPUT_DIR='$REMOTE_DIR/output' /bin/bash '$REMOTE_DIR/build.sh' '$REMOTE_DIR/demo.pub'" \
        || die 'Build failed inside Podman Machine'

    LOCAL_DISK_TMP="$(mktemp "$OUTPUT_DIR/qcow2/.disk.qcow2.XXXXXX")"
    podman machine ssh -- "sudo -n cat '$REMOTE_DIR/output/qcow2/disk.qcow2'" > "$LOCAL_DISK_TMP" \
        || die 'Could not transfer the disk from Podman Machine'
    [[ -s "$LOCAL_DISK_TMP" ]] || die 'Podman Machine returned an empty disk image'
    chmod 0644 "$LOCAL_DISK_TMP"
    mv -f -- "$LOCAL_DISK_TMP" "$OUTPUT_DIR/qcow2/disk.qcow2"
    LOCAL_DISK_TMP=''
    printf 'QCOW2 image: %s\n' "$OUTPUT_DIR/qcow2/disk.qcow2"
    exit 0
fi

[[ "$HOST_OS" == Linux ]] || die "Build on native ARM64 Linux; detected $HOST_OS"
[[ "$HOST_ARCH" == aarch64 ]] || die "Build on native aarch64 Linux; detected $HOST_ARCH"
[[ "$(id -u)" == 0 ]] || die "Run with rootful Podman (for example, sudo $0 SSH_PUBLIC_KEY_FILE)"

for required_command in podman ssh-keygen openssl python3 mkdir mktemp chmod rm; do
    command -v "$required_command" >/dev/null 2>&1 || die "Required command not found: $required_command"
done

python3 -c 'import pathlib, sys; lines = [line.strip() for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line.strip()]; raise SystemExit(0 if len(lines) == 1 else 1)' "$KEY_FILE" \
    || die 'SSH public-key file must contain exactly one non-empty key line'
ssh-keygen -lf "$KEY_FILE" >/dev/null 2>&1 || die 'SSH public-key file is not a valid OpenSSH public key'

ROOTLESS="$(podman info --format '{{.Host.Security.Rootless}}')" \
    || die 'Could not query Podman; make sure rootful Podman is installed and available'
[[ "$ROOTLESS" == false ]] || die 'Podman is running rootless; run this script with rootful Podman'
STORAGE_ROOT="$(podman info --format '{{.Store.GraphRoot}}')" \
    || die 'Could not determine the rootful Podman storage directory'
[[ "$STORAGE_ROOT" == /* && -d "$STORAGE_ROOT" ]] \
    || die "Rootful Podman storage directory is missing or not absolute: $STORAGE_ROOT"

OUTPUT_DIR="${DEMO_OUTPUT_DIR:-$SCRIPT_DIR/output}"
mkdir -p -- "$OUTPUT_DIR"
OUTPUT_DIR="$(cd -- "$OUTPUT_DIR" && pwd -P)"

LOCAL_IMAGE=localhost/android-cluster-demo:local
BUILDER_IMAGE=quay.io/centos-bootc/bootc-image-builder@sha256:dab86ee6b46dcade411b37f4e70b07d93b6a06da0dbfec6fe89247a220e0d9f8

PRIVATE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/android-cluster-demo.XXXXXX")"
trap 'rm -rf -- "$PRIVATE_DIR"' EXIT
chmod 0700 "$PRIVATE_DIR"
CONFIG_FILE="$PRIVATE_DIR/config.toml"
umask 077

PASSWORD_HASH="$(openssl rand -base64 48 | openssl passwd -6 -stdin)" \
    || die 'Could not generate an unusable password hash; check the OpenSSL installation'
[[ "$PASSWORD_HASH" == '$6$'* ]] || die 'OpenSSL did not produce a SHA-512 password hash'

python3 -c '
import json
import pathlib
import sys

template_path, config_path = map(pathlib.Path, sys.argv[1:])
key, password_hash = sys.stdin.buffer.read().decode("utf-8").split("\0", 1)
template = template_path.read_text(encoding="utf-8").rstrip()
with config_path.open("x", encoding="utf-8") as config:
    config.write(template + "\n\n[[customizations.user]]\n")
    config.write("name = " + json.dumps("demo") + "\n")
    config.write("home = " + json.dumps("/home/demo") + "\n")
    config.write("shell = " + json.dumps("/usr/bin/bash") + "\n")
    config.write("password = " + json.dumps(password_hash) + "\n")
    config.write("key = " + json.dumps(key) + "\n")
config_path.chmod(0o600)
' "$SCRIPT_DIR/kernel-args.toml" "$CONFIG_FILE" < <(printf '%s\0%s' "$(python3 -c 'import pathlib,sys; print(next(line.strip() for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line.strip()))' "$KEY_FILE")" "$PASSWORD_HASH")
unset PASSWORD_HASH

printf 'Building service image %s\n' "$LOCAL_IMAGE"
podman build --pull=missing --tag "$LOCAL_IMAGE" --file "$SCRIPT_DIR/Containerfile" "$SCRIPT_DIR"

printf 'Building ARM64 QCOW2 disk\n'
podman run \
    --rm \
    --privileged \
    --pull=missing \
    --security-opt label=type:unconfined_t \
    --volume "$CONFIG_FILE:/config.toml:ro" \
    --volume "$OUTPUT_DIR:/output" \
    --volume "$STORAGE_ROOT:/var/lib/containers/storage" \
    "$BUILDER_IMAGE" \
    --type qcow2 \
    --config /config.toml \
    --local \
    "$LOCAL_IMAGE"

DISK_IMAGE="$OUTPUT_DIR/qcow2/disk.qcow2"
[[ -s "$DISK_IMAGE" ]] || die "Image builder completed without producing $DISK_IMAGE"
chmod 0644 "$DISK_IMAGE"
printf 'QCOW2 image: %s\n' "$DISK_IMAGE"