"""Launch Klipper with the narrow PTY compatibility adjustments Android requires."""

import os
import runpy
import shlex
import sys


_original_chmod = os.chmod


if not hasattr(os, "getloadavg"):
    def _android_getloadavg():
        """Return Linux load averages omitted by Termux's Python build."""
        try:
            with open("/proc/loadavg", "r", encoding="ascii") as load_file:
                values = load_file.read().split()[:3]
            if len(values) == 3:
                return tuple(float(value) for value in values)
        except (OSError, ValueError):
            pass
        # Klipper uses this for informational statistics only. Do not terminate
        # printer control merely because a vendor kernel hides proc load data.
        return (0.0, 0.0, 0.0)

    os.getloadavg = _android_getloadavg


def _android_compatible_chmod(path, mode, *args, **kwargs):
    try:
        return _original_chmod(path, mode, *args, **kwargs)
    except PermissionError:
        # Android owns /dev/pts and denies chmod even to the process that opened
        # the PTY. Klipper already owns the open descriptors; this chmod only
        # broadens access for its optional G-code terminal endpoint.
        is_path = isinstance(path, (str, bytes, os.PathLike))
        resolved = os.path.realpath(os.fsdecode(path)) if is_path else ""
        if mode == 0o660 and resolved.startswith("/dev/pts/"):
            return None
        raise


os.chmod = _android_compatible_chmod

# Klipper deliberately asks pyserial for an advisory exclusive flock. Android's
# SELinux policy denies flock() on an app-owned PTY slave, even though the same
# Termux process may open, configure, read, and write it. Suppress exclusivity
# only inside our private bridge runtime directory. Real serial devices retain
# upstream Klipper's exclusive-open behavior.
import serial  # noqa: E402

_original_serial = serial.Serial
_bridge_serial_dir = os.path.abspath("@PREFIX@/var/run/klipper-android")


class _AndroidBridgeSerial(_original_serial):
    def _is_bridge_port(self):
        port = self.port
        if isinstance(port, (str, bytes, os.PathLike)):
            port_path = os.path.abspath(os.fsdecode(port))
            return os.path.dirname(port_path) == _bridge_serial_dir
        return False

    def open(self):
        if self._is_bridge_port():
            self._exclusive = None
        return super().open()

    def _set_special_baudrate(self, baudrate):
        # The real baud is configured on Android from bridge.conf. A PTY has no
        # wire rate, and Android does not implement Linux's custom-baud ioctl.
        if self._is_bridge_port():
            return None
        return super()._set_special_baudrate(baudrate)

    def _update_dtr_state(self):
        # PTYs have no modem-control lines. Android applies the configured DTR
        # flag directly to the USB serial driver when opening the remote port.
        if self._is_bridge_port():
            return None
        return super()._update_dtr_state()

    def _update_rts_state(self):
        if self._is_bridge_port():
            return None
        return super()._update_rts_state()


serial.Serial = _AndroidBridgeSerial
klippy = "@HOME@/klipper/klippy/klippy.py"
sys.path.insert(0, os.path.dirname(klippy))

# Android's dynamic linker does not resolve libm symbols implicitly. Upstream
# Klipper's helper link command omits -lm, which leaves functions such as atan2
# unresolved even though compilation succeeds.
#
# Klipper also timestamps serial traffic with CLOCK_MONOTONIC_RAW. Some Android
# vendor kernels return corrupt values for that clock even though
# CLOCK_MONOTONIC remains reliable. A bad host timestamp makes clocksync infer
# impossible MCU frequency changes and eventually schedules a move in the past
# ("Timer too close"). Force only RAW requests to the reliable Android clock;
# all other clock_gettime users retain their requested clock.
import chelper  # noqa: E402

helper_state_dir = "@HOME@/.local/state/klipper-android"
clock_header = os.path.join(helper_state_dir, "android-monotonic-clock.h")
clock_header_contents = """\
#ifndef KLIPPER_ANDROID_MONOTONIC_CLOCK_H
#define KLIPPER_ANDROID_MONOTONIC_CLOCK_H
#include <time.h>
static inline int
klipper_android_clock_gettime(clockid_t clock_id, struct timespec *timestamp)
{
    if (clock_id == CLOCK_MONOTONIC_RAW)
        clock_id = CLOCK_MONOTONIC;
    return clock_gettime(clock_id, timestamp);
}
#define clock_gettime klipper_android_clock_gettime
#endif
"""
os.makedirs(helper_state_dir, exist_ok=True)
try:
    with open(clock_header, "r", encoding="ascii") as header_file:
        installed_clock_header = header_file.read()
except FileNotFoundError:
    installed_clock_header = None
if installed_clock_header != clock_header_contents:
    temporary_header = clock_header + ".new"
    with open(temporary_header, "w", encoding="ascii") as header_file:
        header_file.write(clock_header_contents)
    os.replace(temporary_header, clock_header)

clock_compile_arg = " -include " + shlex.quote(clock_header)
if clock_compile_arg not in chelper.COMPILE_ARGS:
    chelper.COMPILE_ARGS += clock_compile_arg
if not chelper.COMPILE_ARGS.endswith(" -lm"):
    chelper.COMPILE_ARGS += " -lm"
helper_marker = os.path.join(helper_state_dir, "c-helper-android-clock-v1")
helper_library = os.path.join(os.path.dirname(chelper.__file__), "c_helper.so")
if not os.path.exists(helper_marker):
    try:
        os.unlink(helper_library)
    except FileNotFoundError:
        pass
    _original_build_helper = chelper.check_build_c_library

    def _build_android_helper():
        result = _original_build_helper()
        os.makedirs(os.path.dirname(helper_marker), exist_ok=True)
        with open(helper_marker, "w", encoding="ascii") as marker:
            marker.write("CLOCK_MONOTONIC\n-lm\n")
        return result

    chelper.check_build_c_library = _build_android_helper

sys.argv[0] = klippy
runpy.run_path(klippy, run_name="__main__")
