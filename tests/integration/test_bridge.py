#!/usr/bin/env python3
"""End-to-end bridge test with a fake Android USB service."""

from __future__ import annotations

import os
import pathlib
import select
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import tty

MAGIC = b"KLIPUSB\0"
TOKEN = bytes(range(32))
DEVICE_ID = bytes.fromhex("00112233445566778899aabbccddeeff")


def recv_exact(connection: socket.socket, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        part = connection.recv(length - len(result))
        if not part:
            raise EOFError(f"wanted {length} bytes, got {len(result)}")
        result.extend(part)
    return bytes(result)


class FakeUsbService(threading.Thread):
    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.listener = socket.socket()
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.port = self.listener.getsockname()[1]
        self.ready = threading.Event()
        self.finished = threading.Event()
        self.error: BaseException | None = None

    def run(self) -> None:
        try:
            self.ready.set()
            connection, _ = self.listener.accept()
            with connection:
                request = recv_exact(connection, 72)
                magic, version, operation, request_id = struct.unpack(
                    ">8sHHI", request[:16]
                )
                assert magic == MAGIC
                assert version == 1
                assert operation == 1
                assert request[16:48] == TOKEN
                assert request[48:64] == DEVICE_ID
                assert struct.unpack(">I", request[64:68])[0] == 250000
                assert request[68:] == bytes((8, 1, 0, 0))
                connection.sendall(
                    struct.pack(">8sHHIHH", MAGIC, 1, 0, request_id, 0, 0)
                )
                assert recv_exact(connection, 10) == b"from-pty\x00\xff"
                connection.sendall(b"from-usb\x00\xff")
        except BaseException as error:  # Propagate thread failures to the test.
            self.error = error
        finally:
            self.listener.close()
            self.finished.set()


def wait_for_path(path: pathlib.Path, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_symlink():
            return
        time.sleep(0.02)
    raise TimeoutError(f"bridge did not publish {path}")


def read_fd_exact(descriptor: int, length: int, timeout: float = 5.0) -> bytes:
    result = bytearray()
    deadline = time.monotonic() + timeout
    while len(result) < length:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"PTY produced {len(result)} of {length} bytes")
        readable, _, _ = select.select([descriptor], [], [], remaining)
        if not readable:
            continue
        result.extend(os.read(descriptor, length - len(result)))
    return bytes(result)


def main() -> int:
    bridge = pathlib.Path(sys.argv[1]).resolve()
    service = FakeUsbService()
    service.start()
    service.ready.wait(2)

    with tempfile.TemporaryDirectory(prefix="kab-integration-") as directory:
        root = pathlib.Path(directory)
        pty_link = root / "mcu"
        config = root / "bridge.conf"
        config.write_text(
            "server=127.0.0.1\n"
            f"port={service.port}\n"
            f"token={TOKEN.hex()}\n"
            "log_interval=0\n"
            f"device=main,{DEVICE_ID.hex()},250000,8,1,none,none,{pty_link}\n",
            encoding="utf-8",
        )
        process = subprocess.Popen(
            [str(bridge), str(config)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            wait_for_path(pty_link)
            descriptor = os.open(pty_link, os.O_RDWR | os.O_NOCTTY)
            try:
                tty.setraw(descriptor)
                assert os.write(descriptor, b"from-pty\x00\xff") == 10
                assert read_fd_exact(descriptor, 10) == b"from-usb\x00\xff"
            finally:
                os.close(descriptor)
            assert service.finished.wait(3), "fake service did not finish"
            if service.error:
                raise service.error
        except BaseException:
            process.terminate()
            process.wait(timeout=3)
            print(process.stderr.read(), file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        if process.returncode not in (0, -15):
            print(process.stderr.read(), file=sys.stderr)
            return 1
    print("test_bridge: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
