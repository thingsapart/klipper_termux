# Bridge Protocol Version 1

The companion app listens only on loopback TCP, default port `27831`. Each connection starts with a fixed 72-byte request in network byte order.

| Offset | Bytes | Field |
|---:|---:|---|
| 0 | 8 | `KLIPUSB\0` magic |
| 8 | 2 | Version (`1`) |
| 10 | 2 | Operation (`1` = open, `2` = list, `3` = status) |
| 12 | 4 | Request ID |
| 16 | 32 | Pairing token |
| 48 | 16 | Device UUID |
| 64 | 4 | Baud |
| 68 | 1 | Data bits |
| 69 | 1 | Stop bits |
| 70 | 1 | Parity enum |
| 71 | 1 | Flags: bit 0 DTR, bit 1 RTS |

The response starts with a fixed 20-byte header:

| Offset | Bytes | Field |
|---:|---:|---|
| 0 | 8 | Magic |
| 8 | 2 | Version |
| 10 | 2 | Status |
| 12 | 4 | Echoed request ID |
| 16 | 2 | Diagnostic UTF-8 byte length, maximum 1024 |
| 18 | 2 | Reserved, zero |

After an `OPEN` response with status zero and its optional diagnostic, all remaining bytes are the raw serial stream. There is no framing, escaping, JSON, checksum, or Klipper-message decoding.

Implemented status codes are `OK=0`, `BAD_MAGIC=1`, `UNSUPPORTED_VERSION=2`, `UNAUTHORIZED=3`, `BAD_REQUEST=4`, `DEVICE_NOT_FOUND=5`, `PERMISSION_REQUIRED=6`, `DEVICE_BUSY=7`, `USB_ERROR=8`, and `INTERNAL_ERROR=9`.

`LIST` and `STATUS` numeric operations are reserved in the current app prototype and return `BAD_REQUEST`; the Android UI supplies discovery and statistics until their bounded binary record format is implemented.

