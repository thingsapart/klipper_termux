#!/usr/bin/env python3
"""Measure PTY-to-loopback bridge latency with a fake raw USB echo service."""

from __future__ import annotations

import argparse
import os
import pathlib
import socket
import statistics
import struct
import subprocess
import tempfile
import threading
import time
import tty

MAGIC = b"KLIPUSB\0"
TOKEN = bytes(range(32))
DEVICE = bytes.fromhex("00112233445566778899aabbccddeeff")


def exact(connection: socket.socket, count: int) -> bytes:
    value = bytearray()
    while len(value) < count:
        block = connection.recv(count - len(value))
        if not block:
            raise EOFError
        value.extend(block)
    return bytes(value)


class EchoService(threading.Thread):
    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.port = self.listener.getsockname()[1]
        self.error: Exception | None = None

    def run(self) -> None:
        try:
            connection, _ = self.listener.accept()
            with connection:
                request = exact(connection, 72)
                request_id = struct.unpack(">I", request[12:16])[0]
                connection.sendall(struct.pack(">8sHHIHH", MAGIC, 1, 0, request_id, 0, 0))
                while True:
                    data = connection.recv(16384)
                    if not data:
                        break
                    connection.sendall(data)
        except Exception as error:
            self.error = error
        finally:
            self.listener.close()


def percentile(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * quantile))]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bridge", type=pathlib.Path)
    parser.add_argument("--iterations", type=int, default=2000)
    parser.add_argument("--burst", type=int, default=32)
    arguments = parser.parse_args()
    service = EchoService()
    service.start()

    with tempfile.TemporaryDirectory(prefix="kab-benchmark-") as directory:
        root = pathlib.Path(directory)
        link = root / "mcu"
        config = root / "bridge.conf"
        config.write_text(
            f"server=127.0.0.1\nport={service.port}\ntoken={TOKEN.hex()}\n"
            f"log_interval=0\ndevice=bench,{DEVICE.hex()},250000,8,1,none,none,{link}\n"
        )
        process = subprocess.Popen(
            [str(arguments.bridge.resolve()), str(config)], stderr=subprocess.DEVNULL
        )
        try:
            deadline = time.monotonic() + 5
            while not link.is_symlink() and time.monotonic() < deadline:
                time.sleep(0.01)
            descriptor = os.open(link, os.O_RDWR | os.O_NOCTTY)
            tty.setraw(descriptor)
            payload = bytes(index & 0xFF for index in range(arguments.burst))
            timings: list[float] = []
            started = time.perf_counter()
            for _ in range(arguments.iterations):
                before = time.perf_counter_ns()
                os.write(descriptor, payload)
                received = bytearray()
                while len(received) < len(payload):
                    received.extend(os.read(descriptor, len(payload) - len(received)))
                after = time.perf_counter_ns()
                if received != payload:
                    raise RuntimeError("data corruption")
                timings.append((after - before) / 1_000_000)
            elapsed = time.perf_counter() - started
            os.close(descriptor)
        finally:
            process.terminate()
            process.wait(timeout=3)
    if service.error:
        raise service.error
    print(f"iterations={arguments.iterations} burst={arguments.burst} bytes")
    print(f"p50={statistics.median(timings):.3f} ms")
    print(f"p95={percentile(timings, .95):.3f} ms")
    print(f"p99={percentile(timings, .99):.3f} ms")
    print(f"max={max(timings):.3f} ms")
    print(f"round_trips_per_second={arguments.iterations / elapsed:.1f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

