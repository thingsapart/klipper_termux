"""Launch Klipper with the one PTY permission adjustment Android requires."""

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
klippy = "@HOME@/klipper/klippy/klippy.py"
sys.path.insert(0, os.path.dirname(klippy))
sys.argv[0] = klippy
runpy.run_path(klippy, run_name="__main__")
