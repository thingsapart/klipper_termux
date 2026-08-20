# Klipper Android Bridge

Run an unmodified Klipper host natively in Termux while a small Android app owns the USB serial connection required by Android's security model.

```text
Klipper → PTY → native C bridge → raw loopback TCP → Android USB service → MCU
```

The project currently contains a buildable prototype:

- Multi-device native C bridge with fixed buffers and no hot-path message parsing.
- Fixed-size authenticated binary setup protocol followed by an unframed raw stream.
- Android 7+ companion app using `usb-serial-for-android`.
- CDC-ACM, CH340/CH341, CP210x, FTDI, and PL2303 driver coverage from that library.
- Foreground service, wake lock while connected, USB permission UI, TX/RX indicators, rates, totals, and error counters.
- Native dashboard plus a retained, loopback-only Mainsail WebView; the app-bar button switches directly between them, while the drawer and overflow menu expose Setup.
- Native Termux installer for Klipper, Moonraker, Mainsail, nginx, and runit services.
- Native unit tests, PTY/TCP integration test, Android protocol tests, installer checks, and latency benchmark.

This has not yet been validated on a physical Android phone or printer. Do not rely on it for an unattended or safety-critical print until the hardware test matrix in [the implementation plan](docs/IMPLEMENTATION_PLAN.md) passes.

## Build and test

Native bridge:

```sh
cmake -S . -B build -G Ninja -DBUILD_TESTING=ON
cmake --build build
ctest --test-dir build --output-on-failure
```

Android app:

```sh
scripts/build-app.sh
# Build, install over ADB, and launch:
scripts/build-and-upload-app.sh [--serial DEVICE_SERIAL]
```

Standalone offline Klipper Configurator:

```sh
scripts/build-configurator-app.sh
```

The standalone APK shares its pure Kotlin generator and Android wizard with the
bridge app. It needs no USB, Termux, network, or storage permission: Android's
document picker exports a versioned ZIP to Downloads (or another user-selected
location) and can reopen its own exported projects for continued editing.

Release/distribution builds can embed the published one-tap Termux installer
locations without editing source. If `origin` is a GitHub SSH or HTTPS URL,
both build scripts derive these first two values automatically; explicit values
remain available for forks, mirrors, or non-`main` builds:

```sh
KAB_INSTALLER_URL=https://raw.githubusercontent.com/OWNER/REPO/main/installer/install.sh \
KAB_REPOSITORY_URL=https://github.com/OWNER/REPO.git \
KAB_TERMUX_DOWNLOAD_URL=https://f-droid.org/packages/com.termux/ \
KAB_TERMUX_GITHUB_RELEASES_URL=https://github.com/termux/termux-app/releases \
scripts/build-app.sh
```

The last two variables set the initial Termux download links shown in Settings.
They default to F-Droid and the official GitHub Releases page and can also be
edited and persisted inside the app.

Until both values are supplied, the setup wizard keeps its install button disabled
and visibly shows the publication placeholders.

The embedded Mainsail view defaults to `http://127.0.0.1:8080/`. Its address is
editable in Settings, but deliberately restricted to `localhost`,
`127.0.0.1`, or `::1`; non-local web links open in the system browser. The core
Mainsail UI, controls, and WebSocket connection are supported. Native file
chooser/download integration is deferred.

The debug APK is produced under `android/app/build/outputs/apk/debug/`.

## Prepare a GitHub release APK

The Android app contains no ABI-specific native libraries, so one universal APK
covers every supported phone architecture. For an explicitly marked test
prerelease, add the real GitHub repository as `origin`, then use the stable
local debug key:

```sh
git remote add origin https://github.com/OWNER/REPO.git
scripts/prepare-release.sh 0.1.0-test.1 --test-signing
```

For a real release, keep the keystore outside the repository and provide all
four signing values:

```sh
export KAB_SIGNING_STORE_FILE=/secure/path/klipper-bridge-release.jks
export KAB_SIGNING_STORE_PASSWORD='...'
export KAB_SIGNING_KEY_ALIAS=klipper-bridge
export KAB_SIGNING_KEY_PASSWORD='...'
scripts/prepare-release.sh 0.1.0
```

The helper runs unit tests and release lint, builds the minified release,
verifies its signature, and writes the universal APK and `.sha256` file to
`dist/`. Both `dist/` and signing-key formats are ignored by Git.

No GitHub CLI is needed. Push the source and release tag with ordinary Git:

```sh
git push -u origin main
git tag -a v0.1.0-test.1 -m "Test release 0.1.0-test.1"
git push origin v0.1.0-test.1
```

Then open the repository on github.com, choose **Releases → Draft a new
release**, select the pushed tag, and upload the APK and `.sha256` from `dist/`.
Mark debug-signed builds as prereleases.

The `origin` remote is the single normal source of repository identity for APK
builds. `KAB_SOURCE_REF` defaults to `main` and can select another published
branch. `KAB_REPOSITORY_URL` and `KAB_INSTALLER_URL` override inference when
needed. Placeholder strings remaining in Android defaults and tests are guards:
they keep one-tap installation disabled in builds that have no configured
repository and do not need manual replacement.

Android Studio is not required. Building needs JDK 17-21 plus the Android SDK
command-line platform/build tools for API 35; upload also needs Platform-Tools
(`adb`). The Gradle wrapper obtains Gradle and project dependencies. An
already-built APK needs none of these tools on the phone.

For the native bridge alone, Termux needs only Clang and its normal C library:

```sh
bridge/build-termux.sh /tmp/klipper-android-bridge
```

Installer syntax/dry-run test:

```sh
tests/installer/test_installer.sh
```

See [INSTALL.md](docs/INSTALL.md) for the phone workflow and [PROTOCOL.md](docs/PROTOCOL.md) for the wire format.
