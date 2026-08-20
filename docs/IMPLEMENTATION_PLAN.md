# Klipper on Android/Termux: Architecture and Implementation Plan

Status: design baseline for review  
Target milestone: native, no-root Klipper communicating with common USB serial MCUs  
Minimum Android version: Android 7.0 / API 24, subject to physical-device validation

## 1. Goals

The project will make an Android phone act as a practical Klipper host without rooting the phone and without running Klipper inside a proot Linux distribution.

The initial production path is:

```text
Klipper in native Termux
        │ normal serial file configured in printer.cfg
        ▼
Termux pseudo-terminal (PTY)
        │ raw bytes
        ▼
Native Termux bridge daemon
        │ binary setup handshake, then raw TCP byte stream
        ▼
Android companion app foreground service
        │ Android USB Host API + userspace USB-serial driver
        ▼
Klipper MCU over USB
```

The first milestone must:

- Run Klipper natively in Termux, without Debian/Ubuntu proot.
- Keep the upstream Klipper source unmodified.
- Support Android 7+ if physical testing finds no platform-specific blocker.
- Support CDC-ACM, CH340/CH341, CP210x, FTDI, and PL2303 USB serial devices.
- Support more than one MCU at the same time.
- Add little latency, CPU load, allocation pressure, or copying.
- Shut down safely on transport loss and never resume a failed print automatically.
- Provide a one-command Termux installer for Klipper, Moonraker, Mainsail, the bridge, and services.
- Provide a small companion-app UI showing permissions, connection states, traffic, and diagnostic counters.

Firmware flashing and USB-CAN are planned extensions, not requirements for the first working print.

## 2. Non-goals for the First Milestone

- Running arbitrary Linux USB software through a general-purpose USB proxy.
- Providing a Linux kernel TTY or SocketCAN device from the Android app.
- Transparent propagation of every POSIX serial ioctl through a PTY.
- Automatically supporting DFU, HID bootloaders, Katapult, or vendor-specific flash protocols.
- Automatically resuming an interrupted print.
- Replacing Mainsail, Moonraker, or Klipper with Android-native implementations.
- Root-only installation or modification of Android device-node permissions.

## 3. Feasibility and Platform Assumptions

Android exposes USB host devices through `UsbManager`. Permission belongs to the Android application that requested it and normally lasts until detach. A Termux process cannot directly open the protected USB device node, which is the core problem this project solves.

The companion app will use [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android). It implements the common USB-UART protocols in userspace and currently declares API 17 as its minimum. Android's USB Host API dates back further than the project's Android 7 target.

Current Termux supports Android 7 and newer. Therefore the intended application minimum is API 24. Code using newer foreground-service, notification, or permission APIs must be guarded by SDK checks. Android 7 support is considered accepted only after a real API 24/25 phone completes the hardware test matrix.

KIAUH will not be invoked by the installer. KIAUH assumes a Debian-family system, `apt`, `sudo`, and systemd. Native Termux instead uses `pkg`, application-private paths, and runit through `termux-services`. Its component layout and user experience can inform this project, but its implementation is not directly reusable.

## 4. Repository Layout

The intended repository layout is:

```text
android/                  Android companion application
  app/
  build.gradle(.kts)
bridge/                   Native Termux bridge
  include/                Shared binary protocol declarations
  src/
  tests/
installer/
  install.sh              Small curl-to-shell bootstrap
  lib/                    Installer implementation modules
  services/               runit templates
  config/                 Klipper, Moonraker, nginx templates
scripts/
  benchmark/              Traffic and latency tools
  release/                Checksums and release packaging
tests/
  integration/            Fake Android service and PTY tests
docs/
  IMPLEMENTATION_PLAN.md
  INSTALL.md
  TROUBLESHOOTING.md
  PROTOCOL.md
```

The Android app and native daemon will share protocol constants through generated or mechanically verified definitions. Generation must be deterministic and checked into the repository; builds must fail if the C and Kotlin layouts disagree.

## 5. Transport Architecture

### 5.1 Why a PTY

Klipper normally opens a serial path using pyserial, configures termios, and passes the resulting descriptor to its C serial queue. A PTY provides the descriptor behavior Klipper expects while avoiding a maintained Klipper patch.

For each MCU, the Termux bridge will:

1. Allocate a PTY master/slave pair.
2. Put the PTY in raw mode.
3. Publish the slave through an atomic stable symlink, for example:

   ```text
   $PREFIX/var/run/klipper-android/mcu
   ```

4. Keep the PTY master open while reconnecting the Android transport.
5. Forward bytes only while the matching Android USB session is open.

Klipper configuration will use the stable symlink:

```ini
[mcu]
serial: /data/data/com.termux/files/usr/var/run/klipper-android/mcu
baud: 250000
restart_method: command
```

The exact Termux prefix will be substituted by the installer rather than hardcoded in generated configuration.

### 5.2 Why Loopback TCP

The companion app and Termux are separate Android application sandboxes. A loopback TCP socket is portable across supported Android versions and does not require shared signing keys, Binder integration with Termux, filesystem sharing, or SELinux exceptions.

The service will bind only to `127.0.0.1` and `::1`. The default port is `27831`, configurable to resolve conflicts. Binding to wildcard addresses is forbidden.

TCP overhead at common Klipper baud rates is small relative to USB transfer and application scheduling overhead. The implementation will still:

- Enable `TCP_NODELAY` in both directions.
- Use persistent data connections.
- Avoid application framing after setup.
- Use fixed buffers and nonblocking I/O on the Termux side.
- Avoid TLS because the traffic never leaves loopback and authentication is performed by a random local token.
- Benchmark Unix-domain alternatives only as a research path; they are not the compatibility baseline.

### 5.3 Connection Ownership

There is one raw TCP data connection per open USB serial port. One native bridge daemon may manage multiple PTYs and data connections in a single event loop.

The Android service has exclusive ownership of an open USB port. A second open request for the same port returns `DEVICE_BUSY`. This prevents two Klipper instances or diagnostic clients from interleaving bytes.

## 6. Binary Protocol Version 1

### 6.1 Design Principles

- Parse setup data once per connection.
- Carry raw serial bytes without framing or decoding after setup.
- Use fixed-width integer fields and network byte order.
- Put a version in every setup message.
- Bound every variable-length response.
- Never infer a protocol from partial magic or silently downgrade versions.
- Keep administrative/control requests separate from active serial streams.

### 6.2 Open Request

The fixed 72-byte open request is:

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 8 | magic | `KLIPUSB\0` |
| 8 | 2 | version | Protocol version, initially `1` |
| 10 | 2 | operation | `OPEN`, `LIST`, or `STATUS` |
| 12 | 4 | request_id | Client-selected correlation ID |
| 16 | 32 | token | Random pairing token bytes |
| 48 | 16 | device_id | App-assigned persistent UUID; all-zero selects the first permitted serial port |
| 64 | 4 | baud | Requested UART baud |
| 68 | 1 | data_bits | Normally 8 |
| 69 | 1 | stop_bits | 1 or 2 |
| 70 | 1 | parity | None, odd, even, mark, or space enum |
| 71 | 1 | flags | Initial DTR/RTS flags |

The protocol does not carry device aliases in the hot request. Aliases are local
configuration mapped to a 16-byte device UUID. The reserved all-zero UUID is the
default automatic selector: Android resolves each `OPEN` to the first supported
port that has USB permission, or reports that permission/device attachment is
needed. This keeps the request constant-sized while allowing unplugged installs
to attach later without rewriting Termux configuration.

### 6.3 Response

The fixed response header contains:

- Eight-byte magic.
- Protocol version.
- Numeric status.
- Echoed request ID.
- Unsigned message length.
- Reserved field, required to be zero in version 1.

An optional bounded UTF-8 diagnostic follows the header. Status values include:

- `OK`
- `BAD_MAGIC`
- `UNSUPPORTED_VERSION`
- `UNAUTHORIZED`
- `BAD_REQUEST`
- `DEVICE_NOT_FOUND`
- `PERMISSION_REQUIRED`
- `DEVICE_BUSY`
- `USB_ERROR`
- `INTERNAL_ERROR`

For a successful `OPEN`, the first byte following the optional response message is the first raw serial byte. No additional message boundaries exist.

### 6.4 LIST and STATUS

`LIST` and `STATUS` use short-lived connections. Their successful response payload is a versioned sequence of length-prefixed binary records. These records may contain device UUID, VID, PID, USB port index, driver type, permission state, open state, serial number, product name, and alias.

The control record specification will live in `docs/PROTOCOL.md`. Unknown record fields must be skippable using record lengths. The raw `OPEN` stream is deliberately not extensible in-band; future control features require another control connection.

### 6.5 Pairing and Local Security

- The app generates 32 cryptographically random bytes.
- The UI displays a copyable hexadecimal token and complete Termux pairing command.
- The Termux configuration stores the decoded token in a user-only file with mode `0600`.
- Comparisons in the app use a constant-time function.
- Authentication failures reveal no device information.
- Token regeneration immediately invalidates new connections using the old token; the UI warns before disconnecting existing sessions.
- Loopback-only binding is validated after socket creation and covered by tests.

The token prevents another ordinary local app with network permission from casually opening the printer MCU. It is not a defense against root or a compromised Termux/app process.

## 7. Native Termux Bridge

### 7.1 Process Model

The final daemon will load one configuration file and manage all configured MCUs in one process. Each MCU state machine contains:

- Device UUID and human alias.
- Serial settings.
- PTY master descriptor and stable slave link.
- TCP descriptor or reconnect state.
- Two fixed directional buffers.
- Counters and timestamps.

The event loop uses `poll` initially for portability and simplicity. `epoll` may replace it only if benchmarks show meaningful benefit with the small expected descriptor count.

### 7.2 Data Path

Each direction uses a fixed ring or compacting linear buffer. A buffer tracks readable start/end offsets and supports partial writes. The loop never allocates memory after configuration is loaded.

Events are enabled conditionally:

- Read PTY only when the TCP connection is active and the PTY-to-socket buffer has room.
- Write TCP only when PTY-originated data is pending.
- Read TCP only when the socket-to-PTY buffer has room.
- Write PTY only when USB-originated data is pending.

On disconnect:

- Close the TCP descriptor.
- Discard partial data in both directions so stale commands are not replayed after reconnection.
- Keep the PTY master and stable symlink alive.
- Mark the connection fault in counters and logs.
- Reconnect with capped exponential backoff and jitter.
- Do not attempt to tell Klipper that a print is safe to resume.

### 7.3 Buffering and Latency

The initial buffer size is 16 KiB in each direction. It is a starting value, not a permanent tuning claim.

Benchmarks will compare at least 1, 4, 16, and 64 KiB buffers under:

- Frequent 5–64 byte bursts.
- 250000-baud sustained traffic.
- 1-Mbaud full-duplex stress.
- Two concurrent MCUs.
- CPU contention representing Moonraker and web UI activity.

The selected default must minimize p99 latency without causing excessive wakeups or backpressure. No application-level coalescing timer will be added.

### 7.4 PTY and Serial-Control Limitations

Klipper can set baud and raw-mode properties on the PTY, but Android USB settings are established by the binary open request. The daemon configuration is the source of truth and the installer will keep Klipper's `baud` value synchronized.

DTR/RTS transitions made later by pyserial are not reliably observable through a PTY. Version 1 supports initial DTR/RTS values but requires `restart_method: command`. Boards that require Arduino or Cheetah reset sequences are documented as unsupported until a control-channel extension or narrow Klipper integration is designed.

### 7.5 Diagnostics

Per-device counters include:

- Bytes read from and written to Klipper.
- Bytes read from and written to Android.
- Read and write system calls.
- Short writes and buffer-full events.
- TCP connects, disconnects, and authentication failures.
- Current and maximum buffered bytes.
- Connection and last-activity timestamps.

The daemon exposes a local status command through a mode-`0600` Unix socket within the Termux private directory. This socket is for CLI diagnostics only and is not cross-application IPC.

## 8. Android Companion App

### 8.1 Technology and Compatibility

- Kotlin Android application.
- `minSdk 24`.
- Current compile/target SDK.
- Lightweight XML layouts and ViewBinding; no Compose requirement.
- Pinned `usb-serial-for-android` release with Gradle dependency verification.
- No analytics, advertising, cloud account, or Internet server access.

Compatibility branches include:

- API 24–25 service start followed immediately by `startForeground`.
- API 26+ notification channels and `startForegroundService` where needed.
- API 31+ foreground-service start restrictions and PendingIntent mutability flags.
- API 33+ notification runtime permission UX.
- API 34+ connected-device foreground-service type and permission declarations.

### 8.2 USB Discovery and Identity

The app enumerates USB devices and lets the user grant permission and assign aliases. Each configured port receives a random persistent UUID used by the bridge protocol.

Persisted matching uses, in order:

1. USB serial number plus VID/PID and port index.
2. VID/PID plus a user-confirmed single-device rule.
3. Explicit reassignment after attach.

The app never silently selects among multiple identical serial-less devices. Android bus/device paths are not treated as stable identity.

### 8.3 USB Workers

Each open serial port has:

- One worker blocking on USB reads into a reusable byte array and writing to its socket.
- One worker blocking on socket reads into another reusable byte array and writing to USB.
- A small immutable session configuration.
- Atomic counters sampled by the UI.

There is no per-transfer coroutine launch, wrapper object, logging statement, string formatting, or packet decode. Logging is limited to lifecycle transitions and sampled diagnostics.

If the selected library API internally allocates per read, either use its lower-level reusable-buffer API or contribute/maintain the smallest isolated adapter needed to remove that allocation. This must be confirmed with Android Studio allocation profiling.

### 8.4 Foreground Service and Power

The bridge runs as a foreground service while enabled. It holds a partial wake lock only while at least one configured USB/TCP bridge is active. The notification shows:

- Service state.
- Number of attached USB ports.
- Number of active Termux streams.
- Fault/permission state.
- An action to open the app and an explicit stop action.

The notification is updated on state transitions, not on every traffic sample. Users are instructed to exempt both Termux and the companion app from battery optimization. OEM task killing remains a documented reliability risk.

### 8.5 App Screens

The main screen contains:

1. **Service card**
   - Start/stop state.
   - Listening address and port.
   - Pairing state and token regeneration.
   - Battery-optimization warning.

2. **USB device cards**
   - Alias and persistent ID suffix.
   - Manufacturer/product/serial where available.
   - VID/PID, port index, and selected driver.
   - Permission, USB-open, Termux-connected, and traffic-active indicators.
   - Requested serial settings.
   - TX/RX lamps that decay after a short visible interval.
   - Current TX/RX rate, total bytes, uptime, reconnect count, and errors.

3. **Setup/help card**
   - Copyable pairing command.
   - Missing-permission action.
   - Link to local troubleshooting instructions.

Visible rates refresh once per second. Sampling stops when the activity is not visible. Traffic lamps and counters must not alter the forwarding path beyond atomic increments.

### 8.6 Error Behavior

- USB detach closes the corresponding TCP data connection immediately.
- Permission loss changes the device card state and requires explicit user action unless Android grants permission through the configured attach intent.
- USB read/write failure closes the port, records the numeric and human-readable error, and avoids a tight reopen loop.
- Invalid clients receive a bounded protocol error and are disconnected.
- The app never interprets Klipper messages or issues printer commands.

## 9. Native Termux Installer

### 9.1 User Entry Points

The convenient entry point is:

```sh
curl -fsSL https://raw.githubusercontent.com/OWNER/REPOSITORY/main/installer/install.sh | \
  KAB_REPOSITORY=https://github.com/OWNER/REPOSITORY.git bash
```

Flags are supported with:

```sh
curl -fsSL URL/install.sh | bash -s -- --channel stable --ui mainsail
```

A safer documented workflow downloads the script and checksum, verifies, displays, and then executes it. Documentation must state plainly that a direct curl-to-shell command cannot independently authenticate the first downloaded script.

### 9.2 Defaults and Options

Default components:

- Native Klipper.
- Native Moonraker.
- Mainsail static files.
- nginx on port 8080.
- Native bridge daemon.
- `termux-services` runit definitions.

Supported options include:

- `--klipper-only`
- `--without-ui`
- `--ui mainsail|none`
- `--channel stable|edge`
- `--non-interactive`
- `--instance NAME`
- `--port NUMBER`
- `--repair`
- `--update`

The default stable channel uses a repository manifest containing tested upstream commit IDs and artifact checksums. Edge uses current upstream branches and is clearly marked unsupported for unattended printers.

### 9.3 Environment Validation

Before modification, the installer verifies:

- It is running under the real Termux prefix, not a generic Linux/proot environment.
- Android SDK level is at least 24.
- The Termux package source is supported and package metadata can refresh.
- Sufficient free storage exists.
- The target paths are owned by the current Termux UID.
- Existing installations and user configuration are detected.

The installer never invokes `sudo`, systemd, writes outside Termux-private locations, or overwrites `printer.cfg`.

### 9.4 Installation Operations

1. Install build/runtime dependencies with `pkg`.
2. Download or update pinned Klipper and Moonraker revisions.
3. Create component-specific Python virtual environments.
4. Install Python requirements and compile Klipper's C helper natively against Android/Bionic.
5. Build or install the native bridge for the current Termux ABI.
6. Download a checksummed Mainsail release.
7. Generate nginx configuration for port 8080 and Termux-private paths.
8. Create the standard `~/printer_data/{config,logs,gcodes,database}` layout.
9. Generate examples without replacing existing files.
10. Install runit services and helper commands.
11. Run a diagnostic self-test before enabling services.
12. Prompt for the app pairing token and selected device UUID when available.

### 9.5 Service Model

Runit services:

- `klipper-android-bridge`
- `klipper`
- `moonraker`
- `nginx`

The installed `klipper-android-runner` is the single user-facing control point
for starting, stopping, restarting, and observing this set. It ensures
`runsvdir` is alive and then delegates supervision to runit; it is not another
long-lived monitor. New service directories contain a `down` marker so a first
install cannot start against incomplete printer or bridge configuration.

The companion APK may invoke only this fixed runner through Termux's exported,
permission-protected `RunCommandService`. The manifest declares the Termux
permission and package visibility, the installer enables
`allow-external-apps`, and the user must grant the additional permission in
Android settings. Commands are background executions, so they neither require
nor create a visible terminal session.

The Klipper run script waits for its configured PTY link before executing Klippy. It has a bounded wait with useful status output rather than spinning. The bridge service starts independently and maintains PTYs across Android-service reconnects.

Termux wake-lock acquisition and release are explicit service lifecycle operations. Installation also explains that Termux itself must be allowed to run in the background. Optional Termux:Boot integration is deferred until the manual-start path is proven reliable.

### 9.6 Idempotence and Recovery

- Every generated file is recorded in an installation manifest.
- Configuration changes use templates plus clearly delimited managed files.
- Existing user configuration is preserved.
- Updates create timestamped configuration backups.
- Interrupted downloads use temporary files and atomic rename.
- Git repositories are only advanced after dependency preparation succeeds.
- Failed updates leave the previous runnable version selectable.
- `doctor` checks dependencies, services, paths, virtual environments, PTYs, app reachability, authentication, device status, and recent logs.

Removal is not part of the first curl command. A later explicit uninstall command must list affected paths and require confirmation before deleting anything.

## 10. Performance Plan

### 10.1 What Matters

Klipper serial traffic consists of timing-sensitive command and response exchanges, often in short bursts. Absolute throughput is modest, but scheduling delays, buffering, and host stalls can trigger retransmissions or shutdowns. The design therefore prioritizes tail latency and predictable availability over headline bandwidth.

### 10.2 Instrumentation

Instrumentation must measure without logging each transfer:

- Monotonic timestamps at PTY ingress/egress and Android socket/USB boundaries in benchmark builds.
- Byte and syscall/USB-transfer counts.
- Buffer occupancy high-water marks.
- Reconnect and error counts.
- Process CPU time and context switches.
- Android heap allocations during a steady-state trace.
- Round-trip p50, p95, p99, and maximum latency.

Detailed timestamp tracing is compile-time or runtime disabled in normal releases. Production counters remain aggregate atomics.

### 10.3 Initial Acceptance Targets

On the oldest supported physical phone:

- No unexplained byte loss during an eight-hour idle/traffic test.
- One hour of 1-Mbaud full-duplex synthetic traffic without corruption.
- Less than 5 ms added p99 loopback bridge round-trip latency under normal Klipper load.
- Less than 10% of one CPU core for one 250000-baud MCU in steady state.
- No steady-state heap allocations in project-owned forwarding loops.
- No unbounded queue growth under backpressure.
- Two MCUs remain connected during simultaneous sustained traffic.

These are engineering targets, not claims. Results will be recorded by phone model, Android release, MCU/adapter, hub, baud, build version, and power state.

### 10.4 Benchmark Cases

- Echo fixture with deterministic pseudo-random binary data.
- 5, 16, 32, 64, and 256-byte request/response bursts.
- Sustained one-way and full-duplex streams.
- Deliberately slow PTY and USB consumers to validate backpressure.
- Screen on/off and Doze transitions.
- Moonraker API polling and Mainsail activity in parallel.
- One and two MCU connections.
- USB detach during queued traffic.

## 11. Test Strategy

### 11.1 Native Unit Tests

- Wire-structure sizes and byte order.
- Hex token and UUID parsing.
- Authentication request construction.
- Partial read/write handling.
- Ring-buffer wrap and capacity behavior.
- Malformed and oversized responses.
- Backoff bounds and reset.
- Configuration validation.
- Refusal to replace a non-symlink PTY target.

### 11.2 Native Integration Tests

A fake Android TCP server will:

- Validate the binary request.
- Return each error status.
- Echo arbitrary binary streams.
- Fragment response headers and payloads.
- Apply backpressure.
- Disconnect mid-write.

Tests open the published PTY slave as a Klipper-like client and verify byte-exact traffic, reconnect behavior, and stale-buffer discard.

### 11.3 Android Unit and Instrumentation Tests

- Request parsing and bounded diagnostics.
- Constant-time token validation behavior.
- Device UUID mapping and ambiguity refusal.
- Serial-parameter validation.
- Session exclusivity.
- USB detach and permission transitions.
- Foreground-service behavior on representative SDK levels.
- Statistics sampling without per-transfer UI work.
- Loopback-only bind verification.

USB driver behavior requires physical hardware tests; emulator success is insufficient.

### 11.4 Installer Tests

- Clean native Termux installation.
- Rerun without changes.
- Interrupted download and interrupted Python dependency build.
- Upgrade and rollback.
- Existing `printer.cfg` and existing Klipper checkout.
- Unsupported Android and proot detection.
- Paths containing spaces where supported, or an explicit early rejection where not.
- Missing app, invalid token, no USB permission, and no attached device.
- Service restart and log availability.

### 11.5 Hardware Matrix

Minimum initial matrix:

| Category | Required examples |
|---|---|
| Android | API 24/25 phone, API 26 phone, current Android phone |
| Native USB | At least one STM32 or RP2040 CDC-ACM Klipper board |
| USB-UART | At least two families among CH340, CP210x, and FTDI |
| Topology | Direct OTG and powered hub |
| MCU count | One device and two simultaneous devices |
| Power state | Screen-on, screen-off, charging where hardware permits |

A release cannot claim Android 7 support until the API 24/25 physical row passes a representative print and eight-hour test.

## 12. Safety and Failure Modes

- Klipper/MCU safety shutdown remains the authority when communication is lost.
- The bridge closes failed sessions promptly instead of concealing loss with indefinite buffering.
- Pending data is discarded on reconnect.
- No automatic print resume is implemented.
- The installer warns that phones, OTG adapters, batteries, and consumer Android firmware are less deterministic than purpose-built SBCs.
- A powered hub is recommended to avoid phone power limits and printer-board brownouts.
- Documentation warns about USB back-powering and simultaneous-charge incompatibilities.
- Android thermal throttling, low-memory killing, battery aging, Wi-Fi sleep, and OEM background restrictions are tracked as operational risks.

## 13. Delivery Phases

### Phase 0: Protocol and Host Harness

- Finalize protocol version 1 and checked C/Kotlin layouts.
- Complete the C PTY bridge against a fake TCP server.
- Add binary correctness, reconnect, backpressure, and latency tests.
- Exit criterion: byte-exact eight-hour host-only soak test.

### Phase 1: Android CDC Prototype

- Build the foreground service, pairing, device enumeration, and CDC-ACM path.
- Connect a native USB Klipper board.
- Implement basic device/status UI and counters.
- Exit criterion: Klipper identifies the MCU and completes restart/idle tests.

### Phase 2: Common UART Families and Multiple MCUs

- Enable and validate CH340/CH341, CP210x, FTDI, and PL2303 drivers.
- Complete persistent UUID/alias handling and ambiguity UX.
- Validate two simultaneous MCUs.
- Exit criterion: supported hardware matrix passes without protocol changes.

### Phase 3: Native Termux Stack Installer

- Install Klipper, Moonraker, Mainsail, nginx, bridge, and runit services.
- Add stable manifest, update/rollback, doctor, and configuration preservation.
- Exit criterion: clean phone reaches Mainsail and a ready Klipper instance from the documented one-liner plus Android permission/pairing actions.

### Phase 4: Performance and Reliability Release Gate

- Profile allocations, CPU, latency, wakeups, and buffer sizes.
- Test API 24, API 26, and current Android physical phones.
- Run representative prints and fault injection.
- Exit criterion: acceptance targets are met or revised with measured justification and documented limitations.

### Phase 5: Deferred Transports

- Serial bootloader-entry helper.
- Katapult/DFU flashing design, likely as explicit Android-side protocol adapters or a separate authorized native bridge path.
- USB-CAN feasibility prototype. Unmodified Klipper normally expects SocketCAN, so this may require a narrow upstreamable Klipper userspace-CAN transport or root/kernel support.

## 14. Key Roadblocks Requiring Validation

1. **Native Python dependencies in Termux:** current Klipper and Moonraker dependency pins must compile against the Termux Python/Bionic combination selected by the stable manifest.
2. **Android 7 hardware behavior:** API compatibility appears straightforward, but old vendor USB stacks and background policies require real phones.
3. **USB permission after reconnect:** behavior varies depending on attach intent, default-app selection, and vendor firmware.
4. **DTR/RTS reset modes:** PTY transport cannot transparently reproduce every reset ioctl.
5. **Serial-less identical MCUs:** no stable automatic identity is available after physical reordering.
6. **Power topology:** many phones cannot charge while acting as USB host, and some printer boards or hubs back-power the phone.
7. **OEM process management:** foreground service and wake locks reduce but do not eliminate forced termination.
8. **Moonraker/web stack overhead:** native operation avoids proot, but memory and CPU must still be measured on low-RAM phones.
9. **USB-CAN:** Android userspace USB does not automatically create the SocketCAN interface expected by Klipper.

## 15. Definition of the First Usable Release

The first release is complete when a user can:

1. Install the companion APK on an Android 7+ phone.
2. Run the documented Termux installer.
3. Connect and authorize a supported printer controller.
4. Assign it an alias and pair Termux using the app-provided command.
5. Put the generated PTY path in `printer.cfg`.
6. Start the managed services and open Mainsail at the phone's port 8080 address.
7. Complete a representative print while the screen is off.
8. See USB, Termux, and traffic status plus useful TX/RX/error counters in the app.
9. Receive a safe Klipper communication failure if USB, the app, or the bridge is forcibly stopped.

The release notes must list exactly which phones, Android versions, MCU boards, USB-UART families, hubs, and baud rates were physically verified.
