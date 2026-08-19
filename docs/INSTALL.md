# Installation

## Current prototype workflow

1. Build and install the debug APK from `android/app/build/outputs/apk/debug/app-debug.apk`.
2. Open **Klipper USB Bridge**, connect the printer MCU through OTG or a powered hub, and grant USB access.
3. Start the bridge service. Copy the token and note the device UUID shown on its card.
4. In a current native Termux installation, clone this repository and run:

   ```sh
   installer/install.sh --source-dir "$PWD"
   ```

5. Copy `~/printer_data/config/bridge.conf.example` to `bridge.conf`; replace `TOKEN` and `DEVICE_UUID`.
6. Replace the sample `printer.cfg` with the printer's configuration, retaining the generated MCU PTY path and `restart_method: command`.
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
with `KAB_INSTALLER_URL` and `KAB_REPOSITORY_URL` enable an **Install in
Termux** button that sends that fixed command through the same permission gate.
The bridge step can place the app's token and listener port into Termux's
`~/printer_data/config/bridge.conf` through the permission-gated command API.
The USB device UUID still must be learned after attaching the printer.

On a fresh Termux installation, `allow-external-apps = true` must still be set
manually before Android is allowed to invoke the installer; copying the command
into Termux does not require external-app control.

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
`termux-services`; nginx is added for Mainsail. The C bridge itself can be
compiled directly with `bridge/build-termux.sh`, avoiding CMake and Ninja.

The eventual convenience form is:

```sh
curl -fsSL https://raw.githubusercontent.com/OWNER/REPOSITORY/main/installer/install.sh | \
  KAB_REPOSITORY=https://github.com/OWNER/REPOSITORY.git bash
```

The repository URL remains a placeholder until this project is published. Downloading, inspecting, and executing the script separately is safer than curl-to-shell.

## Operational requirements

- Android 7/API 24 or newer.
- USB host/OTG support.
- A powered hub for multiple MCUs or boards that draw significant current.
- Battery optimization disabled for both Termux and the companion app.
- Native Termux; proot is deliberately unsupported by the installer.
- `restart_method: command`; PTY transport cannot propagate arbitrary DTR/RTS reset sequences.
