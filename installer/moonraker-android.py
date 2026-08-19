"""Launch Moonraker with narrowly scoped Android filesystem compatibility."""

import os
import runpy


_original_scandir = os.scandir


def _android_compatible_scandir(path):
    try:
        return _original_scandir(path)
    except PermissionError:
        # Android exposes this directory through sysfs but SELinux may deny
        # listing it. Moonraker can fall back to thermal_zone0 when no hwmon
        # entries are returned, and continues to provide all other statistics.
        is_path = isinstance(path, (str, bytes, os.PathLike))
        normalized = os.path.normpath(os.fsdecode(path)) if is_path else ""
        if normalized == "/sys/class/hwmon":
            return iter(())
        raise


os.scandir = _android_compatible_scandir
runpy.run_module("moonraker", run_name="__main__", alter_sys=True)
