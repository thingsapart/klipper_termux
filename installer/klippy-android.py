"""Launch Klipper with the narrow PTY compatibility adjustments Android requires."""

import os
import runpy
import sys


_original_chmod = os.chmod


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
    def open(self):
        port = self.port
        if isinstance(port, (str, bytes, os.PathLike)):
            port_path = os.path.abspath(os.fsdecode(port))
            if os.path.dirname(port_path) == _bridge_serial_dir:
                self._exclusive = None
        return super().open()


serial.Serial = _AndroidBridgeSerial
klippy = "@HOME@/klipper/klippy/klippy.py"
sys.path.insert(0, os.path.dirname(klippy))

# Android's dynamic linker does not resolve libm symbols implicitly. Upstream
# Klipper's helper link command omits -lm, which leaves functions such as atan2
# unresolved even though compilation succeeds. Rebuild once with explicit libm.
import chelper  # noqa: E402

if not chelper.COMPILE_ARGS.endswith(" -lm"):
    chelper.COMPILE_ARGS += " -lm"
helper_marker = "@HOME@/.local/state/klipper-android/c-helper-linked-libm"
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
            marker.write("-lm\n")
        return result

    chelper.check_build_c_library = _build_android_helper

sys.argv[0] = klippy
runpy.run_path(klippy, run_name="__main__")
