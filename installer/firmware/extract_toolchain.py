#!/usr/bin/env python3
"""Safely extract a trusted, checksum-verified toolchain without hard links."""

import os
import posixpath
import shutil
import stat
import sys
import tarfile
import time
from pathlib import Path, PurePosixPath
from typing import Optional


def stripped_path(name: str) -> Optional[PurePosixPath]:
    parts = PurePosixPath(name).parts
    if len(parts) <= 1:
        return None
    result = PurePosixPath(*parts[1:])
    if result.is_absolute() or ".." in result.parts:
        raise ValueError(f"unsafe archive path: {name}")
    return result


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: extract_toolchain.py ARCHIVE DESTINATION", file=sys.stderr)
        return 2
    archive, destination = sys.argv[1], Path(sys.argv[2])
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:xz") as source:
        members = source.getmembers()
        total_bytes = sum(member.size for member in members if member.isfile())
        copied_bytes = 0
        last_progress = 0.0
        print(
            f"Extracting {len(members)} toolchain entries "
            f"({total_bytes / (1024 * 1024):.0f} MiB)…",
            flush=True,
        )
        for index, member in enumerate(members, start=1):
            relative = stripped_path(member.name)
            if relative is None:
                continue
            target = destination.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if member.isdir():
                target.mkdir(exist_ok=True)
            elif member.issym():
                link = PurePosixPath(member.linkname)
                resolved = posixpath.normpath(str(relative.parent / link))
                if link.is_absolute() or resolved == ".." or resolved.startswith("../"):
                    raise ValueError(f"unsafe symlink target: {member.linkname}")
                target.unlink(missing_ok=True)
                os.symlink(member.linkname, target)
                continue
            elif member.isfile() or member.islnk():
                # tarfile resolves a hard-link member to its source stream. Writing
                # that stream creates an independent file, avoiding Android filesystems
                # that reject link(2) in app-private storage.
                contents = source.extractfile(member)
                if contents is None:
                    raise ValueError(f"archive entry has no contents: {member.name}")
                with contents, target.open("wb") as output:
                    while True:
                        block = contents.read(1024 * 1024)
                        if not block:
                            break
                        output.write(block)
                        copied_bytes += len(block)
                        now = time.monotonic()
                        if now - last_progress >= 0.4:
                            percent = (copied_bytes * 100 / total_bytes) if total_bytes else 100
                            print(
                                f"\r  [{index}/{len(members)}] {percent:5.1f}%  {member.name}",
                                end="",
                                flush=True,
                            )
                            last_progress = now
            else:
                raise ValueError(f"unsupported archive entry: {member.name}")
            os.chmod(target, stat.S_IMODE(member.mode))
        print("\r  [complete] Toolchain extracted.                              ", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
