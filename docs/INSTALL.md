# Installation

## Current prototype workflow

1. Build and install the debug APK from `android/app/build/outputs/apk/debug/app-debug.apk`.
2. Open **Klipper USB Bridge** and start the bridge service. A printer MCU may be connected now or later.
3. When a printer is attached through OTG or a powered hub, grant USB access.
4. In a current native Termux installation, clone this repository and run:

   ```sh
   installer/install.sh --source-dir "$PWD"
   ```

5. Copy `~/printer_data/config/bridge.conf.example` to `bridge.conf` and replace `TOKEN`. The default `auto` selector uses the first permitted USB serial port.
6. Run `kabctl printer-starter` to create a safe API-only `printer.cfg`. It
   publishes Klipper to Moonraker and enables Mainsail's virtual SD-card,
   pause/status, response, and object-exclusion components. It does not contain
   motion or heater pins. Replace it with the printer's real configuration,
   retaining the generated MCU PTY path and `restart_method: command`.
7. Run `kabctl doctor`, then start the supervised stack:

   ```sh
   klipper-android-runner start
   ```

   `status`, `restart`, `stop`, and `monitor` are also available. Runit, not
   the runner process, monitors and restarts Klipper, Moonraker, the bridge,
   and nginx. `enable` makes the stack start whenever Termux's service daemon
   starts; `disable` restores explicit startup.

8. Open `http://PHONE_IP:8080/` from the local network.

## Starting the stack from the companion app

The app's **Start Klipper stack** button sends Termux's documented background
`RUN_COMMAND` intent. It starts the runner without opening a terminal activity,
including when Termux is not currently visible, and starts the Android USB
bridge foreground service in the same tap.

One-time setup is required:

1. Use a Termux release that provides `RunCommandService` (Termux 0.95+).
2. Leave the installer's external-app control enabled. It writes
   `allow-external-apps = true` to `~/.termux/termux.properties`; pass
   `--no-app-control` to opt out.
3. In Android App Info for **Klipper USB Bridge**, grant the Termux
   `RUN_COMMAND` permission, usually listed under Additional permissions.

This is deliberately permission-gated: enabling external commands lets this
app execute the fixed installed runner in Termux's account. The bridge app does
not accept arbitrary command text from its UI or network protocol.

To keep the APK and dependency graph small, the Termux adapter contains the
documented intent constants directly instead of depending on the larger
`termux-shared` library. They are isolated in `TermuxRunner.kt` so compatibility
changes remain localized.

Open **Settings** from the navigation drawer or overflow menu, then tap
**Install and Setup Klipper** to enter the guided checklist. The wizard detects
Termux, command permission, USB permission, live bridge connections, and a
working Mainsail page. It also shows a copyable curl installer command. APKs built
with `KAB_INSTALLER_URL` and `KAB_REPOSITORY_URL` enable an **Install Klipper
in Termux** button that sends that fixed command through the same permission gate.
Installation runs in a visible Termux terminal so progress and download or
package-manager errors are not hidden; stack start/stop/status commands remain
background commands.
The bridge step places the app's token, listener port, and automatic USB selector
into Termux's `~/printer_data/config/bridge.conf` through the permission-gated
command API. The native bridge publishes a PTY even with no device attached and
retries the Android service with bounded backoff. Klipper can therefore start in
an expected MCU-unavailable state while Moonraker and Mainsail remain testable.
When a permitted serial device appears, Android binds the next OPEN request to
the first available port; no config rewrite or service restart is required.
**Configure Termux bridge** also asks `kabctl` to install the starter
configuration when no user-owned `printer.cfg` exists. Existing printer-specific
configuration is never overwritten. This is enough for Klipper and Moonraker to
connect and expose configuration editing in Mainsail; connecting a physical
printer still requires compatible Klipper firmware on the controller and the
correct board/printer pin configuration.

The setup wizard includes optional SSH access. **Install and configure SSH**
opens a visible Termux session, installs `openssh`, and runs `passwd` there; the
Android app never receives the password. It configures and starts the supervised
server on TCP port 2020. Connect using the Termux username shown at completion:

```sh
ssh -p 2020 TERMUX_USER@PHONE_LAN_IP
```

Do not expose this password-authenticated port through a router. Prefer adding
an SSH public key and disabling password authentication for long-term use.

The dashboard shows the phone's preferred Wi-Fi/Ethernet IPv4 address and the
corresponding Mainsail URL below System status. Tap it to copy the address.
Moonraker advertises `klipper-android.local` through its built-in Zeroconf
component by default. Change the label under **Settings → Network identity**;
the app validates it, updates `moonraker.conf` through `kabctl`, and restarts
Moonraker when it is already running. The companion bridge service holds an
Android Wi-Fi multicast lock while active so mDNS remains reliable despite
Android's multicast filtering. Direct IP access remains available on networks
that block client-to-client multicast.

On a fresh Termux installation, `allow-external-apps = true` must still be
confirmed once inside Termux before Android is allowed to invoke the installer.
The wizard's **Copy enable command and open Termux** action copies an idempotent
configuration command and launches Termux; paste it and press Enter. Keeping
that final confirmation inside Termux preserves its security boundary.

## Build tools and runtime dependencies

The supported desktop build is command-line only:

- JDK 17-21 (the project compiles its Java/Kotlin bytecode for Java 17).
- Android SDK command-line tools with platform API 35 and matching build tools.
- `adb`/Platform-Tools only for `scripts/build-and-upload-app.sh`.
- The checked-in Gradle wrapper, which downloads Gradle, the Android Gradle
  plugin, Kotlin plugin, and USB serial library on first use.

Android Studio and the emulator are not required. Driving `aapt2`, `d8`, and
`apksigner` by hand would still require Android platform/build-tool files and
would replace Gradle with brittle custom dependency packaging, so it is not a
useful reduction. A release APK built by CI would leave end users with no SDK,
JDK, or Gradle requirement.

On the phone, the APK has no SDK dependency. The installer uses native Termux
packages: Git, Python, Clang, Make, libffi/OpenSSL/zlib, curl/unzip, and
`termux-services`; `iproute2` supplies network information to Moonraker, and
nginx is added for Mainsail. The C bridge itself can be
compiled directly with `bridge/build-termux.sh`, avoiding CMake and Ninja.

The installer keeps its executables under `~/.local/bin` and creates managed
links for `kabctl`, `klipper-android-runner`, and `klipper-android-bridge` in
`$PREFIX/bin`. Termux already includes that directory in `PATH`, so the commands
work immediately without editing `.bashrc`, `.zshrc`, or the current shell.

The eventual convenience form is:

```sh
curl -fsSL https://raw.githubusercontent.com/OWNER/REPOSITORY/main/installer/install.sh | \
  KAB_REPOSITORY=https://github.com/OWNER/REPOSITORY.git bash
```

The repository URL remains a placeholder until this project is published. Downloading, inspecting, and executing the script separately is safer than curl-to-shell.

Klipper, Moonraker, and the bridge project are shallow working-tree checkouts:
only the selected revision is fetched, rather than the projects' full Git
history. Python dependencies use pip without retaining downloaded-wheel caches,
and the Mainsail archive is deleted after extraction.

Rerunning the installer detects an existing managed installation and asks for
`UPDATE` or `DELETE` in the Termux terminal. Update stops the running stack,
checks the bridge project for a newer revision, and fetches Klipper or Moonraker
only when the selected revision is not already present. The native bridge is
rebuilt only when its C headers, sources, or build script changed. Existing
virtual environments are retained; pip is rerun against them and reuses
already-satisfied packages, changing only dependencies required by the current
requirements files. A missing or broken environment is recreated. Mainsail is
downloaded only when its pinned release changed or its installed `index.html`
is missing. It is verified and extracted into a staging directory before the
old UI is replaced. Generated service scripts and Android compatibility launchers
are refreshed, and a previously running stack is restarted. If an update is
interrupted after stopping services, the next successful UPDATE remembers to
restart them. Configuration, gcodes, database, and logs are preserved. Delete
performs a clean reinstall and removes all managed files.
Both modes preserve Termux packages, `~/.termux/termux.properties`, and unrelated
files. Automation may pass `--update` or `--reinstall` explicitly;
`--non-interactive` without either mode refuses to change an existing install.

Installer output is appended to
`~/.local/state/klipper-android/installer.log` (rotated after 1 MiB). Inspect it
with `kabctl installer-log`; failed commands include their approximate script
line and exit status. Klipper is launched through a small compatibility shim
that ignores only Android's denial of `chmod(0660)` on a Klipper-created
`/dev/pts/*` node. Other permission errors are preserved.
The optional Klipper G-code terminal is published below Termux's writable
`$PREFIX/var/run/klipper-android` directory instead of upstream's hard-coded
`/tmp/printer` path.
Moonraker also uses a small compatibility launcher. On Android devices where
SELinux exposes but denies enumeration of `/sys/class/hwmon`, it treats that
directory as empty and uses Moonraker's normal `thermal_zone0` fallback. Other
filesystem permission errors are not suppressed.
The managed nginx proxy preserves Mainsail's original `Host` header when
forwarding API and websocket traffic. This lets Moonraker validate the
same-origin websocket without opening broad CORS access.

## Operational requirements

- Android 7/API 24 or newer.
- USB host/OTG support.
- A powered hub for multiple MCUs or boards that draw significant current.
- Battery optimization disabled for both Termux and the companion app.
- Native Termux; proot is deliberately unsupported by the installer.
- `restart_method: command`; PTY transport cannot propagate arbitrary DTR/RTS reset sequences.
