# Android Cluster bootc demo image

This standalone example builds an ARM64 CentOS Stream bootc disk for the Android
Cluster app. It is intentionally outside the app source and does not change the
app. The guest serves a small HTTP page, accepts SSH public-key authentication,
and offers a non-root serial shell.

> **Local demo only — not production configuration.** Serial access automatically
> opens a shell as `demo`; HTTP is unencrypted; and the app's forwarded ports
> listen on the phone's network interfaces. Use only a trusted, isolated LAN.
> Do not ship this disk or reuse its settings as production configuration.

## Requirements

Build on native `aarch64` Linux with rootful Podman, or on Apple Silicon macOS
with an ARM64 Podman Machine. The Linux environment needs `bash`, `ssh-keygen`
(OpenSSH), OpenSSL, Python 3, and standard `mktemp`/file utilities. On macOS,
the default Podman Machine must be running, have those tools and `tar`, and
allow passwordless `sudo` inside the VM. The Mac needs Podman, `bash`, `tar`,
and standard file utilities. Internet access is needed to pull CentOS Stream 10
and the pinned bootc image builder. Allow several gigabytes of free storage in
the Linux host or VM and on the Mac for the copied disk. Install `qemu-img` to
inspect the resulting disk, and Android `adb` only if you plan to transfer it
with the Android Debug Bridge. Intel Macs, x86-64 Linux, and native rootless
builds are not supported.

The image builder is referenced by an immutable multi-architecture manifest
digest in `build.sh`, rather than the registry's mutable `latest` tag. The
service image uses the CentOS Stream 10 `stream10` tag.

## Create an SSH key

Create a dedicated client key on the build host (or another trusted computer):

```bash
ssh-keygen -t ed25519 -f "$HOME/.ssh/android-cluster-demo" -C android-cluster-demo
```

Keep `android-cluster-demo` private and pass only the `.pub` file to the build.
The script validates that the public-key file contains exactly one OpenSSH key.
It creates the unprivileged `demo` account and installs that public key into the
disk at build time. A unique random password hash is generated locally for the
account; the hash is stored only in a private temporary config, is never printed,
and that config is removed on exit. The `demo` account is not added to `wheel` or
given `sudo` access.

## Build the disk

On native ARM64 Linux, run the wrapper from the repository root with rootful
Podman and the public key:

```bash
sudo scripts/demo-image/build.sh "$HOME/.ssh/android-cluster-demo.pub"
```

On Apple Silicon, install Podman and initialize its default machine if needed,
giving it enough disk and memory. Start an existing machine without reinitializing
it. For example, to create a new default machine and start it:

```bash
podman machine init --cpus 4 --memory 8192 --disk-size 60
podman machine start
```

Run the wrapper on the Mac **without** `sudo`:

```bash
scripts/demo-image/build.sh "$HOME/.ssh/android-cluster-demo.pub"
```

The wrapper copies only the build files and public key to a private directory in
the VM, runs the Linux build there with `sudo -n` and rootful Podman, then copies
the disk back. It does not require the project directory to be shared with the
VM or change the machine's default rootless/rootful connection. The VM's `/var`
filesystem needs space for the temporary disk as well as container storage.
Temporary files are removed on exit; if the VM becomes unreachable, remove the
reported temporary directory inside the VM after restoring access.

The wrapper checks the host architecture and rootful Podman storage, builds the
reusable service image, then invokes the pinned privileged bootc image builder
with the local image, `kernel-args.toml`, and QCOW2 output options. The generated
disk is normally:

```text
scripts/demo-image/output/qcow2/disk.qcow2
```

The script prints the absolute artifact path on success. The `output/` directory
is ignored by Git. Optionally inspect the format and virtual size:

```bash
qemu-img info scripts/demo-image/output/qcow2/disk.qcow2
```

Confirm the output reports `file format: qcow2` before transferring it.

## Transfer and import

Copy the disk to the phone using a trusted transfer method. For example, with
USB debugging enabled and the phone connected:

```bash
adb push scripts/demo-image/output/qcow2/disk.qcow2 /sdcard/Download/android-cluster-demo.qcow2
```

In Android Cluster, use the image card's import action to select the copied
`.qcow2` file, then select that imported image. The app copies picker-selected
images into its private storage, so the transfer copy can be removed after the
import is confirmed.

## Configure mappings and start

Before starting the VM, add both mappings in the app's port-mapping card. Enter
the phone-side host port first and the guest-side port second:

| Host port on phone | Guest port | Service |
| ---: | ---: | --- |
| `18080` | `8080` | HTTP demo page |
| `12222` | `22` | SSH |

Start the selected disk only after both mappings are listed. The app adds a
virtio NIC when mappings exist and enforces at least 1 GiB RAM for QCOW2 boots.
The disk includes `console=ttyAMA0` in its boot configuration because the app
does not add kernel arguments to QCOW2 boots.

## Check HTTP and SSH from the LAN

Find the phone's current LAN IP address and run these commands from another
client on the same trusted network. Replace `PHONE_IP` with that address:

```bash
curl http://PHONE_IP:18080/
ssh -i "$HOME/.ssh/android-cluster-demo" -o IdentitiesOnly=yes -p 12222 demo@PHONE_IP
```

The HTTP response should contain the `Android Cluster bootc demo` heading. SSH
logs in as the non-root `demo` user using the supplied key. Password and root
SSH login are disabled. The guest firewall allows SSH and TCP port `8080` only.

## Use the serial shell

The guest configures `ttyAMA0` to automatically log in as `demo`, not root. The
app's console displays complete output lines, so the shell prompt may not appear
until a command prints a newline. Send this line in the console input:

```bash
printf '%s\n' ANDROID_CLUSTER_SERIAL_OK
```

The console should show `ANDROID_CLUSTER_SERIAL_OK` on its own line. The final
newline is deliberate so the app's line-oriented display can show the result.

## Limitations and security

- This example is for a trusted local demonstration only. The serial console
  grants anyone with console access a shell as `demo` without authentication.
- HTTP is cleartext. SSH is key-only, but the client private key must remain
  private and should not be copied onto the phone.
- Port forwarding is exposed on the phone's network interfaces. Use a trusted
  LAN, consider its firewall and isolation, and remove both mappings when done.
- The guest depends on DHCP, UEFI, virtio block/network, and the app's QEMU
  configuration. A QCOW2 file alone does not guarantee that the disk will boot
  on every phone or firmware build.
- No production hardening, availability, or network isolation is provided.

## Cleanup

Delete the imported image from the app and remove the generated disk when it is
no longer needed:

```bash
rm -f scripts/demo-image/output/qcow2/disk.qcow2
```

If the key was created only for this demo, remove its private and public files
from the key's directory when finished. File deletion does not guarantee secure
erasure on flash storage. The build script removes its temporary config on both
success and failure; the built service image and pinned builder image remain in
rootful Podman's local storage. Remove those images only if they are not needed
by another workload.

## Validation status

The shell wrapper passes `bash -n` and `test-build.sh`, which exercises host and
key validation, command argument handling, private config permissions, VM
transfer and cleanup, and failure handling with stubbed commands. On Apple
Silicon with Podman 6.1.4, a real Podman Machine build completed and `qemu-img`
reported a non-corrupt QCOW2 disk with a 10 GiB virtual size. Guest boot and
service checks were not run. No Android device was attached, so import,
serial-console behavior, and LAN forwarding were not verified here.