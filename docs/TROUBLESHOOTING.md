# Troubleshooting

Start with:

```sh
kabctl doctor
kabctl status
```

## Android app sees no USB device

- Confirm the phone supports USB host mode.
- Try a powered OTG hub and a known data-capable cable.
- Check that another Android USB application is not the default owner.
- Reconnect the board and grant permission in the companion app.

## Bridge reports unauthorized

Regenerate or copy the token from the app and place all 64 hexadecimal characters in `bridge.conf`. Restart `klipper-android-bridge` afterward.

## No printer MCU is attached yet

Run **Configure Termux bridge** with no USB device attached. The app writes an
`offline` device entry, and the native bridge publishes Klipper's PTY without
trying to open a USB device. Start the whole stack normally. Klipper will report
that its MCU is unavailable, but Moonraker and Mainsail should remain reachable.
After attaching the board and granting USB access, configure the bridge again
and restart the stack.

`kabctl doctor` checks both Moonraker directly on port 7125 and through the
Mainsail nginx proxy. If either fails, inspect `kabctl logs moonraker` and
`~/printer_data/logs/nginx-error.log`.

## Klipper cannot open the PTY

- Confirm the Android service is running.
- Confirm `bridge.conf` passes `klipper-android-bridge --check-config`.
- Check that `$PREFIX/var/run/klipper-android/main` is a symlink.
- Use the same PTY path in `printer.cfg`.

## MCU disconnects with the screen off

Disable battery optimization for Termux and the companion app. Some vendors impose additional background restrictions even on foreground services. Keep the phone powered and test the exact model before unattended printing.

## App cannot start the Termux stack

- Install Termux before granting the bridge app's Additional permission named
  `RUN_COMMAND`.
- Confirm `allow-external-apps = true` is present in
  `~/.termux/termux.properties`, then run `termux-reload-settings`.
- Or use **Copy enable command and open Termux** in the setup wizard, paste the
  copied command, and press Enter.
- Confirm `~/.local/bin/klipper-android-runner status` works inside Termux.
- The current bridge integration targets the official `com.termux` package;
  forks using another application ID are not discovered.

## Installer does not appear in Termux

The installer is requested as a foreground terminal session and the bridge app
also opens Termux after sending it. On Android 10 and newer, the system can still
block a terminal activity started by Termux's background service. If Termux opens
without the installer session, tap the Termux command notification. Alternatively,
allow Termux to **Display over other apps** in Android's app settings. This special
permission belongs to Termux and is not required for background stack controls.

## Identical boards switch identities

USB devices without unique serial numbers cannot be safely matched after physical reordering. Connect one at a time and recreate the app profile, or use adapters/firmware with unique USB serial descriptors.

## Reset does not work

Use `restart_method: command`. Arduino/Cheetah DTR/RTS reset sequences are not supported through the version 1 PTY bridge.
